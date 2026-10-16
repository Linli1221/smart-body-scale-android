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

---

## 🛠️ 技术栈

- **开发语言**：Kotlin
- **UI 框架**：Jetpack Compose
- **异步与流式编程**：Kotlin Coroutines & Flow (StateFlow / SharedFlow)
- **本地数据库**：Room Database (支持自动建表与实体映射转换)
- **导航组件**：Jetpack Navigation Compose (支持带参数的二级页面深度跳转)
- **蓝牙通信**：Android BLE Core APIs (基于 GATT 协议开发，高度定制化 PacketParser)
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
│   │                     #   └─ AFUPacketParser：薄荷健康/沃莱 20 字节复合帧解析
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
| `push` 到 `main` / `master` | 自动构建 |
| 对 `main` / `master` 发起 `pull_request` | 自动构建 |
| `workflow_dispatch` | 在 Actions 页面手动点击 **Run workflow** |

### 云端执行的步骤

1. `actions/checkout` 拉取代码；
2. `actions/setup-java` 安装 **JDK 21**（与 `gradle/gradle-daemon-jvm.properties` 的 `toolchainVersion=21` 一致）；
3. `android-actions/setup-android` 安装 Android SDK（`platform-tools`、`platforms;android-36`）；
4. `./gradlew testDebugUnitTest` 运行单元测试；
5. `./gradlew assembleDebug` 打包 Debug APK；
6. `./gradlew assembleRelease` 打包未签名 Release APK；
7. 上传产物 Artifacts：`app-debug-apk`、`app-release-apk-unsigned`、`unit-test-report`。

### 获取 APK

1. 打开本仓库的 **Actions** 页面；
2. 进入最近一次成功的 **Android CI** 运行；
3. 在页面底部 **Artifacts** 区域下载 `app-debug-apk`（可直接安装调试）或 `app-release-apk-unsigned`（如需正式发布请自行签名）。

---

## 🚀 快速上手（安装已构建的 APK）

1. **下载 APK**：按上一节从 GitHub Actions Artifacts 获取 APK；
2. **部署运行**：
   - 准备一台拥有蓝牙功能的 Android 真机（API 26+，开启手机蓝牙）；
   - 安装 APK 后打开应用，在「绑定体脂秤」页选择你的型号（薄荷健康/沃莱 或 小米体脂秤 2 代）并站上体脂秤完成配对；
   - 之后每次开启应用直接站上体脂秤即可开始称重与体脂分析。
