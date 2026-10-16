package com.example.dianzicheng.data.ble

import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid
import com.example.dianzicheng.domain.ScaleModel
import java.util.UUID

/**
 * 体脂秤数据包解析引擎（仅支持薄荷健康/沃莱与小米体脂秤 2 代）。
 *
 * 支持协议：
 * 1. [薄荷健康 / 沃莱 (Yolanda / Boohee)]：AFU 0xAC 20 字节复合帧（私有服务 0xFFB0）与
 *    0xAC 8 字节流式帧、0xA2 / 0xA3 复合帧、0xCA 0x20 透传帧。
 * 2. [小米体脂秤 2 代]：0x181B Body Composition 广播 Service Data（13 字节，即踩即读无需配对）。
 *
 * 其它品牌（蓝牙 SIG 通用秤、OKOK/芯海、香山、斐讯、小米体重秤 1 代等）的解析支持已移除。
 */
object MultiScalePacketParser {

    /** 蓝牙 SIG 标准 Body Composition Service UUID (0x181B)，小米体脂秤 2 代广播服务 */
    val UUID_SERVICE_SIG_BCS: UUID = UUID.fromString("0000181B-0000-1000-8000-00805F9B34FB")

    /** 薄荷健康 / 沃莱 AFU 私有服务 UUID (0xFFB0) */
    val UUID_SERVICE_AFU: UUID = UUID.fromString("0000FFB0-0000-1000-8000-00805F9B34FB")

    /** 薄荷健康 / 沃莱 Yolanda 扩展服务 UUID (0xFFA0) */
    val UUID_SERVICE_YOLANDA: UUID = UUID.fromString("0000FFA0-0000-1000-8000-00805F9B34FB")

    /**
     * 解析完成的秤面数据。
     *
     * @property weightKg      体重数值（千克，kg）
     * @property isStable      是否已稳定锁定
     * @property impedanceOhm  体内阻抗数值（欧姆，Ω），为 null 表示未测出或不支持
     * @property protocolName  命中并解析的协议名称（用于日志标记与诊断）
     */
    data class ParsedScaleData(
        val weightKg: Double,
        val isStable: Boolean,
        val impedanceOhm: Double? = null,
        val protocolName: String
    )

    /**
     * 明确已知的非体脂秤厂商 Company ID 集合（手机、电脑、芯片原厂、通用外设等）。
     * 当广播包来自这些厂商时，严禁盲目将其载荷作为体脂秤协议解析，
     * 杜绝苹果 Continuity、三星、华为等设备伪报假体重。
     */
    val NON_SCALE_COMPANY_IDS = setOf(
        0x004C, // Apple Inc.
        0x0006, // Microsoft
        0x00E0, // Google LLC
        0x0075, // Samsung Electronics
        0x027D, // Huawei Technologies
        0x000A, // Qualcomm
        0x0001, // Nokia
        0x0002, // Intel
        0x000F, // Broadcom
        0x0087  // Garmin
    )

    /**
     * 从 BLE 广播原始字节数组中按标准 AD Structure 格式解析设备名称。
     */
    fun parseNameFromBytes(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        var i = 0
        while (i < bytes.size) {
            val length = bytes[i].toInt() and 0xFF
            if (length == 0) break
            if (i + length >= bytes.size) break
            val type = bytes[i + 1].toInt() and 0xFF
            if ((type == 0x08 || type == 0x09) && length > 1) {
                return try {
                    String(bytes, i + 2, length - 1, Charsets.UTF_8).trim()
                } catch (e: Exception) {
                    null
                }
            }
            i += length + 1
        }
        return null
    }

    /**
     * 解析特征值通知或指示（GATT Notification / Indication）数据包。
     *
     * 依次尝试薄荷健康/沃莱协议与小米体脂秤 2 代协议，支持传入用户手动选择的型号
     * [preferredModel] 优先匹配。
     *
     * @param data           接收到的原始字节数组
     * @param charUuid       发出通知的特征 UUID（用于辅助区分各厂商私有特征）
     * @param preferredModel 用户手动偏好的型号（默认 AUTO，指定时优先尝试该协议）
     * @return 成功解析出有效数据时返回 [ParsedScaleData]，否则返回 null
     */
    fun parseNotification(
        data: ByteArray,
        charUuid: String? = null,
        preferredModel: ScaleModel = ScaleModel.AUTO
    ): ParsedScaleData? {
        if (data.isEmpty()) return null

        val uuidStr = charUuid?.uppercase() ?: ""

        // 优先分支 1：用户明确指定了偏好型号，优先执行该型号的专属解析
        when (preferredModel) {
            ScaleModel.BOOHEE_YOLANDA -> {
                parseAfu(data)?.let { return it }
                parseYolandaIcomon(data)?.let { return it }
            }
            ScaleModel.XIAOMI_SCALE_2 -> parseXiaomiPayload(data)?.let { return it }
            else -> {}
        }

        // 优先分支 2：特征 UUID 明确匹配
        // 薄荷健康 / 沃莱 AFU 私有特征 (0xFFB0 服务 / 0xFFB2 通知)
        if (uuidStr.contains("FFB2") || uuidStr.contains("FFB0")) {
            parseAfu(data)?.let { return it }
        }
        // 薄荷健康 / 沃莱 Yolanda 特征 (0xFFA1 / 0xFFE1)
        if (uuidStr.contains("FFA1") || uuidStr.contains("FFE1")) {
            parseYolandaIcomon(data)?.let { return it }
        }
        // 小米体脂秤 2 代 (0x181B Body Composition)
        if (uuidStr.contains("181B")) {
            parseXiaomiPayload(data)?.let { return it }
        }

        // 默认/兜底级联匹配
        // 1. 薄荷健康 / 沃莱 AFU 20 字节复合帧（含 0xAC 帧头，严格校验 w3 基准偏移 0x68，防串扰）
        parseAfu(data)?.let { return it }

        // 2. 薄荷健康 / 沃莱 8 字节流与 0xA2 / 0xA3 / 0xCA20 复合帧
        parseYolandaIcomon(data)?.let { return it }

        // 3. 小米体脂秤 2 代格式（内部严格校验年月日时间合法性，杜绝未知特征误判）
        parseXiaomiPayload(data)?.let { return it }

        return null
    }

    /**
     * 解析 BLE 广播数据包（针对小米体脂秤 2 代等无需连接直接广播体重的免配对广播秤）。
     *
     * @param scanRecord 扫描返回的 [ScanRecord] 对象
     * @param preferredModel 用户手动偏好的型号（默认 AUTO）
     * @return 解析出有效测量数据则返回 [ParsedScaleData]，否则返回 null
     */
    fun parseAdvertisement(
        scanRecord: ScanRecord?,
        preferredModel: ScaleModel = ScaleModel.AUTO
    ): ParsedScaleData? {
        if (scanRecord == null) return null

        val bcsParcel = ParcelUuid(UUID_SERVICE_SIG_BCS)
        val bcsData = scanRecord.getServiceData(bcsParcel)

        val serviceUuidStrings = scanRecord.serviceUuids?.map { it.uuid.toString() } ?: emptyList()

        return parseAdvertisementBytes(
            rawBytes = scanRecord.bytes,
            serviceUuidStrings = serviceUuidStrings,
            bcsServiceData = bcsData,
            preferredModel = preferredModel
        )
    }

    /**
     * 底层直接基于原始广播字节与服务信息解析，便于直接在无 Android 硬件环境下进行单元测试与诊断。
     */
    fun parseAdvertisementBytes(
        rawBytes: ByteArray?,
        serviceUuidStrings: List<String> = emptyList(),
        bcsServiceData: ByteArray? = null,
        preferredModel: ScaleModel = ScaleModel.AUTO
    ): ParsedScaleData? {
        if (rawBytes == null && bcsServiceData == null) return null

        // 若广播中明确声明包含 AFU 服务 UUID (FFB0)，则必为双向 GATT 交互秤（需 FFB1 握手与 FFB2 订阅），
        // 绝不可作为广播秤处理，必须留给 GATT 连接流程
        if (serviceUuidStrings.any { it.uppercase().contains("FFB0") }) {
            return null
        }

        // 1. 从小米体脂秤 2 代广播 Service Data (0x181B) 中解析（用户明确偏好薄荷健康时跳过，避免误判）
        if (preferredModel != ScaleModel.BOOHEE_YOLANDA && bcsServiceData != null && bcsServiceData.size >= 12) {
            parseXiaomiBodyCompositionServiceData(bcsServiceData)?.let { return it }
        }

        // 2. 从厂商自定义数据 (0xFF) 或服务数据 (0x16) 中解析薄荷健康 / 沃莱广播款（用户明确偏好小米时跳过）
        if (preferredModel != ScaleModel.XIAOMI_SCALE_2 && rawBytes != null && rawBytes.size >= 6) {
            var i = 0
            while (i < rawBytes.size - 2) {
                val len = rawBytes[i].toInt() and 0xFF
                if (len == 0 || i + len >= rawBytes.size) break
                val type = rawBytes[i + 1].toInt() and 0xFF

                if (type == 0xFF && len >= 3) {
                    val companyId = (rawBytes[i + 2].toInt() and 0xFF) or ((rawBytes[i + 3].toInt() and 0xFF) shl 8)
                    // 苹果、谷歌、微软、三星、华为等非秤厂商的厂商数据一律跳过，避免伪报体重
                    if (companyId in NON_SCALE_COMPANY_IDS) {
                        i += len + 1
                        continue
                    }
                }

                if ((type == 0xFF || type == 0x16) && len >= 5) {
                    val mfgPayload = rawBytes.copyOfRange(i + 2, i + 1 + len)
                    val companyId = if (type == 0xFF && len >= 3) {
                        (rawBytes[i + 2].toInt() and 0xFF) or ((rawBytes[i + 3].toInt() and 0xFF) shl 8)
                    } else -1

                    // AFU 20 字节复合帧属于双向 GATT 秤，绝不可在广播阶段被截断解析
                    if (!isAfuFrame(mfgPayload) && companyId !in NON_SCALE_COMPANY_IDS) {
                        parseYolandaIcomon(mfgPayload)?.let { return it }
                    }

                    // 标准 0xFF 厂商数据前 2 字节为蓝牙联盟 Company ID，跳过 2 字节后再尝试
                    if (len >= 6 && type == 0xFF) {
                        val payloadWithoutCompany = rawBytes.copyOfRange(i + 4, i + 1 + len)
                        if (!isAfuFrame(payloadWithoutCompany) && companyId !in NON_SCALE_COMPANY_IDS) {
                            parseYolandaIcomon(payloadWithoutCompany)?.let { return it }
                        }
                    }
                }
                i += len + 1
            }
        }

        return null
    }

    /**
     * 判断字节序列是否为 AFU 20 字节复合帧（0xAC 帧头，且 w3 落在体重基准偏移 0x68..0x6E 内）。
     */
    private fun isAfuFrame(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            (bytes[0].toInt() and 0xFF) == 0xAC &&
            ((bytes[3].toInt() and 0xFF) in 0x68..0x6E)

    // -------------------------------------------------------------------------
    // 协议 1：薄荷健康 / 沃莱 (Yolanda / Boohee) / 深圳轻牛 / Fitdays / 合泰
    // -------------------------------------------------------------------------

    /**
     * 解析薄荷健康 (Boohee) / 沃莱 (Yolanda) / 深圳轻牛 / Fitdays / 合泰 常见体脂秤协议帧。
     *
     * 涵盖主流形态：
     * 1. 8 字节实时/锁定/阻抗流帧 (0xAC 0x02 / 0x03)：
     *    - 实时动态示数：AC 02 [W_HI] [W_LO] 00 00 CE [CHK] (体重 = raw / 100.0 kg, isStable = false)
     *    - 稳定锁定示数：AC 02 [W_HI] [W_LO] 00 00 CA [CHK] (体重 = raw / 100.0 kg, isStable = true)
     *    - 阻抗完成示数：AC 02 FD 01 [IMP_HI] [IMP_LO] CB [CHK] (阻抗 = raw Ω, weightKg = 0.0, isStable = true)
     *    - 部分变体使用 0xCC / 0xCD 状态位
     * 2. 20 字节复合帧 (0xA2 实时, 0xA3 稳定+阻抗, 或 0xAC 0x02/0x03 复合帧)：
     *    - 0xA2: 实时体重 (Byte 2~4: 24位无符号或 Byte 2~3: 16位无符号)
     *    - 0xA3: 稳定体重 + 阻抗
     *    - 0xAC 0x02 0xFF: 复合体脂包
     */
    fun parseYolandaIcomon(data: ByteArray): ParsedScaleData? {
        if (data.isEmpty()) return null

        val b0 = data[0].toInt() and 0xFF

        // ── 格式 1: 8 字节流式帧 (0xAC 开头) ──────────────────────────────────
        if (b0 == 0xAC && data.size >= 8) {
            val b1 = data[1].toInt() and 0xFF
            // 薄荷健康/沃莱 8 字节流通常以 0x02, 0x03, 0x05, 0x0A 为命令字
            if (b1 in listOf(0x02, 0x03, 0x05, 0x0A)) {
                val statusByte = data[6].toInt() and 0xFF

                // 1.1 阻抗专属数据包：AC 02 FD 01 [IMP_HI] [IMP_LO] CB [CHK]
                val b2 = data[2].toInt() and 0xFF
                val b3 = data[3].toInt() and 0xFF
                if (statusByte == 0xCB || (b2 == 0xFD && b3 == 0x01)) {
                    val rawImp = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
                    if (rawImp in 100..1500) {
                        return ParsedScaleData(
                            weightKg = 0.0, // 标记为阻抗上报包，客户端保留已锁定的体重
                            isStable = true,
                            impedanceOhm = rawImp.toDouble(),
                            protocolName = "Yolanda/Boohee (0xCB)"
                        )
                    } else {
                        // 秤端指示未测得有效阻抗（例如 0x0000 或 0xFFFF 开路，表示穿袜或未踩电极）
                        return ParsedScaleData(
                            weightKg = 0.0,
                            isStable = true,
                            impedanceOhm = null,
                            protocolName = "Yolanda/Boohee (0xCB No Imp)"
                        )
                    }
                }

                // 1.2 稳定锁定示数：AC 02 [W_HI] [W_LO] 00 00 CA [CHK]
                if (statusByte == 0xCA || statusByte == 0xCC) {
                    val rawWeight = (b2 shl 8) or b3
                    val weightKg = rawWeight / 100.0
                    if (weightKg in 0.0..350.0) {
                        val validStable = weightKg >= 3.0
                        return ParsedScaleData(
                            weightKg = weightKg,
                            isStable = validStable,
                            impedanceOhm = null,
                            protocolName = "Yolanda/Boohee (0xCA)"
                        )
                    }
                }

                // 1.3 实时动态变动示数：AC 02 [W_HI] [W_LO] 00 00 CE [CHK]
                if (statusByte == 0xCE || statusByte == 0xCD) {
                    val rawWeight = (b2 shl 8) or b3
                    val weightKg = rawWeight / 100.0
                    if (weightKg in 0.0..350.0) {
                        return ParsedScaleData(
                            weightKg = weightKg,
                            isStable = false,
                            impedanceOhm = null,
                            protocolName = "Yolanda/Boohee (0xCE)"
                        )
                    }
                }

                // 1.4 复合流式变体 (Byte 2~3 为体重, Byte 4~5 为保留/温度/状态, Byte 6 为稳定标志 0x01/0x02)
                if (statusByte in listOf(0x01, 0x02)) {
                    // 若 b3 在 0x68..0x6E 范围内，说明该帧为 AFU 协议报文（w3 基准偏移量 0x68），绝非 Yolanda，直接拒绝以防误判
                    if (b3 in 0x68..0x6E) return null

                    val rawWeight = (b2 shl 8) or b3
                    val weightKg = rawWeight / 100.0
                    if (weightKg in 0.0..350.0) {
                        // 注意：薄荷健康/沃莱 8 字节流式帧仅上报体重，Byte 4~5 常为环境温度、电池电压或 ADC 基准，绝非阻抗；
                        // 真实阻抗始终通过 1.1 专属的 0xCB / FD 01 报文送达。若在此处提取 Byte 4~5，会导致穿袜或未踩电极时误产生假阻抗！
                        return ParsedScaleData(
                            weightKg = weightKg,
                            isStable = (statusByte == 0x02) && weightKg >= 3.0,
                            impedanceOhm = null,
                            protocolName = "Yolanda/Boohee (Stream)"
                        )
                    }
                }
            }

            // 1.5 扩展复合包：AC 02 FF [W_HI] [W_LO] [IMP_HI] [IMP_LO] [STATUS] ...
            if (data.size >= 10 && (data[1].toInt() and 0xFF) in listOf(0x02, 0x03) && (data[2].toInt() and 0xFF) == 0xFF) {
                val rawWeight = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
                val rawImp = ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
                val status = data[7].toInt() and 0xFF
                val weightKg = rawWeight / 100.0
                val isStable = (status == 0x02) && weightKg >= 3.0
                val imp = if (isStable && weightKg >= 3.0 && rawImp in 100..1500) rawImp.toDouble() else null
                if (weightKg in 0.0..350.0) {
                    return ParsedScaleData(
                        weightKg = weightKg,
                        isStable = isStable,
                        impedanceOhm = imp,
                        protocolName = "Yolanda/Boohee (0xFF Composite)"
                    )
                }
            }
        }

        // ── 格式 2: 0xA2 / 0xA3 复合帧 (QNDoctor / 沃莱通用型) ──────────────────
        if ((b0 == 0xA2 || b0 == 0xA3) && data.size >= 7) {
            val isStable = (b0 == 0xA3)
            val w24 = ((data[2].toInt() and 0xFF) shl 16) or ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
            val weightKg = if (w24 / 1000.0 in 3.0..350.0) {
                w24 / 1000.0
            } else {
                val w16 = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                w16 / 100.0
            }

            var imp: Double? = null
            if (isStable && data.size >= 9 && weightKg >= 3.0) {
                val rawImp = ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
                if (rawImp in 100..1500) {
                    imp = rawImp.toDouble()
                }
            }

            if (weightKg in 0.0..350.0) {
                return ParsedScaleData(
                    weightKg = weightKg,
                    isStable = isStable && weightKg >= 3.0,
                    impedanceOhm = imp,
                    protocolName = if (isStable) "Yolanda (0xA3)" else "Yolanda (0xA2)"
                )
            }
        }

        // ── 格式 3: 0xCA 0x20 广播/透传复合帧 ──────────────────────────────────
        if (b0 == 0xCA && data.size >= 8 && (data[1].toInt() and 0xFF) == 0x20) {
            val flags = data[2].toInt() and 0xFF
            val isStable = (flags and 0x01 != 0) || (flags and 0x02 != 0)
            val rawWeight = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
            val weightKg = rawWeight / 100.0
            val rawImp = ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
            val imp = if (isStable && weightKg >= 3.0 && rawImp in 100..1500) rawImp.toDouble() else null
            if (weightKg in 0.0..350.0) {
                return ParsedScaleData(
                    weightKg = weightKg,
                    isStable = isStable && weightKg >= 3.0,
                    impedanceOhm = imp,
                    protocolName = "Yolanda (0xCA20)"
                )
            }
        }

        return null
    }

    // -------------------------------------------------------------------------
    // 协议 2：薄荷健康 / 沃莱 AFU 20 字节复合帧
    // -------------------------------------------------------------------------

    private fun parseAfu(data: ByteArray): ParsedScaleData? {
        val weightData = AFUPacketParser.parseWeight(data) ?: return null
        // 仅当示数已稳定锁定（weightData.isStable）且体重 >= 3.0kg 时，阻抗才具备生理意义且允许读取；
        // 动态测量中严禁读取阻抗；下秤发送 0.00kg 时正常上报体重，但阻抗必须置 null
        val impedance = if (weightData.isStable && weightData.weightKg >= 3.0) AFUPacketParser.parseImpedance(data) else null
        return ParsedScaleData(
            weightKg = weightData.weightKg,
            isStable = weightData.isStable && weightData.weightKg >= 3.0,
            impedanceOhm = impedance,
            protocolName = "AFU"
        )
    }

    // -------------------------------------------------------------------------
    // 协议 3：小米体脂秤 2 代
    // -------------------------------------------------------------------------

    /**
     * 解析小米体脂秤 2 代 (Mi Body Composition Scale, 0x181B) 广播 / 通知数据帧（13 字节）。
     *
     * 字节布局（与开源实现 ble_monitor `miscale.py` 的 Mi Scale V2 分支一致，已用实机抓包逐帧核对）：
     * - Byte 0     control_byte_1：单位/型号标识，`0x02` = kg，`0x03` = lbs
     * - Byte 1     control_byte_2：状态标志位
     *              - bit 1：阻抗已测出 (has impedance)
     *              - bit 5：读数已稳定锁定 (stabilized)
     *              - bit 6：单位为斤 / catty
     *              - bit 7：已下秤 / 读数已移除 (weight removed)
     * - Byte 2~8   年月日时分秒时间戳
     * - Byte 9~10  阻抗 (UINT16 小端序，单位 Ω；未测出时为 0x0000 或 0xFFFF)
     * - Byte 11~12 体重 (UINT16 小端序；kg 精度 1/200，斤与 lbs 精度 1/100)
     *
     * 历史缺陷（已修复）：早期实现把 Byte 0~1 当成一个小端序 16 位 flags 字，
     * 于是 `hasImpedance` 实际测试的是 Byte 0 的 bit7（恒为 0，阻抗永远解析不出来），
     * `isStable` 命中的是 Byte 0 的 bit1（恒为 1，把秤刚唤醒时重播的上一次旧读数也误判成稳定测量）。
     */
    private fun parseXiaomiBodyCompositionServiceData(data: ByteArray): ParsedScaleData? {
        if (data.size < 13) return null

        val controlByte1 = data[0].toInt() and 0xFF
        val controlByte2 = data[1].toInt() and 0xFF

        val hasImpedance = (controlByte2 and (1 shl 1)) != 0
        val isStabilized = (controlByte2 and (1 shl 5)) != 0
        val isCatty = (controlByte2 and (1 shl 6)) != 0
        val weightRemoved = (controlByte2 and (1 shl 7)) != 0
        val isLbs = controlByte1 == 3

        // 阻抗固定位于 Byte 9~10（小端序，单位 Ω）
        val rawImpedance = (data[9].toInt() and 0xFF) or ((data[10].toInt() and 0xFF) shl 8)
        val impedance = if (hasImpedance && rawImpedance in 100..1500) rawImpedance.toDouble() else null

        // 体重固定位于 Byte 11~12（小端序）
        val rawWeight = (data[11].toInt() and 0xFF) or ((data[12].toInt() and 0xFF) shl 8)

        val weightKg = when {
            isCatty -> (rawWeight / 100.0) * 0.5           // 1 斤 = 0.5 kg
            isLbs   -> (rawWeight / 100.0) * 0.45359237    // 1 lb = 0.45359237 kg
            else    -> rawWeight / 200.0                   // kg 精度 1/200 = 0.005kg
        }

        // 空秤与下秤残余读数：小米离秤阶段仍会持续广播约 0.1kg 的值，统一归零，
        // 让上层像处理 0.0kg 离秤包一样复位测量状态
        if (weightKg < 0.5) {
            return ParsedScaleData(
                weightKg = 0.0,
                isStable = false,
                impedanceOhm = null,
                protocolName = "Xiaomi Mi Scale 2"
            )
        }

        if (weightKg > 350.0) return null

        return ParsedScaleData(
            weightKg = weightKg,
            // 仅"读数已稳定 且 人仍站在秤上"才算有效锁定；
            // 置了 weight removed(bit7) 的包通常是秤唤醒时重播的上一次旧读数，绝不能当作新测量入库
            isStable = isStabilized && !weightRemoved && weightKg >= 3.0,
            // 阻抗仍照常上报：部分固件只在带 removed 标志的收尾包中给出稳定阻抗，丢掉会永远测不出体脂
            impedanceOhm = impedance,
            protocolName = "Xiaomi Mi Scale 2"
        )
    }

    /**
     * 小米体脂秤 2 代 13 字节 GATT 通知帧：内部携带年月日时间戳，严格校验时间合法性后再解析，
     * 杜绝任意 13 字节未知特征被误判为小米秤。
     */
    private fun parseXiaomiPayload(data: ByteArray): ParsedScaleData? {
        if (data.size != 13) return null
        val year = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        val month = data[4].toInt() and 0xFF
        val day = data[5].toInt() and 0xFF
        if ((year in 2015..2035 && month in 1..12 && day in 1..31) || (year == 0 && month == 0 && day == 0)) {
            return parseXiaomiBodyCompositionServiceData(data)
        }
        return null
    }
}
