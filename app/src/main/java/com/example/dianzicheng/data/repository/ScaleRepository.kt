package com.example.dianzicheng.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.dianzicheng.data.local.ScaleDao
import com.example.dianzicheng.data.local.toDomain
import com.example.dianzicheng.data.local.toEntity
import com.example.dianzicheng.domain.BiaModel
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.math.abs

/**
 * 体重秤数据仓库，封装 Room DAO 操作，提供领域层可用的接口。
 *
 * 职责：
 * 1. 提供测量记录和家庭成员的增删查 Flow/挂起函数接口
 * 2. 保存测量时自动执行成员智能匹配与 BIA 指标重新计算
 * 3. 在保存成功后更新成员参考体重，提升下次匹配精准度
 * 4. 切换电阻抗估算模型时，把全部历史记录一次性换算到同一口径
 *
 * @param dao    Room DAO，负责实际的增删查
 * @param db     所属的 [RoomDatabase]，用于 [recalculateHistory] 的跨表事务；
 *               为兼容既有单元测试，允许传 null（此时退化为非事务写入）
 */
class ScaleRepository(
    private val dao: ScaleDao,
    private val db: RoomDatabase? = null
) {

    /**
     * 获取所有测量历史记录（Flow，按时间倒序）。
     * 历史页订阅此 Flow，数据库更新后自动推送最新列表。
     */
    fun getHistory(): Flow<List<BodyMeasurement>> =
        dao.getAllMeasurements()
            // 【性能】实体→领域映射固定在 Default 线程池执行，避免每次全表推送都在收集方
            // （主线程 viewModelScope）逐行映射，历史越长省得越多
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    /**
     * 一次性获取所有测量记录列表（挂起函数）。
     * 用于 Health Connect 批量同步、WebDAV 备份等非实时场景。
     */
    suspend fun getAllMeasurements(): List<BodyMeasurement> =
        dao.getAllMeasurementsList().map { it.toDomain() }

    /**
     * 获取所有家庭成员列表（Flow）。
     * ScaleViewModel 和 ProfileViewModel 均订阅此 Flow 以保持成员列表同步。
     */
    fun getMembers(): Flow<List<FamilyMember>> =
        dao.getAllMembers().map { entities -> entities.map { it.toDomain() } }

    /**
     * 保存或覆盖更新一条测量记录，并触发成员匹配逻辑。
     *
     * 优先级（成员绑定）：
     * 1. 传入的 [targetMember]（用户手动选择）
     * 2. 记录中已有的 memberId 所对应的成员
     * 3. 通过 [findBestMember] 智能匹配最近的成员
     * 4. 以上均失败：不绑定成员，只保存体重数据
     *
     * 若成功绑定成员，会用该成员的真实参数（性别、身高、年龄）按用户在
     * 「我的」页选择的电阻抗估算模型 [biaModel] 重新计算体脂率等 BIA 指标，
     * 并将本次体重更新到成员的 [FamilyMember.referenceWeightKg]，供下次匹配使用。
     *
     * @param measurement 原始测量数据（体重、阻抗等）
     * @param targetMember 用户手动指定的目标成员，null 表示自动匹配
     * @param existingId 已有的会话 ID，传入时用于覆盖同一次称量的旧记录（防止重复条目）
     * @param biaModel 电阻抗估算模型，默认 [BiaModel.DEFAULT]
     * @return Pair<最终保存的测量数据, 实际绑定的成员（null 表示未绑定）>
     */
    suspend fun saveMeasurement(
        measurement: BodyMeasurement,
        targetMember: FamilyMember? = null,
        existingId: String? = null,
        biaModel: BiaModel = BiaModel.DEFAULT,
        duplicateIdToRemove: String? = null,
    ): Pair<BodyMeasurement, FamilyMember?> {
        val members = dao.getMembersList().map { it.toDomain() }

        // 按优先级确定实际绑定的成员
        val memberToBind = targetMember
            ?: members.firstOrNull { it.id == measurement.memberId }
            ?: findBestMember(measurement.weightKg, members)

        // 若有匹配成员，用其真实参数重新计算 BIA 指标；否则保留原始数据
        val recalculated = if (memberToBind != null) {
            com.example.dianzicheng.domain.BodyAlgorithm.calculate(
                weightKg = measurement.weightKg,
                impedanceOhm = measurement.impedanceOhm,
                sex = memberToBind.sex,
                heightCm = memberToBind.heightCm,
                birthDateEpochMs = memberToBind.birthDateEpochMs,
                biaModel = biaModel
            ).copy(
                // 使用传入的 existingId 保证同次称量幂等写入，否则生成新 UUID
                id = existingId ?: measurement.id.ifEmpty { UUID.randomUUID().toString() },
                // 保留原始称量时间戳，不被 BodyAlgorithm 内的 System.currentTimeMillis() 覆盖
                measuredAtEpochMs = measurement.measuredAtEpochMs,
                memberId = memberToBind.id,
                memberNameSnapshot = memberToBind.name
            )
        } else {
            // 未匹配到成员时：优先使用系统已有成员身高或默认 170cm 兜底计算有效 BMI，杜绝 BMI 为 0.0
            val fallbackHeightCm = members.firstOrNull()?.heightCm ?: 170.0
            val heightM = (fallbackHeightCm.coerceIn(50.0, 250.0)) / 100.0
            val fallbackBmi = if (measurement.bmi > 0.0) measurement.bmi
                else if (measurement.weightKg > 0.0) (measurement.weightKg / (heightM * heightM)).coerceIn(1.0, 100.0)
                else 0.0
            measurement.copy(
                id = existingId ?: measurement.id.ifEmpty { UUID.randomUUID().toString() },
                bmi = fallbackBmi
            )
        }

        // 合并时原子更新完整记录并删除本次产生的重复记录，避免中间态或部分失败。
        suspend fun persist() {
            dao.insertMeasurement(recalculated.toEntity())
            if (duplicateIdToRemove != null && duplicateIdToRemove != recalculated.id) {
                dao.deleteMeasurementById(duplicateIdToRemove)
            }
        }
        if (duplicateIdToRemove != null && db != null) {
            db.withTransaction { persist() }
        } else {
            persist()
        }

        // 用本次体重更新成员参考体重，供下次智能匹配参考（仅更新单字段，避免整行 REPLACE）。
        // 【写库节流】参考体重只是智能匹配的线索（匹配容差 10kg）：变化不足 0.05kg 时不写库，
        // 避免称重校准期间每次示数都更新 members 表，触发成员 Flow 重发与整页重组。
        memberToBind?.let { member ->
            if (abs(member.referenceWeightKg - measurement.weightKg) >= REFERENCE_WEIGHT_UPDATE_EPSILON_KG) {
                dao.updateMemberReferenceWeight(member.id, measurement.weightKg)
            }
        }

        return Pair(recalculated, memberToBind)
    }

    /**
     * 将已有测量记录重新绑定到指定成员，并以该成员参数按指定电阻抗估算模型
     * 重新计算 BIA 指标后写库。
     *
     * 常用于：用户在称重结果卡片中手动选择"重选/绑定成员"时调用。
     *
     * @param measurement 要重新绑定的原始测量记录
     * @param member 用户选择的目标成员
     * @param biaModel 电阻抗估算模型，默认 [BiaModel.DEFAULT]
     * @return 重新计算后的测量记录
     */
    suspend fun bindMeasurementToMember(
        measurement: BodyMeasurement,
        member: FamilyMember,
        biaModel: BiaModel = BiaModel.DEFAULT
    ): BodyMeasurement {
        val recalculated = com.example.dianzicheng.domain.BodyAlgorithm.calculate(
            weightKg = measurement.weightKg,
            impedanceOhm = measurement.impedanceOhm,
            sex = member.sex,
            heightCm = member.heightCm,
            birthDateEpochMs = member.birthDateEpochMs,
            biaModel = biaModel
        ).copy(
            id = measurement.id,
            measuredAtEpochMs = measurement.measuredAtEpochMs,
            memberId = member.id,
            memberNameSnapshot = member.name
        )
        dao.insertMeasurement(recalculated.toEntity())
        // 同步更新成员参考体重（仅更新单字段，避免整行 REPLACE）
        dao.updateMemberReferenceWeight(member.id, measurement.weightKg)
        return recalculated
    }

    /**
     * 用指定的电阻抗估算模型重新计算**全部历史测量记录**的体脂率与派生指标。
     *
     * 用于用户在「我的 → 体脂估算模型」中切换公式后，把既有历史数据一次性换算到同一口径，
     * 使历史曲线不会因为切换公式而前后断层。
     *
     * 重算规则（逐条记录）：
     * 1. 必须绑定了家庭成员（有 memberId 且成员仍存在）——否则没有性别/身高/年龄，
     *    无法参与 BIA 计算，保持原样不动；
     * 2. 必须有有效阻抗（[BodyMeasurement.impedanceOhm] > 0.0）——未赤脚称重的记录
     *    体脂本来就是 0.0，不存在"换算"一说，保持原样不动；
     * 3. 体重与称重时间、成员绑定关系、id 全部原样保留，只覆盖 BIA 派生指标，
     *    因此同一次称重不会产生新记录，也不会打乱历史排序；
     * 4. 若重算结果与原值完全一致（含浮点比较），跳过写库，避免无意义的 IO。
     *
     * 全部记录在**单个数据库事务**内完成：中途失败会整体回滚，
     * 不会留下"一半旧公式、一半新公式"的混合数据。
     *
     * @param biaModel 要应用的新电阻抗估算模型
     * @return [HistoryRecalcResult] 统计结果（更新条数、跳过条数与原因）
     */
    suspend fun recalculateHistory(biaModel: BiaModel): HistoryRecalcResult {
        // 【性能】全表实体→领域映射在 Default 线程池执行：调用方（ProfileViewModel）运行在主线程，
        // 历史数千条时逐行映射会造成几十毫秒的主线程停顿
        val (membersById, measurements) = withContext(Dispatchers.Default) {
            val membersMap = dao.getMembersList().map { it.toDomain() }.associateBy { it.id }
            val all = dao.getAllMeasurementsList().map { it.toDomain() }
            Pair(membersMap, all)
        }

        var updated = 0
        var skippedNoMember = 0
        var skippedNoImpedance = 0
        var unchanged = 0

        // 事务内一次性写完：要么全部成功，要么全部回滚。
        // 先计算所有需要变更的记录，再批量写入（单条预编译语句循环，远快于逐条 REPLACE）。
        // db 为 null 时（单元测试）退化为普通循环，逻辑保持一致。
        val writeAll: suspend () -> Unit = {
            val changed = mutableListOf<com.example.dianzicheng.data.local.MeasurementEntity>()
            measurements.forEach { measurement ->
                val member = measurement.memberId?.let { membersById[it] }
                if (member == null) {
                    skippedNoMember++
                    return@forEach
                }
                if (measurement.impedanceOhm <= 0.0) {
                    skippedNoImpedance++
                    return@forEach
                }

                val recalculated = com.example.dianzicheng.domain.BodyAlgorithm.calculate(
                    weightKg = measurement.weightKg,
                    impedanceOhm = measurement.impedanceOhm,
                    sex = member.sex,
                    heightCm = member.heightCm,
                    birthDateEpochMs = member.birthDateEpochMs,
                    biaModel = biaModel
                ).copy(
                    id = measurement.id,
                    measuredAtEpochMs = measurement.measuredAtEpochMs,
                    memberId = measurement.memberId,
                    memberNameSnapshot = measurement.memberNameSnapshot
                )

                if (recalculated.isBiaEquivalentTo(measurement)) {
                    unchanged++
                    return@forEach
                }
                changed.add(recalculated.toEntity())
                updated++
            }
            if (changed.isNotEmpty()) dao.insertMeasurements(changed)
        }

        val database = db
        if (database != null) {
            database.withTransaction { writeAll() }
        } else {
            writeAll()
        }

        return HistoryRecalcResult(
            total = measurements.size,
            updated = updated,
            skippedNoMember = skippedNoMember,
            skippedNoImpedance = skippedNoImpedance,
            unchanged = unchanged
        )
    }

    /**
     * 根据当前体重，从数据库中查找最匹配的家庭成员。
     * 是 [findBestMember] 的挂起函数包装，供外部（如 ScaleViewModel）调用。
     */
    suspend fun getBestMember(weightKg: Double): FamilyMember? {
        val members = dao.getMembersList().map { it.toDomain() }
        return findBestMember(weightKg, members)
    }

    /**
     * 成员智能匹配算法，从成员列表中找到体重最接近的成员。
     *
     * 匹配策略（按优先级）：
     * 1. 只有 1 个成员时，直接返回该成员（单用户家庭无歧义）
     * 2. 多成员中若全为新成员（referenceWeightKg <= 0.0），优先绑定第一个未测成员
     * 3. 在已测过体重的成员中，寻找体重最接近的成员（容差放宽至 10.0kg，适应日常体重正常浮动与衣服波动）
     * 4. 若与已测成员差值较大，但存在未测过体重的新增成员，优先归属于未测成员
     * 5. 兜底返回最接近的已有成员，避免数据彻底孤立
     *
     * @param weightKg 本次称量体重（公斤）
     * @param members 候选成员列表
     * @return 最匹配的成员，无法匹配时为 null
     */
    private fun findBestMember(weightKg: Double, members: List<FamilyMember>): FamilyMember? {
        if (members.isEmpty()) return null

        // 1. 单成员：直接自动绑定
        if (members.size == 1) {
            return members.first()
        }

        // 2. 多成员中若全为新成员（referenceWeightKg <= 0.0），优先绑定第一个未测成员
        val measuredMembers = members.filter { it.referenceWeightKg > 0.0 }
        if (measuredMembers.isEmpty()) {
            return members.first()
        }

        // 3. 在已测过体重的成员中，寻找体重最接近的成员（容差放宽至 10.0kg，适应日常体重正常浮动与衣服波动）
        val best = measuredMembers.minByOrNull { abs(it.referenceWeightKg - weightKg) }
        if (best != null && abs(best.referenceWeightKg - weightKg) <= 10.0) {
            return best
        }

        // 4. 若与已测成员差值较大，但存在未测过体重的新增成员，优先归属于未测成员
        val unmeasured = members.firstOrNull { it.referenceWeightKg <= 0.0 }
        if (unmeasured != null) {
            return unmeasured
        }

        // 5. 兜底返回最接近的已有成员，避免数据彻底孤立
        return best ?: members.first()
    }

    /** 按 ID 删除测量记录，用于作废无效会话 */
    suspend fun deleteMeasurementById(id: String) {
        dao.deleteMeasurementById(id)
    }
}

/**
 * 历史记录重新计算的结果统计。
 *
 * @param total              参与本次重算的记录总数
 * @param updated            实际发生变化的记录条数（已写库）
 * @param skippedNoMember    因未绑定成员（或成员已被删除）而跳过的条数
 * @param skippedNoImpedance 因无有效阻抗（未赤脚称重）而跳过的条数
 * @param unchanged          重算结果与原值一致、无需写库的条数
 */
data class HistoryRecalcResult(
    val total: Int,
    val updated: Int,
    val skippedNoMember: Int,
    val skippedNoImpedance: Int,
    val unchanged: Int
) {
    /** 是否存在被跳过的记录（用于给用户一个可读的提示） */
    val hasSkipped: Boolean
        get() = skippedNoMember > 0 || skippedNoImpedance > 0

    /** 面向用户的汇总文案，例如「已重算 12 条记录」 */
    fun summaryText(): String = when {
        total == 0 -> "暂无历史记录，无需重算"
        updated == 0 && !hasSkipped -> "所有记录已是最新模型的结果"
        updated == 0 -> "没有可重算的记录（历史数据缺少成员或未测阻抗）"
        hasSkipped -> "已重算 $updated 条，跳过 ${skippedNoMember + skippedNoImpedance} 条（无成员或无阻抗）"
        else -> "已重算 $updated 条记录"
    }
}

/** 成员参考体重更新门槛（kg）：变化不足此值不写库（写库节流，见 [ScaleRepository.saveMeasurement]） */
private const val REFERENCE_WEIGHT_UPDATE_EPSILON_KG = 0.05

/**
 * 判断两条测量记录的 BIA 派生指标是否等价（用于跳过无意义的历史重算写库）。
 *
 * 只比较体脂率、肌肉量、水分、蛋白质、骨量五项；体重、阻抗、时间、成员绑定等
 * 不属于"公式换算"的范畴，一律视为相同。
 *
 * 使用 1e-6 的绝对容差：既能吸收浮点重排造成的末位差异，
 * 又不会把不同模型的真实差异误判为"未变化"。
 */
private fun BodyMeasurement.isBiaEquivalentTo(other: BodyMeasurement): Boolean {
    // 局部变量不能用 const：Kotlin 仅允许 const 修饰顶层 / object / companion object 中的编译期常量
    val eps = 1e-6
    return abs(bodyFatPct - other.bodyFatPct) < eps &&
        abs(muscleKg - other.muscleKg) < eps &&
        abs(waterPct - other.waterPct) < eps &&
        abs(proteinPct - other.proteinPct) < eps &&
        abs(boneMassKg - other.boneMassKg) < eps
}
