package com.example.dianzicheng.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dianzicheng.data.ble.BleScaleClient
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.BodyAlgorithm
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.BiaModel
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.domain.ScaleModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.round

import com.example.dianzicheng.data.health.HealthConnectManager
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.domain.RememberedScale
import com.example.dianzicheng.domain.ScaleMemoryLogic
import com.example.dianzicheng.domain.normalizeMacAddress
import com.example.dianzicheng.domain.sameMacAddress
import kotlinx.coroutines.flow.first

/** 日志标签，用于在 AppLogger 和 Logcat 中标识来源 */
private const val TAG = "ScaleVM"

/**
 * 称重主界面的 ViewModel，负责：
 * 1. 订阅 BleScaleClient 的体重、稳定状态、阻抗三路 Flow
 * 2. 在合适时机（稳定锁定 / 阻抗到达）触发测量数据保存
 * 3. 维护当前称量的会话状态，防止同一次称量生成重复记录
 * 4. 支持手动选择/绑定家庭成员，重新计算体脂率并更新数据库
 */
class ScaleViewModel(
    private val bleClient: BleScaleClient,
    private val repository: ScaleRepository,
    private val preferenceManager: PreferenceManager? = null,
    private val healthConnectManager: HealthConnectManager? = null
) : ViewModel() {

    /** 对外暴露的 UI 状态，使用 StateFlow 保证只读性 */
    private val _uiState = MutableStateFlow(ScaleUiState())
    val uiState: StateFlow<ScaleUiState> = _uiState.asStateFlow()

    /**
     * 当前称量会话 ID（UUID 字符串）。
     * 在 isStable=true 时生成，在下秤（weight=0 或 isStable=false 后延迟300ms）后清空。
     * 同一会话 ID 用于对数据库进行幂等写入（REPLACE），避免重复记录。
     */
    private var activeSessionId: String? = null

    /**
     * 当前称量会话开始的时间戳（毫秒）。
     * 在 isStable=true 时固定记录，供整个会话期间复用，
     * 确保阻抗补充写入时不会改变测量时间。
     */
    private var activeSessionTimestamp: Long = 0L

    /** 获取有效的会话时间戳，若为 0L 则兜底初始化为当前系统时间 */
    private fun getSessionTimestamp(): Long {
        if (activeSessionTimestamp <= 0L) {
            activeSessionTimestamp = System.currentTimeMillis()
        }
        return activeSessionTimestamp
    }

    /** 当前会话内是否已弹出过"发现新成员"提示，防止重复弹窗打扰用户 */
    private var hasAlertedForCurrentSession = false

    /**
     * 上一次完成锁定的会话 ID。
     * 当用户离秤（activeSessionId 被清空）后，阻抗数据仍可能延迟到达，
     * 此时通过 lastLockedSessionId 找到对应记录进行补充更新。
     */
    private var lastLockedSessionId: String? = null

    /**
     * 当前锁定的体重值（公斤）。
     * 在 isStable=true 时写入，在 weight=0 时不重置（保留至下一次会话开始），
     * 供阻抗延迟到达时使用正确的体重参与计算。
     */
    private var currentLockedWeightKg = 0.0

    /**
     * 当前会话是否曾被中途打断（如踩空、跳下又立刻站回）。
     * 中断后再次稳定一般是全新一次称重；仅在归零后极短时间内重新锁定才允许续接。
     */
    private var sessionInterrupted = false

    /**
     * 当前会话是否已写入数据库。
     * 阻抗到达（真人赤脚测量的确定信号）或下秤时校验通过才会写库；
     * 短暂踩秤/放物品等无效会话不写库，避免历史被无关数据污染。
     */
    private var sessionCommitted = false

    /** 本次上秤时刻（体重首次 ≥3.0kg），用于下秤时计算在秤时长 */
    private var stepOnTimeMs = 0L

    /** 最近一次体重归零的时刻，用于判断再次锁定是否为短时续接 */
    private var lastWeightZeroMs = 0L

    /** 体重不稳定时的延迟清空会话 Job，重新稳定时取消该任务以防误清空 */
    private var clearSessionJob: Job? = null

    companion object {
        /** 归零后该时间窗口内重新锁定且体重接近，仍视为同一次称重（覆盖瞬时抖动） */
        private const val CONTINUATION_AFTER_ZERO_GRACE_MS = 2500L

        /** 同一会话内再次锁定的体重允许波动范围（公斤） */
        private const val WEIGHT_CONTINUATION_TOLERANCE_KG = 2.0

        /** 实时体重超出锁定值超过该幅度时，判定该锁定为爬升过程中的过早误报，作废会话 */
        private const val SUPERSEDE_THRESHOLD_KG = 3.0
    }

    /**
     * 当前选中的电阻抗（BIA）体成分估算模型。
     *
     * 来自 DataStore，用户在「我的」页切换后实时生效：下一次称重即按新模型计算体脂率。
     * 历史记录不会被追溯重算。
     */
    private var selectedBiaModel: BiaModel = BiaModel.DEFAULT

    init {
        checkBluetoothAndLocationState()
        observeBle()        // 开始监听 BLE 数据流
        observeMembers()    // 开始监听家庭成员列表
        observePreferences()// 开始监听已配对设备偏好
    }

    /**
     * 监听家庭成员列表变化。
     *
     * 注意：只在用户已明确选择成员时，才同步更新该成员的最新信息（如修改了身高）；
     * 若未选择任何成员（selectedMember == null），保持 null 不自动绑定，
     * 以免干扰后续的智能自动匹配逻辑。
     */
    private fun observeMembers() {
        viewModelScope.launch {
            repository.getMembers().collect { members ->
                _uiState.update { state ->
                    val currentSelected = state.selectedMember
                    val updatedSelected = if (currentSelected != null) {
                        // 同步已选成员的最新信息（如身高、出生日期变更）
                        members.firstOrNull { it.id == currentSelected.id } ?: currentSelected
                    } else if (members.size == 1) {
                        // 系统中仅有唯一家庭成员（如默认"自己"）时，自动选中，无歧义
                        members.first()
                    } else {
                        null // 多成员时保持智能自动匹配模式
                    }
                    state.copy(
                        availableMembers = members,
                        selectedMember = updatedSelected
                    )
                }
                // 家庭成员变化后同步 B1 秤端用户档案（年龄/性别/目标体重）
                syncAfuB1UserProfile(_uiState.value.selectedMember ?: members.firstOrNull())
            }
        }
    }

    /**
     * 用户手动选择成员（或切换为智能匹配模式）。
     *
     * 若当前已有测量结果且选择了具体成员，立即用新成员参数重新计算并保存。
     *
     * @param member 选择的成员；传 null 表示切换为智能自动匹配
     */
    fun selectMember(member: FamilyMember?) {
        _uiState.update { it.copy(selectedMember = member) }
        syncAfuB1UserProfile(member ?: _uiState.value.availableMembers.firstOrNull())
        val currentMeas = _uiState.value.currentMeasurement
        if (currentMeas != null && member != null) {
            bindCurrentMeasurementToMember(member)
        }
    }

    /**
     * 将所选家庭成员的身体参数同步给薄荷健康 B1（AFU-BH-TZ-B1）。
     *
     * 秤端需要年龄 / 性别 / 目标体重来完成自身换算，并为下一次称重「武装」阻抗测量（档案 0x5B）。
     * 本应用的体脂率仍由本地 BIA 算法（[BodyAlgorithm]）计算，这里的档案只用于驱动秤端行为。
     */
    private fun syncAfuB1UserProfile(member: FamilyMember?) {
        bleClient.afuB1UserAge = member?.let { ageFromBirthDate(it.birthDateEpochMs) } ?: 30
        bleClient.afuB1UserIsMale = member?.sex == Sex.MALE
        bleClient.afuB1UserTargetKg = member?.referenceWeightKg?.takeIf { it > 0.0 } ?: 60.0
    }

    /** 由出生日期毫秒时间戳推算年龄（岁），异常值回退为 30 岁。 */
    private fun ageFromBirthDate(birthDateEpochMs: Long): Int {
        if (birthDateEpochMs <= 0L) return 30
        val millisPerYear = 365.2425 * 24 * 60 * 60 * 1000L
        val age = ((System.currentTimeMillis() - birthDateEpochMs) / millisPerYear).toInt()
        return age.coerceIn(10, 99)
    }

    /**
     * 将当前测量结果重新绑定到指定成员，重新计算 BIA 指标并更新数据库。
     * 常用于用户点击"重选/绑定成员"后的回调。
     *
     * @param member 要绑定的目标成员
     */
    fun bindCurrentMeasurementToMember(member: FamilyMember) {
        val currentMeas = _uiState.value.currentMeasurement ?: return
        viewModelScope.launch {
            try {
                val boundResult = repository.bindMeasurementToMember(
                    measurement = currentMeas,
                    member = member,
                    biaModel = selectedBiaModel
                )
                // 手动绑定即用户明确要保留这条记录，视为已写库
                sessionCommitted = true
                _uiState.update {
                    it.copy(
                        currentMeasurement = boundResult,
                        selectedMember = member
                    )
                }
                scheduleHealthSync(boundResult)
            } catch (e: Exception) {
                android.util.Log.e("ScaleViewModel", "Error binding measurement to member", e)
            }
        }
    }

    /**
     * 启动所有 BLE 数据 Flow 的监听。
     *
     * 四路 Flow 各自在独立协程中运行：
     * - connectionState：连接状态变更
     * - weight：实时体重数值
     * - isStable：体重是否稳定锁定
     * - impedance：体内阻抗（BIA 测量值）
     * - discoveredDevice：扫描发现的设备名称和 MAC
     */
    private fun observeBle() {
        // ── 连接状态 ──────────────────────────────────────────────────────────
        viewModelScope.launch {
            bleClient.connectionState.collect { state ->
                AppLogger.i(TAG, "BLE 连接状态变更: $state")
                _uiState.update { it.copy(connection = state) }
            }
        }

        // ── 实时体重 ──────────────────────────────────────────────────────────
        viewModelScope.launch {
            bleClient.weight.collect { weight ->
                val shouldClearImpedance = weight < 3.0 || !_uiState.value.isStable
                // 【性能保护】界面只展示到 0.01kg。秤在动态示数阶段每秒上报十余次、
                // 且常在末位反复抖动，若原值直接写入 uiState，Dashboard 会按包频率整页重组。
                // 这里先按显示精度取整，值未变化时 MutableStateFlow 会自动合并、不触发重组。
                // 注意：仅影响 UI 显示字段，入库使用的仍是 BLE 原始值。
                val displayWeight = round(weight * 100.0) / 100.0
                _uiState.update {
                    if (shouldClearImpedance) {
                        it.copy(liveWeightKg = displayWeight, impedanceOhm = 0.0)
                    } else {
                        it.copy(liveWeightKg = displayWeight)
                    }
                }
                if (weight >= 3.0) {
                    // 记录上秤时刻（仅在未记录时），供下秤时计算在秤时长
                    if (stepOnTimeMs == 0L) stepOnTimeMs = System.currentTimeMillis()

                    // 【核心修复】：若秤正处于稳定锁定状态，且示数发生更新（如秤端微调校准 5.50 -> 5.10）
                    // 因 StateFlow(isStable=true) 在值未变化时不会重复触发 isStable.collect，
                    // 必须在此处响应体重变动，同步更新 currentLockedWeightKg 并更新数据库，防止历史记录残留前置瞬态值！
                    if (bleClient.isStable.value && lastLockedSessionId != null && abs(weight - currentLockedWeightKg) >= 0.005) {
                        AppLogger.i(TAG, "锁定状态下示数校准更新: ${String.format("%.2f", currentLockedWeightKg)}kg -> ${String.format("%.2f", weight)}kg, sessionId=${lastLockedSessionId?.take(8)}")
                        currentLockedWeightKg = weight
                        val sessionId = lastLockedSessionId!!
                        activeSessionId = sessionId
                        processMeasurement(
                            sessionId = sessionId,
                            timestamp = getSessionTimestamp(),
                            commitToDb = true,
                            explicitWeight = weight
                        )
                    }

                    // 过早锁定作废：秤可能在体重爬升过程中误报"稳定"（如爬到 18kg 时），
                    // 若实际体重随后显著超出锁定值，说明那次锁定是中间值，作废对应会话，
                    // 避免产生一条远低于真实体重的垃圾记录
                    val supersededId = lastLockedSessionId
                    if (supersededId != null && weight > currentLockedWeightKg + SUPERSEDE_THRESHOLD_KG) {
                        AppLogger.i(TAG, "检测到过早锁定 (锁定 ${String.format("%.2f", currentLockedWeightKg)}kg < 实际 ${String.format("%.2f", weight)}kg)，作废会话 ${supersededId.take(8)}")
                        if (sessionCommitted) {
                            // 垃圾记录可能已被阻抗抢先写入数据库：撤销它
                            viewModelScope.launch {
                                try {
                                    repository.deleteMeasurementById(supersededId)
                                } catch (e: Exception) {
                                    AppLogger.e(TAG, "撤销过早锁定记录失败: ${e.message}")
                                }
                            }
                        }
                        healthSyncJob?.cancel()
                        activeSessionId = null
                        lastLockedSessionId = null
                        sessionCommitted = false
                        _uiState.update { it.copy(currentMeasurement = null) }
                    }
                } else if (weight <= 0.0) {
                    // 体重归零，说明用户已离秤：仅当锁定的体重 >= 3.0kg 且尚未写库时才兜底提交
                    val sessionId = activeSessionId ?: lastLockedSessionId
                    if (sessionId != null && !sessionCommitted && currentLockedWeightKg >= 3.0) {
                        AppLogger.i(TAG, "下秤，兜底提交本次测量: sessionId=${sessionId.take(8)}")
                        sessionCommitted = true
                        processMeasurement(sessionId, getSessionTimestamp(), commitToDb = true)
                    }
                    activeSessionId = null
                    hasAlertedForCurrentSession = false
                    sessionInterrupted = true
                    stepOnTimeMs = 0L
                    lastWeightZeroMs = System.currentTimeMillis()
                    // 阻抗可能延迟到达：保留 lastLockedSessionId 3 秒补写窗口，
                    // 之后清除，防止上一人的阻抗被误写入下一人的会话
                    val sessionIdToClear = lastLockedSessionId
                    if (sessionIdToClear != null) {
                        viewModelScope.launch {
                            delay(3000)
                            if (lastLockedSessionId == sessionIdToClear) {
                                lastLockedSessionId = null
                            }
                        }
                    }
                }
            }
        }

        // ── 稳定状态 ──────────────────────────────────────────────────────────
        viewModelScope.launch {
            bleClient.isStable.collect { stable ->
                if (stable) {
                    _uiState.update { it.copy(isStable = true) }
                    clearSessionJob?.cancel()
                    val w = bleClient.weight.value
                    if (w >= 3.0) {
                        // 归零后极短时间内重新锁定且体重接近：仍属同一次称重
                        // （覆盖秤的瞬时抖动、重心移动导致的短暂归零）
                        val interruptionWithinGrace = sessionInterrupted
                            && lastWeightZeroMs > 0
                            && System.currentTimeMillis() - lastWeightZeroMs <= CONTINUATION_AFTER_ZERO_GRACE_MS
                        val isContinuation = lastLockedSessionId != null
                            && (!sessionInterrupted || (interruptionWithinGrace && abs(w - currentLockedWeightKg) <= WEIGHT_CONTINUATION_TOLERANCE_KG))
                        if (isContinuation) {
                            val sessionId = lastLockedSessionId!!
                            activeSessionId = sessionId
                            currentLockedWeightKg = w
                            if (interruptionWithinGrace) sessionInterrupted = false
                            AppLogger.i(TAG, "体重再次稳定(同一会话): ${String.format("%.2f", w)} kg, sessionId=${sessionId.take(8)}")
                            // 同一会话体重微调，同步更新持久化记录
                            processMeasurement(
                                sessionId = sessionId,
                                timestamp = getSessionTimestamp(),
                                commitToDb = true,
                                explicitImpedance = bleClient.impedance.value,
                                explicitWeight = w
                            )
                        } else {
                            currentLockedWeightKg = w
                            AppLogger.i(TAG, "体重锁定稳定: ${String.format("%.2f", w)} kg")
                            val newSessionId = java.util.UUID.randomUUID().toString()
                            activeSessionId = newSessionId
                            lastLockedSessionId = newSessionId
                            activeSessionTimestamp = System.currentTimeMillis()
                            sessionInterrupted = false
                            sessionCommitted = false
                            // 锁定时立即写入数据库，确保穿袜、普通秤、老人小孩等无阻抗场景也能及时安全持久化。
                            // 显式传入当前 bleClient.impedance.value（若穿袜或未踩电极，此时严格为 0.0）
                            processMeasurement(
                                sessionId = newSessionId,
                                timestamp = activeSessionTimestamp,
                                commitToDb = true,
                                explicitImpedance = bleClient.impedance.value,
                                explicitWeight = w
                            )
                        }
                    }
                } else {
                    // 体重不稳定：UI 上的阻抗立即归零，防止动态变动时残留上一轮阻抗
                    _uiState.update { it.copy(isStable = false, impedanceOhm = 0.0) }
                    // 延迟 2000ms 后清空 activeSessionId，
                    // 给秤端锁定后测算 BIA 阻抗留出充分的计算与通知写入窗口期。
                    // 使用受管协程句柄，避免新稳定事件被先前的延迟任务误销毁。
                    clearSessionJob?.cancel()
                    clearSessionJob = viewModelScope.launch {
                        delay(2000)
                        if (!_uiState.value.isStable) {
                            activeSessionId = null
                            hasAlertedForCurrentSession = false
                        }
                    }
                }
            }
        }

        // ── 阻抗（BIA） ──────────────────────────────────────────────────────
        viewModelScope.launch {
            bleClient.impedance.collect { imp ->
                _uiState.update { it.copy(impedanceOhm = imp) }
                // 阻抗到达说明是真人赤脚测量：复用已有 sessionId 和时间戳，
                // 确认会话有效并立即写库（阻抗可能晚于下秤到达，此处兜底提交）
                val sessionId = activeSessionId ?: lastLockedSessionId ?: _uiState.value.currentMeasurement?.id
                // 实时体重已远超锁定值时，该锁定是爬升中的过早误报，不可提交
                // （实时体重为 0 表示已下秤，属于延迟阻抗补写，放行）
                val liveWeight = bleClient.weight.value
                val lockStillValid = liveWeight <= 0.0 || liveWeight <= currentLockedWeightKg + SUPERSEDE_THRESHOLD_KG
                if (imp > 0.0 && sessionId != null && currentLockedWeightKg >= 3.0 && lockStillValid) {
                    AppLogger.i(TAG, "收到阻抗数据: ${imp.toInt()} Ω，更新记录 sessionId=${sessionId.take(8)}")
                    processMeasurement(
                        sessionId = sessionId,
                        timestamp = getSessionTimestamp(),
                        commitToDb = true,
                        explicitImpedance = imp
                    )
                }
            }
        }

        // ── 已发现设备 ────────────────────────────────────────────────────────
        viewModelScope.launch {
            bleClient.discoveredDevice.collect { pair ->
                _uiState.update {
                    it.copy(
                        discoveredDeviceName = pair?.first,
                        discoveredDeviceMac = pair?.second
                    )
                }
            }
        }

        // ── 候选设备列表 ──────────────────────────────────────────────────────
        viewModelScope.launch {
            bleClient.discoveredScales.collect { list ->
                _uiState.update { state ->
                    state.copy(discoveredScales = list)
                }
            }
        }
    }

    /**
     * 监听「已记住的多台体脂秤」与当前使用设备的变化，更新 UI 状态与 BLE 客户端。
     *
     * 两路订阅各自独立：
     * 1. rememberedScales —— 全量设备列表，用于界面展示，并同步给 BLE 客户端作为自动连接白名单；
     * 2. activeScale —— 当前使用设备，决定界面展示的设备名，并在设备可用时自动开始扫描。
     */
    private fun observePreferences() {
        val pref = preferenceManager ?: return
        viewModelScope.launch {
            pref.rememberedScales.collect { scales ->
                // 同步自动连接白名单：已记住的任意一台体脂秤踩上都能直接连接并称重
                val macs = scales.map { normalizeMacAddress(it.mac) }.toSet()
                bleClient.rememberedMacs = macs
                // 维持不变式：当前使用设备必须仍在已记住集合内。
                // 用户移除设备后，activeScale 的更正可能晚一拍到达，若此处保留旧指针，
                // 已移除的秤仍会被 isRememberedMac 误判为目标设备。
                val staleActive = bleClient.lastPairedMac
                if (!staleActive.isNullOrEmpty() && !macs.contains(normalizeMacAddress(staleActive))) {
                    bleClient.lastPairedMac = null
                }
                _uiState.update {
                    it.copy(
                        rememberedScales = scales,
                        isDeviceRemembered = scales.isNotEmpty()
                    )
                }
                // 当前正连接着的那台秤被移除时：主动断开并恢复扫描，避免继续接收已移除设备的数据
                val connectedMac = bleClient.connectedMac
                if (scales.isEmpty()) {
                    bleClient.disconnectAndReset()
                } else if (!connectedMac.isNullOrEmpty() && !bleClient.isRememberedMac(connectedMac)) {
                    AppLogger.i(TAG, "当前连接的体脂秤已被移除，主动断开并恢复扫描: $connectedMac")
                    bleClient.disconnectAndReset()
                    startScanning()
                }
            }
        }
        viewModelScope.launch {
            pref.activeScale.collect { active ->
                val mac = active?.mac
                bleClient.lastPairedMac = mac
                _uiState.update {
                    it.copy(
                        activeScaleMac = mac,
                        pairedDeviceMac = mac,
                        pairedDeviceName = active?.name
                    )
                }
                if (mac.isNullOrEmpty()) {
                    bleClient.disconnectAndReset()
                } else {
                    bleClient.isPairingMode = false
                    if (bleClient.connectionState.value == BleScaleClient.ConnectionState.IDLE && bleClient.isBluetoothEnabled()) {
                        startScanning()
                    }
                }
            }
        }
        viewModelScope.launch {
            pref.selectedScaleModel.collect { modelId ->
                val model = ScaleModel.fromId(modelId)
                _uiState.update { it.copy(selectedScaleModel = model) }
                bleClient.preferredModel = model
            }
        }
        viewModelScope.launch {
            pref.selectedBiaModel.collect { model ->
                selectedBiaModel = model
                _uiState.update { it.copy(selectedBiaModel = model) }
                AppLogger.i(TAG, "电阻抗估算模型已切换: ${model.displayName} (${model.id})")
            }
        }
    }

    /**
     * 用户切换体脂秤型号偏好。
     * 同步更新 UI 状态、BLE 客户端优先协议，并持久化到 DataStore。
     */
    fun selectScaleModel(model: ScaleModel) {
        _uiState.update { it.copy(selectedScaleModel = model) }
        bleClient.preferredModel = model
        preferenceManager?.let { pref ->
            viewModelScope.launch {
                pref.saveSelectedScaleModel(model.id)
            }
        }
    }

    /** 直接连接指定 MAC 地址的设备（跳过扫描步骤，用于已知设备快速重连） */
    fun connectToMac(mac: String) {
        bleClient.connectMac(mac)
    }

    /**
     * 计算当前称量的测量结果，并按需写入数据库。
     *
     * 调用时机：
     * 1. [bleClient.isStable] 锁定（commitToDb=false，仅计算与展示）
     * 2. [bleClient.impedance] 收到有效阻抗（commitToDb=true，确认有效立即写库）
     * 3. 下秤时在秤时长校验通过（commitToDb=true，会话结束提交）
     *
     * 写入策略：
     * - 同一 sessionId 使用 REPLACE 策略，确保同次称量只保留一条最新记录
     * - 未通过有效性校验的会话（短暂踩秤等）不会触发数据库写入
     * - 无匹配成员时只保存体重，体脂等全部为 0，不使用虚假参数
     *
     * @param sessionId  本次称量的唯一会话 ID（UUID 字符串）
     * @param timestamp  本次称量开始时的时间戳（毫秒，固定值，不随多次保存改变）
     * @param commitToDb 是否写入数据库（及触发参考体重更新与 Health Connect 同步）
     * @param explicitImpedance 显式指定的阻抗值（Ω），null 表示默认从 BLE 读取
     */
    private fun processMeasurement(
        sessionId: String,
        timestamp: Long,
        commitToDb: Boolean,
        explicitImpedance: Double? = null,
        explicitWeight: Double? = null
    ) {
        val weight = explicitWeight ?: currentLockedWeightKg
        currentLockedWeightKg = weight
        if (weight < 3.0) return // 体重过低，不处理（防止误触、放物品等情况）
        val impedance = explicitImpedance ?: bleClient.impedance.value

        viewModelScope.launch {
            try {
                AppLogger.d(TAG, "处理测量: weight=${String.format("%.2f", weight)} kg, impedance=${impedance.toInt()} Ω, commit=$commitToDb, sessionId=${sessionId.take(8)}")

                // 严格使用用户手动选择的成员；若未选择则通过智能匹配查找
                val targetMember = _uiState.value.selectedMember
                    ?: repository.getBestMember(weight)

                val measurement = if (targetMember != null) {
                    // 有匹配成员：使用真实身体参数与所选电阻抗模型计算 BIA 指标
                    BodyAlgorithm.calculate(
                        weightKg = weight,
                        impedanceOhm = impedance,
                        sex = targetMember.sex,
                        heightCm = targetMember.heightCm,
                        birthDateEpochMs = targetMember.birthDateEpochMs,
                        biaModel = selectedBiaModel
                    )
                } else {
                    // 没有精确匹配到成员时（如首测新成员、体重差异悬殊、或未建成员）：
                    // 获取参考身高：已选成员 > 首个可用成员 > 默认 170.0cm，确保 BMI 始终有效计算呈现在卡片上
                    val fallbackMember = _uiState.value.selectedMember
                        ?: _uiState.value.availableMembers.firstOrNull()
                    val heightCm = fallbackMember?.heightCm ?: 170.0
                    val heightM = (heightCm.coerceIn(50.0, 250.0)) / 100.0
                    val calculatedBmi = if (weight > 0.0) {
                        (weight / (heightM * heightM)).coerceIn(1.0, 100.0)
                    } else 0.0

                    BodyMeasurement(
                        id = "",
                        measuredAtEpochMs = timestamp,
                        weightKg = weight,
                        impedanceOhm = impedance,
                        bmi = calculatedBmi,
                        bodyFatPct = 0.0,
                        muscleKg = 0.0,
                        waterPct = 0.0,
                        proteinPct = 0.0,
                        boneMassKg = 0.0,
                        memberId = fallbackMember?.id,
                        memberNameSnapshot = fallbackMember?.name
                    )
                }

                // 强制使用本次会话的固定时间戳，覆盖 BodyAlgorithm 内部的 System.currentTimeMillis()，
                // 确保多次写入的时间戳完全一致
                val finalMeasurement = measurement.copy(
                    id = sessionId,
                    measuredAtEpochMs = timestamp
                )

                var displayMeasurement = finalMeasurement
                var matchedMember: FamilyMember? = targetMember

                if (commitToDb) {
                    AppLogger.d(TAG, "目标成员: ${targetMember?.name ?: "未匹配 (仅保存体重)"}")
                    try {
                        val r = repository.saveMeasurement(
                            finalMeasurement,
                            targetMember = targetMember,
                            existingId = sessionId,
                            biaModel = selectedBiaModel
                        )
                        sessionCommitted = true
                        displayMeasurement = r.first
                        matchedMember = r.second
                        AppLogger.i(TAG, "保存成功: id=${sessionId.take(8)}, weight=${String.format("%.2f", weight)} kg, member=${r.second?.name ?: "无"}")
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "保存失败: ${e.message}")
                        android.util.Log.e("ScaleViewModel", "Database save failed", e)
                    }
                }

                // 判断是否需要弹出"发现新成员"提示：
                // 匹配成员的参考体重与本次体重差距超过 7 kg，且用户未手动选择成员
                val diff = matchedMember?.let {
                    kotlin.math.abs(it.referenceWeightKg - weight)
                } ?: 100.0
                val userManuallySelected = _uiState.value.selectedMember != null
                val isOffScale = !bleClient.isStable.value
                val shouldShowAlert = isOffScale
                    && !hasAlertedForCurrentSession
                    && diff > 7.0
                    && !userManuallySelected
                if (shouldShowAlert) {
                    hasAlertedForCurrentSession = true // 本会话内只弹一次
                }

                // 仅已写库的记录才同步 Health Connect（防抖，同一次称重只写入一次）
                if (commitToDb) {
                    scheduleHealthSync(displayMeasurement)
                }

                // 更新 UI 状态：显示当前测量结果，并在需要时触发弹窗
                _uiState.update {
                    it.copy(
                        currentMeasurement = displayMeasurement,
                        showNewMemberAlert = if (shouldShowAlert) true else it.showNewMemberAlert
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("ScaleViewModel", "Error in processMeasurement", e)
            }
        }
    }

    /** 关闭"发现新成员"提示弹窗 */
    fun dismissAlert() {
        _uiState.update { it.copy(showNewMemberAlert = false) }
    }

    /** 待执行的 Health Connect 防抖同步任务 */
    private var healthSyncJob: Job? = null

    /**
     * 防抖写入 Health Connect。
     * 同一次称重的"稳定保存"与"阻抗补写"会先后触发本方法，
     * 取消前一个延迟任务，确保每次称重只向 Health Connect 写入一条最终记录。
     */
    private fun scheduleHealthSync(measurement: BodyMeasurement) {
        val prefs = preferenceManager ?: return
        val hc = healthConnectManager ?: return
        healthSyncJob?.cancel()
        healthSyncJob = viewModelScope.launch {
            delay(2000)
            try {
                val enabled = prefs.healthConnectEnabled.first()
                if (enabled && hc.hasAllPermissions()) {
                    hc.writeMeasurement(measurement)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Health Connect auto sync failed", e)
            }
        }
    }

    /**
     * 重置测量相关的会话状态与实时 UI。
     */
    private fun resetMeasurementUi() {
        activeSessionId = null
        lastLockedSessionId = null
        activeSessionTimestamp = 0L
        currentLockedWeightKg = 0.0
        sessionInterrupted = false
        sessionCommitted = false
        stepOnTimeMs = 0L
        lastWeightZeroMs = 0L
        _uiState.update {
            it.copy(
                currentMeasurement = null,
                liveWeightKg = 0.0,
                impedanceOhm = 0.0,
                isStable = false
            )
        }
    }

    /**
     * 检查系统蓝牙与定位服务开关状态，并刷新 UI 状态。
     */
    fun checkBluetoothAndLocationState() {
        val btEnabled = bleClient.isBluetoothEnabled()
        val locEnabled = bleClient.isLocationEnabled()
        _uiState.update {
            it.copy(
                isBluetoothEnabled = btEnabled,
                isLocationEnabled = locEnabled
            )
        }
    }

    /**
     * 启动手动配对发现扫描流程。
     * 进入发现模式，收集附近所有候选体脂秤设备，等待用户手动点击选择。
     */
    fun startPairingScan() {
        AppLogger.i(TAG, "启动手动配对扫描流程")
        checkBluetoothAndLocationState()
        if (!bleClient.isBluetoothEnabled()) {
            AppLogger.w(TAG, "系统蓝牙未开启，跳过配对扫描")
            return
        }
        resetMeasurementUi()
        _uiState.update { it.copy(discoveredScales = emptyList()) }
        bleClient.startPairingScan()
    }

    /**
     * 用户手动选择设备，连接并记住该设备。
     *
     * 多设备支持：新设备会被写入「已记住的体脂秤」列表（同 MAC 则原地更新），
     * 并自动切换为当前使用设备；列表中已有的其它体脂秤**不会**被清除。
     *
     * @param device 用户在发现列表中点击选中的秤
     */
    fun pairAndConnectDevice(device: BleScaleClient.DiscoveredScaleDevice) {
        AppLogger.i(TAG, "用户确认连接并记住设备: ${device.name} [${device.address}]")
        val mac = normalizeMacAddress(device.address)
        val remembered = RememberedScale(
            mac = mac,
            name = device.name,
            modelId = device.matchedModel.id,
            protocolName = device.protocolName,
            lastUsedAtMs = System.currentTimeMillis()
        )
        viewModelScope.launch {
            preferenceManager?.rememberScale(remembered)
        }
        // 界面立即同步更新已绑定设备信息，消除 DataStore 异步落库延迟引起的界面闪烁
        _uiState.update { state ->
            state.copy(
                rememberedScales = ScaleMemoryLogic.upsert(state.rememberedScales, remembered),
                activeScaleMac = mac,
                pairedDeviceMac = mac,
                pairedDeviceName = device.name,
                isDeviceRemembered = true
            )
        }
        bleClient.lastPairedMac = mac
        bleClient.rememberMac(mac)
        bleClient.manualConnect(device)
    }

    /**
     * 切换当前使用中的体脂秤。
     *
     * 仅改变「当前使用设备」的记录与界面展示；由于自动连接接受任意一台已记住的秤，
     * 切换不会中断正在进行中的称重。
     *
     * @param mac 目标设备 MAC（必须已在已记住列表中）
     */
    fun switchActiveScale(mac: String) {
        val target = normalizeMacAddress(mac)
        if (target.isEmpty()) return
        AppLogger.i(TAG, "切换当前使用的体脂秤: $target")
        _uiState.update { state ->
            state.copy(
                activeScaleMac = target,
                pairedDeviceMac = target,
                pairedDeviceName = state.rememberedScales
                    .firstOrNull { sameMacAddress(it.mac, target) }?.name ?: state.pairedDeviceName
            )
        }
        bleClient.lastPairedMac = target
        viewModelScope.launch {
            preferenceManager?.setActiveScale(target)
        }
    }

    /**
     * 开始扫描 BLE 体脂秤设备。
     * 注意：若尚未记住任何设备，绝不盲连，保持 IDLE 状态等待用户手动配对。
     */
    fun startScanning() {
        AppLogger.i(TAG, "请求启动称重扫描...")
        checkBluetoothAndLocationState()
        if (!bleClient.isBluetoothEnabled()) {
            AppLogger.w(TAG, "系统蓝牙未开启，跳过自动连接扫描")
            return
        }
        resetMeasurementUi()

        // 确保退出配对扫描模式，切入常规称量监听流程
        bleClient.isPairingMode = false

        // 多设备场景下：只要已记住集合非空即可开始扫描（踩上其中任意一台都会自动连接）。
        // UI 状态兜底值可能晚一拍更新（例如刚移除设备），因此只在它仍属于已记住集合时才采用，
        // 否则会把已移除的 MAC 写回 lastPairedMac，让该秤重新被当作目标设备
        val stateMac = _uiState.value.activeScaleMac ?: _uiState.value.pairedDeviceMac
        val fallbackMac = stateMac?.takeIf { bleClient.rememberedMacs.contains(normalizeMacAddress(it)) }
        val targetMac = bleClient.lastPairedMac ?: fallbackMac
        if (targetMac.isNullOrEmpty() && bleClient.rememberedMacs.isEmpty()) {
            AppLogger.i(TAG, "尚未记住任何设备，不执行自动连接扫描，等待手动配对")
            return
        }
        if (!targetMac.isNullOrEmpty()) {
            bleClient.lastPairedMac = targetMac
        }

        bleClient.startScan()
    }

    /**
     * 退出配对界面时停止配对扫描并重置配对模式。
     */
    fun stopPairingScan() {
        AppLogger.i(TAG, "退出配对模式，重置扫描状态")
        bleClient.stopScan()
        bleClient.isPairingMode = false
    }

    override fun onCleared() {
        super.onCleared()
        bleClient.stopScan()
        clearSessionJob?.cancel()
    }
}
