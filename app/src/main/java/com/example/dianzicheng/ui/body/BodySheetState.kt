package com.example.dianzicheng.ui.body

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 抽屉三锚点。
 *
 * [fraction] 是归一化展开进度：0 = 收起，0.5 = 半展开，1 = 全展开。
 * 注意它**不是**屏幕占比的线性映射——三态可见高度分别是 12% / 62% / 92%，
 * 映射关系见 [BodySheetState.translationForFraction] 的分段线性实现。
 */
enum class SheetAnchor(val fraction: Float) {
    COLLAPSED(0f),
    HALF(0.5f),
    FULL(1f),
}

// ── 视觉/手势常量（契约第 4 节）─────────────────────────────────────────────

/** 抽屉容器固定占总屏高的 92%（固定高度 + 位移，拖拽时内容不重排）。 */
private const val SHEET_HEIGHT_RATIO = 0.92f

/** 收起时露出的高度占屏比。 */
private const val COLLAPSED_VISIBLE_RATIO = 0.12f

/** 半展开时露出的高度占屏比。 */
private const val HALF_VISIBLE_RATIO = 0.62f

/** 收起时容器向下的位移（占屏比）：0.92 - 0.12 = 0.80。 */
private const val COLLAPSED_TRANSLATION_RATIO = SHEET_HEIGHT_RATIO - COLLAPSED_VISIBLE_RATIO

/** 半展开时容器向下的位移（占屏比）：0.92 - 0.62 = 0.30。 */
private const val HALF_TRANSLATION_RATIO = SHEET_HEIGHT_RATIO - HALF_VISIBLE_RATIO

/** 半展开锚点在 [SheetAnchor.fraction] 空间中的位置。 */
private const val HALF_FRACTION = 0.5f

/** 遮罩最大不透明度（仅全展开方向渐显）。 */
private const val MAX_SCRIM_ALPHA = 0.45f

/** 主舞台缩放：收起 1.00 / 半展开 0.72 / 全展开 0.50。 */
private const val COLLAPSED_STAGE_SCALE = 1.00f
private const val HALF_STAGE_SCALE = 0.72f
private const val FULL_STAGE_SCALE = 0.50f

/** 主舞台上移：半展开 80dp，全展开继续略上移（96dp）。 */
private val HALF_STAGE_LIFT = 80.dp
private val FULL_STAGE_LIFT = 96.dp

/** 释放速度达到该阈值时，至少朝手势方向移动到下一个锚点。 */
private const val RELEASE_VELOCITY_DP_PER_SEC = 400f
private const val VELOCITY_PROJECTION_SECONDS = 0.18f
private const val MAX_SPRING_VELOCITY_DP_PER_SEC = 4000f
private const val OVERSCROLL_LIMIT_DP = 80f

/** 弹簧：dampingRatio 0.82、stiffness 280。 */
private val SHEET_SPRING: SpringSpec<Float> = spring(dampingRatio = 0.82f, stiffness = 280f)

/** 锚点按 fraction 升序，用于「速度翻页」的相邻锚点计算。 */
private val ANCHOR_ORDER = listOf(SheetAnchor.COLLAPSED, SheetAnchor.HALF, SheetAnchor.FULL)

/** 越界阻尼：距离越大，手指继续移动所带来的位移越小，最多接近 limit。 */
internal fun rubberBandOffset(raw: Float, lower: Float, upper: Float, limit: Float): Float {
    if (limit <= 0f) return raw.coerceIn(lower, upper)
    fun resisted(distance: Float): Float = limit * distance / (limit + distance)
    return when {
        raw < lower -> lower - resisted(lower - raw)
        raw > upper -> upper + resisted(raw - upper)
        else -> raw
    }
}

/** 按位置与速度投影到实际像素坐标，快速甩动时确保朝手势方向至少吸附一档。 */
internal fun settleAnchorIndex(position: Float, velocity: Float, anchors: List<Float>, threshold: Float): Int {
    require(anchors.isNotEmpty())
    val first = anchors.first()
    val last = anchors.last()
    if (position <= first && velocity <= 0f) return 0
    if (position >= last && velocity >= 0f) return anchors.lastIndex
    val projected = (position + velocity * VELOCITY_PROJECTION_SECONDS).coerceIn(first, last)
    var target = anchors.indices.minByOrNull { abs(anchors[it] - projected) } ?: 0
    if (velocity >= threshold) {
        val next = anchors.indexOfFirst { it > position + 0.5f }
        if (next >= 0) target = maxOf(target, next)
    } else if (velocity <= -threshold) {
        val next = anchors.indexOfLast { it < position - 0.5f }
        if (next >= 0) target = minOf(target, next)
    }
    return target
}

/**
 * 三态抽屉状态机（契约第 4 节）。
 *
 * 内部只在动画值 [offset] 上维护「容器向下位移」，可见高度、遮罩、主舞台缩放全部由它派生，
 * 因此拖拽过程中不会触发内容重排。
 *
 * 派生量（[fraction] / [topOffset] / [scrimAlpha] / [stageScale] / [stageTranslationY]）
 * 都是对 [offset] 的纯函数计算：在组合阶段读取会让调用方逐帧重组，
 * 建议调用方把高频读取放进布局/绘制阶段（如 `Modifier.offset { }`、`Modifier.drawBehind { }`）
 * 或包一层 `derivedStateOf`，本模块的抽屉已按此方式实现。
 *
 * @param screenHeight 屏幕（可用）高度；屏高为 0 时退化为 92% 固定高、无位移。
 * @param density 用于 dp↔px 换算与 400dp/s 阈值换算。
 * @param scope 动画宿主作用域（一般来自 `rememberCoroutineScope()`）。
 * @param initialAnchor 初始锚点。
 */
@Stable
class BodySheetState internal constructor(
    private val screenHeight: Dp,
    private val density: Density,
    private val scope: CoroutineScope,
    initialAnchor: SheetAnchor,
) {
    private val screenHeightPx: Float = with(density) { screenHeight.toPx() }

    /** 收起态位移（px）。 */
    private val collapsedOffsetPx: Float = screenHeightPx * COLLAPSED_TRANSLATION_RATIO

    /** 半展开态位移（px）。 */
    private val halfOffsetPx: Float = screenHeightPx * HALF_TRANSLATION_RATIO

    /** 唯一的动画状态：容器向下位移（px）。0 = 全展开，collapsedOffsetPx = 收起。 */
    private val offset = Animatable(initialValue = translationForFraction(initialAnchor.fraction))

    /** 未阻尼的手指位移，拖拽期间独立累计，避免反向拖拽时跳动。 */
    private var dragTargetPx: Float = translationForFraction(initialAnchor.fraction)

    /** 是否处于手指拖拽中（下一次拖拽起点需要用动画当前值重新对齐）。 */
    private var dragging: Boolean = false

    // ── 对外只读派生量 ───────────────────────────────────────────────────

    /** 归一化展开进度：0 = 收起，0.5 = 半展开，1 = 全展开。 */
    val fraction: Float
        get() = fractionForTranslation(offset.value)

    /** 当前最近的锚点（拖拽过程中连续变化，用于内容分支判断）。 */
    val currentAnchor: SheetAnchor
        get() {
            val current = fraction
            var best = SheetAnchor.COLLAPSED
            var bestDistance = Float.MAX_VALUE
            ANCHOR_ORDER.forEach { anchor ->
                val distance = abs(current - anchor.fraction)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = anchor
                }
            }
            return best
        }

    /** 抽屉容器高度（固定 92% 屏高）。 */
    val sheetHeight: Dp
        get() = (screenHeight * SHEET_HEIGHT_RATIO).coerceAtLeast(0.dp)

    /** 容器向下位移：0 = 全展开；收起时 = 0.80 × 屏高。 */
    val topOffset: Dp
        get() = with(density) { offset.value.toDp() }

    /** 遮罩不透明度：半展开为 0，全展开渐显到 0.45。 */
    val scrimAlpha: Float
        get() {
            val progress = ((fraction - HALF_FRACTION) / (1f - HALF_FRACTION)).coerceIn(0f, 1f)
            return progress * MAX_SCRIM_ALPHA
        }

    /** 主舞台缩放：1.00 → 0.72 → 0.50（按锚点分段线性）。 */
    val stageScale: Float
        get() {
            val current = fraction
            return if (current <= HALF_FRACTION) {
                COLLAPSED_STAGE_SCALE +
                    (HALF_STAGE_SCALE - COLLAPSED_STAGE_SCALE) * (current / HALF_FRACTION)
            } else {
                HALF_STAGE_SCALE +
                    (FULL_STAGE_SCALE - HALF_STAGE_SCALE) *
                    ((current - HALF_FRACTION) / (1f - HALF_FRACTION))
            }
        }

    /**
     * 主舞台上移量（dp）：收起 0，半展开 80dp，全展开 96dp。
     *
     * 该值是 [BodySheetState] 的一部分而不是页面常量，保证拖拽时舞台跟手；
     * 页面用 `graphicsLayer { translationY = -state.stageTranslationY.toPx() }` 应用即可。
     */
    val stageTranslationY: Dp
        get() {
            val current = fraction
            return if (current <= HALF_FRACTION) {
                HALF_STAGE_LIFT * (current / HALF_FRACTION)
            } else {
                HALF_STAGE_LIFT +
                    (FULL_STAGE_LIFT - HALF_STAGE_LIFT) * ((current - HALF_FRACTION) / (1f - HALF_FRACTION))
            }
        }

    /**
     * 指定锚点下的容器位移（dp）。纯函数、不读动画状态，可在 `derivedStateOf` 中安全调用。
     *
     * 用途：抽屉内容区在容器被下移时，底部需要补上同等留白，
     * 否则「固定 92% 高容器 + 位移」会让半展开时最后一段内容永远滚不到（位移量 = 视口被藏起的高度）。
     */
    fun topOffsetAt(anchor: SheetAnchor): Dp =
        with(density) { translationForFraction(anchor.fraction).toDp() }

    // ── 手势与动画 ───────────────────────────────────────────────────────

    /** 拖拽增量（px，正数向下 = 收起方向）；只应由抽屉头部/拖拽条调用。 */
    fun onDrag(deltaPx: Float) {
        if (!dragging) {
            dragging = true
            dragTargetPx = offset.value
        }
        val limitPx = OVERSCROLL_LIMIT_DP * density.density
        dragTargetPx = (dragTargetPx + deltaPx).coerceIn(-limitPx * 4f, collapsedOffsetPx + limitPx * 4f)
        val next = rubberBandOffset(dragTargetPx, 0f, collapsedOffsetPx, limitPx)
        // snapTo 会取消正在进行的弹簧动画，保证手指永远优先。
        // UNDISPATCHED 让拖拽增量在当前帧立即写入，避免每个 move 事件排队一帧造成“拖泥带水”。
        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(next) }
    }

    /** 动画到指定锚点（弹簧 0.82 / 280）。 */
    fun animateTo(anchor: SheetAnchor) {
        dragging = false
        val target = translationForFraction(anchor.fraction)
        dragTargetPx = target
        scope.launch {
            offset.animateTo(targetValue = target, animationSpec = SHEET_SPRING)
        }
    }

    /**
     * 释放后根据位置和速度预测落点，弹簧动画负责吸附及越界回弹。
     *
     * @param velocityPxPerSec 竖直释放速度（px/s，正数向下 = 收起方向）。
     */
    suspend fun settle(velocityPxPerSec: Float) {
        dragging = false
        val target = resolveSettleAnchor(velocityPxPerSec)
        val targetPx = translationForFraction(target.fraction)
        dragTargetPx = targetPx
        offset.animateTo(
            targetValue = targetPx,
            animationSpec = SHEET_SPRING,
            initialVelocity = if ((offset.value < 0f && velocityPxPerSec < 0f) ||
                (offset.value > collapsedOffsetPx && velocityPxPerSec > 0f)) 0f else
                velocityPxPerSec.coerceIn(-MAX_SPRING_VELOCITY_DP_PER_SEC * density.density, MAX_SPRING_VELOCITY_DP_PER_SEC * density.density),
        )
    }

    // ── 内部换算 ─────────────────────────────────────────────────────────

    /**
     * fraction → 向下位移（px）的分段线性映射：
     * 0 → 0.80S，0.5 → 0.30S，1 → 0（S 为屏高）。
     */
    private fun translationForFraction(fraction: Float): Float {
        val clamped = fraction.coerceIn(0f, 1f)
        return if (clamped <= HALF_FRACTION) {
            collapsedOffsetPx +
                (halfOffsetPx - collapsedOffsetPx) * (clamped / HALF_FRACTION)
        } else {
            halfOffsetPx * (1f - (clamped - HALF_FRACTION) / (1f - HALF_FRACTION))
        }
    }

    /** 向下位移（px）→ fraction 的反向映射。 */
    private fun fractionForTranslation(translationPx: Float): Float {
        if (collapsedOffsetPx <= 0f) return 1f
        val raw = if (translationPx >= halfOffsetPx) {
            // 收起段：collapsedOffsetPx → 0，halfOffsetPx → 0.5
            (collapsedOffsetPx - translationPx) / (collapsedOffsetPx - halfOffsetPx) * HALF_FRACTION
        } else {
            // 展开段：halfOffsetPx → 0.5，0 → 1
            HALF_FRACTION + (1f - translationPx / halfOffsetPx) * (1f - HALF_FRACTION)
        }
        return raw.coerceIn(0f, 1f)
    }

    /** 使用真实位移而非归一化进度计算吸附，正确处理三档间距不相等。 */
    private fun resolveSettleAnchor(velocityPxPerSec: Float): SheetAnchor {
        val anchors = listOf(0f, halfOffsetPx, collapsedOffsetPx)
        return listOf(SheetAnchor.FULL, SheetAnchor.HALF, SheetAnchor.COLLAPSED)[
            settleAnchorIndex(offset.value, velocityPxPerSec, anchors, RELEASE_VELOCITY_DP_PER_SEC * density.density)
        ]
    }

}

/**
 * 记住一个 [BodySheetState]。
 *
 * @param screenHeight 屏幕可用高度（页面用 `BoxWithConstraints` 的 `maxHeight` 或
 *        `LocalConfiguration.screenHeightDp` 提供均可；不含状态栏/导航栏时按可用高度算）。
 * @param initialAnchor 初始锚点。契约默认：有历史 → HALF；无历史 → COLLAPSED。
 */
@Composable
fun rememberBodySheetState(
    screenHeight: Dp,
    initialAnchor: SheetAnchor = SheetAnchor.HALF,
): BodySheetState {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    return remember(screenHeight, initialAnchor, density) {
        BodySheetState(
            screenHeight = screenHeight,
            density = density,
            scope = scope,
            initialAnchor = initialAnchor,
        )
    }
}
