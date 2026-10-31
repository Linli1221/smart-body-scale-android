# 身体报告单页 · 实现说明

## 1. 落地范围

在现有单页面 + 弹层架构上，把测量主页升级为「称重主舞台 + 三态历史抽屉」，不引入页面导航栈，不改 BLE / Room / Repository / ViewModel 的业务契约。

- 入口：[`ScaleApp.kt`](../app/src/main/java/com/example/dianzicheng/ui/ScaleApp.kt) 去掉顶栏，`Scaffold.contentWindowInsets = WindowInsets(0,0,0,0)`，安全区由页面自理。
- 页面：[`MeasureHomePage.kt`](../app/src/main/java/com/example/dianzicheng/ui/page/MeasureHomePage.kt)
- 组件：`ui/body/` 下 7 个文件（设计 token、指标纯逻辑、卡片、表盘、抽屉状态机、趋势图、抽屉容器）。
- 旧 `HistorySheet` 保留，由「全部历史」入口打开；删除、详情、指标顺序调整等能力不丢。

## 2. 状态机

`BodySheetState` 用「固定 92% 高容器 + 向下位移」实现三锚点：

| 锚点 | fraction | 可见高度 | 容器位移 | 主舞台缩放 |
| --- | --- | --- | --- | --- |
| COLLAPSED | 0 | 12% | 0.80 × 屏高 | 1.00 |
| HALF | 0.5 | 62% | 0.30 × 屏高 | 0.72 |
| FULL | 1 | 92% | 0 | 0.50 |

- 拖拽只绑定抽屉头部，内容区独立 `verticalScroll`；
- 释放速度 ≥ 400dp/s 翻到速度方向相邻锚点，否则吸附最近锚点；
- 弹簧 `dampingRatio = 0.82f, stiffness = 280f`；
- 高频量在 `offset { }` / `drawBehind { }` / `graphicsLayer { }` 中读取，避免拖拽逐帧重组 8 张卡片。

## 3. 测量完成时序

以新 `uiState.currentMeasurement.id` 为触发：

1. T+0：阶段 LOCKED，数字两次 150ms 脉冲，`performHapticFeedback(KEYBOARD_TAP)`；
2. T+300：阶段 CALCULATING，文案「正在生成体成分报告」，表盘 1000ms 顺时针流光；
3. T+1300：阶段 COMPLETE，抽屉自动到 HALF；
4. T+1500：卡片 `reveal = true`，按行 50ms 渐显，数字 400ms 滚动。

若测量被作废 / 用户重新开始扫描，`currentMeasurement == null` 的清理 effect 会把阶段复位为 IDLE/OFFLINE，避免永久流光或透明卡片。

## 4. 数据与指标

- `buildBodyReport` 把一条 `BodyMeasurement` 映射成 8 张卡片的 `MetricReading`；
- `buildHistorySnapshots` 支持 SINGLE / WEEK / MONTH，返回**最新在前**的时间轴快照；
- 时间轴选择、周/月切换、成员筛选都会重建快照并刷新卡片、差值、图表；
- 差值基准强制同成员；「首次基准」只用于 SINGLE；
- 无阻抗（`impedanceOhm <= 0` 或 `bodyFatPct <= 0`）时，体脂/肌肉/水分/内脏脂肪/蛋白质/骨量全部 `--`，体重与 BMI 正常；
- 内脏脂肪是**展示层估算**（BMI + 体脂率 + 年龄 + 性别，1..20 级），原数据模型没有该字段，不写库、不作为医学结论。

## 5. 视觉适配

- 固定色 token 使用交付方案浅/深两套值，状态色不跟随 Monet 漂移；主题主色沿用 Miuix `primary`；
- 抽屉满宽，头部/内容 16dp 边距，保证 375 宽下卡片 `(375-32-10)/2 = 166.5dp`；
- 抽屉内容底部补 `navigationBars` 内边距与「容器位移」留白，半展开时最后一行可滚到导航栏上方；
- 半展开/全展开时主舞台按当前锚点收缩底部预留（12% → 58%），并把 80dp 联动位移取 30% 与布局收缩叠加，避免表盘被抽屉盖住；
- 顶部状态胶囊 28dp 高、14dp 圆角、6dp 呼吸点；设置入口固定 28dp，保证胶囊距安全区约 12dp。

## 6. 异常/边界

- 无 BIA：抽屉仍自动半展开；6 张体成分卡 `--`；36dp 温和提示条；
- 首次无历史：体重卡「首次基准记录」；全展开单点发光锚点 + 「再测一次即可连成趋势曲线」；
- 离线无设备但有历史：初始半展开，状态胶囊「未连接设备 · 点击唤醒测量」，点击走配对；
- 成员被删除：筛选 id 自动回落到「全部」；
- 历史晚到 / 连接状态变化：初始 HALF effect 以 `history.size / connection / liveWeight` 为 key，条件满足后重试。

## 7. 验证状态

- 纯逻辑单测：[`BodyMetricUiTest.kt`](../app/src/test/java/com/example/dianzicheng/BodyMetricUiTest.kt)
- 静态检查：Kotlin 括号平衡、无 `material3` / `material.icons` / `MaterialTheme`、Miuix 0.9.4 已知 API 对照、HTML 内联 JS `node --check`；
- **未运行本地 Gradle**：当前环境无 JDK / Android SDK，按项目约定由 GitHub Actions 编译；
- 视觉最终确认需真机/浏览器打开 `prototype/body-report.html` 走查。
