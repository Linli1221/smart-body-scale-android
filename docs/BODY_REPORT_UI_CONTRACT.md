# 身体报告单页 UI 实现契约（v1）

> 目标：在现有 Android Compose + Miuix 0.9.4 项目中，按用户交付方案实现「称重主舞台 + 三态历史抽屉 + 8 项指标 Bento 网格」。
> 本文件是并行子任务的**唯一接口契约**。只允许在各自负责的文件内实现，禁止改动其他子任务文件；集成冲突由主智能体处理。

## 0. 工程约束

- 包名：`com.example.dianzicheng.ui.body`（新组件），页面放 `com.example.dianzicheng.ui.page`。
- Miuix 0.9.4；禁止 `androidx.compose.material3.*` / `androidx.compose.material.icons.*`。
- 不引入新依赖；不本地构建（本机无 JDK/Android SDK）。所有 API 必须能从现有源码或 Compose Foundation 稳定 API 推导。
- 所有面向用户文案使用简体中文。
- 数字统一启用 `fontFeatureSettings = "tnum"`（等宽数字）。
- 现有业务层（BLE / Room / ViewModel）不改；只做 UI 层与纯展示逻辑。
- 现有 `BodyMeasurement` 没有「内脏脂肪」字段。新 UI 用**可解释的估算值**（由体脂率、BMI、年龄、性别推导），并在代码注释中标注“估算”，不得修改数据库结构。
- `BodyMeasurement` 没有年龄字段；通过 `FamilyMember.birthDateEpochMs` 推导；缺省按男性 30 岁处理。
- 卡片数值为 `--` 表示本次无 BIA；体重与 BMI 始终可用。

## 1. 设计 token：`ui/body/BodyDesign.kt`（A 负责）

```kotlin
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

@Composable fun bodyPalette(): BodyPalette

object BodyDimens {
    val PagePadding: Dp          // 16.dp
    val CardGap: Dp              // 10.dp
    val CardRadius: Dp           // 16.dp
    val SheetRadius: Dp          // 28.dp
    val SummaryCardHeight: Dp    // 76.dp
    val ShapeCardHeight: Dp      // 96.dp
    val MetabolicCardHeight: Dp  // 84.dp
    val CompactCardHeight: Dp    // 68.dp
    val StatusCapsuleHeight: Dp  // 28.dp
    val DialSize: Dp             // 240.dp
    val DialStroke: Dp           // 4.dp
}

@Composable fun bodyNumberStyle(size: TextUnit, weight: FontWeight = FontWeight.SemiBold): TextStyle
```

- 浅色 token 严格使用交付方案：背景 `#F1F5F9`、抽屉 `#FFFFFF`（约 94% 不透明）、卡片 `#F8FAFC`、一级文字 `#0F172A`、二级 `#64748B`、达标 `#10B981`、临界 `#F59E0B`、高危 `#EF4444`。
- 深色 token：背景 `#0B0F19`、抽屉 `#1E293B`、卡片 `#0F172A`、一级文字 `#F8FAFC`、二级 `#94A3B8`、达标 `#34D399`、临界 `#FBBF24`、高危 `#F87171`。
- 主题主色用 Miuix 语义主色，但状态色必须使用上述固定 token，保证设计还原。

## 2. 指标模型：`ui/body/BodyMetricUi.kt`（A 负责）

```kotlin
enum class MetricKind { WEIGHT, BMI, BODY_FAT, MUSCLE, WATER, VISCERAL_FAT, PROTEIN, BONE }

enum class MetricTone { GOOD, WARN, DANGER, NEUTRAL }

data class MetricStatus(val label: String, val tone: MetricTone)

data class RangeBarSpec(
    val min: Float,
    val standardStart: Float,
    val standardEnd: Float,
    val max: Float,
    val value: Float,
)

data class MetricReading(
    val kind: MetricKind,
    val title: String,
    val valueText: String,
    val unit: String,
    val numericValue: Double?,
    val status: MetricStatus?,
    val range: RangeBarSpec?,
)

data class BodyReport(
    val measurement: BodyMeasurement,
    val member: FamilyMember?,
    val readings: Map<MetricKind, MetricReading>,
    val hasBia: Boolean,
    val weightDeltaKg: Double?,
    val isFirstRecord: Boolean,
    val visceralFatLevel: Int?,
)

enum class HistoryMode { SINGLE, WEEK, MONTH }

data class HistorySnapshot(
    val measurement: BodyMeasurement,
    val label: String,
    val sublabel: String,
    val bucketStartMs: Long,
    val sampleCount: Int,
)

fun ageYears(birthDateEpochMs: Long, nowMs: Long = System.currentTimeMillis()): Int
fun estimateVisceralFatLevel(m: BodyMeasurement, member: FamilyMember?): Int
fun buildBodyReport(
    measurement: BodyMeasurement,
    member: FamilyMember?,
    previous: BodyMeasurement?,
    isFirstRecord: Boolean,
): BodyReport
fun metricNumericValue(m: BodyMeasurement, kind: MetricKind, member: FamilyMember? = null): Double?
fun buildHistorySnapshots(
    history: List<BodyMeasurement>,
    memberId: String?,
    mode: HistoryMode,
    nowMs: Long = System.currentTimeMillis(),
): List<HistorySnapshot>
```

阈值（严格按交付方案；未给出的按生理常识补全，并在注释说明）：

- BMI：`<18.5` 偏瘦（WARN）；`18.5..23.9` 标准（GOOD）；`24.0..27.9` 偏胖（WARN）；`>=28.0` 肥胖（DANGER）。
- 体脂：男 `10..20` 标准、女 `18..28` 标准；低于下限偏低（WARN），高于上限 5 个百分点内偏高（WARN），再高肥胖（DANGER）。
- 水分：男 `55..65`、女 `45..60` 达标；低于下限偏低（WARN），高于上限偏高（WARN）。
- 内脏脂肪：`1..9` 达标；`10..14` 偏高（WARN）；`>=15` 超标（DANGER）。估算公式必须收敛到 `1..20`。
- 蛋白质：`16.0..20.0` 达标；否则偏低/偏高（WARN）。
- 骨量：按体重区间给目标值（男 <60kg→2.5、60..75→2.9、>75→3.2；女 <45kg→1.8、45..60→2.2、>60→2.5），目标 ±0.3 内正常（GOOD），否则偏低/偏高（WARN）。
- 肌肉量：按体重占比（男 40%..60%、女 35%..55%）为达标；低于/高于为 WARN。
- 体重：`MetricStatus(null?)` 可为空；差值用 `weightDeltaKg`。
- 范围条：体脂 `min=5, standardStart/End=性别标准, max=40`；肌肉 `min=0, standard=体重占比区间, max=体重×0.65`；水分 `min=30, standard=性别标准, max=75`；内脏 `min=1, standard=1..9, max=20`；蛋白质 `min=10, standard=16..20, max=24`；骨量 `min=1, standard=目标±0.3, max=5`。

历史快照：

- `SINGLE`：按 `measuredAtEpochMs` 倒序，label `MM/dd`，sublabel `HH:mm`，`sampleCount=1`。
- `WEEK`：按 ISO 周分组，取组内各指标算术平均，`bucketStartMs=周一 00:00`，label 周起始 `MM/dd`，sublabel `周均`，`sampleCount=组内条数`；id 用 `week-<start>`。
- `MONTH`：按年月分组，取平均，label `M月`，sublabel `月均`；id 用 `month-<yyyy-MM>`。
- 聚合后的 `BodyMeasurement` 保留 `memberId`、`memberNameSnapshot`；`impedanceOhm` 取平均；体脂等为 0 的缺失值不参与平均；若全缺失则为 0。
- 返回顺序：**最新在前**（与现有 history 一致），供时间轴左侧最近、右侧更早使用。

## 3. 卡片与表盘：`ui/body/MetricCards.kt`、`ui/body/WeightStage.kt`（A 负责）

```kotlin
@Composable fun BodyMetricGrid(
    report: BodyReport,
    selectedMetric: MetricKind?,
    reveal: Boolean,
    onMetricClick: (MetricKind) -> Unit,
    modifier: Modifier = Modifier,
)

@Composable fun StatusCapsule(
    text: String,
    dotColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)

enum class StagePhase { IDLE, LIVE, LOCKED, CALCULATING, COMPLETE, OFFLINE }

@Composable fun WeightDial(
    weightKg: Double,
    phase: StagePhase,
    stageScale: Float,
    modifier: Modifier = Modifier,
)

@Composable fun StagePrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- `BodyMetricGrid` 内部按「体重+BMI / 体脂+肌肉 / 水分+内脏脂肪 / 蛋白质+骨量」四行排列；间距 10dp；宽度均分；高度严格 76/96/84/68dp；圆角 16dp。
- 顶层两卡并排；下方三行各两张。无 BIA 时在体脂/肌肉行上方插入 36dp 提示条「未检测到赤足接触，本次仅记录体重与 BMI」，6 张体成分卡显示 `--`。
- 卡片点击回调；选中卡 1.5dp 主题色描边。
- `reveal=false` 时卡片透明且下移 12dp；`true` 时按行序 50ms 间隔渐显上移，数字 400ms 从 0 滚动到实测值。
- 首次记录：体重卡不显示差值胶囊，显示「首次基准记录」。
- `StagePrimaryAction` 为 HyperOS 胶囊主按钮。
- `WeightDial`：240dp 表盘、4dp 外环、中心 64sp 数字 + 18sp `kg`；`LOCKED` 时数字做两次 150ms 透明度闪烁；`CALCULATING` 时顺时针流光扫描 1000ms；根据 `stageScale` 缩放但保持中心。
- `StatusCapsule`：28dp 高、14dp 圆角、6dp 呼吸圆点、12dp 水平内边距。

## 4. 抽屉状态与历史抽屉：`ui/body/BodySheetState.kt`、`ui/body/BodyTrendChart.kt`、`ui/body/BodyHistoryDrawer.kt`（B 负责）

```kotlin
enum class SheetAnchor(val fraction: Float) { COLLAPSED(0f), HALF(0.5f), FULL(1f) }

@Stable
class BodySheetState internal constructor(...) {
    val fraction: Float
    val currentAnchor: SheetAnchor
    val sheetHeight: Dp
    val topOffset: Dp
    val scrimAlpha: Float
    val stageScale: Float
    fun animateTo(anchor: SheetAnchor)
    fun onDrag(deltaPx: Float)
    suspend fun settle(velocityPxPerSec: Float)
}

@Composable fun rememberBodySheetState(screenHeight: Dp): BodySheetState

@Composable fun BodyHistoryDrawer(
    state: BodySheetState,
    report: BodyReport,
    snapshots: List<HistorySnapshot>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    mode: HistoryMode,
    onModeChange: (HistoryMode) -> Unit,
    selectedMetric: MetricKind,
    onMetricSelected: (MetricKind) -> Unit,
    members: List<FamilyMember>,
    memberFilterId: String?,
    onMemberFilter: (String?) -> Unit,
    revealCards: Boolean,
    onOpenAllHistory: () -> Unit,
    modifier: Modifier = Modifier,
)

@Composable fun BodyTrendChart(
    metric: MetricKind,
    snapshots: List<HistorySnapshot>,
    member: FamilyMember? = null, // 仅内脏脂肪估算需要性别/年龄
    modifier: Modifier = Modifier,
)
```

- 抽屉高度占比：收起 12%、半展开 62%、全展开 92%；顶距 715/308/65（375×812 基准）。实现用**固定 92% 高容器 + 位移**，避免拖拽时内容重排。
- 弹簧：`dampingRatio=0.82f, stiffness=280f`；释放阈值 400 dp/s；否则吸附最近锚点。
- 遮罩仅全展开渐显，最大 45%。
- 主舞台缩放：收起 1.00、半展开 0.72、全展开 0.50；半展开布局收缩 + 图层上移，视觉约 80dp（全展开继续略上移）。
- 抽屉满宽、顶部 28dp 圆角、36×4dp 拖拽条（距顶 8dp）、头部/内容左右 16dp 边距；拖拽手势仅绑定头部，避免和内容滚动冲突。
- 抽屉头部：标题/日期/成员 +「单次/周均/月均」分段控制器 + 44dp 横向时间轴；多成员时显示筛选 chip 行。
- 趋势图只在全展开显示，180dp 高；默认体重；点击卡片切换指标；单点时画发光锚点并显示「再测一次即可连成趋势曲线」。
- 抽屉内部可垂直滚动；卡片网格使用 A 的 `BodyMetricGrid`。
- 全展开时点击卡片：先选中，页面层负责自动展开到 FULL。

## 5. 页面编排：`ui/page/MeasureHomePage.kt` + `ui/ScaleApp.kt`（C 负责）

```kotlin
@Composable fun MeasureHomePage(
    uiState: ScaleUiState,
    history: List<BodyMeasurement>,
    members: List<FamilyMember>,
    onStartScan: () -> Unit,
    onOpenPairing: () -> Unit,
    onSelectMember: (FamilyMember?) -> Unit,
    onBindMember: (FamilyMember) -> Unit,
    onDismissAlert: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
)
```

时序：

1. 新 `currentMeasurement.id`：阶段 LOCKED，触发 Light Haptic，数字闪两次 150ms。
2. +300ms：CALCULATING，文字「正在生成体成分报告」，流光 1000ms。
3. +1300ms：抽屉自动到 HALF，主舞台缩放 0.72、上移 80dp。
4. +1500ms：卡片 `reveal=true`，50ms/行渐显，数字 400ms 滚动。

边界：

- 无 BIA：仍自动半展开；体重/BMI 正常；6 张体成分卡 `--`；提示条。
- 首次无历史：体重卡显示「首次基准记录」；全展开图表单锚点 + 文案。
- 离线查看：无设备但有历史时，初始 HALF，选中最近记录，状态胶囊「未连接设备 · 点击唤醒测量」。
- 抽屉初始：有历史→HALF；无历史→COLLAPSED；有当前测量→HALF/COMPLETE。
- 保留设置、配对、成员选择、全部历史、删除等旧入口：设置/全部历史通过回调打开既有 Overlay；成员选择复用 `SelectMemberDialog`。
- `ScaleApp` 去掉顶栏，`Scaffold(contentWindowInsets = WindowInsets(0,0,0,0))`；页面自行处理 safeDrawing。测量完成不再自动弹旧 HistorySheet，改由新抽屉承担。

## 6. 验收与禁止项

- 不得改 `BodyMeasurement`、Room、BLE、Repository、ViewModel 的公开行为。
- 不得删除现有 `MeasurePage.kt` 中的 `SelectMemberDialog`（新页面复用）。
- 不得使用 `MaterialTheme`、`material3`、`Icons.*`。
- 不得写死屏幕像素；用 dp/sp 与 BoxWithConstraints。
- 不得在子任务间修改对方文件；接口不匹配时在返回结果中说明，由主智能体集成。
