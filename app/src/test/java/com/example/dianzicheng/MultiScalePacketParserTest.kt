package com.example.dianzicheng

import com.example.dianzicheng.data.ble.MultiScalePacketParser
import com.example.dianzicheng.domain.ScaleModel
import org.junit.Assert.*
import org.junit.Test

/**
 * 体脂秤协议解析引擎单元测试。
 *
 * 覆盖范围仅限本项目支持的两家体脂秤：
 * 1. 薄荷健康 / 沃莱 (Yolanda / Boohee)：AFU 0xAC 20 字节复合帧 + 8 字节流 / 0xA2 / 0xA3 / 0xCA20 帧；
 * 2. 小米体脂秤 2 代：0x181B Body Composition 13 字节广播帧。
 */
class MultiScalePacketParserTest {

    // -------------------------------------------------------------------------
    // 薄荷健康 / 沃莱：AFU 0xAC 20 字节复合帧
    // -------------------------------------------------------------------------

    @Test
    fun testAfuProtocolNotification() {
        // 经典的薄荷健康/沃莱 AFU 协议数据包（与 README 一致）
        val rawData = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x69.toByte(), 0x40.toByte(),
            0x82.toByte(), 0x02.toByte(), 0x00.toByte(), 0x05.toByte(), 0x40.toByte(),
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        )

        val result = MultiScalePacketParser.parseNotification(rawData, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertEquals("AFU", result!!.protocolName)
        assertEquals(82.05, result.weightKg, 0.001)
        assertTrue(result.isStable)
        assertNotNull(result.impedanceOhm)
        assertEquals(1344.0, result.impedanceOhm!!, 0.1)
    }

    @Test
    fun testAfuStepOffZeroWeightPacket() {
        // AFU 下秤 0.00kg 离秤数据包：必须能被成功解析为 0.0kg，且阻抗为 null，稳定标志为 false
        val zeroData = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x68.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x05.toByte(), 0x40.toByte(), 0x00.toByte()
        )

        val result = MultiScalePacketParser.parseNotification(zeroData, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull("下秤 0.00kg 数据包不应被丢弃", result)
        assertEquals("AFU", result!!.protocolName)
        assertEquals(0.0, result.weightKg, 0.001)
        assertFalse(result.isStable)
        assertNull("空秤/离秤时严禁产生阻抗", result.impedanceOhm)
    }

    @Test
    fun testAfuUnder3KgSuppressesImpedanceAndStability() {
        // AFU 踩秤爬升阶段 (例如 1.5kg < 3.0kg)：允许上报实时动态体重，但严禁产生阻抗与稳定锁定
        val lightData = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x68.toByte(), 0x05.toByte(),
            0xDC.toByte(), 0x02.toByte(), 0x05.toByte(), 0x40.toByte(), 0x00.toByte()
        )

        val result = MultiScalePacketParser.parseNotification(lightData, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertEquals(1.5, result!!.weightKg, 0.01)
        assertFalse("低于 3.0kg 绝对不允许稳定锁定", result.isStable)
        assertNull("低于 3.0kg 绝对不允许读取阻抗", result.impedanceOhm)
    }

    @Test
    fun testAfu7ByteDynamicPacket() {
        // AFU 7 字节实时动态测量数据包：AC 07 00 68 12 34 00 (4.66kg, 动态测量未锁定)
        val dynamic7 = byteArrayOf(
            0xAC.toByte(), 0x07, 0x00, 0x68, 0x12, 0x34, 0x00
        )
        val result = MultiScalePacketParser.parseNotification(dynamic7, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull("AFU 7 字节实时动态包必须成功解析", result)
        assertEquals("AFU", result!!.protocolName)
        assertEquals(4.66, result.weightKg, 0.001)
        assertFalse("状态为 0x00 时必须判定为未锁定（实时动态示数）", result.isStable)
        assertNull(result.impedanceOhm)
    }

    @Test
    fun testAfu8ByteDynamicPacket() {
        // AFU 8 字节实时动态示数包：AC 08 00 69 40 82 01 00 (82.05kg, status=0x01 测量中)
        val dynamic8 = byteArrayOf(
            0xAC.toByte(), 0x08, 0x00, 0x69.toByte(), 0x40.toByte(), 0x82.toByte(), 0x01, 0x00
        )
        val result = MultiScalePacketParser.parseNotification(dynamic8, null, ScaleModel.AUTO)
        assertNotNull("AFU 8 字节动态包在 AUTO 模式下必须成功解析", result)
        assertEquals("AFU", result!!.protocolName)
        assertEquals(82.05, result.weightKg, 0.001)
        assertFalse("status=0x01 时必须判定为未锁定（实时变动示数）", result.isStable)
        assertNull(result.impedanceOhm)
    }

    @Test
    fun testAfu8ByteLockedPacketWithoutImpedance() {
        // AFU 8 字节锁定示数包：AC 08 00 69 40 82 02 00 (82.05kg, 锁定 status=0x02, 数据不足 9 字节无阻抗)
        val locked8 = byteArrayOf(
            0xAC.toByte(), 0x08, 0x00, 0x69.toByte(), 0x40.toByte(), 0x82.toByte(), 0x02, 0x00
        )
        val result = MultiScalePacketParser.parseNotification(locked8, null, ScaleModel.BOOHEE_YOLANDA)
        assertNotNull("AFU 8 字节锁定包必须成功解析", result)
        assertEquals("AFU", result!!.protocolName)
        assertEquals(82.05, result.weightKg, 0.001)
        assertTrue("status=0x02 且体重>=3kg 时必须判定为稳定锁定", result.isStable)
        assertNull("8 字节数据包不包含阻抗字节，阻抗应为 null", result.impedanceOhm)
    }

    @Test
    fun testAfuWithPrefixBytes() {
        // 验证帧头 0xAC 前有前缀杂质字节时依然能准确定位并解析
        val prefixed = byteArrayOf(
            0xFF.toByte(), 0xAC.toByte(), 0x07, 0x00, 0x69.toByte(), 0x40.toByte(), 0x82.toByte(), 0x02
        )
        val result = MultiScalePacketParser.parseNotification(prefixed, null, ScaleModel.AUTO)
        assertNotNull(result)
        assertEquals("AFU", result!!.protocolName)
        assertEquals(82.05, result.weightKg, 0.001)
        assertTrue(result.isStable)
    }

    @Test
    fun testAfuNoElectrodeContactReturnsNullImpedance() {
        // AFU 协议：用户穿袜或未踩电极时，秤端在 offset+7 和 offset+8 发送 0x00 0x00，尾部为校验和
        // 必须解析出有效稳定体重，但阻抗绝对必须为 null，严禁将尾部校验和误当作阻抗！
        val afuSocksOnData = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x69.toByte(), 0x40.toByte(),
            0x82.toByte(), 0x02.toByte(), 0x00.toByte(), 0x00.toByte(), 0x96.toByte()
        )
        val result = MultiScalePacketParser.parseNotification(afuSocksOnData, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertEquals(82.05, result!!.weightKg, 0.001)
        assertTrue(result.isStable)
        assertNull("穿袜或未踩电极（0x0000）时阻抗必须为 null，严禁将尾部校验和 0x96 误作为 150Ω 阻抗", result.impedanceOhm)

        // 开路情况（0xFF 0xFF）
        val afuOpenCircuit = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x69.toByte(), 0x40.toByte(),
            0x82.toByte(), 0x02.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x96.toByte()
        )
        val resultOpen = MultiScalePacketParser.parseNotification(afuOpenCircuit, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(resultOpen)
        assertNull("开路 0xFFFF 时阻抗必须为 null", resultOpen!!.impedanceOhm)
    }

    @Test
    fun testAfuDynamicWeightSuppressesImpedance() {
        // AFU 动态测量中 (status = 0x00)，即使数据中有非零字节，也严禁读取阻抗
        val dynamicWithNoise = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x69.toByte(), 0x40.toByte(),
            0x82.toByte(), 0x00.toByte(), 0x02.toByte(), 0x08.toByte(), 0x00.toByte()
        )
        val result = MultiScalePacketParser.parseNotification(dynamicWithNoise, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertFalse(result!!.isStable)
        assertNull("未稳定锁定时绝对不允许读取阻抗", result.impedanceOhm)
    }

    @Test
    fun testAfuImpedanceVariesWithByte9AndDoesNotGetStuckAt514() {
        // 关键回归测试：AFU 阻抗真正提取 Byte 8(高位)与 Byte 9(低位)，
        // 绝不可将 Byte 7(状态位 0x02)与 Byte 8(高位 0x02)误拼为恒定 514Ω！
        val afuMeas1 = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x69.toByte(), 0x40.toByte(),
            0x82.toByte(), 0x02.toByte(), 0x02.toByte(), 0x02.toByte(), 0x34.toByte()
        )
        val result1 = MultiScalePacketParser.parseNotification(afuMeas1, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result1)
        assertEquals(82.05, result1!!.weightKg, 0.001)
        assertTrue(result1.isStable)
        assertEquals(564.0, result1.impedanceOhm!!, 0.1)
        assertNotEquals(514.0, result1.impedanceOhm!!, 0.01)

        val afuMeas2 = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x69.toByte(), 0x40.toByte(),
            0x82.toByte(), 0x02.toByte(), 0x02.toByte(), 0x02.toByte(), 0x6A.toByte()
        )
        val result2 = MultiScalePacketParser.parseNotification(afuMeas2, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result2)
        assertEquals(618.0, result2!!.impedanceOhm!!, 0.1)

        // 状态位 0x04 (体脂完成锁定)，必须保持 isStable=true
        val afuStatus04 = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x69.toByte(), 0x40.toByte(),
            0x82.toByte(), 0x04.toByte(), 0x02.toByte(), 0x02.toByte(), 0x48.toByte()
        )
        val result3 = MultiScalePacketParser.parseNotification(afuStatus04, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result3)
        assertTrue("状态位 0x04 必须判定为稳定锁定状态", result3!!.isStable)
        assertEquals(584.0, result3.impedanceOhm!!, 0.1)
    }

    // -------------------------------------------------------------------------
    // 薄荷健康 / 沃莱：8 字节流式帧与 0xA2 / 0xA3 复合帧
    // -------------------------------------------------------------------------

    @Test
    fun testYolandaBooheeLiveSettlingWeight() {
        // 沃莱/薄荷健康 8 字节动态示数：AC 02 1A C2 00 00 CE 4E (68.50kg)
        val data = byteArrayOf(
            0xAC.toByte(), 0x02,
            0x1A, 0xC2.toByte(),
            0x00, 0x00,
            0xCE.toByte(), 0x4E
        )

        val result = MultiScalePacketParser.parseNotification(data, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertEquals("Yolanda/Boohee (0xCE)", result!!.protocolName)
        assertEquals(68.50, result.weightKg, 0.01)
        assertFalse("动态示数状态下严禁标记为稳定锁定", result.isStable)
        assertNull(result.impedanceOhm)
    }

    @Test
    fun testYolandaBooheeStableLockedWeight() {
        // 沃莱/薄荷健康 8 字节稳定锁定：AC 02 1A C2 00 00 CA 4A (68.50kg)
        val data = byteArrayOf(
            0xAC.toByte(), 0x02,
            0x1A, 0xC2.toByte(),
            0x00, 0x00,
            0xCA.toByte(), 0x4A
        )

        val result = MultiScalePacketParser.parseNotification(data, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertEquals("Yolanda/Boohee (0xCA)", result!!.protocolName)
        assertEquals(68.50, result.weightKg, 0.01)
        assertTrue("0xCA 标志必须判定为稳定锁定", result.isStable)
        assertNull(result.impedanceOhm)
    }

    @Test
    fun testYolandaBooheeImpedancePacket() {
        // 沃莱/薄荷健康 8 字节阻抗专属报文：AC 02 FD 01 02 08 CB D5 (520Ω)
        val data = byteArrayOf(
            0xAC.toByte(), 0x02,
            0xFD.toByte(), 0x01,
            0x02, 0x08,
            0xCB.toByte(), 0xD5.toByte()
        )

        val result = MultiScalePacketParser.parseNotification(data, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertEquals("Yolanda/Boohee (0xCB)", result!!.protocolName)
        assertEquals(0.0, result.weightKg, 0.001)
        assertTrue(result.isStable)
        assertNotNull(result.impedanceOhm)
        assertEquals(520.0, result.impedanceOhm!!, 0.1)
    }

    @Test
    fun testYolanda0CbNoElectrodeContactReturnsNullImpedance() {
        // 沃莱 0xCB 阻抗报文：秤端上报 0 表示未测出阻抗
        val cbZero = byteArrayOf(
            0xAC.toByte(), 0x02,
            0xFD.toByte(), 0x01,
            0x00, 0x00,
            0xCB.toByte(), 0xD5.toByte()
        )
        val result = MultiScalePacketParser.parseNotification(cbZero, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertNull("阻抗上报 0 时阻抗应为 null", result!!.impedanceOhm)
    }

    @Test
    fun testYolandaStreamStatus01IsDynamicNotStable() {
        // 沃莱流式包：statusByte 为 0x01 代表测量中（动态爬升），严禁判定为 isStable=true
        val streamDynamic = byteArrayOf(
            0xAC.toByte(), 0x02, 0x02, 0x00, 0x00, 0x00, 0x01, 0x00
        )
        val result = MultiScalePacketParser.parseYolandaIcomon(streamDynamic)
        assertNotNull(result)
        assertEquals(5.12, result!!.weightKg, 0.01)
        assertFalse("statusByte 为 0x01 时必须判定为实时动态变动，严禁过早锁定", result.isStable)
    }

    @Test
    fun testYolandaStreamStatus02IsStable() {
        // 沃莱流式包：statusByte 为 0x02 代表测量完成稳定锁定
        val streamLocked = byteArrayOf(
            0xAC.toByte(), 0x02, 0x02, 0x00, 0x00, 0x00, 0x02, 0x00
        )
        val result = MultiScalePacketParser.parseYolandaIcomon(streamLocked)
        assertNotNull(result)
        assertEquals(5.12, result!!.weightKg, 0.01)
        assertTrue("statusByte 为 0x02 时必须判定为稳定锁定", result.isStable)
    }

    @Test
    fun testYolandaStreamDoesNotExtractImpedanceFromWeightFrame() {
        // 沃莱/薄荷 8 字节流式帧：稳定锁定 (status = 0x02)，但 byte 4~5 为室内温度 24.0°C (0x00F0 = 240)
        // 严禁将温度误作为 240Ω 阻抗！阻抗只能来自专属的 0xCB 报文
        val weightFrameWithTemp = byteArrayOf(
            0xAC.toByte(), 0x02,
            0x1A, 0xC2.toByte(),
            0x00, 0xF0.toByte(),
            0x02, 0x4A
        )
        val result = MultiScalePacketParser.parseYolandaIcomon(weightFrameWithTemp)
        assertNotNull(result)
        assertEquals(68.50, result!!.weightKg, 0.01)
        assertTrue(result.isStable)
        assertNull("8 字节体重流式帧中绝不能提取阻抗，严禁将室内温度误解析为阻抗", result.impedanceOhm)
    }

    @Test
    fun testYolandaCompositeA3Packet() {
        // 沃莱 0xA3 复合帧 (稳定体重约 68.54kg + 阻抗 520Ω)
        val data = byteArrayOf(
            0xA3.toByte(), 0x19,
            0x01, 0x0B, 0xB8.toByte(),
            0x02, 0x08,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        val result = MultiScalePacketParser.parseNotification(data, "0000FFA1-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertEquals("Yolanda (0xA3)", result!!.protocolName)
        assertEquals(68.54, result.weightKg, 0.01)
        assertTrue(result.isStable)
        assertEquals(520.0, result.impedanceOhm!!, 0.1)
    }

    @Test
    fun testAfuPacketNotInterpretedAsYolandaStream() {
        // 验证 AFU 报文绝不会被误识别为 Yolanda Stream 流式协议（杜绝产生伪造数值）
        val afuPacket = byteArrayOf(
            0xAC.toByte(), 0x02, 0x00, 0x69.toByte(), 0x40.toByte(), 0x82.toByte(), 0x02, 0x00
        )
        assertNull("Yolanda 解析器必须拒绝 AFU 报文 (b3 in 0x68..0x6E)", MultiScalePacketParser.parseYolandaIcomon(afuPacket))
    }

    @Test
    fun testAfuParserRejectsYolandaPacketsToPreventBogusNumbers() {
        // 核心防串扰校验：验证 AFUPacketParser 绝不会把 Yolanda 8 字节帧误解析为数千公斤
        val yolandaData = byteArrayOf(
            0xAC.toByte(), 0x02,
            0x1A, 0xC2.toByte(),
            0x00, 0x00,
            0xCA.toByte(), 0x4A
        )
        assertNull(
            "AFUPacketParser 必须拒绝 Yolanda 8 字节帧，严禁产出数千公斤未知数值",
            com.example.dianzicheng.data.ble.AFUPacketParser.parseWeight(yolandaData)
        )

        val yolandaImp = byteArrayOf(
            0xAC.toByte(), 0x02,
            0xFD.toByte(), 0x01,
            0x02, 0x08,
            0xCB.toByte(), 0xD5.toByte()
        )
        assertNull(
            "AFUPacketParser 必须拒绝 Yolanda 阻抗帧",
            com.example.dianzicheng.data.ble.AFUPacketParser.parseWeight(yolandaImp)
        )
    }

    // -------------------------------------------------------------------------
    // 小米体脂秤 2 代
    // -------------------------------------------------------------------------

    // ── 以下帧全部取自实机蓝牙抓包 scale-ble-capture-20261017-004517.jsonl ──
    //    设备：小米体脂秤 2 代 MIBFS [5C:CA:D3:54:BA:2F]，0x181B 广播 Service Data（13 字节）
    //    02A6… 秤唤醒时重播的上一次旧读数（weight removed=1）
    //    0204… 踩秤过程中实时爬升的示数（stabilized 未置位）
    //    0224… 读数已稳定，但阻抗尚未测出（0x0000 / 0xFFFF）
    //    0226… 读数稳定且阻抗有效（661Ω）
    //    0204… / 0284… 下秤阶段残留的 0.1kg 读数

    @Test
    fun testXiaomiScale2StabilizedWithImpedance() {
        // 0226 EA07 0A10 102C05 9502 E830 → 62.600kg + 661Ω，stabilized(bit5)=1
        val result = MultiScalePacketParser.parseNotification("0226EA070A10102C059502E830".hexToBytes(), null)
        assertNotNull(result)
        assertEquals("Xiaomi Mi Scale 2", result!!.protocolName)
        assertEquals(62.600, result.weightKg, 0.001)
        assertTrue("stabilized(bit5) 置位且未下秤时必须判定为稳定锁定", result.isStable)
        assertEquals("has_impedance(bit1) 置位时必须解析出真实阻抗", 661.0, result.impedanceOhm!!, 0.001)
    }

    @Test
    fun testXiaomiScale2LiveWeightRampIsNotStable() {
        // 0204 EA07 0A10 102C01 0000 1E2D → 踩秤爬升中的 57.75kg
        val result = MultiScalePacketParser.parseNotification("0204EA070A10102C0100001E2D".hexToBytes(), null)
        assertNotNull(result)
        assertEquals(57.75, result.weightKg, 0.001)
        assertFalse("stabilized(bit5) 未置位时严禁锁定", result.isStable)
        assertNull("has_impedance(bit1) 未置位时不得产生阻抗", result.impedanceOhm)
    }

    @Test
    fun testXiaomiScale2StabilizedBeforeImpedanceHasNoImpedance() {
        // 0224 EA07 0A10 102C05 FEFF E830 → 体重已稳定 62.600kg，但阻抗仍是 0xFFFF 无效占位
        val invalidImp = MultiScalePacketParser.parseNotification("0224EA070A10102C05FEFFE830".hexToBytes(), null)
        assertNotNull(invalidImp)
        assertEquals(62.600, invalidImp!!.weightKg, 0.001)
        assertTrue(invalidImp.isStable)
        assertNull("has_impedance(bit1) 未置位时严禁读取 0xFFFF 占位值", invalidImp.impedanceOhm)

        // 0224 …0000… → 阻抗 0x0000 同样无效（穿袜 / 未踩电极）
        val zeroImp = MultiScalePacketParser.parseNotification("0224EA070A10102C050000E830".hexToBytes(), null)
        assertNotNull(zeroImp)
        assertNull(zeroImp!!.impedanceOhm)
    }

    @Test
    fun testXiaomiScale2WakeUpStalePreviousReadingIsNotStable() {
        // 02A6 EA07 0A10 100B21 8802 5230 → 秤刚唤醒时重播的上一次旧读数：
        // weight removed(bit7)=1，虽带阻抗 648Ω，但绝非本次测量，严禁锁定入库
        val result = MultiScalePacketParser.parseNotification("02A6EA070A10100B2188025230".hexToBytes(), null)
        assertNotNull(result)
        assertEquals(61.850, result.weightKg, 0.001)
        assertFalse("weight removed(bit7) 置位的旧读数严禁判定为稳定测量", result.isStable)
    }

    @Test
    fun testXiaomiScale2StepOffResidualWeightTreatedAsZero() {
        // 0204 / 0284 … 1200 0014 00 → 下秤阶段残留的 0.1kg 读数，必须归零以触发上层复位
        for (hex in listOf("0204EA070A10102C1200001400", "0284EA070A10102C1200001400")) {
            val result = MultiScalePacketParser.parseNotification(hex.hexToBytes(), null)
            assertNotNull(result)
            assertEquals("下秤残余读数必须归零: $hex", 0.0, result!!.weightKg, 0.001)
            assertFalse(result.isStable)
            assertNull(result.impedanceOhm)
        }
    }

    @Test
    fun testXiaomiScale2UnitVariants() {
        // kg：control_byte_1 = 0x02，精度 1/200 → 12500 / 200 = 62.5kg
        val kg = MultiScalePacketParser.parseNotification("0224EA070A10102C050000D430".hexToBytes(), null)
        assertNotNull(kg)
        assertEquals(62.5, kg!!.weightKg, 0.001)

        // 斤：control_byte_2 bit6 置位，精度 1/100 → 125.00 斤 × 0.5 = 62.5kg
        val jin = MultiScalePacketParser.parseNotification("0244EA070A10102C050000D430".hexToBytes(), null)
        assertNotNull(jin)
        assertEquals(62.5, jin!!.weightKg, 0.001)

        // lbs：control_byte_1 = 0x03，精度 1/100 → 137.79 lbs × 0.45359237 = 62.5kg
        val lbs = MultiScalePacketParser.parseNotification("0324EA070A10102C050000D335".hexToBytes(), null)
        assertNotNull(lbs)
        assertEquals(62.5, lbs!!.weightKg, 0.01)
    }

    @Test
    fun testXiaomiScale2FullWeighingSessionReplay() {
        // 按时间顺序复刻实机抓包中的一次完整称重（17 种唯一报文）
        val frames = listOf(
            "02A6EA070A10100B2188025230", // 唤醒重播旧读数 61.85kg / 648Ω（removed）
            "0204EA070A10102C0100001E2D", // 57.75kg 爬升
            "0204EA070A10102C0100005230", // 61.85kg
            "0204EA070A10102C0200009830", // 62.20kg
            "0204EA070A10102C020000FC30", // 62.70kg
            "0204EA070A10102C030000DE30", // 62.55kg
            "0204EA070A10102C040000F230", // 62.65kg
            "0204EA070A10102C050000F230", // 62.65kg
            "0224EA070A10102C050000E830", // 稳定 62.600kg，阻抗待测
            "0224EA070A10102C05FEFFE830", // 稳定 62.600kg，阻抗 0xFFFF 无效
            "0226EA070A10102C059502E830", // 稳定 62.600kg + 661Ω ← 本次有效测量
            "02A6EA070A10102C109502E830", // 下秤后重播最终结果
            "0204EA070A10102C1200001400", // 0.1kg 残余
            "0284EA070A10102C1200001400"  // 0.1kg 残余（removed）
        )

        val parsed = frames.map { MultiScalePacketParser.parseNotification(it.hexToBytes(), null) }
        assertTrue("实机抓包的每一帧都应能被解析", parsed.all { it != null })

        // 只有第 9/10/11 帧（stabilized 且尚未下秤）才允许触发锁定
        val stable = parsed.filter { it!!.isStable }
        assertEquals("锁定帧数量", 3, stable.size)
        assertEquals("锁定体重必须唯一且为最终值", listOf(62.6), stable.map { it!!.weightKg }.distinct())

        // 阻抗只允许出现在带 has_impedance 标志的帧上
        assertEquals(listOf(648.0, 661.0, 661.0), parsed.mapNotNull { it!!.impedanceOhm })

        // 唤醒重播的旧读数与下秤残余值都不得进入稳定序列
        assertTrue(stable.all { kotlin.math.abs(it!!.weightKg - 62.600) < 0.001 })
    }

    @Test
    fun testXiaomiScale2BroadcastAdvertisement() {
        // 实机完整 0x181B 广播 AD 结构（即踩即读，无需 GATT 连接）
        val result = MultiScalePacketParser.parseAdvertisementBytes(
            rawBytes = ("02010603021B1810161B180226EA070A10102C059502E830" +
                        "06094D4942465309FF57015CCAD354BA2F").hexToBytes(),
            serviceUuidStrings = listOf("0000181b-0000-1000-8000-00805f9b34fb"),
            bcsServiceData = "0226EA070A10102C059502E830".hexToBytes()
        )

        assertNotNull("小米体脂秤 2 代广播 Service Data 必须被正确解析", result)
        assertEquals("Xiaomi Mi Scale 2", result!!.protocolName)
        assertEquals(62.600, result.weightKg, 0.001)
        assertTrue(result.isStable)
        assertEquals(661.0, result.impedanceOhm!!, 0.001)
    }

    @Test
    fun testXiaomiBodyCompositionScale2StepOffZeroWeight() {
        // 小米体脂秤 2 下秤 0.00kg 数据帧（flags 全 0）
        val xiaomiZero = byteArrayOf(
            0x00.toByte(), 0x00.toByte(),
            0xEA.toByte(), 0x07.toByte(), 0x09.toByte(), 0x0F.toByte(), 0x0C.toByte(), 0x00.toByte(), 0x1E.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte()
        )

        val result = MultiScalePacketParser.parseNotification(xiaomiZero, null)
        assertNotNull("小米体脂秤 2 下秤 0.00kg 数据包不应被丢弃", result)
        assertEquals(0.0, result!!.weightKg, 0.001)
        assertFalse(result.isStable)
        assertNull("0.0kg 离秤时严禁产生阻抗", result.impedanceOhm)
    }

    @Test
    fun testXiaomiPayloadRejectsRandomBytes() {
        // 任意 13 字节随机报文（年份超出 2015..2035），严禁被误判为小米体脂秤
        val random13 = byteArrayOf(
            0x01, 0x02,
            0x99.toByte(), 0x99.toByte(),
            0x25, 0x50, 0x10, 0x20, 0x30,
            0x01, 0x02,
            0x10, 0x20
        )
        assertNull(
            "非法日期的 13 字节数据严禁误判为小米体脂秤",
            MultiScalePacketParser.parseNotification(random13, null)
        )
    }

    // -------------------------------------------------------------------------
    // 广播过滤：双向 GATT 秤绝不可被当作免配对广播秤
    // -------------------------------------------------------------------------

    @Test
    fun testBooheeGattServiceAdvertisementNotTreatedAsBroadcastScale() {
        val raw = byteArrayOf(0x03, 0x03, 0xB0.toByte(), 0xFF.toByte())
        val result = MultiScalePacketParser.parseAdvertisementBytes(
            rawBytes = raw,
            serviceUuidStrings = listOf("0000FFB0-0000-1000-8000-00805F9B34FB")
        )
        assertNull("含 0xFFB0 服务的双向 GATT 秤绝不可以被当作广播秤处理", result)
    }

    @Test
    fun testAppleContinuityNearbyActionNeverParsedAsScale() {
        // 模拟苹果设备 Nearby Action / Continuity 广播包：
        // Company ID = 0x004C (Apple Inc.)，严禁被误判为体脂秤
        val appleBytes = byteArrayOf(
            0x02, 0x01, 0x1A,
            0x09, 0xFF.toByte(), 0x4C, 0x00, 0x10, 0x07, 0x1D, 0x4F, 0x01, 0x00
        )

        val result = MultiScalePacketParser.parseAdvertisementBytes(
            rawBytes = appleBytes,
            serviceUuidStrings = emptyList(),
            preferredModel = ScaleModel.AUTO
        )

        assertNull("苹果 Nearby Action 广播包绝对严禁被误判为体脂秤！", result)
    }

    // -------------------------------------------------------------------------
    // 型号偏好优先级与枚举
    // -------------------------------------------------------------------------

    @Test
    fun testPreferredModelPrioritizationBoohee() {
        val yolandaData = byteArrayOf(
            0xA3.toByte(), 0x19,
            0x01, 0x0B, 0xB8.toByte(),
            0x02, 0x08,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val result = MultiScalePacketParser.parseNotification(
            yolandaData,
            "0000FFA1-0000-1000-8000-00805F9B34FB",
            preferredModel = ScaleModel.BOOHEE_YOLANDA
        )
        assertNotNull(result)
        assertEquals("Yolanda (0xA3)", result!!.protocolName)
        assertEquals(68.54, result.weightKg, 0.01)
        assertTrue(result.isStable)
        assertEquals(520.0, result.impedanceOhm!!, 0.1)
    }

    @Test
    fun testPreferredModelPrioritizationXiaomi() {
        // 实机帧：0226… → 62.600kg + 661Ω
        val result = MultiScalePacketParser.parseNotification(
            "0226EA070A10102C059502E830".hexToBytes(),
            null,
            preferredModel = ScaleModel.XIAOMI_SCALE_2
        )
        assertNotNull(result)
        assertTrue(result!!.protocolName.contains("Xiaomi"))
        assertEquals(62.600, result.weightKg, 0.001)
        assertTrue(result.isStable)
        assertEquals(661.0, result.impedanceOhm!!, 0.001)
    }

    @Test
    fun testScaleModelEnumOnlyKeepsSupportedBrands() {
        // 仅保留薄荷健康/沃莱与小米体脂秤 2 代，外加自动识别模式
        assertEquals(3, ScaleModel.entries.size)
        assertEquals(
            listOf(ScaleModel.AUTO, ScaleModel.BOOHEE_YOLANDA, ScaleModel.XIAOMI_SCALE_2),
            ScaleModel.entries.toList()
        )
        assertEquals(listOf("推荐", "热门品牌"), ScaleModel.grouped().keys.toList())
        assertEquals(listOf(ScaleModel.AUTO), ScaleModel.grouped()["推荐"])
        assertEquals(
            listOf(ScaleModel.BOOHEE_YOLANDA, ScaleModel.XIAOMI_SCALE_2),
            ScaleModel.grouped()["热门品牌"]
        )
    }

    @Test
    fun testScaleModelFromId() {
        assertEquals(ScaleModel.AUTO, ScaleModel.fromId("auto"))
        assertEquals(ScaleModel.BOOHEE_YOLANDA, ScaleModel.fromId("boohee_yolanda"))
        assertEquals(ScaleModel.XIAOMI_SCALE_2, ScaleModel.fromId("xiaomi_scale_2"))
        // 已移除型号的历史持久化 ID 与未知 ID 一律回退到 AUTO
        assertEquals(ScaleModel.AUTO, ScaleModel.fromId("xiaomi_scale_1"))
        assertEquals(ScaleModel.AUTO, ScaleModel.fromId("okok_chipsea"))
        assertEquals(ScaleModel.AUTO, ScaleModel.fromId("senssun"))
        assertEquals(ScaleModel.AUTO, ScaleModel.fromId("afu_protocol"))
        assertEquals(ScaleModel.AUTO, ScaleModel.fromId("phicomm_s7"))
        assertEquals(ScaleModel.AUTO, ScaleModel.fromId("sig_standard"))
        assertEquals(ScaleModel.AUTO, ScaleModel.fromId("some_unknown_model_xyz"))
        assertEquals(ScaleModel.AUTO, ScaleModel.fromId(null))
    }

    // -------------------------------------------------------------------------
    // 配对列表排序
    // -------------------------------------------------------------------------

    @Test
    fun testDiscoveredScaleDeviceSortingWithLiveWeight() {
        val list = mutableListOf(
            com.example.dianzicheng.data.ble.BleScaleClient.DiscoveredScaleDevice(
                name = "未知蓝牙设备",
                address = "AA:BB:CC:11:22:33",
                rssi = -50,
                isConfirmedScale = false
            ),
            com.example.dianzicheng.data.ble.BleScaleClient.DiscoveredScaleDevice(
                name = "普通体脂秤",
                address = "AA:BB:CC:44:55:66",
                rssi = -70,
                isConfirmedScale = true,
                liveWeightKg = null
            ),
            com.example.dianzicheng.data.ble.BleScaleClient.DiscoveredScaleDevice(
                name = "正在踩秤的体脂秤",
                address = "AA:BB:CC:77:88:99",
                rssi = -80,
                isConfirmedScale = true,
                liveWeightKg = 65.20
            )
        )

        list.sortWith(
            compareByDescending<com.example.dianzicheng.data.ble.BleScaleClient.DiscoveredScaleDevice> {
                (it.liveWeightKg != null && it.liveWeightKg > 0.0)
            }.thenByDescending {
                it.isConfirmedScale
            }.thenByDescending {
                it.rssi
            }
        )

        // 正在踩秤的设备必须排在第一位
        assertEquals("正在踩秤的体脂秤", list[0].name)
        assertEquals(65.20, list[0].liveWeightKg!!, 0.001)
        // 确认的体脂秤排在普通蓝牙设备前面
        assertEquals("普通体脂秤", list[1].name)
        assertEquals("未知蓝牙设备", list[2].name)
    }

    /** 把实机抓包中的十六进制报文直接写成测试用例。 */
    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
