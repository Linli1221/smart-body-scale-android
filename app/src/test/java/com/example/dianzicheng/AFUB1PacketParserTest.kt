package com.example.dianzicheng

import com.example.dianzicheng.data.ble.AFUB1PacketParser
import com.example.dianzicheng.data.ble.AFUB1PacketParser.Frame
import org.junit.Assert.*
import org.junit.Test

/**
 * 薄荷健康 B1 (AFU-BH-TZ-B1) 协议编解码单元测试。
 *
 * 帧样本全部来自真机抓包（scale-ble-capture-20261017-015555.jsonl，通知特征 af000003），
 * 逐帧验证协议解析、体重/阻抗/锁定标志解码与各命令帧的构造结果。
 */
class AFUB1PacketParserTest {

    private fun hex(s: String): ByteArray =
        s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    /** 真机抓包中去重后的全部互异帧（含实时体重、存储记录与控制帧）。 */
    private val capturedFrames = listOf(
        "AF 45 00 00 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 07",
        "AF 45 8A 02 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 13",
        "AF 45 A4 06 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 11",
        "AF 45 4E 0C 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 01",
        "AF 45 5C 12 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 15",
        "AF 45 38 18 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 17",
        "AF 45 04 29 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 14",
        "AF 45 9E 39 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 1E",
        "AF 45 2A 44 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 15",
        "AF 45 18 47 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 06",
        "AF 45 A6 40 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 0D",
        "AF 45 AA 37 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 08",
        "AF 45 3E 30 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 15",
        "AF 45 BA 2C 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 0D",
        "AF 45 18 2E 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 0D",
        "AF 45 12 2F 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 08",
        "AF 45 E6 2D 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 1A",
        "AF 45 44 2F 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 1A",
        "AF 45 D4 30 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 0B",
        "AF 45 64 32 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 1D",
        "AF 45 CE 31 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 06",
        "AF 45 06 31 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 1E",
        "AF 45 00 32 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 19",
        "AF 45 A2 30 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 19",
        "AF 45 70 30 08 80 02 80 01 88 38 4B 00 00 00 00 00 00 51 07",
        "AF 45 19 01 00 05 01 00 01 03 00 01 00 02 00 00 00 AA 5F 10",
        "AF 45 00 DB 64 D2 6A 70 30 08 80 00 01 00 00 00 00 00 54 18",
    )

    // -------------------------------------------------------------------------
    // 抓包帧整体校验
    // -------------------------------------------------------------------------

    @Test
    fun testAllCapturedFramesPassChecksumAndParse() {
        assertEquals(27, capturedFrames.size)
        capturedFrames.forEach { s ->
            val frame = hex(s)
            assertEquals("帧长必须为 20: $s", 20, frame.size)
            assertTrue("帧头必须为 AF 45: $s", AFUB1PacketParser.isAfuB1Frame(frame))
            // 校验和 = raw[2..18] 求和取低 5 位
            assertEquals(
                "校验和必须匹配: $s",
                frame[19].toInt() and 0x1F,
                AFUB1PacketParser.crc(frame)
            )
            assertNotNull("抓包帧必须能被解析: $s", AFUB1PacketParser.parse(frame))
        }
    }

    // -------------------------------------------------------------------------
    // 实时体重 (0x51)
    // -------------------------------------------------------------------------

    @Test
    fun testLiveWeightLockedFrame() {
        // 称重结束时唯一带锁定标志的实时体重帧：12.400 kg
        val frame = AFUB1PacketParser.parse(
            hex("AF 45 70 30 08 80 02 80 01 88 38 4B 00 00 00 00 00 00 51 07")
        )
        assertTrue(frame is Frame.LiveWeight)
        val live = frame as Frame.LiveWeight
        assertEquals(12400, live.weightGrams)
        assertEquals(12.4, live.weightGrams / 1000.0, 0.0001)
        assertEquals(0x02, live.mode)
        assertTrue("bit31 为 1 时必须判定为已锁定", live.locked)
    }

    @Test
    fun testLiveWeightRampFrames() {
        // 抓包中踩秤上升阶段的原始值（克），全部未锁定
        val expected = listOf(
            "AF 45 00 00 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 07" to 0,
            "AF 45 8A 02 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 13" to 650,
            "AF 45 A4 06 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 11" to 1700,
            "AF 45 4E 0C 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 01" to 3150,
            "AF 45 18 47 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 06" to 18200,
            "AF 45 BA 2C 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 0D" to 11450
        )
        expected.forEach { (s, grams) ->
            val frame = AFUB1PacketParser.parse(hex(s))
            assertTrue(frame is Frame.LiveWeight)
            val live = frame as Frame.LiveWeight
            assertEquals("$s 体重解码错误", grams, live.weightGrams)
            assertFalse("上升阶段不得出现锁定标志: $s", live.locked)
        }
    }

    // -------------------------------------------------------------------------
    // 存储记录 (0x54) 与阻抗 (0x52)
    // -------------------------------------------------------------------------

    @Test
    fun testStoredRecordDecode() {
        // 称重结束后秤端自动回推的本次记录：与锁定体重一致的 12.400 kg，时间戳 = 抓包时刻
        val frame = AFUB1PacketParser.parse(
            hex("AF 45 00 DB 64 D2 6A 70 30 08 80 00 01 00 00 00 00 00 54 18")
        )
        assertTrue(frame is Frame.StoredRecord)
        val record = frame as Frame.StoredRecord
        assertEquals(0, record.historyType)
        assertEquals(0, record.sequence)
        // 1792173275 为大端写入、按小端读取的 Unix 秒（与抓包 startedAt 相差约 2 秒）
        assertEquals(1792173275L, record.timestampEpochSeconds)
        assertEquals(12400, record.weightGrams)
        assertTrue(record.locked)
        assertEquals(0, record.resistanceOhms)
    }

    @Test
    fun testStoredRecordWithResistance() {
        // 赤脚称重后记录帧会在 raw[13..14] 回带阻抗（小端序）
        val raw = ByteArray(20)
        raw[0] = 0xAF.toByte(); raw[1] = 0x45
        raw[2] = 0x04                                   // historyType=0, sequence=1
        raw[3] = 0xDB.toByte(); raw[4] = 0x64; raw[5] = 0xD2.toByte(); raw[6] = 0x6A
        raw[7] = 0x70; raw[8] = 0x30; raw[9] = 0x08; raw[10] = 0x80.toByte()
        raw[13] = 0x08; raw[14] = 0x02                  // 0x0208 = 520 Ω
        raw[18] = AFUB1PacketParser.WIRE_STORED_RECORD.toByte()
        raw[19] = AFUB1PacketParser.crc(raw).toByte()

        val record = AFUB1PacketParser.parse(raw) as Frame.StoredRecord
        assertEquals(12400, record.weightGrams)
        assertTrue(record.locked)
        assertEquals(520, record.resistanceOhms)
        assertEquals(0, record.historyType)
        assertEquals(1, record.sequence)
        assertEquals(AFUB1PacketParser.WIRE_STORED_RECORD, record.ackReplyByte)
    }

    @Test
    fun testImpedanceResultDecode() {
        // 线类型 0x52：子命令 0x01 表示有效结果，阻抗为 raw[4..5] 小端序
        val raw = ByteArray(20)
        raw[0] = 0xAF.toByte(); raw[1] = 0x45
        raw[2] = 0x01
        raw[4] = 0x08; raw[5] = 0x02                   // 0x0208 = 520 Ω
        raw[18] = AFUB1PacketParser.WIRE_IMPEDANCE_RESULT.toByte()
        raw[19] = AFUB1PacketParser.crc(raw).toByte()

        val frame = AFUB1PacketParser.parse(raw)
        assertTrue(frame is Frame.ImpedanceResult)
        val result = frame as Frame.ImpedanceResult
        assertTrue(result.valid)
        assertEquals(520, result.adcOhms)
        assertEquals(AFUB1PacketParser.WIRE_IMPEDANCE_RESULT, result.ackReplyByte)
    }

    // -------------------------------------------------------------------------
    // 控制帧
    // -------------------------------------------------------------------------

    @Test
    fun testControlFrameDecode() {
        // 抓包中唯一的秤端控制帧（子命令 0x19）
        val frame = AFUB1PacketParser.parse(
            hex("AF 45 19 01 00 05 01 00 01 03 00 01 00 02 00 00 00 AA 5F 10")
        )
        assertTrue(frame is Frame.Control)
        assertEquals(0x19, (frame as Frame.Control).cmd)
        assertEquals(0, frame.ackReplyByte)
    }

    @Test
    fun testAckFrameIsNotAcknowledgedAgain() {
        // 秤端对手机命令的确认帧无需再回确认，否则会形成确认风暴
        val raw = ByteArray(20)
        raw[0] = 0xAF.toByte(); raw[1] = 0x45
        raw[2] = AFUB1PacketParser.CMD_ACK.toByte()
        raw[3] = AFUB1PacketParser.WIRE_CONTROL.toByte()
        raw[4] = 0x12
        raw[18] = AFUB1PacketParser.WIRE_CONTROL.toByte()
        raw[19] = AFUB1PacketParser.crc(raw).toByte()

        val frame = AFUB1PacketParser.parse(raw)
        assertTrue(frame is Frame.Acknowledgement)
        assertEquals(0, frame!!.ackReplyByte)
    }

    @Test
    fun testBodyCompChunkIsAcknowledgedWithChunkCommand() {
        // 加密报告分片必须回带 0x14 作为确认的 reply 字节，否则秤端会反复重投
        val raw = ByteArray(20)
        raw[0] = 0xAF.toByte(); raw[1] = 0x45
        raw[2] = AFUB1PacketParser.CMD_BODY_COMP_CHUNK.toByte()
        raw[3] = 0x02
        raw[18] = AFUB1PacketParser.WIRE_CONTROL.toByte()
        raw[19] = AFUB1PacketParser.crc(raw).toByte()

        val frame = AFUB1PacketParser.parse(raw)
        assertTrue(frame is Frame.BodyCompChunk)
        val chunk = frame as Frame.BodyCompChunk
        assertEquals(2, chunk.chunkIndex)
        assertEquals(AFUB1PacketParser.CMD_BODY_COMP_CHUNK, chunk.ackReplyByte)
    }

    // -------------------------------------------------------------------------
    // 异常帧拒绝
    // -------------------------------------------------------------------------

    @Test
    fun testRejectWrongHeaderOrLength() {
        assertFalse(AFUB1PacketParser.isAfuB1Frame(ByteArray(19)))
        assertNull(AFUB1PacketParser.parse(ByteArray(0)))
        // 旧款 0xAC 协议帧绝不能落入 B1 解析器
        val legacyAfu = hex("AC 29 00 69 40 82 02 00 05 40 00 00 00 00 00 00 00 00 00 00")
        assertFalse(AFUB1PacketParser.isAfuB1Frame(legacyAfu))
        assertNull(AFUB1PacketParser.parse(legacyAfu))
        // 薄荷健康 8 字节流式帧同样不应被 B1 解析器接受
        assertNull(AFUB1PacketParser.parse(hex("AC 02 1A C2 00 00 CA 4A")))
    }

    @Test
    fun testRejectCorruptedChecksumAndPayload() {
        val good = hex("AF 45 70 30 08 80 02 80 01 88 38 4B 00 00 00 00 00 00 51 07")
        assertNotNull(AFUB1PacketParser.parse(good))

        val badCrc = good.copyOf()
        badCrc[19] = (badCrc[19] + 1).toByte()
        assertNull("校验和错误必须拒绝", AFUB1PacketParser.parse(badCrc))

        val badBody = good.copyOf()
        badBody[10] = (badBody[10] + 1).toByte()
        assertNull("帧体被篡改后校验和必然不符，必须拒绝", AFUB1PacketParser.parse(badBody))
    }

    // -------------------------------------------------------------------------
    // 命令帧构造
    // -------------------------------------------------------------------------

    private fun assertValidCommandFrame(frame: ByteArray, cmd: Int, wireType: Int) {
        assertEquals(20, frame.size)
        assertEquals(0xAF, frame[0].toInt() and 0xFF)
        assertEquals(0x45, frame[1].toInt() and 0xFF)
        assertEquals(cmd, frame[2].toInt() and 0xFF)
        assertEquals(wireType, frame[18].toInt() and 0xFF)
        assertEquals(
            "构造出的命令帧校验和必须自洽",
            frame[19].toInt() and 0x1F,
            AFUB1PacketParser.crc(frame)
        )
    }

    @Test
    fun testBuildTimeSyncMatchesCapturedTimestampEncoding() {
        val frame = AFUB1PacketParser.buildTimeSync(1792173275L)
        assertValidCommandFrame(frame, AFUB1PacketParser.CMD_TIME_SYNC, AFUB1PacketParser.WIRE_CONTROL)
        // ConvertIntEndian(now) 以大端写出：1792173275 = 0x6AD264DB -> DB 64 D2 6A
        assertEquals(0xDB, frame[3].toInt() and 0xFF)
        assertEquals(0x64, frame[4].toInt() and 0xFF)
        assertEquals(0xD2, frame[5].toInt() and 0xFF)
        assertEquals(0x6A, frame[6].toInt() and 0xFF)
        assertEquals(0x20, frame[7].toInt() and 0xFF)
        // 抓包中同一条记录的时间戳字段与对时帧的时间槽位编码完全一致
        val record = AFUB1PacketParser.parse(
            hex("AF 45 00 DB 64 D2 6A 70 30 08 80 00 01 00 00 00 00 00 54 18")
        ) as Frame.StoredRecord
        assertEquals(1792173275L, record.timestampEpochSeconds)
    }

    @Test
    fun testBuildSyncRequests() {
        val first = AFUB1PacketParser.buildSyncRequest(AFUB1PacketParser.SyncRequestVariant.FIRST)
        val second = AFUB1PacketParser.buildSyncRequest(AFUB1PacketParser.SyncRequestVariant.SECOND)
        assertValidCommandFrame(first, AFUB1PacketParser.CMD_SYNC_REQUEST, AFUB1PacketParser.WIRE_SYNC_REQUEST)
        assertValidCommandFrame(second, AFUB1PacketParser.CMD_SYNC_REQUEST, AFUB1PacketParser.WIRE_SYNC_REQUEST)
        assertFalse("两次同步请求必须内容不同", first.contentEquals(second))
        // 手机 → 秤的帧绝不能被秤端解析器当作上行数据
        assertNull(AFUB1PacketParser.parse(first))
    }

    @Test
    fun testBuildUserProfiles() {
        AFUB1PacketParser.ProfileVariant.entries.forEach { variant ->
            val frame = AFUB1PacketParser.buildUserProfile(
                variant = variant,
                nowEpochSeconds = 1792173275L,
                user = AFUB1PacketParser.SCALE_USER_SLOT,
                age = 32,
                sex = 1,
                targetKg = 65.5f
            )
            assertValidCommandFrame(frame, variant.code, AFUB1PacketParser.WIRE_USER_PROFILE)
            // 档案体：time4(3..6) 00 user af openByte 17 age sex targetLo targetHi 03 00
            assertEquals(1, frame[8].toInt() and 0xFF)
            assertEquals(0xAF, frame[9].toInt() and 0xFF)
            assertEquals(variant.openByte, frame[10].toInt() and 0xFF)
            assertEquals(0x17, frame[11].toInt() and 0xFF)
            assertEquals(32, frame[12].toInt() and 0xFF)
            assertEquals(1, frame[13].toInt() and 0xFF)
            // 65.50 kg -> 6550 = 0x1996（小端序 96 19）
            assertEquals(0x96, frame[14].toInt() and 0xFF)
            assertEquals(0x19, frame[15].toInt() and 0xFF)
            assertEquals(0x03, frame[16].toInt() and 0xFF)
            assertEquals(0x00, frame[17].toInt() and 0xFF)
            assertNull("手机 → 秤的档案帧不应被当作上行数据", AFUB1PacketParser.parse(frame))
        }
    }

    @Test
    fun testBuildAckAndChunkRequest() {
        val ack = AFUB1PacketParser.buildAck(AFUB1PacketParser.WIRE_STORED_RECORD, 7)
        assertValidCommandFrame(ack, AFUB1PacketParser.CMD_ACK, AFUB1PacketParser.WIRE_CONTROL)
        assertEquals(AFUB1PacketParser.WIRE_STORED_RECORD, ack[3].toInt() and 0xFF)
        assertEquals(0x00, ack[4].toInt() and 0xFF)
        assertEquals(7, ack[5].toInt() and 0xFF)

        assertValidCommandFrame(
            AFUB1PacketParser.buildChunkRequest(),
            AFUB1PacketParser.CMD_BODY_COMP_CHUNK,
            AFUB1PacketParser.WIRE_CONTROL
        )
        assertValidCommandFrame(
            AFUB1PacketParser.buildHistoryDumpRequest(1792173275L, 1, 32, 1, 65.5f),
            AFUB1PacketParser.CMD_HISTORY_DUMP,
            AFUB1PacketParser.WIRE_USER_PROFILE
        )
    }
}
