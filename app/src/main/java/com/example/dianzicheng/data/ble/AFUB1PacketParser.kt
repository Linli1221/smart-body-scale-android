package com.example.dianzicheng.data.ble

import java.util.UUID

/**
 * 薄荷健康 B1 (AFU-BH-TZ-B1，蚂蚁阿福联名款) 蓝牙体脂秤协议编解码器。
 *
 * 该秤是本应用已支持的「薄荷健康 / 沃莱」家族中的新一代型号，与旧款 0xAC 协议完全不同：
 * 使用自定义服务 0xFC50 与 128 位特征 af0000xx-9f2d-4c8a-8d6f-6a7b45f90000，
 * 且**只广播身份信息、不广播体重**，必须建立 GATT 连接后由通知特征获取数据。
 *
 * 帧结构（双向均为固定 20 字节）：
 * ```
 *   [0]=0xAF [1]=0x45 [2]=子命令 [3..17]=帧体 [18]=线类型 [19]=校验
 * ```
 * 校验 = raw[2..18] 求和后取低 5 位（sum & 0x1F）。
 *
 * 第 18 字节（线类型）决定帧的语义：
 * - 0x51 实时体重（秤 → 手机），称重过程中持续推送
 * - 0x54 存储/历史记录（秤 → 手机），携带体重、阻抗与时间戳
 * - 0x52 单次阻抗结果（秤 → 手机），需先用 0x5B 档案为下一次称重「武装」阻抗测量
 * - 0x5F 控制帧（双向）：子命令 0x12=确认、0x14=加密报告分片、0x16=对时
 * - 0x62 同步请求（手机 → 秤）、0x61 用户档案（手机 → 秤）
 *
 * 体重存放在「字节序反转后的 32 位标志字」低 18 位中，单位为克；最高位（bit 31）表示示数已锁定。
 */
object AFUB1PacketParser {

    /** 固定帧长（收发均为 20 字节）。 */
    const val FRAME_SIZE = 20

    /** 秤对外广播并与数据通道共用的服务 UUID。 */
    val SERVICE_UUID: UUID = UUID.fromString("0000FC50-0000-1000-8000-00805F9B34FB")

    /** 命令写入特征所在服务（实际使用时优先从 GATT 树中动态解析）。 */
    val WRITE_SERVICE_UUID: UUID = UUID.fromString("af000000-9f2d-4c8a-8d6f-6a7b45f90000")

    /** 命令写入特征，握手/档案/确认均写在此特征上。 */
    val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("af000002-9f2d-4c8a-8d6f-6a7b45f90000")

    /** 主要数据上报特征（NOTIFY）。 */
    val NOTIFY_CHARACTERISTIC_UUID: UUID = UUID.fromString("af000003-9f2d-4c8a-8d6f-6a7b45f90000")

    /** 次要数据上报特征（INDICATE）。 */
    val INDICATE_CHARACTERISTIC_UUID: UUID = UUID.fromString("af000004-9f2d-4c8a-8d6f-6a7b45f90000")

    /** 线类型：存储/历史记录。 */
    const val WIRE_STORED_RECORD = 0x54

    /** 线类型：单次阻抗结果。 */
    const val WIRE_IMPEDANCE_RESULT = 0x52

    /** 线类型：实时体重。 */
    const val WIRE_LIVE_WEIGHT = 0x51

    /** 线类型：控制帧。 */
    const val WIRE_CONTROL = 0x5F

    /** 线类型：同步请求（仅手机 → 秤）。 */
    const val WIRE_SYNC_REQUEST = 0x62

    /** 线类型：用户档案（仅手机 → 秤）。 */
    const val WIRE_USER_PROFILE = 0x61

    /** 子命令：对时。 */
    const val CMD_TIME_SYNC = 0x16

    /** 子命令：同步请求。 */
    const val CMD_SYNC_REQUEST = 0x04

    /** 子命令：确认收帧。 */
    const val CMD_ACK = 0x12

    /** 子命令：加密体成分报告分片（收发复用）。 */
    const val CMD_BODY_COMP_CHUNK = 0x14

    /** 子命令：请求导出完整历史队列。 */
    const val CMD_HISTORY_DUMP = 0x8E

    /** 秤端使用的用户槽位（与官方 App 一致，固定 1 号槽）。 */
    const val SCALE_USER_SLOT = 1

    /** 体重存放在（字节序反转后的）标志字低 18 位中。 */
    private const val WEIGHT_G_MASK = 0x3FFFF

    /**
     * 用户档案用途。
     *
     * 注意：[INITIAL] 必须等实时称重开始后再下发，过早下发会让秤切入称重模式并阻断其历史队列；
     * [GET_WEIGH_AGAIN_2] (0x5B) 会为**下一次**称重「武装」阻抗测量。
     */
    enum class ProfileVariant(val code: Int, val openByte: Int) {
        /** 首次联系：开始实时称重后下发。 */
        INITIAL(0x3F, 0xB6),

        /** 重新测量：促使秤提交本次称重并推送最新记录。 */
        GET_WEIGH_AGAIN_1(0x4B, 0xB6),

        /** 重新测量 + 为下一次称重武装阻抗测量。 */
        GET_WEIGH_AGAIN_2(0x5B, 0xD9),

        /** 结束本次会话并让秤准备好下一次称重。 */
        FINAL(0x68, 0xD9)
    }

    /** 握手阶段的两次同步请求（顺序与官方抓包一致）。 */
    enum class SyncRequestVariant { FIRST, SECOND }

    /** 解析后的秤端帧。 */
    sealed class Frame {
        /** 该帧的线类型（raw[18]）。 */
        abstract val wireType: Int

        /** 回复确认帧时应回带的字节；0 表示该帧无需确认。 */
        abstract val ackReplyByte: Int

        /** 存储/历史记录（线类型 0x54）。 */
        data class StoredRecord(
            val historyType: Int,
            val sequence: Int,
            val timestampEpochSeconds: Long,
            val weightGrams: Int,
            val locked: Boolean,
            val resistanceOhms: Int
        ) : Frame() {
            override val wireType: Int = WIRE_STORED_RECORD
            override val ackReplyByte: Int = WIRE_STORED_RECORD
        }

        /** 实时体重（线类型 0x51）。 */
        data class LiveWeight(
            val weightGrams: Int,
            val mode: Int,
            val locked: Boolean
        ) : Frame() {
            override val wireType: Int = WIRE_LIVE_WEIGHT
            override val ackReplyByte: Int = WIRE_LIVE_WEIGHT
        }

        /** 单次阻抗结果（线类型 0x52）。 */
        data class ImpedanceResult(
            val valid: Boolean,
            val adcOhms: Int
        ) : Frame() {
            override val wireType: Int = WIRE_IMPEDANCE_RESULT
            override val ackReplyByte: Int = WIRE_IMPEDANCE_RESULT
        }

        /** 加密体成分报告分片（线类型 0x5F，子命令 0x14），载荷无需解析，但必须确认。 */
        data class BodyCompChunk(val chunkIndex: Int) : Frame() {
            override val wireType: Int = WIRE_CONTROL
            override val ackReplyByte: Int = CMD_BODY_COMP_CHUNK
        }

        /** 秤端对手机命令的确认（线类型 0x5F，子命令 0x12）。 */
        data class Acknowledgement(val replyCmd: Int, val replyOp: Int) : Frame() {
            override val wireType: Int = WIRE_CONTROL
            override val ackReplyByte: Int = 0
        }

        /** 其它控制帧（如秤端回显的对时帧）。 */
        data class Control(val cmd: Int) : Frame() {
            override val wireType: Int = WIRE_CONTROL
            override val ackReplyByte: Int = 0
        }
    }

    /**
     * 判断字节数组是否可能是 B1 帧（仅校验长度与 0xAF 0x45 帧头，不含校验和验证）。
     */
    fun isAfuB1Frame(raw: ByteArray): Boolean =
        raw.size == FRAME_SIZE &&
            (raw[0].toInt() and 0xFF) == 0xAF &&
            (raw[1].toInt() and 0xFF) == 0x45

    /**
     * 解析一帧数据。长度、帧头、校验和任一不符，或方向为「手机 → 秤」的帧，均返回 null。
     */
    fun parse(raw: ByteArray): Frame? {
        if (!isAfuB1Frame(raw)) return null
        if (!crcValid(raw)) return null

        val cmd = raw[2].toInt() and 0xFF
        return when (raw[18].toInt() and 0xFF) {
            WIRE_STORED_RECORD -> {
                val weightFlags = convertIntEndian(be32(raw, 7))
                Frame.StoredRecord(
                    historyType = cmd and 0x03,
                    sequence = (cmd ushr 2) and 0x3F,
                    timestampEpochSeconds = le32u(raw, 3),
                    weightGrams = weightFlags and WEIGHT_G_MASK,
                    locked = weightFlags.bit(31),
                    resistanceOhms = le16(raw, 13)
                )
            }

            WIRE_IMPEDANCE_RESULT -> Frame.ImpedanceResult(valid = cmd == 0x01, adcOhms = le16(raw, 4))

            WIRE_LIVE_WEIGHT -> {
                val weightFlags = convertIntEndian(be32(raw, 2))
                Frame.LiveWeight(
                    weightGrams = weightFlags and WEIGHT_G_MASK,
                    mode = raw[6].toInt() and 0xFF,
                    locked = weightFlags.bit(31)
                )
            }

            WIRE_CONTROL -> when (cmd) {
                CMD_BODY_COMP_CHUNK -> Frame.BodyCompChunk(chunkIndex = raw[3].toInt() and 0xFF)
                CMD_ACK -> Frame.Acknowledgement(
                    replyCmd = raw[3].toInt() and 0xFF,
                    replyOp = raw[4].toInt() and 0xFF
                )
                else -> Frame.Control(cmd)
            }

            // 0x62 同步请求 / 0x61 用户档案仅由手机发往秤，秤端不会发出
            else -> null
        }
    }

    // -------------------------------------------------------------------------
    // 命令构造（手机 → 秤）
    // -------------------------------------------------------------------------

    /** 对时帧：`af45 16 <time4> 20 00…00 5f <crc>`。 */
    fun buildTimeSync(nowEpochSeconds: Long): ByteArray =
        buildFrame(CMD_TIME_SYNC, WIRE_CONTROL, time4(nowEpochSeconds) + byteArrayOf(0x20) + ByteArray(10))

    /**
     * 同步请求：`af45 04 <body15> 62 <crc>`，握手阶段需连续发送两次。
     *
     * 帧体内的部分字节（0xb6/0xf8/0x11 等）来自官方 App 抓包，可能与会话相关。
     */
    fun buildSyncRequest(variant: SyncRequestVariant): ByteArray {
        val body = if (variant == SyncRequestVariant.FIRST) {
            byteArrayOf(
                0x00, 0x01, 0xAF.toByte(), 0xB6.toByte(), 0x17, 0x1D, 0x01,
                0x00, 0x02, 0xB4.toByte(), 0xC6.toByte(), 0x1B, 0x56, 0x01, 0x00
            )
        } else {
            byteArrayOf(
                0x01, 0x03, 0xA5.toByte(), 0xF8.toByte(), 0x11, 0x1D, 0x02,
                0x00, 0x04, 0xB4.toByte(), 0xC6.toByte(), 0x1B, 0x1D, 0x01, 0x00
            )
        }
        return buildFrame(CMD_SYNC_REQUEST, WIRE_SYNC_REQUEST, body)
    }

    /**
     * 用户档案：`af45 <variant> <time4> 00 <user> af <openByte> 17 <age> <sex> <target*100 LE> 03 00 61 <crc>`。
     *
     * @param sex 1 = 男，0 = 女（AFU 约定）
     * @param targetKg 目标体重（公斤），写入时按 ×100 取整
     */
    fun buildUserProfile(
        variant: ProfileVariant,
        nowEpochSeconds: Long,
        user: Int,
        age: Int,
        sex: Int,
        targetKg: Float
    ): ByteArray {
        val targetX100 = Math.round(targetKg * 100f) and 0xFFFF
        val body = time4(nowEpochSeconds) + byteArrayOf(
            0x00, (user and 0xFF).toByte(),
            0xAF.toByte(), variant.openByte.toByte(), 0x17,
            (age and 0xFF).toByte(), (sex and 0xFF).toByte(),
            (targetX100 and 0xFF).toByte(), ((targetX100 ushr 8) and 0xFF).toByte(),
            0x03, 0x00
        )
        return buildFrame(variant.code, WIRE_USER_PROFILE, body)
    }

    /**
     * 请求导出完整历史队列：`af45 8e <time4> 00 <user> af de 17 <age> <sex> <target*100 LE> 03 00 61 <crc>`。
     *
     * 不发送该请求时，秤只会反复推送同一条存储记录。
     */
    fun buildHistoryDumpRequest(
        nowEpochSeconds: Long,
        user: Int,
        age: Int,
        sex: Int,
        targetKg: Float
    ): ByteArray {
        val targetX100 = Math.round(targetKg * 100f) and 0xFFFF
        val body = time4(nowEpochSeconds) + byteArrayOf(
            0x00, (user and 0xFF).toByte(),
            0xAF.toByte(), 0xDE.toByte(), 0x17,
            (age and 0xFF).toByte(), (sex and 0xFF).toByte(),
            (targetX100 and 0xFF).toByte(), ((targetX100 ushr 8) and 0xFF).toByte(),
            0x03, 0x00
        )
        return buildFrame(CMD_HISTORY_DUMP, WIRE_USER_PROFILE, body)
    }

    /**
     * 确认帧：`af45 12 <replyWireType> 00 <correlation> 00…00 5f <crc>`。
     *
     * 秤端未被确认的帧会反复重投，因此每一帧数据都必须回复确认；
     * 存储记录还需回带其序号作为关联索引。
     */
    fun buildAck(replyWireType: Int, correlationIndex: Int): ByteArray =
        buildFrame(
            CMD_ACK,
            WIRE_CONTROL,
            byteArrayOf((replyWireType and 0xFF).toByte(), 0x00, (correlationIndex and 0xFF).toByte()) + ByteArray(12)
        )

    /** 请求秤端推送本次称重的（加密）报告分片：`af45 14 00…00 5f <crc>`。 */
    fun buildChunkRequest(): ByteArray =
        buildFrame(CMD_BODY_COMP_CHUNK, WIRE_CONTROL, ByteArray(15))

    // -------------------------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------------------------

    /** 32 位字节序反转（官方 App 的 ConvertIntEndian）。 */
    fun convertIntEndian(v: Int): Int =
        ((v and 0xFF) shl 24) or ((v and 0xFF00) shl 8) or
            ((v ushr 8) and 0xFF00) or ((v ushr 24) and 0xFF)

    /** 校验和：raw[2..18] 求和后取低 5 位。 */
    fun crc(raw: ByteArray): Int {
        var sum = 0
        for (i in 2 until 19) sum += raw[i].toInt() and 0xFF
        return sum and 0x1F
    }

    private fun crcValid(raw: ByteArray): Boolean = crc(raw) == (raw[19].toInt() and 0x1F)

    /** 组装一帧：AF 45 [子命令@2][帧体@3..17][线类型@18][校验@19]。 */
    private fun buildFrame(cmd: Int, wireType: Int, body: ByteArray): ByteArray {
        require(body.size == 15) { "AFU-B1 帧体必须为 15 字节，实际为 ${body.size}" }
        val frame = ByteArray(FRAME_SIZE)
        frame[0] = 0xAF.toByte()
        frame[1] = 0x45.toByte()
        frame[2] = (cmd and 0xFF).toByte()
        body.copyInto(frame, 3)
        frame[18] = (wireType and 0xFF).toByte()
        frame[19] = crc(frame).toByte()
        return frame
    }

    /** 时间戳槽位：ConvertIntEndian(now) 以大端字节写出。 */
    private fun time4(nowEpochSeconds: Long): ByteArray {
        val v = convertIntEndian((nowEpochSeconds and 0xFFFFFFFFL).toInt())
        return byteArrayOf(
            ((v ushr 24) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            (v and 0xFF).toByte()
        )
    }

    private fun le16(raw: ByteArray, off: Int): Int =
        (raw[off].toInt() and 0xFF) or ((raw[off + 1].toInt() and 0xFF) shl 8)

    private fun le32u(raw: ByteArray, off: Int): Long =
        ((raw[off].toLong() and 0xFF) or
            ((raw[off + 1].toLong() and 0xFF) shl 8) or
            ((raw[off + 2].toLong() and 0xFF) shl 16) or
            ((raw[off + 3].toLong() and 0xFF) shl 24)) and 0xFFFFFFFFL

    private fun be32(raw: ByteArray, start: Int): Int =
        ((raw[start].toInt() and 0xFF) shl 24) or ((raw[start + 1].toInt() and 0xFF) shl 16) or
            ((raw[start + 2].toInt() and 0xFF) shl 8) or (raw[start + 3].toInt() and 0xFF)

    private fun Int.bit(n: Int): Boolean = ((this ushr n) and 1) == 1
}
