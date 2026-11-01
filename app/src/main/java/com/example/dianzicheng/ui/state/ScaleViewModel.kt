package com.example.dianzicheng.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dianzicheng.data.ble.BleScaleClient
import com.example.dianzicheng.data.health.HealthConnectManager
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.BiaModel
import com.example.dianzicheng.domain.BodyAlgorithm
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.RememberedScale
import com.example.dianzicheng.domain.ScaleMemoryLogic
import com.example.dianzicheng.domain.ScaleModel
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.domain.normalizeMacAddress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/** 短暂归零后仅续接体重接近的会话；宽限过后新称重必须使用新的记录 ID。 */
internal fun isMeasurementContinuation(
    hasPrevious: Boolean,
    interrupted: Boolean,
    elapsedSinceZeroMs: Long,
    weightDifferenceKg: Double,
    graceMs: Long,
    toleranceKg: Double,
): Boolean = hasPrevious && (!interrupted ||
    (elapsedSinceZeroMs in 0..graceMs && weightDifferenceKg <= toleranceKg))

/**
 * 测量主页状态持有者：BLE 称重会话状态机 + 成员绑定 + 设备配对/切换。
 *
 * 会话语义（与旧实现逐条对齐，保证全功能复刻）：
 * 1. 体重稳定锁定 → 生成会话 UUID，**立即写库**（无阻抗也先存体重/BMI）；
 * 2. 阻抗稍后到达 → 用同一 sessionId 覆盖写（REPLACE 幂等），兼容有/无 BIA 两种称重；
 * 3. 过早锁定（实时体重超锁定值 3.0kg）→ 会话作废并删除已写记录；
 * 4. 归零后 2.5s 内重新锁定且体重差 ≤2.0kg → 视为同一次称重（续接）；
 * 5. 阻抗延迟补写窗口：下秤后 20s 内到达的阻抗仍写回上一条记录；
 * 6. Health Connect 同步 2s 防抖，同一次称重只写一条最终记录。
 */
class ScaleViewModel(
    private val bleClient: BleScaleClient,
    private val repository: ScaleRepository,
    private val preferenceManager: PreferenceManager? = null,
    private val healthConnectManager: HealthConnectManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScaleUiState())
    val uiState: StateFlow<ScaleUiState> = _uiState.asStateFlow()

    // ── 会话内部状态（幂等核心） ─────────────────────────────────────────────
    private val measurementMutex = Mutex()
    private var activeSessionId: String? = null
    private var activeSessionTimestamp: Long = 0L
    private var hasAlertedForCurrentSession = false
    private var lastLockedSessionId: String? = null
    private var currentLockedWeightKg = 0.0
    private var sessionInterrupted = false
    private var sessionCommitted = false
    private var lastWeightZeroMs = 0L
    private var clearSessionJob: Job? = null
    private var healthSyncJob: Job? = null
    private var selectedBiaModel: BiaModel = BiaModel.DEFAULT

    /** 本次会话测得的阻抗（Ω），0.0 表示未测得；供离秤最终提交时保留 BIA 结果不被清零 */
    private var sessionImpedanceOhm = 0.0

    /** 写库节流基准：最近一次真正落库的体重与时刻（NaN/0 表示尚未落库，首个写入必定放行） */
    private var lastPersistedWeightKg = Double.NaN
    private var lastPersistedMs = 0L

    /** 实时体重显示发射节流基准：最近一次发射给 UI 的取整体重与时刻 */
    private var lastEmittedWeightKg = Double.NaN
    private var lastWeightEmitMs = 0L

    init {
        checkBluetoothAndLocationState()
        observeBle()
        observeMembers()
        observePreferences()
    }

    // ── 公开 API ────────────────────────────────────────────────────────────

    /** 手动选择绑定成员；传 null 切回「全员智能自动匹配」。已有结果且选了具体成员时立即重绑重算。 */
    fun selectMember(member: FamilyMember?) {
        _uiState.update { it.copy(selectedMember = member) }
        syncAfuB1UserProfile(member ?: _uiState.value.availableMembers.firstOrNull())
        val current = _uiState.value.currentMeasurement ?: return
        if (member != null && current.weightKg >= MIN_VALID_WEIGHT_KG) {
            bindCurrentMeasurementToMember(member)
        }
    }

    /** 把当前测量结果改绑到指定成员：按该成员参数重算 BIA 并 REPLACE 写库（视为用户确认保留）。 */
    fun bindCurrentMeasurementToMember(member: FamilyMember) {
        val current = _uiState.value.currentMeasurement ?: return
        viewModelScope.launch {
            try {
                val updated = repository.bindMeasurementToMember(current, member, selectedBiaModel)
                sessionCommitted = true
                _uiState.update {
                    it.copy(currentMeasurement = updated, selectedMember = member)
                }
                scheduleHealthSync(updated)
            } catch (e: Exception) {
                AppLogger.e(TAG, "重绑成员失败", e)
            }
        }
    }

    /** 切换体脂秤型号偏好（优先匹配协议）。 */
    fun selectScaleModel(model: ScaleModel) {
        _uiState.update { it.copy(selectedScaleModel = model) }
        bleClient.preferredModel = model
        val pm = preferenceManager ?: return
        viewModelScope.launch { pm.saveSelectedScaleModel(model.id) }
    }

    /** 跳过扫描直连已知 MAC。 */
    fun connectToMac(mac: String) {
        bleClient.connectMac(mac)
    }

    /** 关闭「发现新成员？」提示。 */
    fun dismissAlert() {
        _uiState.update { it.copy(showNewMemberAlert = false) }
    }

    /** 刷新系统蓝牙 / 定位开关状态（定位只记录不拦截）。 */
    fun checkBluetoothAndLocationState() {
        _uiState.update {
            it.copy(
                isBluetoothEnabled = bleClient.isBluetoothEnabled(),
                isLocationEnabled = bleClient.isLocationEnabled()
            )
        }
    }

    /** 手动配对发现模式：只收集候选设备、绝不自动连接。 */
    fun startPairingScan() {
        checkBluetoothAndLocationState()
        if (!_uiState.value.isBluetoothEnabled) {
            AppLogger.w(TAG, "蓝牙未开启，跳过配对扫描")
            return
        }
        AppLogger.i(TAG, "开始配对扫描")
        resetMeasurementUi()
        _uiState.update { it.copy(discoveredScales = emptyList()) }
        bleClient.startPairingScan()
    }

    /** 配对点选：记住设备（多设备 upsert、切为当前使用）并立即连接。 */
    fun pairAndConnectDevice(device: BleScaleClient.DiscoveredScaleDevice) {
        val mac = normalizeMacAddress(device.address)
        if (mac.isBlank()) return
        AppLogger.i(TAG, "绑定设备 ${device.name} ($mac)")
        val incoming = RememberedScale(
            mac = mac,
            name = device.name,
            modelId = device.matchedModel.id,
            protocolName = device.protocolName,
            lastUsedAtMs = System.currentTimeMillis()
        )
        viewModelScope.launch {
            preferenceManager?.rememberScale(incoming, makeActive = true)
        }
        // 立即同步 UI（消除 DataStore 延迟闪烁）：只 upsert，不清其它已记住秤
        _uiState.update { state ->
            val upserted = ScaleMemoryLogic.upsert(state.rememberedScales, incoming)
            state.copy(
                rememberedScales = upserted,
                activeScaleMac = mac,
                pairedDeviceMac = mac,
                isDeviceRemembered = true
            )
        }
        bleClient.lastPairedMac = mac
        bleClient.rememberMac(mac)
        bleClient.manualConnect(device)
    }

    /** 切换「当前使用设备」：不打断进行中的称重（自动连接白名单含全部已记住设备）。 */
    fun switchActiveScale(mac: String) {
        if (mac.isBlank()) return
        AppLogger.i(TAG, "切换当前设备 $mac")
        _uiState.update { state ->
            state.copy(
                activeScaleMac = mac,
                pairedDeviceMac = mac
            )
        }
        bleClient.lastPairedMac = mac
        viewModelScope.launch { preferenceManager?.setActiveScale(mac) }
    }

    /** 常规称重扫描：无任何已记住设备时绝不盲连（保持 IDLE 等手动配对）。 */
    fun startScanning() {
        checkBluetoothAndLocationState()
        if (!_uiState.value.isBluetoothEnabled) {
            AppLogger.w(TAG, "蓝牙未开启，跳过扫描")
            return
        }
        AppLogger.i(TAG, "开始称重扫描")
        resetMeasurementUi()
        bleClient.isPairingMode = false
        val preferred = bleClient.lastPairedMac
            ?: _uiState.value.activeScaleMac?.takeIf { bleClient.isRememberedMac(it) }
        if (!bleClient.hasRememberedDevice && preferred == null) {
            AppLogger.i(TAG, "无已记住设备，保持待配对状态")
            return
        }
        bleClient.startScan()
    }

    /** 退出配对界面：停扫描并退出配对模式。 */
    fun stopPairingScan() {
        AppLogger.i(TAG, "停止配对扫描")
        // 配对成功后可能正在连接；弹层销毁不应取消连接或自动监听。
        if (!bleClient.isPairingMode) return
        bleClient.stopScan()
        bleClient.isPairingMode = false
        if (bleClient.hasRememberedDevice && bleClient.isBluetoothEnabled() &&
            bleClient.connectionState.value == BleScaleClient.ConnectionState.IDLE
        ) bleClient.startScan()
    }

    override fun onCleared() {
        super.onCleared()
        bleClient.stopScan()
        clearSessionJob?.cancel()
        healthSyncJob?.cancel()
    }

    // ── BLE 观察 ───────────────────────────────────────────────────────────

    private fun observeBle() {
        viewModelScope.launch {
            bleClient.connectionState.collect { state ->
                _uiState.update { it.copy(connection = state) }
                AppLogger.d(TAG, "连接状态 → $state")
            }
        }
        viewModelScope.launch {
            bleClient.weight.collect { rawWeight -> measurementMutex.withLock { onWeightChanged(rawWeight) } }
        }
        viewModelScope.launch {
            bleClient.isStable.collect { stable -> measurementMutex.withLock { onStableChanged(stable) } }
        }
        viewModelScope.launch {
            bleClient.impedance.collect { z -> measurementMutex.withLock { onImpedanceChanged(z) } }
        }
        viewModelScope.launch {
            bleClient.discoveredScales.collect { list ->
                _uiState.update { it.copy(discoveredScales = list) }
            }
        }
    }

    /** 实时体重：显示取整与发射节流、锁定后示数校准（写库节流）、过早锁定作废、离秤最终提交。 */
    private suspend fun onWeightChanged(rawWeight: Double) {
        // 写库挂起期间可能排队收到旧的归零事件；若秤已经恢复示数，不能结束当前会话。
        if (rawWeight <= 0.0 && bleClient.weight.value > 0.0) return
        val stable = bleClient.isStable.value
        // 显示精度 0.01kg 取整（防高频重组）；入库与锁定始终用原始 rawWeight。
        // 【M1 性能】显示值相等去重 + ≤100ms 发射节流：称重期间每秒 10~20 个数据包不再逐包驱动
        // 整页重组；并与阻抗清零合并为单次 update，消除旧实现的双发射。
        // 节流只影响展示（滞后 ≤100ms、视觉无感），锁定值/入库值不受影响。
        val roundedWeightKg = (rawWeight * 100).roundToInt() / 100.0
        val nowMs = System.currentTimeMillis()
        val emitWeight = roundedWeightKg != lastEmittedWeightKg &&
            nowMs - lastWeightEmitMs >= WEIGHT_EMIT_MIN_INTERVAL_MS
        val clearImpedance = rawWeight < MIN_VALID_WEIGHT_KG || !stable
        if (emitWeight || clearImpedance) {
            if (emitWeight) {
                lastEmittedWeightKg = roundedWeightKg
                lastWeightEmitMs = nowMs
            }
            _uiState.update {
                it.copy(
                    liveWeightKg = if (emitWeight) roundedWeightKg else it.liveWeightKg,
                    impedanceOhm = if (clearImpedance) 0.0 else it.impedanceOhm
                )
            }
        }

        val sessionId = activeSessionId
        if (sessionId != null && stable && currentLockedWeightKg >= MIN_VALID_WEIGHT_KG) {
            when {
                // 过早锁定作废：实时体重超锁定值 3.0kg（多半是换了人/物品上去）
                rawWeight > currentLockedWeightKg + SUPERSEDE_THRESHOLD_KG -> {
                    AppLogger.i(TAG, "过早锁定作废：${currentLockedWeightKg}kg → ${rawWeight}kg")
                    healthSyncJob?.cancel()
                    if (sessionCommitted) {
                        runCatching { repository.deleteMeasurementById(sessionId) }
                    }
                    clearSession()
                    _uiState.update { it.copy(currentMeasurement = null) }
                }
                // 锁定中示数校准：|Δ| ≥ 0.005kg 时刷新示数；真正写库由 processMeasurement 内部的
                // 节流门槛控制（距上次落库 |Δ|≥0.05kg 或 ≥500ms 才落库），离秤时无条件补最终值
                abs(rawWeight - currentLockedWeightKg) >= CALIBRATION_EPSILON_KG -> {
                    currentLockedWeightKg = rawWeight
                    processMeasurement(commitToDb = true)
                }
            }
        }

        // 离秤（0kg）：**最终值提交**（无条件落库，REPLACE 幂等）——写库节流期间滞后的示数
        // 一律在此补齐，保证最终体重数值不丢失、不偏差；结果卡保留到下一次会话。
        // explicitImpedance 传会话阻抗，防止以 0 覆盖已写入的 BIA 结果。
        if (rawWeight <= 0.0) {
            if (sessionId != null && currentLockedWeightKg >= MIN_VALID_WEIGHT_KG) {
                AppLogger.i(TAG, "离秤最终提交会话 $sessionId")
                processMeasurement(commitToDb = true, forceCommit = true, explicitImpedance = sessionImpedanceOhm)
            }
            if (sessionId != null || activeSessionId != null) {
                activeSessionId = null
                hasAlertedForCurrentSession = false
                sessionInterrupted = true
                lastWeightZeroMs = System.currentTimeMillis()
                // lastLockedSessionId 保留 20s 阻抗延迟补写窗口，之后清空
                val pending = lastLockedSessionId
                if (pending != null) {
                    viewModelScope.launch {
                        delay(LAST_LOCKED_RETAIN_MS)
                        measurementMutex.withLock {
                            if (lastLockedSessionId == pending && activeSessionId == null) {
                                lastLockedSessionId = null
                            }
                        }
                    }
                }
            }
        }
    }

    /** 稳定锁定 / 解锁：新会话立即写库，中断后满足宽限条件则续接同一条记录。 */
    private suspend fun onStableChanged(stable: Boolean) {
        if (stable) {
            clearSessionJob?.cancel()
            if (activeSessionId != null) return
            val weight = bleClient.weight.value
            if (weight < MIN_VALID_WEIGHT_KG) return
            val previousLockedWeightKg = currentLockedWeightKg
            val now = System.currentTimeMillis()
            val continuation = isMeasurementContinuation(
                hasPrevious = lastLockedSessionId != null,
                interrupted = sessionInterrupted,
                elapsedSinceZeroMs = now - lastWeightZeroMs,
                weightDifferenceKg = abs(weight - previousLockedWeightKg),
                graceMs = CONTINUATION_AFTER_ZERO_GRACE_MS,
                toleranceKg = WEIGHT_CONTINUATION_TOLERANCE_KG,
            )
            currentLockedWeightKg = weight
            // 锁定是关键时刻：强制刷新实时体重显示，防止发射节流滞后的尾值丢失
            flushLiveWeight()
            if (continuation && lastLockedSessionId != null) {
                activeSessionId = lastLockedSessionId
                sessionInterrupted = false
                lastLockedSessionId = activeSessionId
                AppLogger.i(TAG, "会话续接 $activeSessionId（|Δw| 宽限内重新锁定）")
                processMeasurement(commitToDb = true, forceCommit = true)
            } else {
                activeSessionId = UUID.randomUUID().toString()
                activeSessionTimestamp = now
                sessionCommitted = false
                sessionInterrupted = false
                sessionImpedanceOhm = 0.0
                lastLockedSessionId = activeSessionId
                AppLogger.i(TAG, "稳定锁定 ${weight}kg，新会话 $activeSessionId")
                processMeasurement(commitToDb = true, forceCommit = true)
            }
        } else {
            _uiState.update { it.copy(impedanceOhm = 0.0) }
            // 不稳定持续 2s 后才清会话（短暂波动不作废）
            val sessionId = activeSessionId
            clearSessionJob?.cancel()
            clearSessionJob = viewModelScope.launch {
                delay(UNSTABLE_CLEAR_DELAY_MS)
                measurementMutex.withLock {
                    if (activeSessionId == sessionId && !bleClient.isStable.value) {
                        activeSessionId = null
                        hasAlertedForCurrentSession = false
                    }
                }
            }
        }
    }

    /** 阻抗到达：20s 补写窗口内写回同一条记录（兼容「先锁体重、后出阻抗」的 BIA 流程）。 */
    private suspend fun onImpedanceChanged(impedanceOhm: Double) {
        if (impedanceOhm <= 0.0) return
        // 阻抗可能与首个锁定包同时到达；先建立会话，避免丢失或写入旧记录。
        if (activeSessionId == null && bleClient.isStable.value) {
            onStableChanged(true)
        }
        val sessionId = activeSessionId ?: lastLockedSessionId ?: return
        if (currentLockedWeightKg < MIN_VALID_WEIGHT_KG) return
        val live = bleClient.weight.value
        val lockStillValid = live <= 0.0 || live <= currentLockedWeightKg + SUPERSEDE_THRESHOLD_KG
        if (!lockStillValid) return
        sessionImpedanceOhm = impedanceOhm
        _uiState.update { it.copy(impedanceOhm = impedanceOhm) }
        AppLogger.i(TAG, "阻抗到达 ${impedanceOhm.toInt()}Ω，补写会话 $sessionId")
        processMeasurement(commitToDb = true, forceCommit = true, explicitImpedance = impedanceOhm, explicitSessionId = sessionId)
    }

    // ── 成员与偏好观察 ──────────────────────────────────────────────────────

    private fun observeMembers() {
        viewModelScope.launch {
            repository.getMembers().collect { members ->
                val selected = _uiState.value.selectedMember
                val refreshedSelected = when {
                    selected == null && members.size == 1 -> members.first() // 仅一名成员时自动选中
                    selected != null -> members.firstOrNull { it.id == selected.id } ?: selected // 被删则保留旧对象
                    else -> null
                }
                _uiState.update {
                    it.copy(availableMembers = members, selectedMember = refreshedSelected)
                }
                syncAfuB1UserProfile(refreshedSelected ?: members.firstOrNull())
            }
        }
    }

    private fun observePreferences() {
        val pm = preferenceManager ?: return
        viewModelScope.launch {
            pm.rememberedScales.collect { scales ->
                val macs = scales.map { normalizeMacAddress(it.mac) }.toSet()
                bleClient.rememberedMacs = macs
                val connected = bleClient.connectedMac
                val removedWhileConnected = connected != null && !macs.contains(normalizeMacAddress(connected))
                _uiState.update {
                    it.copy(rememberedScales = scales, isDeviceRemembered = scales.isNotEmpty())
                }
                when {
                    // 正在连接的秤被移除 → 主动断开并恢复扫描
                    removedWhileConnected -> {
                        bleClient.disconnectAndReset()
                        if (macs.isNotEmpty() && bleClient.isBluetoothEnabled()) bleClient.startScan()
                    }
                    // 配对过程中首次登记尚未完成时，不要打断正在连接的设备。
                    macs.isEmpty() && !bleClient.isPairingMode && connected != null -> bleClient.disconnectAndReset()
                }
            }
        }
        viewModelScope.launch {
            pm.activeScale.collect { scale ->
                val mac = scale?.mac
                bleClient.lastPairedMac = mac
                _uiState.update {
                    it.copy(
                        activeScaleMac = mac,
                        pairedDeviceMac = mac
                    )
                }
            }
        }
        viewModelScope.launch {
            pm.selectedScaleModel.collect { raw ->
                val model = ScaleModel.fromId(raw)
                _uiState.update { it.copy(selectedScaleModel = model) }
                bleClient.preferredModel = model
            }
        }
        viewModelScope.launch {
            pm.selectedBiaModel.collect { model ->
                selectedBiaModel = model
                _uiState.update { it.copy(selectedBiaModel = model) }
                AppLogger.d(TAG, "体脂估算模型 → ${model.displayName}")
            }
        }
    }

    // ── 会话核心：计算 + 落库 ───────────────────────────────────────────────

    /**
     * 计算（并可选落库）当前锁定体重对应的身体指标。
     *
     * - 有成员：按成员身高/年龄/性别 + 当前 BIA 模型计算；
     * - 无成员：仅存体重，BMI 按兜底身高（已选 > 首个成员 > 170cm）计算，体脂相关全 0；
     * - 落库使用本次会话 UUID 作 [existingId]，同一次称重多次写为 REPLACE 幂等。
     */
    private suspend fun processMeasurement(
        commitToDb: Boolean,
        forceCommit: Boolean = false,
        explicitImpedance: Double? = null,
        explicitSessionId: String? = null
    ) {
        val weight = currentLockedWeightKg
        if (weight < MIN_VALID_WEIGHT_KG) return
        val impedance = explicitImpedance ?: sessionImpedanceOhm
        val sessionId = explicitSessionId ?: activeSessionId ?: lastLockedSessionId
            ?: _uiState.value.currentMeasurement?.id ?: UUID.randomUUID().toString()
        val timestamp = getSessionTimestamp()

        val selected = _uiState.value.selectedMember
        val member = selected ?: runCatching { repository.getBestMember(weight) }.getOrNull()

        val measurement = if (member != null) {
            BodyAlgorithm.calculate(
                weightKg = weight,
                impedanceOhm = impedance,
                sex = member.sex,
                heightCm = member.heightCm,
                birthDateEpochMs = member.birthDateEpochMs,
                biaModel = selectedBiaModel
            )
        } else {
            // 无成员可绑（selected 恒为 null）：仅存体重；BMI 按兜底身高（首个成员 > 170cm）计算，体脂相关全 0
            val fallbackHeight = _uiState.value.availableMembers.firstOrNull()?.heightCm
                ?: FALLBACK_HEIGHT_CM
            val height = fallbackHeight.coerceIn(MIN_HEIGHT_CM, MAX_HEIGHT_CM)
            val bmi = (weight / ((height / 100.0) * (height / 100.0))).coerceIn(MIN_BMI, MAX_BMI)
            BodyAlgorithm.calculate(
                weightKg = weight,
                impedanceOhm = 0.0,
                sex = Sex.MALE,
                heightCm = height,
                birthDateEpochMs = System.currentTimeMillis() - 25L * 365 * 24 * 3600 * 1000,
                biaModel = selectedBiaModel
            ).copy(bmi = bmi, bodyFatPct = 0.0, muscleKg = 0.0, waterPct = 0.0, proteinPct = 0.0, boneMassKg = 0.0)
        }.copy(id = sessionId, measuredAtEpochMs = timestamp)

        var final = measurement
        if (commitToDb) {
            // 【H3 写库节流】锁定后示数校准每 0.005kg 就会触发一次保存，引发 Room 全表 Flow 重发
            // → 全表映射 → 快照重算 → 整页重组风暴。这里仅当体重距上次落库变化 ≥0.05kg
            // 或距上次落库 ≥500ms 才真正写库；锁定 / 离秤最终提交 / 阻抗补写传 forceCommit=true 无条件落库。
            val shouldSave = forceCommit ||
                abs(weight - lastPersistedWeightKg) >= DB_WRITE_WEIGHT_EPSILON_KG ||
                System.currentTimeMillis() - lastPersistedMs >= DB_WRITE_MIN_INTERVAL_MS
            if (shouldSave) {
                try {
                    val (saved, bound) = repository.saveMeasurement(
                        measurement = measurement,
                        targetMember = selected,
                        existingId = sessionId,
                        biaModel = selectedBiaModel
                    )
                    final = saved
                    sessionCommitted = true
                    lastPersistedWeightKg = weight
                    lastPersistedMs = System.currentTimeMillis()
                    AppLogger.i(TAG, "记录已保存 id=$sessionId 体重=${saved.weightKg}kg 成员=${bound?.name ?: "未绑定"}")
                    scheduleHealthSync(saved)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "记录保存失败：${e.localizedMessage}")
                    AppLogger.e(TAG, "saveMeasurement failed", e)
                }
            }
        }

        maybeShowNewMemberAlert(member, weight)
        _uiState.update { it.copy(currentMeasurement = final) }
    }

    /** 「发现新成员？」提示：已离秤、本会话未弹过、与匹配成员参考体重差 > 7kg、未手动选成员。 */
    private fun maybeShowNewMemberAlert(member: FamilyMember?, weight: Double) {
        if (bleClient.isStable.value) return
        if (hasAlertedForCurrentSession) return
        if (_uiState.value.selectedMember != null) return
        val diff = if (member == null) Double.MAX_VALUE else abs(member.referenceWeightKg - weight)
        if (diff > NEW_MEMBER_ALERT_THRESHOLD_KG) {
            hasAlertedForCurrentSession = true
            _uiState.update { it.copy(showNewMemberAlert = true) }
        }
    }

    /** Health Connect 同步（2s 防抖，同一次称重只写最终一条）。 */
    private fun scheduleHealthSync(measurement: BodyMeasurement) {
        val hc = healthConnectManager ?: return
        healthSyncJob?.cancel()
        healthSyncJob = viewModelScope.launch {
            delay(HEALTH_SYNC_DEBOUNCE_MS)
            val enabled = preferenceManager?.healthConnectEnabled?.firstOrNull() ?: false
            if (enabled && hc.hasAllPermissions()) {
                runCatching { hc.writeMeasurement(measurement) }
            }
        }
    }

    /** 同步薄荷 B1 秤端用户档案（年龄/性别/目标体重），仅在有成员时下发。 */
    private fun syncAfuB1UserProfile(member: FamilyMember?) {
        if (member == null) return
        val age = run {
            // 年龄统一走 domain 唯一口径 BodyAlgorithm.calculateAge（修正旧 365 天粗算的 ±1 岁漂移），
            // 并保留 B1 档案协议的有效年龄范围约束（超出回落默认值）
            val years = BodyAlgorithm.calculateAge(member.birthDateEpochMs)
            if (years in MIN_USER_AGE..MAX_USER_AGE) years else DEFAULT_USER_AGE
        }
        bleClient.afuB1UserAge = age
        bleClient.afuB1UserIsMale = member.sex == Sex.MALE
        bleClient.afuB1UserTargetKg = if (member.referenceWeightKg > 0) member.referenceWeightKg else DEFAULT_TARGET_KG
    }

    private fun getSessionTimestamp(): Long =
        if (activeSessionTimestamp > 0L) activeSessionTimestamp else System.currentTimeMillis()

    /** 立即发射当前实时体重显示值（越过 ≤100ms 发射节流），用于锁定等关键时刻防止尾值丢失。 */
    private fun flushLiveWeight() {
        val rounded = (bleClient.weight.value * 100).roundToInt() / 100.0
        if (rounded != lastEmittedWeightKg) {
            lastEmittedWeightKg = rounded
            lastWeightEmitMs = System.currentTimeMillis()
            _uiState.update { it.copy(liveWeightKg = rounded) }
        }
    }

    private fun clearSession() {
        activeSessionId = null
        sessionCommitted = false
        hasAlertedForCurrentSession = false
        sessionImpedanceOhm = 0.0
    }

    /** 扫描/配对前清空测量展示（保留偏好与设备状态）。 */
    private fun resetMeasurementUi() {
        clearSessionJob?.cancel()
        activeSessionId = null
        activeSessionTimestamp = 0L
        hasAlertedForCurrentSession = false
        lastLockedSessionId = null
        currentLockedWeightKg = 0.0
        sessionInterrupted = false
        sessionCommitted = false
        sessionImpedanceOhm = 0.0
        lastPersistedWeightKg = Double.NaN
        lastPersistedMs = 0L
        lastEmittedWeightKg = Double.NaN
        lastWeightEmitMs = 0L
        _uiState.update {
            it.copy(
                liveWeightKg = 0.0,
                impedanceOhm = 0.0,
                isStable = false,
                currentMeasurement = null,
                showNewMemberAlert = false
            )
        }
    }

    private companion object {
        const val TAG = "ScaleVM"

        /** 最低有效体重（kg）：低于此值不产生测量 */
        const val MIN_VALID_WEIGHT_KG = 3.0

        /** 过早锁定阈值（kg）：实时体重超锁定值该值即作废会话 */
        const val SUPERSEDE_THRESHOLD_KG = 3.0

        /** 锁定后示数校准灵敏度（kg） */
        const val CALIBRATION_EPSILON_KG = 0.005

        /** 写库节流：距上次落库的体重变化门槛（kg），与 [DB_WRITE_MIN_INTERVAL_MS] 满足其一才落库 */
        const val DB_WRITE_WEIGHT_EPSILON_KG = 0.05

        /** 写库节流：距上次落库的最小间隔（ms） */
        const val DB_WRITE_MIN_INTERVAL_MS = 500L

        /** 实时体重显示的最小发射间隔（ms）：相等去重 + 限频，滞后 ≤100ms 视觉无感 */
        const val WEIGHT_EMIT_MIN_INTERVAL_MS = 100L

        /** 归零后续接宽限（ms） */
        const val CONTINUATION_AFTER_ZERO_GRACE_MS = 2500L

        /** 续接体重容差（kg） */
        const val WEIGHT_CONTINUATION_TOLERANCE_KG = 2.0

        /** 阻抗延迟补写窗口（ms） */
        const val LAST_LOCKED_RETAIN_MS = 20000L

        /** 不稳定清会话延迟（ms） */
        const val UNSTABLE_CLEAR_DELAY_MS = 2000L

        /** Health Connect 同步防抖（ms） */
        const val HEALTH_SYNC_DEBOUNCE_MS = 2000L

        /** 「发现新成员？」阈值（kg）：与匹配容差（10kg）不同 */
        const val NEW_MEMBER_ALERT_THRESHOLD_KG = 7.0

        const val FALLBACK_HEIGHT_CM = 170.0
        const val MIN_HEIGHT_CM = 50.0
        const val MAX_HEIGHT_CM = 250.0
        const val MIN_BMI = 1.0
        const val MAX_BMI = 100.0
        const val DEFAULT_USER_AGE = 30
        const val MIN_USER_AGE = 10
        const val MAX_USER_AGE = 99
        const val DEFAULT_TARGET_KG = 60.0
    }
}

