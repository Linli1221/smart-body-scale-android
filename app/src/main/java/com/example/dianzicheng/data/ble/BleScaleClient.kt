package com.example.dianzicheng.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.domain.ScaleModel
import com.example.dianzicheng.domain.normalizeMacAddress
import com.example.dianzicheng.domain.sameMacAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

private const val TAG = "BleScaleClient"

/** 薄荷健康 B1：秤在该时长内不再上报任何数据帧，即判定用户已下秤（秤进入空闲）。 */
private const val AFUB1_IDLE_MS = 2000L

/** 薄荷健康 B1：提交流程等待秤空闲的轮询间隔。 */
private const val AFUB1_IDLE_POLL_MS = 250L

/** 薄荷健康 B1：下发「拉取本次记录」命令后，等待秤端回推最新记录的最长时间。 */
private const val AFUB1_COMMIT_TIMEOUT_MS = 15000L

/** 薄荷健康 B1：判定「本次刚产生的记录」时允许的时间偏差（秒）。 */
private const val AFUB1_FRESH_RECORD_WINDOW_S = 600L

/** 薄荷健康 B1：判定「本次刚产生的记录」时允许的体重偏差（克）。 */
private const val AFUB1_FRESH_WEIGHT_TOLERANCE_G = 1000

/**
 * BLE 蓝牙体脂秤客户端，负责完整的蓝牙生命周期管理：
 * 扫描 → 发现设备 → GATT 连接 → 服务发现 → 订阅通知 → 握手 → 数据接收与解析。
 *
 * 内部通过 [MultiScalePacketParser] 解析薄荷健康/沃莱与小米体脂秤 2 代报文；
 * 薄荷健康 B1（AFU-BH-TZ-B1）的 20 字节 AF 45 帧则由 [AFUB1PacketParser] 配合专属状态机处理，
 * 并以 [StateFlow] 的形式向上层（ViewModel / UI）暴露实时状态：
 * - [connectionState]：当前连接状态枚举
 * - [weight]：实时体重（kg）
 * - [isStable]：体重是否稳定锁定
 * - [impedance]：生物电阻抗值（Ω）
 *
 * @param context Android 上下文，用于获取蓝牙系统服务。
 */
@SuppressLint("MissingPermission")
class BleScaleClient(private val context: Context) {

    // -------------------------------------------------------------------------
    // 蓝牙适配器与 GATT 连接
    // -------------------------------------------------------------------------

    /** 系统蓝牙适配器，从 BluetoothManager 获取；若设备无 BT 则为 null。 */
    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** 当前活跃的 GATT 连接实例；未连接时为 null。 */
    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null

    /** 支持的体脂秤服务 UUID 集合（仅薄荷健康/沃莱与小米体脂秤 2 代），用于广播过滤与服务匹配 */
    private val SUPPORTED_SERVICE_UUIDS = setOf(
        MultiScalePacketParser.UUID_SERVICE_AFU,        // 0xFFB0 (薄荷健康/沃莱 AFU 私有协议)
        MultiScalePacketParser.UUID_SERVICE_YOLANDA,    // 0xFFA0 (薄荷健康/沃莱 Yolanda 扩展)
        AFUB1PacketParser.SERVICE_UUID,                 // 0xFC50 (薄荷健康 B1 / AFU-BH-TZ-B1)
        MultiScalePacketParser.UUID_SERVICE_SIG_BCS     // 0x181B (小米体脂秤 2 代 Body Composition)
    )

    companion object {
        /** 已知体脂秤芯片厂商 Company ID 集合（0xFF 厂商自定义数据），常量避免每个广播包分配 List */
        private val KNOWN_SCALE_COMPANY_IDS = setOf(0x01A7, 0x0157, 0x0209)

        /**
         * 自动连接扫描的扫描模式：BALANCED 显著降低前台常扫功耗（扫描是本应用待机耗电大头），
         * 发现延迟约 +1~2s，而称重会话持续 10~30s，完全足够「踩秤即连」。
         * 配对扫描（[startPairingScan]）不受此常量影响，仍为 LOW_LATENCY。
         * 若需回到极低延迟的自动连接，把本常量改回 [ScanSettings.SCAN_MODE_LOW_LATENCY] 即可。
         */
        private val AUTO_SCAN_MODE = ScanSettings.SCAN_MODE_BALANCED

        /**
         * 自动连接扫描的批量上报延迟（ms）：蓝牙控制器缓存该时长内的扫描结果整批回调
         * （onBatchScanResults），把「每广播包一次主线程回调」降为约 2 次/秒。
         * 配对扫描仍为 0（即时回调），保证候选列表的实时刷新。
         */
        private const val AUTO_SCAN_REPORT_DELAY_MS = 500L
    }

    // -------------------------------------------------------------------------
    // 对外暴露的 StateFlow 状态流
    // -------------------------------------------------------------------------

    /** 连接状态（内部可写）。 */
    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    /** 连接状态（外部只读）。 */
    val connectionState: StateFlow<ConnectionState> = _connectionState

    /** 实时体重，单位 kg（内部可写）。 */
    private val _weight = MutableStateFlow(0.0)
    /** 实时体重，单位 kg（外部只读）。 */
    val weight: StateFlow<Double> = _weight

    /** 体重是否稳定锁定（内部可写）。 */
    private val _isStable = MutableStateFlow(false)
    /** 体重是否稳定锁定（外部只读）。 */
    val isStable: StateFlow<Boolean> = _isStable

    /** 生物电阻抗值，单位 Ω（内部可写）。 */
    private val _impedance = MutableStateFlow(0.0)
    /** 生物电阻抗值，单位 Ω（外部只读）。 */
    val impedance: StateFlow<Double> = _impedance

    /** 扫描发现的所有候选体脂秤列表（供用户手动选择配对）。 */
    private val _discoveredScales = MutableStateFlow<List<DiscoveredScaleDevice>>(emptyList())
    /** 扫描发现的所有候选体脂秤列表（外部只读）。 */
    val discoveredScales: StateFlow<List<DiscoveredScaleDevice>> = _discoveredScales

    // -------------------------------------------------------------------------
    // 连接状态枚举与设备模型
    // -------------------------------------------------------------------------

    /**
     * 扫描发现的候选体脂秤设备数据模型。
     */
    data class DiscoveredScaleDevice(
        val name: String,
        val address: String,
        val rssi: Int = 0,
        val isBroadcastScale: Boolean = false,
        val isConfirmedScale: Boolean = true,
        val matchedModel: ScaleModel = ScaleModel.AUTO,
        val protocolName: String = "",
        val liveWeightKg: Double? = null
    )

    /**
     * BLE 连接状态枚举，描述客户端当前所处的生命周期阶段。
     *
     * - [IDLE]：空闲，未扫描也未连接。
     * - [SCANNING]：正在执行 BLE 广播扫描。
     * - [CONNECTING]：已发现目标设备，正在建立 GATT 连接或进行服务发现。
     * - [CONNECTED]：GATT 已连接并完成全部特征订阅，等待用户踩秤。
     * - [MEASURING]：检测到有效稳定体重，正在进行测量（含阻抗采集）。
     */
    enum class ConnectionState { IDLE, SCANNING, CONNECTING, CONNECTED, MEASURING }

    // -------------------------------------------------------------------------
    // 外部可读写的辅助属性
    // -------------------------------------------------------------------------

    /** 用户手动指定的体脂秤型号偏好（默认为 AUTO 智能自动识别） */
    var preferredModel: ScaleModel = ScaleModel.AUTO

    /**
     * 当前使用中的体脂秤 MAC 地址（大写归一化）。
     *
     * 多设备场景下它表示「用户当前选中的那台」，仅用于界面展示与直连偏好；
     * 是否允许自动连接由 [rememberedMacs] 决定（任意一台已记住的秤踩上都能连）。
     *
     * 注意：该字段会被 GATT 回调线程（binder 线程）读取，因此必须是 @Volatile。
     */
    @Volatile
    var lastPairedMac: String? = null

    /**
     * 已记住的全部体脂秤 MAC 地址集合（大写归一化）。
     *
     * 由上层从 DataStore 同步写入。集合非空即允许自动连接：
     * 用户踩上其中**任意一台**都会自动建立连接并回传数据，无需手动切换。
     * 非空时扫描器只对集合内的设备发起连接，杜绝误连周围无关设备。
     *
     * 注意：该字段由主线程写入（DataStore 订阅）、被 BLE 扫描回调与 GATT 回调线程读取，
     * 因此必须是 @Volatile，避免陈旧读导致「已移除的秤仍被当作目标设备」。
     */
    @Volatile
    var rememberedMacs: Set<String> = emptySet()

    /**
     * 是否已记住至少一台体脂秤。
     *
     * 兼容旧字段 [lastPairedMac]：即使上层尚未把集合同步进来，只要仍有当前使用设备就视为已记住。
     */
    val hasRememberedDevice: Boolean
        get() = rememberedMacs.isNotEmpty() || !lastPairedMac.isNullOrEmpty()

    /**
     * 判断给定 MAC 是否属于已记住的体脂秤（忽略大小写）。
     *
     * 同时兼容 [lastPairedMac]，确保集合尚未同步时不会丢失当前使用设备。
     */
    fun isRememberedMac(mac: String?): Boolean {
        if (mac.isNullOrEmpty()) return false
        if (sameMacAddress(mac, lastPairedMac)) return true
        return rememberedMacs.any { sameMacAddress(it, mac) }
    }

    /**
     * 当前已建立（或正在建立）GATT 连接的设备 MAC 地址；未连接时为 null。
     *
     * 供上层在「已记住列表」变化后判断当前连接是否已失效，例如用户移除了正在连接的那台秤。
     */
    val connectedMac: String?
        get() = bluetoothGatt?.device?.address

    /**
     * 把一台设备补充进运行时记忆集合（仅改内存，持久化由上层负责）。
     *
     * 用于手动配对成功后的立即生效，避免等待 DataStore 异步回读造成「刚绑好却仍不自动连接」。
     */
    fun rememberMac(mac: String) {
        val normalized = normalizeMacAddress(mac)
        if (normalized.isEmpty()) return
        if (rememberedMacs.any { sameMacAddress(it, normalized) }) return
        rememberedMacs = rememberedMacs + normalized
    }

    /**
     * 当前正在上报数据的广播秤 MAC（多设备会话锁）。
     *
     * 多设备场景下，同屋另一台已记住的广播秤（如小米秤）若同时活动，其广播包会把正在进行的
     * 读数清零或互相覆盖。此字段记录「当前占用示数」的设备，会话期间其它已记住设备一律忽略。
     * 会话在下秤包（0kg）或 2.5s 无数据看门狗触发时释放，因此不会被永久卡死。
     *
     * 仅在主线程（BLE 扫描回调）读写，无需 @Volatile。
     */
    private var broadcastSessionMac: String? = null

    /**
     * 是否处于显式手动配对模式。
     * 处于配对模式时，扫描器绝不自动连接任何设备，仅将符合特征的设备收集至 [discoveredScales] 供用户手动选择。
     */
    var isPairingMode: Boolean = false

    /** MAC 地址首次确认回调，用于将新配对的 MAC 持久化到外部存储。 */
    var onMacDiscovered: ((String) -> Unit)? = null

    /** GATT 连接失败后当前的自动重连次数 */
    private var gattRetryCount = 0

    /** GATT 连接失败最大自动重连次数；超出后需用户手动触发，避免无限耗电 */
    private val MAX_GATT_RETRY = 5

    // -------------------------------------------------------------------------
    // 薄荷健康 B1 (AFU-BH-TZ-B1) 专属会话状态
    // -------------------------------------------------------------------------

    /** 当前连接设备是否为薄荷健康 B1（AFU-BH-TZ-B1，自定义服务 0xFC50）。 */
    private var isAfuB1Device = false

    /** B1 命令写入特征（af000002）所在服务与特征 UUID，连接后从 GATT 树动态解析。 */
    private var afuB1WriteServiceUuid: java.util.UUID? = null
    private var afuB1WriteCharUuid: java.util.UUID? = null

    /** B1 握手是否已完成、用户档案是否已下发。 */
    private var afuB1HandshakeDone = false
    private var afuB1ProfileSent = false

    /** 最近一次收到 B1 数据帧的时刻，用于判定秤是否已空闲（用户下秤）。 */
    private var afuB1LastFrameAt = 0L

    /** 本次称重最终锁定的体重（克），用于识别秤端回推的是否为「本次」记录。 */
    private var afuB1FinalWeightG = 0

    /** B1 提交流程状态：是否已启动、是否已下发拉取命令、截止时刻、是否已收到本次记录。 */
    private var afuB1CommitStarted = false
    private var afuB1CommitRequested = false
    private var afuB1CommitDeadline = 0L
    private var afuB1FreshRecordSeen = false
    private var afuB1CommitRunnable: Runnable? = null

    /** 已处理过的 B1 记录指纹，避免秤端反复重投同一条历史记录造成重复入库。 */
    private val afuB1SeenRecords = mutableSetOf<String>()

    /** B1 用户档案参数（年龄 / 是否男性 / 目标体重），由上层按所选家庭成员同步。 */
    var afuB1UserAge: Int = 30
    var afuB1UserIsMale: Boolean = true
    var afuB1UserTargetKg: Double = 60.0

    // -------------------------------------------------------------------------
    // 广播数据辅助解析工具方法
    // -------------------------------------------------------------------------


    /**
     * 从 BLE 广播原始字节数组中按标准 AD Structure 格式解析设备名称。
     *
     * BLE 广播数据由若干 AD 结构（length + type + value）组成。
     * 类型 0x08（缩短本地名）或 0x09（完整本地名）的值字段即为设备名称。
     *
     * @param bytes BLE ScanRecord 原始字节数组。
     * @return 解析成功返回设备名称字符串（已去除首尾空白），否则返回 null。
     */
    private fun parseNameFromBytes(bytes: ByteArray?): String? = MultiScalePacketParser.parseNameFromBytes(bytes)


    /**
     * 根据设备名称与广播包特征，推断其对应的体脂秤型号枚举。
     *
     * 仅区分薄荷健康/沃莱与小米体脂秤 2 代两类；无法判定时返回 [ScaleModel.AUTO]。
     */
    fun detectMatchedModel(
        deviceName: String?,
        scanRecord: android.bluetooth.le.ScanRecord?,
        advProvider: () -> MultiScalePacketParser.ParsedScaleData? = { MultiScalePacketParser.parseAdvertisement(scanRecord, preferredModel) }
    ): ScaleModel {
        val name = deviceName ?: ""

        // 小米体脂秤 2 代：广播名常见为 MIBFS / MISCALE / MI_SCALE / 米家 / 小米，或声明 0x181B 服务
        if (name.startsWith("MIBFS", ignoreCase = true) || name.startsWith("MISCALE", ignoreCase = true) ||
            name.equals("MI_SCALE", ignoreCase = true) || name.contains("米家", ignoreCase = true) ||
            name.contains("小米", ignoreCase = true) ||
            scanRecord?.serviceUuids?.any { it.uuid.toString().uppercase().contains("181B") } == true) {
            return ScaleModel.XIAOMI_SCALE_2
        }

        // 薄荷健康 B1 (AFU-BH-TZ-B1)：广播名以 AFU 开头（如 AFU-BH-TZ-B1 / AFU-WL-TZ-A1），服务 0xFC50
        if (name.startsWith("AFU", ignoreCase = true) ||
            scanRecord?.serviceUuids?.any { it.uuid == AFUB1PacketParser.SERVICE_UUID } == true) {
            return ScaleModel.BOOHEE_YOLANDA
        }

        // 薄荷健康 / 沃莱：0xFFB0 专属服务，或设备名包含品牌关键词
        if (scanRecord?.serviceUuids?.any { it.uuid.toString().uppercase().contains("FFB0") } == true ||
            name.contains("Yolanda", ignoreCase = true) || name.contains("Boohee", ignoreCase = true) ||
            name.contains("薄荷", ignoreCase = true) || name.contains("轻牛", ignoreCase = true) ||
            name.contains("沃莱", ignoreCase = true) || name.contains("ICOMON", ignoreCase = true) ||
            name.startsWith("BH_", ignoreCase = true) || name.startsWith("BH-", ignoreCase = true) ||
            name.contains("Fitdays", ignoreCase = true) || name.contains("合泰", ignoreCase = true)) {
            return ScaleModel.BOOHEE_YOLANDA
        }

        // 依据广播数据实际命中的协议反推型号（复用调用方的单次解析结果）
        val advData = advProvider()
        if (advData != null) {
            return if (advData.protocolName.contains("Xiaomi", ignoreCase = true)) {
                ScaleModel.XIAOMI_SCALE_2
            } else {
                ScaleModel.BOOHEE_YOLANDA
            }
        }

        return ScaleModel.AUTO
    }

    /**
     * 综合多维度规则判断某个扫描结果是否来自目标体脂秤设备。
     *
     * 匹配优先级（由高到低）：
     * 1. 已配对 MAC 地址精确匹配（最高优先级，直接返回 true）。
     * 2. 广播包中包含目标体脂秤服务 UUID（薄荷健康/沃莱 0xFFB0、小米 0x181B）。
     * 3. 设备名称包含特定体脂秤关键词（薄荷健康 / 沃莱 / 小米 / Scale / Weight / 体脂 / 电子秤）。
     * 4. 广播原始数据中厂商数据（0xFF）或服务数据（0x16）段的首字节为 0xAC 帧头。
     *
     * @param result BLE 扫描回调返回的单条扫描结果。
     * @return 判定为目标设备返回 true，否则返回 false。
     */
    private fun isScaleAdvertisement(result: ScanResult, advProvider: () -> MultiScalePacketParser.ParsedScaleData?): Boolean {
        val device = result.device
        val scanRecord = result.scanRecord
        val serviceUuids = scanRecord?.serviceUuids
        val rawBytes = scanRecord?.bytes
        // 尝试从多个来源获取设备名称（优先系统缓存，其次广播字段，最后手动解析）
        val deviceName = device.name ?: scanRecord?.deviceName ?: MultiScalePacketParser.parseNameFromBytes(rawBytes)

        // 1. 已记住的 MAC 地址精确匹配（多设备：任意一台已记住的秤都视为目标设备）
        if (isRememberedMac(device.address)) return true

        // 2. 检查黑名单厂商（Apple、Google、Microsoft、Samsung、Huawei 等设备绝对不是体脂秤）
        if (rawBytes != null && rawBytes.size >= 4) {
            var i = 0
            while (i < rawBytes.size - 3) {
                val len = rawBytes[i].toInt() and 0xFF
                if (len == 0 || i + len >= rawBytes.size) break
                val type = rawBytes[i + 1].toInt() and 0xFF
                if (type == 0xFF && len >= 3) {
                    val companyId = (rawBytes[i + 2].toInt() and 0xFF) or ((rawBytes[i + 3].toInt() and 0xFF) shl 8)
                    if (companyId in MultiScalePacketParser.NON_SCALE_COMPANY_IDS) {
                        val hasExplicitScaleName = deviceName?.let { name ->
                            name.contains("Scale", ignoreCase = true) ||
                            name.contains("Weight", ignoreCase = true) ||
                            name.contains("体脂", ignoreCase = true) ||
                            name.contains("电子秤", ignoreCase = true) ||
                            name.contains("体重", ignoreCase = true)
                        } == true
                        if (!hasExplicitScaleName) {
                            return false
                        }
                    }
                }
                i += len + 1
            }
        }

        // 3. 支持的目标体脂秤 Service UUID 匹配（薄荷健康/沃莱 0xFFB0、小米 0x181B）
        val hasService = serviceUuids?.any { it.uuid in SUPPORTED_SERVICE_UUIDS } == true
        if (hasService) return true

        // 4. 广播数据包直接可解析出有效体重（如小米秤 Service Data、免配对广播秤；复用单次解析结果）
        if (advProvider() != null) {
            return true
        }

        // 5. 称重设备常见关键词匹配（仅薄荷健康/沃莱与小米体脂秤 2 代）
        val nameMatched = deviceName?.let { name ->
            name.contains("Scale", ignoreCase = true) ||
            name.contains("Weight", ignoreCase = true) ||
            name.contains("体脂", ignoreCase = true) ||
            name.contains("电子秤", ignoreCase = true) ||
            name.contains("体重", ignoreCase = true) ||
            name.contains("称重", ignoreCase = true) ||
            name.contains("健康秤", ignoreCase = true) ||
            name.startsWith("MIBFS", ignoreCase = true) ||
            name.startsWith("MISCALE", ignoreCase = true) ||
            name.contains("小米", ignoreCase = true) ||
            name.contains("米家", ignoreCase = true) ||
            name.equals("MI_SCALE", ignoreCase = true) ||
            name.contains("Yolanda", ignoreCase = true) ||
            name.contains("ICOMON", ignoreCase = true) ||
            name.contains("沃莱", ignoreCase = true) ||
            name.contains("BOOHEE", ignoreCase = true) ||
            name.contains("薄荷", ignoreCase = true) ||
            name.startsWith("BH_", ignoreCase = true) ||
            name.startsWith("BH-", ignoreCase = true) ||
            name.contains("Fitdays", ignoreCase = true) ||
            name.contains("轻牛", ignoreCase = true) ||
            name.contains("合泰", ignoreCase = true) ||
            name.startsWith("AFU", ignoreCase = true) ||
            name.contains("TZ-B1", ignoreCase = true) ||
            name.contains("BH-TZ", ignoreCase = true)
        } == true
        if (nameMatched) return true

        // 6. 检查广播 AD 结构中是否声明了蓝牙 SIG 标准外观类型 (Appearance = 0x0400..0x043F 体重/体脂秤)
        if (rawBytes != null && rawBytes.size >= 4) {
            var i = 0
            while (i < rawBytes.size - 3) {
                val len = rawBytes[i].toInt() and 0xFF
                if (len == 0 || i + len >= rawBytes.size) break
                val type = rawBytes[i + 1].toInt() and 0xFF
                if (type == 0x19 && len >= 3) { // 0x19 = Appearance
                    val appearance = (rawBytes[i + 2].toInt() and 0xFF) or ((rawBytes[i + 3].toInt() and 0xFF) shl 8)
                    if (appearance in 0x0400..0x043F) return true
                }
                i += len + 1
            }
        }

        // 7. 解析 BLE 广播数据中的厂商自定义数据包结构 (0xFF 或 0x16)，校验已知秤芯片 Company ID 与专属协议特征
        if (rawBytes != null && rawBytes.size >= 4) {
            var i = 0
            while (i < rawBytes.size - 2) {
                val len = rawBytes[i].toInt() and 0xFF
                if (len == 0 || i + len >= rawBytes.size) break
                val type = rawBytes[i + 1].toInt() and 0xFF
                if (type == 0xFF && len >= 3) {
                    val companyId = (rawBytes[i + 2].toInt() and 0xFF) or ((rawBytes[i + 3].toInt() and 0xFF) shl 8)
                    // 0x01A7 (Yolanda / Fitdays), 0x0209 (Yolanda 变体), 0x0157 (Huami / Xiaomi)
                    if (companyId in KNOWN_SCALE_COMPANY_IDS) return true
                }
                if (type == 0x16 && len >= 2) {
                    val b0 = rawBytes[i + 2].toInt() and 0xFF
                    // 服务数据首字节为 0xAC / 0xA2 / 0xA3 / 0xCA 时判定为薄荷健康/沃莱；
                    // 0xAF 为薄荷健康 B1 (AFU-BH-TZ-B1) 的 0xFC50 服务数据帧头
                    if (b0 == 0xAC || b0 == 0xA2 || b0 == 0xA3 || b0 == 0xCA || b0 == 0xAF) return true
                }
                i += len + 1
            }
        }

        return false
    }

    // -------------------------------------------------------------------------
    // BLE 扫描回调
    // -------------------------------------------------------------------------

    /**
     * BLE 扫描事件回调。
     * - [onScanResult]：每发现一个广播包就触发一次，过滤后决定是否连接。
     * - [onScanFailed]：扫描启动失败时触发，800ms 后自动重试。
     */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanRequested || !isForeground) return
            scanRetryCount = 0
            val device = result.device

            // 【Debug 抓包】在任何过滤逻辑之前，先无差别记录每一条收到的广播包原始字节，
            // 保证抓包内容为「设备实际发出的全部广播」，不受本 App 的筛选规则影响。
            BleProtocolRecorder.recordScanResult(result)

            // 【性能】同一 ScanRecord 的广播解析（ParcelUuid/UUID 字符串/AD 段拷贝）代价可观，
            // 旧实现对同一记录最多解析 4 次（isScaleAdvertisement / detectMatchedModel / 模式1 / 模式2）。
            // 这里以惰性单次解析统一供给全部判定路径：未命中任何解析点的包一次都不解析，
            // 命中后复用同一结果，语义与逐处解析完全一致。
            val advDataLazy = lazy(LazyThreadSafetyMode.NONE) {
                MultiScalePacketParser.parseAdvertisement(result.scanRecord, preferredModel)
            }
            val advProvider: () -> MultiScalePacketParser.ParsedScaleData? = { advDataLazy.value }

            // 模式 1：处于手动配对模式，或尚未记住任何设备
            if (isPairingMode || !hasRememberedDevice) {
                val isScale = isScaleAdvertisement(result, advProvider)
                val rawBytes = result.scanRecord?.bytes
                val parsedName = device.name ?: result.scanRecord?.deviceName ?: MultiScalePacketParser.parseNameFromBytes(rawBytes)

                // 检查设备是否来自已知非秤厂商（Apple、Google、Microsoft、Samsung、Huawei 等）
                var isBlacklistedVendor = false
                if (rawBytes != null && rawBytes.size >= 4) {
                    var i = 0
                    while (i < rawBytes.size - 3) {
                        val len = rawBytes[i].toInt() and 0xFF
                        if (len == 0 || i + len >= rawBytes.size) break
                        val type = rawBytes[i + 1].toInt() and 0xFF
                        if (type == 0xFF && len >= 3) {
                            val companyId = (rawBytes[i + 2].toInt() and 0xFF) or ((rawBytes[i + 3].toInt() and 0xFF) shl 8)
                            if (companyId in MultiScalePacketParser.NON_SCALE_COMPANY_IDS) {
                                isBlacklistedVendor = true
                                break
                            }
                        }
                        i += len + 1
                    }
                }

                // 过滤规则：
                // 1. 确认为体脂秤的设备：加入列表；
                // 2. 属于已知非秤厂商：严禁加入任何列表，杜绝视觉污染与误导；
                // 3. 未确认为秤的设备：仅当有明确设备名（非匿名空串）且信号较强 (RSSI >= -75 dBm) 时，作为逃生兜底进入折叠列表。
                val shouldInclude = if (isScale) {
                    true
                } else if (!isBlacklistedVendor && !parsedName.isNullOrBlank() && result.rssi >= -75) {
                    true
                } else {
                    false
                }

                if (shouldInclude) {
                    val advScaleData = if (isScale) advProvider() else null
                    // 【Debug 抓包】记录纳入候选列表设备的广播原始 AD 结构与解析结论
                    BleProtocolRecorder.recordAdStructures(
                        source = if (isScale) "candidate-scale" else "candidate-other",
                        raw = result.scanRecord?.bytes
                    )
                    val liveWeight = if (isScale && advScaleData != null && advScaleData.weightKg in 3.0..350.0) advScaleData.weightKg else null
                    val matchedModel = detectMatchedModel(parsedName, result.scanRecord, advProvider)
                    val isXiaomi = matchedModel == ScaleModel.XIAOMI_SCALE_2 ||
                                   parsedName?.startsWith("MIBFS", ignoreCase = true) == true ||
                                   parsedName?.startsWith("MISCALE", ignoreCase = true) == true ||
                                   parsedName?.contains("小米", ignoreCase = true) == true ||
                                   parsedName?.contains("米家", ignoreCase = true) == true
                    val isBroadcast = advScaleData != null || isXiaomi

                    val defaultName = if (isScale) {
                        if (advScaleData != null && advScaleData.protocolName.isNotBlank()) {
                            "${advScaleData.protocolName} (${device.address.takeLast(5)})"
                        } else if (matchedModel != ScaleModel.AUTO) {
                            "${matchedModel.displayName} (${device.address.takeLast(5)})"
                        } else {
                            "体脂秤 (${device.address.takeLast(5)})"
                        }
                    } else {
                        parsedName ?: "蓝牙设备 (${device.address.takeLast(5)})"
                    }
                    val name = parsedName ?: defaultName

                    val currentList = _discoveredScales.value.toMutableList()
                    val existingIndex = currentList.indexOfFirst { it.address.equals(device.address, ignoreCase = true) }
                    val item = DiscoveredScaleDevice(
                        name = name,
                        address = device.address,
                        rssi = result.rssi,
                        isBroadcastScale = isBroadcast,
                        isConfirmedScale = isScale,
                        matchedModel = matchedModel,
                        protocolName = advScaleData?.protocolName ?: matchedModel.protocolName,
                        liveWeightKg = liveWeight
                    )
                    // 【性能保护】同一台设备每秒会广播多次，若列表内容与上次几乎一致
                    // （名称/型号/是否秤/实时体重相同，且 RSSI 变化小于 5dBm），
                    // 则直接跳过重建列表与 StateFlow 推送，避免以广播频率重组整个配对界面。
                    if (existingIndex >= 0) {
                        val old = currentList[existingIndex]
                        val rssiUnchanged = kotlin.math.abs(old.rssi - item.rssi) < 5
                        val restUnchanged = old.copy(rssi = item.rssi) == item
                        if (rssiUnchanged && restUnchanged) {
                            return
                        }
                        currentList[existingIndex] = item
                    } else {
                        currentList.add(item)
                    }
                    // 排序规则：
                    // 1. 正在踩秤称重中（含有实时体重数据 > 0）的设备排在最最顶端！
                    // 2. 若用户指定了型号偏好 (preferredModel != AUTO)，与该型号匹配的设备排在顶端
                    // 3. 确认为体脂秤或 Wi-Fi 秤的设备排在未确认的普通蓝牙设备前面
                    // 4. 最后按信号强度 (RSSI) 降序排列
                    currentList.sortWith(
                        compareByDescending<DiscoveredScaleDevice> {
                            (it.liveWeightKg != null && it.liveWeightKg > 0.0)
                        }.thenByDescending {
                            preferredModel != ScaleModel.AUTO && it.matchedModel == preferredModel
                        }.thenByDescending {
                            it.isConfirmedScale
                        }.thenByDescending {
                            it.rssi
                        }
                    )
                    _discoveredScales.value = currentList
                }
                // 【核心保护】：手动配对阶段仅收集设备列表，绝不自动发起连接，等待用户手动点击！
                return
            }

            // 模式 2：已记住设备，严格执行自动连接
            // 多设备场景下，已记住的**任意一台**体脂秤踩上都能直接连接称重（无需手动切换），
            // 因此这里按「已记住 MAC 集合」匹配；集合之外的设备一律严格忽略，杜绝误连周围无关设备
            if (!isRememberedMac(device.address)) {
                // 非已记住的设备，严格忽略
                return
            }

            // 确认是已记住的目标设备后，按协议处理
            if (isScaleAdvertisement(result, advProvider)) {
                val name = device.name ?: result.scanRecord?.deviceName ?: parseNameFromBytes(result.scanRecord?.bytes) ?: "体脂秤设备 (${device.address.takeLast(5)})"

                // 尝试直接从广播数据解析（如小米秤、免配对广播秤即踩即读；复用单次解析结果）
                val advScaleData = advProvider()
                // 【Debug 抓包】记录已命中目标设备的广播原始 AD 结构与解析结论
                BleProtocolRecorder.recordAdStructures(
                    source = "paired-target",
                    raw = result.scanRecord?.bytes
                )
                if (advScaleData != null) {
                    // 【多设备会话锁】同一时刻只允许一台已记住的秤占用示数：
                    // 若会话由另一台设备持有，则忽略本包（含其 0kg 下秤包），
                    // 避免同屋另一台已记住的广播秤把正在进行的读数清零或互相覆盖。
                    val sessionMac = broadcastSessionMac
                    if (!sessionMac.isNullOrEmpty() && !sessionMac.equals(device.address, ignoreCase = true)) {
                        AppLogger.d(TAG, "忽略其它已记住设备的广播（会话中）: ${device.address}, 当前会话: $sessionMac")
                        return
                    }
                    // Bug #25 修复：只要 parseAdvertisement 返回非 null，就判定为广播秤，统一在此处理。
                    // 原条件 "advScaleData.weightKg > 0.0" 会导致离秤 0kg 包 fall-through 到下方 GATT 连接逻辑，
                    // 从而错误地对广播秤发起 GATT 连接。
                    if (advScaleData.weightKg > 0.0) {
                        // 有有效示数：该设备取得（或保持）本次会话的所有权
                        broadcastSessionMac = device.address
                        _weight.value = advScaleData.weightKg
                        val validStable = advScaleData.isStable && (advScaleData.weightKg >= 3.0)
                        _isStable.value = validStable
                        if (validStable) {
                            _connectionState.value = ConnectionState.MEASURING
                            // 仅当示数锁定且有明确阻抗上报时接收阻抗
                            if (advScaleData.impedanceOhm != null && advScaleData.impedanceOhm > 0.0 && advScaleData.weightKg >= 3.0) {
                                _impedance.value = advScaleData.impedanceOhm
                            }
                        } else {
                            if (_connectionState.value != ConnectionState.MEASURING) {
                                _connectionState.value = ConnectionState.CONNECTED
                            }
                            // 实时动态示数阶段强制清零阻抗，杜绝上一轮或爬升中残留
                            _impedance.value = 0.0
                        }
                    } else {
                        // 广播秤下秤包（weightKg == 0.0）：立即归零体重与稳定状态，切回已连接状态
                        // 同时释放会话锁，允许其它已记住的秤立刻接管
                        broadcastSessionMac = null
                        AppLogger.d(TAG, "广播秤下秤包 (0.0kg)：归零测量状态并释放会话锁")
                        _weight.value = 0.0
                        _isStable.value = false
                        _impedance.value = 0.0
                        if (isScanning) _connectionState.value = ConnectionState.SCANNING
                    }

                    // 重置 2.5s 无广播数据超时定时器（无论体重是否为 0 均重置，避免超时回调再次归零干扰）
                    // 复用同一个 Runnable 实例，避免广播高峰期每包分配新对象
                    val watchdog = inactivityRunnable ?: Runnable {
                        AppLogger.d(TAG, "无广播数据超时 (2.5s): 用户已下秤，重置测量状态")
                        broadcastSessionMac = null
                        _weight.value = 0.0
                        _isStable.value = false
                        _impedance.value = 0.0
                        if (isScanning) _connectionState.value = ConnectionState.SCANNING
                    }.also { inactivityRunnable = it }
                    handler.removeCallbacks(watchdog)
                    handler.postDelayed(watchdog, 2500)

                    // 广播秤保持扫描流以持续接收实时示数，直接返回，不走 GATT 连接逻辑
                    return
                }

                // 非广播秤（薄荷健康/沃莱 AFU 等需 GATT 双向通信的设备）：建立 GATT 连接
                // 【核心保护】：若当前已在连接中或已建立连接/测量中，绝不重复发起 GATT 连接，防止多广播包碰撞
                if (_connectionState.value == ConnectionState.CONNECTING ||
                    _connectionState.value == ConnectionState.CONNECTED ||
                    _connectionState.value == ConnectionState.MEASURING) {
                    return
                }
                AppLogger.i(TAG, "已发现已记住的体脂秤，自动连接 GATT: $name [${device.address}]")
                connect(device)
            }
        }

        /**
         * 批量扫描结果回调。
         * 自动连接模式使用 setReportDelay(>0) 批量上报时，系统**不再**回调 [onScanResult]，
         * 而是整批走本方法（若不实现将丢失全部自动连接扫描结果！）；
         * 逐条转交同一处理逻辑，保证批量/即时两条上报路径行为完全一致。
         */
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            if (!scanRequested || !isForeground) return
            BleProtocolRecorder.recordScanFailed(errorCode)
            AppLogger.e(TAG, "BLE 扫描失败，错误码: $errorCode")
            isScanning = false
            if (errorCode == ScanCallback.SCAN_FAILED_ALREADY_STARTED) {
                isScanning = true
                _connectionState.value = ConnectionState.SCANNING
                return
            }
            _connectionState.value = ConnectionState.IDLE
            scheduleScanRetry()
        }
    }

    /** 指数退避并持续低频重试；主动停止或进入后台时取消待执行任务。 */
    private fun scheduleScanRetry() {
        if (!scanRequested || !isForeground) return
        scanRetryRunnable?.let { handler.removeCallbacks(it) }
        val delayMs = (2000L * (1L shl scanRetryCount.coerceAtMost(4))).coerceAtMost(30000L)
        scanRetryCount = (scanRetryCount + 1).coerceAtMost(5)
        AppLogger.w(TAG, "BLE 扫描失败，将在 ${delayMs}ms 后重试")
        val retry = Runnable {
            scanRetryRunnable = null
            if (scanRequested && isForeground && _connectionState.value == ConnectionState.IDLE) startScan()
        }
        scanRetryRunnable = retry
        handler.postDelayed(retry, delayMs)
    }

    // -------------------------------------------------------------------------
    // 公开方法：直连、扫描、停止扫描、断开重置
    // -------------------------------------------------------------------------

    /**
     * 直接通过已知 MAC 地址跳过扫描发起 GATT 连接。
     *
     * 适用于已知配对 MAC（如从 SharedPreferences 恢复）的场景，
     * 可省去扫描耗时直接尝试重连。
     *
     * @param macAddress 目标设备的蓝牙 MAC 地址字符串（格式：XX:XX:XX:XX:XX:XX）。
     */
    fun connectMac(macAddress: String) {
        // 校验 MAC 地址格式合法性
        if (BluetoothAdapter.checkBluetoothAddress(macAddress)) {
            val device = bluetoothAdapter?.getRemoteDevice(macAddress)
            if (device != null) {
                // 通知上层 MAC 已确认（用于持久化）
                onMacDiscovered?.invoke(macAddress)
                connect(device)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 内部定时器工具与扫描状态
    // -------------------------------------------------------------------------

    /** 是否正在扫描中 */
    @Volatile
    var isScanning: Boolean = false
        private set

    /** 扫描失败连续重试计数 */
    private var scanRetryCount = 0
    private var scanRetryRunnable: Runnable? = null
    private var scanRequested = false

    /** 仅前台允许扫描；后台保留已有连接，回到前台后重新扫描。 */
    var isForeground: Boolean = false

    /** GATT 连接超时看门狗 Runnable，8 秒内未完成连接则回退到扫描状态。 */
    private var connectTimeoutRunnable: Runnable? = null

    /** 待执行的 GATT 连接调度任务（延迟 250ms 防射频冲突） */
    private var pendingConnectRunnable: Runnable? = null

    /** 单个特征描述符写入超时看门狗 Runnable，2.5 秒内未收到 onDescriptorWrite 则跳过。 */
    private var descriptorTimeoutRunnable: Runnable? = null

    /** 主线程 Handler，用于延迟任务（看门狗定时器、扫描重试等）。 */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // -------------------------------------------------------------------------
    // 公开方法：蓝牙与定位状态查询
    // -------------------------------------------------------------------------

    /**
     * 检查系统蓝牙是否已开启。
     *
     * @return 蓝牙适配器存在且已启用时返回 true，否则返回 false。
     */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /**
     * 检查系统定位服务是否开启 (部分 Android 系统或国产 ROM 需开启定位服务才能扫描 BLE 设备)。
     */
    fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return false
        return locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
               locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }

    // -------------------------------------------------------------------------
    // 扫描控制
    // -------------------------------------------------------------------------

    /**
     * 启动低能耗蓝牙（BLE）主动扫描。
     *
     * 启动前会：
     * 1. 检查是否满足扫描条件：非配对模式下若未记住任何设备，直接跳过并设为 IDLE。
     * 2. 重置体重、稳定、阻抗状态为初始值。
     * 3. 清理旧 GATT 连接和看门狗定时器，防止资源泄漏。
     * 4. 扫描参数按模式区分：配对扫描为 LOW_LATENCY + 即时回调 + 无过滤（发现任意秤）；
     *    自动连接扫描为 [AUTO_SCAN_MODE] + [AUTO_SCAN_REPORT_DELAY_MS] 批量上报 + ScanFilter
     *    （已记住 MAC 与支持的服务 UUID，硬件侧过滤无关广播），显著降低常扫功耗。
     *
     * 若蓝牙适配器不可用或未启用，则直接将状态设为 [ConnectionState.IDLE] 并返回。
     */
    fun startScan() {
        if (!isForeground) return
        scanRequested = true
        scanRetryRunnable?.let { handler.removeCallbacks(it) }
        scanRetryRunnable = null
        // 已经扫描时直接返回，不能先清空正在进行的称重会话。
        if (isScanning && _connectionState.value == ConnectionState.SCANNING) {
            BleProtocolRecorder.recordScanLifecycle("SCAN_SKIP", "已在扫描中")
            AppLogger.d(TAG, "BLE 扫描已处于运行状态，无需重复启动")
            return
        }

        // 新扫描开始时清理上一轮测量状态。
        broadcastSessionMac = null
        _weight.value = 0.0
        _isStable.value = false
        _impedance.value = 0.0

        // 取消任何待发起的 GATT 连接，确保扫描环境干净
        pendingConnectRunnable?.let { handler.removeCallbacks(it) }
        pendingConnectRunnable = null

        // 【关键保护】：非配对模式下，如果没有记住任何设备，绝不执行盲目扫描和自动连接
        if (!isPairingMode && !hasRememberedDevice) {
            AppLogger.i(TAG, "尚未记住任何体脂秤设备，跳过自动连接扫描（请先进行手动配对）")
            _connectionState.value = ConnectionState.IDLE
            isScanning = false
            return
        }

        val adapter = bluetoothAdapter
        val scanner = try {
            adapter?.bluetoothLeScanner
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "蓝牙扫描权限尚未授予: ${e.message}")
            _connectionState.value = ConnectionState.IDLE
            isScanning = false
            return
        }
        if (scanner == null) {
            // 记录详细原因：无适配器 / BT 未开启 / Scanner 为 null
            val reason = if (adapter == null) "No BT Adapter" else if (!adapter.isEnabled) "BT Disabled" else "Scanner Null"
            AppLogger.e(TAG, "蓝牙扫描器不可用: $reason")
            _connectionState.value = ConnectionState.IDLE
            isScanning = false
            if (adapter != null && runCatching { adapter.isEnabled }.getOrDefault(false)) scheduleScanRetry()
            return
        }

        // 取消旧的连接超时与描述符超时看门狗，防止误触发
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectTimeoutRunnable = null
        descriptorTimeoutRunnable?.let { handler.removeCallbacks(it) }
        descriptorTimeoutRunnable = null
        // 每次重新扫描都重置薄荷健康 B1 会话，避免上一轮记录指纹抑制本次识别
        resetAfuB1Session()

        // 关闭旧的 GATT 连接，释放系统蓝牙资源
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {}
        bluetoothGatt = null

        // 【功耗优化】两种扫描模式：
        // - 配对扫描（isPairingMode=true）：需要发现**任意**体脂秤，保持无过滤 + LOW_LATENCY + 即时回调；
        // - 自动连接扫描：只关心「已记住的秤」（模式 2 严格按 rememberedMacs 白名单连接），
        //   改用 ScanFilter 在蓝牙控制器侧过滤无关广播，并以 BALANCED 占空比 + 批量上报降低常扫功耗。
        val autoConnectScan = !isPairingMode
        val scanMode = if (autoConnectScan) AUTO_SCAN_MODE else ScanSettings.SCAN_MODE_LOW_LATENCY
        val reportDelayMs = if (autoConnectScan) AUTO_SCAN_REPORT_DELAY_MS else 0L
        AppLogger.i(TAG, "启动 BLE 扫描 (isPairingMode: $isPairingMode, activeMac: $lastPairedMac, 已记住 ${rememberedMacs.size} 台)...")
        _connectionState.value = ConnectionState.SCANNING

        // 构建扫描参数配置（针对 MIUI/HyperOS/ColorOS/OriginOS 启用 Aggressive 模式）
        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setReportDelay(reportDelayMs)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            settingsBuilder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            settingsBuilder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            settingsBuilder.setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
        }
        val settings = settingsBuilder.build()

        // 自动连接扫描过滤器：已记住 MAC（自动连接语义 = 只处理已记住设备，完全等价）
        // OR 支持的服务 UUID（兜底已知型号设备）。配对扫描保持无过滤（由 isScaleAdvertisement 逻辑层过滤）。
        val scanFilters: List<ScanFilter>? = if (autoConnectScan) {
            buildList<ScanFilter> {
                // 当前设备可能先于设备列表加载完成，过滤器也必须覆盖该地址。
                for (mac in rememberedMacs + listOfNotNull(lastPairedMac)) {
                    if (BluetoothAdapter.checkBluetoothAddress(mac)) {
                        add(ScanFilter.Builder().setDeviceAddress(mac).build())
                    }
                }
                for (uuid in SUPPORTED_SERVICE_UUIDS) {
                    add(ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build())
                }
            }.ifEmpty { null }
        } else {
            null
        }
        BleProtocolRecorder.recordScanLifecycle(
            "SCAN_START",
            "isPairingMode=$isPairingMode, activeMac=${lastPairedMac ?: ""}, remembered=${rememberedMacs.size}, " +
                "mode=${if (autoConnectScan) "BALANCED" else "LOW_LATENCY"}, reportDelay=${reportDelayMs}ms, filters=${scanFilters?.size ?: 0}"
        )

        // 先尝试停止旧扫描（防止重复启动导致系统报错）
        try {
            scanner.stopScan(scanCallback)
        } catch (e: Exception) {}

        // 自动连接模式启用硬件侧过滤；配对模式以无过滤器方式启动扫描
        try {
            scanner.startScan(scanFilters, settings, scanCallback)
            isScanning = true
        } catch (e: Exception) {
            isScanning = false
            AppLogger.e(TAG, "启动 BLE 扫描失败: ${e.message}")
            _connectionState.value = ConnectionState.IDLE
            scheduleScanRetry()
        }
    }

    /**
     * 启动设备手动配对扫描。
     * 处于配对模式时绝不自动连接任何设备，仅上报扫描到的候选秤列表供用户手动选择。
     */
    fun startPairingScan() {
        AppLogger.i(TAG, "启动设备手动配对扫描...")
        if (isScanning) stopScan()
        isPairingMode = true
        _discoveredScales.value = emptyList()
        startScan()
    }

    /**
     * 用户手动选择设备并发起连接与记住。
     *
     * @param device 用户在界面上点击的目标秤设备
     */
    fun manualConnect(device: DiscoveredScaleDevice) {
        isPairingMode = false
        stopScan()
        val bluetoothAdapter = bluetoothAdapter ?: return
        val remoteDevice = try {
            bluetoothAdapter.getRemoteDevice(device.address)
        } catch (e: Exception) {
            AppLogger.e(TAG, "无效的 MAC 地址: ${device.address}: ${e.message}")
            return
        }

        AppLogger.i(TAG, "用户手动连接设备: ${device.name} [${device.address}], 广播秤: ${device.isBroadcastScale}")
        BleProtocolRecorder.recordManualConnect(device.address, device.name, device.isBroadcastScale)
        lastPairedMac = normalizeMacAddress(device.address)
        // 立即纳入运行时记忆集合：多设备场景下新绑定的秤无需等待 DataStore 回读即可自动连接
        rememberMac(device.address)
        onMacDiscovered?.invoke(device.address)

        if (device.isBroadcastScale) {
            // 广播秤（如小米体脂秤）：已记住该 MAC，直接设为已连接
            _connectionState.value = ConnectionState.CONNECTED
            // 延迟 500ms 再启动自动监听扫描，留足时间给上层 UI 捕获 CONNECTED 状态并完成配对保存与导航
            handler.postDelayed({
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    startScan()
                }
            }, 500L)
        } else {
            // GATT 秤：发起 GATT 连接
            connect(remoteDevice)
        }
    }

    /**
     * 停止正在进行的 BLE 扫描。
     *
     * 若当前状态为 [ConnectionState.SCANNING]，则将状态重置为 [ConnectionState.IDLE]。
     * 如果调用时扫描已停止，忽略异常静默处理。
     */
    fun stopScan() {
        val wasScanning = isScanning
        scanRequested = false
        scanRetryRunnable?.let { handler.removeCallbacks(it) }
        scanRetryRunnable = null
        scanRetryCount = 0
        isScanning = false
        BleProtocolRecorder.recordScanLifecycle("SCAN_STOP")
        pendingConnectRunnable?.let {
            handler.removeCallbacks(it)
            if (_connectionState.value == ConnectionState.CONNECTING && bluetoothGatt == null) {
                _connectionState.value = ConnectionState.IDLE
            }
        }
        pendingConnectRunnable = null
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            AppLogger.w(TAG, "停止扫描异常: ${e.message}")
        }
        // 广播秤依赖扫描接收数据；停止监听后不能保留虚假的 CONNECTED 状态，
        // 否则从后台返回时不会重新扫描。真正的 GATT 连接保持原状态。
        if (_connectionState.value == ConnectionState.SCANNING ||
            (wasScanning && bluetoothGatt == null &&
                (_connectionState.value == ConnectionState.CONNECTED ||
                    _connectionState.value == ConnectionState.MEASURING))
        ) {
            _connectionState.value = ConnectionState.IDLE
        }
    }

    /**
     * 断开当前 GATT 连接并完整重置客户端至初始状态。
     *
     * 会清除：
     * - 配对模式标记与候选设备列表
     * - 无数据超时定时器（inactivityRunnable）
     * - 连接超时看门狗（connectTimeoutRunnable）
     * - 待执行连接任务（pendingConnectRunnable）
     * - 描述符超时看门狗（descriptorTimeoutRunnable）
     * - GATT 连接实例
     * - 当前使用设备 MAC（lastPairedMac）。注意 [rememberedMacs] 由上层依据持久化列表同步，
     *   不在此处清空，避免断开动作误删用户已记住的设备
     * - 所有测量数据（体重、稳定标志、阻抗）
     */
    fun disconnectAndReset() {
        AppLogger.i(TAG, "断开连接并重置蓝牙状态")
        isPairingMode = false
        isScanning = false
        scanRetryCount = 0
        _discoveredScales.value = emptyList()
        // 取消无数据超时定时器
        inactivityRunnable?.let { handler.removeCallbacks(it) }
        inactivityRunnable = null
        // 取消待执行的连接任务与看门狗
        pendingConnectRunnable?.let { handler.removeCallbacks(it) }
        pendingConnectRunnable = null
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectTimeoutRunnable = null
        // 取消描述符超时看门狗
        descriptorTimeoutRunnable?.let { handler.removeCallbacks(it) }
        descriptorTimeoutRunnable = null
        // 清空薄荷健康 B1 会话状态与未执行的提交流程
        resetAfuB1Session()
        // 释放广播秤会话锁，避免断开后仍拒绝其它已记住设备的广播
        broadcastSessionMac = null

        stopScan()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {}
        bluetoothGatt = null
        // 清除「当前使用设备」指针；已记住设备集合 rememberedMacs 由上层（PreferenceManager 订阅）负责同步
        lastPairedMac = null
        // 重置所有测量数据为初始值
        _connectionState.value = ConnectionState.IDLE
        _weight.value = 0.0
        _isStable.value = false
        _impedance.value = 0.0
    }

    // -------------------------------------------------------------------------
    // 内部 GATT 连接管理
    // -------------------------------------------------------------------------

    /**
     * 向指定蓝牙设备发起 GATT 连接。
     *
     * 连接流程：
     * 1. 防重入保护：已处于连接中/已连接/测量中时直接忽略。
     * 2. 立即将状态更新为 CONNECTING，屏蔽后续重复广播包。
     * 3. 立即调用 stopScan 停止扫描，释放 BLE 射频资源。
     * 4. 延迟 250ms 后在主线程调度关闭旧 GATT 并调用 connectGatt，彻底杜绝 Status 133 / GATT_ERROR。
     * 5. 设置 8 秒连接超时看门狗：若 8 秒内 GATT 未建立则回退到扫描。
     * 6. 在 Android M+ 上强制使用 LE 传输通道。
     *
     * @param device 目标蓝牙设备对象。
     */
    private fun connect(device: BluetoothDevice) {
        if (_connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.MEASURING) {
            AppLogger.d(TAG, "当前已处于 ${_connectionState.value} 状态，忽略重复连接请求: ${device.address}")
            return
        }

        // 停止广播秤的旧看门狗，避免它在新 GATT 会话中清空示数。
        inactivityRunnable?.let { handler.removeCallbacks(it) }
        inactivityRunnable = null
        broadcastSessionMac = null
        // 1. 立即停止扫描，避免无线电资源冲突
        stopScan()
        // 2. 立即置为 CONNECTING，阻止后续广播包触发多次连接
        _connectionState.value = ConnectionState.CONNECTING

        pendingConnectRunnable?.let { handler.removeCallbacks(it) }
        val connectRunnable = Runnable {
            pendingConnectRunnable = null
            if (_connectionState.value != ConnectionState.CONNECTING) return@Runnable

            // 关闭旧的 GATT 连接实例
            try {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            } catch (e: Exception) {
                AppLogger.w(TAG, "关闭旧 GATT 实例异常: ${e.message}")
            }
            bluetoothGatt = null

            AppLogger.i(TAG, "正在发起 GATT 连接 (延迟 250ms 防射频冲突): ${device.address}...")
            BleProtocolRecorder.recordConnectAttempt(
                address = device.address,
                transport = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) "TRANSPORT_LE" else "AUTO"
            )

            // Cancel previous connection watchdog
            connectTimeoutRunnable?.let { handler.removeCallbacks(it) }

            // 8-second watchdog: if GATT doesn't establish, close GATT & fall back to scan
            val timeoutRunnable = Runnable {
                if (_connectionState.value == ConnectionState.CONNECTING) {
                    AppLogger.w(TAG, "GATT 连接超时 (8s)，回退并重新启动 LE 扫描...")
                    BleProtocolRecorder.recordConnectTimeout(device.address, 8000L)
                    try {
                        bluetoothGatt?.disconnect()
                        bluetoothGatt?.close()
                    } catch (e: Exception) {}
                    bluetoothGatt = null
                    _connectionState.value = ConnectionState.IDLE
                    startScan()  // 回退到扫描，重新发现设备
                }
            }
            connectTimeoutRunnable = timeoutRunnable
            handler.postDelayed(timeoutRunnable, 8000L)  // 8000ms = 8秒超时

            // 发起 GATT 连接；Android M（API 23）及以上强制指定 LE 传输通道以提高稳定性
            try {
                bluetoothGatt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, gattCallback)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "发起 GATT 连接失败: ${e.message}")
                handler.removeCallbacks(timeoutRunnable)
                connectTimeoutRunnable = null
                _connectionState.value = ConnectionState.IDLE
                handler.postDelayed({
                    if (isForeground && !isPairingMode && hasRememberedDevice &&
                        _connectionState.value == ConnectionState.IDLE) startScan()
                }, 2000L)
            }
        }
        pendingConnectRunnable = connectRunnable
        // 留出 250ms 给系统蓝牙芯片完全停止 LE 扫描状态，彻底规避 Status 133 冲突
        handler.postDelayed(connectRunnable, 250L)
    }

    // -------------------------------------------------------------------------
    // 特征订阅队列管理
    // -------------------------------------------------------------------------

    /** 等待逐个订阅的通知/指示特征列表（服务发现后填充）。 */
    private var pendingSubscribeList = mutableListOf<BluetoothGattCharacteristic>()

    /** 当前正在处理的特征订阅索引（指向 [pendingSubscribeList] 中的下一个待订阅特征）。 */
    private var pendingSubscribeIndex = 0

    /**
     * 向所有具有可写属性的特征发送 AFU 协议握手包。
     *
     * 握手数据包格式（10 字节）：
     *   FD 37 00 00 00 00 00 00 00 37
     * 发送握手是 AFU 体脂秤开始推送体重 + 阻抗数据的必要前提。
     *
     * 兼容处理：
     * - Android 13+（TIRAMISU）：使用新 API [BluetoothGatt.writeCharacteristic]。
     * - 旧版本：使用已废弃的 [BluetoothGattCharacteristic.value] + [BluetoothGatt.writeCharacteristic]。
     *
     * @param gatt 当前 GATT 连接实例。
     */
    private fun sendHandshake(gatt: BluetoothGatt) {
        for (service in gatt.services) {
            val sUuid = service.uuid.toString().uppercase()
            // 仅对薄荷健康/沃莱 AFU 私有服务 (0xFFB0) 发送握手包，
            // 严禁向其它服务或未知厂商特征发送，以防远端 MCU 异常或断连
            val isAfuService = sUuid.contains("FFB0") || sUuid.contains("0000FFB0")
            if (!isAfuService) continue

            // 只要设备包含 0xFFB0 服务（AFU 专属服务），无论用户偏好型号为何，都必须发送握手包激活 MCU，
            // 否则秤端不会开始推送 20 字节复合帧体重/阻抗数据。
            for (char in service.characteristics) {
                val props = char.properties
                val cUuid = char.uuid.toString().uppercase()
                val isAfuWrite = cUuid.contains("FFB1") || cUuid.contains("0000FFB1")
                // 只向支持 WRITE 或 WRITE_NO_RESPONSE 的 AFU 写入特征发送握手
                if (isAfuWrite && (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                    props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)) {

                    val handshakeData = byteArrayOf(0xFD.toByte(), 0x37, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x37)
                    val handshakeHex = handshakeData.joinToString(" ") { "%02X".format(it) }
                    // 完整握手包十六进制同样只在 Debug 构建输出到日志系统
                    if (BleProtocolRecorder.isEnabled) {
                        AppLogger.i(TAG, "向 ${char.uuid} 发送握手数据包: $handshakeHex")
                    } else {
                        AppLogger.i(TAG, "向 ${char.uuid} 发送握手数据包 (${handshakeData.size} 字节)")
                    }
                    val writeType = if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 &&
                        props and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    } else {
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    }
                    char.writeType = writeType
                    // 根据 Android API 版本选择写入方式
                    val writeSuccess: Boolean? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(char, handshakeData, writeType) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        char.value = handshakeData
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(char)
                    }
                    // 【Debug 抓包】记录下行握手包的完整字节与写入结果
                    BleProtocolRecorder.recordWrite(
                        charUuid = char.uuid,
                        serviceUuid = service.uuid,
                        value = handshakeData,
                        writeType = writeType,
                        success = writeSuccess
                    )
                }
            }
        }

        // 薄荷健康 B1 (AFU-BH-TZ-B1)：握手为「对时 + 连续两次同步请求」。
        // 用户档案（0x3F）必须等实时称重开始后再下发，过早下发会把秤切到称重模式并阻断其历史队列。
        if (isAfuB1Device) {
            sendAfuB1TimeSync()
            sendAfuB1SyncRequestPair()
            afuB1HandshakeDone = true
            AppLogger.i(TAG, "薄荷健康 B1 握手已发送 (对时 + 2x 同步请求)")
        }
    }

    /**
     * 顺序订阅 [pendingSubscribeList] 中的下一个特征的通知或指示。
     *
     * BLE 协议要求：同一时刻只能有一个 [BluetoothGatt.writeDescriptor] 操作在途，
     * 因此必须串行订阅，等上一次 [onDescriptorWrite] 回调后再订阅下一个。
     *
     * 订阅步骤：
     * 1. 调用 [BluetoothGatt.setCharacteristicNotification] 在本地开启通知。
     * 2. 向 CCCD（Client Characteristic Configuration Descriptor，UUID 0x2902）
     *    写入 [BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE] 或
     *    [BluetoothGattDescriptor.ENABLE_INDICATION_VALUE]。
     * 3. 若特征无 CCCD 描述符，则直接跳过继续下一个。
     * 4. 全部订阅完成后调用 [sendHandshake] 并将连接状态更新为 [ConnectionState.CONNECTED]。
     *
     * @param gatt 当前 GATT 连接实例。
     */
    private fun subscribeNextCharacteristic(gatt: BluetoothGatt) {
        // 取消前一个特征的描述符超时看门狗
        descriptorTimeoutRunnable?.let { handler.removeCallbacks(it) }
        descriptorTimeoutRunnable = null

        // 所有特征均已订阅完毕
        if (pendingSubscribeIndex >= pendingSubscribeList.size) {
            AppLogger.i(TAG, "所有特征通知已成功订阅，准备发送握手包")
            // 服务发现及全部特征订阅顺利完成，取消看门狗
            connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
            connectTimeoutRunnable = null
            // 稍作微小延迟 150ms 确保 BLE 描述符写入事务完全完成，再执行握手写入
            handler.postDelayed({
                sendHandshake(gatt)
            }, 150L)
            _connectionState.value = ConnectionState.CONNECTED
            return
        }
        val characteristic = pendingSubscribeList[pendingSubscribeIndex]
        pendingSubscribeIndex++

        AppLogger.d(TAG, "正在订阅特征 (${pendingSubscribeIndex}/${pendingSubscribeList.size}): ${characteristic.uuid}")
        // 在本地（Android 系统层）开启特征通知路由
        gatt.setCharacteristicNotification(characteristic, true)

        // 获取 CCCD 描述符（标准 UUID 0x2902），向设备端写入以开启通知/指示
        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        if (descriptor != null) {
            val props = characteristic.properties
            // 根据特征属性决定写入通知值还是指示值
            val cccdValue = if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE  // 指示：需设备确认
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE  // 通知：无需确认
            }

            // 【Debug 抓包】记录本次订阅的特征属性、CCCD 描述符与将要写入的开关值（通知/指示）
            BleProtocolRecorder.recordSubscribe(
                charUuid = characteristic.uuid,
                properties = props,
                descriptor = descriptor,
                cccdValue = cccdValue
            )

            // 单个描述符写入设置 2.5s 超时看门狗，防止个别体脂秤丢弃回调导致整个订阅链路永久死锁
            val dTimeout = Runnable {
                AppLogger.w(TAG, "描述符写入超时 (2.5s) [${characteristic.uuid}], 跳过并尝试下一个特征")
                subscribeNextCharacteristic(gatt)
            }
            descriptorTimeoutRunnable = dTimeout
            handler.postDelayed(dTimeout, 2500L)

            // Android 13+（API 33）使用新版 writeDescriptor API，旧版本降级使用已废弃方式
            val success = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, cccdValue) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = cccdValue
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            AppLogger.d(TAG, "writeDescriptor 结果 [${characteristic.uuid}]: $success")
            // 【Debug 抓包】记录 CCCD 订阅写入的特征、描述符与写入值（通知/指示开关）
            BleProtocolRecorder.recordDescriptorWrite(
                charUuid = characteristic.uuid,
                descriptorUuid = descriptor.uuid,
                value = cccdValue,
                success = success
            )
            if (!success) {
                // writeDescriptor 立即失败时（无需等待回调），取消定时器并直接处理下一个
                descriptorTimeoutRunnable?.let { handler.removeCallbacks(it) }
                descriptorTimeoutRunnable = null
                subscribeNextCharacteristic(gatt)
            }
            // 成功发起写入后等待 onDescriptorWrite 回调，再继续下一个
        } else {
            // 无 CCCD 描述符，跳过该特征继续下一个
            subscribeNextCharacteristic(gatt)
        }
    }

    // -------------------------------------------------------------------------
    // 无数据超时看门狗
    // -------------------------------------------------------------------------

    /**
     * 用户下秤后无数据包超时定时器。
     * 2.5 秒内若无新数据包到达，则将体重、稳定标志、阻抗全部重置为 0，
     * 确保下一次称重生成全新的记录，而不会叠加到上一次测量结果上。
     */
    private var inactivityRunnable: Runnable? = null

    /**
     * 处理从 GATT 特征通知中接收到的原始数据包。
     *
     * 处理流程：
     * 1. 打印原始十六进制数据日志，便于调试。
     * 2. 重置 2.5 秒无数据超时看门狗定时器。
     * 3. 薄荷健康 B1 的 20 字节 AF 45 帧交给 [handleAfuB1Frame] 专属状态机处理（需逐帧确认）；
     *    其余报文调用 [MultiScalePacketParser.parseNotification] 解析薄荷健康/沃莱或小米体脂秤 2 代，
     *    若体重 >= 3.0kg 且协议标志为稳定，则锁定稳定状态并切换到 [ConnectionState.MEASURING]。
     * 4. 解析出的有效阻抗会实时更新阻抗状态流。
     *
     * @param data     特征通知携带的原始字节数据。
     * @param charUuid 发出通知的特征 UUID 字符串（用于日志标记来源）。
     */
    private fun handleIncomingData(data: ByteArray, charUuid: String) {
        val scaleResult = MultiScalePacketParser.parseNotification(data, charUuid, preferredModel)
        // 【Debug 抓包】完整记录上行报文原始字节 + 解析结论（未识别报文也照常记录，供协议逆向）。
        // 仅在抓包开关打开时才做 UUID 解析与记录调用，避免每帧无谓的 UUID.fromString 分配。
        if (BleProtocolRecorder.isEnabled) {
            val parsedCharUuid = try { java.util.UUID.fromString(charUuid) } catch (e: Exception) { null }
            BleProtocolRecorder.recordNotification(parsedCharUuid, data, scaleResult)
            // 完整十六进制原始报文只在抓包开启时拼接并输出：Release 包不再把蓝牙原始载荷写入日志系统，
            // 避免运行日志（可复制/可分享）成为 Release 版泄漏完整协议数据的旁路。
            val parsedDetails = if (scaleResult != null) {
                " [${scaleResult.protocolName}] | 体重=${String.format(Locale.US, "%.2f", scaleResult.weightKg)}kg, 锁定=${scaleResult.isStable}" +
                    (scaleResult.impedanceOhm?.let { ", 阻抗=${it.toInt()}Ω" } ?: "")
            } else ""
            val hexStr = data.joinToString(" ") { "%02X".format(it) }
            AppLogger.d(TAG, "收到原始蓝牙数据: $hexStr$parsedDetails")
        } else {
            AppLogger.d(TAG, "收到蓝牙数据 (${data.size} 字节)")
        }

        // 薄荷健康 B1 (AFU-BH-TZ-B1)：20 字节 AF 45 帧由专属状态机处理，
        // 需要逐帧回确认、并在用户下秤后主动拉取本次测量记录，不能走通用解析分支。
        if (AFUB1PacketParser.isAfuB1Frame(data)) {
            resetInactivityWatchdog()
            handleAfuB1Frame(data)
            return
        }

        // 重置 2.5s 无数据包超时定时器（下秤停测后，自动重置状态，确保下一次称重生成全新记录）
        resetInactivityWatchdog()

        // 更新测量结果
        scaleResult?.let { result ->
            if (result.weightKg > 0.0) {
                _weight.value = result.weightKg
                // 只有当体重 >= 3.0kg 时才判定为有效稳定锁定（防止单脚踩秤或轻微压秤时的误锁定）
                val validStable = result.isStable && (result.weightKg >= 3.0)
                _isStable.value = validStable
                if (validStable) {
                    // 体重稳定锁定后切换到测量状态，通知 UI 开始体成分计算
                    _connectionState.value = ConnectionState.MEASURING
                } else {
                    // 体重处于实时动态变动中（未锁定）：
                    // 彻底清零阻抗，杜绝上一轮称重遗留或动态爬升中的杂质阻抗残留！
                    _impedance.value = 0.0
                }
            } else if (result.weightKg <= 0.0 && result.impedanceOhm == null) {
                // 仅当体重为 0 且无阻抗数据时（下秤离秤包），才归零体重与稳定标志
                _weight.value = 0.0
                _isStable.value = false
                _impedance.value = 0.0
                if (_connectionState.value == ConnectionState.MEASURING) {
                    _connectionState.value = ConnectionState.CONNECTED
                }
            }
            // 阻抗专属数据包（result.weightKg <= 0.0 && result.impedanceOhm != null）：绝不归零体重，保留已锁定的 _weight.value

            result.impedanceOhm?.let { imp ->
                // 人体生物电阻抗（BIA）必须基于真实人体踩秤（体重 >= 3.0kg）才能测得，空秤时禁止接收阻抗
                if (imp > 0.0 && (_weight.value >= 3.0 || result.weightKg >= 3.0)) {
                    _impedance.value = imp
                }
            }
        }
    }

    /**
     * 重置 2.5 秒无数据看门狗。超时即视为用户已下秤，归零全部测量状态，
     * 确保下一次称重生成全新记录而不会叠加到上一次结果上。
     */
    private fun resetInactivityWatchdog() {
        // 复用同一个 Runnable 实例：称重高峰期每秒十余条通知，
        // 每帧新建 Runnable 会造成明显的 GC 压力
        val watchdog = inactivityRunnable ?: Runnable {
            AppLogger.d(TAG, "无数据超时 (2.5s): 用户已下秤，重置测量状态")
            // 超时后将所有测量状态重置为 0，为下次称重做准备
            _weight.value = 0.0
            _isStable.value = false
            _impedance.value = 0.0
            if (_connectionState.value == ConnectionState.MEASURING) {
                _connectionState.value = ConnectionState.CONNECTED
            }
        }.also { inactivityRunnable = it }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 2500)  // 2500ms 无数据则认为用户已下秤
    }

    // -------------------------------------------------------------------------
    // 薄荷健康 B1 (AFU-BH-TZ-B1) 专属状态机
    // -------------------------------------------------------------------------

    /** 在服务发现结果中识别薄荷健康 B1，并动态定位其命令写入特征（af000002）。 */
    private fun detectAfuB1Device(gatt: BluetoothGatt) {
        if (gatt.services.none { it.uuid == AFUB1PacketParser.SERVICE_UUID }) {
            isAfuB1Device = false
            return
        }
        isAfuB1Device = true
        afuB1HandshakeDone = false
        afuB1ProfileSent = false
        afuB1SeenRecords.clear()
        afuB1WriteServiceUuid = null
        afuB1WriteCharUuid = null

        outer@ for (service in gatt.services) {
            for (char in service.characteristics) {
                if (char.uuid == AFUB1PacketParser.WRITE_CHARACTERISTIC_UUID) {
                    afuB1WriteServiceUuid = service.uuid
                    afuB1WriteCharUuid = char.uuid
                    break@outer
                }
            }
        }
        AppLogger.i(
            TAG,
            "识别到薄荷健康 B1 (AFU-BH-TZ-B1)，命令写入特征: " +
                (afuB1WriteCharUuid?.toString() ?: "未在 GATT 树中找到，将按默认 UUID 尝试")
        )
    }

    /** 解析 B1 命令写入特征：优先使用服务发现阶段记录的实例，其次全树兜底查找。 */
    private fun resolveAfuB1WriteCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        afuB1WriteServiceUuid?.let { svcUuid ->
            gatt.getService(svcUuid)
                ?.getCharacteristic(AFUB1PacketParser.WRITE_CHARACTERISTIC_UUID)
                ?.let { return it }
        }
        for (service in gatt.services) {
            service.getCharacteristic(AFUB1PacketParser.WRITE_CHARACTERISTIC_UUID)?.let { return it }
        }
        return null
    }

    /** 向 B1 下发一个 20 字节协议帧。 */
    private fun writeAfuB1(frame: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val characteristic = resolveAfuB1WriteCharacteristic(gatt)
        // 十六进制字符串仅用于日志/抓包，延迟到真正需要时再拼接（每收到一帧都会 ACK 一次）
        val captureEnabled = BleProtocolRecorder.isEnabled
        if (characteristic == null) {
            if (captureEnabled) {
                AppLogger.w(TAG, "B1 写入特征 (af000002) 不存在，跳过命令: ${frame.joinToString(" ") { "%02X".format(it) }}")
            }
            return false
        }
        val props = characteristic.properties
        val writeType = if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 &&
            props and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        characteristic.writeType = writeType
        val success = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, frame, writeType) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = frame
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        if (captureEnabled) {
            BleProtocolRecorder.recordWrite(
                charUuid = characteristic.uuid,
                serviceUuid = afuB1WriteServiceUuid ?: AFUB1PacketParser.WRITE_SERVICE_UUID,
                value = frame,
                writeType = writeType,
                success = success
            )
            AppLogger.d(TAG, "B1 下发命令 (${if (success) "成功" else "失败"}): ${frame.joinToString(" ") { "%02X".format(it) }}")
        }
        return success
    }

    private fun sendAfuB1TimeSync() {
        writeAfuB1(AFUB1PacketParser.buildTimeSync(System.currentTimeMillis() / 1000L))
    }

    private fun sendAfuB1SyncRequestPair() {
        writeAfuB1(AFUB1PacketParser.buildSyncRequest(AFUB1PacketParser.SyncRequestVariant.FIRST))
        writeAfuB1(AFUB1PacketParser.buildSyncRequest(AFUB1PacketParser.SyncRequestVariant.SECOND))
    }

    private fun sendAfuB1UserProfile(variant: AFUB1PacketParser.ProfileVariant) {
        writeAfuB1(
            AFUB1PacketParser.buildUserProfile(
                variant = variant,
                nowEpochSeconds = System.currentTimeMillis() / 1000L,
                user = AFUB1PacketParser.SCALE_USER_SLOT,
                age = afuB1UserAge,
                sex = if (afuB1UserIsMale) 1 else 0,
                targetKg = afuB1UserTargetKg.toFloat()
            )
        )
    }

    /**
     * 处理一帧薄荷健康 B1 数据。
     *
     * 与旧协议的差别：
     * 1. 秤端未被确认的帧会反复重投，因此每一帧都必须回确认（记录帧还需回带序号）；
     * 2. 体重锁定后必须等用户下秤、秤进入空闲，再主动下发「重新测量 + 分片请求」，
     *    才能拉取到本次的完整记录（含阻抗）；否则只能拿到实时体重。
     */
    private fun handleAfuB1Frame(raw: ByteArray) {
        val frame = AFUB1PacketParser.parse(raw) ?: return
        afuB1LastFrameAt = System.currentTimeMillis()

        if (frame.ackReplyByte != 0) {
            val correlation = (frame as? AFUB1PacketParser.Frame.StoredRecord)?.sequence ?: 0
            writeAfuB1(AFUB1PacketParser.buildAck(frame.ackReplyByte, correlation))
        }

        when (frame) {
            is AFUB1PacketParser.Frame.LiveWeight -> {
                val weightKg = frame.weightGrams / 1000.0
                _weight.value = weightKg
                val locked = frame.locked && weightKg >= 3.0
                _isStable.value = locked
                if (locked) {
                    if (_connectionState.value != ConnectionState.MEASURING) {
                        _connectionState.value = ConnectionState.MEASURING
                    }
                    afuB1FinalWeightG = frame.weightGrams
                    startAfuB1CommitFlow()
                } else {
                    // 实时动态称重阶段强制清零阻抗，杜绝上一轮残留
                    _impedance.value = 0.0
                }
                // 开始实时称重后才允许下发用户档案（过早下发会阻断秤端历史队列）
                if (afuB1HandshakeDone && !afuB1ProfileSent) {
                    afuB1ProfileSent = true
                    sendAfuB1UserProfile(AFUB1PacketParser.ProfileVariant.INITIAL)
                    AppLogger.i(TAG, "B1 实时称重已开始，用户档案 (0x3F) 已下发")
                }
            }

            is AFUB1PacketParser.Frame.StoredRecord -> {
                val weightKg = frame.weightGrams / 1000.0
                val fingerprint = "${frame.timestampEpochSeconds}:${frame.weightGrams}:${frame.resistanceOhms}"
                val isNew = afuB1SeenRecords.add(fingerprint)
                // 仅接受「本次称重刚产生」的记录：历史记录绝不能覆盖当前测量结果
                val isFresh = afuB1FinalWeightG > 0 &&
                    Math.abs(frame.weightGrams - afuB1FinalWeightG) <= AFUB1_FRESH_WEIGHT_TOLERANCE_G &&
                    Math.abs(System.currentTimeMillis() / 1000L - frame.timestampEpochSeconds) <= AFUB1_FRESH_RECORD_WINDOW_S
                if (isFresh) {
                    afuB1FreshRecordSeen = true
                    // 只取用记录里的阻抗：体重与锁定状态已由 0x51 实时帧给出。
                    // 若此处再改写 _weight/_isStable，上层会把「记录回推」误判成又踩了一次秤，
                    // 从而为同一次称重生成重复记录。
                    if (frame.resistanceOhms > 0) {
                        _impedance.value = frame.resistanceOhms.toDouble()
                    }
                }
                AppLogger.i(
                    TAG,
                    "B1 记录: ${weightKg}kg 阻抗=${frame.resistanceOhms}Ω 时间=${frame.timestampEpochSeconds} " +
                        (if (isFresh) "本次" else "历史") + (if (isNew) " 新" else " 重复")
                )
            }

            is AFUB1PacketParser.Frame.ImpedanceResult -> {
                if (frame.valid && frame.adcOhms > 0) {
                    _impedance.value = frame.adcOhms.toDouble()
                    AppLogger.i(TAG, "B1 单次阻抗结果: ${frame.adcOhms}Ω")
                }
            }

            // 加密报告分片 / 确认帧 / 其它控制帧：无测量数据，已在上面统一确认
            else -> Unit
        }
    }

    /** 体重锁定后启动提交流程：等秤空闲 → 重新对时并索要本次记录。 */
    private fun startAfuB1CommitFlow() {
        if (afuB1CommitStarted) return
        afuB1CommitStarted = true
        afuB1CommitRequested = false
        afuB1FreshRecordSeen = false
        scheduleAfuB1CommitStep(AFUB1_IDLE_POLL_MS)
    }

    private fun scheduleAfuB1CommitStep(delayMs: Long) {
        afuB1CommitRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { runAfuB1CommitStep() }
        afuB1CommitRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun runAfuB1CommitStep() {
        val now = System.currentTimeMillis()
        if (afuB1CommitRequested) {
            if (afuB1FreshRecordSeen || now >= afuB1CommitDeadline) {
                if (!afuB1FreshRecordSeen) {
                    AppLogger.w(TAG, "B1 未在 ${AFUB1_COMMIT_TIMEOUT_MS}ms 内回推本次记录，沿用实时体重")
                }
                sendAfuB1Finalize()
                afuB1CommitStarted = false
                afuB1CommitRequested = false
                return
            }
            scheduleAfuB1CommitStep(AFUB1_IDLE_POLL_MS)
            return
        }

        // 秤仍在推送数据 → 用户尚未下秤，继续等待空闲
        if (now - afuB1LastFrameAt < AFUB1_IDLE_MS) {
            scheduleAfuB1CommitStep(AFUB1_IDLE_POLL_MS)
            return
        }

        afuB1CommitRequested = true
        afuB1CommitDeadline = now + AFUB1_COMMIT_TIMEOUT_MS
        AppLogger.i(TAG, "B1 已空闲（用户下秤），重新对时并拉取本次测量记录…")
        sendAfuB1TimeSync()
        sendAfuB1SyncRequestPair()
        sendAfuB1UserProfile(AFUB1PacketParser.ProfileVariant.GET_WEIGH_AGAIN_1)
        sendAfuB1UserProfile(AFUB1PacketParser.ProfileVariant.GET_WEIGH_AGAIN_2)
        writeAfuB1(AFUB1PacketParser.buildChunkRequest())
        scheduleAfuB1CommitStep(AFUB1_IDLE_POLL_MS)
    }

    /** 结束本次会话并让秤准备好下一次称重（0x68 档案 + 对时 + 同步请求）。 */
    private fun sendAfuB1Finalize() {
        sendAfuB1UserProfile(AFUB1PacketParser.ProfileVariant.FINAL)
        sendAfuB1TimeSync()
        sendAfuB1SyncRequestPair()
    }

    /** 清空 B1 会话状态与未执行的提交流程任务。 */
    private fun resetAfuB1Session() {
        afuB1CommitRunnable?.let { handler.removeCallbacks(it) }
        afuB1CommitRunnable = null
        isAfuB1Device = false
        afuB1WriteServiceUuid = null
        afuB1WriteCharUuid = null
        afuB1HandshakeDone = false
        afuB1ProfileSent = false
        afuB1LastFrameAt = 0L
        afuB1FinalWeightG = 0
        afuB1CommitStarted = false
        afuB1CommitRequested = false
        afuB1CommitDeadline = 0L
        afuB1FreshRecordSeen = false
        afuB1SeenRecords.clear()
    }

    // -------------------------------------------------------------------------
    // GATT 事件回调
    // -------------------------------------------------------------------------

    /**
     * GATT 协议事件回调，处理连接状态变化、服务发现、描述符写入及特征值通知四类事件。
     */
    private val gattCallback = object : BluetoothGattCallback() {

        /**
         * GATT 连接状态变化回调。
         *
         * 处理逻辑：
         * 1. 取消正在等待的连接看门狗与调度任务。
         * 2. STATE_CONNECTED && GATT_SUCCESS：
         *    - 物理链路建立成功，重置重试计数器 gattRetryCount = 0。
         *    - 请求高优先级连接参数，延迟 300ms 发起 discoverServices()。
         * 3. STATE_DISCONNECTED：
         *    - 关闭当前 GATT 实例，释放底层 Binder 与 Client 句柄。
         *    - 重置测量数据（体重、稳定状态、阻抗）。
         *    - 区分两种断开场景：
         *      A. 正常完成称重后的秤端休眠断开（此前处于 CONNECTED 或 MEASURING）：
         *         - 重置 gattRetryCount = 0，非故障。
         *         - 延时 800ms 重新恢复扫描，以便用户下次踩秤时即踩即连。
         *      B. 连接建立阶段失败（此前处于 CONNECTING，如 Status 133 / 超时等）：
         *         - 执行指数退避重试（最长 8s）。
         *         - 达到最大重试后不永久停止，冷却 5s 后重置并恢复扫描，避免秤被重新唤醒时无法连接。
         * 4. 其他异常状态：关闭 GATT，并在 1s 后恢复扫描。
         *
         * @param gatt     GATT 客户端实例。
         * @param status   操作结果状态码（[BluetoothGatt.GATT_SUCCESS] 为成功）。
         * @param newState 新的连接状态（[BluetoothProfile.STATE_CONNECTED] 或 [BluetoothProfile.STATE_DISCONNECTED]）。
         */
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt) {
                AppLogger.d(TAG, "忽略过期 GATT 连接回调")
                runCatching { gatt.close() }
                return
            }
            BleProtocolRecorder.recordConnectionState(gatt.device?.address ?: "", status, newState)
            AppLogger.i(TAG, "GATT 连接状态变更: status=$status, newState=$newState, currentConnectionState=${_connectionState.value}")
            // 连接事件发生，取消超时看门狗与延时任务
            connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
            connectTimeoutRunnable = null
            pendingConnectRunnable?.let { handler.removeCallbacks(it) }
            pendingConnectRunnable = null

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                // 物理连接建立成功，重置重试计数器
                gattRetryCount = 0
                _connectionState.value = ConnectionState.CONNECTING
                // 为服务发现和特征订阅设置超时看门狗（10秒），防止 discoverServices() 挂起无响应
                val discoveryTimeout = Runnable {
                    if (_connectionState.value == ConnectionState.CONNECTING) {
                        AppLogger.w(TAG, "GATT 服务发现或特征订阅超时(10s)，主动断开重连")
                        try {
                            gatt.disconnect()
                            gatt.close()
                        } catch (e: Exception) {}
                        if (bluetoothGatt == gatt) {
                            bluetoothGatt = null
                        }
                        _connectionState.value = ConnectionState.IDLE
                        startScan()
                    }
                }
                connectTimeoutRunnable = discoveryTimeout
                handler.postDelayed(discoveryTimeout, 10000L)

                // 请求高优先级连接参数
                handler.post {
                    BleProtocolRecorder.recordConnectionPriority(
                        gatt.device?.address ?: "",
                        BluetoothGatt.CONNECTION_PRIORITY_HIGH
                    )
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                }

                // 延迟 300ms 再发起服务发现，防止与连接参数协商在链路层产生报文碰撞
                handler.postDelayed({
                    if (_connectionState.value == ConnectionState.CONNECTING && bluetoothGatt == gatt) {
                        AppLogger.i(TAG, "发起 GATT discoverServices()")
                        // 【Debug 抓包】记录服务发现请求的发起与是否被系统接受
                        val started = gatt.discoverServices()
                        BleProtocolRecorder.recordServiceDiscoveryRequest(gatt.device?.address ?: "", started)
                    }
                }, 300L)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // 收到断开事件，必须先彻底关闭当前 GATT 客户端实例释放底层 Binder / Client 句柄
                try {
                    gatt.close()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "断开连接关闭 GATT 异常: ${e.message}")
                }
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                }
                pendingSubscribeList.clear()
                pendingSubscribeIndex = 0

                // 区分是"正常测量完成后的设备休眠断开"还是"连接发起阶段的握手失败"
                val wasPreviouslyConnected = _connectionState.value == ConnectionState.CONNECTED ||
                                            _connectionState.value == ConnectionState.MEASURING

                _connectionState.value = ConnectionState.IDLE
                _weight.value = 0.0
                _isStable.value = false
                _impedance.value = 0.0

                if (wasPreviouslyConnected) {
                    // 场景 1：设备已完成称重，秤端自动休眠断开（常见 status: 19 远端用户终止、8 超时等）
                    // 此为体脂秤标准节能行为，绝对不是连接故障！立即重置重试计数，并恢复扫描等待下次上秤
                    gattRetryCount = 0
                    AppLogger.i(TAG, "体脂秤已正常休眠/断开 (status=$status)，重置测量状态，延时恢复扫描等待下次上秤...")
                    if (!isPairingMode && hasRememberedDevice) {
                        handler.postDelayed({
                            if (!isPairingMode && hasRememberedDevice && _connectionState.value == ConnectionState.IDLE) {
                                AppLogger.i(TAG, "秤休眠后延时恢复扫描成功，就绪等待下次上秤")
                                startScan()
                            }
                        }, 800L)
                    }
                } else {
                    // 场景 2：在 CONNECTING 阶段连接失败（如 Status 133、超时或秤刚唤醒就断开）
                    AppLogger.w(TAG, "GATT 连接建立失败 (status=$status)，当前重试次数: $gattRetryCount / $MAX_GATT_RETRY")
                    if (!isPairingMode && hasRememberedDevice) {
                        if (gattRetryCount < MAX_GATT_RETRY) {
                            val delayMs = (1000L * (1 shl gattRetryCount)).coerceAtMost(8000L)
                            gattRetryCount++
                            BleProtocolRecorder.recordConnectRetry(
                                address = bluetoothGatt?.device?.address ?: "",
                                attempt = gattRetryCount,
                                delayMs = delayMs
                            )
                            AppLogger.w(TAG, "将在 ${delayMs}ms 后恢复扫描以重连目标秤（第 $gattRetryCount 次）")
                            handler.postDelayed({
                                if (!isPairingMode && hasRememberedDevice && _connectionState.value == ConnectionState.IDLE) {
                                    startScan()
                                }
                            }, delayMs)
                        } else {
                            // 达到最大重试后不永久停止，冷却 5 秒后重置重试计数并继续恢复扫描，保证秤重新踩亮时能连上
                            gattRetryCount = 0
                            AppLogger.w(TAG, "GATT 连接连续失败已达 $MAX_GATT_RETRY 次，冷却 5s 后重置并恢复扫描...")
                            handler.postDelayed({
                                if (!isPairingMode && hasRememberedDevice && _connectionState.value == ConnectionState.IDLE) {
                                    startScan()
                                }
                            }, 5000L)
                        }
                    }
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                // 其他异常状态（如未处于 STATE_DISCONNECTED 却收到 status != 0）
                AppLogger.w(TAG, "GATT 出现异常状态 status=$status, newState=$newState，关闭连接")
                try {
                    gatt.close()
                } catch (e: Exception) {}
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                }
                _connectionState.value = ConnectionState.IDLE
                if (!isPairingMode && hasRememberedDevice) {
                    handler.postDelayed({
                        if (!isPairingMode && hasRememberedDevice && _connectionState.value == ConnectionState.IDLE) {
                            startScan()
                        }
                    }, 1000L)
                }
            }
        }

        /**
         * GATT 服务发现完成回调。
         *
         * 成功后遍历所有非系统服务（过滤 GAP/GATT 通用服务及非秤标准服务），
         * 收集所有支持 Notify 或 Indicate 的特征加入待订阅队列，
         * 然后调用 [subscribeNextCharacteristic] 开始串行订阅流程。
         *
         * @param gatt   GATT 客户端实例。
         * @param status 发现结果状态码。
         */
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) return
            AppLogger.i(TAG, "GATT 服务发现完成: status=$status")
            // 【Debug 抓包】无论成功失败都记录服务发现结果；失败时这里是唯一能定位原因的入口
            BleProtocolRecorder.recordServiceDiscovery(status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // 清空订阅队列，准备重新收集
                pendingSubscribeList.clear()
                pendingSubscribeIndex = 0

                // 遍历所有 GATT 服务，收集可订阅的特征
                for (service in gatt.services) {
                    val sUuid = service.uuid.toString().uppercase()
                    // 【Debug 抓包】记录服务发现的结果拓扑：服务、特征属性与描述符
                    BleProtocolRecorder.recordService(service.uuid, service.type)
                    for (char in service.characteristics) {
                        BleProtocolRecorder.recordCharacteristic(
                            serviceUuid = service.uuid,
                            charUuid = char.uuid,
                            properties = char.properties,
                            permissions = char.permissions,
                            descriptors = char.descriptors
                        )
                    }
                    // 跳过 GAP（0x1800）、GATT（0x1801）、设备信息（0x180A）、电池（0x180F）、时间（0x1805）等通用系统服务
                    val isSystemOrIgnored = sUuid.startsWith("00001800") ||
                                           sUuid.startsWith("00001801") ||
                                           sUuid.startsWith("0000180A") ||
                                           sUuid.startsWith("0000180F") ||
                                           sUuid.startsWith("00001805")
                    if (isSystemOrIgnored) continue

                    for (char in service.characteristics) {
                        val props = char.properties
                        // 收集支持 NOTIFY 或 INDICATE 的特征，这些特征会主动推送测量数据
                        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                            props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                            pendingSubscribeList.add(char)
                        }
                    }
                }

                // 识别薄荷健康 B1 (AFU-BH-TZ-B1)：服务 0xFC50 + 专属 af0000xx 特征
                detectAfuB1Device(gatt)

                AppLogger.i(TAG, "共找到 ${pendingSubscribeList.size} 个有效通知特征")
                // 【Debug 抓包】记录本次确定的完整订阅清单
                BleProtocolRecorder.recordSubscribePlan(
                    total = pendingSubscribeList.size,
                    charUuids = pendingSubscribeList.map { it.uuid }
                )
                // 开始串行订阅流程（第一个特征）
                subscribeNextCharacteristic(gatt)
            }
        }

        /**
         * GATT 描述符写入完成回调。
         *
         * 每次 [BluetoothGatt.writeDescriptor] 操作完成（成功或失败）后触发，
         * 继续串行订阅队列中的下一个特征。
         *
         * @param gatt       GATT 客户端实例。
         * @param descriptor 已完成写入的描述符。
         * @param status     写入操作结果状态码。
         */
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (gatt !== bluetoothGatt) return
            AppLogger.d(TAG, "描述符写入完成: status=$status, 特征: ${descriptor.characteristic?.uuid}")
            // 【Debug 抓包】记录 CCCD 写入回调及其 status 码
            BleProtocolRecorder.recordDescriptorWriteResult(
                charUuid = descriptor.characteristic?.uuid,
                descriptorUuid = descriptor.uuid,
                status = status
            )
            // 收到写入回调，取消该特征的超时定时器
            descriptorTimeoutRunnable?.let { handler.removeCallbacks(it) }
            descriptorTimeoutRunnable = null
            // 无论成功或失败，继续订阅队列中的下一个特征
            subscribeNextCharacteristic(gatt)
        }

        /**
         * GATT 特征写入完成回调。
         *
         * [BluetoothGatt.writeCharacteristic] 的返回值仅表示「写入已入队」，
         * 真正的写入结果（尤其 Release/Response 写入是否被对端 ACK）必须由本回调给出。
         * Debug 抓包据此补全 OUT 方向的最终结果码，从而完整还原一次下行写入的生命周期。
         *
         * @param gatt           GATT 客户端实例。
         * @param characteristic 被写入的特征。
         * @param status         写入结果状态码（0 为成功）。
         */
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (gatt !== bluetoothGatt) return
            AppLogger.d(TAG, "特征写入完成: status=$status, 特征: ${characteristic.uuid}")
            // 【Debug 抓包】记录下行写入的最终 GATT 结果码
            BleProtocolRecorder.recordWriteResult(
                charUuid = characteristic.uuid,
                status = status
            )
        }

        // Android 13+ (API 33+) overload
        /**
         * 特征值变化通知回调（Android 13+ / API 33+ 新签名）。
         *
         * Android 13 起引入此新版重载，直接将通知数据通过参数传入，
         * 不再需要从 [BluetoothGattCharacteristic.value] 读取（已废弃）。
         *
         * @param gatt           GATT 客户端实例。
         * @param characteristic 发出通知的特征。
         * @param value          通知携带的最新数据字节数组。
         */
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (gatt !== bluetoothGatt) return
            handleIncomingData(value, characteristic.uuid.toString())
        }

        // Older Android versions overload
        /**
         * 特征值变化通知回调（Android 12 及以下旧版签名，已废弃但仍需兼容）。
         *
         * 旧版 API 需从 [BluetoothGattCharacteristic.value] 属性读取数据，
         * 此方法在 Android 13+ 上不会被调用（系统优先调用上方新版重载）。
         *
         * @param gatt           GATT 客户端实例。
         * @param characteristic 发出通知的特征（数据从 [BluetoothGattCharacteristic.value] 读取）。
         */
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (gatt !== bluetoothGatt) return
            val data = characteristic.value ?: return
            handleIncomingData(data, characteristic.uuid.toString())
        }
    }
}
