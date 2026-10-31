package com.example.dianzicheng.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.data.ble.BleScaleClient
import com.example.dianzicheng.ui.state.ScaleUiState
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 应用自有语义色：健康/达标绿（Miuix 无 success token，此处为产品自有状态色）。 */
val HealthGreen = Color(0xFF4CAF50)

// ── 健康区间判定（纯函数，界面与历史记录卡共用） ─────────────────────────────

/** BMI 区间：<18.5 偏瘦｜<24.0 标准｜<28.0 超重｜否则 肥胖。 */
fun getBmiStatus(bmi: Double): String = when {
    bmi < 18.5 -> "偏瘦"
    bmi < 24.0 -> "标准"
    bmi < 28.0 -> "超重"
    else -> "肥胖"
}

/** 体脂率区间（按性别）：男 <8 偏低｜<20 标准｜<25 偏高｜否则 肥胖；女 <17/<30/<35。 */
fun getFatStatus(fatPct: Double, isMale: Boolean): String = if (isMale) {
    when {
        fatPct < 8.0 -> "偏低"
        fatPct < 20.0 -> "标准"
        fatPct < 25.0 -> "偏高"
        else -> "肥胖"
    }
} else {
    when {
        fatPct < 17.0 -> "偏低"
        fatPct < 30.0 -> "标准"
        fatPct < 35.0 -> "偏高"
        else -> "肥胖"
    }
}

/** 水分区间：50.0%..65.0% 为「标准」，其余「注意」。 */
fun getWaterStatus(waterPct: Double): String =
    if (waterPct in 50.0..65.0) "标准" else "注意"

// ── 状态徽标 ───────────────────────────────────────────────────────────────

/**
 * 健康区间徽标：「标准」用健康绿，其余（偏瘦/超重/肥胖/偏低/偏高/注意）用 error 红。
 * 底色为对应色 10% 透明度（真实透明度需求，非禁用态模拟）。
 */
@Composable
fun StatusBadge(text: String) {
    val color = if (text == "标准") HealthGreen else MiuixTheme.colorScheme.error
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

// ── 连接状态 chip ──────────────────────────────────────────────────────────

/**
 * 测量页连接状态指示：8dp 圆点 + 文案。
 * 优先级：蓝牙关闭 → 未绑定设备 → 按连接状态展示（设备名优先取实际连接的那台）。
 */
@Composable
fun ConnectionStatusChip(uiState: ScaleUiState) {
    val deviceName = uiState.discoveredDeviceName ?: uiState.pairedDeviceName
    val (dotColor, text) = when {
        !uiState.isBluetoothEnabled -> MiuixTheme.colorScheme.error to "手机蓝牙已关闭"
        !uiState.isDeviceRemembered -> MiuixTheme.colorScheme.error to "未绑定设备（自动连接已暂停）"
        else -> when (uiState.connection) {
            BleScaleClient.ConnectionState.IDLE ->
                MiuixTheme.colorScheme.outline to ("就绪" + (deviceName?.let { " · $it" } ?: ""))
            BleScaleClient.ConnectionState.SCANNING ->
                MiuixTheme.colorScheme.primary to ("正在寻找" + (deviceName?.let { " · $it" } ?: " · 搜索中"))
            BleScaleClient.ConnectionState.CONNECTING ->
                MiuixTheme.colorScheme.secondary to ("正在连接" + (deviceName?.let { " · $it" } ?: ""))
            BleScaleClient.ConnectionState.CONNECTED ->
                HealthGreen to ("已连接" + (deviceName?.let { " · $it" } ?: ""))
            BleScaleClient.ConnectionState.MEASURING ->
                HealthGreen to ("测量中" + (deviceName?.let { " · $it" } ?: ""))
        }
    }
    Surface(
        color = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
        }
    }
}

// ── 体重大读数 ─────────────────────────────────────────────────────────────

/**
 * 体重读数光环（产品核心表达）：径向渐变 + 大号数字。
 * 称重中（未稳定）数字呼吸闪烁；空态显示灰色 0.00。
 */
@Composable
fun WeightDisplay(
    weightKg: Double,
    isStable: Boolean,
    isCompact: Boolean,
    modifier: Modifier = Modifier
) {
    val diameter = if (isCompact) 180.dp else 240.dp
    val fontSize = if (isCompact) 60.sp else 76.sp
    val blinking = if (!isStable && weightKg > 0) {
        val transition = rememberInfiniteTransition(label = "weight-blink")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
            label = "weight-alpha"
        )
        alpha
    } else {
        1f
    }
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        0f to MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        1f to Color.Transparent
                    )
                )
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                // 产品核心读数：固定两位小数、超大字号（超出 Miuix 字阶的有意定制）
                text = String.format("%.2f", weightKg),
                style = MiuixTheme.textStyles.headline1.copy(fontSize = fontSize),
                fontWeight = FontWeight.Black,
                color = if (weightKg > 0) MiuixTheme.colorScheme.onBackground
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.alpha(blinking)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "kg",
                style = MiuixTheme.textStyles.title3,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .alpha(blinking)
            )
        }
    }
}

// ── 结果卡指标格 ───────────────────────────────────────────────────────────

/** 单个指标格：标签 + 数值 + 可选健康区间徽标（垂直居中排布）。 */
@Composable
fun MetricItem(
    label: String,
    value: String,
    badge: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
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
            Spacer(modifier = Modifier.height(4.dp))
            StatusBadge(text = badge)
        }
    }
}

/** 全宽细进度条 + 文案（扫描中等不确定进度场景）。 */
@Composable
fun InlineProgressRow(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        top.yukonga.miuix.kmp.basic.LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceSecondary
        )
    }
}
