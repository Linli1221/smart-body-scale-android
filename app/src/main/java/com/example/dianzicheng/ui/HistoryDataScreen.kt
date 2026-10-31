package com.example.dianzicheng.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.ui.theme.电子秤Theme
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 应用自有语义色：健康区间「标准」绿（非 Miuix token，保持原产品色值，按 MIUIX_STYLE §2 收敛为命名常量）。 */
private val HealthGreen = Color(0xFF4CAF50)

/**
 * 历史数据页：汇总展示所有测量记录中的身体成分指标。
 *
 * 页面自上而下分为三块：
 * 1. **概览卡片**：总记录数、测量天数跨度、平均体重；
 * 2. **指标数据卡**：BMI / 体脂率 / 肌肉量 / 蛋白质 / 骨量 各一张数据卡片，
 *    展示最新值、平均值与极值区间（不画走势线，趋势由历史页的趋势卡片承担）；
 * 3. **全部记录**：按时间倒序列出每一次测量（每行一条，含全部指标）。
 *
 * 无 BIA 数据（未赤脚称重）的记录中，体脂率等指标值为 0，构建统计序列时直接过滤，
 * 不参与计算，对应卡片显示「暂无有效数据」。
 */
@Composable
fun HistoryDataScreen(
    history: List<BodyMeasurement>,
    members: List<FamilyMember> = emptyList(),
    onDelete: (BodyMeasurement) -> Unit = {},
    onNavigateBack: () -> Unit
) {
    // 时间正序：首条为最早一次，末条为最新一次
    val sorted = remember(history) { history.sortedBy { it.measuredAtEpochMs } }

    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()) }
    var measurementToDelete by remember { mutableStateOf<BodyMeasurement?>(null) }

    // 时间倒序视图（最新在最上）：asReversed() 返回视图而非拷贝，且仅在 sorted 变化时重建，
    // 避免每次重组都分配一份全新的倒序列表。
    // 注意：必须在这里（@Composable 作用域）计算，不能放进 LazyColumn 的 LazyListScope 里。
    val reversedMeasurements = remember(sorted) { sorted.asReversed() }

    // 每个列表项的日期字符串预格式化一次，避免每次重组都分配 Date + format
    val dateStrings = remember(sorted) {
        sorted.associate { it.id to dateFormat.format(Date(it.measuredAtEpochMs)) }
    }

    // 五项身体成分的统计序列（0 值 = 未测出，已过滤）
    val summaries = remember(sorted) {
        listOf(
            MetricSummary("BMI", "", 1, sorted.map { it.bmi }.filter { it > 0.0 }),
            MetricSummary("体脂率", "%", 1, sorted.map { it.bodyFatPct }.filter { it > 0.0 }),
            MetricSummary("肌肉量", "kg", 1, sorted.map { it.muscleKg }.filter { it > 0.0 }),
            MetricSummary("蛋白质", "%", 1, sorted.map { it.proteinPct }.filter { it > 0.0 }),
            MetricSummary("骨量", "kg", 1, sorted.map { it.boneMassKg }.filter { it > 0.0 }),
        )
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "历史数据",
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(
                    "暂无历史数据",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
            return@Scaffold
        }

        // 卡片自持水平 12dp 外边距（Example 组合证据）；SmallTitle 默认 28dp 缩进与卡内文本（12+16）对齐
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 1. 概览：总记录数 / 天数跨度 / 平均体重 ──
            item(key = "overview") {
                val daySpan =
                    ((sorted.last().measuredAtEpochMs - sorted.first().measuredAtEpochMs) / 86_400_000L).toInt()
                val avgWeight = sorted.map { it.weightKg }.average()

                HistorySummaryCard(
                    items = listOf(
                        HistorySummaryItem("总记录数", "${sorted.size} 次"),
                        HistorySummaryItem("测量跨度", if (daySpan > 0) "$daySpan 天" else "单日"),
                        HistorySummaryItem("平均体重", String.format("%.1f kg", avgWeight))
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            // ── 2. 指标数据卡：五项身体成分各一张 ──
            item(key = "summary_header") {
                // 分组标签用 SmallTitle（自带 subtitle 字阶与标签内边距）
                SmallTitle(
                    text = "指标数据",
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            itemsIndexed(summaries, key = { _, summary -> summary.label }) { _, summary ->
                MetricDataCard(summary = summary, modifier = Modifier.padding(horizontal = 12.dp))
            }

            // ── 3. 全部记录（时间倒序，最新在最上） ──
            item(key = "list_header") {
                // 分组标签用 SmallTitle；记录条数沿用原标签文案，内容不变
                SmallTitle(
                    text = "全部记录（${sorted.size} 次）",
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            itemsIndexed(reversedMeasurements, key = { _, m -> m.id }) { index, measurement ->
                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ModernMeasurementCard(
                        measurement = measurement,
                        dateStr = dateStrings[measurement.id] ?: "",
                        onDelete = { measurementToDelete = measurement },
                        onNavigateToDetail = {},
                        isMale = members.firstOrNull { it.id == measurement.memberId }?.sex == Sex.MALE
                    )
                }
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // 删除确认弹窗：确认后同步删除本地与 Health Connect 记录，
    // history Flow 自动推送更新，本页概览与数据卡随之刷新
    val deleteTarget = measurementToDelete
    if (deleteTarget != null) {
        OverlayDialog(
            show = true,
            title = "确认删除",
            summary = "确定要删除 ${dateFormat.format(Date(deleteTarget.measuredAtEpochMs))} 的 " +
                "${String.format("%.2f", deleteTarget.weightKg)} kg 测量记录吗？此操作无法撤销。",
            // 产品约定：破坏性确认正文用全强度文字（组件默认 summaryColor 为次要灰阶）
            summaryColor = MiuixTheme.colorScheme.onSurface,
            onDismissRequest = { measurementToDelete = null }
        ) {
            // 操作行参照 Example component/DialogSection.kt：等宽 TextButton + 20dp 间隔、右对齐主操作
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    text = "取消",
                    onClick = { measurementToDelete = null },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(20.dp))
                // 破坏性操作：文字 error（MIUIX_STYLE §5）
                TextButton(
                    text = "删除",
                    onClick = {
                        onDelete(deleteTarget)
                        measurementToDelete = null
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error)
                )
            }
        }
    }
}

/** 概览卡中的单个数据项（标签 + 数值）。 */
data class HistorySummaryItem(val label: String, val value: String)

/** 概览卡片：横向排布三枚关键统计。 */
@Composable
fun HistorySummaryCard(items: List<HistorySummaryItem>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            items.forEach { item ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = item.label,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // 概览统计数值：body1 档（就近原 16sp），层级由字号 + 灰阶表达
                    Text(
                        text = item.value,
                        style = MiuixTheme.textStyles.body1
                    )
                }
            }
        }
    }
}

/**
 * 单个指标数据卡片的数据源。
 *
 * @param label    指标名（如「BMI」「体脂率」）
 * @param unit     单位后缀，无单位传空串
 * @param decimals 数值小数位数
 * @param values   按时间正序的有效测量值序列（0 值已在调用侧过滤）
 */
data class MetricSummary(
    val label: String,
    val unit: String,
    val decimals: Int,
    val values: List<Double>
)

/**
 * 单指标数据卡片。
 *
 * 顶部为指标名 + 最新值；下方三列等宽展示 平均值 / 最高 / 最低，
 * 让用户在不翻逐条记录的前提下掌握该指标的整体水平与波动区间。
 *
 * 不着色、不标注方向：BMI 偏高是负面、肌肉量偏高却是正面，
 * 用红绿臆断好坏会误导用户，因此统一用中性色呈现。
 */
@Composable
fun MetricDataCard(summary: MetricSummary, modifier: Modifier = Modifier) {
    val fmt = "%.${summary.decimals}f"
    val latest = summary.values.lastOrNull()
    val average = summary.values.average()
    val maxValue = summary.values.maxOrNull()
    val minValue = summary.values.minOrNull()

    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = summary.label,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.weight(1f)
                )
                // 指标最新值：产品数据读数，title 档按视觉层级就近取 title3（原 22sp，次级读数）
                Text(
                    text = if (latest != null) String.format(fmt, latest) + summary.unit else "--",
                    style = MiuixTheme.textStyles.title3,
                    color = if (latest != null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (summary.values.isEmpty()) {
                Text(
                    text = "暂无有效数据（需赤脚称重）",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricStatCell("平均值", String.format(fmt, average) + summary.unit, modifier = Modifier.weight(1f))
                    MetricStatCell("最高", String.format(fmt, maxValue!!) + summary.unit, modifier = Modifier.weight(1f))
                    MetricStatCell("最低", String.format(fmt, minValue!!) + summary.unit, modifier = Modifier.weight(1f))
                    MetricStatCell("测量次数", "${summary.values.size} 次", modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 数据卡片中的单列统计值（标签在上、数值在下，居中）。 */
@Composable
fun MetricStatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
/**
 * 逐条记录卡片中的单个指标小卡片。
 *
 * 每个指标独立成卡（浅色容器 + 圆角）：上方是指标名、下方是数值，
 * 有健康区间结论时再补一枚状态标签（如 BMI「标准」、体脂率「偏高」）。
 * 数据缺失（未赤脚称重、无阻抗）时数值显示为 --，且不展示状态标签。
 */
@Composable
fun HistoryMetricCard(
    label: String,
    value: String,
    status: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp)) // 小件 12dp 档（MIUIX_STYLE §3）
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) // 淡底色小卡（真实透明度需求）
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body1,
            color = if (value == "--") MiuixTheme.colorScheme.onSurfaceSecondary else MiuixTheme.colorScheme.onSurface,
            maxLines = 1
        )
        if (status != null) {
            // 「标准」绿为应用自有的健康区间语义色（非主题 token），收敛为文件顶部 HealthGreen 命名常量
            val statusColor = if (status == "标准") HealthGreen else MiuixTheme.colorScheme.error
            Surface(
                color = statusColor.copy(alpha = 0.1f), // 淡底色徽标（真实透明度需求）
                shape = RoundedCornerShape(8.dp), // 徽标 8dp 档（MIUIX_STYLE §3）
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = status,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    style = MiuixTheme.textStyles.footnote2,
                    color = statusColor
                )
            }
        }
    }
}

@Composable
fun ModernMeasurementCard(
    measurement: BodyMeasurement,
    dateStr: String,
    onDelete: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    /** 该记录所属成员是否为男性，用于体脂率健康区间判断（男女标准不同） */
    isMale: Boolean = true
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToDetail(measurement.id) }
    ) {
        // 记录卡用 Card 默认形态（16dp squircle + surfaceContainer）；16dp 内边距与 SmallTitle 标签缩进对齐
        Column(modifier = Modifier.padding(16.dp)) {
            // 头部：左侧日期 + 成员标签，右侧删除按钮；体重由下方六指标卡片统一呈现
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateStr,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = onDelete) {
                    Icon(
                        MiuixIcons.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                }
            }

            // Member Name tag if present
            if (measurement.memberNameSnapshot != null) {
                Surface(
                    color = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), // 淡底色徽标（真实透明度需求）
                    shape = RoundedCornerShape(8.dp) // 徽标 8dp 档（MIUIX_STYLE §3）
                ) {
                    Text(
                        text = measurement.memberNameSnapshot,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            val hasBia = measurement.impedanceOhm > 0.0 && measurement.bodyFatPct > 0.0
            // 卡片式指标区：左侧体重主数值，右侧 BMI / 体脂率 / 肌肉量 / 蛋白质 / 骨量 五张小卡
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 体重：作为本次测量的主指标单独成块，用大号数字突出
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .clip(RoundedCornerShape(12.dp)) // 高亮小卡 12dp 档（MIUIX_STYLE §3）
                        .background(MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) // 主指标淡底（真实透明度需求）
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "体重",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // 体重主读数（产品核心数据）：title 档按视觉层级就近取 title2（原 26sp，保持卡片内最大读数）
                    Text(
                        text = String.format("%.2f", measurement.weightKg),
                        style = MiuixTheme.textStyles.title2,
                        color = MiuixTheme.colorScheme.primary,
                        maxLines = 1
                    )
                    Text(
                        text = "kg",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        maxLines = 1
                    )
                }

                Column(
                    modifier = Modifier.weight(1.6f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HistoryMetricCard(
                            label = "BMI",
                            value = if (measurement.bmi > 0.0) String.format("%.1f", measurement.bmi) else "--",
                            status = if (measurement.bmi > 0.0) getBmiStatus(measurement.bmi) else null,
                            modifier = Modifier.weight(1f)
                        )
                        HistoryMetricCard(
                            label = "体脂率",
                            value = if (hasBia) String.format("%.1f%%", measurement.bodyFatPct) else "--",
                            status = if (hasBia) getFatStatus(measurement.bodyFatPct, isMale) else null,
                            modifier = Modifier.weight(1f)
                        )
                        HistoryMetricCard(
                            label = "肌肉量",
                            value = if (hasBia) String.format("%.1f", measurement.muscleKg) else "--",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HistoryMetricCard(
                            label = "蛋白质",
                            value = if (hasBia) String.format("%.1f%%", measurement.proteinPct) else "--",
                            modifier = Modifier.weight(1f)
                        )
                        HistoryMetricCard(
                            label = "骨量",
                            value = if (hasBia) String.format("%.1f", measurement.boneMassKg) else "--",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "点击查看详情 ›",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End
            )
        }
    }
}


@Preview(showBackground = true)
@Composable
fun HistoryDataScreenPreview() {
    电子秤Theme {
        HistoryDataScreen(
            history = listOf(
                BodyMeasurement("1", System.currentTimeMillis(), 70.5, 500.0, 22.8, 18.5, 60.0, 55.0, 16.5, 3.2, "1", "我的名字"),
                BodyMeasurement("2", System.currentTimeMillis() - 86_400_000L * 3, 71.2, 510.0, 23.1, 19.0, 59.0, 54.0, 16.0, 3.1, "1", "我的名字"),
                BodyMeasurement("3", System.currentTimeMillis() - 86_400_000L * 10, 72.0, 0.0, 23.4, 0.0, 0.0, 0.0, 0.0, 0.0, "1", "我的名字")
            ),
            onNavigateBack = {}
        )
    }
}
