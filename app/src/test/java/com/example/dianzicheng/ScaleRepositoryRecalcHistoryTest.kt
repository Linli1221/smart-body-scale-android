package com.example.dianzicheng

import com.example.dianzicheng.data.local.MeasurementEntity
import com.example.dianzicheng.data.local.MemberEntity
import com.example.dianzicheng.data.local.ScaleDao
import com.example.dianzicheng.data.repository.HistoryRecalcResult
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.BiaModel
import com.example.dianzicheng.domain.BodyAlgorithm
import com.example.dianzicheng.domain.Sex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/**
 * [ScaleRepository.recalculateHistory] 的单元测试。
 *
 * 使用内存假 DAO（不依赖 Android / Room），因此构造 Repository 时 `db` 传 null，
 * 走非事务写入路径。生产环境的 `withTransaction` 包裹的是同一段循环逻辑，
 * 事务只保证「要么全成功、要么全回滚」的原子性，不改变逐条记录的换算结果。
 */
class ScaleRepositoryRecalcHistoryTest {

    private val member = MemberEntity(
        id = "m1",
        name = "自己",
        sex = Sex.MALE,
        heightCm = 175.0,
        birthDateEpochMs = epochMsForAge(30),
        referenceWeightKg = 70.0
    )

    /** 内存假 DAO：用 MutableStateFlow 模拟 Room 的响应式查询与 REPLACE 语义 */
    private class FakeDao : ScaleDao {
        val members = MutableStateFlow<List<MemberEntity>>(emptyList())
        val measurements = MutableStateFlow<List<MeasurementEntity>>(emptyList())

        override fun getAllMembers(): Flow<List<MemberEntity>> = members
        override suspend fun getMembersList(): List<MemberEntity> = members.value
        override suspend fun insertMember(member: MemberEntity) {
            members.value = members.value.filterNot { it.id == member.id } + member
        }

        override suspend fun deleteMember(member: MemberEntity) {
            members.value = members.value.filterNot { it.id == member.id }
        }

        /** 批量插入：语义与 Room 的 @Insert(REPLACE) 一致，逐条覆盖同 ID 记录 */
        override suspend fun insertMembers(members: List<MemberEntity>) {
            members.forEach { insertMember(it) }
        }

        /** 仅更新参考体重字段，模拟 Room 的定向 UPDATE（不触碰其它列） */
        override suspend fun updateMemberReferenceWeight(memberId: String, weightKg: Double) {
            members.value = members.value.map {
                if (it.id == memberId) it.copy(referenceWeightKg = weightKg) else it
            }
        }

        override fun getAllMeasurements(): Flow<List<MeasurementEntity>> = measurements
        override suspend fun getAllMeasurementsList(): List<MeasurementEntity> = measurements.value
        override fun getMeasurementsForMember(memberId: String): Flow<List<MeasurementEntity>> =
            measurements.map { list -> list.filter { it.memberId == memberId } }

        override suspend fun insertMeasurement(measurement: MeasurementEntity) {
            measurements.value = measurements.value.filterNot { it.id == measurement.id } + measurement
        }

        /** 批量插入：语义与 Room 的 @Insert(REPLACE) 一致，逐条覆盖同 ID 记录 */
        override suspend fun insertMeasurements(measurements: List<MeasurementEntity>) {
            measurements.forEach { insertMeasurement(it) }
        }

        override suspend fun deleteMeasurement(measurement: MeasurementEntity) {
            measurements.value = measurements.value.filterNot { it.id == measurement.id }
        }

        override suspend fun deleteMeasurementById(id: String) {
            measurements.value = measurements.value.filterNot { it.id == id }
        }

        override suspend fun deleteMeasurementsByMemberId(memberId: String) {
            measurements.value = measurements.value.filterNot { it.memberId == memberId }
        }
    }

    /** 构造一个「年龄恰好为 [age] 岁」的出生日期时间戳 */
    private fun epochMsForAge(age: Int): Long {
        val cal = Calendar.getInstance().apply {
            add(Calendar.YEAR, -age)
            add(Calendar.DAY_OF_YEAR, -30)
        }
        return cal.timeInMillis
    }

    private fun measurement(
        id: String,
        memberId: String? = "m1",
        impedance: Double = 500.0,
        fat: Double = 0.0
    ) = MeasurementEntity(
        id = id,
        measuredAtEpochMs = 1_700_000_000_000L + id.hashCode(),
        weightKg = 70.0,
        impedanceOhm = impedance,
        bmi = 70.0 / (1.75 * 1.75),
        bodyFatPct = fat,
        muscleKg = 0.0,
        waterPct = 0.0,
        proteinPct = 0.0,
        boneMassKg = 0.0,
        memberId = memberId,
        memberNameSnapshot = "自己"
    )

    /** 用默认模型算一次，作为「历史里已存着旧口径数据」的基线 */
    private fun baselineFat(): Double = BodyAlgorithm.calculate(
        weightKg = 70.0,
        impedanceOhm = 500.0,
        sex = Sex.MALE,
        heightCm = 175.0,
        birthDateEpochMs = member.birthDateEpochMs,
        biaModel = BiaModel.DEFAULT
    ).bodyFatPct

    /** 期望的新模型体脂率 */
    private fun expectedFat(model: BiaModel): Double = BodyAlgorithm.calculate(
        weightKg = 70.0,
        impedanceOhm = 500.0,
        sex = Sex.MALE,
        heightCm = 175.0,
        birthDateEpochMs = member.birthDateEpochMs,
        biaModel = model
    ).bodyFatPct

    @Test
    fun recalculateUpdatesMeasurementsWithImpedanceAndMember() = runBlocking {
        val dao = FakeDao().apply {
            members.value = listOf(member)
            // 已有旧口径数据（fat 非 0），应被新模型覆盖
            measurements.value = listOf(
                measurement("a", fat = baselineFat()),
                measurement("b", fat = baselineFat())
            )
        }
        val repo = ScaleRepository(dao)

        val result = repo.recalculateHistory(BiaModel.WU_2015)

        assertEquals("应更新 2 条", 2, result.updated)
        assertEquals("总数应为 2", 2, result.total)
        assertEquals("不应有无成员跳过", 0, result.skippedNoMember)
        assertEquals("不应有无阻抗跳过", 0, result.skippedNoImpedance)
        assertEquals("不应有未变化", 0, result.unchanged)

        val expected = expectedFat(BiaModel.WU_2015)
        dao.measurements.value.forEach { m ->
            assertEquals("${m.id} 的体脂率应换算到新模型", expected, m.bodyFatPct, 1e-9)
            assertTrue("${m.id} 的肌肉量应被重新计算", m.muscleKg > 0.0)
        }
    }

    @Test
    fun recalculateSkipsRecordsWithoutImpedance() = runBlocking {
        val dao = FakeDao().apply {
            members.value = listOf(member)
            measurements.value = listOf(
                measurement("with-imp", impedance = 500.0, fat = baselineFat()),
                measurement("no-imp", impedance = 0.0, fat = 0.0)
            )
        }
        val repo = ScaleRepository(dao)

        val result = repo.recalculateHistory(BiaModel.JEBB_2000)

        assertEquals("有阻抗的应更新", 1, result.updated)
        assertEquals("无阻抗的应跳过", 1, result.skippedNoImpedance)
        assertEquals(
            "无阻抗记录必须保持原值 0.0",
            0.0,
            dao.measurements.value.first { it.id == "no-imp" }.bodyFatPct,
            0.0
        )
    }

    @Test
    fun recalculateSkipsRecordsWithoutMember() = runBlocking {
        val dao = FakeDao().apply {
            members.value = listOf(member)
            measurements.value = listOf(
                measurement("bound", memberId = "m1", impedance = 500.0, fat = baselineFat()),
                measurement("unbound", memberId = null, impedance = 500.0, fat = 0.0),
                measurement("orphan", memberId = "deleted-member", impedance = 500.0, fat = 0.0)
            )
        }
        val repo = ScaleRepository(dao)

        val result = repo.recalculateHistory(BiaModel.WU_2015)

        assertEquals("只有绑定有效成员的记录应更新", 1, result.updated)
        assertEquals("未绑定或成员已删除的应跳过", 2, result.skippedNoMember)
        assertEquals(
            "未绑定记录必须保持原值",
            0.0,
            dao.measurements.value.first { it.id == "unbound" }.bodyFatPct,
            0.0
        )
    }

    @Test
    fun recalculateReportsUnchangedWhenSameModel() = runBlocking {
        val dao = FakeDao().apply {
            members.value = listOf(member)
            // 先写入默认模型的精确结果，再用默认模型重算 → 应判定为未变化
            measurements.value = listOf(
                measurement("a", fat = baselineFat()),
                measurement("b", fat = baselineFat())
            )
        }
        val repo = ScaleRepository(dao)

        val result = repo.recalculateHistory(BiaModel.DEFAULT)

        assertEquals("同模型重算不应产生写入", 0, result.updated)
        assertEquals("同模型重算应判定为未变化", 2, result.unchanged)
        assertFalse(result.hasSkipped)
        assertEquals("所有记录已是最新模型的结果", result.summaryText())
    }

    @Test
    fun recalculatePreservesIdentityFields() = runBlocking {
        val original = measurement("keep-id", fat = baselineFat()).copy(
            measuredAtEpochMs = 1_600_000_000_000L,
            memberNameSnapshot = "自定义快照名"
        )
        val dao = FakeDao().apply {
            members.value = listOf(member)
            measurements.value = listOf(original)
        }
        val repo = ScaleRepository(dao)

        repo.recalculateHistory(BiaModel.JEBB_2000)

        val after = dao.measurements.value.single()
        assertEquals("记录 ID 不能被改变", "keep-id", after.id)
        assertEquals("称重时间不能被改变", 1_600_000_000_000L, after.measuredAtEpochMs)
        assertEquals("体重不能被改变", original.weightKg, after.weightKg, 0.0)
        assertEquals("阻抗不能被改变", original.impedanceOhm, after.impedanceOhm, 0.0)
        assertEquals("成员绑定不能被改变", "m1", after.memberId)
        assertEquals("成员姓名快照不能被改变", "自定义快照名", after.memberNameSnapshot)
    }

    @Test
    fun recalculateHandlesEmptyHistory() = runBlocking {
        val dao = FakeDao().apply {
            members.value = listOf(member)
            measurements.value = emptyList()
        }
        val repo = ScaleRepository(dao)

        val result = repo.recalculateHistory(BiaModel.WU_2015)

        assertEquals(0, result.total)
        assertEquals(0, result.updated)
        assertFalse(result.hasSkipped)
        assertEquals("暂无历史记录，无需重算", result.summaryText())
    }

    @Test
    fun recalculateIsIdempotent() = runBlocking {
        val dao = FakeDao().apply {
            members.value = listOf(member)
            measurements.value = listOf(measurement("a", fat = baselineFat()))
        }
        val repo = ScaleRepository(dao)

        // 第一次：从默认模型切到 Wu 2015，应产生写入
        val first = repo.recalculateHistory(BiaModel.WU_2015)
        assertEquals(1, first.updated)

        // 第二次：同一模型再跑一遍，结果应完全一致且不再写入
        val second = repo.recalculateHistory(BiaModel.WU_2015)
        assertEquals(0, second.updated)
        assertEquals(1, second.unchanged)
        assertEquals("同模型重算应幂等", expectedFat(BiaModel.WU_2015), dao.measurements.value.single().bodyFatPct, 1e-9)
    }

    @Test
    fun summaryTextDescribesSkippedRecords() {
        val result = HistoryRecalcResult(
            total = 10,
            updated = 6,
            skippedNoMember = 2,
            skippedNoImpedance = 2,
            unchanged = 0
        )
        assertTrue(result.hasSkipped)
        assertEquals("已重算 6 条，跳过 4 条（无成员或无阻抗）", result.summaryText())
    }

    @Test
    fun summaryTextReportsNothingToDo() {
        val empty = HistoryRecalcResult(0, 0, 0, 0, 0)
        assertEquals("暂无历史记录，无需重算", empty.summaryText())
        assertFalse(empty.hasSkipped)

        val allSkipped = HistoryRecalcResult(total = 5, updated = 0, skippedNoMember = 3, skippedNoImpedance = 2, unchanged = 0)
        assertEquals("没有可重算的记录（历史数据缺少成员或未测阻抗）", allSkipped.summaryText())
        assertTrue(allSkipped.hasSkipped)

        val unchanged = HistoryRecalcResult(total = 3, updated = 0, skippedNoMember = 0, skippedNoImpedance = 0, unchanged = 3)
        assertEquals("所有记录已是最新模型的结果", unchanged.summaryText())
        assertFalse(unchanged.hasSkipped)
    }
}
