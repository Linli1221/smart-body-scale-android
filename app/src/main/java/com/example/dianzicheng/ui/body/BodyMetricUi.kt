package com.example.dianzicheng.ui.body

import com.example.dianzicheng.domain.BodyAlgorithm
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.Sex
import java.time.DayOfWeek
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

/** 八项指标。顺序即界面从上到下的阅读顺序。 */
enum class MetricKind { WEIGHT, BMI, BODY_FAT, MUSCLE, WATER, VISCERAL_FAT, PROTEIN, BONE }

enum class MetricTone { GOOD, WARN, DANGER, NEUTRAL }

data class MetricStatus(val label: String, val tone: MetricTone)

/**
 * 三色区间条数据。全部为绝对数值，UI 只负责把 [value] 映射到条上的游标位置。
 */
data class RangeBarSpec(
    val min: Float,
    val standardStart: Float,
    val standardEnd: Float,
    val max: Float,
    val value: Float,
)

data class MetricReading(
    val kind: MetricKind,
    val title: String,
    val valueText: String,
    val unit: String,
    val numericValue: Double?,
    val status: MetricStatus?,
    val range: RangeBarSpec?,
)

data class BodyReport(
    val measurement: BodyMeasurement,
    val member: FamilyMember?,
    val readings: Map<MetricKind, MetricReading>,
    val hasBia: Boolean,
    val weightDeltaKg: Double?,
    val isFirstRecord: Boolean,
    val visceralFatLevel: Int?,
)

enum class HistoryMode { SINGLE, WEEK, MONTH }

/** 最近七个本地日期的起点（包含今天），按日历日处理夏令时。 */
fun startOfRecentSevenDaysMillis(nowMs: Long = System.currentTimeMillis()): Long {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().minusDays(6)
        .atStartOfDay(zone).toInstant().toEpochMilli()
}

/** 计算包含 [nowMs] 的 ISO 周（周一为起点）的 00:00 时间戳。 */
fun startOfWeekMillis(nowMs: Long = System.currentTimeMillis()): Long {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
}

/** 计算下一 ISO 周的起点（用于日期前后切换）。 */
fun shiftWeekMillis(weekStartMs: Long, weeks: Int): Long {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(weekStartMs).atZone(zone).toLocalDate()
    return date.plusWeeks(weeks.toLong())
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
}

/** 格式化周范围标题，例如 10/26 – 11/01。 */
fun formatWeekRange(weekStartMs: Long): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(weekStartMs).atZone(zone).toLocalDate()
    val end = start.plusDays(6)
    return start.format(MM_DD_FORMAT) + " – " + end.format(MM_DD_FORMAT)
}

/** 判断某条测量是否落在指定 ISO 周内（左闭右开）。 */
fun isInWeekWindow(
    measuredAtMs: Long,
    weekStartMs: Long,
    weekEndMs: Long,
): Boolean = measuredAtMs >= weekStartMs && measuredAtMs < weekEndMs

/**
 * 时间轴快照。
 *
 * [measurement] 在单次模式下就是原始记录；在周均/月均模式下是聚合后的合成记录，
 * 因此卡片与趋势图可以共用同一套读取逻辑。
 */
data class HistorySnapshot(
    val measurement: BodyMeasurement,
    val label: String,
    val sublabel: String,
    val bucketStartMs: Long,
    val sampleCount: Int,
)

private const val DEFAULT_AGE = 30

private val MM_DD_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd", Locale.US)
private val HH_MM_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

/** 由出生日期推导年龄；缺失/未来日期回落到中性 30 岁。
 *  薄委托 [BodyAlgorithm.ageFrom]（全工程唯一年龄口径），仅保留 UI 层既有签名与语义。 */
fun ageYears(birthDateEpochMs: Long, nowMs: Long = System.currentTimeMillis()): Int =
    BodyAlgorithm.ageFrom(birthDateEpochMs, nowMs)

/**
 * 内脏脂肪等级估算。
 *
 * 体脂秤的 BIA 硬件并未直接测量内脏脂肪；这里用 BMI、体脂率、年龄、性别做
 * 可解释的近似（1..20 级），仅用于界面趋势展示，不写入数据库、不参与医学判断。
 * 公式刻意保持简单：BMI 偏离标准越多、体脂越高、年龄越大，等级越高。
 */
fun estimateVisceralFatLevel(m: BodyMeasurement, member: FamilyMember?): Int {
    if (m.impedanceOhm <= 0.0 || m.bodyFatPct <= 0.0) return 1
    val male = member?.sex != Sex.FEMALE
    val age = member?.let { ageYears(it.birthDateEpochMs) } ?: DEFAULT_AGE
    val bmiPart = ((m.bmi - 18.5) / 2.5).coerceAtLeast(0.0)
    val fatLimit = if (male) 20.0 else 28.0
    val fatPart = ((m.bodyFatPct - fatLimit) / 5.0).coerceAtLeast(0.0)
    val agePart = ((age - 30) / 20.0).coerceAtLeast(0.0)
    val sexPart = if (male) 0.0 else 0.3
    return (1.0 + bmiPart * 1.8 + fatPart * 1.5 + agePart + sexPart)
        .roundToInt()
        .coerceIn(1, 20)
}

/** 单条记录 → 8 张卡片需要的展示模型。 */
fun buildBodyReport(
    measurement: BodyMeasurement,
    member: FamilyMember?,
    previous: BodyMeasurement?,
    isFirstRecord: Boolean,
): BodyReport {
    val hasBia = measurement.impedanceOhm > 0.0 && measurement.bodyFatPct > 0.0
    val readings = MetricKind.entries.associateWith { kind ->
        buildReading(measurement, member, kind, hasBia)
    }
    return BodyReport(
        measurement = measurement,
        member = member,
        readings = readings,
        hasBia = hasBia,
        weightDeltaKg = previous
            ?.takeIf { it.weightKg > 0.0 && measurement.weightKg > 0.0 }
            ?.let { measurement.weightKg - it.weightKg },
        isFirstRecord = isFirstRecord,
        visceralFatLevel = if (hasBia) estimateVisceralFatLevel(measurement, member) else null,
    )
}

/** 趋势图/卡片共用的数值读取；无 BIA 的指标返回 null。 */
fun metricNumericValue(
    m: BodyMeasurement,
    kind: MetricKind,
    member: FamilyMember? = null,
): Double? {
    if (kind != MetricKind.WEIGHT && kind != MetricKind.BMI &&
        (m.impedanceOhm <= 0.0 || m.bodyFatPct <= 0.0)
    ) {
        return null
    }
    return when (kind) {
        MetricKind.WEIGHT -> m.weightKg.takeIf { it > 0.0 }
        MetricKind.BMI -> m.bmi.takeIf { it > 0.0 }
        MetricKind.BODY_FAT -> m.bodyFatPct.takeIf { it > 0.0 }
        MetricKind.MUSCLE -> m.muscleKg.takeIf { it > 0.0 }
        MetricKind.WATER -> m.waterPct.takeIf { it > 0.0 }
        MetricKind.VISCERAL_FAT -> estimateVisceralFatLevel(m, member).toDouble()
        MetricKind.PROTEIN -> m.proteinPct.takeIf { it > 0.0 }
        MetricKind.BONE -> m.boneMassKg.takeIf { it > 0.0 }
    }
}

/**
 * 历史时间轴：单次 / ISO 周均 / 月均。返回**最新在前**，时间轴左侧为最近。
 * 聚合记录保留成员归属，但数值是组内有效值的算术平均。
 */
fun buildHistorySnapshots(
    history: List<BodyMeasurement>,
    memberId: String?,
    mode: HistoryMode,
    nowMs: Long = System.currentTimeMillis(),
    windowStartMs: Long? = null,
    windowEndMs: Long? = null,
): List<HistorySnapshot> {
    val filtered = history
        .filter { memberId == null || it.memberId == memberId }
        .filter { windowStartMs == null || it.measuredAtEpochMs >= windowStartMs }
        .filter { windowEndMs == null || it.measuredAtEpochMs < windowEndMs }
        .sortedByDescending { it.measuredAtEpochMs }
    if (filtered.isEmpty()) return emptyList()

    return when (mode) {
        HistoryMode.SINGLE -> filtered.map { m ->
            val date = Instant.ofEpochMilli(m.measuredAtEpochMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            HistorySnapshot(
                measurement = m,
                label = date.format(MM_DD_FORMAT),
                sublabel = Instant.ofEpochMilli(m.measuredAtEpochMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalTime()
                    .format(HH_MM_FORMAT),
                bucketStartMs = m.measuredAtEpochMs,
                sampleCount = 1,
            )
        }

        HistoryMode.WEEK -> {
            val zone = ZoneId.systemDefault()
            val grouped = filtered.groupBy { m ->
                val date = Instant.ofEpochMilli(m.measuredAtEpochMs).atZone(zone).toLocalDate()
                date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
            }
            grouped.entries
                .sortedByDescending { it.key }
                .map { (start, group) ->
                    val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                    HistorySnapshot(
                        measurement = aggregate(group, "week-$start", start),
                        label = date.format(MM_DD_FORMAT),
                        sublabel = "周均",
                        bucketStartMs = start,
                        sampleCount = group.size,
                    )
                }
        }

        HistoryMode.MONTH -> {
            val zone = ZoneId.systemDefault()
            val grouped = filtered.groupBy { m ->
                val date = Instant.ofEpochMilli(m.measuredAtEpochMs).atZone(zone).toLocalDate()
                YearMonth.from(date).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            }
            grouped.entries
                .sortedByDescending { it.key }
                .map { (start, group) ->
                    val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                    val monthKey = YearMonth.from(date).toString()
                    HistorySnapshot(
                        measurement = aggregate(group, "month-$monthKey", start),
                        label = "${date.monthValue}月",
                        sublabel = "月均",
                        bucketStartMs = start,
                        sampleCount = group.size,
                    )
                }
        }
    }
}

private fun buildReading(
    m: BodyMeasurement,
    member: FamilyMember?,
    kind: MetricKind,
    hasBia: Boolean,
): MetricReading {
    val male = member?.sex != Sex.FEMALE
    if (!hasBia && kind != MetricKind.WEIGHT && kind != MetricKind.BMI) {
        return MetricReading(
            kind = kind,
            title = titleOf(kind),
            valueText = "--",
            unit = unitOf(kind),
            numericValue = null,
            status = null,
            range = null,
        )
    }

    return when (kind) {
        MetricKind.WEIGHT -> MetricReading(
            kind = kind,
            title = "体重",
            valueText = format(m.weightKg, 2),
            unit = "kg",
            numericValue = m.weightKg.takeIf { it > 0.0 },
            status = null,
            range = null,
        )

        MetricKind.BMI -> {
            val hasBmi = m.bmi > 0.0
            MetricReading(
                kind = kind,
                title = "BMI",
                valueText = if (hasBmi) format(m.bmi, 1) else "--",
                unit = "",
                numericValue = m.bmi.takeIf { hasBmi },
                status = if (hasBmi) bmiStatus(m.bmi) else null,
                range = if (hasBmi) {
                    RangeBarSpec(
                        min = 15f,
                        standardStart = 18.5f,
                        standardEnd = 23.9f,
                        max = 35f,
                        value = m.bmi.toFloat(),
                    )
                } else {
                    null
                },
            )
        }

        MetricKind.BODY_FAT -> {
            val standardStart = if (male) 10f else 18f
            val standardEnd = if (male) 20f else 28f
            MetricReading(
                kind = kind,
                title = "体脂率",
                valueText = format(m.bodyFatPct, 1),
                unit = "%",
                numericValue = m.bodyFatPct.takeIf { it > 0.0 },
                status = fatStatus(m.bodyFatPct, male),
                range = RangeBarSpec(
                    min = 5f,
                    standardStart = standardStart,
                    standardEnd = standardEnd,
                    max = 40f,
                    value = m.bodyFatPct.toFloat(),
                ),
            )
        }

        MetricKind.MUSCLE -> {
            val low = (m.weightKg * if (male) 0.40 else 0.35).toFloat()
            val high = (m.weightKg * if (male) 0.60 else 0.55).toFloat()
            MetricReading(
                kind = kind,
                title = "肌肉量",
                valueText = format(m.muscleKg, 1),
                unit = "kg",
                numericValue = m.muscleKg.takeIf { it > 0.0 },
                status = muscleStatus(m.muscleKg, m.weightKg, male),
                range = RangeBarSpec(
                    min = 0f,
                    standardStart = low,
                    standardEnd = high,
                    max = (m.weightKg * 0.65).toFloat().coerceAtLeast(high + 1f),
                    value = m.muscleKg.toFloat(),
                ),
            )
        }

        MetricKind.WATER -> {
            val low = if (male) 55f else 45f
            val high = if (male) 65f else 60f
            MetricReading(
                kind = kind,
                title = "水分",
                valueText = format(m.waterPct, 1),
                unit = "%",
                numericValue = m.waterPct.takeIf { it > 0.0 },
                status = waterStatus(m.waterPct, male),
                range = RangeBarSpec(
                    min = 30f,
                    standardStart = low,
                    standardEnd = high,
                    max = 75f,
                    value = m.waterPct.toFloat(),
                ),
            )
        }

        MetricKind.VISCERAL_FAT -> {
            val level = estimateVisceralFatLevel(m, member)
            MetricReading(
                kind = kind,
                title = "内脏脂肪",
                valueText = level.toString(),
                unit = "级",
                numericValue = level.toDouble(),
                status = visceralStatus(level),
                range = RangeBarSpec(
                    min = 1f,
                    standardStart = 1f,
                    standardEnd = 9f,
                    max = 20f,
                    value = level.toFloat(),
                ),
            )
        }

        MetricKind.PROTEIN -> MetricReading(
            kind = kind,
            title = "蛋白质",
            valueText = format(m.proteinPct, 1),
            unit = "%",
            numericValue = m.proteinPct.takeIf { it > 0.0 },
            status = proteinStatus(m.proteinPct),
            range = RangeBarSpec(
                min = 10f,
                standardStart = 16f,
                standardEnd = 20f,
                max = 24f,
                value = m.proteinPct.toFloat(),
            ),
        )

        MetricKind.BONE -> {
            val target = boneTarget(m.weightKg, male)
            MetricReading(
                kind = kind,
                title = "骨量",
                valueText = format(m.boneMassKg, 1),
                unit = "kg",
                numericValue = m.boneMassKg.takeIf { it > 0.0 },
                status = boneStatus(m.boneMassKg, target),
                range = RangeBarSpec(
                    min = 1f,
                    standardStart = (target - 0.3).toFloat(),
                    standardEnd = (target + 0.3).toFloat(),
                    max = 5f,
                    value = m.boneMassKg.toFloat(),
                ),
            )
        }
    }
}

private fun titleOf(kind: MetricKind): String = when (kind) {
    MetricKind.WEIGHT -> "体重"
    MetricKind.BMI -> "BMI"
    MetricKind.BODY_FAT -> "体脂率"
    MetricKind.MUSCLE -> "肌肉量"
    MetricKind.WATER -> "水分"
    MetricKind.VISCERAL_FAT -> "内脏脂肪"
    MetricKind.PROTEIN -> "蛋白质"
    MetricKind.BONE -> "骨量"
}

private fun unitOf(kind: MetricKind): String = when (kind) {
    MetricKind.WEIGHT, MetricKind.MUSCLE, MetricKind.BONE -> "kg"
    MetricKind.BMI -> ""
    MetricKind.VISCERAL_FAT -> "级"
    else -> "%"
}

private fun format(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

private fun bmiStatus(bmi: Double): MetricStatus = when {
    bmi < 18.5 -> MetricStatus("偏瘦", MetricTone.WARN)
    bmi <= 23.9 -> MetricStatus("标准", MetricTone.GOOD)
    bmi < 28.0 -> MetricStatus("偏胖", MetricTone.WARN)
    else -> MetricStatus("肥胖", MetricTone.DANGER)
}

private fun fatStatus(fat: Double, male: Boolean): MetricStatus {
    val low = if (male) 10.0 else 18.0
    val high = if (male) 20.0 else 28.0
    val danger = high + 5.0
    return when {
        fat < low -> MetricStatus("偏低", MetricTone.WARN)
        fat <= high -> MetricStatus("标准", MetricTone.GOOD)
        fat <= danger -> MetricStatus("偏高", MetricTone.WARN)
        else -> MetricStatus("肥胖", MetricTone.DANGER)
    }
}

private fun muscleStatus(muscle: Double, weight: Double, male: Boolean): MetricStatus {
    if (weight <= 0.0) return MetricStatus("--", MetricTone.NEUTRAL)
    val pct = muscle / weight
    val low = if (male) 0.40 else 0.35
    val high = if (male) 0.60 else 0.55
    return when {
        pct < low -> MetricStatus("偏低", MetricTone.WARN)
        pct <= high -> MetricStatus("达标", MetricTone.GOOD)
        else -> MetricStatus("偏高", MetricTone.WARN)
    }
}

private fun waterStatus(water: Double, male: Boolean): MetricStatus {
    val low = if (male) 55.0 else 45.0
    val high = if (male) 65.0 else 60.0
    return when {
        water < low -> MetricStatus("偏低", MetricTone.WARN)
        water <= high -> MetricStatus("达标", MetricTone.GOOD)
        else -> MetricStatus("偏高", MetricTone.WARN)
    }
}

private fun visceralStatus(level: Int): MetricStatus = when {
    level <= 9 -> MetricStatus("达标", MetricTone.GOOD)
    level <= 14 -> MetricStatus("偏高", MetricTone.WARN)
    else -> MetricStatus("超标", MetricTone.DANGER)
}

private fun proteinStatus(protein: Double): MetricStatus = when {
    protein < 16.0 -> MetricStatus("偏低", MetricTone.WARN)
    protein <= 20.0 -> MetricStatus("达标", MetricTone.GOOD)
    else -> MetricStatus("偏高", MetricTone.WARN)
}

private fun boneTarget(weight: Double, male: Boolean): Double = if (male) {
    when {
        weight < 60.0 -> 2.5
        weight <= 75.0 -> 2.9
        else -> 3.2
    }
} else {
    when {
        weight < 45.0 -> 1.8
        weight <= 60.0 -> 2.2
        else -> 2.5
    }
}

private fun boneStatus(bone: Double, target: Double): MetricStatus = when {
    bone < target - 0.3 -> MetricStatus("偏低", MetricTone.WARN)
    bone <= target + 0.3 -> MetricStatus("正常", MetricTone.GOOD)
    else -> MetricStatus("偏高", MetricTone.WARN)
}

/** 周/月聚合：只对有效值求平均，保留成员归属与合成 id/时间。 */
private fun aggregate(
    group: List<BodyMeasurement>,
    id: String,
    bucketStartMs: Long,
): BodyMeasurement {
    fun avg(selector: (BodyMeasurement) -> Double): Double {
        val values = group.map(selector).filter { it > 0.0 }
        return if (values.isEmpty()) 0.0 else values.average()
    }

    val latest = group.maxByOrNull { it.measuredAtEpochMs }
    return BodyMeasurement(
        id = id,
        measuredAtEpochMs = bucketStartMs,
        weightKg = avg { it.weightKg },
        impedanceOhm = avg { it.impedanceOhm },
        bmi = avg { it.bmi },
        bodyFatPct = avg { it.bodyFatPct },
        muscleKg = avg { it.muscleKg },
        waterPct = avg { it.waterPct },
        proteinPct = avg { it.proteinPct },
        boneMassKg = avg { it.boneMassKg },
        memberId = latest?.memberId,
        memberNameSnapshot = latest?.memberNameSnapshot,
    )
}
