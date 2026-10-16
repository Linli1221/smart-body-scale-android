package com.example.dianzicheng.domain

/**
 * 电阻抗（BIA）体成分估算模型。
 *
 * 用户在「我的 → 应用设置」中切换模型，切换后对新一次称重生效：
 * 体脂率（以及由体脂率派生的水分、蛋白质、肌肉量）会按所选公式重新计算。
 *
 * 目前提供 3 种模型：
 * 1. [DEFAULT]      —— 内置通用模型（BMI + 年龄 + 阻抗加权），适配大多数脚踩式体脂秤；
 * 2. [WU_2015]      —— 亚洲人专属 F-F BIA 模型（Wu et al., 2015，以 DXA 为金标准开发）；
 * 3. [JEBB_2000]    —— 高加索人 / 通用修正模型（Jebb et al., 2000，以四室模型为金标准）。
 *
 * @property id          唯一标识符，用于持久化存储到 DataStore
 * @property displayName 界面展示的模型名称
 * @property brand       来源人群/机构标签
 * @property category    分类标签（用于对话框分组）
 * @property description 模型适用场景与特性简述
 * @property formulaText 界面展示的公式原文（含性别差异说明）
 * @property source      文献出处
 * @property fatMin      体脂率计算结果的下限（%），防止公式在极端输入下溢出
 * @property fatMax      体脂率计算结果的上限（%）
 */
enum class BiaModel(
    val id: String,
    val displayName: String,
    val brand: String,
    val category: String,
    val description: String,
    val formulaText: String,
    val source: String,
    val fatMin: Double,
    val fatMax: Double,
) {
    /** 内置通用模型：综合 BMI、年龄、阻抗三维加权，默认选项。 */
    DEFAULT(
        id = "DEFAULT",
        displayName = "内置通用模型 (BMI + 阻抗)",
        brand = "内置默认",
        category = "推荐",
        description = "综合 BMI、年龄与阻抗加权估算，适配大多数脚踩式体脂秤，结果偏保守稳定",
        formulaText = "男：0.18×BMI + 0.012×年龄 + 0.018×Z − 3.2\n" +
            "女：0.26×BMI + 0.011×年龄 + 0.020×Z − 2.5",
        source = "内置经验公式",
        fatMin = 5.0,
        fatMax = 55.0
    ),

    /**
     * 亚洲人专属去脂体重（FFM）模型，Wu et al., 2015。
     *
     * FFM = 13.055 + 0.204×体重 + 0.394×(身高²/Z) − 0.136×年龄 + 8.125×性别
     * 体脂率 = (1 − FFM / 体重) × 100%
     */
    WU_2015(
        id = "WU_2015",
        displayName = "亚洲人专属模型 (Wu 2015)",
        brand = "亚洲人群 / 台湾",
        category = "国际公认 F-F BIA 公式",
        description = "以 DXA 为金标准开发的去脂体重公式，最适合亚洲人体型，脚踩式测量首选",
        formulaText = "FFM = 13.055 + 0.204×体重 + 0.394×(身高²/Z) − 0.136×年龄 + 8.125×性别\n" +
            "体脂率 = (1 − FFM ÷ 体重) × 100%\n" +
            "(男 = 1，女 = 0；身高单位 cm，体重 kg)",
        source = "Wu et al., 2015（DXA 金标准）",
        fatMin = 3.0,
        fatMax = 65.0
    ),

    /**
     * 高加索人 / 通用修正模型，Jebb et al., 2000。
     *
     * %FM = −156.1 − 89.1×ln(身高m) + 45.6×ln(体重kg) + 0.120×年龄 + 0.0494×Z
     * 女性额外 + 19.6×ln(身高m)
     */
    JEBB_2000(
        id = "JEBB_2000",
        displayName = "通用修正模型 (Jebb 2000)",
        brand = "高加索人群 / 通用",
        category = "国际公认 F-F BIA 公式",
        description = "以四室模型为金标准重新推导的自然对数公式，修正了市售脚踩秤内置公式的系统误差",
        formulaText = "%FM = −156.1 − 89.1×ln(身高m) + 45.6×ln(体重kg) + 0.120×年龄 + 0.0494×Z\n" +
            "女性需额外 + 19.6×ln(身高m)",
        source = "Jebb et al., 2000（四室模型金标准）",
        fatMin = 3.0,
        fatMax = 65.0
    );

    companion object {
        /**
         * 根据持久化存储的 ID 还原对应的枚举项；若为 null、未匹配，一律回退到 [DEFAULT]。
         */
        fun fromId(id: String?): BiaModel {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
        }

        /** 获取所有可用的分类列表（用于对话框分组展示）。 */
        fun categories(): List<String> = listOf("推荐", "国际公认 F-F BIA 公式")

        /**
         * 按分类分组的所有模型映射表（保持预定义分类顺序）。
         *
         * 未在 [categories] 中显式声明分类的模型按枚举顺序追加到末尾，
         * 保证未来新增分组时不会丢失任何模型。
         */
        fun grouped(): Map<String, List<BiaModel>> {
            val groups = entries.groupBy { it.category }
            val sortedMap = LinkedHashMap<String, List<BiaModel>>()
            for (cat in categories()) {
                groups[cat]?.let { sortedMap[cat] = it }
            }
            groups.forEach { (k, v) ->
                if (!sortedMap.containsKey(k)) sortedMap[k] = v
            }
            return sortedMap
        }
    }
}
