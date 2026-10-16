package com.example.dianzicheng.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.le.ScanResult
import com.example.dianzicheng.BuildConfig
import com.example.dianzicheng.data.local.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "BleProtocol"

/**
 * 蓝牙协议包完整抓包记录器（**仅在 Debug 版本生效**）。
 *
 * 与 [AppLogger] 的区别与分工：
 * - [AppLogger] 面向所有版本，记录人类可读的运行日志，容量仅 1000 条且**不保证**保留原始报文。
 * - 本记录器仅在 `BuildConfig.DEBUG == true` 时开启，逐条记录**完整的蓝牙协议交互报文**：
 *   扫描广播原始字节、GATT 连接/服务发现、特征属性、描述符订阅（CCCD）写入、
 *   下行写入（AFU 握手包）与其 GATT 结果码、上行通知（体重/阻抗原始帧）。
 *   每条记录都带有可回放的完整十六进制载荷。
 *
 * 设计要点：
 * - Release 版本：[isEnabled] 恒为 `false`，所有 record* 方法在首行直接返回，
 *   不产生字符串格式化、不分配缓冲、不推送 StateFlow，**不记录任何报文**。
 *   （注意：本项目 Release 构建未开启 R8 minify，因此这些方法体依然存在于 APK 中，
 *     只是永远不执行；这是有意的取舍——按需开启 minify 时它们会被自动裁剪。）
 * - 独立环形缓冲：[MAX_ENTRIES] 默认 6000 条，远大于日志缓冲，避免一次称重就把报文挤掉。
 * - 明文可回放：同时提供 [exportJsonLines]（每行一个 JSON 对象，便于脚本解析）与 [exportText]（人类可读对齐文本）。
 * - 线程安全：GATT 回调可能来自主线程，扫描回调与看门狗来自其它线程，均以 `@Synchronized` 保护。
 *
 * 使用示例：
 * ```kotlin
 * BleProtocolRecorder.recordScanResult(result)          // 广播包
 * BleProtocolRecorder.recordNotification(uuid, value, parsed)  // 上行通知
 * BleProtocolRecorder.recordWriteResult(uuid, status)   // 下行写入结果
 * ```
 */
@SuppressLint("MissingPermission") // result.device.name 需要 BLUETOOTH_CONNECT，调用方已持有该运行时权限
object BleProtocolRecorder {

    /**
     * 内存环形缓冲区容量。
     * 一次完整称重通常产生 30~200 条报文，6000 条足以覆盖数十次测量而不丢失任何交互。
     */
    const val MAX_ENTRIES = 6000

    /** 单条 JSON 记录中十六进制载荷的最大长度；超出则截断，防止异常大包拖垮内存 */
    private const val MAX_PAYLOAD_HEX = 2048

    /**
     * 是否允许抓包。
     *
     * 只有 Debug 构建（`BuildConfig.DEBUG`）且显式开关为 true 时才为 true；
     * Release 构建下恒为 false，因此所有 record* 方法都会在执行前直接返回。
     */
    val isEnabled: Boolean = BuildConfig.DEBUG && BuildConfig.BLE_PROTOCOL_CAPTURE

    /** 当前是否正在实际记录报文（Release 恒为 false，Debug 恒为 true）。 */
    val isActive: Boolean
        get() = isEnabled

    /** 单条协议报文记录。 */
    data class Entry(
        /** 记录产生的绝对时间（Unix 毫秒） */
        val timeMs: Long,
        /** 相对本次抓包开始的偏移毫秒数，便于还原报文时序 */
        val offsetMs: Long,
        /** 报文方向：SCAN / IN / OUT / EVENT */
        val direction: String,
        /** 事件名，如 SCAN_RESULT / NOTIFY / WRITE / CONNECT_STATE */
        val event: String,
        /** 目标设备 MAC（无设备上下文时为空串） */
        val address: String,
        /** 目标设备名称（无设备上下文时为空串） */
        val name: String,
        /** 报文载荷十六进制（无载荷事件为空串） */
        val payloadHex: String,
        /** 一行式完整 JSON 记录，供导出/分享 */
        val jsonLine: String
    )

    /** 内部环形缓冲区 */
    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES + 1)

    /** 抓包开始时间戳，用于计算 [Entry.offsetMs] */
    private var captureStartMs: Long = 0L

    /** 因超出容量而被丢弃的记录条数（导出时会作为元信息上报） */
    private var droppedCount: Int = 0

    /** 对外暴露的记录流，UI 通过 collectAsState 实时刷新 */
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /**
     * 当前订阅者数量。
     *
     * `subscriptionCount` 只声明在 [kotlinx.coroutines.flow.MutableSharedFlow] 上，
     * 只读的 StateFlow 没有该属性，因此在这里转发内部可变流上的计数，
     * 供上层在「首次订阅」时调用 [publishSnapshot] 补齐快照。
     */
    val subscriptionCount: StateFlow<Int> get() = _entries.subscriptionCount

    // -------------------------------------------------------------------------
    // 抓包控制
    // -------------------------------------------------------------------------

    /**
     * 清空抓包缓冲区并重置计时基准。
     * 建议在开始一次「可复现的」称重复现流程前调用，噪声最小。
     */
    fun clear() {
        if (!isEnabled) return
        synchronized(this) {
            buffer.clear()
            droppedCount = 0
            captureStartMs = 0L
            _entries.value = emptyList()
        }
        AppLogger.i(TAG, "蓝牙协议抓包已清空")
    }

    /** 被环形缓冲丢弃的历史条数。 */
    fun dropped(): Int = if (!isEnabled) 0 else synchronized(this) { droppedCount }

    /**
     * 直接读取缓冲区的最新快照，与 [entries] StateFlow 的推送时机无关。
     *
     * 由于 [entries] 只在有订阅者时才会更新（无观察者时跳过 O(N) 拷贝），
     * 需要"当前真实内容"的场景（例如导出前的非空校验）应改用本方法。
     */
    fun snapshot(): List<Entry> = if (!isEnabled) emptyList() else synchronized(this) { buffer.toList() }

    /**
     * 重新发布一次当前缓冲区快照（仅在确有订阅者时）。
     *
     * 用于 UI 打开抓包查看器／日志查看器的瞬间补齐数据：
     * 避免"后台称重期间无订阅者 → StateFlow 未更新 → 打开查看器看到旧快照"。
     */
    fun publishSnapshot() {
        if (!isEnabled) return
        synchronized(this) {
            if (_entries.subscriptionCount.value > 0) {
                _entries.value = buffer.toList()
            }
        }
    }

    // -------------------------------------------------------------------------
    // 底层记录入口
    // -------------------------------------------------------------------------

    /**
     * 追加一条协议记录。
     *
     * 这是所有 record* 方法的统一落点：负责生成 JSON 行、维护时序偏移与环形缓冲。
     *
     * @param direction 报文方向（SCAN / IN / OUT / EVENT）
     * @param event     事件名
     * @param address   目标设备 MAC
     * @param name      目标设备名称
     * @param payload   原始载荷字节，可为 null（无载荷事件）
     * @param extra     追加到 JSON 中的额外键值对（键与值都会被转义）
     * @return 生成的记录；未开启抓包时返回 null
     */
    @Synchronized
    private fun record(
        direction: String,
        event: String,
        address: String = "",
        name: String = "",
        payload: ByteArray? = null,
        extra: List<Pair<String, Any?>> = emptyList()
    ): Entry? {
        if (!isActive) return null

        val now = System.currentTimeMillis()
        if (captureStartMs == 0L) captureStartMs = now

        val hex = payload?.let { toHex(it) } ?: ""

        val sb = StringBuilder(256)
        sb.append('{')
        sb.append("\"ts\":").append(now).append(',')
        sb.append("\"offsetMs\":").append(now - captureStartMs).append(',')
        sb.append("\"dir\":\"").append(direction).append("\",")
        sb.append("\"event\":\"").append(event).append('"')
        if (address.isNotEmpty()) sb.append(",\"address\":\"").append(escapeJson(address)).append('"')
        if (name.isNotEmpty()) sb.append(",\"name\":\"").append(escapeJson(name)).append('"')
        for ((k, v) in extra) {
            sb.append(",\"").append(escapeJson(k)).append("\":")
            when (v) {
                null -> sb.append("null")
                is Number, is Boolean -> sb.append(v.toString())
                else -> sb.append('"').append(escapeJson(v.toString())).append('"')
            }
        }
        if (hex.isNotEmpty()) {
            sb.append(",\"len\":").append(payload?.size ?: 0)
            sb.append(",\"hex\":\"").append(hex).append('"')
        }
        sb.append('}')

        val entry = Entry(
            timeMs = now,
            offsetMs = now - captureStartMs,
            direction = direction,
            event = event,
            address = address,
            name = name,
            payloadHex = hex,
            jsonLine = sb.toString()
        )

        buffer.addLast(entry)
        if (buffer.size > MAX_ENTRIES) {
            buffer.pollFirst()
            droppedCount++
        }
        // 快照推送（O(N) 拷贝）仅在抓包查看 UI 真正订阅时执行；
        // 扫描高峰期每包一条记录，无观察者时跳过一次最多 6000 条的拷贝
        if (_entries.subscriptionCount.value > 0) {
            _entries.value = buffer.toList()
        }
        return entry
    }

    // -------------------------------------------------------------------------
    // 各类蓝牙报文记录 API
    // -------------------------------------------------------------------------

    /** 记录一条 BLE 扫描广播包（含完整 AD 结构原始字节）。 */
    fun recordScanResult(result: ScanResult) {
        if (!isActive) return
        // 注意：局部变量不可命名为 record，否则会遮蔽同名的私有方法 record(...)
        val scanRecord = result.scanRecord
        val raw = scanRecord?.bytes
        val deviceName = result.device.name ?: scanRecord?.deviceName
        record(
            direction = "SCAN",
            event = "SCAN_RESULT",
            address = result.device.address ?: "",
            name = deviceName ?: "",
            payload = raw,
            extra = listOf(
                "rssi" to result.rssi,
                "txPower" to result.txPower,
                "connectable" to result.isConnectable,
                "serviceUuids" to (scanRecord?.serviceUuids?.joinToString(",") { it.uuid.toString() } ?: ""),
                "deviceName" to (scanRecord?.deviceName ?: ""),
                "adStructures" to describeAdStructures(raw)
            )
        )
    }

    /** 记录 BLE 扫描启动失败事件。 */
    fun recordScanFailed(errorCode: Int) {
        record(
            direction = "EVENT",
            event = "SCAN_FAILED",
            extra = listOf("errorCode" to errorCode)
        )
    }

    /** 记录扫描生命周期事件（启动/停止/跳过）。 */
    fun recordScanLifecycle(event: String, detail: String = "") {
        record(
            direction = "EVENT",
            event = event,
            extra = if (detail.isEmpty()) emptyList() else listOf("detail" to detail)
        )
    }

    /** 记录发起 GATT 连接的动作。 */
    fun recordConnectAttempt(address: String, name: String = "", transport: String = "LE") {
        record(direction = "EVENT", event = "CONNECT_ATTEMPT", address = address, name = name, extra = listOf("transport" to transport))
    }

    /** 记录一次连接重试调度。 */
    fun recordConnectRetry(address: String, attempt: Int, delayMs: Long) {
        record(
            direction = "EVENT",
            event = "CONNECT_RETRY",
            address = address,
            extra = listOf("attempt" to attempt, "delayMs" to delayMs)
        )
    }

    /** 记录 GATT 连接超时看门狗触发。 */
    fun recordConnectTimeout(address: String, timeoutMs: Long) {
        record(direction = "EVENT", event = "CONNECT_TIMEOUT", address = address, extra = listOf("timeoutMs" to timeoutMs))
    }

    /** 记录用户手动绑定/连接设备的动作。 */
    fun recordManualConnect(address: String, name: String, isBroadcastScale: Boolean) {
        record(
            direction = "EVENT",
            event = "MANUAL_CONNECT",
            address = address,
            name = name,
            extra = listOf("isBroadcastScale" to isBroadcastScale)
        )
    }

    /**
     * 记录 GATT 连接状态变更（含 status 码，如 133 / 8 / 19 等关键诊断信息）。
     */
    fun recordConnectionState(address: String, status: Int, newState: Int) {
        record(
            direction = "EVENT",
            event = "CONNECT_STATE",
            address = address,
            extra = listOf(
                "status" to status,
                "newState" to newState,
                "statusName" to gattStatusName(status),
                "stateName" to connectionStateName(newState)
            )
        )
    }

    /** 记录请求连接优先级。 */
    fun recordConnectionPriority(address: String, priority: Int) {
        record(direction = "EVENT", event = "CONNECTION_PRIORITY", address = address, extra = listOf("priority" to priority))
    }

    /** 记录服务发现请求的发起（[android.bluetooth.BluetoothGatt.discoverServices] 的返回值）。 */
    fun recordServiceDiscoveryRequest(address: String, started: Boolean) {
        record(
            direction = "EVENT",
            event = "DISCOVER_SERVICES_REQUEST",
            address = address,
            extra = listOf("accepted" to started)
        )
    }

    /**
     * 记录服务发现的最终结果。
     *
     * 必须**无论成功失败都调用**：服务发现失败时，本条目是唯一能定位原因的证据。
     */
    fun recordServiceDiscovery(status: Int) {
        record(
            direction = "EVENT",
            event = "SERVICE_DISCOVERY_RESULT",
            extra = listOf(
                "status" to status,
                "statusName" to gattStatusName(status),
                "success" to (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS)
            )
        )
    }

    /** 记录一次服务的全部特征与描述符（服务发现结果，完整协议拓扑）。 */
    fun recordService(serviceUuid: UUID, serviceType: Int) {
        record(
            direction = "EVENT",
            event = "SERVICE_FOUND",
            extra = listOf("serviceUuid" to serviceUuid.toString(), "serviceType" to serviceType)
        )
    }

    /**
     * 记录一个特征的属性与全部描述符。
     *
     * 这是协议抓包中最关键的静态信息：只有知道特征的 properties 位掩码，
     * 才能解释后续 writeType / 通知订阅行为。
     */
    fun recordCharacteristic(
        serviceUuid: UUID,
        charUuid: UUID,
        properties: Int,
        permissions: Int,
        descriptors: List<BluetoothGattDescriptor>
    ) {
        record(
            direction = "EVENT",
            event = "CHARACTERISTIC",
            extra = listOf(
                "serviceUuid" to serviceUuid.toString(),
                "charUuid" to charUuid.toString(),
                "properties" to properties,
                "propertiesHex" to "0x%02X".format(properties),
                "propertiesName" to characteristicPropertiesName(properties),
                "permissions" to permissions,
                "descriptors" to descriptors.joinToString(",") { it.uuid.toString() }
            )
        )
    }

    /** 记录一次特征订阅（本地开启通知 + CCCD 写入值）。 */
    fun recordSubscribe(
        charUuid: UUID,
        properties: Int,
        descriptor: BluetoothGattDescriptor?,
        cccdValue: ByteArray?
    ) {
        record(
            direction = "EVENT",
            event = "SUBSCRIBE",
            extra = listOf(
                "charUuid" to charUuid.toString(),
                "properties" to properties,
                "propertiesName" to characteristicPropertiesName(properties),
                "descriptorUuid" to (descriptor?.uuid?.toString() ?: ""),
                "cccdHex" to (cccdValue?.let { toHex(it) } ?: ""),
                "hasCccd" to (descriptor != null)
            )
        )
    }

    /**
     * 记录要订阅的通知特征集合（服务发现后确定的完整订阅清单）。
     */
    fun recordSubscribePlan(total: Int, charUuids: List<UUID>) {
        record(
            direction = "EVENT",
            event = "SUBSCRIBE_PLAN",
            extra = listOf(
                "total" to total,
                "charUuids" to charUuids.joinToString(",") { it.toString() }
            )
        )
    }

    /** 记录描述符写入的发起结果。 */
    fun recordDescriptorWrite(charUuid: UUID?, descriptorUuid: UUID?, value: ByteArray?, success: Boolean) {
        record(
            direction = "OUT",
            event = "DESCRIPTOR_WRITE",
            payload = value,
            extra = listOf(
                "charUuid" to (charUuid?.toString() ?: ""),
                "descriptorUuid" to (descriptorUuid?.toString() ?: ""),
                "success" to success
            )
        )
    }

    /** 记录描述符写入回调（含 status）。 */
    fun recordDescriptorWriteResult(charUuid: UUID?, descriptorUuid: UUID?, status: Int) {
        record(
            direction = "EVENT",
            event = "DESCRIPTOR_WRITE_RESULT",
            extra = listOf(
                "charUuid" to (charUuid?.toString() ?: ""),
                "descriptorUuid" to (descriptorUuid?.toString() ?: ""),
                "status" to status
            )
        )
    }

    /**
     * 记录一次下行写入（例如 AFU 握手包 `FD 37 ... 37`）。
     *
     * @param success 写入发起是否成功；旧版 API 无返回值时传 null
     */
    fun recordWrite(charUuid: UUID?, serviceUuid: UUID?, value: ByteArray?, writeType: Int, success: Boolean?) {
        record(
            direction = "OUT",
            event = "WRITE",
            payload = value,
            extra = listOf(
                "charUuid" to (charUuid?.toString() ?: ""),
                "serviceUuid" to (serviceUuid?.toString() ?: ""),
                "writeType" to writeType,
                "writeTypeName" to writeTypeName(writeType),
                "success" to success
            )
        )
    }

    /**
     * 记录一次下行写入的**最终 GATT 结果**（来自 `onCharacteristicWrite` 回调）。
     *
     * 与 [recordWrite] 的区别：后者记录的是「写入已入队」，本方法记录的是对端是否真正收到。
     * 两者配对出现，才构成一次下行写入的完整生命周期。
     */
    fun recordWriteResult(charUuid: UUID?, status: Int) {
        record(
            direction = "EVENT",
            event = "WRITE_RESULT",
            extra = listOf(
                "charUuid" to (charUuid?.toString() ?: ""),
                "status" to status,
                "statusName" to gattStatusName(status),
                "success" to (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS)
            )
        )
    }

    /**
     * 记录一次上行通知/指示（体重与阻抗的原始帧）。
     *
     * @param parsed 解析结果摘要（解析失败时为 null）
     */
    fun recordNotification(charUuid: UUID?, value: ByteArray, parsed: MultiScalePacketParser.ParsedScaleData?) {
        record(
            direction = "IN",
            event = "NOTIFY",
            payload = value,
            extra = listOf(
                "charUuid" to (charUuid?.toString() ?: ""),
                "protocol" to (parsed?.protocolName ?: ""),
                "weightKg" to (parsed?.weightKg ?: 0.0),
                "isStable" to (parsed?.isStable ?: false),
                "impedanceOhm" to (parsed?.impedanceOhm ?: 0.0)
            )
        )
        // 单独再落一条未识别标记，便于在导出文件中一眼筛出「协议解析失败」的原始帧
        if (parsed == null) {
            recordUnparsed(charUuid, value)
        }
    }

    /** 记录一条解析失败/未识别的原始报文，便于协议逆向。 */
    fun recordUnparsed(charUuid: UUID?, value: ByteArray) {
        record(
            direction = "IN",
            event = "NOTIFY_UNPARSED",
            payload = value,
            extra = listOf("charUuid" to (charUuid?.toString() ?: ""))
        )
    }

    /** 记录广播/通知中包含的原始 AD Structure 结构（0xFF 厂商数据 / 0x16 服务数据等）。 */
    fun recordAdStructures(source: String, raw: ByteArray?) {
        record(
            direction = "SCAN",
            event = "AD_STRUCTURES",
            payload = raw,
            extra = listOf("source" to source, "adStructures" to describeAdStructures(raw))
        )
    }

    // -------------------------------------------------------------------------
    // 导出
    // -------------------------------------------------------------------------

    /** 导出为 JSON Lines 文本：每行一个独立的 JSON 对象，便于脚本离线解析。 */
    fun exportJsonLines(): String = synchronized(this) {
        if (!isEnabled) return ""
        buildString {
            append(metaJsonLine())
            append('\n')
            buffer.forEach { append(it.jsonLine).append('\n') }
        }
    }

    /**
     * 导出为人类可读的对齐文本：
     * 顶部为抓包元信息，正文每条报文一行，含完整十六进制载荷。
     */
    fun exportText(): String = synchronized(this) {
        if (!isEnabled) return ""
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        buildString {
            appendLine("╔══════════════════════════════════════════════════════════════")
            appendLine("║ 蓝牙协议完整抓包 (Debug 专用)")
            appendLine("╠══════════════════════════════════════════════════════════════")
            appendLine("║ 记录条数 : ${buffer.size}${if (droppedCount > 0) " (已滚出 $droppedCount 条)" else ""}")
            appendLine("║ 起止时间 : ${fmt.format(Date(captureStartMs))} ~ ${fmt.format(Date(System.currentTimeMillis()))}")
            appendLine("║ 说明     : SCAN=广播  IN=秤→手机  OUT=手机→秤  EVENT=连接/订阅状态机")
            appendLine("╚══════════════════════════════════════════════════════════════")
            buffer.forEachIndexed { index, entry ->
                val time = fmt.format(Date(entry.timeMs))
                val header = "[%04d] %s +%-7d %-5s %-20s %s".format(
                    index, time, entry.offsetMs, entry.direction, entry.event, entry.address
                )
                appendLine(header)
                if (entry.name.isNotEmpty()) appendLine("       name     : ${entry.name}")
                if (entry.payloadHex.isNotEmpty()) {
                    appendLine("       payload  : ${entry.payloadHex}")
                    appendLine("       ascii    : ${toAscii(hexToBytes(entry.payloadHex))}")
                }
                appendLine("       json     : ${entry.jsonLine}")
            }
        }
    }

    /** 抓包元信息（JSON 对象，作为 JSONL 文件首行）。 */
    private fun metaJsonLine(): String = buildString {
        append('{')
        append("\"meta\":true")
        append(",\"app\":\"smart-body-scale-android\"")
        append(",\"version\":\"").append(BuildConfig.VERSION_NAME).append('"')
        append(",\"buildType\":\"").append(if (BuildConfig.DEBUG) "debug" else "release").append('"')
        append(",\"entries\":").append(buffer.size)
        append(",\"dropped\":").append(droppedCount)
        append(",\"startedAt\":").append(captureStartMs)
        append(",\"androidSdk\":").append(android.os.Build.VERSION.SDK_INT)
        append(",\"device\":\"").append(escapeJson(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)).append('"')
        append('}')
    }

    // -------------------------------------------------------------------------
    // 格式化工具
    // -------------------------------------------------------------------------

    /** 将字节数组转为大写的空格分隔十六进制字符串（如 `AC 29 00 69`）。 */
    fun toHex(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size * 3)
        for (i in bytes.indices) {
            if (i > 0) sb.append(' ')
            if (sb.length > MAX_PAYLOAD_HEX) {
                sb.append("…")
                break
            }
            val v = bytes[i].toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(v.toString(16).uppercase(Locale.US))
        }
        return sb.toString()
    }

    /** 将十六进制字符串还原为字节数组（导出文本中的载荷可反向回放）。 */
    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace("…", "")
        if (clean.length < 2) return ByteArray(0)
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return ByteArray(0)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** 将字节数组转为可打印 ASCII（不可打印字符以 `.` 表示）。 */
    fun toAscii(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(if (v in 0x20..0x7E) v.toChar() else '.')
        }
        return sb.toString()
    }

    /**
     * 解析 AD Structure 列表，输出 `类型(名称): 载荷十六进制` 的紧凑描述。
     * 用于在不依赖手机 UI 的情况下解读广播内容。
     */
    fun describeAdStructures(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var i = 0
        var guard = 0
        while (i < bytes.size && guard++ < 64) {
            val len = bytes[i].toInt() and 0xFF
            if (len == 0 || i + len >= bytes.size) break
            val type = bytes[i + 1].toInt() and 0xFF
            val value = bytes.copyOfRange(i + 2, i + 1 + len)
            parts.add("0x%02X(%s):%s".format(type, adTypeName(type), toHex(value)))
            i += len + 1
        }
        return parts.joinToString(" | ")
    }

    /** AD 类型码可读名称（仅覆盖调试常用的关键类型）。 */
    private fun adTypeName(type: Int): String = when (type) {
        0x01 -> "Flags"
        0x02, 0x03 -> "UUID16"
        0x06, 0x07 -> "UUID128"
        0x08 -> "ShortName"
        0x09 -> "FullName"
        0x0A -> "TxPower"
        0x16 -> "ServiceData16"
        0x19 -> "Appearance"
        0x20 -> "ServiceData32"
        0x21 -> "ServiceData128"
        0xFF -> "Manufacturer"
        else -> "Type$type"
    }

    /** 特征 properties 位掩码转可读名称。 */
    private fun characteristicPropertiesName(props: Int): String {
        val names = mutableListOf<String>()
        if (props and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) names.add("BROADCAST")
        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) names.add("READ")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) names.add("WRITE_NO_RESPONSE")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) names.add("WRITE")
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) names.add("NOTIFY")
        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) names.add("INDICATE")
        if (props and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) names.add("SIGNED_WRITE")
        if (props and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) names.add("EXTENDED_PROPS")
        return if (names.isEmpty()) "NONE" else names.joinToString("|")
    }

    /** 写入类型可读名称。 */
    private fun writeTypeName(writeType: Int): String = when (writeType) {
        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE -> "WRITE_TYPE_NO_RESPONSE"
        BluetoothGattCharacteristic.WRITE_TYPE_SIGNED -> "WRITE_TYPE_SIGNED"
        else -> "WRITE_TYPE_DEFAULT"
    }

    /** GATT status 码可读名称（聚焦体脂秤调试中最常见的失败码）。 */
    private fun gattStatusName(status: Int): String = when (status) {
        0 -> "GATT_SUCCESS"
        8 -> "GATT_CONN_TIMEOUT"
        19 -> "GATT_CONN_TERMINATE_PEER_USER"
        22 -> "GATT_CONN_TERMINATE_LOCAL_HOST"
        133 -> "GATT_ERROR(133)"
        137 -> "GATT_CONN_TERMINATE_PEER_USER/LINK_LOST"
        143 -> "GATT_CONN_TERMINATE_LOCAL_HOST(143)"
        257 -> "GATT_FAILURE(257)"
        else -> "STATUS_$status"
    }

    /** 连接状态码可读名称。 */
    private fun connectionStateName(state: Int): String = when (state) {
        android.bluetooth.BluetoothProfile.STATE_CONNECTED -> "STATE_CONNECTED"
        android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> "STATE_DISCONNECTED"
        android.bluetooth.BluetoothProfile.STATE_CONNECTING -> "STATE_CONNECTING"
        android.bluetooth.BluetoothProfile.STATE_DISCONNECTING -> "STATE_DISCONNECTING"
        else -> "STATE_$state"
    }

    /** 最小 JSON 字符串转义（引号、反斜杠与控制字符）。 */
    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
