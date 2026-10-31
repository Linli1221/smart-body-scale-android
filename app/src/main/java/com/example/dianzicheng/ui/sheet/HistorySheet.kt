package com.example.dianzicheng.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.BodyMetric
import com.example.dianzicheng.domain.BodyMetricOrderCodec
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.ui.component.MetricTrendChart
import com.example.dianzicheng.ui.component.getBmiStatus
import com.example.dianzicheng.ui.component.getFatStatus
import com.example.dianzicheng.ui.component.getWaterStatus
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史数据底部弹层（完整历史全量）：
 * 成员筛选 → 六张指标走势图（可调顺序）→ 指标数据统计 → 全部记录（详情 / 删除）。
 */
@Composable
fun HistorySheet(
    show: Boolean,
    history: List<BodyMeasurement>,
    members: List<FamilyMember>,
    metricOrder: List<BodyMetric>,
    onSaveMetricOrder: (List<BodyMetric>) -> Unit,
    onDelete: (BodyMeasurement) -> Unit,
    onDismissRequest: () -> Unit
) {
    var selectedMemberId by remember { mutableStateOf<String?>(null) }
    var showMetricOrder by remember { mutableStateOf(false) }
    var measurementToDelete by remember { mutableStateOf<BodyMeasurement?>(null) }
    var detailMeasurement by remember { mutableStateOf<BodyMeasurement?>(null) }

    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()) }
    val filteredHistory = history.filter { selectedMemberId == null || it.memberId == selectedMemberId }
    val memberCounts = remember(history) {
        history.groupingBy { it.memberId }.eachCount()
    }

    OverlayBottomSheet(
        show = show,
        title = "历史数据",
        onDismissRequest = onDismissRequest
    ) {
        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = MiuixTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(80.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "暂无",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无历史记录",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }
            }
            return@OverlayBottomSheet
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            // ① 成员筛选 chip 条（多成员时出现）
            if (members.size > 1) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        MemberFilterChip(
                            label = "全部 (${history.size})",
                            selected = selectedMemberId == null,
                            onClick = { selectedMemberId = null }
                        )
                        members.forEach { member ->
                            MemberFilterChip(
                                label = "${member.name} (${memberCounts[member.id] ?: 0})",
                                selected = selectedMemberId == member.id,
                                onClick = { selectedMemberId = member.id }
                            )
                        }
                    }
                }
            }

            // ② 指标走势分组头 + 调整顺序
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        SmallTitle(text = "指标走势")
                    }
                    Button(onClick = { showMetricOrder = true }) {
                        Icon(
                            imageVector = MiuixIcons.Sort,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("调整顺序", style = MiuixTheme.textStyles.footnote1)
                    }
                }
            }

            // ③ 六张指标走势图（有效点 ≥ 2 才渲染）
            items(
                count = metricOrder.size,
                key = { "trend_${metricOrder[it].name}" }
            ) { index ->
                val metric = metricOrder[index]
                val points = filteredHistory
                    .sortedBy { it.measuredAtEpochMs }
                    .mapNotNull { m -> metric.valueOf(m)?.let { it to m.measuredAtEpochMs } }
                if (points.size >= 2) {
                    MetricTrendChart(
                        metric = metric,
                        values = points.map { it.first },
                        timestamps = points.map { it.second },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }

            // ④ 筛选后为空的提示
            if (filteredHistory.isEmpty()) {
                item {
                    Text(
                        text = "该成员暂无历史记录",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                    )
                }
            } else {
                // ⑤ 概览 + 指标数据统计
                item {
                    HistorySummaryCard(
                        history = filteredHistory,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
                item {
                    SmallTitle(text = "指标数据")
                }
                items(
                    count = METRIC_DATA_ITEMS.size,
                    key = { "metric_data_${METRIC_DATA_ITEMS[it].first}" }
                ) { index ->
                    val (label, unit, decimals) = METRIC_DATA_ITEMS[index]
                    val values = when (label) {
                        "BMI" -> filteredHistory.map { it.bmi }
                        "体脂率" -> filteredHistory.map { it.bodyFatPct }
                        "肌肉量" -> filteredHistory.map { it.muscleKg }
                        "蛋白质" -> filteredHistory.map { it.proteinPct }
                        else -> filteredHistory.map { it.boneMassKg }
                    }.filter { it > 0.0 }
                    MetricDataCard(
                        label = label,
                        unit = unit,
                        decimals = decimals,
                        values = values,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                // ⑥ 全部记录（时间倒序）
                item {
                    SmallTitle(text = "全部记录（${filteredHistory.size} 次）")
                }
                items(
                    count = filteredHistory.size,
                    key = { "record_${filteredHistory[it].id}" }
                ) { index ->
                    val measurement = filteredHistory[index]
                    MeasurementRecordCard(
                        measurement = measurement,
                        members = members,
                        dateFormat = dateFormat,
                        onDelete = { measurementToDelete = measurement },
                        onOpenDetail = { detailMeasurement = measurement },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }

    // ── 弹窗（Window 层）─────────────────────────────────────────────────
    if (showMetricOrder) {
        MetricOrderDialog(
            order = metricOrder,
            onMove = { from, to -> onSaveMetricOrder(BodyMetricOrderCodec.move(metricOrder, from, to)) },
            onReset = { onSaveMetricOrder(BodyMetric.defaultOrder) },
            onDismiss = { showMetricOrder = false }
        )
    }

    measurementToDelete?.let { target ->
        WindowDialog(
            show = true,
            title = "确认删除",
            summary = "确定要删除 ${dateFormat.format(Date(target.measuredAtEpochMs))} 的 " +
                "${String.format("%.2f", target.weightKg)} kg 测量记录吗？此操作无法撤销。",
            onDismissRequest = { measurementToDelete = null }
        ) {
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
                TextButton(
                    text = "删除",
                    onClick = {
                        onDelete(target)
                        measurementToDelete = null
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error)
                )
            }
        }
    }

    detailMeasurement?.let { detail ->
        MeasurementDetailDialog(
            measurement = detail,
            members = members,
            dateFormat = dateFormat,
            onDismiss = { detailMeasurement = null }
        )
    }
}

/** 指标数据卡的固定五项（label / 单位 / 小数位）。 */
private val METRIC_DATA_ITEMS = listOf(
    Triple("BMI", "", 1),
    Triple("体脂率", "%", 1),
    Triple("肌肉量", "kg", 1),
    Triple("蛋白质", "%", 1),
    Triple("骨量", "kg", 1)
)

// ── 历史弹层小件 ───────────────────────────────────────────────────────────

/** 成员筛选 chip。 */
@Composable
private fun MemberFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MiuixTheme.colorScheme.primaryContainer
        else MiuixTheme.colorScheme.secondaryVariant,
        modifier = Modifier.height(32.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.body2,
                color = if (selected) MiuixTheme.colorScheme.onPrimaryContainer
                else MiuixTheme.colorScheme.onSecondaryVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}

/** 概览卡：总记录数 / 测量跨度 / 平均体重。 */
@Composable
private fun HistorySummaryCard(
    history: List<BodyMeasurement>,
    modifier: Modifier = Modifier
) {
    val sorted = history.sortedBy { it.measuredAtEpochMs }
    val daySpan = if (sorted.size >= 2) {
        ((sorted.last().measuredAtEpochMs - sorted.first().measuredAtEpochMs) / 86_400_000L).toInt()
    } else {
        0
    }
    val avgWeight = if (sorted.isEmpty()) 0.0 else sorted.map { it.weightKg }.average()

    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryCell(label = "总记录数", value = "${sorted.size} 次")
            SummaryCell(label = "测量跨度", value = if (daySpan > 0) "$daySpan 天" else "单日")
            SummaryCell(label = "平均体重", value = String.format(Locale.getDefault(), "%.1f kg", avgWeight))
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, style = MiuixTheme.textStyles.body1)
    }
}

/**
 * 指标数据卡：最新值 + 平均/最高/最低/测量次数。
 * 产品约束：不着色不标方向（偏高未必负面），统一中性色。
 */
@Composable
private fun MetricDataCard(
    label: String,
    unit: String,
    decimals: Int,
    values: List<Double>,
    modifier: Modifier = Modifier
) {
    val fmt = "%.${decimals}f"
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = values.lastOrNull()
                        ?.let { String.format(Locale.getDefault(), fmt, it) + unit } ?: "--",
                    style = MiuixTheme.textStyles.title3,
                    color = if (values.isEmpty()) MiuixTheme.colorScheme.onSurfaceSecondary
                    else MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "最新值",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.outline
                )
            }
            if (values.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "暂无有效数据（需赤脚称重）",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricStatCell("平均值", String.format(Locale.getDefault(), fmt, values.average()) + unit, Modifier.weight(1f))
                    MetricStatCell("最高", String.format(Locale.getDefault(), fmt, values.max()) + unit, Modifier.weight(1f))
                    MetricStatCell("最低", String.format(Locale.getDefault(), fmt, values.min()) + unit, Modifier.weight(1f))
                    MetricStatCell("测量次数", "${values.size} 次", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricStatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 测量记录卡：日期 / 成员 / 指标块 / 详情入口 / 删除。 */
@Composable
private fun MeasurementRecordCard(
    measurement: BodyMeasurement,
    members: List<FamilyMember>,
    dateFormat: SimpleDateFormat,
    onDelete: () -> Unit,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasBia = measurement.impedanceOhm > 0.0 && measurement.bodyFatPct > 0.0
    val isMale = members.firstOrNull { it.id == measurement.memberId }?.sex != Sex.FEMALE

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier
            .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dateFormat.format(Date(measurement.measuredAtEpochMs)),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = "删除",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (!measurement.memberNameSnapshot.isNullOrBlank()) {
                Surface(
                    color = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = measurement.memberNameSnapshot,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 体重主块
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.weight(1.1f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "体重",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                        Text(
                            text = String.format(Locale.getDefault(), "%.2f", measurement.weightKg),
                            style = MiuixTheme.textStyles.title2,
                            color = MiuixTheme.colorScheme.primary
                        )
                        Text(
                            text = "kg",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                }
                // 右侧两行小卡
                Column(
                    modifier = Modifier.weight(1.6f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HistoryMetricCard(
                            label = "BMI",
                            value = if (measurement.bmi > 0) String.format(Locale.getDefault(), "%.1f", measurement.bmi) else "--",
                            badge = if (measurement.bmi > 0) getBmiStatus(measurement.bmi) else null,
                            modifier = Modifier.weight(1f)
                        )
                        HistoryMetricCard(
                            label = "体脂率",
                            value = if (hasBia) String.format(Locale.getDefault(), "%.1f%%", measurement.bodyFatPct) else "--",
                            badge = if (hasBia) getFatStatus(measurement.bodyFatPct, isMale) else null,
                            modifier = Modifier.weight(1f)
                        )
                        HistoryMetricCard(
                            label = "肌肉量",
                            value = if (hasBia) String.format(Locale.getDefault(), "%.1f", measurement.muscleKg) else "--",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HistoryMetricCard(
                            label = "蛋白质",
                            value = if (hasBia) String.format(Locale.getDefault(), "%.1f%%", measurement.proteinPct) else "--",
                            modifier = Modifier.weight(1f)
                        )
                        HistoryMetricCard(
                            label = "骨量",
                            value = if (hasBia) String.format(Locale.getDefault(), "%.1f", measurement.boneMassKg) else "--",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "点击查看详情 ›",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.primary,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenDetail)
            )
        }
    }
}

/** 记录卡内指标小卡（BMI / 体脂率带状态徽标，其余中性）。 */
@Composable
private fun HistoryMetricCard(
    label: String,
    value: String,
    badge: String? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MiuixTheme.textStyles.body1,
                color = if (value == "--") MiuixTheme.colorScheme.onSurfaceSecondary
                else MiuixTheme.colorScheme.onSurface
            )
            if (badge != null) {
                Spacer(modifier = Modifier.height(2.dp))
                com.example.dianzicheng.ui.component.StatusBadge(text = badge)
            }
        }
    }
}

/** 指标顺序调整弹窗：上下箭头即调即存（写 DataStore 实时生效）。 */
@Composable
private fun MetricOrderDialog(
    order: List<BodyMetric>,
    onMove: (from: Int, to: Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = true,
        title = "调整指标顺序",
        summary = "拖动或点击箭头调整趋势卡片的展示顺序",
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            order.forEachIndexed { index, metric ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "${index + 1}.",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.width(28.dp)
                    )
                    Text(
                        text = metric.label,
                        style = MiuixTheme.textStyles.body1,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onMove(index, index - 1) },
                        enabled = index > 0,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = MiuixIcons.ExpandLess,
                            contentDescription = "上移",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { onMove(index, index + 1) },
                        enabled = index < order.size - 1,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = MiuixIcons.ExpandMore,
                            contentDescription = "下移",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    text = "恢复默认",
                    onClick = onReset,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = "完成",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

/**
 * 测量详情弹窗：体重 + 7 项身体数据分析（含达标徽标）。
 * BMI / 体脂率 / 水分挂健康区间徽标；肌肉量 / 蛋白质 / 骨量无公认阈值，保持中性。
 */
@Composable
private fun MeasurementDetailDialog(
    measurement: BodyMeasurement,
    members: List<FamilyMember>,
    dateFormat: SimpleDateFormat,
    onDismiss: () -> Unit
) {
    val hasBia = measurement.impedanceOhm > 0.0 && measurement.bodyFatPct > 0.0
    val isMale = members.firstOrNull { it.id == measurement.memberId }?.sex != Sex.FEMALE

    WindowDialog(
        show = true,
        title = "测量详情",
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = dateFormat.format(Date(measurement.measuredAtEpochMs)),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
            if (!measurement.memberNameSnapshot.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MiuixTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "成员: ${measurement.memberNameSnapshot}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = String.format(Locale.getDefault(), "%.2f", measurement.weightKg),
                    style = MiuixTheme.textStyles.headline1.copy(fontSize = 48.sp),
                    color = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "kg",
                    style = MiuixTheme.textStyles.title3,
                    color = MiuixTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "身体数据分析",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        DetailGridItem(
                            label = "BMI",
                            value = if (measurement.bmi > 0) String.format(Locale.getDefault(), "%.1f", measurement.bmi) else "--",
                            badge = if (measurement.bmi > 0) getBmiStatus(measurement.bmi) else null,
                            modifier = Modifier.weight(1f)
                        )
                        DetailGridItem(
                            label = "体脂率",
                            value = if (hasBia) String.format(Locale.getDefault(), "%.1f%%", measurement.bodyFatPct) else "--",
                            badge = if (hasBia) getFatStatus(measurement.bodyFatPct, isMale) else null,
                            modifier = Modifier.weight(1f)
                        )
                        DetailGridItem(
                            label = "水分",
                            value = if (hasBia) String.format(Locale.getDefault(), "%.1f%%", measurement.waterPct) else "--",
                            badge = if (hasBia) getWaterStatus(measurement.waterPct) else null,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        DetailGridItem(
                            label = "肌肉量",
                            value = if (hasBia) String.format(Locale.getDefault(), "%.1fkg", measurement.muscleKg) else "--",
                            modifier = Modifier.weight(1f)
                        )
                        DetailGridItem(
                            label = "蛋白质",
                            value = if (hasBia) String.format(Locale.getDefault(), "%.1f%%", measurement.proteinPct) else "--",
                            modifier = Modifier.weight(1f)
                        )
                        DetailGridItem(
                            label = "骨量",
                            value = if (hasBia) String.format(Locale.getDefault(), "%.1fkg", measurement.boneMassKg) else "--",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        DetailGridItem(
                            label = "电阻抗",
                            value = if (measurement.impedanceOhm > 0) "${measurement.impedanceOhm.toInt()}Ω" else "未测出"
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(text = "关闭", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun DetailGridItem(
    label: String,
    value: String,
    badge: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.title3,
            color = if (value == "--" || value == "未测出") MiuixTheme.colorScheme.onSurfaceSecondary
            else MiuixTheme.colorScheme.onSurface
        )
        if (badge != null) {
            Spacer(modifier = Modifier.height(4.dp))
            com.example.dianzicheng.ui.component.StatusBadge(text = badge)
        }
    }
}
