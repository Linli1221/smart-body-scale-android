package com.example.dianzicheng.ui.body

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import java.util.Locale

/**
 * 8 项指标 Bento 网格。
 *
 * 四行结构：体重+BMI / 体脂+肌肉 / 水分+内脏脂肪 / 蛋白质+骨量。
 * 卡片高度严格按交付方案（76/96/84/68dp）；无 BIA 时在体成分第一行上方插入提示条。
 */
@Composable
fun BodyMetricGrid(
    report: BodyReport,
    selectedMetric: MetricKind?,
    reveal: Boolean,
    onMetricClick: (MetricKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = bodyPalette()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BodyDimens.CardGap),
    ) {
        MetricRow {
            WeightSummaryCard(
                report = report,
                selected = selectedMetric == MetricKind.WEIGHT,
                reveal = reveal,
                order = 0,
                accent = palette.metricAccents.forMetric(MetricKind.WEIGHT),
                onClick = { onMetricClick(MetricKind.WEIGHT) },
                modifier = Modifier.weight(1f),
            )
            BmiSummaryCard(
                report = report,
                selected = selectedMetric == MetricKind.BMI,
                reveal = reveal,
                order = 0,
                accent = palette.metricAccents.forMetric(MetricKind.BMI),
                onClick = { onMetricClick(MetricKind.BMI) },
                modifier = Modifier.weight(1f),
            )
        }

        if (!report.hasBia) {
            BiaUnavailableBanner(
                palette = palette,
                reveal = reveal,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        MetricRow {
            RangeMetricCard(
                reading = report.readings.getValue(MetricKind.BODY_FAT),
                selected = selectedMetric == MetricKind.BODY_FAT,
                reveal = reveal,
                order = 1,
                onClick = { onMetricClick(MetricKind.BODY_FAT) },
                modifier = Modifier.weight(1f),
            )
            RangeMetricCard(
                reading = report.readings.getValue(MetricKind.MUSCLE),
                selected = selectedMetric == MetricKind.MUSCLE,
                reveal = reveal,
                order = 1,
                onClick = { onMetricClick(MetricKind.MUSCLE) },
                modifier = Modifier.weight(1f),
            )
        }

        MetricRow {
            RiskMetricCard(
                reading = report.readings.getValue(MetricKind.WATER),
                selected = selectedMetric == MetricKind.WATER,
                reveal = reveal,
                order = 2,
                icon = RiskIcon.WATER,
                onClick = { onMetricClick(MetricKind.WATER) },
                modifier = Modifier.weight(1f),
            )
            RiskMetricCard(
                reading = report.readings.getValue(MetricKind.VISCERAL_FAT),
                selected = selectedMetric == MetricKind.VISCERAL_FAT,
                reveal = reveal,
                order = 2,
                icon = RiskIcon.SHIELD,
                onClick = { onMetricClick(MetricKind.VISCERAL_FAT) },
                modifier = Modifier.weight(1f),
            )
        }

        MetricRow {
            CompactMetricCard(
                reading = report.readings.getValue(MetricKind.PROTEIN),
                selected = selectedMetric == MetricKind.PROTEIN,
                reveal = reveal,
                order = 3,
                onClick = { onMetricClick(MetricKind.PROTEIN) },
                modifier = Modifier.weight(1f),
            )
            CompactMetricCard(
                reading = report.readings.getValue(MetricKind.BONE),
                selected = selectedMetric == MetricKind.BONE,
                reveal = reveal,
                order = 3,
                onClick = { onMetricClick(MetricKind.BONE) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BodyDimens.CardGap),
        content = content,
    )
}

// ── 顶层双基准摘要卡 ───────────────────────────────────────────────────────

@Composable
private fun WeightSummaryCard(
    report: BodyReport,
    selected: Boolean,
    reveal: Boolean,
    order: Int,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = bodyPalette()
    val reading = report.readings.getValue(MetricKind.WEIGHT)
    MetricCardFrame(
        selected = selected,
        height = BodyDimens.SummaryCardHeight,
        order = order,
        reveal = reveal,
        accent = accent,
        onClick = onClick,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = reading.title,
                    style = bodyLabelStyle(13.sp),
                    color = palette.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                WeightDeltaPill(report = report)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                MetricNumber(
                    value = reading.numericValue,
                    decimals = 2,
                    play = reveal,
                    delayMillis = order * 50,
                    style = bodyNumberStyle(28.sp, FontWeight.Bold),
                    color = accent,
                    secondaryColor = palette.textSecondary,
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "kg",
                    style = bodyLabelStyle(11.sp),
                    color = palette.textSecondary,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun WeightDeltaPill(report: BodyReport) {
    val palette = bodyPalette()
    val delta = report.weightDeltaKg
    val (text, color) = when {
        report.isFirstRecord -> "首次基准记录" to palette.primary
        delta == null -> "暂无对比" to palette.textSecondary
        kotlin.math.abs(delta) < 0.005 -> "与上次持平" to palette.textSecondary
        delta < 0 -> String.format(
            Locale.US,
            "↓ %.2f kg",
            kotlin.math.abs(delta),
        ) to palette.good
        else -> String.format(Locale.US, "↑ %.2f kg", delta) to palette.warn
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            style = bodyLabelStyle(9.5.sp, FontWeight.Medium),
            color = color,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun BmiSummaryCard(
    report: BodyReport,
    selected: Boolean,
    reveal: Boolean,
    order: Int,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = bodyPalette()
    val reading = report.readings.getValue(MetricKind.BMI)
    MetricCardFrame(
        selected = selected,
        height = BodyDimens.SummaryCardHeight,
        order = order,
        reveal = reveal,
        accent = accent,
        onClick = onClick,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = reading.title,
                    style = bodyLabelStyle(13.sp),
                    color = palette.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                reading.status?.let { StatusPill(status = it) }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                MetricNumber(
                    value = reading.numericValue,
                    decimals = 1,
                    play = reveal,
                    delayMillis = order * 50,
                    style = bodyNumberStyle(28.sp, FontWeight.Bold),
                    color = accent,
                    secondaryColor = palette.textSecondary,
                )
            }
        }
    }
}

// ── 第一行：核心塑形卡（体脂率 / 肌肉量） ─────────────────────────────────

@Composable
private fun RangeMetricCard(
    reading: MetricReading,
    selected: Boolean,
    reveal: Boolean,
    order: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = bodyPalette()
    val accent = palette.metricAccents.forMetric(reading.kind)
    MetricCardFrame(
        selected = selected,
        height = BodyDimens.ShapeCardHeight,
        order = order,
        reveal = reveal,
        accent = accent,
        onClick = onClick,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = reading.title,
                    style = bodyLabelStyle(13.sp),
                    color = palette.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                reading.status?.let {
                    Text(
                        text = it.label,
                        style = bodyLabelStyle(10.sp, FontWeight.Medium),
                        color = palette.toneColor(it.tone),
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                MetricNumber(
                    value = reading.numericValue,
                    decimals = 1,
                    play = reveal,
                    delayMillis = order * 50,
                    style = bodyNumberStyle(24.sp),
                    color = accent,
                    secondaryColor = palette.textSecondary,
                )
                if (reading.unit.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = reading.unit,
                        style = bodyLabelStyle(10.sp),
                        color = palette.textSecondary,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            // 交付方案只为第一行「体脂/肌肉」定义三色区间条；
            // 84dp/68dp 行保持高密度数值排版，不再塞入额外观测条。
            RangeBar(
                spec = reading.range,
                palette = palette,
                accent = accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
            )
        }
    }
}

@Composable
private fun RangeBar(
    spec: RangeBarSpec?,
    palette: BodyPalette,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val barHeight = 4.dp.toPx()
        val top = (size.height - barHeight) / 2f
        val radius = androidx.compose.ui.geometry.CornerRadius(barHeight / 2f)
        if (spec == null) {
            drawRoundRect(
                color = palette.track,
                topLeft = Offset(0f, top),
                size = Size(size.width, barHeight),
                cornerRadius = radius,
            )
            return@Canvas
        }
        val span = (spec.max - spec.min).coerceAtLeast(0.001f)
        fun xOf(v: Float): Float = ((v - spec.min) / span).coerceIn(0f, 1f) * size.width
        val lowEnd = xOf(spec.standardStart)
        val highEnd = xOf(spec.standardEnd)
        val gap = 1.dp.toPx()

        drawRoundRect(
            color = palette.warn.copy(alpha = 0.55f),
            topLeft = Offset(0f, top),
            size = Size(lowEnd.coerceAtLeast(0f), barHeight),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = palette.good,
            topLeft = Offset((lowEnd + gap).coerceAtMost(size.width), top),
            size = Size((highEnd - lowEnd - gap * 2f).coerceAtLeast(0f), barHeight),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = palette.danger.copy(alpha = 0.7f),
            topLeft = Offset((highEnd + gap).coerceAtMost(size.width), top),
            size = Size((size.width - highEnd - gap).coerceAtLeast(0f), barHeight),
            cornerRadius = radius,
        )

        val cursorX = xOf(spec.value)
        val cursorY = size.height / 2f
        drawCircle(
            color = palette.card.copy(alpha = 0.95f),
            radius = 5.dp.toPx(),
            center = Offset(cursorX, cursorY),
        )
        drawCircle(
            color = accent,
            radius = 3.2.dp.toPx(),
            center = Offset(cursorX, cursorY),
        )
        drawCircle(
            color = palette.card,
            radius = 1.2.dp.toPx(),
            center = Offset(cursorX, cursorY),
        )
    }
}

// ── 第二行：代谢与风险卡（水分 / 内脏脂肪） ───────────────────────────────

private enum class RiskIcon { WATER, SHIELD }

@Composable
private fun RiskMetricCard(
    reading: MetricReading,
    selected: Boolean,
    reveal: Boolean,
    order: Int,
    icon: RiskIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = bodyPalette()
    val accent = palette.metricAccents.forMetric(reading.kind)
    MetricCardFrame(
        selected = selected,
        height = BodyDimens.MetabolicCardHeight,
        order = order,
        reveal = reveal,
        accent = accent,
        onClick = onClick,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = reading.title,
                    style = bodyLabelStyle(13.sp),
                    color = palette.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                reading.status?.let {
                    Text(
                        text = it.label,
                        style = bodyLabelStyle(10.sp, FontWeight.Medium),
                        color = palette.toneColor(it.tone),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    MetricNumber(
                        value = reading.numericValue,
                        decimals = if (reading.kind == MetricKind.VISCERAL_FAT) 0 else 1,
                        play = reveal,
                        delayMillis = order * 50,
                        style = bodyNumberStyle(22.sp),
                        color = accent,
                        secondaryColor = palette.textSecondary,
                    )
                    if (reading.unit.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = reading.unit,
                            style = bodyLabelStyle(10.sp),
                            color = palette.textSecondary,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                }
                RiskGlyph(
                    icon = icon,
                    color = reading.status?.let { palette.toneColor(it.tone) } ?: accent,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

@Composable
private fun RiskGlyph(icon: RiskIcon, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(
            width = 1.8.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        when (icon) {
            RiskIcon.WATER -> {
                val drop = Path().apply {
                    moveTo(w * 0.50f, h * 0.08f)
                    cubicTo(w * 0.72f, h * 0.34f, w * 0.82f, h * 0.50f, w * 0.82f, h * 0.63f)
                    cubicTo(w * 0.82f, h * 0.83f, w * 0.67f, h * 0.94f, w * 0.50f, h * 0.94f)
                    cubicTo(w * 0.33f, h * 0.94f, w * 0.18f, h * 0.83f, w * 0.18f, h * 0.63f)
                    cubicTo(w * 0.18f, h * 0.50f, w * 0.28f, h * 0.34f, w * 0.50f, h * 0.08f)
                    close()
                }
                drawPath(drop, color = color.copy(alpha = 0.16f))
                drawPath(drop, color = color, style = stroke)
                val wave = Path().apply {
                    moveTo(w * 0.28f, h * 0.64f)
                    cubicTo(w * 0.38f, h * 0.56f, w * 0.44f, h * 0.72f, w * 0.53f, h * 0.64f)
                    cubicTo(w * 0.62f, h * 0.56f, w * 0.68f, h * 0.68f, w * 0.74f, h * 0.62f)
                }
                drawPath(wave, color = color, style = stroke)
            }

            RiskIcon.SHIELD -> {
                val shield = Path().apply {
                    moveTo(w * 0.50f, h * 0.08f)
                    lineTo(w * 0.82f, h * 0.20f)
                    lineTo(w * 0.82f, h * 0.50f)
                    cubicTo(w * 0.82f, h * 0.72f, w * 0.66f, h * 0.86f, w * 0.50f, h * 0.94f)
                    cubicTo(w * 0.34f, h * 0.86f, w * 0.18f, h * 0.72f, w * 0.18f, h * 0.50f)
                    lineTo(w * 0.18f, h * 0.20f)
                    close()
                }
                drawPath(shield, color = color.copy(alpha = 0.14f))
                drawPath(shield, color = color, style = stroke)
                drawLine(
                    color = color.copy(alpha = 0.7f),
                    start = Offset(w * 0.34f, h * 0.46f),
                    end = Offset(w * 0.66f, h * 0.46f),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color.copy(alpha = 0.5f),
                    start = Offset(w * 0.36f, h * 0.58f),
                    end = Offset(w * 0.64f, h * 0.58f),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

// ── 第三行：基础构成卡（蛋白质 / 骨量） ───────────────────────────────────

@Composable
private fun CompactMetricCard(
    reading: MetricReading,
    selected: Boolean,
    reveal: Boolean,
    order: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = bodyPalette()
    val accent = palette.metricAccents.forMetric(reading.kind)
    MetricCardFrame(
        selected = selected,
        height = BodyDimens.CompactCardHeight,
        order = order,
        reveal = reveal,
        accent = accent,
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = reading.title,
                    style = bodyLabelStyle(13.sp),
                    color = palette.textSecondary,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(1.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    MetricNumber(
                        value = reading.numericValue,
                        decimals = 1,
                        play = reveal,
                        delayMillis = order * 50,
                        style = bodyNumberStyle(20.sp),
                        color = accent,
                        secondaryColor = palette.textSecondary,
                    )
                    if (reading.unit.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = reading.unit,
                            style = bodyLabelStyle(9.sp),
                            color = palette.textSecondary,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
            }
            val status = reading.status
            val dotColor = status?.let { palette.toneColor(it.tone) } ?: palette.track
            Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(color = dotColor)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = status?.label ?: "--",
                style = bodyLabelStyle(10.sp, FontWeight.Medium),
                color = dotColor,
                maxLines = 1,
            )
        }
    }
}

// ── 无 BIA 提示条 ─────────────────────────────────────────────────────────

@Composable
private fun BiaUnavailableBanner(
    palette: BodyPalette,
    reveal: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(
        targetValue = if (reveal) 1f else 0f,
        animationSpec = tween(400),
        label = "bia-warning",
    )
    Surface(
        modifier = modifier
            .height(36.dp)
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 8.dp.toPx()
            },
        color = palette.warn.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = palette.warn, shape = CircleShape) {
                Box(modifier = Modifier.size(6.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "未检测到赤足接触，本次仅记录体重与 BMI",
                style = bodyLabelStyle(11.sp),
                color = palette.textPrimary,
                maxLines = 1,
            )
        }
    }
}

// ── 共用小件 ───────────────────────────────────────────────────────────────

@Composable
private fun MetricCardFrame(
    selected: Boolean,
    height: Dp,
    order: Int,
    reveal: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = bodyPalette()
    val progress by animateFloatAsState(
        targetValue = if (reveal) 1f else 0f,
        animationSpec = tween(
            durationMillis = 400,
            delayMillis = order * 50,
            easing = FastOutSlowInEasing,
        ),
        label = "metric-card-$order",
    )
    val tint = accent.copy(alpha = if (selected) 0.14f else 0.07f)
        .compositeOver(if (selected) palette.cardSelected else palette.card)
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(height)
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 12.dp.toPx()
            },
        shape = RoundedCornerShape(BodyDimens.CardRadius),
        color = tint,
        border = if (selected) BorderStroke(1.5.dp, accent) else null,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to accent.copy(alpha = 0.08f),
                        1f to Color.Transparent,
                    )
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            content = content,
        )
    }
}

@Composable
private fun StatusPill(status: MetricStatus) {
    val palette = bodyPalette()
    val color = palette.toneColor(status.tone)
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = status.label,
            style = bodyLabelStyle(9.5.sp, FontWeight.Medium),
            color = color,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MetricNumber(
    value: Double?,
    decimals: Int,
    play: Boolean,
    delayMillis: Int,
    style: TextStyle,
    color: Color,
    secondaryColor: Color,
) {
    val target = if (play && value != null) value.toFloat() else 0f
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 400, delayMillis = delayMillis),
        label = "metric-number",
    )
    Text(
        text = if (value == null) "--" else String.format(Locale.US, "%.${decimals}f", animated),
        style = style,
        color = if (value == null) secondaryColor else color,
        maxLines = 1,
    )
}
