package com.example.dianzicheng.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.BodyMetric
import com.example.dianzicheng.domain.BodyMetricOrderCodec
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.ui.theme.电子秤Theme
import java.text.SimpleDateFormat
import java.util.*

/**
 * 构建走势图折线/填充路径。
 * 只在数据或画布尺寸变化时调用（调用方通过 remember 缓存），
 * 避免入场动画的每一帧都重新分配 Path 并重建所有贝塞尔曲线段。
 *
 * @param includeFill true 时闭合到图表底部形成填充区域路径，false 时仅折线
 */
private fun buildChartPaths(
    values: List<Double>,
    sizePx: IntSize,
    lowerBound: Double,
    valueRange: Double,
    density: Density,
    includeFill: Boolean
): Path {
    val path = Path()
    if (sizePx.width <= 0 || sizePx.height <= 0 || values.isEmpty()) return path
    val paddingLeft = with(density) { 8.dp.toPx() }
    val paddingRight = with(density) { 8.dp.toPx() }
    val paddingTop = with(density) { 12.dp.toPx() }
    val paddingBottom = with(density) { 24.dp.toPx() }
    val chartW = sizePx.width - paddingLeft - paddingRight
    val chartH = sizePx.height - paddingTop - paddingBottom
    val n = values.size

    fun xOf(i: Int) = paddingLeft + i.toFloat() / (n - 1).toFloat() * chartW
    fun yOf(value: Double) = paddingTop + chartH * (1.0 - (value - lowerBound) / valueRange).toFloat()

    path.moveTo(xOf(0), yOf(values[0]))
    for (i in 1 until n) {
        val x0 = xOf(i - 1); val y0 = yOf(values[i - 1])
        val x1 = xOf(i);     val y1 = yOf(values[i])
        val cx = (x0 + x1) / 2f
        path.cubicTo(cx, y0, cx, y1, x1, y1)
    }
    if (includeFill) {
        path.lineTo(xOf(n - 1), sizePx.height - paddingBottom)
        path.lineTo(xOf(0), sizePx.height - paddingBottom)
        path.close()
    }
    return path
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryContent(
    history: List<BodyMeasurement>,
    members: List<FamilyMember> = emptyList(),
    metricOrder: List<BodyMetric> = BodyMetric.defaultOrder,
    onSaveMetricOrder: (List<BodyMetric>) -> Unit = {},
    /** 点击列表最底部的「历史数据」入口，进入完整的历史数据统计页 */
    onNavigateToHistoryData: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault()) }
    var selectedMemberId by remember { mutableStateOf<String?>(null) }
    var showMetricOrderDialog by remember { mutableStateOf(false) }

    val filteredHistory = remember(history, selectedMemberId) {
        if (selectedMemberId == null) {
            history
        } else {
            history.filter { it.memberId == selectedMemberId }
        }
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
                            // 每个成员的测量条数只随 history 变化时统计一次，
                            // 避免每个 FilterChip 重组都 O(members × history) 全量扫描
                            val memberCounts = remember(history) {
                                history.groupingBy { it.memberId }.eachCount()
                            }
                            items(members, key = { it.id }) { member ->
                                val count = memberCounts[member.id] ?: 0
                                FilterChip(
                                    selected = selectedMemberId == member.id,
                                    onClick = { selectedMemberId = member.id },
                                    label = { Text("${member.name} ($count)") }
                                )
                            }
                        }
                    }
                }

                // ── 走势大图区：按用户自定义顺序渲染各指标的走势大图 ──
                // 体重作为主指标置首，其余五项由 metricOrder 决定；
                // 逐条记录卡片已移至「历史数据」子页面，本页只承载趋势总览
                item(key = "metric_trend_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "指标走势",
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
                            .mapNotNull { it to metric.valueOf(it) }
                            .filter { it.second != null }
                            .map { it.first to it.second!! }
                    }
                    if (series.size >= 2) {
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            // 拆出 values / timestamps 的派生列表跟随 series 缓存，
                            // 避免重组时为每个图表额外分配两个新 List
                            val chartValues = remember(series) { series.map { it.second } }
                            val chartTimestamps = remember(series) { series.map { it.first.measuredAtEpochMs } }
                            MetricTrendChart(
                                title = "${metric.label}走势",
                                values = chartValues,
                                timestamps = chartTimestamps,
                                unit = metric.unit,
                                decimals = metric.decimals
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


/**
 * 通用指标走势大图：与体重走势同款的折线 + 渐变面积 + 动画绘制。
 *
 * @param title      卡片标题（如 "BMI走势"、"体脂率走势"）
 * @param values     按时间正序的有效值序列（0 值需在调用侧过滤）
 * @param timestamps 与 values 等长的时间戳序列，用于绘制首末日期标签；
 *                传空列表则不绘制日期标签
 * @param unit       单位后缀，无单位传空串
 * @param decimals   极值与均值的小数位数
 */
@Composable
fun MetricTrendChart(
    title: String,
    values: List<Double>,
    timestamps: List<Long> = emptyList(),
    unit: String,
    decimals: Int,
    modifier: Modifier = Modifier
) {
    if (values.size < 2) return

    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current

    // Animation progress
    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(values) {
        animProgress.snapTo(0f)
        animProgress.animateTo(1f, animationSpec = tween(durationMillis = 900))
    }

    // 极值/上下界仅随数据变化时计算一次，避免每次重组重复扫描 values
    val minVal = remember(values) { values.min() }
    val maxVal = remember(values) { values.max() }
    val hasDiff = maxVal > minVal
    // 数据无波动时给一个固定的上下padding，避免折线贴边导致视觉上是"平的"
    val pad = if (decimals == 0) 1.0 else Math.pow(10.0, -decimals.toDouble())
    val lowerBound = if (hasDiff) minVal else minVal - pad
    val upperBound = if (hasDiff) maxVal else maxVal + pad
    val valueRange = upperBound - lowerBound

    val labelDateFormat = remember { SimpleDateFormat("MM/dd", Locale.getDefault()) }

    // 日期标签只在时间戳变化时格式化一次，避免 draw 作用域内每帧分配 Date/String
    val firstLabel = remember(timestamps) {
        if (timestamps.isEmpty()) "" else labelDateFormat.format(Date(timestamps.first()))
    }
    val lastLabel = remember(timestamps) {
        if (timestamps.isEmpty()) "" else labelDateFormat.format(Date(timestamps.last()))
    }

    // 日期标签绘制用的 Paint 只创建一次（textSize 依赖 density）
    val labelPaint = remember(density) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = with(density) { 10.sp.toPx() }
        }
    }

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
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "近${values.size}次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val fmt = "%.${decimals}f"
                if (hasDiff) {
                    Text(
                        text = String.format("↓ " + fmt, minVal) + unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = String.format("↑ " + fmt, maxVal) + unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    Text(
                        text = String.format("均值: " + fmt, minVal) + unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Canvas 的实际像素尺寸（onSizeChanged 上报），用于缓存折线路径
            var canvasSizePx by remember { mutableStateOf(IntSize.Zero) }

            // 折线与填充路径只在数据或尺寸变化时重建，动画帧之间直接复用
            val cachedLinePath = remember(values, canvasSizePx, lowerBound, valueRange) {
                buildChartPaths(values, canvasSizePx, lowerBound, valueRange, density, includeFill = false)
            }
            val cachedFillPath = remember(values, canvasSizePx, lowerBound, valueRange) {
                buildChartPaths(values, canvasSizePx, lowerBound, valueRange, density, includeFill = true)
            }

            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .onSizeChanged { canvasSizePx = it }
            ) {
                val w = size.width
                val h = size.height
                val paddingLeft = 8.dp.toPx()
                val paddingRight = 8.dp.toPx()
                val paddingTop = 12.dp.toPx()
                val paddingBottom = 24.dp.toPx()
                val chartW = w - paddingLeft - paddingRight
                val chartH = h - paddingTop - paddingBottom
                val n = values.size

                fun xOf(i: Int) = paddingLeft + i.toFloat() / (n - 1).toFloat() * chartW
                fun yOf(value: Double) = paddingTop + chartH * (1.0 - (value - lowerBound) / valueRange).toFloat()

                // Grid lines (3 horizontal)
                val gridColor = outlineColor.copy(alpha = 0.12f)
                for (gi in 0..2) {
                    val gy = paddingTop + chartH * gi / 2f
                    drawLine(gridColor, Offset(paddingLeft, gy), Offset(paddingLeft + chartW, gy), strokeWidth = 1.dp.toPx())
                }

                // 折线路径只在数据或尺寸变化时重建一次并缓存，
                // 动画推进（每帧重绘）时直接复用，不再每帧分配两个 Path + 重建所有曲线段
                val linePath = cachedLinePath
                val fillPath = cachedFillPath

                // Progress clip for animation
                clipRect(left = 0f, top = 0f, right = paddingLeft + chartW * animProgress.value, bottom = h) {
                    // Gradient fill area
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(primaryColor.copy(alpha = 0.25f), primaryColor.copy(alpha = 0f)),
                            startY = paddingTop,
                            endY = h - paddingBottom
                        )
                    )

                    // Line
                    drawPath(
                        path = linePath,
                        color = primaryColor,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // Data points
                    val maxIdx = values.indexOfFirst { it == maxVal }
                    val minIdx = values.indexOfFirst { it == minVal }
                    for (i in 0 until n) {
                        val px = xOf(i); val py = yOf(values[i])
                        val isHighlight = hasDiff && (i == maxIdx || i == minIdx || i == 0 || i == n - 1)
                        if (isHighlight) {
                            drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(px, py))
                            drawCircle(primaryColor, radius = 4.dp.toPx(), center = Offset(px, py), style = Stroke(width = 2.dp.toPx()))
                        } else {
                            drawCircle(primaryColor.copy(alpha = 0.4f), radius = 2.5.dp.toPx(), center = Offset(px, py))
                        }
                    }
                }

                // Date labels: first and last（仅在提供了时间戳时绘制；Paint 与文本均已缓存）
                if (timestamps.isNotEmpty()) {
                    labelPaint.color = outlineColor.copy(alpha = 0.7f).toArgb()
                    labelPaint.textAlign = android.graphics.Paint.Align.LEFT
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
}

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
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
        onNavigateToHistoryData = onNavigateToHistoryData,
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun HistoryPreview() {
    电子秤Theme(dynamicColor = true) {
        HistoryContent(
            history = listOf(
                BodyMeasurement("1", System.currentTimeMillis(), 70.5, 500.0, 22.8, 18.5, 55.0, 60.0, 16.5, 3.2, "1", "我的名字"),
                BodyMeasurement("2", System.currentTimeMillis() - 86400000, 71.2, 510.0, 23.1, 19.0, 54.0, 59.0, 16.0, 3.1, "1", "我的名字"),
                BodyMeasurement("3", System.currentTimeMillis() - 86400000L * 3, 70.8, 505.0, 22.9, 18.2, 54.5, 58.5, 15.8, 3.15, "1", "我的名字")
            ),
            members = listOf(),
            metricOrder = BodyMetric.defaultOrder,
            onSaveMetricOrder = {},
            onNavigateToHistoryData = {}
        )
    }
}
