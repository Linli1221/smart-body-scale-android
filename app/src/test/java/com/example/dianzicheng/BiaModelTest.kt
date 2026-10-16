package com.example.dianzicheng

import com.example.dianzicheng.domain.BiaModel
import org.junit.Assert.*
import org.junit.Test

/**
 * [BiaModel] 枚举自身的健壮性测试。
 *
 * 覆盖持久化 ID 的解析（含脏数据回退）与分组顺序，
 * 保证未来新增模型时不会破坏既有存储值或 UI 分组。
 */
class BiaModelTest {

    @Test
    fun fromIdRoundTripsEveryModel() {
        for (model in BiaModel.entries) {
            assertEquals(
                "fromId(${model.id}) 应还原为 ${model.name}",
                model,
                BiaModel.fromId(model.id)
            )
        }
    }

    @Test
    fun fromIdIsCaseInsensitive() {
        assertEquals(BiaModel.WU_2015, BiaModel.fromId("wu_2015"))
        assertEquals(BiaModel.JEBB_2000, BiaModel.fromId("jEbB_2000"))
    }

    @Test
    fun fromIdFallsBackToDefaultOnNullOrUnknown() {
        assertEquals(BiaModel.DEFAULT, BiaModel.fromId(null))
        assertEquals(BiaModel.DEFAULT, BiaModel.fromId(""))
        assertEquals(BiaModel.DEFAULT, BiaModel.fromId("NOT_A_MODEL"))
    }

    @Test
    fun idsAreUnique() {
        val ids = BiaModel.entries.map { it.id }
        assertEquals("模型 ID 必须唯一", ids.size, ids.toSet().size)
    }

    @Test
    fun groupedCoversEveryModelOnce() {
        val grouped = BiaModel.grouped()
        val flat = grouped.values.flatten()
        assertEquals("分组后模型总数不变", BiaModel.entries.size, flat.size)
        assertEquals("每个模型只出现一次", flat.size, flat.toSet().size)
        assertTrue("分组不应为空", grouped.isNotEmpty())
    }

    @Test
    fun everyModelHasFormulaTextAndSource() {
        for (model in BiaModel.entries) {
            assertTrue("${model.id} 缺少公式说明", model.formulaText.isNotBlank())
            assertTrue("${model.id} 缺少来源说明", model.source.isNotBlank())
            assertTrue("${model.id} 缺少描述", model.description.isNotBlank())
            assertTrue(
                "${model.id} 体脂率区间必须有效",
                model.fatMin < model.fatMax && model.fatMin > 0.0
            )
        }
    }
}
