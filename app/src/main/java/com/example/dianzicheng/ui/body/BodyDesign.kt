package com.example.dianzicheng.ui.body

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 身体报告单页的设计 token（A 组 · 契约 §1）。
 *
 * 设计原则：
 * - 状态色（达标 / 临界 / 高危）与表面色一律使用交付方案给定的**固定色值**（浅/深两套），
 *   不跟随 Miuix 动态取色——这些颜色承担健康语义，不能随后盖或壁纸漂移；
 * - 主题主色（primary / onPrimary）取 Miuix 语义 token，用于选中描边、表盘与主按钮；
 * - 全部尺寸为 dp/sp，不写死屏幕像素。
 *
 * @property background     页面底色
 * @property backgroundGlow 页面/表盘背后的柔光（已含透明度，可直接用于径向渐变）
 * @property sheet          历史抽屉底色（约 94% 不透明，可透出下层主舞台）
 * @property card           指标卡底色
 * @property cardSelected   选中指标卡的淡色底（与 1.5dp 主题色描边配合）
 * @property textPrimary    一级文字
 * @property textSecondary  二级文字
 * @property primary        主题主色（Miuix 语义色，动态取色）
 * @property onPrimary      主色上的文字色（Miuix 语义色）
 * @property good           达标（固定 token）
 * @property warn           临界（固定 token）
 * @property danger         高危（固定 token）
 * @property divider        分隔线
 * @property track          轨道 / 进度条底（固定中性色）
 * @property scrim          抽屉遮罩底色。**为不透明基色**：全展开渐显时由调用方叠加
 *                          `copy(alpha = 0.45f * progress)`（契约 §4 上限 45%）。
 */
@Immutable
data class BodyPalette(
    val background: Color,
    val backgroundGlow: Color,
    val sheet: Color,
    val card: Color,
    val cardSelected: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val primary: Color,
    val onPrimary: Color,
    val good: Color,
    val warn: Color,
    val danger: Color,
    val divider: Color,
    val track: Color,
    val scrim: Color,
)

// ── 浅色固定 token（交付方案） ────────────────────────────────────────────────
private val LightBackground = Color(0xFFF1F5F9)
private val LightSheet = Color(0xF0FFFFFF) // #FFFFFF · 约 94% 不透明
private val LightCard = Color(0xFFF8FAFC)
private val LightTextPrimary = Color(0xFF0F172A)
private val LightTextSecondary = Color(0xFF64748B)
private val LightGood = Color(0xFF10B981)
private val LightWarn = Color(0xFFF59E0B)
private val LightDanger = Color(0xFFEF4444)
private val LightDivider = Color(0xFFE2E8F0)
private val LightTrack = Color(0xFFE2E8F0)
private val LightScrim = Color(0xFF020617) // slate-950，透明度由调用方叠加

// ── 深色固定 token（交付方案） ────────────────────────────────────────────────
private val DarkBackground = Color(0xFF0B0F19)
private val DarkSheet = Color(0xF01E293B) // #1E293B · 约 94% 不透明
private val DarkCard = Color(0xFF0F172A)
private val DarkTextPrimary = Color(0xFFF8FAFC)
private val DarkTextSecondary = Color(0xFF94A3B8)
private val DarkGood = Color(0xFF34D399)
private val DarkWarn = Color(0xFFFBBF24)
private val DarkDanger = Color(0xFFF87171)
private val DarkDivider = Color(0xFF1E293B)
private val DarkTrack = Color(0xFF1E293B)
private val DarkScrim = Color(0xFF000000)

/**
 * 当前明暗模式下的身体报告色板。
 *
 * primary / onPrimary 来自 [MiuixTheme] 语义 token（跟随系统动态取色）；
 * 其余为交付方案固定 token。用 remember 缓存，避免每张卡片重复分配。
 */
@Composable
fun bodyPalette(): BodyPalette {
    val dark = isSystemInDarkTheme()
    val primary = MiuixTheme.colorScheme.primary
    val onPrimary = MiuixTheme.colorScheme.onPrimary
    return remember(dark, primary, onPrimary) {
        if (dark) {
            BodyPalette(
                background = DarkBackground,
                backgroundGlow = primary.copy(alpha = 0.22f),
                sheet = DarkSheet,
                card = DarkCard,
                // 选中卡淡底取自主色：与 1.5dp 主题色描边天然同色系，且跟随动态取色
                cardSelected = primary.copy(alpha = 0.16f).compositeOver(DarkCard),
                textPrimary = DarkTextPrimary,
                textSecondary = DarkTextSecondary,
                primary = primary,
                onPrimary = onPrimary,
                good = DarkGood,
                warn = DarkWarn,
                danger = DarkDanger,
                divider = DarkDivider,
                track = DarkTrack,
                scrim = DarkScrim,
            )
        } else {
            BodyPalette(
                background = LightBackground,
                backgroundGlow = primary.copy(alpha = 0.12f),
                sheet = LightSheet,
                card = LightCard,
                cardSelected = primary.copy(alpha = 0.10f).compositeOver(LightCard),
                textPrimary = LightTextPrimary,
                textSecondary = LightTextSecondary,
                primary = primary,
                onPrimary = onPrimary,
                good = LightGood,
                warn = LightWarn,
                danger = LightDanger,
                divider = LightDivider,
                track = LightTrack,
                scrim = LightScrim,
            )
        }
    }
}

/**
 * 指标状态 → 固定状态色（**不使用** Miuix 动态色，保证设计还原）。
 *
 * 供卡片状态点、范围条游标、趋势图折线共用（B 组图表亦可复用）。
 */
fun BodyPalette.toneColor(tone: MetricTone): Color = when (tone) {
    MetricTone.GOOD -> good
    MetricTone.WARN -> warn
    MetricTone.DANGER -> danger
    MetricTone.NEUTRAL -> textSecondary
}

/**
 * 尺寸 token（契约 §1）：76 / 96 / 84 / 68dp 卡片、10dp 间距、16dp 圆角、240dp 表盘、4dp 环。
 */
object BodyDimens {
    /** 页面左右安全边距 */
    val PagePadding: Dp = 16.dp

    /** 卡片间距（Bento 四行） */
    val CardGap: Dp = 10.dp

    /** 指标卡圆角 */
    val CardRadius: Dp = 16.dp

    /** 历史抽屉顶部圆角 */
    val SheetRadius: Dp = 28.dp

    /** 第一行：体重 / BMI */
    val SummaryCardHeight: Dp = 76.dp

    /** 第二行：体脂 / 肌肉（视觉主场，最高） */
    val ShapeCardHeight: Dp = 96.dp

    /** 第三行：水分 / 内脏脂肪 */
    val MetabolicCardHeight: Dp = 84.dp

    /** 第四行：蛋白质 / 骨量 */
    val CompactCardHeight: Dp = 68.dp

    /** 状态胶囊高度（28dp 高 / 14dp 圆角） */
    val StatusCapsuleHeight: Dp = 28.dp

    /** 主表盘直径 */
    val DialSize: Dp = 240.dp

    /** 主表盘外环线宽 */
    val DialStroke: Dp = 4.dp
}

/**
 * 数字专用字阶：Miuix 主字阶 + 固定字号 + 等宽数字。
 *
 * 说明（MIUIX_STYLE §1 的例外项）：表盘 64sp、卡片读数 21–27sp 是产品核心读数的有意定制，
 * 超出 Miuix 字阶，故显式覆写 fontSize；字重默认 SemiBold。
 * 所有数字启用 `tnum`（[TextStyle.fontFeatureSettings]），称重跳字与滚动动画时水平不抖。
 *
 * @param size   字号（表盘 64sp / 卡片 21–27sp）
 * @param weight 字重，默认 SemiBold
 */
@Composable
fun bodyNumberStyle(size: TextUnit, weight: FontWeight = FontWeight.SemiBold): TextStyle =
    MiuixTheme.textStyles.main.copy(
        fontSize = size,
        fontWeight = weight,
        fontFeatureSettings = "tnum",
    )

/**
 * 卡片标题 / 单位 / 辅助文案的小字号样式（footnote 字阶 + 可覆写字号）。
 *
 * 与 [bodyNumberStyle] 一样启用 `tnum`：标题里也可能出现数字（如「BMI」「1..9 级」）。
 *
 * @param size   字号（标题 11–12sp、单位 9–10sp、表盘 kg 18sp）
 * @param weight 字重，默认 Normal
 */
@Composable
fun bodyLabelStyle(
    size: TextUnit = 13.sp,
    weight: FontWeight = FontWeight.Normal,
): TextStyle = MiuixTheme.textStyles.footnote1.copy(
    fontSize = size,
    fontWeight = weight,
    fontFeatureSettings = "tnum",
)
