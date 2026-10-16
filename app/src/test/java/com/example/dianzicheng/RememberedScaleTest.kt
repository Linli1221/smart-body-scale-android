package com.example.dianzicheng

import com.example.dianzicheng.domain.RememberedScale
import com.example.dianzicheng.domain.RememberedScaleCodec
import com.example.dianzicheng.domain.ScaleMemoryLogic
import com.example.dianzicheng.domain.ScaleModel
import com.example.dianzicheng.domain.buildScaleMemory
import com.example.dianzicheng.domain.normalizeMacAddress
import com.example.dianzicheng.domain.sameMacAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「记住多台体脂秤」纯逻辑单元测试。
 *
 * 覆盖三块核心风险点：
 * 1. 持久化编解码必须往返无损（设备名可能包含制表符、换行、反斜杠等任意字符）；
 * 2. 老版本「单台已配对设备」必须无损迁移为多设备列表；
 * 3. 列表增删改与「当前使用设备」的解析规则（多设备场景下最容易出现数据错乱的地方）。
 */
class RememberedScaleTest {

    private fun scale(
        mac: String,
        name: String = "体脂秤",
        modelId: String = ScaleModel.AUTO.id,
        protocolName: String = "",
        lastUsedAtMs: Long = 0L
    ) = RememberedScale(
        mac = mac,
        name = name,
        modelId = modelId,
        protocolName = protocolName,
        lastUsedAtMs = lastUsedAtMs
    )

    // ── MAC 归一化 ────────────────────────────────────────────────────────────

    @Test
    fun normalizeMacAddressTrimsAndUppercases() {
        assertEquals("AA:BB:CC:DD:EE:FF", normalizeMacAddress("  aa:bb:cc:dd:ee:ff "))
        assertEquals("", normalizeMacAddress(null))
        assertEquals("", normalizeMacAddress("   "))
    }

    @Test
    fun sameMacAddressIgnoresCaseAndBlankValues() {
        assertTrue(sameMacAddress("AA:BB", "aa:bb"))
        assertFalse(sameMacAddress(null, "AA:BB"))
        assertFalse(sameMacAddress("AA:BB", null))
        assertFalse("两个空值不得视为同一台设备", sameMacAddress("", ""))
        assertFalse(sameMacAddress("   ", "  "))
        assertFalse(sameMacAddress("AA:BB", "AA:CC"))
    }

    // ── 编解码 ────────────────────────────────────────────────────────────────

    @Test
    fun encodeEmptyListProducesEmptyString() {
        assertEquals("", RememberedScaleCodec.encode(emptyList()))
    }

    @Test
    fun codecRoundTripKeepsTrickyNamesIntact() {
        val trickyName = "小米体脂秤\t2\n(备用)\\A·薄荷健康 B1"
        val original = listOf(
            scale(
                mac = "aa:bb:cc:dd:ee:01",
                name = trickyName,
                modelId = ScaleModel.XIAOMI_SCALE_2.id,
                protocolName = "Xiaomi Mi Scale 2",
                lastUsedAtMs = 1712345678901L
            )
        )

        val decoded = RememberedScaleCodec.decode(RememberedScaleCodec.encode(original))

        assertEquals(1, decoded.size)
        // MAC 会被归一化为大写形式
        assertEquals("AA:BB:CC:DD:EE:01", decoded[0].mac)
        assertEquals("含制表符/换行/反斜杠的设备名必须无损往返", trickyName, decoded[0].name)
        assertEquals(ScaleModel.XIAOMI_SCALE_2.id, decoded[0].modelId)
        assertEquals("Xiaomi Mi Scale 2", decoded[0].protocolName)
        assertEquals(1712345678901L, decoded[0].lastUsedAtMs)
    }

    @Test
    fun codecRoundTripKeepsMultipleDevicesInOrder() {
        val original = listOf(
            scale("AA:BB:CC:DD:EE:01", "甲", lastUsedAtMs = 3L),
            scale("AA:BB:CC:DD:EE:02", "乙", ScaleModel.BOOHEE_YOLANDA.id, "Yolanda/Boohee", 2L),
            scale("AA:BB:CC:DD:EE:03", "丙", lastUsedAtMs = 1L)
        )

        val decoded = RememberedScaleCodec.decode(RememberedScaleCodec.encode(original))

        assertEquals(3, decoded.size)
        assertEquals(listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:03"), decoded.map { it.mac })
        assertEquals(ScaleModel.BOOHEE_YOLANDA.id, decoded[1].modelId)
    }

    @Test
    fun decodeReturnsEmptyForNullOrUnrecognizableInput() {
        assertEquals(0, RememberedScaleCodec.decode(null).size)
        assertEquals(0, RememberedScaleCodec.decode("").size)
        assertEquals(0, RememberedScaleCodec.decode("    ").size)
        assertEquals(0, RememberedScaleCodec.decode("just-some-garbage").size)
        assertEquals(0, RememberedScaleCodec.decode("[not json at all]").size)
        // 只有表头、没有任何设备行
        assertEquals(0, RememberedScaleCodec.decode("SCALES_V1").size)
    }

    @Test
    fun decodeSkipsBlankMacAndDeduplicatesIgnoringCase() {
        val raw = "SCALES_V1\n" +
            "AA:BB:CC:DD:EE:01\t设备甲\tXIAOMI_SCALE_2\tProto\t111\n" +
            "aa:bb:cc:dd:ee:01\t重复甲\tAUTO\t\t222\n" +
            "\t空MAC设备\tAUTO\t\t333\n" +
            "AA:BB:CC:DD:EE:02\t设备乙\tAUTO\t\t0"

        val decoded = RememberedScaleCodec.decode(raw)

        assertEquals("重复 MAC（忽略大小写）只保留第一条", 2, decoded.size)
        assertEquals("AA:BB:CC:DD:EE:01", decoded[0].mac)
        assertEquals("设备甲", decoded[0].name)
        assertEquals(111L, decoded[0].lastUsedAtMs)
        assertEquals("AA:BB:CC:DD:EE:02", decoded[1].mac)
        assertEquals("设备乙", decoded[1].name)
    }

    @Test
    fun decodeFillsDefaultsForMissingFields() {
        val decoded = RememberedScaleCodec.decode("SCALES_V1\nAA:BB:CC:DD:EE:03\t")

        assertEquals(1, decoded.size)
        assertEquals("AA:BB:CC:DD:EE:03", decoded[0].mac)
        assertEquals("体脂秤", decoded[0].name)
        assertEquals(ScaleModel.AUTO.id, decoded[0].modelId)
        assertEquals("", decoded[0].protocolName)
        assertEquals(0L, decoded[0].lastUsedAtMs)
    }

    @Test
    fun decodeToleratesCrlfAndMissingHeader() {
        val crlf = "SCALES_V1\r\nAA:BB:CC:DD:EE:04\t设备\r\n"
        val withoutHeader = "AA:BB:CC:DD:EE:05\t无表头设备"

        val decodedCrlf = RememberedScaleCodec.decode(crlf)
        assertEquals(1, decodedCrlf.size)
        assertEquals("设备", decodedCrlf[0].name)

        val decodedWithoutHeader = RememberedScaleCodec.decode(withoutHeader)
        assertEquals(1, decodedWithoutHeader.size)
        assertEquals("无表头设备", decodedWithoutHeader[0].name)
    }

    // ── 旧版单设备迁移 ────────────────────────────────────────────────────────

    @Test
    fun migrateLegacyBuildsSingleEntry() {
        val migrated = ScaleMemoryLogic.migrateLegacy("  aa:bb:cc:dd:ee:06 ", "  小米体脂秤 2  ")

        assertEquals(1, migrated.size)
        assertEquals("AA:BB:CC:DD:EE:06", migrated[0].mac)
        assertEquals("小米体脂秤 2", migrated[0].name)
        assertEquals(ScaleModel.AUTO.id, migrated[0].modelId)
        assertEquals("", migrated[0].protocolName)
        assertEquals(0L, migrated[0].lastUsedAtMs)
    }

    @Test
    fun migrateLegacyFallsBackToDefaultNameAndSkipsBlankMac() {
        assertEquals("体脂秤", ScaleMemoryLogic.migrateLegacy("AA:BB:CC:DD:EE:07", null)[0].name)
        assertEquals(0, ScaleMemoryLogic.migrateLegacy(null, "旧设备").size)
        assertEquals(0, ScaleMemoryLogic.migrateLegacy("", "旧设备").size)
        assertEquals(0, ScaleMemoryLogic.migrateLegacy("   ", "旧设备").size)
    }

    @Test
    fun buildScaleMemoryPrefersStoredListOverLegacyFields() {
        val stored = RememberedScaleCodec.encode(listOf(scale("AA:BB:CC:DD:EE:0A", "新版设备")))

        val memory = buildScaleMemory(
            rawList = stored,
            rawActiveMac = "AA:BB:CC:DD:EE:0A",
            legacyMac = "AA:BB:CC:DD:EE:0B",
            legacyName = "旧版设备"
        )

        assertEquals(1, memory.scales.size)
        assertEquals("新版设备", memory.scales[0].name)
        assertEquals("AA:BB:CC:DD:EE:0A", memory.activeMac)
        assertEquals("新版设备", memory.active?.name)
        assertFalse(memory.isEmpty)
    }

    @Test
    fun buildScaleMemoryFallsBackToLegacyWhenStoredListEmpty() {
        val memory = buildScaleMemory(
            rawList = null,
            rawActiveMac = null,
            legacyMac = "aa:bb:cc:dd:ee:0b",
            legacyName = "旧版设备"
        )

        assertEquals(1, memory.scales.size)
        assertEquals("AA:BB:CC:DD:EE:0B", memory.scales[0].mac)
        assertEquals("旧版设备", memory.scales[0].name)
        assertEquals("旧版设备必须自动成为当前使用设备", "AA:BB:CC:DD:EE:0B", memory.activeMac)
    }

    @Test
    fun buildScaleMemoryFallsBackToFirstWhenPreferredMissing() {
        val stored = RememberedScaleCodec.encode(
            listOf(scale("AA:BB:CC:DD:EE:01", "甲"), scale("AA:BB:CC:DD:EE:02", "乙"))
        )

        val memory = buildScaleMemory(
            rawList = stored,
            rawActiveMac = "AA:BB:CC:DD:EE:09",
            legacyMac = null,
            legacyName = null
        )

        assertEquals("AA:BB:CC:DD:EE:01", memory.activeMac)
        assertEquals("甲", memory.active?.name)
        assertEquals(2, memory.scales.size)
    }

    @Test
    fun buildScaleMemoryReturnsEmptyWhenNothingStored() {
        val memory = buildScaleMemory(null, null, null, null)

        assertTrue(memory.isEmpty)
        assertEquals(0, memory.scales.size)
        assertNull(memory.activeMac)
        assertNull(memory.active)
    }

    @Test
    fun scaleMemoryActiveFallsBackToFirstForStaleActiveMac() {
        val first = scale("AA:BB:CC:DD:EE:01", "甲")
        val second = scale("AA:BB:CC:DD:EE:02", "乙")

        val memory = com.example.dianzicheng.domain.ScaleMemory(
            scales = listOf(first, second),
            activeMac = "AA:BB:CC:DD:EE:FF"
        )

        assertEquals("甲", memory.active?.name)
    }

    // ── 列表增删改 ────────────────────────────────────────────────────────────

    @Test
    fun upsertReplacesMatchingMacIgnoringCaseAndMovesToFront() {
        val list = listOf(
            scale("AA:BB:CC:DD:EE:01", "甲"),
            scale("AA:BB:CC:DD:EE:02", "乙")
        )

        val updated = ScaleMemoryLogic.upsert(
            list,
            scale("aa:bb:cc:dd:ee:02", "乙改名", ScaleModel.XIAOMI_SCALE_2.id, "Proto", 500L)
        )

        assertEquals(2, updated.size)
        assertEquals("AA:BB:CC:DD:EE:02", updated[0].mac)
        assertEquals("乙改名", updated[0].name)
        assertEquals(ScaleModel.XIAOMI_SCALE_2.id, updated[0].modelId)
        assertEquals("Proto", updated[0].protocolName)
        assertEquals(500L, updated[0].lastUsedAtMs)
        assertEquals("AA:BB:CC:DD:EE:01", updated[1].mac)
    }

    @Test
    fun upsertKeepsPreviousFieldsForMacOnlyRefresh() {
        val existing = scale("AA:BB:CC:DD:EE:01", "用户命名", ScaleModel.XIAOMI_SCALE_2.id, "Proto", 999L)

        val updated = ScaleMemoryLogic.upsert(listOf(existing), scale("AA:BB:CC:DD:EE:01", "", "", "", 0L))

        assertEquals(1, updated.size)
        assertEquals("仅携带 MAC 的补充登记不得覆盖已有名称", "用户命名", updated[0].name)
        assertEquals(ScaleModel.XIAOMI_SCALE_2.id, updated[0].modelId)
        assertEquals("Proto", updated[0].protocolName)
        assertEquals(999L, updated[0].lastUsedAtMs)
    }

    @Test
    fun upsertDoesNotDowngradeConcreteModelToAuto() {
        val existing = scale("AA:BB:CC:DD:EE:01", "小米秤", ScaleModel.XIAOMI_SCALE_2.id, "Xiaomi Mi Scale 2", 10L)

        // 重新绑定同一台秤，但本次未识别出具体型号（AUTO）：不得把已记住的型号降级为 AUTO
        val updated = ScaleMemoryLogic.upsert(
            listOf(existing),
            scale("AA:BB:CC:DD:EE:01", "小米秤", ScaleModel.AUTO.id, "", 20L)
        )

        assertEquals(1, updated.size)
        assertEquals(ScaleModel.XIAOMI_SCALE_2.id, updated[0].modelId)
        assertEquals("协议说明同样不得被清空", "Xiaomi Mi Scale 2", updated[0].protocolName)

        // 识别出具体型号时仍应正常刷新
        val refreshed = ScaleMemoryLogic.upsert(
            updated,
            scale("AA:BB:CC:DD:EE:01", "小米秤", ScaleModel.BOOHEE_YOLANDA.id, "", 30L)
        )
        assertEquals(ScaleModel.BOOHEE_YOLANDA.id, refreshed[0].modelId)
    }

    @Test
    fun upsertIgnoresBlankMac() {
        val list = listOf(scale("AA:BB:CC:DD:EE:01", "甲"))

        assertEquals(list, ScaleMemoryLogic.upsert(list, scale("   ", "无效")))
        assertEquals(0, ScaleMemoryLogic.upsert(emptyList(), scale("", "无效")).size)
    }

    @Test
    fun upsertCapsRememberedDeviceCount() {
        var list: List<RememberedScale> = emptyList()
        for (index in 1..10) {
            list = ScaleMemoryLogic.upsert(list, scale("AA:BB:CC:DD:EE:%02d".format(index), "设备$index"))
        }

        assertEquals(ScaleMemoryLogic.MAX_REMEMBERED, list.size)
        assertEquals("最新添加的设备必须保留", "设备10", list[0].name)
        assertEquals("设备3", list[list.size - 1].name)
    }

    @Test
    fun ensureNeverOverwritesExistingEntry() {
        val existing = scale("AA:BB:CC:DD:EE:01", "用户命名", ScaleModel.XIAOMI_SCALE_2.id, "Proto", 5L)

        val afterExisting = ScaleMemoryLogic.ensure(listOf(existing), scale("aa:bb:cc:dd:ee:01", "体脂秤"))
        assertEquals(1, afterExisting.size)
        assertEquals("用户命名", afterExisting[0].name)
        assertEquals(ScaleModel.XIAOMI_SCALE_2.id, afterExisting[0].modelId)

        val afterNew = ScaleMemoryLogic.ensure(listOf(existing), scale("AA:BB:CC:DD:EE:02", "新设备"))
        assertEquals(2, afterNew.size)
        assertEquals("AA:BB:CC:DD:EE:02", afterNew[0].mac)
        assertEquals("新设备", afterNew[0].name)

        val afterBlank = ScaleMemoryLogic.ensure(listOf(existing), scale("", "无效"))
        assertEquals(1, afterBlank.size)
        assertEquals("AA:BB:CC:DD:EE:01", afterBlank[0].mac)
    }

    @Test
    fun removeDropsOnlyMatchingMac() {
        val list = listOf(
            scale("AA:BB:CC:DD:EE:01", "甲"),
            scale("AA:BB:CC:DD:EE:02", "乙")
        )

        val afterRemove = ScaleMemoryLogic.remove(list, "aa:bb:cc:dd:ee:01")
        assertEquals(1, afterRemove.size)
        assertEquals("AA:BB:CC:DD:EE:02", afterRemove[0].mac)

        assertEquals("空 MAC 不得误删任何设备", 2, ScaleMemoryLogic.remove(list, null).size)
        assertEquals(2, ScaleMemoryLogic.remove(list, "  ").size)
        assertEquals("不存在的 MAC 不得误删任何设备", 2, ScaleMemoryLogic.remove(list, "AA:BB:CC:DD:EE:99").size)
    }

    @Test
    fun resolveActivePrefersPreferredMacThenFirst() {
        val list = listOf(
            scale("AA:BB:CC:DD:EE:01", "甲"),
            scale("AA:BB:CC:DD:EE:02", "乙")
        )

        assertEquals("AA:BB:CC:DD:EE:02", ScaleMemoryLogic.resolveActive(list, "aa:bb:cc:dd:ee:02"))
        assertEquals("AA:BB:CC:DD:EE:01", ScaleMemoryLogic.resolveActive(list, "AA:BB:CC:DD:EE:99"))
        assertEquals("AA:BB:CC:DD:EE:01", ScaleMemoryLogic.resolveActive(list, null))
        assertNull("空列表必须解析为 null（未记住任何设备）", ScaleMemoryLogic.resolveActive(emptyList(), "AA:BB:CC:DD:EE:01"))
    }

    @Test
    fun markUsedRefreshesTimestampAndMovesToFront() {
        val list = listOf(
            scale("AA:BB:CC:DD:EE:01", "甲", lastUsedAtMs = 100L),
            scale("AA:BB:CC:DD:EE:02", "乙", lastUsedAtMs = 200L)
        )

        val updated = ScaleMemoryLogic.markUsed(list, "aa:bb:cc:dd:ee:01", 777L)

        assertEquals(2, updated.size)
        assertEquals("AA:BB:CC:DD:EE:01", updated[0].mac)
        assertEquals(777L, updated[0].lastUsedAtMs)
        assertEquals("AA:BB:CC:DD:EE:02", updated[1].mac)

        val untouched = ScaleMemoryLogic.markUsed(list, "AA:BB:CC:DD:EE:99", 777L)
        assertEquals(list.map { it.mac }, untouched.map { it.mac })
    }

    @Test
    fun rememberedScaleExposesModelAndDetail() {
        val concrete = scale("AA:BB:CC:DD:EE:01", "小米秤", ScaleModel.XIAOMI_SCALE_2.id, "Xiaomi Mi Scale 2")
        assertEquals(ScaleModel.XIAOMI_SCALE_2, concrete.model)
        assertEquals("Xiaomi Mi Scale 2", concrete.detail)

        val autoWithModel = scale("AA:BB:CC:DD:EE:02", "未知", ScaleModel.AUTO.id, "")
        assertEquals("", autoWithModel.detail)

        val showy = scale("AA:BB:CC:DD:EE:03", "小米秤", ScaleModel.XIAOMI_SCALE_2.id, "")
        assertEquals(ScaleModel.XIAOMI_SCALE_2.displayName, showy.detail)
    }
}
