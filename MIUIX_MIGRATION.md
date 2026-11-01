> **⚠️ 历史文档（Miuix 迁移已完成）**：本文写于迁移期间，保留原文仅作决策记录；文中旧名词与当前代码的对应关系：
> `电子秤Theme` → 现 `ui/theme/Theme.kt` 的 `AppTheme`；`MainScreen（含 bottomBar）` → 现 `ui/ScaleApp.kt` 单页面（无 bottomBar、无页面导航栈）；
> `PairingScreen` → 现 `ui/sheet/PairingSheet.kt` 底部弹层；页面跳转 → 单页面 + 底部弹层（ScaleApp 的 ActiveOverlay 路由）。

# Miuix 迁移规范（本项目专用）

> 目标：把 `app/src/main/java/com/example/dianzicheng/ui/` 下所有 Compose UI 从 Material 3 迁移到 **Miuix 0.9.4**（top.yukonga.miuix.kmp，HyperOS 设计）。
> **禁止本地构建**（本机无 JDK，用户也明确要求不构建）；正确性靠固定源码逐个核对 API。

## 0. 已完成的基础设施（勿重复改动）

- Gradle 已加入（`gradle/libs.versions.toml` 中 `miuix = "0.9.4"`）：
  - `top.yukonga.miuix.kmp:miuix-ui-android:0.9.4`
  - `top.yukonga.miuix.kmp:miuix-preference-android:0.9.4`
  - `top.yukonga.miuix.kmp:miuix-icons-android:0.9.4`
- 根主题已迁移：`ui/theme/Theme.kt` 的 `电子秤Theme` = `ThemeController(ColorSchemeMode.MonetSystem)` + `MiuixTheme`。
  `Color.kt` / `Type.kt` 已删除。
- 页面**不要**再创建 `MiuixTheme`；应用已有根 Scaffold（MainScreen，含 bottomBar 与 Overlay popup 层），
  二级页面仅在确有页面边界（自带 TopAppBar/返回键）时才加局部 `Scaffold`。
  注意：`isPairingComplete == false` 时 PairingScreen 渲染在根 Scaffold **之外**，其弹窗若用 Overlay 系需要页面自带 `Scaffold` 宿主（或用 Window 系弹窗）。

## 0.5 工具链要求（CI 失败日志实证）

- `compileSdk = 37`：miuix 0.9.4 的 AAR 在 `checkAarMetadata` 强制要求 compileSdk ≥ 37（targetSdk/minSdk 不必变）。
- Kotlin **2.4.20**：miuix 0.9.4 由 Kotlin 2.4.20 构建，metadata 2.4 需要 ≥2.4 的编译器读取。AGP 9 内置 Kotlin（built-in Kotlin）默认 KGP 2.2.10 且**禁止**独立 `org.jetbrains.kotlin.android` 插件；官方升级方式是在**根 build.gradle.kts 的 `buildscript` classpath** 声明 `kotlin-gradle-plugin:2.4.20`（见 kotl.in/gradle/agp-built-in-kotlin）。`org.jetbrains.kotlin.plugin.compose` 保持 plugins 块且与 KGP 同版本 2.4.20。
- KSP **2.3.13**：同样经根 `buildscript` classpath 注入（`symbol-processing-gradle-plugin`），app 内以无版本 `id("com.google.devtools.ksp")` 应用。
- Compose BOM **2026.09.00**（对齐 miuix 所需的 androidx.compose 1.12.x）。

## 1. 硬性规则

1. **禁止引用 `androidx.compose.material3.*` / `androidx.compose.material.icons.*`**（最后会移除依赖）。所有 UI 走 Miuix。
2. **禁止臆造 API**：每个 Miuix 组件在使用前必须核对固定快照（commit `39c40f99844227b853f0049a0933b1f3ae6c00ba`）的文档/demo/源码：
   - Markdown 文档 / demo：`https://raw.githubusercontent.com/compose-miuix-ui/miuix/39c40f99844227b853f0049a0933b1f3ae6c00ba/<路径>`
   - demo 路径：`docs/demo/src/commonMain/kotlin/<Component>Demo.kt`；源码包路径见下文 import 表。
   - 组件目录（哪个组件对应哪个场景）：读本地 skill 参考 `C:\Users\1\.agents\skills\miuix\references\component-selection.md`、`overlays-and-windows.md`、`preferences-and-menus.md`。
3. **保持行为与状态逻辑不变**：业务回调、状态提升、enabled/disabled、错误/空态、动画时长等一律保留；只换视觉组件。
4. Miuix 组件优先用其 `*Defaults` 与主题 token，不写死颜色；必须自定义时用 `MiuixTheme.colorScheme.*`。
5. Compose 基础布局（Row/Column/Box/LazyColumn/Spacer/Arrangement/Modifier）保持不变，不属于迁移对象。
6. `Text` 用 `top.yukonga.miuix.kmp.basic.Text`；文本样式用 `MiuixTheme.textStyles.*`（可 `.copy(fontSize=..)` 微调以保持原布局）。
7. 图标一律用 Miuix 图标（见 §4 映射表）；`Icon` 用 `top.yukonga.miuix.kmp.basic.Icon`。
8. 迁移完成后自查：文件内 grep 不到 `material3`、`material.icons`、`MaterialTheme`、`Icons.`。

## 2. 主题 token 映射

`MaterialTheme.colorScheme.X` → `MiuixTheme.colorScheme.Y`（`import top.yukonga.miuix.kmp.theme.MiuixTheme`）：

| Material 3 | Miuix (0.9.4 Colors) |
|---|---|
| primary | primary |
| onPrimary | onPrimary |
| primaryContainer | primaryContainer |
| onPrimaryContainer | onPrimaryContainer |
| secondary / secondaryContainer / onSecondaryContainer | secondary / secondaryContainer / onSecondaryContainer |
| tertiary | *（无同名）* 视语义用 primary 或 onBackgroundVariant；tertiaryContainer / onTertiaryContainer 保留 |
| background / onBackground | background / onBackground |
| surface / onSurface | surface / onSurface |
| surfaceVariant | surfaceVariant（或 surfaceContainer，卡片底色场景用 surfaceContainer） |
| onSurfaceVariant | onSurfaceSecondary（次要文字）/ onSurfaceVariantSummary（摘要）/ onSurfaceContainerVariant（行内次要） |
| outline | outline |
| outlineVariant | dividerLine（分隔线）/ outline（描边） |
| error / onError | error / onError |
| errorContainer / onErrorContainer | errorContainer / onErrorContainer |
| scrim / 其他 | windowDimming（弹窗遮罩）；无对应时按语义就近并注释 |

`MaterialTheme.typography.X` → `MiuixTheme.textStyles.Y`：

| Material 3 | Miuix TextStyles |
|---|---|
| titleLarge / titleMedium / titleSmall | title1 / title2 / title3 |
| bodyLarge / bodyMedium | body1 / body2 |
| bodySmall | footnote1 |
| labelLarge | button |
| labelMedium / labelSmall | footnote2 |
| display*/headline* | headline1 / headline2 / title1（按视觉大小就近） |

（TextStyles 公开属性：main, paragraph, body1, body2, button, footnote1, footnote2, headline1, headline2, subtitle, title1..title4。）

## 3. 组件映射与 import

包速查（全部核对过或按下表使用）：

| 能力 | import |
|---|---|
| Scaffold / Card / Text / Icon / Button / TextButton / IconButton / Switch / Checkbox / RadioButton / Slider / TextField / Surface / TopAppBar / SmallTopAppBar / NavigationBar / NavigationBarItem / NavigationItem / FloatingNavigationBar / TabRow / Divider / ProgressIndicator / Snackbar / FloatingActionButton / SearchBar / Badge / PullToRefresh / BasicComponent / SmallTitle / NumberPicker | `top.yukonga.miuix.kmp.basic.*`（同名类） |
| OverlayDialog / OverlayBottomSheet / OverlayDropdownMenu / OverlayListPopup 等 | `top.yukonga.miuix.kmp.overlay.*` |
| WindowDialog / WindowBottomSheet 等 | `top.yukonga.miuix.kmp.window.*` |
| SwitchPreference / CheckboxPreference / ArrowPreference / SliderPreference / RadioButtonPreference / DropdownPreference / SpinnerPreference 等 | `top.yukonga.miuix.kmp.preference.*` |
| MiuixIcons | `top.yukonga.miuix.kmp.icon.MiuixIcons`；基础图标 `top.yukonga.miuix.kmp.icon.basic.<Name>`；扩展图标 `top.yukonga.miuix.kmp.icon.extended.<Name>` |

**已核对的调用形态**（直接采用）：

```kotlin
// Scaffold + 底部导航（docs/demo/ScaffoldDemo.kt、NavigationBarDemo.kt 已核对）
Scaffold(
    topBar = { SmallTopAppBar(title = "标题") },      // SmallTopAppBar(title: String, ...)
    bottomBar = {
        NavigationBar {
            items.forEachIndexed { i, item ->
                NavigationBarItem(
                    selected = i == selected,          // 位置参数顺序：selected, onClick
                    onClick = { ... },
                    icon = item.icon,                  // icon: ImageVector（不是槽位！）
                    label = item.label,                // label: String（不是槽位！）
                    // colors = NavigationBarDefaults.navigationBarItemColors(),  // 需要时；colors 在 badge 之前
                    // badge = { ... }                                           // 具名传参
                )
            }
        }
    },
) { paddingValues -> ... }   // 内容记得消费 paddingValues

// NavigationItem(label: String, icon: ImageVector) 用于缓存 tab 配置
// Text：top.yukonga.miuix.kmp.basic.Text(text = ..., style = MiuixTheme.textStyles.title1)
// Icon：top.yukonga.miuix.kmp.basic.Icon(imageVector = ..., contentDescription = ..., modifier = ..., tint = ...)
```

**其余组件（Button、TextField、Switch、Slider、ProgressIndicator、Snackbar、Dialog/BottomSheet、Preference 行、TopAppBar 完整参数、TabRow、Divider、Surface、BasicComponent…）**：
使用前**必须** fetch 对应 `docs/demo/src/commonMain/kotlin/<Component>Demo.kt`（以及不确定参数时 fetch 源码
`miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/basic/<Component>.kt` 等）核对形参名与顺序。
特别注意：
- `NavigationBarItem`/`FloatingNavigationBarItem` 的 `colors` 在 `badge` 之前 → badge 必须具名传参。
- `TextField` 没有 `isError`/`supportingText`（0.9.4），错误提示自行用 Text + error 色表达。
- `BasicComponent` 是 `startAction` 槽而非 `icon` 参数。
- 弹窗二选一：页面内生命周期 → Overlay 系（需 Scaffold 祖先）；独立窗口/跨页 → Window 系。
- 示例 App（`example/shared/...`）只作组合参考，不引入其 helper。

## 4. 图标映射（Material Icons → MiuixIcons，全部已在 0.9.4 图标清单中核对存在）

| Material | Miuix（import `top.yukonga.miuix.kmp.icon.extended.<Name>`，标注 basic 的用 `icon.basic`） |
|---|---|
| Icons.Default.Home | MiuixIcons.Home |
| Icons.Default.Person | MiuixIcons.Contacts |
| Icons.Default.AccountCircle | MiuixIcons.ContactsCircle |
| Icons.Default.DateRange（日历语义） | MiuixIcons.Weeks（或 Months；“历史/最近”语义用 MiuixIcons.Recent） |
| Icons.Default.Bluetooth | MiuixIcons.SearchDevice（无蓝牙图标；设备语义） |
| Icons.Default.BluetoothSearching | MiuixIcons.Scan（或 SearchDevice，按语境） |
| Icons.Default.Refresh | MiuixIcons.Refresh |
| Icons.Default.Check | MiuixIcons.Basic.Check（basic 图标必须带 `Basic.` 段：`val MiuixIcons.Basic.Check`，import `top.yukonga.miuix.kmp.icon.basic.Check`） |
| Icons.Default.Info | MiuixIcons.Info |
| Icons.Default.Warning | MiuixIcons.Report（无 warning 图标；警示语义） |
| Icons.Default.Tune | MiuixIcons.Tune |
| Icons.AutoMirrored...ArrowBack | MiuixIcons.Back |
| Icons.AutoMirrored...ArrowForward | MiuixIcons.Forward（行尾跳转可用 MiuixIcons.ArrowRight，basic） |
| Icons.Default.ArrowDropDown / KeyboardArrowDown | MiuixIcons.ExpandMore |
| Icons.Default.KeyboardArrowUp | MiuixIcons.ExpandLess |
| Icons.Default.Sort | MiuixIcons.Sort |
| Icons.Default.Add | MiuixIcons.Add |
| Icons.Default.Delete | MiuixIcons.Delete |
| Icons.Default.Close | MiuixIcons.Basic.Close（basic） |
| Icons.Default.ContentCopy | MiuixIcons.Copy |
| Icons.Default.Share | MiuixIcons.Share |
| Icons.Default.Favorite | MiuixIcons.FavoritesFill |
| Icons.Default.FavoriteBorder | MiuixIcons.Favorites |
| Icons.Default.Security | MiuixIcons.Lock |
| Icons.Default.CloudSync | MiuixIcons.Update |
| Icons.Default.Cloud | MiuixIcons.CloudFill |
| Icons.Default.CloudUpload | MiuixIcons.UploadCloud |
| Icons.Default.CloudDownload | MiuixIcons.Download |
| Icons.AutoMirrored...OpenInNew | MiuixIcons.Link（跳转外部） |
| Icons.Default.ManageAccounts | MiuixIcons.Settings |
| Icons.Default.Calculate | MiuixIcons.Tasks（计算/算法语义；可按语境选 MindMap） |
| Icons.AutoMirrored...Article | MiuixIcons.Notes |
| Icons.Default.DataObject | MiuixIcons.File |
| Icons.Default.AutoAwesome | MiuixIcons.Promotions（或按语境 Favorites/Theme，就近选择并保持语义） |

其他未列图标：在下方完整清单中选**语义最近**的一个，禁止自造矢量图或保留 Material 图标。
Miuix 0.9.4 图标全集（basic: ArrowRight, ArrowUpDown, Check, Close, Search, SearchCleanup, Sidebar）：
extended: Add, AddCircle, AddFolder, Alarm, Album, All, Answer, AppRecording, Back, Background, Backup, BankCards, Blocklist, CallRecording, Carrier, ChevronBackward, ChevronForward, Clear, Close2, CloudFill, Community, Contacts, ContactsBook, ContactsCircle, ConvertFile, Copy, Create, Cut, Delete, Download, Edit, Email, ExpandLess, ExpandMore, Favorites, FavoritesFill, File, FileDownloads, Filter, Folder, FolderFill, Forward, GridView, Help, Hide, Home, HorizontalSplit, Image, Import, Info, Layers, Link, ListView, Location, Lock, MapAlbum, Merge, Messages, Mic, MicSlash, MindMap, Months, More, MoreCircle, MoveFile, Music, Notes, NotesFill, Ok, Paste, Pause, Phone, Photos, Pin, Play, Playlist, Promotions, Recent, Recording, RecordingTape, Redo, Refresh, Remove, RemoveContact, Rename, Replace, Reply, ReplyAll, Report, Reset, RotateLeft, Scan, ScreenCapture, ScreenMirroring, SearchDevice, SelectAll, Send, Settings, Share, Show, Sort, Stopwatch, Store, Tasks, Th1..Th31, Theme, Timer, TopDownloads, Translate, Trim, Tune, Undo, Unlock, Unpin, Update, UploadCloud, VerticalSplit, VolumeOff, VolumeUp, Weeks, WorldClock, Years, ZoomOut。
图标权重：扩展图标默认 `MiuixIcons.X`（Regular），需要粗细时 `MiuixIcons.Medium.X` 等（仅当源码中该权重存在）；**基础图标（ArrowRight, ArrowUpDown, Check, Close, Search, SearchCleanup, Sidebar）的访问形态是 `MiuixIcons.Basic.X`**（源码 `val MiuixIcons.Basic.Check`），不是 `MiuixIcons.X`。

## 5. 交付自查清单

- [ ] 文件中无 `material3` / `material.icons` / `MaterialTheme` / `Icons` 残留（用 grep 自查）
- [ ] 每个 Miuix 组件的形参名/顺序在固定快照核对过；报告中列出“组件 ↔ 核对来源”
- [ ] 业务逻辑、状态、回调签名与迁移前一致（公开的 `@Composable` 函数签名不变，避免牵连调用方）
- [ ] 文本/间距/尺寸尽量保持原视觉密度（可用 `MiuixTheme.textStyles.*.copy(...)` 与原 dp 值）
- [ ] 弹窗有明确的关闭/取消路径；disabled 与空态保留
