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

/**
 * 身体指标展示顺序的文本编解码器。
 *
 * 存储格式为逗号分隔的枚举名，如 `WEIGHT,BMI,BODY_FAT,MUSCLE,PROTEIN,BONE`。
 * 设计为可被 JVM 纯单元测试覆盖，因此不依赖任何 Android API。
 *
 * 解码对脏数据保持宽容：
 * - 空串 / null 返回 [BodyMetric.defaultOrder]；
 * - 无法识别的名称直接跳过，不会让整个顺序失效；
 * - 未出现在编码串中的指标按 [BodyMetric.defaultOrder] 的相对次序补齐，
 *   保证新增指标后旧数据仍能完整展示；
 * - 重复项只保留首次出现的位置。
 */
object BodyMetricOrderCodec {

    /** 编码为用户可持久化的字符串；空列表时返回空串。 */
    fun encode(order: List<BodyMetric>): String =
        order.joinToString(",") { it.name }

    /**
     * 解析持久化字符串为指标顺序。
     *
     * @param raw 持久化的原始值，可为 null 或空串
     * @return 始终返回包含全部 6 项指标的列表，长度恒为 [BodyMetric.values].size
     */
    fun decode(raw: String?): List<BodyMetric> {
        if (raw.isNullOrBlank()) return BodyMetric.defaultOrder

        val byName = BodyMetric.values().associateBy { it.name }
        val resolved = LinkedHashSet<BodyMetric>()

        raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { name -> byName[name]?.let { resolved.add(it) } }

        // 补齐缺失项：沿用默认顺序的相对位置，避免新指标被排到末尾之外
        BodyMetric.defaultOrder.forEach { resolved.add(it) }

        return resolved.toList()
    }

    /**
     * 将列表中的某一项移动到指定下标。
     *
     * 下标越界时先收敛到合法范围，保证调用方无需自行校验。
     *
     * @param order 原始顺序
     * @param from  被移动项的当前下标
     * @param to    目标下标
     * @return 移动后的新列表；输入为空时原样返回
     */
    fun move(order: List<BodyMetric>, from: Int, to: Int): List<BodyMetric> {
        if (order.isEmpty()) return order
        val fromIndex = from.coerceIn(order.indices)
        val toIndex = to.coerceIn(order.indices)
        if (fromIndex == toIndex) return order

        val mutable = order.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        return mutable
    }
}
