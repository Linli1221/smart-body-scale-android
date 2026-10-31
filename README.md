# 智能体脂秤 Android 客户端 (Smart Body Scale Android)

这是一个基于 Android 平台开发的**智能体脂秤客户端应用**。项目采用现代化的 **Jetpack Compose** 声明式 UI 框架构建，通过 **低功耗蓝牙 (BLE)** 与体脂秤进行实时数据通信，解析专有硬件协议，利用 BIA（生物电阻抗分析）算法计算并记录用户的各项身体指标（体重、体脂率、BMI、水分、肌肉量、蛋白质、骨量等）。

> **支持范围**：本客户端目前**仅支持两家体脂秤**——**薄荷健康 / 沃莱 (Yolanda)** 与 **小米体脂秤 2 代 / 米家体脂秤**。
> 薄荷健康一侧已覆盖两代协议：旧款 `0xAC` 复合帧与**薄荷健康 B1 (AFU-BH-TZ-B1)** 的 `0xFC50` + `AF 45` 帧。
> 其它品牌（蓝牙 SIG 通用秤、OKOK/芯海、香山、斐讯 S7/S9、小米体重秤 1 代等）的协议支持已全部移除。

ios版项目地址 https://github.com/maoziban/smart-body-scale-IOS

---

## 🌟 功能特性

- ⚡ **极速蓝牙连接**：支持 BLE 低功耗蓝牙扫描与自动重连，连接后自动申请高优先级连接模式（`CONNECTION_PRIORITY_HIGH`），确保服务搜索和特征订阅零延迟。
- 📊 **多维身体指标计算 (BIA)**：
  - 测得阻抗时，使用双频阻抗算法高精度计算身体成分。
  - **多套国际公认估算模型可切换**：内置通用模型、亚洲人专属模型 (Wu et al., 2015)、通用修正模型 (Jebb et al., 2000)，在「我的 → 体脂估算模型」中一键切换；切换时自动把全部历史记录重算到同一口径，历史曲线不会断层。
  - 穿袜子或阻抗测得为 0 时，自动无缝降级为基于身高、年龄、性别的智能估算算法，确保称重必定有数据反馈。
- 👥 **多成员家庭管理**：
  - 支持添加、删除家庭成员，**免去烦琐的体重估算输入**。
  - **首测智能绑定**：新成员首次称重时，系统会自动捕获并将实际体重绑定为该成员的参考体重。
  - **动态权重匹配**：后续测量时，系统会自动在 ±7kg 的范围内动态匹配最接近的家庭成员。
- 📅 **历史测量档案与详情**：
  - 历史数据以底部弹层呈现：六张指标走势图（顺序可调）+ 指标统计卡 + 全部记录卡片流，支持一键删除单次记录。
  - **测量详情弹窗**：点击记录卡片即可查看 8 项身体参数（体重/BMI/体脂率/水分/肌肉量/蛋白质/骨量/阻抗）及其健康区间达标徽标。
- 🎨 **HyperOS 单页面 UI（Miuix）**：
  - 全站界面基于 **Miuix 0.9.4**（HyperOS 设计语言）：统一字阶、SmallTitle + Card 分组、Preference 设置行与语义色 token。
  - **单页面 + 弹层架构**：默认单页面就是测量主页；历史数据、我的/设置、配对、日志与协议抓包全部以**底部弹层**承载，无页面导航栈，返回手势逐层关闭弹层。
  - **测量完成自动弹出历史**：一次称重结束后，历史数据弹层自动从底部弹出，直接看到本次结果与全部历史趋势。
  - 历史详情以弹窗展示 8 项身体参数（体重/BMI/体脂率/水分/肌肉量/蛋白质/骨量/阻抗）及健康区间达标徽标。
- 🧪 **蓝牙协议完整抓包（Debug 专用）**：Debug 包内置协议抓包器，完整记录扫描广播、GATT 连接、服务/特征拓扑、CCCD 订阅、AFU 握手包与体重/阻抗通知的**全部原始十六进制字节**，支持一键导出 `.jsonl` 文件分享到电脑离线分析。
- 🔍 **运行日志查看器（Debug 专用）**：Debug 包的「我的 → 查看运行日志」可实时查看内存环形缓冲中的运行日志，并支持复制/分享导出。
- 📦 **仅 arm64-v8a**：`defaultConfig.ndk.abiFilters` 只保留 `arm64-v8a`，APK 内不再打包其它 ABI 的 `.so`（Compose 的传递依赖 `androidx.graphics:graphics-path` 原本会带入 armeabi-v7a / arm64-v8a / x86 / x86_64 四份 `libandroidx.graphics.path.so`），安装包更小。

> **Release 包不含上述两个调试入口**：正式包的「我的」页面里既没有「查看运行日志」，也没有「蓝牙协议抓包」。
> 两个入口分别由 `BuildConfig.RUNTIME_LOG_VIEWER` 与 `BuildConfig.BLE_PROTOCOL_CAPTURE` 控制，
> 在 Release 构建中均被显式写死为 `false`（且叠加 `BuildConfig.DEBUG`），入口与弹窗都不会渲染，
> 协议抓包器也一条报文都不会记录（详见 [实现约束](#实现约束release-包为什么拿不到协议报文)）。

---

## 🧮 体脂估算模型（BIA 公式切换）

不同人群体脂构成的差异很大，同一套公式不可能对所有人都准。本项目把三套经过同行评议的
**F-F（脚踩式）生物电阻抗预测公式**做成了可切换选项，入口在
**「我的 → 应用设置 → 体脂估算模型」**。

| 模型 | 公式 | 适用人群 | 金标准 |
| :--- | :--- | :--- | :--- |
| **内置通用模型**（默认） | 男：`0.18×BMI + 0.012×年龄 + 0.018×Z − 3.2`<br>女：`0.26×BMI + 0.011×年龄 + 0.020×Z − 2.5` | 通用，结果偏保守稳定 | 内置经验公式 |
| **亚洲人专属** (Wu et al., 2015) | `FFM = 13.055 + 0.204×体重 + 0.394×(身高²/Z) − 0.136×年龄 + 8.125×性别`<br>体脂率 `= (1 − FFM ÷ 体重) × 100%` | **最适合亚洲人** | DXA（双能 X 线） |
| **通用修正** (Jebb et al., 2000) | `%FM = −156.1 − 89.1×ln(身高m) + 45.6×ln(体重kg) + 0.120×年龄 + 0.0494×Z`<br>女性额外 `+ 19.6×ln(身高m)` | 高加索人群 / 通用修正 | 四室模型 |

> 公式中 `Z` 为体内阻抗（Ω），身高以 cm 计（Jebb 公式中取 m），体重以 kg 计，性别男=1、女=0。

### 行为约定

- **切换后自动重算全部历史记录**：确认新模型的那一刻，应用会把数据库中所有
  「已绑定成员 + 有有效阻抗」的记录一次性换算到同一公式，历史曲线不会前后断层。
  重算在单个数据库事务内完成——要么全部成功，要么整体回滚，不会留下混合数据。
- **重算时保留记录的身份信息**：记录 ID、称重时间、体重、阻抗、成员绑定关系、
  成员姓名快照全部原样保留，只覆盖体脂率及其派生的肌肉量/水分/蛋白质/骨量，
  因此不会产生新记录，也不会打乱历史排序。
- **跳过的记录保持原值**：未绑定成员（或成员已被删除）、未赤脚测阻抗的记录
  无法参与公式换算，保持原值不动，并在完成提示中告知跳过了几条。
- **幂等**：同一模型重复触发重算时，判定为"未变化"并跳过写库，不产生无意义 IO。
- **对话框内直接展示公式原文与来源**：选中某个模型即可看到完整公式、适用人群与文献出处，不必退出页面查资料。
- **测量结果卡片标注当前模型**：称重完成后，结果卡片底部会显示「估算模型：xxx」，让体脂率的口径一目了然。
- **结果收敛到生理合理区间**：每个模型自带体脂率上下限（`fatMin`/`fatMax`），
  极端输入（身高异常、阻抗异常大）下也不会输出生理上不可能的值。
- **无阻抗时不估算**：未赤脚称重（`Z = 0`）时任何模型都返回 `0.0`，不会用虚假参数凑数。

### 代码位置

| 关注点 | 文件 |
| :--- | :--- |
| 模型枚举与公式元数据 | [`BiaModel.kt`](app/src/main/java/com/example/dianzicheng/domain/BiaModel.kt) |
| 三套公式的具体实现 | [`BodyAlgorithm.kt`](app/src/main/java/com/example/dianzicheng/domain/BodyAlgorithm.kt) 的 `calculateBodyFatPct()` |
| **历史记录重算** | [`ScaleRepository.kt`](app/src/main/java/com/example/dianzicheng/data/repository/ScaleRepository.kt) 的 `recalculateHistory()` |
| 模型选择对话框 | [`SelectBiaModelDialog.kt`](app/src/main/java/com/example/dianzicheng/ui/SelectBiaModelDialog.kt) |
| 偏好持久化 | [`PreferenceManager.kt`](app/src/main/java/com/example/dianzicheng/data/local/PreferenceManager.kt) 的 `selectedBiaModel` / `saveSelectedBiaModel` |
| 单元测试 | [`BiaModelTest.kt`](app/src/test/java/com/example/dianzicheng/BiaModelTest.kt)、[`BodyAlgorithmTest.kt`](app/src/test/java/com/example/dianzicheng/BodyAlgorithmTest.kt)、[`ScaleRepositoryRecalcHistoryTest.kt`](app/src/test/java/com/example/dianzicheng/ScaleRepositoryRecalcHistoryTest.kt) |

单元测试直接用文档示例值做回归：`62.2kg / 1.73m / 21 岁 / Z=650Ω` 的男性受试者，
Wu 2015 模型应得 **≈ 21.0%**，Jebb 2000 模型应得 **≈ 18.0%**。
历史重算的测试覆盖了：正常换算、跳过无阻抗记录、跳过无成员记录、
同模型幂等、身份字段不被篡改、空历史、以及汇总文案。

---

## 🛠️ 技术栈

- **开发语言**：Kotlin
- **UI 框架**：Jetpack Compose + **Miuix 0.9.4**（HyperOS 设计语言组件库：miuix-ui / miuix-preference / miuix-icons）
- **界面架构**：单页面 + 底部弹层（OverlayBottomSheet / WindowDialog），无页面导航栈
- **异步与流式编程**：Kotlin Coroutines & Flow (StateFlow / SharedFlow)
- **本地数据库**：Room Database (支持自动建表与实体映射转换)
- **蓝牙通信**：Android BLE Core APIs (基于 GATT 协议开发，高度定制化 PacketParser)
- **协议调试**：Debug 专用 BLE 协议完整抓包器（JSON Lines 导出 + FileProvider 文件分享）
- **构建环境**：JDK 21 / Gradle 9.5 / AGP 9.3 / compileSdk 36
- **ABI 策略**：仅 `arm64-v8a`（`defaultConfig.ndk.abiFilters`）

---

## 📡 蓝牙硬件报文协议

### 1. 薄荷健康 / 沃莱 (Yolanda) — 双向 GATT 连接

客户端连接后订阅 `FFB2` 蓝牙特征的通知，接收 20 字节原始十六进制报文（例：`AC 29 00 69 40 82 02 00 05 40 ...`）进行实时动态解析：

| 字节偏移 (Index) | 格式说明 | 协议解析规则 |
| :--- | :--- | :--- |
| **第 0 字节** | 报文头 | 必须为魔数 `0xAC` 才会进行数据解析 |
| **第 1-2 字节** | MAC 地址 | 体脂秤设备的物理 MAC 地址 |
| **第 3-5 字节** | 体重数值 | 重量 ($\text{kg}$) = $\frac{(raw[3] - 0x68) \times 65536 + raw[4] \times 256 + raw[5]}{1000.0}$ |
| **第 6 字节** | 稳定标志位 | `0x02`（含 `0x03\u20130x05` 体脂测算阶段）表示体重数值锁定且稳定，其它表示称重中 |
| **第 8-9 字节** | 电阻抗 (BIA) | Big Endian 格式双字节阻抗值（例：`0x0540` = 1344 Ω） |

> 连接建立后需向 `FFB1` 特征写入 10 字节握手指令 `FD 37 00 00 00 00 00 00 00 37`，秤端才会开始推送体重与阻抗数据。

此外还兼容薄荷健康/沃莱的 **8 字节流式帧**（`AC 02 …`，状态位 `0xCA/0xCB/0xCC/0xCD/0xCE`）与 **0xA2 / 0xA3 / 0xCA20 复合帧**。

> 🔍 **需要核对真实报文？** 见下文 [蓝牙协议完整抓包（Debug 专用）](#-蓝牙协议完整抓包debug-专用)，可在真机上导出一次称重的完整协议交互记录。

### 2. 薄荷健康 B1 (AFU-BH-TZ-B1，蚂蚁阿福联名款) — 双向 GATT 连接

B1 是薄荷健康家族的新一代型号：广播名以 `AFU` 开头（如 `AFU-BH-TZ-B1`），
广播中**只带身份信息、不带体重**，必须建立 GATT 连接后从通知特征取数。

| 项目 | UUID |
| :--- | :--- |
| 广播 / 数据服务 | `0000FC50-0000-1000-8000-00805F9B34FB` |
| 命令写入特征 (WRITE) | `af000002-9f2d-4c8a-8d6f-6a7b45f90000` |
| 数据上报特征 (NOTIFY / INDICATE) | `af000003-…` / `af000004-…` |

帧结构（收发都是固定 20 字节）：

```
[0]=0xAF  [1]=0x45  [2]=子命令  [3..17]=帧体  [18]=线类型  [19]=校验
```

- **校验和**：`sum(raw[2..18]) & 0x1F`
- **线类型 (raw[18])**：`0x51` 实时体重、`0x54` 存储/历史记录、`0x52` 单次阻抗结果、
  `0x5F` 控制帧、`0x62` 同步请求（手机→秤）、`0x61` 用户档案（手机→秤）
- **体重**：位于「字节序反转后的 32 位标志字」低 18 位，单位为**克**；最高位（bit 31）为示数锁定标志

连接后的交互顺序（与官方 App 行为一致）：

1. **握手**：对时（`0x16`）→ 连续两次同步请求（`0x04`）。此时**不能**下发用户档案，
   否则秤会切到称重模式并阻断自己的历史队列。
2. **实时称重**：秤以约 120 ms 间隔推送 `0x51`，体重锁定帧的 bit 31 置位；此时才下发 `0x3F` 用户档案。
3. **逐帧回确认**：秤端未被确认的帧会反复重投，因此每一帧数据都要回 `0x12` 确认
   （`0x54` 记录还需回带序号）；加密报告分片（`0x5F/0x14`）回带 `0x14`。
4. **拉取本次记录**：等用户下秤、秤空闲 2 秒后，重新对时 + 两次同步请求 + 档案 `0x4B`/`0x5B` +
   分片请求（`0x14`），秤即回推本次的 `0x54`（携带阻抗）。
5. **收尾**：下发档案 `0x68` + 对时 + 同步请求，让秤准备好下一次称重。

> ⚠️ 档案 `0x5B` 会为**下一次**称重「武装」阻抗测量，因此首次称重通常只有体重，第二次起才能测出体脂。
> 协议常量与命令构造见 [`AFUB1PacketParser.kt`](app/src/main/java/com/example/dianzicheng/data/ble/AFUB1PacketParser.kt)；
> 单元测试 [`AFUB1PacketParserTest.kt`](app/src/test/java/com/example/dianzicheng/AFUB1PacketParserTest.kt)
> 直接使用真机抓包帧逐帧校验协议解析结果。

### 3. 小米体脂秤 2 代 / 米家 — 免配对广播秤

小米体脂秤 2 代通过蓝牙广播 `0x181B` (Body Composition Service) 的 **13 字节 Service Data** 上报数据，**即踩即读，无需建立 GATT 连接**（广播名 `MIBFS`，厂商数据 Company ID `0x0157`）：

| 字节偏移 | 说明 |
| :--- | :--- |
| **Byte 0** | `control_byte_1`：单位/型号标识，`0x02` = kg，`0x03` = lbs |
| **Byte 1** | `control_byte_2`：状态标志位 —— bit 1 = 阻抗已测出，bit 5 = 读数已稳定锁定，bit 6 = 单位为斤 (catty)，bit 7 = 已下秤 / 读数已移除 |
| **Byte 2-8** | 年月日时分秒时间戳 |
| **Byte 9-10** | 阻抗 (UINT16 小端序，Ω；未测出时为 `0x0000` 或 `0xFFFF`) |
| **Byte 11-12** | 体重 (UINT16 小端序；kg 精度 1/200，斤与 lbs 精度 1/100) |

> ⚠️ 标志位必须**按字节分别取位**：`Byte 1` 承载全部状态位，`Byte 0` 只承载单位标识。
> 若把 `Byte 0~1` 当作一个小端序 16 位 flags 字，`bit 1 / bit 5 / bit 7` 都会落到 `Byte 0` 上而永远无法命中——
> 结果是阻抗永远读不出来，且秤唤醒时重播的上一次旧读数会被误判成一次稳定测量。
> 另外 `bit 7`（读数已移除）置位的包只是秤在重播收尾结果，不能当作新的稳定测量写库。

---

## 📂 项目结构

```text
app/src/main/java/com/example/dianzicheng/
│
├── data/
│   ├── ble/              # 蓝牙连接中心 (BleScaleClient)
│   │                     #   ├─ MultiScalePacketParser：薄荷健康/沃莱 + 小米体脂秤 2 代协议分发
│   │                     #   ├─ AFUPacketParser：薄荷健康/沃莱旧款 0xAC 20 字节复合帧解析
│   │                     #   ├─ AFUB1PacketParser：薄荷健康 B1 的 AF 45 帧编解码与握手/档案/确认构造
│   │                     #   └─ BleProtocolRecorder：Debug 专用的蓝牙协议完整抓包器
│   ├── health/           # Health Connect 系统健康数据同步
│   ├── backup/           # WebDAV 云端备份
│   ├── local/            # Room 数据库配置 (ScaleDao, Entities, AppDatabase) 与实体转换器
│   └── repository/       # 统一数据仓库 (ScaleRepository) 负责数据匹配与持久化
│
├── domain/               # 业务实体 (Models)、支持型号枚举 (ScaleModel)、
│                         #   电阻抗估算模型枚举 (BiaModel) 及 BIA 身体指标核心算法 (BodyAlgorithm)
│
└── ui/                   # 单页面 + 弹层前端（全部基于 Miuix 0.9.4）
    ├── ScaleApp.kt       #   应用壳：根 Scaffold + 弹层路由（测量完成自动弹出历史数据）
    ├── theme/            #   AppTheme 根主题（ThemeController(MonetSystem) + MiuixTheme）
    ├── state/            #   ScaleViewModel（称重会话状态机）、HistoryViewModel、ProfileViewModel 与 UI 状态
    ├── page/             #   MeasurePage：测量主页（成员绑定 / 连接状态 / 体重大读数 / 结果卡 / 操作区）
    ├── sheet/            #   HistorySheet（完整历史）、SettingsSheet（我的）、PairingSheet（配对）、
    │                     #   DebugSheets（运行日志 / 蓝牙协议抓包，Debug 专用）——均为底部弹层
    ├── dialog/           #   成员选择/添加、型号与体脂模型选择、WebDAV 配置、删除确认等弹窗
    └── component/        #   指标趋势图自绘、健康区间徽标、连接状态 chip 等共享组件
```

---

## 🚀 构建与发布（仅使用 GitHub Actions，不在本地构建）

本项目**不提供本地构建流程**，所有编译、单元测试与 APK 打包均在 **GitHub Actions** 云端完成。
工作流定义见 [`.github/workflows/android.yml`](.github/workflows/android.yml)。

### 触发方式

| 事件 | 说明 |
| :--- | :--- |
| `push` 到 `main` / `master` | 自动构建 Debug，并额外做一次 **Release 变体编译校验**（只编译校验，不发布） |
| 对 `main` / `master` 发起 `pull_request` | 自动构建 Debug |
| `workflow_dispatch` | 在 Actions 页面手动点击 **Run workflow**（可勾选 `build_release` 一并出未签名 Release 包） |

### 云端执行的步骤

1. `actions/checkout` 拉取代码；
2. `actions/setup-java` 安装 **JDK 21**（与 `gradle/gradle-daemon-jvm.properties` 的 `toolchainVersion=21` 一致）；
3. `android-actions/setup-android` 安装 Android SDK（`platform-tools`、`platforms;android-36`）；
4. `./gradlew testDebugUnitTest` 运行单元测试；
5. `./gradlew assembleDebug` 打包 Debug APK，并**校验 APK 的原生库只剩 `arm64-v8a`**（见下方「ABI 策略」）；
6. 上传产物 Artifacts：`app-debug-apk`、`unit-test-report`；
7. **仅 `push` 到 `main`/`master` 时**，额外运行 `release-compile-check` 作业：
   `./gradlew assembleRelease -PbuildRelease=true`（独立并发组、不取消），
   确保 Release 专属代码路径不会一直隐藏到发版当天才暴露问题（该作业不上传产物，同样会校验 ABI）。

### ABI 策略：只编译 arm64-v8a

`app/build.gradle.kts` 的 `defaultConfig.ndk.abiFilters` 只放行 `arm64-v8a`：

```kotlin
defaultConfig {
    ndk {
        abiFilters += "arm64-v8a"   // 只编译 / 只打包 arm64
    }
}
```

为什么必须显式限制：本项目依赖里的原生库来自 Compose 的传递依赖
`androidx.compose.ui:ui-graphics` → `androidx.graphics:graphics-path`，它自带
`libandroidx.graphics.path.so`，AAR 里同时提供 **armeabi-v7a / arm64-v8a / x86 / x86_64** 四份；
不加限制四份会全部打进 APK。加上该过滤后，APK 的 `lib/` 下只会保留 `lib/arm64-v8a/`。

- 生效范围：**Debug 与 Release 都只出 arm64**（写在 `defaultConfig` 里）；
- 副作用：x86 / x86_64 模拟器上会因缺少该 `.so` 而无法正常运行 Compose 的路径绘制与矢量图标。
  本项目调试与分发一律使用 arm64 真机，若确需模拟器调试，请临时把对应 ABI 加回 `abiFilters`；
- 回归保护：CI 在 Debug 打包后、Release 打包后都会检查 APK 里是否有非 `lib/arm64-v8a/` 的原生库，
  一旦 `abiFilters` 被误删就直接失败。

> **默认只构建 Debug 版本。** 原因有二：
> 1. 蓝牙协议完整抓包导出与「查看运行日志」查看器仅存在于 Debug 包（依赖 `BuildConfig.DEBUG`）；
> 2. 日常分发与自测都使用 Debug 包，无需为每次提交额外编译 Release。
>
> 实现方式是**在 Gradle 配置阶段直接禁用 release 构建变体**（`app/build.gradle.kts` 中的
> `androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = false } }`），
> 因此 `./gradlew build`、`./gradlew assemble`、`./gradlew check` 这类聚合任务都只会处理 Debug 变体。
> 根项目 `build.gradle.kts` 另外声明了 `defaultTasks(":app:testDebugUnitTest", ":app:assembleDebug")`，
> 保证直接运行 `./gradlew`（不带任务名）时也只做 Debug 单测与 Debug 打包
> （注意：Gradle 只从**根项目**解析 default tasks，写在子项目里对根目录调用无效）。
>
> 需要未签名 Release 包时，必须显式放行该变体：在 Actions 页面手动 **Run workflow** 并勾选 `build_release`，
> 或本地执行 `./gradlew assembleRelease -PbuildRelease=true`。

### 获取 APK

1. 打开本仓库的 **Actions** 页面；
2. 进入最近一次成功的 **Android CI** 运行；
3. 在页面底部 **Artifacts** 区域下载 `app-debug-apk`（arm64 真机可直接安装，且自带蓝牙协议抓包与运行日志查看能力）；
4. 如需正式发布，请按上一节手动构建 Release 包并自行签名（同一 APK 不含上述两个调试入口，且同样为 arm64-only）。

---

## 🧪 蓝牙协议完整抓包（Debug 专用）

排查「称重数值不对 / 阻抗测不出 / 连不上秤」这类问题时，只看运行日志往往不够——必须看到**秤到底发了什么字节**。
Debug 包内置协议抓包器，把整个蓝牙交互过程逐条记录下来，并可导出成文件在电脑上离线分析。

### 抓到了什么

在**任何过滤逻辑之前**记录，因此不存在「被 App 的筛选规则漏掉」的报文：

| 方向 | 事件 | 记录内容 |
| :--- | :--- | :--- |
| `SCAN` | `SCAN_RESULT` | 每条扫描广播的**完整 AD 结构原始字节**、RSSI、TxPower、是否可连接、Service UUID 列表、设备名 |
| `SCAN` | `AD_STRUCTURES` | 命中候选设备/目标设备的 AD 结构逐项拆解（`0xFF` 厂商数据、`0x16` 服务数据、`0x19` Appearance 等） |
| `EVENT` | `CONNECT_ATTEMPT` / `CONNECT_STATE` / `CONNECT_RETRY` / `CONNECT_TIMEOUT` | 连接发起、状态机变更（含 `status=133/8/19` 等关键诊断码）、退避重试与超时看门狗 |
| `EVENT` | `DISCOVER_SERVICES_REQUEST` / `SERVICE_DISCOVERY_RESULT` | 服务发现的发起与最终 status（**失败时也会记录**，这是定位发现失败的唯一证据） |
| `EVENT` | `SERVICE_FOUND` / `CHARACTERISTIC` | 服务发现得到的**完整协议拓扑**：每个服务、每个特征的 properties 位掩码与全部描述符 UUID |
| `EVENT` | `SUBSCRIBE_PLAN` / `SUBSCRIBE` | 服务发现后确定的订阅清单，以及每个待订阅特征的 properties、CCCD 描述符 UUID 与将要写入的开关值（通知/指示） |
| `OUT` | `WRITE` / `DESCRIPTOR_WRITE` | 手机**发往秤**的完整字节（如 AFU 握手包 `FD 37 00 00 00 00 00 00 00 37`）及其入队结果 |
| `EVENT` | `WRITE_RESULT` / `DESCRIPTOR_WRITE_RESULT` | 下行写入的**最终 GATT 结果码**（对端是否真正收到），与上面的 `WRITE` 构成完整生命周期 |
| `IN` | `NOTIFY` | 秤**发往手机**的完整字节，附带解析结论（命中协议、体重 kg、稳定标志、阻抗 Ω） |
| `IN` | `NOTIFY_UNPARSED` | 解析失败的原始帧单独再落一条，便于在导出文件里一眼筛出「协议未识别」的报文 |

> 说明：单条报文的十六进制载荷上限为 2048 字符（约 682 字节）。真实 BLE 帧（MTU 20~517 字节）远低于该上限，
> 因此实际抓包始终是逐字节完整的；只有异常超大包才会截断，并在 JSON 中以 `…` 标记，同时保留真实的 `len` 字段。

### 怎么用

1. 安装 **Debug** 包（`app-debug-apk`），进入「我的」页面 → **蓝牙协议抓包 (Debug)**；
2. 若想得到干净的报文序列，可先点右上角 🗑 **清空**，再回到「测量」页点亮体脂秤并**完整称重一次**（含下秤）；
3. 回到该页面查看实时报文流，然后选择导出方式：
   - **↗ 导出文件**：写入 `cacheDir/ble-protocol/scale-ble-capture-<时间戳>.jsonl` 并通过系统分享面板发送（微信 / 邮件 / 网盘 / 数据线均可），适合把完整抓包带到电脑上离线分析；
   - **{ } 复制 JSONL**：每行一个 JSON 对象，可直接粘进脚本解析；
   - **⧉ 复制文本**：人类可读的对齐格式，每条含完整十六进制载荷与 ASCII 对照，适合直接贴进 Issue。

### 导出格式

JSON Lines（`.jsonl`）——首行是抓包元信息，其余每行一条报文：

```json
{"meta":true,"app":"smart-body-scale-android","version":"1.4.1","buildType":"debug","entries":128,"dropped":0,"startedAt":1717000000000,"androidSdk":34,"device":"Xiaomi 13"}
{"ts":1717000001234,"offsetMs":1234,"dir":"SCAN","event":"SCAN_RESULT","address":"AA:BB:CC:DD:EE:FF","name":"MIBFS","rssi":-52,"txPower":4,"connectable":true,"serviceUuids":"0000181b-0000-1000-8000-00805f9b34fb","deviceName":"MIBFS","adStructures":"0x01(Flags):06 | 0x16(ServiceData16):1B1810...","len":31,"hex":"02 01 06 11 16 1B 18 ..."}
{"ts":1717000002500,"offsetMs":2500,"dir":"OUT","event":"WRITE","charUuid":"0000ffb1-...","serviceUuid":"0000ffb0-...","writeType":0,"writeTypeName":"WRITE_TYPE_DEFAULT","success":true,"len":10,"hex":"FD 37 00 00 00 00 00 00 00 37"}
{"ts":1717000003900,"offsetMs":3900,"dir":"IN","event":"NOTIFY","charUuid":"0000ffb2-...","protocol":"AFU","weightKg":65.35,"isStable":true,"impedanceOhm":1344.0,"len":20,"hex":"AC 29 00 69 40 82 02 00 05 40 ..."}
```

### 实现约束（Release 包为什么拿不到协议报文）

- 抓包器唯一开关是 `BuildConfig.BLE_PROTOCOL_CAPTURE`，**仅 debug 构建为 `true`**，release 显式写死 `false`；
  叠加 `BuildConfig.DEBUG` 后，Release 版本的 `isEnabled` 恒为 `false`：
  所有记录方法首行直接返回，不格式化字符串、不分配缓冲、不推送状态流，
  **一条报文都不会被记录**，UI 入口也整体不渲染；
- 同样地，「查看运行日志」入口由 `BuildConfig.RUNTIME_LOG_VIEWER` 控制，**仅 debug 构建为 `true`**：
  Release 版本不仅没有该入口与查看弹窗，也没有任何日志导出路径（`AppLogger` 仍写 Logcat，仅不可在应用内查看）；
- 完整的十六进制原始报文（收包与握手发包）**只在 Debug 构建写入运行日志**；
  Release 版本日志里只保留「收到蓝牙数据 (N 字节)」这类摘要，不会把协议载荷带进可复制/可分享的日志里；
- 抓包缓冲与 `AppLogger` 的日志缓冲**相互独立**（`BleProtocolRecorder.MAX_ENTRIES = 6000`），不会被 1000 条运行日志滚动挤掉；
- 导出文件只写入 `cacheDir/ble-protocol/`，该路径是 `FileProvider` 唯一对外暴露的目录（`res/xml/file_paths.xml`），
  分享时通过 `FLAG_GRANT_READ_URI_PERMISSION` 临时授权，接收方无法访问 App 的其它文件；
- 需要说明的一点：Release 构建现已开启 R8（`isMinifyEnabled = true` + `isShrinkResources = true`）。
  由于 `BuildConfig.DEBUG` / `BLE_PROTOCOL_CAPTURE` 在 Release 下恒为 `false`，
  抓包器与日志查看器中那些「恒不成立」的分支会被 R8 直接裁剪掉，
  相关代码不再进入 Release APK（这也是开启压缩后体积收益最明显的一块）。

---

## 🚀 快速上手（安装已构建的 APK）

1. **下载 APK**：按上一节从 GitHub Actions Artifacts 获取 APK；
2. **部署运行**：
   - 准备一台拥有蓝牙功能的 **arm64** Android 真机（API 26+，开启手机蓝牙；APK 只打包 arm64-v8a，x86 模拟器不可用）；
   - 安装 APK 后打开应用，在「绑定体脂秤」页选择你的型号（薄荷健康/沃莱 或 小米体脂秤 2 代）并站上体脂秤完成配对；
   - 之后每次开启应用直接站上体脂秤即可开始称重与体脂分析。

---

## 🧭 身体报告单页（新版 UI）

新版测量主页把「称重主舞台 + 历史抽屉」合并为同屏单页面，代码入口：

| 内容 | 文件 |
| :--- | :--- |
| 页面编排 / 测量时序 / 异常态 | [`MeasureHomePage.kt`](app/src/main/java/com/example/dianzicheng/ui/page/MeasureHomePage.kt) |
| 三态抽屉 / 时间轴 / 趋势图 | [`BodySheetState.kt`](app/src/main/java/com/example/dianzicheng/ui/body/BodySheetState.kt) / [`BodyHistoryDrawer.kt`](app/src/main/java/com/example/dianzicheng/ui/body/BodyHistoryDrawer.kt) / [`BodyTrendChart.kt`](app/src/main/java/com/example/dianzicheng/ui/body/BodyTrendChart.kt) |
| 8 项指标 Bento 卡片 | [`MetricCards.kt`](app/src/main/java/com/example/dianzicheng/ui/body/MetricCards.kt) / [`BodyMetricUi.kt`](app/src/main/java/com/example/dianzicheng/ui/body/BodyMetricUi.kt) |
| 主表盘 / 状态胶囊 | [`WeightStage.kt`](app/src/main/java/com/example/dianzicheng/ui/body/WeightStage.kt) |
| 设计 token | [`BodyDesign.kt`](app/src/main/java/com/example/dianzicheng/ui/body/BodyDesign.kt) |
| 实现契约 / 验收清单 | [`BODY_REPORT_UI_CONTRACT.md`](docs/BODY_REPORT_UI_CONTRACT.md) / [`BODY_REPORT_UI_ACCEPTANCE.md`](docs/BODY_REPORT_UI_ACCEPTANCE.md) |
| 可交互 HTML 视觉走查稿 | [`prototype/body-report.html`](prototype/body-report.html) |

- 三态抽屉高度 **12% / 62% / 92%**，弹簧 0.82/280，释放速度阈值 400dp/s；
- 测量完成 T+0 锁定闪烁、T+300 流光、T+1300 半展开、T+1500 卡片 stagger；
- 8 项指标为体重 / BMI / 体脂率 / 肌肉量 / 水分 / 内脏脂肪 / 蛋白质 / 骨量；
- 内脏脂肪在原 BIA 数据模型中没有独立字段，新版 UI 用体脂率 + BMI + 年龄 + 性别做**估算展示**，不写入数据库、不作为医学结论；
- 旧 HistorySheet 仍由「全部历史」入口打开，删除/详情/成员筛选等既有能力保留。
