package com.example.dianzicheng.ui.body

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.dianzicheng.domain.FamilyMember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 时间轴行高（契约：44dp 横向时间轴）。 */
private val TimelineHeight = 44.dp

/** 拖拽条尺寸（契约：36×4dp，距顶 8dp）。 */
private val DragHandleWidth = 36.dp
private val DragHandleHeight = 4.dp
private val DragHandleTopGap = 8.dp

/**
 * 三态历史抽屉容器（契约第 4 节 B 组）。
 *
 * 结构：全屏遮罩（drawBehind，仅全展开渐显 ≤45%）+ 底部 92% 高容器（按 [BodySheetState.topOffset] 位移）。
 * - 顶部 28dp 圆角、左右 16dp 边距、36×4dp 拖拽条。
 * - 拖拽手势**只**绑定在头部（标题 / 日期 / 时间轴 / 筛选 chip），
 *   抽屉内部内容用 `verticalScroll` 独立滚动，两者不冲突。
 * - 头部：标题 + 日期/成员 + 44dp 时间轴 + 多成员筛选。
 * - 内容：全展开时的 180dp 趋势图 + A 组 `BodyMetricGrid` 卡片网格（不在此重写卡片）。
 *
 * 高频动画量（fraction / scrimAlpha / topOffset）只在绘制与布局阶段读取，
 * 组合阶段仅通过 `derivedStateOf` 读取「是否全展开」「遮罩是否可点」两个布尔，避免拖拽逐帧重组。
 *
 * @param state 抽屉状态机（[rememberBodySheetState]）。
 * @param report 当前选中测量的身体报告（A 组构建）。
 * @param snapshots 当前周/成员下的历史快照，最新在前。
 * @param selectedIndex 选中快照下标（对应 [snapshots]）。
 * @param selectedMetric 趋势图与卡片选中的指标（默认体重由页面决定）。
 * @param revealCards 卡片渐显开关（页面时序控制）。
 */
@Composable
fun BodyHistoryDrawer(
    state: BodySheetState,
    report: BodyReport,
    snapshots: List<HistorySnapshot>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    dateLabel: String,
    canPreviousDate: Boolean,
    canNextDate: Boolean,
    onPreviousDate: () -> Unit,
    onNextDate: () -> Unit,
    selectedMetric: MetricKind,
    onMetricSelected: (MetricKind) -> Unit,
    members: List<FamilyMember>,
    memberFilterId: String?,
    onMemberFilter: (String?) -> Unit,
    revealCards: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = bodyPalette()
    val scope = rememberCoroutineScope()
    val sheetShape = RoundedCornerShape(
        topStart = BodyDimens.SheetRadius,
        topEnd = BodyDimens.SheetRadius,
    )

    // 组合阶段只保留两个布尔派生值，避免拖拽时整棵子树逐帧重组。
    val fullyExpanded by remember(state) { derivedStateOf { state.fraction >= 0.99f } }
    val scrimInteractive by remember(state) {
        // 不用 fraction>0.5：弹簧过冲或停在 0.51 时遮罩几乎不可见却会吞掉主舞台点击。
        derivedStateOf { state.scrimAlpha > 0.08f }
    }
    // 内容区底部留白 = 当前锚点的容器位移。容器固定 92% 高再下移时，视口被藏起的高度正好等于位移；
    // 不补这段留白，半展开时内容末尾永远滚不到。只在跨锚点时变化，不会逐帧重排。
    val contentBottomInset by remember(state) {
        derivedStateOf {
            when (state.currentAnchor) {
                SheetAnchor.FULL -> 0.dp
                SheetAnchor.HALF -> state.topOffsetAt(SheetAnchor.HALF)
                SheetAnchor.COLLAPSED -> state.topOffsetAt(SheetAnchor.COLLAPSED)
            }
        }
    }
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val safeIndex = if (snapshots.isEmpty()) 0 else selectedIndex.coerceIn(0, snapshots.lastIndex)
    // 无历史且无当前测量时不再渲染 8 行空网格；有当前测量（首测尚未进 Room Flow）时仍展示报告。
    val hasGridContent = snapshots.isNotEmpty() || report.measurement.weightKg > 0.0
    val selectedSnapshot = snapshots.getOrNull(safeIndex)
    val memberName = memberNameOf(members, memberFilterId, selectedSnapshot, report)
    val headerSubtitle = when {
        selectedSnapshot != null ->
            selectedSnapshot.label + " " + selectedSnapshot.sublabel + " · " + memberName
        // 首测落库但 Room Flow 尚未刷新：报告已有体重，仍给用户一个明确的“本次测量”上下文。
        report.measurement.weightKg > 0.0 -> "本次测量 · " + memberName
        else -> "暂无历史记录 · " + memberName
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ① 遮罩：仅全展开方向渐显（最大 45%）。点遮罩收起抽屉。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (scrimInteractive) {
                        Modifier.pointerInput(state) {
                            detectTapGestures { state.animateTo(SheetAnchor.COLLAPSED) }
                        }
                    } else {
                        Modifier
                    }
                )
                .drawBehind {
                    val scrimAlpha = state.scrimAlpha
                    if (scrimAlpha > 0.001f) {
                        drawRect(color = palette.scrim.copy(alpha = scrimAlpha))
                    }
                }
        )

        // ② 抽屉外框：固定 92% 高容器 + 位移（拖拽时内容不重排）。
        // 容器满宽，左右 16pt 由头部/内容自己加，保证 8 卡网格单卡 166.5pt。
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(state.sheetHeight)
                    .offset { IntOffset(0, state.topOffset.roundToPx()) }
                    .clip(sheetShape)
                    .background(palette.sheet)
            ) {
                // ── 头部（唯一拖拽区）──────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawerDragHandle(state = state, scope = scope)
                ) {
                    Spacer(modifier = Modifier.height(DragHandleTopGap))
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(width = DragHandleWidth, height = DragHandleHeight)
                            .clip(CircleShape)
                            .background(palette.track)
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BodyDimens.PagePadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "身体报告",
                                style = MiuixTheme.textStyles.title3,
                                color = palette.textPrimary,
                                maxLines = 1,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = headerSubtitle,
                                style = MiuixTheme.textStyles.footnote2.copy(fontFeatureSettings = "tnum"),
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // 收起态只露出标题行。
                    Spacer(modifier = Modifier.height(20.dp))
                    HistoryDateNavigator(
                        dateLabel = dateLabel,
                        canPreviousDate = canPreviousDate,
                        canNextDate = canNextDate,
                        onPreviousDate = onPreviousDate,
                        onNextDate = onNextDate,
                        palette = palette,
                        modifier = Modifier.padding(horizontal = BodyDimens.PagePadding),
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    HistoryTimeline(
                        snapshots = snapshots,
                        selectedIndex = safeIndex,
                        onSelectIndex = onSelectIndex,
                        palette = palette,
                    )

                    if (members.size > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        MemberFilterRow(
                            members = members,
                            memberFilterId = memberFilterId,
                            onMemberFilter = onMemberFilter,
                            palette = palette,
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // ── 内容（可垂直滚动，与头部拖拽手势互不干扰）────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = BodyDimens.PagePadding)
                ) {
                    Spacer(modifier = Modifier.height(4.dp))
                    if (fullyExpanded) {
                        BodyTrendChart(
                            metric = selectedMetric,
                            snapshots = snapshots,
                            members = members,
                            selectedIndex = safeIndex,
                            onSelectIndex = onSelectIndex,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(BodyDimens.CardGap))
                    }
                    if (!hasGridContent) {
                        Text(
                            text = "暂无历史记录，完成下一次称重后即可查看趋势",
                            style = MiuixTheme.textStyles.footnote2,
                            color = palette.textSecondary,
                            maxLines = 2,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    } else {
                        BodyMetricGrid(
                            report = report,
                            selectedMetric = selectedMetric,
                            reveal = revealCards,
                            onMetricClick = onMetricSelected,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(BodyDimens.CardGap))
                        Text(
                            text = "点击卡片切换趋势指标",
                            style = MiuixTheme.textStyles.footnote2,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(modifier = Modifier.height(28.dp + contentBottomInset + navigationBottom))
                }
            }
        }
    }
}

// ── 手势 ────────────────────────────────────────────────────────────────────

/**
 * 头部拖拽手势：仅竖直方向，松手按速度吸附锚点。
 *
 * 只在头部使用，内容区的 `verticalScroll` 因此不会被抢走手势。
 */
private fun Modifier.drawerDragHandle(
    state: BodySheetState,
    scope: CoroutineScope,
): Modifier = pointerInput(state) {
    val velocityTracker = VelocityTracker()
    detectVerticalDragGestures(
        onDragStart = { velocityTracker.resetTracking() },
        onDragCancel = { scope.launch { state.settle(0f) } },
        onDragEnd = {
            val velocity = velocityTracker.calculateVelocity().y
            scope.launch { state.settle(velocity) }
        },
        onVerticalDrag = { change, dragAmount ->
            change.consume()
            velocityTracker.addPosition(change.uptimeMillis, change.position)
            state.onDrag(dragAmount)
        },
    )
}

// ── 日期切换：当前期 + 上一期 / 下一期 ───────────────────────────────────────
@Composable
private fun HistoryDateNavigator(
    dateLabel: String,
    canPreviousDate: Boolean,
    canNextDate: Boolean,
    onPreviousDate: () -> Unit,
    onNextDate: () -> Unit,
    palette: BodyPalette,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DateArrowButton(
            text = "‹",
            enabled = canPreviousDate,
            palette = palette,
            onClick = onPreviousDate,
        )
        Text(
            text = dateLabel,
            style = MiuixTheme.textStyles.body2.copy(fontFeatureSettings = "tnum"),
            color = palette.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        DateArrowButton(
            text = "›",
            enabled = canNextDate,
            palette = palette,
            onClick = onNextDate,
        )
    }
}

@Composable
private fun DateArrowButton(
    text: String,
    enabled: Boolean,
    palette: BodyPalette,
    onClick: () -> Unit,
) {
    Surface(
        onClick = { if (enabled) onClick() },
        shape = CircleShape,
        color = if (enabled) palette.cardSelected else Color.Transparent,
        modifier = Modifier.size(28.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MiuixTheme.textStyles.body1,
                color = if (enabled) palette.textPrimary else palette.textSecondary,
                maxLines = 1,
            )
        }
    }
}

// ── 44dp 横向时间轴 ─────────────────────────────────────────────────────────

@Composable
private fun HistoryTimeline(
    snapshots: List<HistorySnapshot>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    palette: BodyPalette,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TimelineHeight)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = BodyDimens.PagePadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        snapshots.forEachIndexed { index, snapshot ->
            val selected = index == selectedIndex
            Surface(
                onClick = { onSelectIndex(index) },
                shape = RoundedCornerShape(12.dp),
                color = if (selected) palette.cardSelected else Color.Transparent,
            ) {
                Column(
                    modifier = Modifier
                        .height(40.dp)
                        .padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = snapshot.label,
                        style = MiuixTheme.textStyles.body2.copy(fontFeatureSettings = "tnum"),
                        color = if (selected) palette.primary else palette.textPrimary,
                        maxLines = 1,
                    )
                    Text(
                        text = snapshot.sublabel,
                        style = MiuixTheme.textStyles.footnote2.copy(fontFeatureSettings = "tnum"),
                        color = palette.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ── 多成员筛选 chip 行 ───────────────────────────────────────────────────────

@Composable
private fun MemberFilterRow(
    members: List<FamilyMember>,
    memberFilterId: String?,
    onMemberFilter: (String?) -> Unit,
    palette: BodyPalette,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = BodyDimens.PagePadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberChip(
            label = "全部",
            selected = memberFilterId == null,
            palette = palette,
            onClick = { onMemberFilter(null) },
        )
        members.forEach { member ->
            MemberChip(
                label = member.name,
                selected = memberFilterId == member.id,
                palette = palette,
                onClick = { onMemberFilter(member.id) },
            )
        }
    }
}

@Composable
private fun MemberChip(
    label: String,
    selected: Boolean,
    palette: BodyPalette,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) palette.cardSelected else palette.card,
    ) {
        Box(
            modifier = Modifier
                .height(32.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote1,
                color = if (selected) palette.primary else palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── 小工具 ──────────────────────────────────────────────────────────────────

/** 头部成员名：筛选成员 → 快照成员名 → 报告成员，最终兜底「未绑定成员」。 */
private fun memberNameOf(
    members: List<FamilyMember>,
    memberFilterId: String?,
    snapshot: HistorySnapshot?,
    report: BodyReport,
): String {
    if (memberFilterId != null) {
        members.firstOrNull { it.id == memberFilterId }?.let { return it.name }
    }
    snapshot?.measurement?.memberNameSnapshot?.takeIf { it.isNotBlank() }?.let { return it }
    report.member?.name?.takeIf { it.isNotBlank() }?.let { return it }
    return "未绑定成员"
}
