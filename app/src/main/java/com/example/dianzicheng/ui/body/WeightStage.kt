package com.example.dianzicheng.ui.body

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

/** 主舞台测量阶段。 */
enum class StagePhase { IDLE, LIVE, LOCKED, CALCULATING, COMPLETE, OFFLINE }

/** 顶部状态胶囊：28dp 高、14dp 圆角、6dp 呼吸圆点。 */
@Composable
fun StatusCapsule(
    text: String,
    dotColor: Color,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = bodyPalette()
    // 呼吸动画只在活跃态（扫描/连接/测量中，active=true）运行；待机/离线/完成后用静态圆点，
    // 避免 rememberInfiniteTransition 常驻逐帧失效（前台待机掉电/掉帧来源之一，性能审计 M6）。
    // 动画值只在图层阶段读取：仅失效 alpha 图层，不逐帧重绘 Canvas、不触发重组。
    val dotAlpha = if (active) {
        val transition = rememberInfiniteTransition(label = "status-dot")
        transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "status-dot-alpha",
        )
    } else {
        remember { mutableStateOf(0.8f) }
    }
    Surface(
        onClick = onClick,
        modifier = modifier.height(BodyDimens.StatusCapsuleHeight),
        shape = RoundedCornerShape(14.dp),
        color = palette.card.copy(alpha = 0.90f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(
                modifier = Modifier
                    .size(6.dp)
                    .graphicsLayer { alpha = dotAlpha.value }
            ) {
                drawCircle(color = dotColor)
            }
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = text,
                style = bodyLabelStyle(11.sp, FontWeight.Medium),
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 核心称重表盘：240dp、4dp 外环、64sp 体重 + 18sp kg。
 *
 * - LOCKED：数字两次 150ms 透明度闪烁（两次脉冲共约 300ms）；
 * - CALCULATING：顺时针 1000ms 流光扫描；
 * - COMPLETE：完整光环 + 绿色端点；
 * - stageScale 由抽屉状态机传入，表盘以中心为原点缩放。
 */
@Composable
fun WeightDial(
    weightKg: Double,
    phase: StagePhase,
    stageScale: Float,
    modifier: Modifier = Modifier,
) {
    val palette = bodyPalette()
    val blink = remember { Animatable(1f) }
    LaunchedEffect(phase) {
        if (phase == StagePhase.LOCKED) {
            repeat(2) {
                blink.animateTo(0.22f, tween(75))
                blink.animateTo(1f, tween(75))
            }
        } else {
            blink.snapTo(1f)
        }
    }

    // 只有分析中才启动常驻旋转动画；IDLE/COMPLETE 不创建无限 transition，避免空转重绘。
    val rotation = if (phase == StagePhase.CALCULATING) {
        val scanTransition = rememberInfiniteTransition(label = "dial-scan")
        val scanRotation by scanTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "dial-rotation",
        )
        scanRotation
    } else {
        0f
    }

    val scale = stageScale.coerceIn(0.5f, 1f)
    Box(
        modifier = modifier
            .size(BodyDimens.DialSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin.Center
            },
        contentAlignment = Alignment.Center,
    ) {
        // 背景径向微光
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        0f to palette.metricAccents.weight.copy(alpha = 0.18f),
                        0.52f to palette.metricAccents.muscle.copy(alpha = 0.07f),
                        1f to Color.Transparent,
                    )
                )
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = BodyDimens.DialStroke.toPx()
            // 4dp 环以表盘边缘为中心线：外径正好 240dp，而不是再向内缩 8dp。
            val inset = strokePx / 2f
            val arcSize = androidx.compose.ui.geometry.Size(
                width = size.width - inset * 2f,
                height = size.height - inset * 2f,
            )
            val topLeft = Offset(inset, inset)
            val center = Offset(size.width / 2f, size.height / 2f)

            drawArc(
                color = palette.track.copy(alpha = 0.65f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )

            when (phase) {
                StagePhase.CALCULATING -> {
                    rotate(rotation, center) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    palette.metricAccents.weight.copy(alpha = 0.18f),
                                    palette.metricAccents.muscle,
                                    palette.metricAccents.bmi.copy(alpha = 0.7f),
                                    Color.Transparent,
                                ),
                                center = center,
                            ),
                            startAngle = -90f,
                            sweepAngle = 150f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round),
                        )
                    }
                }

                StagePhase.COMPLETE -> {
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                palette.metricAccents.weight,
                                palette.metricAccents.muscle,
                                palette.good,
                                palette.metricAccents.bmi,
                                palette.metricAccents.weight,
                            ),
                            center = center,
                        ),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                    drawCircle(
                        color = palette.good,
                        radius = strokePx * 1.5f,
                        center = Offset(size.width / 2f, inset),
                    )
                }

                StagePhase.LOCKED -> {
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                palette.metricAccents.weight,
                                palette.metricAccents.bmi,
                                palette.metricAccents.muscle,
                            ),
                            center = center,
                        ),
                        startAngle = -90f,
                        sweepAngle = 300f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }

                StagePhase.LIVE -> {
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                palette.metricAccents.water.copy(alpha = 0.85f),
                                palette.metricAccents.weight.copy(alpha = 0.85f),
                            ),
                            center = center,
                        ),
                        startAngle = -90f,
                        sweepAngle = 210f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }

                StagePhase.IDLE, StagePhase.OFFLINE -> Unit
            }
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.graphicsLayer { alpha = blink.value },
        ) {
            Text(
                text = String.format(Locale.US, "%.2f", weightKg.coerceAtLeast(0.0)),
                style = bodyNumberStyle(64.sp, FontWeight.Black),
                color = if (weightKg > 0.0) palette.textPrimary else palette.textSecondary,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "kg",
                style = bodyLabelStyle(18.sp, FontWeight.Medium),
                color = palette.textSecondary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
    }
}

/** 主操作按钮（开始称重 / 重新测量 / 搜索设备）。 */
@Composable
fun StagePrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        cornerRadius = 26.dp,
        colors = ButtonDefaults.buttonColorsPrimary(),
        modifier = modifier.height(52.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.button,
        )
    }
}
