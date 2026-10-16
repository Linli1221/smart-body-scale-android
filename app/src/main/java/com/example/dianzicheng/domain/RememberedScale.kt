package com.example.dianzicheng.domain

/**
 * 已记住的一台体脂秤。
 *
 * 本应用支持同时记住多台体脂秤（如家中一台、父母家一台、备用一台），
 * 每台设备以蓝牙 MAC 地址为唯一标识持久化在 DataStore 中。
 *
 * @property mac           蓝牙 MAC 地址，已归一化为大写形式（唯一标识）
 * @property name          设备显示名称（如 "小米体脂秤 2 (A1B2C)"），用户绑定时取自重扫描结果
 * @property modelId       绑定时识别出的型号 ID（见 [ScaleModel.id]），仅用于界面展示
 * @property protocolName  绑定时识别出的协议名称（如 "Xiaomi Mi Scale 2"），仅用于界面展示
 * @property lastUsedAtMs  最近一次被选中/连接的时间（Unix 毫秒），用于列表排序；0 表示从未使用
 */
data class RememberedScale(
    val mac: String,
    val name: String,
    val modelId: String = ScaleModel.AUTO.id,
    val protocolName: String = "",
    val lastUsedAtMs: Long = 0L,
) {
    /** 归一化后的设备型号枚举（未知或已移除的型号回退为 [ScaleModel.AUTO]）。 */
    val model: ScaleModel get() = ScaleModel.fromId(modelId)

    /** 设备列表中展示的补充说明：优先协议名，其次具体型号名，都为空时返回空串。 */
    val detail: String
        get() = protocolName.ifBlank {
            if (modelId != ScaleModel.AUTO.id) model.displayName else ""
        }
}

/** 把蓝牙 MAC 地址归一化为「去首尾空格 + 大写」形式，用于跨来源的等价比较。 */
fun normalizeMacAddress(mac: String?): String = mac?.trim()?.uppercase() ?: ""

/** 判断两个 MAC 地址是否指向同一台设备（忽略大小写与首尾空格，空值视为不相等）。 */
fun sameMacAddress(a: String?, b: String?): Boolean {
    val left = normalizeMacAddress(a)
    val right = normalizeMacAddress(b)
    return left.isNotEmpty() && left == right
}

/**
 * 「已记住的体脂秤」完整快照：设备列表 + 当前使用中的设备 MAC。
 *
 * 由 [buildScaleMemory] 从持久化原始值（含旧版单设备字段）解析得到。
 */
data class ScaleMemory(
    val scales: List<RememberedScale>,
    val activeMac: String?
) {
    /** 当前使用中的设备；若记录的 MAC 已不存在，则回退为列表中的第一台。 */
    val active: RememberedScale?
        get() = scales.firstOrNull { sameMacAddress(it.mac, activeMac) } ?: scales.firstOrNull()

    val isEmpty: Boolean get() = scales.isEmpty()
}

/**
 * [ScaleMemory] 的文本编解码器。
 *
 * 存储格式为「首行固定表头 + 每行一台设备」的行文本，字段之间以制表符分隔：
 * ```
 * SCALES_V1
 * AA:BB:CC:DD:EE:01\t小米体脂秤 2\tXIAOMI_SCALE_2\tXiaomi Mi Scale 2\t1712345678901
 * ```
 *
 * 选择自研行文本而非 org.json 的原因：
 * 1. 编解码逻辑可被 JVM 纯单元测试直接覆盖（Android 单元测试类路径下的 org.json 是空壳桩）；
 * 2. 无需引入任何新依赖；
 * 3. 设备名可能包含任意字符，因此对 \\ 、\t、\n、\r 做了转义，保证往返无损。
 *
 * 解码对脏数据保持宽容：无法解析的行、MAC 为空的条目会被跳过，
 * 缺失字段回落到默认值，重复 MAC（忽略大小写）只保留第一条。
 */
object RememberedScaleCodec {

    /** 格式表头，便于后续版本演进时识别并迁移。 */
    private const val HEADER = "SCALES_V1"

    private const val LINE_SEPARATOR = '\n'
    private const val FIELD_SEPARATOR = '\t'

    /** 设备名缺失时的兜底显示名。 */
    private const val DEFAULT_NAME = "体脂秤"

    /** 把设备列表编码为单行文本；空列表编码为空串（调用方据此移除存储键）。 */
    fun encode(scales: List<RememberedScale>): String {
        if (scales.isEmpty()) return ""
        val builder = StringBuilder()
        builder.append(HEADER)
        for (scale in scales) {
            builder.append(LINE_SEPARATOR)
            builder.append(escape(scale.mac)).append(FIELD_SEPARATOR)
            builder.append(escape(scale.name)).append(FIELD_SEPARATOR)
            builder.append(escape(scale.modelId)).append(FIELD_SEPARATOR)
            builder.append(escape(scale.protocolName)).append(FIELD_SEPARATOR)
            builder.append(scale.lastUsedAtMs.toString())
        }
        return builder.toString()
    }

    /** 解析设备列表；入参为 null / 空白 / 无法识别时返回空列表。 */
    fun decode(raw: String?): List<RememberedScale> {
        if (raw.isNullOrBlank()) return emptyList()
        val result = ArrayList<RememberedScale>()
        val seenMacs = HashSet<String>()
        for (line in raw.split(LINE_SEPARATOR)) {
            // 兼容 CRLF 换行：去掉行尾的回车后再解析
            val lineBody = if (line.endsWith("\r")) line.dropLast(1) else line
            val trimmed = lineBody.trim()
            if (trimmed.isEmpty() || trimmed == HEADER) continue
            val parts = lineBody.split(FIELD_SEPARATOR)
            if (parts.size < 2) continue
            val mac = normalizeMacAddress(unescape(parts[0]))
            if (mac.isEmpty()) continue
            if (!seenMacs.add(mac)) continue
            result.add(
                RememberedScale(
                    mac = mac,
                    name = unescape(parts.getOrNull(1).orEmpty()).ifBlank { DEFAULT_NAME },
                    modelId = unescape(parts.getOrNull(2).orEmpty()).ifBlank { ScaleModel.AUTO.id },
                    protocolName = unescape(parts.getOrNull(3).orEmpty()),
                    lastUsedAtMs = parts.getOrNull(4)?.trim()?.toLongOrNull() ?: 0L
                )
            )
        }
        return result
    }

    private fun escape(value: String): String {
        if (value.isEmpty()) return value
        val builder = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                '\\' -> builder.append("\\\\")
                '\t' -> builder.append("\\t")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    private fun unescape(value: String): String {
        if (value.isEmpty() || value.indexOf('\\') < 0) return value
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    '\\' -> {
                        builder.append('\\')
                        index += 2
                    }
                    't' -> {
                        builder.append('\t')
                        index += 2
                    }
                    'n' -> {
                        builder.append('\n')
                        index += 2
                    }
                    'r' -> {
                        builder.append('\r')
                        index += 2
                    }
                    else -> {
                        builder.append(ch)
                        index += 1
                    }
                }
            } else {
                builder.append(ch)
                index += 1
            }
        }
        return builder.toString()
    }
}

/**
 * 已记住设备列表的纯函数操作集合（不依赖 Android，全部可单元测试）。
 */
object ScaleMemoryLogic {

    /** 最多记住的设备数量，超出后丢弃列表末尾（即最久未使用）的设备。 */
    const val MAX_REMEMBERED = 8

    private const val DEFAULT_NAME = "体脂秤"

    /**
     * 插入或更新一台设备，并把它移动到列表首位（最近使用优先）。
     *
     * 若入参 [incoming] 未携带有效字段（名称为空、型号为空、lastUsedAtMs 为 0），
     * 则沿用同 MAC 旧记录中的对应字段，避免「只带 MAC 的补充登记」把已有信息抹掉。
     */
    fun upsert(scales: List<RememberedScale>, incoming: RememberedScale): List<RememberedScale> {
        val mac = normalizeMacAddress(incoming.mac)
        if (mac.isEmpty()) return scales
        val previous = scales.firstOrNull { sameMacAddress(it.mac, mac) }
        val name = incoming.name.trim().ifBlank { previous?.name.orEmpty() }.ifBlank { DEFAULT_NAME }
        // 型号合并规则：入参为空则沿用旧值；入参为 AUTO（本次未识别出具体型号）时不覆盖已有的具体型号，
        // 避免「重新绑定同一台秤、但本次未识别出型号」把此前记住的型号降级为 AUTO
        val requestedModelId = incoming.modelId.ifBlank { previous?.modelId.orEmpty() }.ifBlank { ScaleModel.AUTO.id }
        val previousModelId = previous?.modelId.orEmpty()
        val modelId = if (requestedModelId == ScaleModel.AUTO.id && previousModelId.isNotBlank()) {
            previousModelId
        } else {
            requestedModelId
        }
        val protocolName = incoming.protocolName.ifBlank { previous?.protocolName.orEmpty() }
        val lastUsedAtMs = if (incoming.lastUsedAtMs > 0L) {
            incoming.lastUsedAtMs
        } else {
            previous?.lastUsedAtMs ?: 0L
        }
        val normalized = RememberedScale(
            mac = mac,
            name = name,
            modelId = modelId,
            protocolName = protocolName,
            lastUsedAtMs = lastUsedAtMs
        )
        val remaining = scales.filterNot { sameMacAddress(it.mac, mac) }
        val merged = ArrayList<RememberedScale>(remaining.size + 1)
        merged.add(normalized)
        merged.addAll(remaining)
        return if (merged.size > MAX_REMEMBERED) merged.subList(0, MAX_REMEMBERED).toList() else merged
    }

    /**
     * 幂等登记：设备已存在时**原样返回**，绝不覆盖已有名称/型号；
     * 仅当列表中不存在该 MAC 时才追加一条记录。
     */
    fun ensure(scales: List<RememberedScale>, incoming: RememberedScale): List<RememberedScale> {
        val mac = normalizeMacAddress(incoming.mac)
        if (mac.isEmpty()) return scales
        if (scales.any { sameMacAddress(it.mac, mac) }) return scales
        return upsert(scales, incoming)
    }

    /** 移除指定 MAC 的设备；MAC 为空或不存在时原样返回。 */
    fun remove(scales: List<RememberedScale>, mac: String?): List<RememberedScale> {
        val target = normalizeMacAddress(mac)
        if (target.isEmpty()) return scales
        return scales.filterNot { sameMacAddress(it.mac, target) }
    }

    /** 刷新指定设备的最近使用时间并置顶；设备不存在时原样返回。 */
    fun markUsed(scales: List<RememberedScale>, mac: String?, atMs: Long): List<RememberedScale> {
        val target = normalizeMacAddress(mac)
        if (target.isEmpty()) return scales
        val existing = scales.firstOrNull { sameMacAddress(it.mac, target) } ?: return scales
        return upsert(scales, existing.copy(lastUsedAtMs = atMs))
    }

    /** 解析当前使用中的 MAC：优先采用 [preferredMac]（若仍在列表内），否则回退为列表第一台。 */
    fun resolveActive(scales: List<RememberedScale>, preferredMac: String?): String? {
        if (scales.isEmpty()) return null
        val preferred = normalizeMacAddress(preferredMac)
        if (preferred.isNotEmpty()) {
            val matched = scales.firstOrNull { sameMacAddress(it.mac, preferred) }
            if (matched != null) return matched.mac
        }
        return scales.first().mac
    }

    /**
     * 旧版单设备数据迁移：把历史遗留的 PAIRED_MAC / PAIRED_DEVICE_NAME 转成一台设备记录。
     * MAC 为空时返回空列表（表示旧数据不存在）。
     */
    fun migrateLegacy(legacyMac: String?, legacyName: String?): List<RememberedScale> {
        val mac = normalizeMacAddress(legacyMac)
        if (mac.isEmpty()) return emptyList()
        return listOf(
            RememberedScale(
                mac = mac,
                name = legacyName?.trim().orEmpty().ifBlank { DEFAULT_NAME },
                modelId = ScaleModel.AUTO.id,
                protocolName = "",
                lastUsedAtMs = 0L
            )
        )
    }
}

/**
 * 从持久化原始值构建完整的 [ScaleMemory] 快照。
 *
 * 解析顺序：
 * 1. 优先使用新版多设备存储（[rawList]）；
 * 2. 新版为空时，从旧版单设备字段（[legacyMac] / [legacyName]）迁移出唯一一台设备，
 *    保证老用户升级后原有体脂秤不会丢失；
 * 3. 两者都为空时返回空记忆（未绑定任何体脂秤）。
 *
 * 当前使用中的设备优先取 [rawActiveMac]，其次回退到旧版 [legacyMac]，
 * 若都不在列表中则取列表第一台。
 */
fun buildScaleMemory(
    rawList: String?,
    rawActiveMac: String?,
    legacyMac: String?,
    legacyName: String?
): ScaleMemory {
    val decoded = RememberedScaleCodec.decode(rawList)
    val scales = if (decoded.isNotEmpty()) decoded else ScaleMemoryLogic.migrateLegacy(legacyMac, legacyName)
    if (scales.isEmpty()) return ScaleMemory(emptyList(), null)
    val preferred = normalizeMacAddress(rawActiveMac).ifEmpty { normalizeMacAddress(legacyMac) }
    return ScaleMemory(scales, ScaleMemoryLogic.resolveActive(scales, preferred))
}
