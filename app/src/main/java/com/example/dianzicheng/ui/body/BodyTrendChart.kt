package com.example.dianzicheng.ui.body

import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.domain.FamilyMember
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.pow

/** 趋势图固定高度（契约：仅全展开显示，180dp）。 */
private val TrendChartHeight = 180.dp

/** 单点状态文案。 */
private const val SINGLE_POINT_HINT = "再测一次即可连成趋势曲线"

/** 指标在趋势图上的展示规格（标题 / 单位 / 小数位）。UI 侧自有映射，不参与 A 组的读数构建。 */
private data class TrendStyle(val title: String, val unit: String, val decimals: Int)

/** 一个绘图点：横轴用快照 label（MM/dd 或 M月），纵轴为指标值。 */
private data class TrendPoint(val label: String, val value: Double)

/**
 * 指标趋势图（Canvas 自绘；Miuix 0.9.4 无图表组件）。
 *
 * - 数据全部来自历史快照 [snapshots]（最新在前），绘图时反转为时间升序（左旧右新）。
 * - 切换指标 / 数据变化时以 420ms 的从左向右揭示动画平滑重绘。
 * - 单点时只画一个发光锚点 + [SINGLE_POINT_HINT] 文案。
 *
 * @param metric 当前趋势指标（默认体重由调用方传入）。
 * @param snapshots 当前模式/成员筛选后的历史快照，顺序为最新在前。
 */
@Composable
fun BodyTrendChart(
    metric: MetricKind,
    snapshots: List<HistorySnapshot>,
    member: FamilyMember? = null,
    modifier: Modifier = Modifier,
) {
    val palette = bodyPalette()
    val accent = palette.metricAccents.forMetric(metric)
    val style = remember(metric) { trendStyleOf(metric) }
    val points = remember(metric, snapshots, member) {
        snapshots.asReversed().mapNotNull { snapshot ->
            val value = metricNumericValue(snapshot.measurement, metric, member)
            // 无 BIA / 缺测的指标返回 null 或 0；0 不是有效生物测量值，一并剔除。
            if (value == null || value <= 0.0) null else TrendPoint(snapshot.label, value)
        }
    }

    val reveal = remember { Animatable(0f) }
    LaunchedEffect(metric, points) {
        reveal.snapTo(0f)
        reveal.animateTo(targetValue = 1f, animationSpec = tween(durationMillis = 420))
    }

    val density = LocalDensity.current
    val labelPaint = remember(palette.textSecondary, density) {
        Paint().apply {
            isAntiAlias = true
            color = palette.textSecondary.toArgb()
            textSize = with(density) { 10.sp.toPx() }
        }
    }
    val valuePaint = remember(accent, density) {
        Paint().apply {
            isAntiAlias = true
            color = accent.toArgb()
            textSize = with(density) { 13.sp.toPx() }
            textAlign = Paint.Align.CENTER
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
                Text(
                    text = if (points.isEmpty()) "暂无数据" else "近 " + points.size + " 次",
                    style = MiuixTheme.textStyles.footnote2.copy(fontFeatureSettings = "tnum"),
                    color = palette.textSecondary,
                    maxLines = 1,
                )
            }

            if (points.size >= 2) {
                val minValue = points.minOf { it.value }
                val maxValue = points.maxOf { it.value }
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

            if (points.isEmpty()) {
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
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    val padLeft = 4.dp.toPx()
                    val padRight = 4.dp.toPx()
                    val padTop = 10.dp.toPx()
                    val padBottom = 16.dp.toPx()
                    val chartWidth = (size.width - padLeft - padRight).coerceAtLeast(1f)
                    val chartHeight = (size.height - padTop - padBottom).coerceAtLeast(1f)
                    val progress = reveal.value

                    // 网格：3 条水平参考线，不参与揭示裁剪
                    for (gridIndex in 0..2) {
                        val gridY = padTop + chartHeight * gridIndex / 2f
                        drawLine(
                            color = palette.divider,
                            start = Offset(padLeft, gridY),
                            end = Offset(padLeft + chartWidth, gridY),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }

                    if (points.size == 1) {
                        // 单点：发光锚点 + 数值，不连线
                        val center = Offset(padLeft + chartWidth / 2f, padTop + chartHeight / 2f)
                        val glowRadius = 30.dp.toPx()
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    accent.copy(alpha = 0.35f * progress),
                                    accent.copy(alpha = 0f),
                                ),
                                center = center,
                                radius = glowRadius,
                            ),
                            radius = glowRadius,
                            center = center,
                        )
                        drawCircle(
                            color = palette.sheet,
                            radius = 7.dp.toPx(),
                            center = center,
                        )
                        drawCircle(
                            color = accent.copy(alpha = progress),
                            radius = 5.dp.toPx(),
                            center = center,
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            formatValue(points.first().value, style) + style.unit,
                            center.x,
                            center.y - 18.dp.toPx(),
                            valuePaint,
                        )
                        return@Canvas
                    }

                    val minValue = points.minOf { it.value }
                    val maxValue = points.maxOf { it.value }
                    val unitStep = if (style.decimals == 0) {
                        1.0
                    } else {
                        10.0.pow(-style.decimals.toDouble())
                    }
                    val span = maxValue - minValue
                    // 值域上下留白，避免折线贴边；全等值时用 ±1 个最小刻度撑开。
                    val lowerBound: Double
                    val valueRange: Double
                    if (span < unitStep) {
                        lowerBound = minValue - unitStep
                        valueRange = unitStep * 2.0
                    } else {
                        lowerBound = minValue - span * 0.18
                        valueRange = span * 1.36
                    }

                    fun xOf(index: Int): Float =
                        padLeft + index.toFloat() / (points.size - 1) * chartWidth

                    fun yOf(value: Double): Float =
                        (padTop + chartHeight * (1.0 - (value - lowerBound) / valueRange)).toFloat()

                    val linePath = Path().apply {
                        moveTo(xOf(0), yOf(points[0].value))
                        for (index in 1 until points.size) {
                            val previousX = xOf(index - 1)
                            val previousY = yOf(points[index - 1].value)
                            val currentX = xOf(index)
                            val currentY = yOf(points[index].value)
                            val controlX = (previousX + currentX) / 2f
                            cubicTo(controlX, previousY, controlX, currentY, currentX, currentY)
                        }
                    }
                    val fillPath = Path().apply {
                        addPath(linePath)
                        lineTo(xOf(points.lastIndex), padTop + chartHeight)
                        lineTo(xOf(0), padTop + chartHeight)
                        close()
                    }

                    // 从左向右揭示：网格与日期标签不裁剪，折线/填充/锚点裁剪
                    clipRect(left = 0f, right = padLeft + chartWidth * progress) {
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    accent.copy(alpha = 0.22f),
                                    accent.copy(alpha = 0f),
                                ),
                                startY = padTop,
                                endY = padTop + chartHeight,
                            ),
                        )
                        drawPath(
                            path = linePath,
                            color = accent,
                            style = Stroke(
                                width = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                        points.forEachIndexed { index, point ->
                            val x = xOf(index)
                            val y = yOf(point.value)
                            if (index == points.lastIndex) {
                                // 最新一次：实心锚点 + 底色描边
                                drawCircle(color = palette.sheet, radius = 5.dp.toPx(), center = Offset(x, y))
                                drawCircle(color = accent, radius = 4.dp.toPx(), center = Offset(x, y))
                            } else {
                                drawCircle(
                                    color = accent.copy(alpha = 0.55f),
                                    radius = 2.5.dp.toPx(),
                                    center = Offset(x, y),
                                )
                            }
                        }
                    }

                    // 首/末日期标签
                    labelPaint.textAlign = Paint.Align.LEFT
                    drawContext.canvas.nativeCanvas.drawText(
                        points.first().label,
                        padLeft,
                        size.height - 3.dp.toPx(),
                        labelPaint,
                    )
                    labelPaint.textAlign = Paint.Align.RIGHT
                    drawContext.canvas.nativeCanvas.drawText(
                        points.last().label,
                        padLeft + chartWidth,
                        size.height - 3.dp.toPx(),
                        labelPaint,
                    )
                }

                if (points.size == 1) {
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
    String.format(Locale.getDefault(), "%.${style.decimals}f", value)
