package com.example.dianzicheng.domain

/**
 * 体脂秤支持的型号与协议分类枚举。
 *
 * 本客户端目前仅支持以下两家体脂秤：
 * 1. 薄荷健康 / 沃莱 (Yolanda) 系列；
 * 2. 小米体脂秤 2 代 / 米家体脂秤。
 *
 * 除上述两家外，其它品牌的专有协议支持已全部移除。
 *
 * @property id             唯一标识符，用于持久化存储到 DataStore
 * @property displayName    在界面上呈现的友好型号全称
 * @property brand          品牌厂商名称
 * @property category       分类标签（如 "推荐", "热门品牌"）
 * @property description    型号与协议特性简述
 * @property wakeGuidance   针对该型号的具体唤醒与称重指引
 * @property protocolName   对应的协议名称
 */
enum class ScaleModel(
    val id: String,
    val displayName: String,
    val brand: String,
    val category: String,
    val description: String,
    val wakeGuidance: String,
    val protocolName: String
) {
    AUTO(
        id = "AUTO",
        displayName = "自动识别 (薄荷健康 / 小米)",
        brand = "薄荷健康 / 小米",
        category = "推荐",
        description = "自动匹配薄荷健康/沃莱与小米体脂秤 2 代的蓝牙协议，无需手动选择",
        wakeGuidance = "请将秤放置于平整地面上，轻踩秤面唤醒屏幕即可",
        protocolName = "Auto-Detect"
    ),
    BOOHEE_YOLANDA(
        id = "BOOHEE_YOLANDA",
        displayName = "薄荷健康 / 沃莱 (Yolanda / 轻牛)",
        brand = "薄荷健康 / 沃莱",
        category = "热门品牌",
        description = "采用 0xAC 8 字节流式与 20 字节复合帧协议，支持 BIA 生物电阻抗与体脂率计算",
        wakeGuidance = "请踩秤亮屏，若测体脂需光脚站立在金属电极片上，稳定后自动回传阻抗",
        protocolName = "Yolanda/Boohee"
    ),
    XIAOMI_SCALE_2(
        id = "XIAOMI_SCALE_2",
        displayName = "小米体脂秤 2 / 米家体脂秤",
        brand = "小米 / 米家",
        category = "热门品牌",
        description = "蓝牙广播 0x181B / 0x2A9C 13 字节协议，即踩即读无需配对，支持 10+ 项体成分",
        wakeGuidance = "赤脚踩上秤面唤醒，保持站立直至显示屏体脂测试进度灯全部亮起",
        protocolName = "Xiaomi Mi Scale 2"
    );

    companion object {
        /**
         * 根据持久化存储的 ID 还原对应的枚举项；若为 null、未匹配或指向已移除的旧型号，
         * 一律回退到 [AUTO]。
         */
        fun fromId(id: String?): ScaleModel {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
        }

        /**
         * 获取所有可用的分类列表（用于 UI 分类选项卡或分组展示）。
         */
        fun categories(): List<String> {
            return listOf("推荐", "热门品牌")
        }

        /**
         * 按分类分组的所有型号映射表（保持预定义分类顺序）。
         */
        fun grouped(): Map<String, List<ScaleModel>> {
            val order = categories()
            val groups = entries.groupBy { it.category }
            val sortedMap = LinkedHashMap<String, List<ScaleModel>>()
            for (cat in order) {
                groups[cat]?.let { sortedMap[cat] = it }
            }
            groups.forEach { (k, v) ->
                if (!sortedMap.containsKey(k)) sortedMap[k] = v
            }
            return sortedMap
        }
    }
}
