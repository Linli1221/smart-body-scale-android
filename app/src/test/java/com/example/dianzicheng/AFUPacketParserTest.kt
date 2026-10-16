package com.example.dianzicheng

import com.example.dianzicheng.data.ble.AFUPacketParser
import org.junit.Assert.*
import org.junit.Test

/**
 * 薄荷健康 / 沃莱 (Yolanda) 20 字节复合帧解析器单元测试。
 *
 * 使用与项目 README 完全一致的实机报文样本：
 *   AC 29 00 69 40 82 02 00 05 40 ...
 * 体重 = ((0x69 - 0x68) * 65536 + 0x40 * 256 + 0x82) / 1000.0 = 82.05 kg
 * 阻抗 = (0x05 shl 8) or 0x40 = 1344 Ω
 */
class AFUPacketParserTest {

    @Test
    fun testParseWeightAndImpedance() {
        val rawData = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x69.toByte(), 0x40.toByte(),
            0x82.toByte(), 0x02.toByte(), 0x00.toByte(), 0x05.toByte(), 0x40.toByte(),
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        )

        val weightData = AFUPacketParser.parseWeight(rawData)
        assertNotNull(weightData)
        assertEquals(82.05, weightData!!.weightKg, 0.0001)
        assertTrue(weightData.isStable)

        val impedance = AFUPacketParser.parseImpedance(rawData)
        assertNotNull(impedance)
        assertEquals(1344.0, impedance!!, 0.0001)
    }

    @Test
    fun testRejectNonAfuFrame() {
        // 薄荷健康 8 字节流式帧（w3 = 0x1A，不在 0x68..0x6E 基准偏移内）必须被拒绝，
        // 严禁产出数千公斤的伪造体重
        val yolandaStream = byteArrayOf(
            0xAC.toByte(), 0x02, 0x1A, 0xC2.toByte(), 0x00, 0x00, 0xCA.toByte(), 0x4A
        )
        assertNull(AFUPacketParser.parseWeight(yolandaStream))
        assertNull(AFUPacketParser.parseImpedance(yolandaStream))
    }

    @Test
    fun testEmptyOrTooShortDataReturnsNull() {
        assertNull(AFUPacketParser.parseWeight(byteArrayOf()))
        assertNull(AFUPacketParser.parseWeight(byteArrayOf(0xAC.toByte(), 0x29, 0x00)))
        assertNull(AFUPacketParser.parseImpedance(byteArrayOf()))
    }
}
