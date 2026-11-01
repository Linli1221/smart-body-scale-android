package com.example.dianzicheng.ui.state

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dianzicheng.BuildConfig
import com.example.dianzicheng.data.ble.BleProtocolRecorder
import com.example.dianzicheng.data.health.HealthConnectManager
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ProfileRepository
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.BiaModel
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.RememberedScale
import com.example.dianzicheng.domain.ScaleModel
import com.example.dianzicheng.domain.Sex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 「我的 / 设置」状态持有者：成员管理、设备管理、体脂模型切换（含历史重算）、
 * Health Connect 同步、运行日志与协议抓包查看器。
 *
 * 一次性反馈沿用 `statusMessage`（StateFlow<String?> + 消费后 clear）的既有契约。
 */
class ProfileViewModel(
    private val repository: ProfileRepository,
    private val preferenceManager: PreferenceManager,
    private val healthConnectManager: HealthConnectManager? = null,
    private val scaleRepository: ScaleRepository? = null
) : ViewModel() {

    // ── 成员 / 设备 ─────────────────────────────────────────────────────────
    val members: StateFlow<List<FamilyMember>> = repository.getMembers()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val rememberedScales: StateFlow<List<RememberedScale>> = preferenceManager.rememberedScales
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val pairedMac: StateFlow<String?> = preferenceManager.pairedMac
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val selectedScaleModel: StateFlow<ScaleModel> = preferenceManager.selectedScaleModel
        .map { ScaleModel.fromId(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, ScaleModel.AUTO)

    val selectedBiaModel: StateFlow<BiaModel> = preferenceManager.selectedBiaModel
        .stateIn(viewModelScope, SharingStarted.Lazily, BiaModel.DEFAULT)

    val healthConnectEnabled: StateFlow<Boolean> = preferenceManager.healthConnectEnabled
        .stateIn(viewModelScope, SharingStarted.Lazily, false)
    val healthSyncError: StateFlow<String?> = healthConnectManager?.syncError ?: MutableStateFlow(null)
    private val _healthPermissionsGranted = MutableStateFlow<Boolean?>(null)
    val healthPermissionsGranted: StateFlow<Boolean?> = _healthPermissionsGranted.asStateFlow()

    fun refreshHealthPermissions() {
        viewModelScope.launch {
            _healthPermissionsGranted.value = try {
                healthConnectManager?.hasAllPermissions() == true
            } catch (e: Exception) {
                false
            }
        }
    }

    // ── 操作状态 ────────────────────────────────────────────────────────────
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isOperating = MutableStateFlow(false)
    val isOperating: StateFlow<Boolean> = _isOperating.asStateFlow()

    private val _syncProgress = MutableStateFlow<String?>(null)
    val syncProgress: StateFlow<String?> = _syncProgress.asStateFlow()

    private val _recalcProgress = MutableStateFlow<String?>(null)
    val recalcProgress: StateFlow<String?> = _recalcProgress.asStateFlow()

    // ── 调试查看器数据源 ────────────────────────────────────────────────────
    val logEntries: StateFlow<List<AppLogger.LogEntry>> = AppLogger.entries
    val bleCaptureEntries: StateFlow<List<BleProtocolRecorder.Entry>> = BleProtocolRecorder.entries

    /** 仅 Debug 构建提供运行日志查看器（Release 无入口、无导出）。 */
    val isLogViewerSupported: Boolean = BuildConfig.DEBUG && BuildConfig.RUNTIME_LOG_VIEWER

    /** 仅 Debug 构建提供蓝牙协议抓包（Release 一条报文都不记录）。 */
    val isBleCaptureSupported: Boolean = BuildConfig.DEBUG && BuildConfig.BLE_PROTOCOL_CAPTURE

    init {
        // 「订阅即补发」：查看器打开（订阅数 > 0）瞬间补齐快照，避免看到旧列表
        viewModelScope.launch {
            AppLogger.subscriptionCount.collect { if (it > 0) AppLogger.publishSnapshot() }
        }
        viewModelScope.launch {
            BleProtocolRecorder.subscriptionCount.collect { if (it > 0) BleProtocolRecorder.publishSnapshot() }
        }
    }

    // ── 型号 / 体脂模型 ─────────────────────────────────────────────────────

    /** 切换体脂秤型号偏好（仅持久化，不重启扫描）。 */
    fun selectScaleModel(model: ScaleModel) {
        AppLogger.i(TAG, "切换体脂秤型号：${model.displayName}")
        viewModelScope.launch { preferenceManager.saveSelectedScaleModel(model.id) }
    }

    /** 切换体脂估算模型：持久化后全量重算历史记录（历史曲线保持同一口径）。 */
    fun selectBiaModel(model: BiaModel) {
        viewModelScope.launch {
            preferenceManager.saveSelectedBiaModel(model)
            val repo = scaleRepository
            if (repo == null) {
                _statusMessage.value = "已切换估算模型，但无法访问数据库，历史记录未重算"
                return@launch
            }
            _recalcProgress.value = "正在用「${model.displayName}」重算历史记录..."
            _isOperating.value = true
            try {
                val result = repo.recalculateHistory(model)
                _statusMessage.value = result.summaryText()
            } catch (e: Exception) {
                _statusMessage.value = "历史记录重算失败：${e.localizedMessage}"
            } finally {
                _isOperating.value = false
                _recalcProgress.value = null
            }
        }
    }

    // ── 成员管理 ────────────────────────────────────────────────────────────

    /** 添加家庭成员（UUID 主键）。 */
    fun addMember(name: String, sex: Sex, heightCm: Double, birthDateEpochMs: Long, weightKg: Double) {
        viewModelScope.launch {
            repository.saveMember(
                FamilyMember(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    sex = sex,
                    heightCm = heightCm,
                    birthDateEpochMs = birthDateEpochMs,
                    referenceWeightKg = weightKg
                )
            )
        }
    }

    /** 删除成员（级联删除其全部测量记录）。 */
    fun deleteMember(member: FamilyMember) {
        viewModelScope.launch { repository.deleteMember(member) }
    }

    // ── 设备管理 ────────────────────────────────────────────────────────────

    /** 移除已记住的体脂秤；移除最后一台时配对状态复位，界面回到绑定引导。 */
    fun removeScale(mac: String) {
        viewModelScope.launch { preferenceManager.removeRememberedScale(mac) }
    }

    /** 切换当前使用中的体脂秤。 */
    fun setActiveScale(mac: String) {
        viewModelScope.launch { preferenceManager.setActiveScale(mac) }
    }

    // ── Health Connect ───────────────────────────────────────────

    fun setHealthConnectEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferenceManager.setHealthConnectEnabled(enabled)
            refreshHealthPermissions()
        }
    }

    fun isHealthConnectAvailable(): Boolean = healthConnectManager?.isAvailable() ?: false

    fun openHealthConnectSettings() {
        healthConnectManager?.openHealthConnectSettings()
    }

    /** 把全部历史记录批量写入 Health Connect。 */
    fun batchSyncToHealthConnect() {
        if (_syncProgress.value != null) return
        val hc = healthConnectManager
        if (hc == null) {
            _statusMessage.value = "当前设备不支持 Health Connect"
            return
        }
        val repo = scaleRepository
        if (repo == null) {
            _statusMessage.value = "无法访问数据库"
            return
        }
        _syncProgress.value = "正在检查权限..."
        viewModelScope.launch {
            _isOperating.value = true
            try {
                if (!hc.isAvailable()) {
                    _statusMessage.value = "当前设备不支持 Health Connect，请升级系统或安装 Health Connect 应用"
                    return@launch
                }
                if (!hc.hasAllPermissions()) {
                    _statusMessage.value = "请先授予体重和体脂写入权限"
                    refreshHealthPermissions()
                    return@launch
                }
                _syncProgress.value = "正在读取历史记录..."
                val snapshotVersion = hc.captureSyncVersion()
                val all = repo.getAllMeasurements()
                if (all.isEmpty()) {
                    _statusMessage.value = "暂无历史测量数据可同步"
                    return@launch
                }
                _syncProgress.value = "正在同步 ${all.size} 条记录..."
                val (success, failed) = hc.batchWriteMeasurements(all, snapshotVersion)
                _statusMessage.value = if (failed == 0) {
                    "已同步 $success 条记录到系统健康"
                } else {
                    "同步完成：成功 $success 条，失败 $failed 条"
                }
            } catch (e: Exception) {
                _statusMessage.value = "同步失败：${e.localizedMessage}"
            } finally {
                _isOperating.value = false
                _syncProgress.value = null
            }
        }
    }

    // ── 一次性状态清理 ──────────────────────────────────────────────────────

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    // ── 运行日志查看器 ──────────────────────────────────────────────────────

    fun clearLogs() {
        AppLogger.clear()
        AppLogger.i(TAG, "日志已清除")
    }

    fun exportLogsText(): String = AppLogger.exportText()

    // ── 蓝牙协议抓包查看器 ──────────────────────────────────────────────────

    fun clearBleCapture() {
        BleProtocolRecorder.clear()
        _statusMessage.value = "蓝牙协议抓包已清空"
    }

    fun exportBleCaptureJson(): String = BleProtocolRecorder.exportJsonLines()

    fun exportBleCaptureText(): String = BleProtocolRecorder.exportText()

    /**
     * 导出抓包文件（JSON Lines）并通过 FileProvider 生成分享 Intent。
     *
     * 文件写入 `cacheDir/ble-protocol/`（与 res/xml/file_paths.xml 的 cache-path 对应），
     * 写前清理旧的 `scale-ble-capture-*`；authority 与 AndroidManifest 的 provider 一致。
     */
    suspend fun createBleCaptureShareIntent(context: Context): Intent? {
        if (!isBleCaptureSupported) return null
        val entries = BleProtocolRecorder.snapshot()
        if (entries.isEmpty()) {
            _statusMessage.value = "暂无抓包数据，请先连接体脂秤并完成一次称重"
            return null
        }
        return try {
            val dir = File(context.cacheDir, BLE_CAPTURE_DIR_NAME)
            if (!dir.exists() && !dir.mkdirs()) {
                _statusMessage.value = "导出失败：无法创建缓存目录"
                return null
            }
            dir.listFiles { f -> f.name.startsWith(BLE_CAPTURE_FILE_PREFIX) }?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "${BLE_CAPTURE_FILE_PREFIX}$stamp.jsonl")
            file.writeText(BleProtocolRecorder.exportJsonLines())
            val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                putExtra(
                    Intent.EXTRA_TEXT,
                    "智能体脂秤 蓝牙协议完整抓包（${entries.size} 条报文，JSON Lines 格式）"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(file.name, uri)
            }
            _statusMessage.value = "已导出 ${entries.size} 条协议报文"
            intent
        } catch (e: Exception) {
            _statusMessage.value = "导出失败：${e.localizedMessage}"
            null
        }
    }

    private companion object {
        const val TAG = "ProfileVM"
        const val BLE_CAPTURE_DIR_NAME = "ble-protocol"
        const val BLE_CAPTURE_FILE_PREFIX = "scale-ble-capture-"
        const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    }
}
