package com.example.dianzicheng.ui.body

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.dianzicheng.domain.FamilyMember
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** 趋势图固定高度（契约：仅全展开显示，180dp）。 */
private val TrendChartHeight = 180.dp

/** 指标在趋势图上的展示规格（标题 / 单位 / 小数位）。UI 侧自有映射，不参与 A 组的读数构建。 */
private data class TrendStyle(val title: String, val unit: String, val decimals: Int)

/** 一个绘图点：横轴用快照 label，纵轴为指标值。 */
private data class TrendPoint(val index: Int, val dayPosition: Float, val value: Double)

/** 七天等宽的趋势图；横向拖动或点击时选择最近的数据点。 */
@Composable
fun BodyTrendChart(
    metric: MetricKind,
    snapshots: List<HistorySnapshot>,
    windowStartMs: Long,
    members: List<FamilyMember> = emptyList(),
    selectedIndex: Int = 0,
    onSelectIndex: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = bodyPalette()
    val accent = palette.metricAccents.forMetric(metric)
    val style = remember(metric) { trendStyleOf(metric) }
    val zone = remember { ZoneId.systemDefault() }
    val startDate = remember(windowStartMs, zone) {
        Instant.ofEpochMilli(windowStartMs).atZone(zone).toLocalDate()
    }
    val displayPoints = remember(metric, snapshots, members, startDate, zone) {
        snapshots.mapIndexedNotNull { index, snapshot ->
            val value = metricNumericValue(snapshot.measurement, metric, members.firstOrNull { it.id == snapshot.measurement.memberId })
            if (value == null || value <= 0.0) null else {
                val time = Instant.ofEpochMilli(snapshot.measurement.measuredAtEpochMs).atZone(zone)
                val day = ChronoUnit.DAYS.between(startDate, time.toLocalDate()).toFloat()
                val fraction = time.toLocalTime().toSecondOfDay() / 86400f
                TrendPoint(index, day + fraction, value)
            }
        }.reversed()
    }
    val safeSelectedIndex = if (snapshots.isEmpty()) 0 else selectedIndex.coerceIn(0, snapshots.lastIndex)
    val selectedValue = snapshots.getOrNull(safeSelectedIndex)?.let {
        metricNumericValue(it.measurement, metric, members.firstOrNull { member -> member.id == it.measurement.memberId })
    }
    val chartMin = displayPoints.minOfOrNull { it.value } ?: 0.0
    val chartMax = displayPoints.maxOfOrNull { it.value } ?: 0.0
    val chartSpan = (chartMax - chartMin).takeIf { it > 0.001 } ?: 2.0
    val chartLowerBound = if (chartMax - chartMin < 0.001) chartMin - 1.0 else chartMin - chartSpan * 0.18
    val chartRange = if (chartMax - chartMin < 0.001) 2.0 else chartSpan * 1.36
    val dayLabels = remember(startDate) {
        val formatter = DateTimeFormatter.ofPattern("M/d")
        (0L..6L).map { startDate.plusDays(it).format(formatter) }
    }
    var plotSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val edgeInset = with(density) { 5.dp.toPx() }
    fun selectNearest(x: Float) {
        val width = (plotSize.width - edgeInset * 2f).coerceAtLeast(1f)
        val dayPosition = ((x - edgeInset) / width * 7f).coerceIn(0f, 7f)
        displayPoints.minByOrNull { abs(it.dayPosition - dayPosition) }?.let { onSelectIndex(it.index) }
    }

    Surface(
        shape = RoundedCornerShape(BodyDimens.CardRadius),
        color = accent.copy(alpha = 0.06f).compositeOver(palette.card),
        modifier = modifier
            .fillMaxWidth()
            .height(TrendChartHeight),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = style.title + "趋势",
                    style = MiuixTheme.textStyles.footnote1.copy(fontFeatureSettings = "tnum"),
                    color = palette.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                RollingCounter(
                    value = selectedValue,
                    style = style,
                    accent = accent,
                    neutral = palette.textSecondary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            if (displayPoints.size >= 2) {
                val minValue = displayPoints.minOf { it.value }
                val maxValue = displayPoints.maxOf { it.value }
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "最低 " + formatValue(minValue, style) + style.unit,
                        style = MiuixTheme.textStyles.footnote2.copy(fontFeatureSettings = "tnum"),
                        color = palette.textSecondary,
                        maxLines = 1,
                    )
                    Text(
                        text = "最高 " + formatValue(maxValue, style) + style.unit,
                        style = MiuixTheme.textStyles.footnote2.copy(fontFeatureSettings = "tnum"),
                        color = palette.textSecondary,
                        maxLines = 1,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .onSizeChanged { plotSize = it }
                            .pointerInput(displayPoints, plotSize) {
                                detectTapGestures { selectNearest(it.x) }
                            }
                            .pointerInput(displayPoints, plotSize) {
                                var dragX = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { dragX = it.x; selectNearest(dragX) },
                                    onHorizontalDrag = { change, amount ->
                                        change.consume()
                                        dragX += amount
                                        selectNearest(dragX)
                                    },
                                    onDragEnd = { selectNearest(dragX) },
                                )
                            },
                    ) {
                        val left = 5.dp.toPx()
                        val plotWidth = (size.width - left * 2f).coerceAtLeast(1f)
                        fun pointOffset(point: TrendPoint): Offset {
                            val x = left + plotWidth * point.dayPosition / 7f
                            val top = 6.dp.toPx()
                            val bottom = size.height - 6.dp.toPx()
                            val y = (bottom - (bottom - top) * (point.value - chartLowerBound) / chartRange).toFloat()
                            return Offset(x, y)
                        }
                        displayPoints.zipWithNext().forEach { (first, second) ->
                            drawLine(accent.copy(alpha = 0.72f), pointOffset(first), pointOffset(second), 2.dp.toPx(), cap = StrokeCap.Round)
                        }
                        displayPoints.forEach { point ->
                            val selected = point.index == safeSelectedIndex
                            val center = pointOffset(point)
                            drawCircle(palette.card, if (selected) 5.dp.toPx() else 3.5.dp.toPx(), center)
                            drawCircle(accent, if (selected) 3.5.dp.toPx() else 2.5.dp.toPx(), center)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        dayLabels.forEach { label ->
                            Text(
                                text = label,
                                style = MiuixTheme.textStyles.footnote2.copy(fontFeatureSettings = "tnum"),
                                color = palette.textSecondary,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (displayPoints.isEmpty()) {
                    Text(
                        text = "暂无趋势数据",
                        style = MiuixTheme.textStyles.footnote2,
                        color = palette.textSecondary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

/** 顶部指标数值滚动计数器。 */
@Composable
private fun RollingCounter(
    value: Double?,
    style: TrendStyle,
    accent: Color,
    neutral: Color,
    modifier: Modifier = Modifier,
) {
    var animatedValue by remember { mutableFloatStateOf(value?.toFloat() ?: 0f) }
    LaunchedEffect(value) {
        if (value == null) {
            animatedValue = 0f
        } else {
            val start = animatedValue
            val target = value.toFloat()
            val steps = 12
            repeat(steps) { step ->
                val t = (step + 1f) / steps
                animatedValue = start + (target - start) * t
                delay(30L)
            }
            animatedValue = target
        }
    }
    Text(
        text = if (value == null) "--" else formatValue(animatedValue.toDouble(), style) + style.unit,
        style = MiuixTheme.textStyles.body1.copy(fontFeatureSettings = "tnum"),
        color = if (value == null) neutral else accent,
        maxLines = 1,
        modifier = modifier,
    )
}

/** 指标 → 展示规格。数值本身统一由 A 组的 metricNumericValue 提供，避免两套阈值逻辑。 */
private fun trendStyleOf(metric: MetricKind): TrendStyle = when (metric) {
    MetricKind.WEIGHT -> TrendStyle(title = "体重", unit = "kg", decimals = 2)
    MetricKind.BMI -> TrendStyle(title = "BMI", unit = "", decimals = 1)
    MetricKind.BODY_FAT -> TrendStyle(title = "体脂率", unit = "%", decimals = 1)
    MetricKind.MUSCLE -> TrendStyle(title = "肌肉量", unit = "kg", decimals = 1)
    MetricKind.WATER -> TrendStyle(title = "水分", unit = "%", decimals = 1)
    MetricKind.VISCERAL_FAT -> TrendStyle(title = "内脏脂肪", unit = "级", decimals = 0)
    MetricKind.PROTEIN -> TrendStyle(title = "蛋白质", unit = "%", decimals = 1)
    MetricKind.BONE -> TrendStyle(title = "骨量", unit = "kg", decimals = 1)
}

/** 统一的小数位格式化（等宽数字由 Text 的 tnum 负责）。 */
private fun formatValue(value: Double, style: TrendStyle): String =
    String.format(Locale.US, "%" + "." + style.decimals + "f", value)
