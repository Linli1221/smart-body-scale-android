package com.example.dianzicheng.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.BodyMetric
import com.example.dianzicheng.domain.BodyMetricOrderCodec
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.ui.theme.电子秤Theme
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryContent(
    history: List<BodyMeasurement>,
    members: List<FamilyMember> = emptyList(),
    metricOrder: List<BodyMetric> = BodyMetric.defaultOrder,
    onSaveMetricOrder: (List<BodyMetric>) -> Unit = {},
    onDelete: (BodyMeasurement) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    /** 点击列表最底部的「历史数据」入口，进入完整的历史数据统计页 */
    onNavigateToHistoryData: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault()) }
    var selectedMemberId by remember { mutableStateOf<String?>(null) }
    var measurementToDelete by remember { mutableStateOf<BodyMeasurement?>(null) }
    var showMetricOrderDialog by remember { mutableStateOf(false) }

    val filteredHistory = remember(history, selectedMemberId) {
        if (selectedMemberId == null) {
            history
        } else {
            history.filter { it.memberId == selectedMemberId }
        }
    }

    if (measurementToDelete != null) {
        val target = measurementToDelete!!
        AlertDialog(
            onDismissRequest = { measurementToDelete = null },
            title = { Text("确认删除", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定要删除 ${dateFormat.format(Date(target.measuredAtEpochMs))} 的 ${String.format("%.2f", target.weightKg)} kg 测量记录吗？此操作无法撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target)
                        measurementToDelete = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { measurementToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showMetricOrderDialog) {
        MetricOrderDialog(
            order = metricOrder,
            onMove = { from, to ->
                onSaveMetricOrder(BodyMetricOrderCodec.move(metricOrder, from, to))
            },
            onReset = { onSaveMetricOrder(BodyMetric.defaultOrder) },
            onDismiss = { showMetricOrderDialog = false }
        )
    }

    Scaffold(
        topBar = {
            MediumTopAppBar(
                title = { Text("测量历史", fontWeight = FontWeight.Bold) }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.size(80.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("暂无", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "暂无历史记录", 
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                if (members.size > 1) {
                    item(key = "member_filter") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                FilterChip(
                                    selected = selectedMemberId == null,
                                    onClick = { selectedMemberId = null },
                                    label = { Text("全部 (${history.size})") }
                                )
                            }
                            items(members, key = { it.id }) { member ->
                                val count = history.count { it.memberId == member.id }
                                FilterChip(
                                    selected = selectedMemberId == member.id,
                                    onClick = { selectedMemberId = member.id },
                                    label = { Text("${member.name} ($count)") }
                                )
                            }
                        }
                    }
                }

                item(key = "weight_chart_${selectedMemberId ?: "all"}") {
                    WeightTrendChart(
                        measurements = filteredHistory.take(30),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                // ── 指标趋势区：按用户自定义顺序渲染各指标的趋势卡片 ──
                item(key = "metric_trend_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "指标趋势",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { showMetricOrderDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Sort,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("调整顺序", fontSize = 12.sp)
                        }
                    }
                }

                items(metricOrder, key = { "trend_${it.name}" }) { metric ->
                    // 时间正序取该指标的全部有效值；0 值（未赤脚称重）由 valueOf 过滤
                    val series = remember(filteredHistory, metric) {
                        filteredHistory
                            .sortedBy { it.measuredAtEpochMs }
                            .mapNotNull { metric.valueOf(it) }
                    }
                    if (series.isNotEmpty()) {
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            MetricTrendCard(
                                label = metric.label,
                                values = series,
                                unit = metric.unit,
                                decimals = metric.decimals,
                                hint = "仅一次测量，暂无趋势"
                            )
                        }
                    }
                }

                if (filteredHistory.isEmpty()) {
                    item(key = "empty_member_history") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("该成员暂无历史记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                } else {
                    items(filteredHistory, key = { it.id }) { measurement ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            ModernMeasurementCard(
                                measurement = measurement,
                                dateStr = dateFormat.format(Date(measurement.measuredAtEpochMs)),
                                onDelete = { measurementToDelete = measurement },
                                onNavigateToDetail = onNavigateToDetail,
                                isMale = members.firstOrNull { it.id == measurement.memberId }?.sex == Sex.MALE
                            )
                        }
                    }
                }

                // ── 列表最底部：「历史数据」入口，点击进入完整的历史数据统计 ──
                if (filteredHistory.isNotEmpty()) {
                    item(key = "history_data_entry") {
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            HistoryDataEntryCard(onClick = onNavigateToHistoryData)
                        }
                    }
                }
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
            .clickable { onNavigateToDetail(measurement.id) },
        shape = RoundedCornerShape(8.dp), // Using a clean, slightly rounded card design instead of extraLarge
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // 头部：左侧日期 + 成员标签，右侧删除按钮；体重由下方六指标卡片统一呈现
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onDelete,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(20.dp))
                }
            }

            // Member Name tag if present
            if (measurement.memberNameSnapshot != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = measurement.memberNameSnapshot,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
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
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "体重",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = String.format("%.2f", measurement.weightKg),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                    Text(
                        text = "kg",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
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
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End
            )
        }
    }
}

/**
 * 历史记录卡片中的单个指标小卡片。
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
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = if (value == "--") MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        if (status != null) {
            val statusColor = if (status == "标准") Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
            Surface(
                color = statusColor.copy(alpha = 0.1f),
                shape = CircleShape,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = status,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = statusColor
                )
            }
        }
    }
}

/**
 * 历史列表最底部的「历史数据」入口卡片。
 *
 * 作为列表收尾项，引导用户进入完整的历史数据统计页（各项指标的历史数据与趋势）。
 */
@Composable
fun HistoryDataEntryCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.DateRange,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "历史数据",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "查看 BMI、体脂率、肌肉量等指标的完整历史数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 历史页的指标趋势卡片。
 *
 * 卡片顶部为指标名 + 最新值，下方是按时间正序绘制的迷你走势线。
 * 走势线只反映变化趋势（按序列自身归一化），具体数值由上方文字给出。
 *
 * 数据不足时的降级：
 * - 有效值少于 2 个时不画走势线，仅在右上角提示原因；
 * - 完全没有有效值时整张卡片不渲染（由调用方过滤）。
 *
 * @param label     指标名（如「BMI」「体脂率」）
 * @param values    按时间正序的有效值序列
 * @param unit      单位后缀
 * @param decimals  数值小数位数
 * @param hint      数据不足时展示的说明文字
 */
@Composable
fun MetricTrendCard(
    label: String,
    values: List<Double>,
    unit: String,
    decimals: Int,
    hint: String,
    modifier: Modifier = Modifier
) {
    val fmt = "%.${decimals}f"
    val latest = values.lastOrNull()

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = (latest?.let { String.format(fmt, it) + unit } ?: "--"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = if (latest != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }

            if (values.size >= 2) {
                Spacer(modifier = Modifier.height(8.dp))
                Sparkline(
                    values = values.takeLast(20),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/**
 * 迷你走势线：折线 + 渐变面积 + 末点高亮。
 *
 * 纵向按序列自身的最小/最大值归一化，不绘制坐标轴与刻度，
 * 因此图形只表达趋势形状，不表达绝对区间。
 */
@Composable
fun Sparkline(
    values: List<Double>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary
) {
    if (values.size < 2) return

    androidx.compose.foundation.Canvas(modifier = modifier) {
        val minVal = values.min()
        val maxVal = values.max()
        val range = if (maxVal > minVal) maxVal - minVal else 1.0
        val w = size.width
        val h = size.height
        val step = w / (values.size - 1).toFloat()

        fun yOf(v: Double) = (h * (1.0 - (v - minVal) / range)).toFloat()

        val fillPath = Path()
        fillPath.moveTo(0f, yOf(values.first()))
        for (i in 1 until values.size) {
            fillPath.lineTo(i * step, yOf(values[i]))
        }
        fillPath.lineTo(w, h)
        fillPath.lineTo(0f, h)
        fillPath.close()
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.22f), lineColor.copy(alpha = 0f)),
                startY = 0f,
                endY = h
            )
        )

        val linePath = Path()
        linePath.moveTo(0f, yOf(values.first()))
        for (i in 1 until values.size) {
            linePath.lineTo(i * step, yOf(values[i]))
        }
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        drawCircle(lineColor, radius = 3.5.dp.toPx(), center = Offset(w, yOf(values.last())))
    }
}

/**
 * 指标顺序调整弹窗。
 *
 * 列表项可上移/下移，实时生效（每次移动即写回 DataStore），
 * 因此「恢复默认」与「完成」都不需要显式保存动作。
 * 顺序持久化由 [HistoryViewModel.saveBodyMetricOrder] 完成。
 */
@Composable
fun MetricOrderDialog(
    order: List<BodyMetric>,
    onMove: (Int, Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整指标顺序", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "拖动或点击箭头调整趋势卡片的展示顺序",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                order.forEachIndexed { index, metric ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.width(28.dp)
                        )
                        Text(
                            text = metric.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onMove(index, index - 1) },
                            enabled = index > 0,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "上移",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = { onMove(index, index + 1) },
                            enabled = index < order.lastIndex,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "下移",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        },
        dismissButton = {
            TextButton(onClick = onReset) {
                Text("恢复默认")
            }
        }
    )
}


@Composable
fun WeightTrendChart(
    measurements: List<BodyMeasurement>,
    modifier: Modifier = Modifier
) {
    if (measurements.size < 2) return

    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current

    // Animation progress
    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(measurements) {
        animProgress.snapTo(0f)
        animProgress.animateTo(1f, animationSpec = tween(durationMillis = 900))
    }

    val sorted = remember(measurements) { measurements.sortedBy { it.measuredAtEpochMs } }
    val weights = sorted.map { it.weightKg }
    val weightMinVal = weights.min()
    val weightMaxVal = weights.max()
    val hasDiff = weightMaxVal > weightMinVal
    val minWeight = if (hasDiff) weightMinVal else weightMinVal - 0.5
    val maxWeight = if (hasDiff) weightMaxVal else weightMaxVal + 0.5
    val weightRange = maxWeight - minWeight

    val labelDateFormat = remember { SimpleDateFormat("MM/dd", Locale.getDefault()) }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "体重走势",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "近${sorted.size}次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (hasDiff) {
                    Text(
                        text = String.format("↓ %.1f kg", weightMinVal),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = String.format("↑ %.1f kg", weightMaxVal),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    Text(
                        text = String.format("均值: %.1f kg", weightMinVal),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            ) {
                val w = size.width
                val h = size.height
                val paddingLeft = 8.dp.toPx()
                val paddingRight = 8.dp.toPx()
                val paddingTop = 12.dp.toPx()
                val paddingBottom = 24.dp.toPx()
                val chartW = w - paddingLeft - paddingRight
                val chartH = h - paddingTop - paddingBottom
                val n = sorted.size

                fun xOf(i: Int) = paddingLeft + i.toFloat() / (n - 1).toFloat() * chartW
                fun yOf(weight: Double) = paddingTop + chartH * (1.0 - (weight - minWeight) / weightRange).toFloat()

                // Grid lines (3 horizontal)
                val gridColor = outlineColor.copy(alpha = 0.12f)
                for (gi in 0..2) {
                    val gy = paddingTop + chartH * gi / 2f
                    drawLine(gridColor, Offset(paddingLeft, gy), Offset(paddingLeft + chartW, gy), strokeWidth = 1.dp.toPx())
                }

                // Progress clip for animation
                clipRect(left = 0f, top = 0f, right = paddingLeft + chartW * animProgress.value, bottom = h) {
                    // Gradient fill area
                    val fillPath = Path()
                    fillPath.moveTo(xOf(0), yOf(weights[0]))
                    for (i in 1 until n) {
                        val x0 = xOf(i - 1); val y0 = yOf(weights[i - 1])
                        val x1 = xOf(i);     val y1 = yOf(weights[i])
                        val cx = (x0 + x1) / 2f
                        fillPath.cubicTo(cx, y0, cx, y1, x1, y1)
                    }
                    fillPath.lineTo(xOf(n - 1), h - paddingBottom)
                    fillPath.lineTo(xOf(0), h - paddingBottom)
                    fillPath.close()
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(primaryColor.copy(alpha = 0.25f), primaryColor.copy(alpha = 0f)),
                            startY = paddingTop,
                            endY = h - paddingBottom
                        )
                    )

                    // Line
                    val linePath = Path()
                    linePath.moveTo(xOf(0), yOf(weights[0]))
                    for (i in 1 until n) {
                        val x0 = xOf(i - 1); val y0 = yOf(weights[i - 1])
                        val x1 = xOf(i);     val y1 = yOf(weights[i])
                        val cx = (x0 + x1) / 2f
                        linePath.cubicTo(cx, y0, cx, y1, x1, y1)
                    }
                    drawPath(
                        path = linePath,
                        color = primaryColor,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // Data points
                    val maxIdx = weights.indexOfFirst { it == weightMaxVal }
                    val minIdx = weights.indexOfFirst { it == weightMinVal }
                    for (i in 0 until n) {
                        val px = xOf(i); val py = yOf(weights[i])
                        val isHighlight = hasDiff && (i == maxIdx || i == minIdx || i == 0 || i == n - 1)
                        if (isHighlight) {
                            drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(px, py))
                            drawCircle(primaryColor, radius = 4.dp.toPx(), center = Offset(px, py), style = Stroke(width = 2.dp.toPx()))
                        } else {
                            drawCircle(primaryColor.copy(alpha = 0.4f), radius = 2.5.dp.toPx(), center = Offset(px, py))
                        }
                    }
                }

                // Date labels: first and last
                val labelPaint = android.graphics.Paint().apply {
                    color = outlineColor.copy(alpha = 0.7f).toArgb()
                    isAntiAlias = true
                    textSize = with(density) { 10.sp.toPx() }
                    textAlign = android.graphics.Paint.Align.LEFT
                }
                val firstLabel = labelDateFormat.format(Date(sorted.first().measuredAtEpochMs))
                val lastLabel = labelDateFormat.format(Date(sorted.last().measuredAtEpochMs))
                val labelY = h - 4.dp.toPx()
                drawContext.canvas.nativeCanvas.apply {
                    drawText(firstLabel, paddingLeft, labelY, labelPaint)
                    labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                    drawText(lastLabel, paddingLeft + chartW, labelY, labelPaint)
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToHistoryData: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val history by viewModel.history.collectAsState()
    val members by viewModel.members.collectAsState()
    val metricOrder by viewModel.bodyMetricOrder.collectAsState()
    HistoryContent(
        history = history,
        members = members,
        metricOrder = metricOrder,
        onSaveMetricOrder = { viewModel.saveBodyMetricOrder(it) },
        onDelete = { viewModel.deleteMeasurement(it) },
        onNavigateToDetail = onNavigateToDetail,
        onNavigateToHistoryData = onNavigateToHistoryData,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementDetailScreen(
    measurementId: String,
    viewModel: HistoryViewModel,
    onNavigateBack: () -> Unit
) {
    val history by viewModel.history.collectAsState()
    val measurement = history.find { it.id == measurementId }

    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("测量详情", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (measurement == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("未找到该测量记录")
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = dateFormat.format(Date(measurement.measuredAtEpochMs)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outline
                )

                if (measurement.memberNameSnapshot != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "成员: ${measurement.memberNameSnapshot}",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = String.format("%.2f", measurement.weightKg),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "kg",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.height(32.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "身体数据分析",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        val hasBia = measurement.impedanceOhm > 0.0 && measurement.bodyFatPct > 0.0
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DetailGridItem("BMI", if (measurement.bmi > 0.0) String.format("%.1f", measurement.bmi) else "--", modifier = Modifier.weight(1f))
                            DetailGridItem("体脂率", if (hasBia) String.format("%.1f%%", measurement.bodyFatPct) else "--", modifier = Modifier.weight(1f))
                            DetailGridItem("水分", if (hasBia) String.format("%.1f%%", measurement.waterPct) else "--", modifier = Modifier.weight(1f))
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DetailGridItem("肌肉量", if (hasBia) String.format("%.1fkg", measurement.muscleKg) else "--", modifier = Modifier.weight(1f))
                            DetailGridItem("蛋白质", if (hasBia) String.format("%.1f%%", measurement.proteinPct) else "--", modifier = Modifier.weight(1f))
                            DetailGridItem("骨量", if (hasBia) String.format("%.1fkg", measurement.boneMassKg) else "--", modifier = Modifier.weight(1f))
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DetailGridItem("电阻抗", if (measurement.impedanceOhm > 0.0) "${measurement.impedanceOhm.toInt()}Ω" else "未测出", modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.weight(2f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailGridItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Preview(showBackground = true)
@Composable
fun HistoryPreview() {
    电子秤Theme(dynamicColor = true) {
        HistoryContent(
            history = listOf(
                BodyMeasurement("1", System.currentTimeMillis(), 70.5, 500.0, 22.8, 18.5, 55.0, 60.0, 16.5, 3.2, "1", "我的名字"),
                BodyMeasurement("2", System.currentTimeMillis() - 86400000, 71.2, 510.0, 23.1, 19.0, 54.0, 59.0, 16.0, 3.1, "1", "我的名字")
            ),
            onDelete = {},
            onNavigateToDetail = {}
        )
    }
}
