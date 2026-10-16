# 智能体脂秤 Android 客户端 (Smart Body Scale Android)

这是一个基于 Android 平台开发的**智能体脂秤客户端应用**。项目采用现代化的 **Jetpack Compose** 声明式 UI 框架构建，通过 **低功耗蓝牙 (BLE)** 与体脂秤进行实时数据通信，解析专有硬件协议，利用 BIA（生物电阻抗分析）算法计算并记录用户的各项身体指标（体重、体脂率、BMI、水分、肌肉量、蛋白质、骨量等）。

> **支持范围**：本客户端目前**仅支持两家体脂秤**——**薄荷健康 / 沃莱 (Yolanda)** 与 **小米体脂秤 2 代 / 米家体脂秤**。
> 其它品牌（蓝牙 SIG 通用秤、OKOK/芯海、香山、斐讯 S7/S9、小米体重秤 1 代等）的协议支持已全部移除。

ios版项目地址 https://github.com/maoziban/smart-body-scale-IOS

---

## 🌟 功能特性

- ⚡ **极速蓝牙连接**：支持 BLE 低功耗蓝牙扫描与自动重连，连接后自动申请高优先级连接模式（`CONNECTION_PRIORITY_HIGH`），确保服务搜索和特征订阅零延迟。
- 📊 **多维身体指标计算 (BIA)**：
  - 测得阻抗时，使用双频阻抗算法高精度计算身体成分。
  - 穿袜子或阻抗测得为 0 时，自动无缝降级为基于身高、年龄、性别的智能估算算法，确保称重必定有数据反馈。
- 👥 **多成员家庭管理**：
  - 支持添加、删除家庭成员，**免去烦琐的体重估算输入**。
  - **首测智能绑定**：新成员首次称重时，系统会自动捕获并将实际体重绑定为该成员的参考体重。
  - **动态权重匹配**：后续测量时，系统会自动在 ±7kg 的范围内动态匹配最接近的家庭成员。
- 📅 **历史测量档案与详情页**：
  - 提供清爽的测量历史卡片流，支持一键删除单次记录。
  - **二级测量详情页**：点击卡片可平滑切入二级详情界面，以专业的指标网格完整展示多达 8 项身体参数及其达标分析。
- 🎨 **极简科技风 UI 设计**：
  - 全站界面基于 Material Design 3 标准，配合动感渐变色与平滑动效。
  - 全站统一收紧为直观干练的 `8.dp` 现代微圆角风格，视觉更加精致、高级。
- 🧪 **蓝牙协议完整抓包（Debug 专用）**：Debug 包内置协议抓包器，完整记录扫描广播、GATT 连接、服务/特征拓扑、CCCD 订阅、AFU 握手包与体重/阻抗通知的**全部原始十六进制字节**，支持一键导出 `.jsonl` 文件分享到电脑离线分析。Release 包不包含该能力。

---

## 🛠️ 技术栈

- **开发语言**：Kotlin
- **UI 框架**：Jetpack Compose
- **异步与流式编程**：Kotlin Coroutines & Flow (StateFlow / SharedFlow)
- **本地数据库**：Room Database (支持自动建表与实体映射转换)
- **导航组件**：Jetpack Navigation Compose (支持带参数的二级页面深度跳转)
- **蓝牙通信**：Android BLE Core APIs (基于 GATT 协议开发，高度定制化 PacketParser)
- **协议调试**：Debug 专用 BLE 协议完整抓包器（JSON Lines 导出 + FileProvider 文件分享）
- **构建环境**：JDK 21 / Gradle 9.5 / AGP 9.3 / compileSdk 36

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

### 2. 小米体脂秤 2 代 / 米家 — 免配对广播秤

小米体脂秤 2 代通过蓝牙广播 `0x181B` (Body Composition Service) 的 **13 字节 Service Data** 上报数据，**即踩即读，无需建立 GATT 连接**：

- **Byte 0-1**：Flags (小端序，Bit 0 = lbs，Bit 4 = 斤，Bit 5 = 测量完成，Bit 7 = 阻抗有效)
- **Byte 2-8**：年月日时分秒时间戳
- **Byte 9-10**：阻抗 (UINT16 小端序，Ω)
- **Byte 11-12**：体重 (UINT16 小端序，kg 精度 1/200)

---

## 📂 项目结构

```text
app/src/main/java/com/example/dianzicheng/
│
├── data/
│   ├── ble/              # 蓝牙连接中心 (BleScaleClient)
│   │                     #   ├─ MultiScalePacketParser：薄荷健康/沃莱 + 小米体脂秤 2 代协议分发
│   │                     #   ├─ AFUPacketParser：薄荷健康/沃莱 20 字节复合帧解析
│   │                     #   └─ BleProtocolRecorder：Debug 专用的蓝牙协议完整抓包器
│   ├── health/           # Health Connect 系统健康数据同步
│   ├── backup/           # WebDAV 云端备份
│   ├── local/            # Room 数据库配置 (ScaleDao, Entities, AppDatabase) 与实体转换器
│   └── repository/       # 统一数据仓库 (ScaleRepository) 负责数据匹配与持久化
│
├── domain/               # 业务实体 (Models)、支持型号枚举 (ScaleModel) 及 BIA 身体指标核心算法 (BodyAlgorithm)
│
└── ui/                   # 主页 (DashboardScreen)、历史 (HistoryScreen)、我的 (ProfileScreen)、
                          # 配对 (PairingScreen) 及对应的 ViewModel 和 Theme 主题包
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
5. `./gradlew assembleDebug` 打包 Debug APK；
6. 上传产物 Artifacts：`app-debug-apk`、`unit-test-report`；
7. **仅 `push` 到 `main`/`master` 时**，额外运行 `release-compile-check` 作业：
   `./gradlew assembleRelease -PbuildRelease=true`（独立并发组、不取消），
   确保 Release 专属代码路径不会一直隐藏到发版当天才暴露问题（该作业不上传产物）。

> **默认只构建 Debug 版本。** 原因有二：
> 1. 蓝牙协议完整抓包导出仅存在于 Debug 包（依赖 `BuildConfig.DEBUG`）；
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
3. 在页面底部 **Artifacts** 区域下载 `app-debug-apk`（可直接安装，且自带蓝牙协议抓包能力）；
4. 如需正式发布，请按上一节手动构建 Release 包并自行签名。

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
- 完整的十六进制原始报文（收包与握手发包）**只在 Debug 构建写入运行日志**；
  Release 版本日志里只保留「收到蓝牙数据 (N 字节)」这类摘要，不会把协议载荷带进可复制/可分享的日志里；
- 抓包缓冲与 `AppLogger` 的日志缓冲**相互独立**（`BleProtocolRecorder.MAX_ENTRIES = 6000`），不会被 1000 条运行日志滚动挤掉；
- 导出文件只写入 `cacheDir/ble-protocol/`，该路径是 `FileProvider` 唯一对外暴露的目录（`res/xml/file_paths.xml`），
  分享时通过 `FLAG_GRANT_READ_URI_PERMISSION` 临时授权，接收方无法访问 App 的其它文件；
- 需要说明的一点：本项目 Release 构建**未开启 R8 minify**（`isMinifyEnabled = false`），
  因此抓包器的方法体依然会打包进 Release APK，只是永远不会执行。
  若日后开启 minify，这些恒为 false 的分支会被自动裁剪掉。

---

## 🚀 快速上手（安装已构建的 APK）

1. **下载 APK**：按上一节从 GitHub Actions Artifacts 获取 APK；
2. **部署运行**：
   - 准备一台拥有蓝牙功能的 Android 真机（API 26+，开启手机蓝牙）；
   - 安装 APK 后打开应用，在「绑定体脂秤」页选择你的型号（薄荷健康/沃莱 或 小米体脂秤 2 代）并站上体脂秤完成配对；
   - 之后每次开启应用直接站上体脂秤即可开始称重与体脂分析。
