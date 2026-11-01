package com.example.dianzicheng.ui.body

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.dianzicheng.domain.FamilyMember
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.runtime.snapshotFlow
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.abs

/** 趋势图固定高度（契约：仅全展开显示，180dp）。 */
private val TrendChartHeight = 180.dp

/** 单个数据点的横向宽度；滑动时以此为磁吸单位。 */
private val TrendStepWidth = 88.dp

/** 单点状态文案。 */
private const val SINGLE_POINT_HINT = "再测一次即可连成趋势曲线"

/** 指标在趋势图上的展示规格（标题 / 单位 / 小数位）。UI 侧自有映射，不参与 A 组的读数构建。 */
private data class TrendStyle(val title: String, val unit: String, val decimals: Int)

/** 一个绘图点：横轴用快照 label，纵轴为指标值。 */
private data class TrendPoint(val label: String, val time: String, val value: Double)

/**
 * 指标趋势折线图（横向滑动 + 数据点磁吸）。
 *
 * - 数据来自历史快照 [snapshots]（最新在前），绘图时反转为时间升序（左旧右新）；
 * - LazyRow 以 88dp 为一个磁吸单元，松手后吸附到最近数据点；
 * - 顶部指标数值使用滚动计数器，随选中数据点平滑变化。
 */
@Composable
fun BodyTrendChart(
    metric: MetricKind,
    snapshots: List<HistorySnapshot>,
    members: List<FamilyMember> = emptyList(),
    selectedIndex: Int = 0,
    onSelectIndex: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = bodyPalette()
    val accent = palette.metricAccents.forMetric(metric)
    val style = remember(metric) { trendStyleOf(metric) }
    val displayPoints = remember(metric, snapshots, members) {
        snapshots.mapNotNull { snapshot ->
            val value = metricNumericValue(snapshot.measurement, metric, members.firstOrNull { it.id == snapshot.measurement.memberId })
            if (value == null || value <= 0.0) null else TrendPoint(snapshot.label, snapshot.sublabel, value)
        }.reversed()
    }
    val snapshotIndices = remember(snapshots, metric, members) {
        snapshots.mapIndexedNotNull { index, snapshot ->
            val value = metricNumericValue(snapshot.measurement, metric, members.firstOrNull { it.id == snapshot.measurement.memberId })
            if (value == null || value <= 0.0) null else index
        }.reversed()
    }
    val safeSelectedIndex = if (snapshots.isEmpty()) 0 else selectedIndex.coerceIn(0, snapshots.lastIndex)
    val selectedValue = snapshots.getOrNull(safeSelectedIndex)?.let {
        metricNumericValue(it.measurement, metric, members.firstOrNull { member -> member.id == it.measurement.memberId })
    }
    val chartMin = displayPoints.minOfOrNull { it.value } ?: 0.0
    val chartMax = displayPoints.maxOfOrNull { it.value } ?: 0.0
    val chartSpan = (chartMax - chartMin).takeIf { it > 0.001 } ?: 2.0
    val chartLowerBound =
        if (chartMax - chartMin < 0.001) chartMin - 1.0 else chartMin - chartSpan * 0.18
    val chartRange =
        if (chartMax - chartMin < 0.001) 2.0 else chartSpan * 1.36

    val listState = rememberLazyListState()
    val snapFling = rememberSnapFlingBehavior(listState)
    // 外部时间轴选择与图表滑动共用选中项；程序滚动期间不反向写回中途经过的点。
    var programmaticScroll by remember { mutableStateOf(false) }
    LaunchedEffect(snapshotIndices, metric) {
        val index = snapshotIndices.indexOf(safeSelectedIndex).coerceAtLeast(0)
        if (snapshotIndices.isNotEmpty()) listState.scrollToItem(index)
    }
    LaunchedEffect(safeSelectedIndex, snapshotIndices) {
        val index = snapshotIndices.indexOf(safeSelectedIndex)
        if (index >= 0 && !listState.isScrollInProgress) {
            programmaticScroll = true
            try {
                listState.animateScrollToItem(index)
            } finally {
                programmaticScroll = false
            }
        }
    }
    LaunchedEffect(listState, snapshotIndices) {
        snapshotFlow {
            if (!listState.isScrollInProgress || programmaticScroll) null else {
                val layout = listState.layoutInfo
                val viewportCenter = layout.viewportStartOffset + layout.viewportSize.width / 2
                layout.visibleItemsInfo
                    .minByOrNull { abs((it.offset + it.size / 2) - viewportCenter) }
                    ?.index
            }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { displayIndex ->
                snapshotIndices.getOrNull(displayIndex)?.let(onSelectIndex)
            }
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

            if (displayPoints.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无趋势数据",
                        style = MiuixTheme.textStyles.footnote2,
                        color = palette.textSecondary,
                        maxLines = 1,
                    )
                }
            } else {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    val sidePadding = ((maxWidth - TrendStepWidth) / 2f).coerceAtLeast(0.dp)
                    LazyRow(
                        state = listState,
                        flingBehavior = snapFling,
                        contentPadding = PaddingValues(horizontal = sidePadding),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(displayPoints) { displayIndex, point ->
                            val previous = displayPoints.getOrNull(displayIndex - 1)
                            val next = displayPoints.getOrNull(displayIndex + 1)
                            TrendPointCell(
                                point = point,
                                previous = previous,
                                next = next,
                                selected = snapshotIndices.getOrNull(displayIndex) == safeSelectedIndex,
                                accent = accent,
                                palette = palette,
                                lowerBound = chartLowerBound,
                                range = chartRange,
                            )
                        }
                    }
                }

                if (displayPoints.size == 1) {
                    Text(
                        text = SINGLE_POINT_HINT,
                        style = MiuixTheme.textStyles.footnote2,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
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

/** 单个磁吸数据点单元：左右相连构成连续折线。 */
@Composable
private fun TrendPointCell(
    point: TrendPoint,
    previous: TrendPoint?,
    next: TrendPoint?,
    selected: Boolean,
    accent: Color,
    palette: BodyPalette,
    lowerBound: Double,
    range: Double,
) {
    Box(
        modifier = Modifier
            .width(TrendStepWidth)
            .fillMaxHeight()
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 为日期和时间预留空间；数据点放在单元中心，与磁吸中心一致。
            val plotTop = 6.dp.toPx()
            val plotBottom = size.height - 36.dp.toPx()
            val yOf = { v: Double ->
                (plotBottom - (plotBottom - plotTop) * (v - lowerBound) / range).toFloat()
            }
            val center = Offset(size.width / 2f, yOf(point.value))
            previous?.let {
                drawLine(
                    color = accent.copy(alpha = 0.72f),
                    start = Offset(0f, (yOf(it.value) + center.y) / 2f),
                    end = center,
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            next?.let {
                drawLine(
                    color = accent.copy(alpha = 0.72f),
                    start = center,
                    end = Offset(size.width, (center.y + yOf(it.value)) / 2f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(color = palette.card, radius = if (selected) 5.dp.toPx() else 3.5.dp.toPx(), center = center)
            drawCircle(color = accent, radius = if (selected) 3.5.dp.toPx() else 2.5.dp.toPx(), center = center)
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = point.label,
                style = MiuixTheme.textStyles.footnote2.copy(fontFeatureSettings = "tnum"),
                color = if (selected) accent else palette.textSecondary,
                maxLines = 1,
            )
            Text(
                text = point.time,
                style = MiuixTheme.textStyles.footnote2.copy(fontFeatureSettings = "tnum"),
                color = palette.textSecondary,
                maxLines = 1,
            )
        }
    }
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
