package com.example.dianzicheng.ui.page

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.view.HapticFeedbackConstants
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.dianzicheng.data.ble.BleScaleClient
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.ui.body.BodyDimens
import com.example.dianzicheng.ui.body.BodyHistoryDrawer
import com.example.dianzicheng.ui.body.HistoryMode
import com.example.dianzicheng.ui.body.MetricKind
import com.example.dianzicheng.ui.body.SheetAnchor
import com.example.dianzicheng.ui.body.StagePhase
import com.example.dianzicheng.ui.body.StagePrimaryAction
import com.example.dianzicheng.ui.body.StatusCapsule
import com.example.dianzicheng.ui.body.WeightDial
import com.example.dianzicheng.ui.body.bodyPalette
import com.example.dianzicheng.ui.body.buildBodyReport
import com.example.dianzicheng.ui.body.buildHistorySnapshots
import com.example.dianzicheng.ui.body.formatWeekRange
import com.example.dianzicheng.ui.body.isInWeekWindow
import com.example.dianzicheng.ui.body.shiftWeekMillis
import com.example.dianzicheng.ui.body.startOfWeekMillis
import com.example.dianzicheng.ui.body.rememberBodySheetState
import com.example.dianzicheng.ui.dialog.SelectMemberDialog
import com.example.dianzicheng.ui.state.ScaleUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import java.util.concurrent.CancellationException
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 无任何记录时喂给 [BodyHistoryDrawer] 的占位测量：抽屉要求非空 [BodyReport]，
 * 空态由抽屉内部的「无历史」文案承担，这里只保证类型与数值安全（全部为 0）。
 */
private val EmptyMeasurement = BodyMeasurement(
    id = "",
    measuredAtEpochMs = 0L,
    weightKg = 0.0,
    impedanceOhm = 0.0,
    bmi = 0.0,
    bodyFatPct = 0.0,
    muscleKg = 0.0,
    waterPct = 0.0,
    proteinPct = 0.0,
    boneMassKg = 0.0,
    memberId = null,
    memberNameSnapshot = null,
)

/** 测量主页的成员弹窗两种语义：顶部成员入口（可自动匹配）与记录重绑（只接受具体成员）。 */
private enum class HomeMemberDialogMode { Select, Bind }

/**
 * 身体报告单页（契约第 5 节）：称重主舞台 + 三态历史抽屉。
 *
 * 页面自行处理安全区（BoxWithConstraints + safeDrawing），不依赖 Scaffold 的顶栏；
 * 深/浅色背景由 [bodyPalette] 跟随系统。
 *
 * 测量完成时序（以新 [ScaleUiState.currentMeasurement] 的 id 为准）：
 * - T+0    → LOCKED + Light Haptic（数字闪烁由 A 的 [WeightDial] 实现）
 * - T+300  → CALCULATING（流光由 [WeightDial] 实现）
 * - T+1300 → 抽屉自动到 HALF（主表盘 0.72 缩放、上移 80dp 由本页与 [WeightDial] 协同）
 * - T+1500 → 卡片 reveal（渐显/滚动由 A 的 BodyMetricGrid 实现）
 *
 * 启动后 1 秒仍未连接体脂秤时，全展开身体报告；自动扫描继续在后台运行。
 */
@Composable
fun MeasureHomePage(
    uiState: ScaleUiState,
    history: List<BodyMeasurement>,
    members: List<FamilyMember>,
    onStartScan: () -> Unit,
    onOpenPairing: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectMember: (FamilyMember?) -> Unit,
    onBindMember: (FamilyMember) -> Unit,
    onDismissAlert: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = bodyPalette()
    val context = LocalContext.current
    val view = LocalView.current
    val darkTheme = isSystemInDarkTheme()

    // App 的 XML 主题是 Light；页面自带深色 token 时必须手动同步系统栏图标明暗，
    // 否则深色背景上会出现深色状态栏/导航栏图标。
    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        // 底部留给抽屉（12% 收起态）；系统导航栏由抽屉内容自己补，避免双重留白。
        val safePadding = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            .asPaddingValues()
        val screenHeight = maxHeight
        // 契约 §5：有历史/有当前测量 → HALF；无历史 → COLLAPSED。首个组合时定锚，之后由时序驱动。
        val initialAnchor = remember {
            if (history.isNotEmpty() || uiState.currentMeasurement != null) {
                SheetAnchor.HALF
            } else {
                SheetAnchor.COLLAPSED
            }
        }
        val sheetState = rememberBodySheetState(
            screenHeight = screenHeight,
            initialAnchor = initialAnchor,
        )
        // 主操作只在「胶囊收起态」出现；半展开/全展开时抽屉覆盖下半屏，
        // 再渲染按钮只会被盖住，还会让舞台内容整体上挤。
        val stageCollapsed by remember(sheetState) {
            derivedStateOf { sheetState.currentAnchor == SheetAnchor.COLLAPSED }
        }
        // 半展开/全展开时给抽屉让位。58% 略小于抽屉 62%，让表盘下缘刚好压在抽屉顶边，
        // 同时保留 80dp 上移的“联动感”（实际取 30%，避免整块表盘被推出屏幕）。
        val stageBottomReserve by animateDpAsState(
            targetValue = if (stageCollapsed) screenHeight * 0.12f else screenHeight * 0.58f,
            animationSpec = tween(durationMillis = 300),
            label = "stage-bottom-reserve",
        )

        // 恢复出来的当前测量（进程重建/旋转后）不重放动画，只把抽屉打开到 HALF。
        val restoredMeasurementId = remember { uiState.currentMeasurement?.id }

        var selectedIndex by remember { mutableStateOf(0) }
        // 默认按“次”显示；但数据窗口只展示一个 ISO 周，用户可前后切换周。
        var mode by remember { mutableStateOf(HistoryMode.SINGLE) }
        var weekStartMs by remember {
            mutableStateOf(
                startOfWeekMillis(
                    uiState.currentMeasurement?.measuredAtEpochMs
                        ?: history.firstOrNull()?.measuredAtEpochMs
                        ?: System.currentTimeMillis()
                )
            )
        }
        var weekWindowInitialized by remember { mutableStateOf(false) }
        var selectedMetric by remember { mutableStateOf(MetricKind.WEIGHT) }
        var memberFilterId by remember { mutableStateOf(uiState.selectedMember?.id) }
        var reveal by remember { mutableStateOf(true) }
        var stagePhase by remember {
            mutableStateOf(
                if (uiState.currentMeasurement != null) StagePhase.COMPLETE else StagePhase.IDLE
            )
        }
        var handledMeasurementId by remember { mutableStateOf(uiState.currentMeasurement?.id) }
        var memberDialogMode by remember { mutableStateOf<HomeMemberDialogMode?>(null) }

        val offlineHistory = history.isNotEmpty() && !uiState.isDeviceRemembered

        // 预测性返回：抽屉展开时按手势进度预览逐级收起（FULL → HALF → COLLAPSED）。
        // 最外层无抽屉/弹层时交给系统预测性退出；弹层由 ScaleApp 的 PredictiveBackHandler 优先处理。
        val backDensity = LocalDensity.current
        // 组合期直接读 currentAnchor 会随抽屉动画逐帧重组整个页面体（性能审计 H5）：
        // 与 stageCollapsed 一样包一层 derivedStateOf，只在跨越锚点边界时才失效。
        val sheetBackEnabled by remember(sheetState) {
            derivedStateOf { sheetState.currentAnchor != SheetAnchor.COLLAPSED }
        }
        PredictiveBackHandler(enabled = sheetBackEnabled) { progress ->
            val originalAnchor = sheetState.currentAnchor
            val targetAnchor = when (originalAnchor) {
                SheetAnchor.FULL -> SheetAnchor.HALF
                SheetAnchor.HALF -> SheetAnchor.COLLAPSED
                SheetAnchor.COLLAPSED -> SheetAnchor.COLLAPSED
            }
            val totalDeltaPx = with(backDensity) {
                sheetState.topOffsetAt(targetAnchor).toPx() -
                    sheetState.topOffsetAt(originalAnchor).toPx()
            }
            var lastProgress = 0f
            try {
                progress.collect { backEvent: BackEventCompat ->
                    val nextProgress = backEvent.progress.coerceIn(0f, 1f)
                    val deltaPx = totalDeltaPx * (nextProgress - lastProgress)
                    lastProgress = nextProgress
                    if (deltaPx != 0f) sheetState.onDrag(deltaPx)
                }
                // 手势完成后无条件落到目标锚点：即使进度未到 1，系统已判定返回完成。
                sheetState.animateTo(targetAnchor)
            } catch (cancellation: CancellationException) {
                // 手势取消：回到手势开始前的锚点，避免抽屉停在半途。
                sheetState.animateTo(originalAnchor)
                throw cancellation
            }
        }

        val enableBluetooth: () -> Unit = {
            // 直接拉起系统蓝牙开启请求；无 Activity 环境时静默忽略。
            try {
                context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {
                // 忽略：状态胶囊仍可重复点击
            }
        }

        // ① 新测量时序：T+0 LOCKED/Haptic → +300 CALCULATING → +1300 HALF → +1500 reveal。
        // 必须早于下面的初始历史判定，确保「首次称重落库」不会被误当成离线历史自动展开。
        LaunchedEffect(uiState.currentMeasurement?.id) {
            val measurement = uiState.currentMeasurement ?: return@LaunchedEffect
            if (measurement.id == handledMeasurementId) return@LaunchedEffect
            handledMeasurementId = measurement.id
            selectedIndex = 0
            reveal = false
            stagePhase = StagePhase.LOCKED
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            delay(300L)
            stagePhase = StagePhase.CALCULATING
            delay(1000L)
            // 流光严格 1000ms：T+1300 收束为 COMPLETE 并弹抽屉，T+1500 再入场卡片。
            stagePhase = StagePhase.COMPLETE
            sheetState.animateTo(SheetAnchor.HALF)
            delay(200L)
            reveal = true
        }

        // ①-b 测量会话被作废/用户重新开始扫描时，协程会被取消；
        // 必须把停在 CALCULATING/LOCKED、reveal=false 的界面复位，避免永久流光/空白卡片。
        LaunchedEffect(uiState.currentMeasurement?.id) {
            if (uiState.currentMeasurement == null) {
                reveal = true
                stagePhase = if (offlineHistory) StagePhase.OFFLINE else StagePhase.IDLE
            }
        }

        // 只在启动时计时一次；读取最新连接状态，避免重组或扫描状态变化重置计时。
        val latestUiState by rememberUpdatedState(uiState)
        LaunchedEffect(Unit) {
            delay(1000L)
            val state = latestUiState
            if (state.connection != BleScaleClient.ConnectionState.CONNECTED &&
                state.connection != BleScaleClient.ConnectionState.MEASURING &&
                state.liveWeightKg <= 0.0 &&
                state.currentMeasurement?.id == restoredMeasurementId
            ) {
                sheetState.animateTo(SheetAnchor.FULL)
            }
        }

        // ④ 实时称重阶段跟随（IDLE/OFFLINE/COMPLETE → LIVE）；LOCKED 由 ① 的测量仪式统一触发，
        //    避免同一值重复赋值导致 WeightDial 的 LaunchedEffect(phase) 不重启、闪烁丢失。
        // 【M1 性能】key 用「是否有示数」而非体重值：触发条件只依赖 liveWeightKg > 0.0，
        // 布尔 key 语义等价，且不再随每包体重变化 cancel/relaunch 本协程。
        LaunchedEffect(uiState.liveWeightKg > 0.0, uiState.isStable) {
            when {
                // 已经有本次测量结果时不再回退到 LIVE，否则 T+1300 完成后称重仍在波动会把
                // 卡片重新隐藏。LIVE 只服务于“新会话尚未锁定”的窗口。
                uiState.liveWeightKg > 0.0 &&
                    !uiState.isStable &&
                    uiState.currentMeasurement == null &&
                    (stagePhase == StagePhase.IDLE ||
                        stagePhase == StagePhase.OFFLINE ||
                        stagePhase == StagePhase.COMPLETE) -> {
                    stagePhase = StagePhase.LIVE
                    sheetState.animateTo(SheetAnchor.COLLAPSED)
                    // 新一轮称重开始先收起旧报告卡片；T+0 再按序列渐显，避免旧卡片闪一下。
                    reveal = false
                }

            }
        }

        // ⑤ 离线历史模式：无绑定设备但有历史 → OFFLINE；重新绑定后回到 IDLE。
        LaunchedEffect(offlineHistory, uiState.currentMeasurement?.id) {
            if (offlineHistory && uiState.currentMeasurement == null) {
                if (stagePhase == StagePhase.IDLE) stagePhase = StagePhase.OFFLINE
            } else if (!offlineHistory && stagePhase == StagePhase.OFFLINE) {
                stagePhase = StagePhase.IDLE
            }
        }

        // ⑥ 顶部成员入口 → 抽屉成员过滤联动（成员变化时只重置选中项，不覆盖用户后续手动筛选）。
        LaunchedEffect(uiState.selectedMember?.id) {
            memberFilterId = uiState.selectedMember?.id
            selectedIndex = 0
        }

        // ⑥-b 成员被删除后，筛选 id 不能继续悬空，否则历史恒空且 chip 无选中项。
        LaunchedEffect(members, memberFilterId) {
            if (memberFilterId != null && members.none { it.id == memberFilterId }) {
                memberFilterId = null
                selectedIndex = 0
            }
        }

        // ── 数据装配 ────────────────────────────────────────────────────────
        // 历史首次加载后，把默认窗口对齐到最近一条记录所在周，避免停留在“空周”。
        LaunchedEffect(history.size, uiState.currentMeasurement?.id) {
            if (!weekWindowInitialized && (history.isNotEmpty() || uiState.currentMeasurement != null)) {
                weekStartMs = startOfWeekMillis(
                    maxOf(
                        uiState.currentMeasurement?.measuredAtEpochMs ?: 0L,
                        history.maxOf { it.measuredAtEpochMs },
                    )
                )
                weekWindowInitialized = true
            }
        }

        val currentMeasurement = uiState.currentMeasurement
        val currentMatchesFilter = currentMeasurement != null &&
            (memberFilterId == null || currentMeasurement.memberId == memberFilterId)
        val weekEndMs = remember(weekStartMs) { shiftWeekMillis(weekStartMs, 1) }
        val dateLabel = remember(weekStartMs) { formatWeekRange(weekStartMs) }
        val snapshots = remember(history, currentMeasurement, memberFilterId, mode, weekStartMs) {
            // 刚锁定的记录可能尚未进入 Room Flow：先并入时间轴，避免表盘是新体重、
            // 报告却还是上一条记录；同一 id 已在 history 中时不重复添加。
            val source = if (currentMeasurement != null &&
                currentMatchesFilter &&
                history.none { it.id == currentMeasurement.id } &&
                isInWeekWindow(currentMeasurement.measuredAtEpochMs, weekStartMs, weekEndMs)
            ) {
                history + currentMeasurement
            } else {
                history
            }
            // 只显示一个 ISO 周内的数据，卡片按“次”展示；周均/月均是可切换的聚合粒度。
            buildHistorySnapshots(
                history = source,
                memberId = memberFilterId,
                mode = mode,
                windowStartMs = weekStartMs,
                windowEndMs = weekEndMs,
            )
        }
        val safeIndex = if (snapshots.isEmpty()) 0 else selectedIndex.coerceIn(0, snapshots.lastIndex)
        val snapshotMeasurement = snapshots.getOrNull(safeIndex)?.measurement
        // 单次模式下，刚落库但 Room Flow 尚未刷新时，当前测量比快照更新，直接展示当前值；
        // 周/月均模式始终展示聚合快照；用户手动选历史节点（index > 0）时也尊重快照。
        val showCurrent = mode == HistoryMode.SINGLE &&
            safeIndex == 0 &&
            currentMatchesFilter &&
            (snapshotMeasurement == null ||
                (currentMeasurement?.measuredAtEpochMs ?: 0L) > snapshotMeasurement.measuredAtEpochMs)
        val measurement = if (showCurrent) {
            currentMeasurement
        } else {
            snapshotMeasurement
                ?: currentMeasurement?.takeIf { currentMatchesFilter }
        }

        val boundMemberId = measurement?.memberId
        // 未绑定记录不能用当前选中成员的性别/年龄去套阈值，否则会把别人的标准套到这条记录上。
        val reportMember = boundMemberId?.let { id -> members.firstOrNull { it.id == id } }

        // 差值基准必须来自同一成员；全员筛选时不能拿另一个人的记录来相减。
        // 当前测量尚未进入 Room 快照时从 index 0 开始找，已对齐快照时从下一个桶开始找。
        val previous = measurement?.let { current ->
            val start = if (current.id == snapshotMeasurement?.id) safeIndex + 1 else safeIndex
            snapshots.drop(start)
                .firstOrNull { it.measurement.memberId == current.memberId }
                ?.measurement
        }
        // 「首次基准」只属于单次记录；周/月均没有上一桶时显示「暂无对比」更准确。
        val isFirstRecord = mode == HistoryMode.SINGLE && measurement != null && previous == null

        val report = remember(measurement, reportMember, previous, isFirstRecord) {
            buildBodyReport(
                measurement = measurement ?: EmptyMeasurement,
                member = reportMember,
                previous = previous,
                isFirstRecord = isFirstRecord,
            )
        }

        // 契约 §5 的 T+300 文案：CALCULATING 阶段必须显示「正在生成体成分报告」；
        // 其余阶段给一句轻量反馈，避免表盘下方留白。
        val phaseText = when (stagePhase) {
            StagePhase.LIVE -> "测量中，请保持站立"
            StagePhase.LOCKED -> "读数已锁定"
            StagePhase.CALCULATING -> "正在生成体成分报告"
            StagePhase.COMPLETE -> if (report.hasBia) "体成分报告已生成" else "体重记录已保存"
            StagePhase.IDLE, StagePhase.OFFLINE -> null
        }
        val phaseTextColor = when (stagePhase) {
            StagePhase.CALCULATING -> palette.primary
            StagePhase.COMPLETE -> palette.good
            else -> palette.textSecondary
        }

        // ── 顶部状态胶囊文案 / 主操作 ───────────────────────────────────────
        val status = when {
            !uiState.isBluetoothEnabled ->
                Triple("蓝牙已关闭 · 点击开启", palette.danger, enableBluetooth)

            uiState.connection == BleScaleClient.ConnectionState.SCANNING ->
                Triple("正在搜索已记住的体脂秤", palette.primary, {})

            uiState.connection == BleScaleClient.ConnectionState.CONNECTING ->
                Triple("正在连接体脂秤", palette.primary, {})

            uiState.connection == BleScaleClient.ConnectionState.MEASURING ->
                Triple("测量中 · 请保持站立", palette.primary, {})

            uiState.connection == BleScaleClient.ConnectionState.CONNECTED ->
                Triple("已连接 · 请赤脚上秤", palette.good, {})

            offlineHistory ->
                Triple("未连接设备 · 点击唤醒测量", palette.warn, onOpenPairing)

            !uiState.isDeviceRemembered ->
                Triple("未绑定体脂秤 · 点击配对", palette.warn, onOpenPairing)

            else ->
                Triple("待机 · 点击搜索体脂秤", palette.textSecondary, onStartScan)
        }
        val statusText = status.first
        val statusDotColor = status.second
        val statusAction = status.third
        // 呼吸圆点只在活跃态动画（性能审计 M6）：IDLE/完成/离线待机时不空转无限动画。
        val statusActive = uiState.connection != BleScaleClient.ConnectionState.IDLE ||
            stagePhase == StagePhase.LIVE ||
            stagePhase == StagePhase.LOCKED ||
            stagePhase == StagePhase.CALCULATING

        val primaryText: String
        val primaryAction: () -> Unit
        when {
            !uiState.isBluetoothEnabled -> {
                primaryText = "开启手机蓝牙"
                primaryAction = enableBluetooth
            }

            !uiState.isDeviceRemembered -> {
                primaryText = "搜索并绑定设备"
                primaryAction = onOpenPairing
            }

            uiState.connection == BleScaleClient.ConnectionState.SCANNING -> {
                primaryText = "正在寻找体脂秤…"
                primaryAction = onStartScan
            }

            uiState.connection == BleScaleClient.ConnectionState.CONNECTING -> {
                primaryText = "正在连接体脂秤…"
                primaryAction = {}
            }

            uiState.connection == BleScaleClient.ConnectionState.MEASURING -> {
                primaryText = "测量中 · 请保持站立"
                primaryAction = {}
            }

            uiState.connection == BleScaleClient.ConnectionState.CONNECTED -> {
                primaryText = "等待上秤"
                primaryAction = {}
            }

            else -> {
                primaryText = "搜索体脂秤"
                primaryAction = onStartScan
            }
        }

        // 边界提示（无历史空态 / 蓝牙关闭 / 未绑定 / 扫描中 / 连接中 / 测量中 / 离线查看）。
        val hintText = when {
            !uiState.isBluetoothEnabled -> "开启手机蓝牙后才能连接体脂秤并接收数据"
            stagePhase == StagePhase.OFFLINE -> "离线查看历史记录；点击状态胶囊可唤醒测量"
            !uiState.isDeviceRemembered -> "尚未绑定体脂秤：绑定后踩上即可自动连接"
            uiState.connection == BleScaleClient.ConnectionState.SCANNING -> "正在寻找已记住的体脂秤，请稍候"
            uiState.connection == BleScaleClient.ConnectionState.CONNECTING -> "正在连接体脂秤，请稍候"
            history.isEmpty() && uiState.currentMeasurement == null -> "还没有称重记录，连接体脂秤后即可测量"
            else -> null
        }

        // 主表盘始终显示“当前/最近一次”体重；时间轴回溯只刷新抽屉里的 8 张卡与图表。
        val stageMeasurement = uiState.currentMeasurement ?: history.firstOrNull()
        val displayWeight = if (uiState.liveWeightKg > 0.0) {
            uiState.liveWeightKg
        } else {
            stageMeasurement?.weightKg ?: 0.0
        }

        // ── 页面编排：顶部状态 + 主舞台（表盘/主操作），抽屉最后绘制覆盖舞台 ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(safePadding)
                .padding(horizontal = BodyDimens.PagePadding)
                // 预留给收起态抽屉（12%），主操作不被遮住。
                .padding(bottom = stageBottomReserve),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            HomeHeader(
                statusText = statusText,
                statusDotColor = statusDotColor,
                statusActive = statusActive,
                onStatusClick = statusAction,
                onOpenSettings = onOpenSettings,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // 背景微光（验收 §1 Z 轴：背景微光 → 主舞台 → 遮罩 → 抽屉）。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    palette.metricAccents.weight.copy(alpha = 0.10f),
                                    palette.metricAccents.muscle.copy(alpha = 0.05f),
                                    Color.Transparent,
                                ),
                            )
                        )
                )
                Column(
                    modifier = Modifier.graphicsLayer {
                        // 高频量都在图层阶段读取：拖拽时只失效绘制层，不重组整页/8 张卡片。
                        // 80dp 契约位移取 30%，与“给抽屉让位”的布局收缩叠加后视觉约等于 80dp。
                        scaleX = sheetState.stageScale
                        scaleY = sheetState.stageScale
                        transformOrigin = TransformOrigin.Center
                        translationY = -sheetState.stageTranslationY.toPx() * 0.30f
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    WeightDial(
                        weightKg = displayWeight,
                        phase = stagePhase,
                        stageScale = 1f,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (phaseText != null) {
                        Text(
                            text = phaseText,
                            style = MiuixTheme.textStyles.body2,
                            color = phaseTextColor,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(if (hintText != null) 6.dp else 16.dp))
                    }

                    if (hintText != null && stageCollapsed) {
                        Text(
                            text = hintText,
                            style = MiuixTheme.textStyles.footnote1,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    } else if (phaseText == null) {
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // 「寻找体脂秤」类主按钮按需求隐藏；未绑定/扫描中的入口由状态胶囊承担。
                    val showPrimaryAction = uiState.isDeviceRemembered &&
                        uiState.connection != BleScaleClient.ConnectionState.SCANNING &&
                        uiState.connection != BleScaleClient.ConnectionState.IDLE
                    if (stageCollapsed && showPrimaryAction) {
                        StagePrimaryAction(
                            text = primaryText,
                            onClick = primaryAction,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (stageCollapsed && uiState.currentMeasurement != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                text = "重绑成员",
                                onClick = { memberDialogMode = HomeMemberDialogMode.Bind },
                            )
                        }
                    }
                }
            }
        }

        // 全历史两次 any 扫描原先每次重组都执行（性能审计 M2）：按数据窗口记忆化。
        val canPreviousDate = remember(history, currentMeasurement, weekStartMs) {
            history.any { it.measuredAtEpochMs < weekStartMs } ||
                currentMeasurement?.let { it.measuredAtEpochMs < weekStartMs } == true
        }
        val canNextDate = remember(history, currentMeasurement, weekEndMs) {
            history.any { it.measuredAtEpochMs >= weekEndMs } ||
                currentMeasurement?.let { it.measuredAtEpochMs >= weekEndMs } == true
        }

        BodyHistoryDrawer(
            state = sheetState,
            report = report,
            snapshots = snapshots,
            selectedIndex = safeIndex,
            onSelectIndex = { selectedIndex = it.coerceAtLeast(0) },
            mode = mode,
            onModeChange = { nextMode ->
                mode = nextMode
                selectedIndex = 0
            },
            dateLabel = dateLabel,
            canPreviousDate = canPreviousDate,
            canNextDate = canNextDate,
            onPreviousDate = {
                weekStartMs = shiftWeekMillis(weekStartMs, -1)
                selectedIndex = 0
            },
            onNextDate = {
                weekStartMs = shiftWeekMillis(weekStartMs, 1)
                selectedIndex = 0
            },
            selectedMetric = selectedMetric,
            onMetricSelected = { kind ->
                selectedMetric = kind
                // 契约 §4：非全展开时点击卡片，由页面层自动展开到 FULL。
                if (sheetState.currentAnchor != SheetAnchor.FULL) {
                    sheetState.animateTo(SheetAnchor.FULL)
                }
            },
            members = members,
            memberFilterId = memberFilterId,
            onMemberFilter = { id ->
                memberFilterId = id
                selectedIndex = 0
            },
            // 无任何测量时保持卡片隐藏：抽屉内的「暂无历史记录」文案承担空态，
            // 避免占位 BodyReport 的 0.00 读数被当成真实数据。
            revealCards = reveal && measurement != null,
        )

        // ── 弹窗（Window 层，可在抽屉之上安全展示） ──────────────────────────
        memberDialogMode?.let { dialogMode ->
            SelectMemberDialog(
                members = uiState.availableMembers,
                currentMemberId = uiState.currentMeasurement?.memberId
                    ?: uiState.selectedMember?.id,
                allowAutoMatch = dialogMode == HomeMemberDialogMode.Select,
                onDismiss = { memberDialogMode = null },
                onSelect = { member ->
                    memberDialogMode = null
                    if (dialogMode == HomeMemberDialogMode.Bind) {
                        // 结果重绑流：只接受具体成员（null = 取消）
                        if (member != null) onBindMember(member)
                    } else {
                        // 顶部成员入口流：null = 全员智能自动匹配
                        onSelectMember(member)
                    }
                },
            )
        }

        if (uiState.showNewMemberAlert) {
            WindowDialog(
                show = true,
                title = "发现新成员？",
                summary = "测量体重与已有成员差距较大，您可以稍后在“我的”页面添加新成员。",
                onDismissRequest = onDismissAlert,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(text = "知道了", onClick = onDismissAlert)
                }
            }
        }
    }
}

/** 顶部一行：状态胶囊 + 「我的/设置」入口（无顶栏，自行处理 safeDrawing）。 */
@Composable
private fun HomeHeader(
    statusText: String,
    statusDotColor: Color,
    statusActive: Boolean,
    onStatusClick: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            StatusCapsule(
                text = statusText,
                dotColor = statusDotColor,
                active = statusActive,
                onClick = onStatusClick,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = MiuixIcons.Settings,
                contentDescription = "我的/设置",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
