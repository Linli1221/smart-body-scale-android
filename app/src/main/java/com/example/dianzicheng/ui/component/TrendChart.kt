package com.example.dianzicheng.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.domain.BodyMetric
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 指标走势图（应用自绘，Miuix 无图表组件）：
 * 平滑贝塞尔折线 + 主色渐变填充 + 极值/首尾点高亮 + 从左向右入场揭示。
 *
 * @param metric   指标（决定单位、小数位与标题）
 * @param values   有效数值序列（按时间升序，已过滤空值）
 * @param timestamps 与 [values] 对齐的时间戳（仅用于首/末日期标签）
 */
@Composable
fun MetricTrendChart(
    metric: BodyMetric,
    values: List<Double>,
    timestamps: List<Long>,
    modifier: Modifier = Modifier
) {
    val primary = MiuixTheme.colorScheme.primary
    val gridColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.12f)
    val labelColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.7f)
    val pointCoreColor = MiuixTheme.colorScheme.surfaceContainer

    val minVal = values.minOrNull() ?: 0.0
    val maxVal = values.maxOrNull() ?: 0.0
    val hasDiff = maxVal > minVal
    val pad = if (metric.decimals == 0) 1.0 else Math.pow(10.0, -metric.decimals.toDouble())
    val lowerBound = if (hasDiff) minVal else minVal - pad
    val valueRange = if (hasDiff) maxVal - minVal else pad * 2
    val fmt = "%.${metric.decimals}f"

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${metric.label}走势",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Text(
                    text = "近${values.size}次",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            // 极值行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (hasDiff) {
                    Text(
                        text = "↓ " + String.format(Locale.getDefault(), fmt, minVal) + metric.unit,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    Text(
                        text = "↑ " + String.format(Locale.getDefault(), fmt, maxVal) + metric.unit,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                } else {
                    Text(
                        text = "均值: " + String.format(Locale.getDefault(), fmt, minVal) + metric.unit,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            val density = LocalDensity.current
            val progress = remember { Animatable(0f) }
            LaunchedEffect(values) {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(900))
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            ) {
                val paddingLeft = 8.dp.toPx()
                val paddingRight = 8.dp.toPx()
                val paddingTop = 12.dp.toPx()
                val paddingBottom = 24.dp.toPx()
                val chartW = size.width - paddingLeft - paddingRight
                val chartH = size.height - paddingTop - paddingBottom

                fun xOf(i: Int): Float =
                    if (values.size <= 1) paddingLeft else paddingLeft + i.toFloat() / (values.size - 1) * chartW

                fun yOf(v: Double): Float =
                    (paddingTop + chartH * (1 - (v - lowerBound) / valueRange)).toFloat()

                // 网格（3 条水平线，不参与揭示裁剪）
                for (gi in 0..2) {
                    val gy = paddingTop + chartH * gi / 2
                    drawLine(
                        color = gridColor,
                        start = Offset(paddingLeft, gy),
                        end = Offset(paddingLeft + chartW, gy),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                if (values.size >= 2) {
                    // 折线 + 填充路径（平滑贝塞尔）
                    val linePath = Path().apply {
                        moveTo(xOf(0), yOf(values[0]))
                        for (i in 1 until values.size) {
                            val x0 = xOf(i - 1)
                            val y0 = yOf(values[i - 1])
                            val x1 = xOf(i)
                            val y1 = yOf(values[i])
                            val cx = (x0 + x1) / 2
                            cubicTo(cx, y0, cx, y1, x1, y1)
                        }
                    }
                    val fillPath = Path().apply {
                        addPath(linePath)
                        lineTo(xOf(values.size - 1), size.height - paddingBottom)
                        lineTo(xOf(0), size.height - paddingBottom)
                        close()
                    }

                    // 入场揭示：网格/日期标签不裁剪，仅折线与填充自左向右显现
                    clipRect(left = 0f, right = paddingLeft + chartW * progress.value) {
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                0f to primary.copy(alpha = 0.25f),
                                1f to primary.copy(alpha = 0f),
                                startY = paddingTop,
                                endY = size.height - paddingBottom
                            )
                        )
                        drawPath(
                            path = linePath,
                            color = primary,
                            style = Stroke(
                                width = 2.5.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }

                    // 点标记
                    val minIdx = values.indexOf(minVal)
                    val maxIdx = values.indexOf(maxVal)
                    values.forEachIndexed { i, v ->
                        val x = xOf(i)
                        val y = yOf(v)
                        val highlighted = hasDiff && (i == maxIdx || i == minIdx || i == 0 || i == values.size - 1)
                        if (highlighted) {
                            drawCircle(color = pointCoreColor, radius = 5.dp.toPx(), center = Offset(x, y))
                            drawCircle(
                                color = primary,
                                radius = 4.dp.toPx(),
                                center = Offset(x, y),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        } else {
                            drawCircle(
                                color = primary.copy(alpha = 0.4f),
                                radius = 2.5.dp.toPx(),
                                center = Offset(x, y)
                            )
                        }
                    }
                }

                // 首/末日期标签
                if (timestamps.isNotEmpty()) {
                    val fmtDate = SimpleDateFormat("MM/dd", Locale.getDefault())
                    val paint = android.graphics.Paint().apply {
                        color = labelColor.toArgb()
                        textSize = with(density) { 10.sp.toPx() }
                        isAntiAlias = true
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        fmtDate.format(Date(timestamps.first())),
                        paddingLeft,
                        size.height - 4.dp.toPx(),
                        paint
                    )
                    paint.textAlign = android.graphics.Paint.Align.RIGHT
                    drawContext.canvas.nativeCanvas.drawText(
                        fmtDate.format(Date(timestamps.last())),
                        paddingLeft + chartW,
                        size.height - 4.dp.toPx(),
                        paint
                    )
                }
            }
        }
    }
}
