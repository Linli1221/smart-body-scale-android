# Miuix 视觉风格规范（本项目专用·第二轮：风格迁移）

> 目标：上一轮已把**组件**换成 Miuix 0.9.4；本轮把**视觉风格**落到 Miuix/HyperOS 设计语言——
> 去掉 M3 时代的字号覆写、字面色、魔法圆角/间距，改用 Miuix 字阶、组件 Defaults、SmallTitle+Card 分组与 HyperOS 版式。
> **禁止本地构建**；正确性靠固定快照（commit `39c40f99`）核对 + CI 云端编译。**视觉最终确认待用户真机验收**（无渲染证据）。

## 0. 决策顺序（每处视觉选择依次问）

1. 项目有意的产品约定？→ 保留（如 Dashboard 的体重大数字读数是产品核心表达，可保留视觉重量，但用字阶表达）
2. Miuix 公开组件默认形态能表达？→ **删掉自定参数，用默认**（绝大多数情况）
3. 需要微调？→ 用组件 `*Defaults` 工厂 / `MiuixTheme` 语义 token
4. 用布局原语（Row/Column/padding）组织
5. 自定义色/形状/绘制仅在有具体产品理由时保留，并在注释写明理由

## 1. 字阶（本轮重点）

**删除** `textStyles.X.copy(fontSize=…, lineHeight=…)` 与裸 `fontSize = N.sp` + `fontWeight = FontWeight.X` 组合，
改用语义字阶（`MiuixTheme.textStyles.*`）本身的角色：

| 内容角色 | 用法 |
|---|---|
| 页面/显著标题 | `title1`–`title4`（按视觉层级就近） |
| 分组标签 | `SmallTitle`（自带 `subtitle` 样式），**不**手写 Text |
| 卡片/行标题 | 组件自带（Preference 标题用组件默认），自定义行用 `body1`/`body2` |
| 正文/说明 | `body1`/`body2`；紧凑辅助文字 `footnote1`/`footnote2` |
| 按钮文字 | Button 默认（不要传 textStyle，除非语义不符） |
| 产品性大数字（体重读数等） | `title1` 起步；确需更大字号时保留 `.copy(fontSize=…)` 并注释"产品核心读数" |

**删除规则**：`fontWeight = FontWeight.Bold/Medium` 仅当所选字阶本身已表达该层级时删除；
若确需强调（如选中态文字加粗），保留并注释。原 M3 映射（titleLarge→title1 等）见 MIUIX_MIGRATION.md §2，
本轮按**语义角色**重新归位，而非机械沿用旧映射。

## 2. 颜色

- 优先组件默认色与语义 token（MIUIX_MIGRATION.md §2 的 token 表）。
- `Color(0x…)` 字面色：健康绿等**应用自有语义色**收敛为文件顶部命名常量（如 `private val HealthGreen = Color(0xFF4CAF50)`）+ 注释"应用自有语义色"；
  能映射语义 token 的一律映射（警示→error 系、强调→primary 系、辅助→onSurfaceSecondary/onSurfaceVariantSummary）。
- `.copy(alpha = …)`：仅用于真实透明度需求（渐变底、淡底色）；**禁用态/层次/阴影造假**必须换对应 token
  （disabledOnSurface、surfaceContainerHigh、onSurfaceContainerVariant 等）。

## 3. 形状与尺寸

- **Card / Button / TextButton / TextField / Switch / RadioButton / ProgressIndicator / Dialog：删掉自定
  `shape`/`cornerRadius`/`RoundedCornerShape`/尺寸参数**，用组件默认（Card 默认 16dp squircle、Button 默认胶囊）。
- `Surface` 仅用于自定义容器（chip、徽标、高亮卡），保留 `RoundedCornerShape` 时半径向组件默认靠拢（16dp 卡 / 12dp 小件 / 8dp 徽标）。
- 图标 `Modifier.size`：行内装饰图标 16–18dp、导航/工具图标按槽位默认，去掉为"凑布局"的魔法尺寸。

## 4. 分组与版式（HyperOS 设置/内容页模式）

- 设置/内容分组一律 `SmallTitle`（Card **外**、其默认自带标签内边距）+ `Card`（相关行放**同一张卡**）。
- Card 是纯 Column（InsideMargin=0）：卡内行组件自带 padding，**删掉习惯性手动行间距/Divider**；
  仅当分组仍歧义时保留 Divider（分组内首个/行间不需要）。
- 分组间距：卡外水平 12dp、组间 12dp 为参考值（Example 组合证据，非硬性）；保留项目既有合理密度。
- 每页一个结构壳（现有 Scaffold），不新增嵌套 Scaffold/Card。

## 5. 按钮与操作

- **删除** 上轮的近似降级：TextButton+透明底+`Modifier.border` 描边按钮 → 直接 `Button`（默认中性）或 `TextButton`；
  OutlinedButton 需求一律按 HyperOS 语义重选：主操作 `ButtonDefaults.buttonColorsPrimary()`、
  次操作默认 `Button()` 或 `TextButton(text=…)`、破坏性 `textButtonColors(textColor = error)`。
- 弹窗操作行：右对齐 Row，主操作主色；参照 Example `component/DialogSection.kt` 的按钮行布局。

## 6. 弹窗

- title/summary 槽承担标题/说明（HyperOS 弹窗字阶由组件内建）；内容区不再重复标题样式。
- 保留全部关闭/取消路径与草稿逻辑不动。

## 7. 证据（固定快照 URL 前缀）

`https://raw.githubusercontent.com/compose-miuix-ui/miuix/39c40f99844227b853f0049a0933b1f3ae6c00ba/`

| 证据 | 路径 |
|---|---|
| 设置页分组模式 | `example/shared/src/commonMain/kotlin/SettingsPage.kt` |
| 首页/内容页版式 | `example/shared/src/commonMain/kotlin/MainPage.kt` |
| 弹窗按钮行 | `example/shared/src/commonMain/kotlin/component/DialogSection.kt` |
| SmallTitle 用法 | `docs/demo/src/commonMain/kotlin/SmallTitleDemo.kt` |
| 设计语言/Defaults | 本地 skill：`C:\Users\1\.agents\skills\miuix\references\design-language.md` |

**禁止**照抄 Example 全文；只提取分组/版式/按钮行模式。API 疑点回查 demo/源码。

## 8. 交付自查

- [ ] 无 `textStyles.X.copy(fontSize` 残留（例外仅限注明的产品读数）
- [ ] 无裸 `fontSize = N.sp` 与 `fontWeight` 组合堆砌（每个保留项有注释理由）
- [ ] 字面色收敛为命名常量或映射到 token
- [ ] Card/Button/TextField 等无自定 shape/cornerRadius
- [ ] 设置/内容分组符合 SmallTitle + Card 模式；无习惯性 Divider/行距堆砌
- [ ] 按钮语义化（主/次/破坏性），无 border 近似
- [ ] **行为/状态/回调 100% 不变**（公开签名不变）
- [ ] 报告注明：视觉确认待真机验收
