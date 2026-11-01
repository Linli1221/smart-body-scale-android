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
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 测量主页状态持有者：BLE 称重会话状态机 + 成员绑定 + 设备配对/切换。
 *
 * 会话语义（与旧实现逐条对齐，保证全功能复刻）：
 * 1. 体重稳定锁定 → 生成会话 UUID，**立即写库**（无阻抗也先存体重/BMI）；
 * 2. 阻抗稍后到达 → 用同一 sessionId 覆盖写（REPLACE 幂等），兼容有/无 BIA 两种称重；
 * 3. 过早锁定（实时体重超锁定值 3.0kg）→ 会话作废并删除已写记录；
 * 4. 归零后 2.5s 内重新锁定且体重差 ≤2.0kg → 视为同一次称重（续接）；
 * 5. 阻抗延迟补写窗口：下秤后 3s 内到达的阻抗仍写回上一条记录；
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
                android.util.Log.e(TAG, "重绑成员失败", e)
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
            val upserted = ScaleMemoryUpsert.upsert(state.rememberedScales, incoming)
            state.copy(
                rememberedScales = upserted,
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

    /** 切换「当前使用设备」：不打断进行中的称重（自动连接白名单含全部已记住设备）。 */
    fun switchActiveScale(mac: String) {
        if (mac.isBlank()) return
        AppLogger.i(TAG, "切换当前设备 $mac")
        val target = _uiState.value.rememberedScales.firstOrNull { it.mac == mac }
        _uiState.update { state ->
            state.copy(
                activeScaleMac = mac,
                pairedDeviceMac = mac,
                pairedDeviceName = target?.name ?: state.pairedDeviceName
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
        bleClient.stopScan()
        bleClient.isPairingMode = false
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
            bleClient.weight.collect { rawWeight -> onWeightChanged(rawWeight) }
        }
        viewModelScope.launch {
            bleClient.isStable.collect { stable -> onStableChanged(stable) }
        }
        viewModelScope.launch {
            bleClient.impedance.collect { z -> onImpedanceChanged(z) }
        }
        viewModelScope.launch {
            bleClient.discoveredScales.collect { list ->
                _uiState.update { it.copy(discoveredScales = list) }
            }
        }
    }

    /** 实时体重：显示取整、锁定后示数校准、过早锁定作废、离秤兜底提交。 */
    private suspend fun onWeightChanged(rawWeight: Double) {
        // 显示精度 0.01kg 取整（防高频重组）；入库始终用原始值
        _uiState.update {
            it.copy(liveWeightKg = (rawWeight * 100).roundToInt() / 100.0)
        }
        val stable = bleClient.isStable.value
        if (rawWeight < MIN_VALID_WEIGHT_KG || !stable) {
            _uiState.update { it.copy(impedanceOhm = 0.0) }
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
                // 锁定中示数校准：|Δ| ≥ 0.005kg 时同 sessionId 重写库
                abs(rawWeight - currentLockedWeightKg) >= CALIBRATION_EPSILON_KG -> {
                    currentLockedWeightKg = rawWeight
                    processMeasurement(commitToDb = true)
                }
            }
        }

        // 离秤（0kg）：兜底提交未写库会话；结果卡保留到下一次会话
        if (rawWeight <= 0.0) {
            if (sessionId != null && !sessionCommitted && currentLockedWeightKg >= MIN_VALID_WEIGHT_KG) {
                AppLogger.i(TAG, "离秤兜底提交会话 $sessionId")
                processMeasurement(commitToDb = true)
            }
            if (sessionId != null || activeSessionId != null) {
                activeSessionId = null
                hasAlertedForCurrentSession = false
                sessionInterrupted = true
                lastWeightZeroMs = System.currentTimeMillis()
                // lastLockedSessionId 保留 3s 阻抗延迟补写窗口，之后清空
                val pending = lastLockedSessionId
                if (pending != null) {
                    viewModelScope.launch {
                        delay(LAST_LOCKED_RETAIN_MS)
                        if (lastLockedSessionId == pending && activeSessionId == null) {
                            lastLockedSessionId = null
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
            val weight = bleClient.weight.value
            if (weight < MIN_VALID_WEIGHT_KG) return
            val previousLockedWeightKg = currentLockedWeightKg
            val now = System.currentTimeMillis()
            val continuation = lastLockedSessionId != null && (
                !sessionInterrupted ||
                    (now - lastWeightZeroMs <= CONTINUATION_AFTER_ZERO_GRACE_MS &&
                        abs(weight - previousLockedWeightKg) <= WEIGHT_CONTINUATION_TOLERANCE_KG)
                )
            currentLockedWeightKg = weight
            if (continuation && lastLockedSessionId != null) {
                activeSessionId = lastLockedSessionId
                AppLogger.i(TAG, "会话续接 $activeSessionId（|Δw| 宽限内重新锁定）")
                processMeasurement(commitToDb = true)
            } else {
                activeSessionId = UUID.randomUUID().toString()
                activeSessionTimestamp = now
                sessionCommitted = false
                sessionInterrupted = false
                AppLogger.i(TAG, "稳定锁定 ${weight}kg，新会话 $activeSessionId")
                processMeasurement(commitToDb = true)
            }
            // 登记最近锁定会话：下秤后 3s 内到达的阻抗靠它补写回同一条记录
            lastLockedSessionId = activeSessionId
        } else {
            _uiState.update { it.copy(impedanceOhm = 0.0) }
            // 不稳定持续 2s 后才清会话（短暂波动不作废）
            val sessionId = activeSessionId
            clearSessionJob?.cancel()
            clearSessionJob = viewModelScope.launch {
                delay(UNSTABLE_CLEAR_DELAY_MS)
                if (activeSessionId == sessionId && !bleClient.isStable.value) {
                    activeSessionId = null
                    hasAlertedForCurrentSession = false
                }
            }
        }
    }

    /** 阻抗到达：3s 补写窗口内写回同一条记录（兼容「先锁体重、后出阻抗」的 BIA 流程）。 */
    private suspend fun onImpedanceChanged(impedanceOhm: Double) {
        if (impedanceOhm <= 0.0) return
        val sessionId = activeSessionId ?: lastLockedSessionId ?: _uiState.value.currentMeasurement?.id
        if (sessionId.isNullOrBlank()) return
        if (currentLockedWeightKg < MIN_VALID_WEIGHT_KG) return
        val live = bleClient.weight.value
        val lockStillValid = live <= 0.0 || live <= currentLockedWeightKg + SUPERSEDE_THRESHOLD_KG
        if (!lockStillValid) return
        _uiState.update { it.copy(impedanceOhm = impedanceOhm) }
        AppLogger.i(TAG, "阻抗到达 ${impedanceOhm.toInt()}Ω，补写会话 $sessionId")
        processMeasurement(commitToDb = true, explicitImpedance = impedanceOhm, explicitSessionId = sessionId)
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
                    // 全部移除 → 自动连接暂停，直到重新绑定
                    macs.isEmpty() -> bleClient.disconnectAndReset()
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
                        pairedDeviceMac = mac,
                        pairedDeviceName = scale?.name
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
        explicitImpedance: Double? = null,
        explicitSessionId: String? = null
    ) {
        val weight = currentLockedWeightKg
        if (weight < MIN_VALID_WEIGHT_KG) return
        val impedance = explicitImpedance
            ?: if (bleClient.isStable.value) _uiState.value.impedanceOhm else 0.0
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
            try {
                val (saved, bound) = repository.saveMeasurement(
                    measurement = measurement,
                    targetMember = selected,
                    existingId = sessionId,
                    biaModel = selectedBiaModel
                )
                final = saved
                sessionCommitted = true
                AppLogger.i(TAG, "记录已保存 id=$sessionId 体重=${saved.weightKg}kg 成员=${bound?.name ?: "未绑定"}")
                scheduleHealthSync(saved)
            } catch (e: Exception) {
                AppLogger.e(TAG, "记录保存失败：${e.localizedMessage}")
                android.util.Log.e(TAG, "saveMeasurement failed", e)
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
            val birth = member.birthDateEpochMs
            if (birth <= 0L) DEFAULT_USER_AGE
            else {
                val years = ((System.currentTimeMillis() - birth) / (365L * 24 * 3600 * 1000)).toInt()
                if (years in MIN_USER_AGE..MAX_USER_AGE) years else DEFAULT_USER_AGE
            }
        }
        bleClient.afuB1UserAge = age
        bleClient.afuB1UserIsMale = member.sex == Sex.MALE
        bleClient.afuB1UserTargetKg = if (member.referenceWeightKg > 0) member.referenceWeightKg else DEFAULT_TARGET_KG
    }

    private fun getSessionTimestamp(): Long =
        if (activeSessionTimestamp > 0L) activeSessionTimestamp else System.currentTimeMillis()

    private fun clearSession() {
        activeSessionId = null
        sessionCommitted = false
        hasAlertedForCurrentSession = false
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

        /** 归零后续接宽限（ms） */
        const val CONTINUATION_AFTER_ZERO_GRACE_MS = 2500L

        /** 续接体重容差（kg） */
        const val WEIGHT_CONTINUATION_TOLERANCE_KG = 2.0

        /** 阻抗延迟补写窗口（ms） */
        const val LAST_LOCKED_RETAIN_MS = 3000L

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

/** 已记住秤列表的幂等 upsert（同 MAC 原地更新并置顶，不清除其它设备）。 */
private object ScaleMemoryUpsert {
    fun upsert(scales: List<RememberedScale>, incoming: RememberedScale): List<RememberedScale> {
        val filtered = scales.filter { it.mac != incoming.mac }
        return listOf(incoming) + filtered
    }
}
