package com.example.dianzicheng.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dianzicheng.BuildConfig
import com.example.dianzicheng.data.backup.WebDavManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 蓝牙协议抓包文件的落盘目录名（cacheDir 下的子目录）。
 *
 * ⚠️ 必须与 `app/src/main/res/xml/file_paths.xml` 中声明的 `<cache-path path="...">` 保持一致，
 * 否则 [FileProvider.getUriForFile] 会抛出 IllegalArgumentException 导致分享失败。
 */
private const val BLE_CAPTURE_DIR_NAME = "ble-protocol"

/**
 * FileProvider authority。
 *
 * ⚠️ 必须与 `AndroidManifest.xml` 中 provider 的 `android:authorities="${applicationId}.fileprovider"` 一致。
 */
private const val BLE_CAPTURE_AUTHORITY_SUFFIX = ".fileprovider"

/**
 * "我的"（设置/个人）页面的 ViewModel。
 *
 * 负责：
 * 1. 家庭成员的增删管理
 * 2. Health Connect 同步开关控制与批量历史数据同步
 * 3. WebDAV 云端备份的配置、测试连接、立即备份和数据恢复
 * 4. **Debug 专用**：应用运行日志查看器的对外暴露（Release 版本不提供该入口）
 * 5. **Debug 专用**：蓝牙协议完整抓包（[BleProtocolRecorder]）的查看、复制与文件分享
 *
 * 说明：第 4、5 项的入口由 `BuildConfig.RUNTIME_LOG_VIEWER` / `BuildConfig.BLE_PROTOCOL_CAPTURE`
 * 控制，两个开关在 Release 构建中恒为 `false`，因此正式包既看不到运行日志查看器，
 * 也看不到蓝牙协议抓包入口。
 */
class ProfileViewModel(
    private val repository: ProfileRepository,
    private val preferenceManager: PreferenceManager,
    private val webDavManager: WebDavManager? = null,
    private val healthConnectManager: HealthConnectManager? = null,
    private val scaleRepository: ScaleRepository? = null
) : ViewModel() {

    // ── 家庭成员 ──────────────────────────────────────────────────────────────

    /** 所有家庭成员列表（Flow，数据库变更时自动推送），初始值为空列表 */
    val members: StateFlow<List<FamilyMember>> = repository.getMembers()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── 设备配对设置 ─────────────────────────────────────────────────────────

    /**
     * 全部已记住的体脂秤（最近使用优先）。
     *
     * 支持同时记住多台（如家中、父母家各一台），界面据此渲染设备列表并允许切换/移除。
     */
    val rememberedScales: StateFlow<List<RememberedScale>> = preferenceManager.rememberedScales
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 当前使用中的体脂秤 MAC 地址（多台已记住设备中选中的那台） */
    val pairedMac: StateFlow<String?> = preferenceManager.pairedMac
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** 当前使用中的体脂秤名称 */
    val pairedDeviceName: StateFlow<String?> = preferenceManager.pairedDeviceName
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // ── 体脂秤型号设置 ────────────────────────────────────────────────────────

    /** 当前选中的体脂秤型号（来自 DataStore，默认自动识别） */
    val selectedScaleModel: StateFlow<ScaleModel> = preferenceManager.selectedScaleModel
        .map { ScaleModel.fromId(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, ScaleModel.AUTO)

    /**
     * 保存用户选择的体脂秤型号偏好
     */
    fun selectScaleModel(model: ScaleModel) {
        viewModelScope.launch {
            preferenceManager.saveSelectedScaleModel(model.id)
            AppLogger.i("ProfileVM", "已切换体脂秤型号偏好为: ${model.displayName} (${model.id})")
        }
    }

    // ── 电阻抗估算模型设置 ────────────────────────────────────────────────────

    /**
     * 当前选中的电阻抗（BIA）体成分估算模型（来自 DataStore，默认 [BiaModel.DEFAULT]）。
     *
     * 该模型决定称重时体脂率（及派生指标）的计算公式；切换时除立即生效外，
     * 还会触发 [selectBiaModel] 内的历史记录重算。
     */
    val selectedBiaModel: StateFlow<BiaModel> = preferenceManager.selectedBiaModel
        .stateIn(viewModelScope, SharingStarted.Lazily, BiaModel.DEFAULT)

    /**
     * 保存用户选择的电阻抗估算模型偏好，并**重新计算全部历史测量记录**。
     *
     * 历史重算与偏好保存在同一次用户操作内完成：
     * 1. 先把新模型 ID 持久化，保证后续新称重立即按新公式计算；
     * 2. 再把数据库中所有「已绑定成员且有有效阻抗」的记录换算到同一口径，
     *   避免历史曲线因切换公式而前后断层。
     *
     * 被跳过的记录（未绑定成员 / 未赤脚测阻抗）保持原值，并通过 [recalcProgress]
     * 汇总告知用户。
     *
     * @param model 新选择的电阻抗估算模型
     */
    fun selectBiaModel(model: BiaModel) {
        viewModelScope.launch {
            preferenceManager.saveSelectedBiaModel(model)
            AppLogger.i("ProfileVM", "已切换电阻抗估算模型为: ${model.displayName} (${model.id})，开始重算历史记录")

            val scaleRepo = scaleRepository ?: run {
                AppLogger.w("ProfileVM", "未注入 ScaleRepository，跳过历史重算")
                _statusMessage.value = "已切换估算模型，但无法访问数据库，历史记录未重算"
                return@launch
            }

            _isOperating.value = true
            _recalcProgress.value = "正在用「${model.displayName}」重算历史记录..."
            try {
                val result = scaleRepo.recalculateHistory(model)
                _recalcProgress.value = null
                _isOperating.value = false
                _statusMessage.value = result.summaryText()
                AppLogger.i(
                    "ProfileVM",
                    "历史重算完成: 共 ${result.total} 条, 更新 ${result.updated} 条, " +
                        "跳过(无成员) ${result.skippedNoMember} 条, 跳过(无阻抗) ${result.skippedNoImpedance} 条, " +
                        "未变化 ${result.unchanged} 条"
                )
            } catch (e: Exception) {
                _recalcProgress.value = null
                _isOperating.value = false
                _statusMessage.value = "历史记录重算失败：${e.localizedMessage}"
                AppLogger.e("ProfileVM", "历史记录重算失败: ${e.message}")
            }
        }
    }

    // ── Health Connect 设置 ───────────────────────────────────────────────────

    /** Health Connect 自动同步开关状态（来自 DataStore） */
    val healthConnectEnabled: StateFlow<Boolean> = preferenceManager.healthConnectEnabled
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    // ── WebDAV 配置 ───────────────────────────────────────────────────────────

    /** WebDAV 服务器地址（来自 DataStore，未配置时为空字符串） */
    val webdavUrl: StateFlow<String> = preferenceManager.webdavUrl
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    /** WebDAV 登录账号 */
    val webdavUsername: StateFlow<String> = preferenceManager.webdavUsername
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    /** WebDAV 登录密码（明文存储在 DataStore，仅限本地使用） */
    val webdavPassword: StateFlow<String> = preferenceManager.webdavPassword
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    /** 上次成功备份的时间戳（毫秒），0L 表示从未备份 */
    val lastBackupTime: StateFlow<Long> = preferenceManager.lastBackupTime
        .stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    // ── 操作状态 ──────────────────────────────────────────────────────────────

    /**
     * 操作结果消息（一次性），用于在 UI 中弹出 Toast。
     * UI 消费后应调用 [clearStatusMessage] 置为 null，防止重复弹出。
     */
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /**
     * 是否正在执行耗时操作（WebDAV 测试/备份/恢复、Health Connect 同步等）。
     * UI 中相关按钮在此为 true 时显示 loading 状态并禁用点击。
     */
    private val _isOperating = MutableStateFlow(false)
    val isOperating: StateFlow<Boolean> = _isOperating.asStateFlow()

    /**
     * 批量同步进度描述文字（如 "正在同步 23 条记录..."）。
     * 同步完成或出错后置为 null，UI 据此显示/隐藏进度条。
     */
    private val _syncProgress = MutableStateFlow<String?>(null)
    val syncProgress: StateFlow<String?> = _syncProgress.asStateFlow()

    /**
     * 历史记录重算进度描述文字（如 "正在用「亚洲人专属模型」重算历史记录..."）。
     *
     * 切换电阻抗估算模型时会同步重算全部历史体脂数据；重算完成或出错后置为 null，
     * UI 据此显示/隐藏进度条。与 [syncProgress] 相互独立，互不打断。
     */
    private val _recalcProgress = MutableStateFlow<String?>(null)
    val recalcProgress: StateFlow<String?> = _recalcProgress.asStateFlow()

    // ── 家庭成员管理 ─────────────────────────────────────────────────────────

    /**
     * 添加新家庭成员并保存到数据库。
     *
     * @param name            成员姓名
     * @param sex             性别
     * @param heightCm        身高（厘米）
     * @param birthDateEpochMs 出生日期（Unix 毫秒时间戳）
     * @param weightKg        初始参考体重（公斤），首次添加时通常为 0.0
     */
    fun addMember(name: String, sex: Sex, heightCm: Double, birthDateEpochMs: Long, weightKg: Double) {
        viewModelScope.launch {
            val member = FamilyMember(
                id = UUID.randomUUID().toString(), // 生成全局唯一 ID
                name = name,
                sex = sex,
                heightCm = heightCm,
                birthDateEpochMs = birthDateEpochMs,
                referenceWeightKg = weightKg
            )
            repository.saveMember(member)
        }
    }

    /** 从数据库删除指定家庭成员 */
    fun deleteMember(member: FamilyMember) {
        viewModelScope.launch { repository.deleteMember(member) }
    }

    /**
     * 移除一台已记住的体脂秤。
     *
     * 移除最后一台时会把配对状态重置为 false，界面回到配对引导页；
     * 仍有其它设备时仅从列表中删除该台，不影响自动连接。
     */
    fun removeScale(mac: String) {
        viewModelScope.launch { preferenceManager.removeRememberedScale(mac) }
    }

    /** 把指定设备切换为当前使用中的体脂秤 */
    fun setActiveScale(mac: String) {
        viewModelScope.launch { preferenceManager.setActiveScale(mac) }
    }

    // ── Health Connect ─────────────────────────────────────────────────────

    /** 更新 Health Connect 自动同步开关状态到 DataStore */
    fun setHealthConnectEnabled(enabled: Boolean) {
        viewModelScope.launch { preferenceManager.setHealthConnectEnabled(enabled) }
    }

    /** 检查当前设备是否支持并已安装 Health Connect */
    fun isHealthConnectAvailable(): Boolean = healthConnectManager?.isAvailable() == true

    /**
     * 将所有历史测量数据批量同步到 Health Connect。
     *
     * 前置条件：
     * 1. 设备支持 Health Connect
     * 2. 已获得 HealthPermission 读写权限
     * 3. 数据库中有历史记录
     *
     * 小米健康 App 通过 Health Connect 接口读取这些同步后的数据。
     */
    fun batchSyncToHealthConnect() {
        val hcManager = healthConnectManager ?: run {
            _statusMessage.value = "当前设备不支持 Health Connect"
            return
        }
        val scaleRepo = scaleRepository ?: run {
            _statusMessage.value = "无法访问数据库"
            return
        }
        viewModelScope.launch {
            if (!hcManager.isAvailable()) {
                _statusMessage.value = "当前设备不支持 Health Connect，请升级系统或安装 Health Connect 应用"
                return@launch
            }
            if (!hcManager.hasAllPermissions()) {
                _statusMessage.value = "请先在上方开启\"同步至系统健康\"并授予权限，再进行批量同步"
                return@launch
            }
            _isOperating.value = true
            _syncProgress.value = "正在读取历史记录..."
            try {
                val allMeasurements = scaleRepo.getAllMeasurements()
                if (allMeasurements.isEmpty()) {
                    _syncProgress.value = null
                    _isOperating.value = false
                    _statusMessage.value = "暂无历史测量数据可同步"
                    return@launch
                }
                _syncProgress.value = "正在同步 ${allMeasurements.size} 条记录..."
                val (success, failed) = hcManager.batchWriteMeasurements(allMeasurements)
                _syncProgress.value = null
                _isOperating.value = false
                _statusMessage.value = if (failed == 0) {
                    "✅ 成功同步 $success 条数据！小米健康将自动读取这些数据。"
                } else {
                    "同步完成：成功 $success 条，失败 $failed 条"
                }
            } catch (e: Exception) {
                _syncProgress.value = null
                _isOperating.value = false
                _statusMessage.value = "同步失败：${e.localizedMessage}"
            }
        }
    }

    /** 跳转到小米健康 App（已安装则直接打开，未安装则跳转应用市场） */
    fun openMiHealth() { healthConnectManager?.openMiHealth() }

    /** 跳转到系统 Health Connect 设置页面，用于管理权限 */
    fun openHealthConnectSettings() { healthConnectManager?.openHealthConnectSettings() }

    /** 检查小米健康 App 是否已安装 */
    fun isMiHealthInstalled(): Boolean = healthConnectManager?.isMiHealthInstalled() == true

    // ── WebDAV 备份 ───────────────────────────────────────────────────────────

    /** 保存 WebDAV 服务器配置（URL、账号、密码）到 DataStore */
    fun saveWebdavConfig(url: String, user: String, pass: String) {
        viewModelScope.launch { preferenceManager.saveWebdavConfig(url, user, pass) }
    }

    /**
     * 测试 WebDAV 连接是否可用（不保存配置，仅验证连通性）。
     * 测试期间 [isOperating] 为 true，结果通过 [statusMessage] 展示。
     */
    fun testWebdavConnection(url: String, user: String, pass: String) {
        val manager = webDavManager ?: return
        viewModelScope.launch {
            _isOperating.value = true
            val result = manager.testConnection(url, user, pass)
            _isOperating.value = false
            _statusMessage.value = result.getOrElse { it.localizedMessage }
        }
    }

    /**
     * 立即执行数据备份到 WebDAV 服务器。
     * 备份成功后更新 [lastBackupTime]，结果通过 [statusMessage] 展示。
     */
    fun backupData() {
        val manager = webDavManager ?: return
        viewModelScope.launch {
            _isOperating.value = true
            val url = webdavUrl.value
            val user = webdavUsername.value
            val pass = webdavPassword.value
            if (url.isBlank()) {
                _isOperating.value = false
                _statusMessage.value = "请先配置 WebDAV 服务器地址"
                return@launch
            }
            val result = manager.backupData(url, user, pass)
            _isOperating.value = false
            result.onSuccess { msg ->
                preferenceManager.saveLastBackupTime(System.currentTimeMillis())
                _statusMessage.value = msg
            }.onFailure { err ->
                _statusMessage.value = err.localizedMessage
            }
        }
    }

    /**
     * 从 WebDAV 服务器恢复数据。
     * 恢复过程中 [isOperating] 为 true，结果通过 [statusMessage] 展示。
     */
    fun restoreData() {
        val manager = webDavManager ?: return
        viewModelScope.launch {
            _isOperating.value = true
            val url = webdavUrl.value
            val user = webdavUsername.value
            val pass = webdavPassword.value
            if (url.isBlank()) {
                _isOperating.value = false
                _statusMessage.value = "请先配置 WebDAV 服务器地址"
                return@launch
            }
            val result = manager.restoreData(url, user, pass)
            _isOperating.value = false
            _statusMessage.value = result.getOrElse { it.localizedMessage }
        }
    }

    // ── 杂项 ──────────────────────────────────────────────────────────────────

    /** 清除状态消息（UI 消费 Toast 后调用，防止重复弹出） */
    fun clearStatusMessage() { _statusMessage.value = null }

    /** 清除同步进度文字（同步完成/出错后调用） */
    fun clearSyncProgress() { _syncProgress.value = null }

    /** 清除历史重算进度文字（重算完成/出错后调用） */
    fun clearRecalcProgress() { _recalcProgress.value = null }

    // ── 日志 ──────────────────────────────────────────────────────────────────

    /**
     * 当前构建是否提供「查看运行日志」入口（**Debug 专用**）。
     *
     * 由 `BuildConfig.DEBUG && BuildConfig.RUNTIME_LOG_VIEWER` 决定：
     * Release 版本恒为 false，UI 中「查看运行日志」入口与日志查看弹窗都会被移除。
     * 注意：[logEntries] 仍然照常采集（Release 下仅用于 Logcat），只是没有查看入口。
     */
    val isLogViewerSupported: Boolean = BuildConfig.DEBUG && BuildConfig.RUNTIME_LOG_VIEWER

    /**
     * 实时日志条目列表（来自 [AppLogger] 的 StateFlow）。
     * 日志查看器 UI 订阅此 Flow，每次有新日志写入时自动刷新。
     */
    val logEntries: StateFlow<List<AppLogger.LogEntry>> = AppLogger.entries
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * 清空所有日志记录（包括 UI 显示和内存缓冲区）。
     * 清空后会写入一条 INFO 日志标记清除操作。
     */
    fun clearLogs() {
        AppLogger.clear()
        AppLogger.i("ProfileVM", "日志已清除")
    }

    /**
     * 将所有日志导出为纯文本字符串，供外部分享（通过 Intent.ACTION_SEND）或复制。
     *
     * @return 格式为 "[HH:mm:ss.SSS] LEVEL tag: message" 的多行文本
     */
    fun exportLogsText(): String = AppLogger.exportText()

    // ── Debug 专用：蓝牙协议完整抓包 ───────────────────────────────────────────

    /**
     * 当前构建是否支持蓝牙协议抓包导出。
     *
     * 由 `BuildConfig.DEBUG && BuildConfig.BLE_PROTOCOL_CAPTURE` 决定：
     * Release 版本恒为 false，UI 中整个「蓝牙协议抓包」入口都会被移除。
     */
    val isBleCaptureSupported: Boolean = BuildConfig.DEBUG && BuildConfig.BLE_PROTOCOL_CAPTURE

    /**
     * 蓝牙协议抓包记录流（实时刷新）。
     *
     * Release 构建下 [BleProtocolRecorder.entries] 恒为空列表，UI 不会渲染该入口。
     * 注：不复用 AppLogger 的 1000 条环形缓冲，抓包使用独立且更大的缓冲（见 [BleProtocolRecorder.MAX_ENTRIES]）。
     */
    val bleCaptureEntries: StateFlow<List<BleProtocolRecorder.Entry>> = BleProtocolRecorder.entries
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 清空蓝牙协议抓包缓冲。 */
    fun clearBleCapture() {
        BleProtocolRecorder.clear()
        _statusMessage.value = "蓝牙协议抓包已清空"
    }

    /** 导出抓包为 JSON Lines 文本（每行一个 JSON 对象，便于脚本解析）。 */
    fun exportBleCaptureJson(): String = BleProtocolRecorder.exportJsonLines()

    /** 导出抓包为人类可读对齐文本（含完整十六进制载荷）。 */
    fun exportBleCaptureText(): String = BleProtocolRecorder.exportText()

    /**
     * 将抓包写入 cacheDir/ble-protocol 并返回可直接用于 `startActivity` 的分享 Intent。
     *
     * 采用**文件分享**而非剪贴板：一次完整称重的报文可达数百 KB，
     * 直接放入剪贴板既容易触发系统大小限制，也不便于在电脑端做协议离线分析。
     *
     * 安全约束（与 file_paths.xml 严格对齐）：
     * - 仅写入 `cacheDir/ble-protocol/`，该目录在 [file_paths.xml] 中才被 FileProvider 暴露；
     * - 通过 `Intent.FLAG_GRANT_READ_URI_PERMISSION` 临时授权，接收方无法访问其它文件。
     *
     * @return 分享 Intent；未开启抓包、无数据或构建不支持时返回 null（调用方不应再启动 Activity）
     */
    suspend fun createBleCaptureShareIntent(context: Context): Intent? {
        if (!isBleCaptureSupported) return null
        val entries = BleProtocolRecorder.entries.value
        if (entries.isEmpty()) {
            _statusMessage.value = "暂无抓包数据，请先连接体脂秤并完成一次称重"
            return null
        }

        val jsonl = BleProtocolRecorder.exportJsonLines()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "scale-ble-capture-$stamp.jsonl"

        return withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, BLE_CAPTURE_DIR_NAME)
                if (!dir.exists() && !dir.mkdirs()) {
                    AppLogger.e("ProfileVM", "创建抓包目录失败: ${dir.absolutePath}")
                    _statusMessage.value = "导出失败：无法创建缓存目录"
                    return@withContext null
                }
                // 清理历史抓包，避免 cache 目录无限膨胀
                dir.listFiles()?.forEach { stale ->
                    if (stale.isFile && stale.name.startsWith("scale-ble-capture-")) stale.delete()
                }

                val file = File(dir, fileName)
                file.writeText(jsonl, Charsets.UTF_8)

                val authority = context.packageName + BLE_CAPTURE_AUTHORITY_SUFFIX
                val uri = FileProvider.getUriForFile(context, authority, file)

                AppLogger.i("ProfileVM", "蓝牙协议抓包已导出: $fileName (${entries.size} 条, ${file.length()} 字节)")
                _statusMessage.value = "已导出 ${entries.size} 条协议报文"

                Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, fileName)
                    putExtra(Intent.EXTRA_TEXT, "智能体脂秤 蓝牙协议完整抓包（${entries.size} 条报文，JSON Lines 格式）")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // 兼容部分接收方对 ClipData 授权的要求
                    clipData = android.content.ClipData.newRawUri(fileName, uri)
                }
            } catch (e: Exception) {
                AppLogger.e("ProfileVM", "导出蓝牙协议抓包失败: ${e.javaClass.simpleName}: ${e.message}")
                _statusMessage.value = "导出失败：${e.localizedMessage}"
                null
            }
        }
    }
}
