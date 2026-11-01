package com.example.dianzicheng.domain

/**
 * 身体成分指标枚举。
 *
 * 每一项都对应 [BodyMeasurement] 中的一个可展示指标，历史页的趋势卡片与
 * 历史数据页的汇总均以此为单位渲染。用户可自定义这些指标的展示顺序。
 *
 * @property label      界面展示的指标名
 * @property unit       单位后缀，无单位时为空串
 * @property decimals   数值保留的小数位数
 * @property requiresBia 该指标是否依赖有效阻抗（未赤脚称重时为 0，展示为 --）
 */
enum class BodyMetric(
    val label: String,
    val unit: String,
    val decimals: Int,
    val requiresBia: Boolean,
) {
    WEIGHT("体重", "kg", 2, false),
    BMI("BMI", "", 1, false),
    BODY_FAT("体脂率", "%", 1, true),
    MUSCLE("肌肉量", "kg", 1, true),
    PROTEIN("蛋白质", "%", 1, true),
    BONE("骨量", "kg", 1, true);

    /** 该指标在某次测量中的取值；依赖 BIA 但本次未测出时返回 null。 */
    fun valueOf(measurement: BodyMeasurement): Double? = when (this) {
        WEIGHT -> measurement.weightKg.takeIf { it > 0.0 }
        BMI -> measurement.bmi.takeIf { it > 0.0 }
        BODY_FAT -> measurement.bodyFatPct.takeIf { hasBia(measurement) }
        MUSCLE -> measurement.muscleKg.takeIf { hasBia(measurement) }
        PROTEIN -> measurement.proteinPct.takeIf { hasBia(measurement) }
        BONE -> measurement.boneMassKg.takeIf { hasBia(measurement) }
    }

    private fun hasBia(measurement: BodyMeasurement): Boolean =
        measurement.impedanceOhm > 0.0 && measurement.bodyFatPct > 0.0

    companion object {
        /** 默认展示顺序：体重作为主指标置首，其余按常用度排列。 */
        val defaultOrder: List<BodyMetric> = listOf(WEIGHT, BMI, BODY_FAT, MUSCLE, PROTEIN, BONE)
    }
}
