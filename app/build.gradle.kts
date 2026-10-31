plugins {
    alias(libs.plugins.android.application)
    // Kotlin 由 AGP 9 built-in Kotlin 提供，版本经根 buildscript classpath 升至 2.4.20
    // （miuix 0.9.4 metadata 2.4 的要求）；compose 编译器插件保持与 KGP 同版本。
    alias(libs.plugins.kotlin.compose)
    // KSP 版本同样由根 buildscript classpath 提供（2.3.13），此处不写版本号
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.dianzicheng"
    // miuix 0.9.4 的 AAR 要求 compileSdk >= 37（checkAarMetadata 强校验）
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.dianzicheng"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ─────────────────────────────────────────────────────────────────────
        // 只编译 / 只打包 arm64-v8a
        //
        // 本应用的调试与分发一律使用 arm64 真机（BLE 无法在模拟器上真实验证）。
        // 注意：本项目依赖里的原生库来自 Compose 的传递依赖
        // `androidx.compose.ui:ui-graphics` → `androidx.graphics:graphics-path`，
        // 它自带 `libandroidx.graphics.path.so`，AAR 同时提供
        // armeabi-v7a / arm64-v8a / x86 / x86_64 四份 .so；不加限制四份会全部打进 APK。
        // 这里显式只保留 arm64-v8a，其余 ABI 的 .so 不再进入 APK。
        //
        // ⚠️ 副作用：x86 / x86_64 模拟器将缺少该 .so（Compose 路径绘制与矢量图标会崩溃）。
        //    若确需在模拟器上调试，请临时把对应 ABI 加回 `abiFilters`。
        // ─────────────────────────────────────────────────────────────────────
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            // Debug 是默认构建类型：BLE 协议完整抓包导出与运行日志查看器在此类型下真正生效。
            // 两个开关都通过 BuildConfig 下发，Release 恒为 false。
            buildConfigField("boolean", "BLE_PROTOCOL_CAPTURE", "true")
            buildConfigField("boolean", "RUNTIME_LOG_VIEWER", "true")
        }
        release {
            // 启用 R8 代码压缩与资源压缩：显著减小 APK 体积并优化运行时性能
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 显式关闭：Release 包不包含任何蓝牙协议抓包与导出能力
            buildConfigField("boolean", "BLE_PROTOCOL_CAPTURE", "false")
            // 显式关闭：Release 包不提供运行日志查看器（无入口、无导出）
            buildConfigField("boolean", "RUNTIME_LOG_VIEWER", "false")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // AGP 8+ 起 buildConfig 默认关闭，必须显式开启才能生成 BuildConfig.DEBUG / 自定义字段
        buildConfig = true
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 默认只构建 Debug 版本
//
// 本项目约定：日常开发、CI 与分发一律使用 Debug 包（蓝牙协议完整抓包导出依赖 Debug）。
// 实现方式是在 Gradle 配置阶段直接禁用 release 构建变体，因此：
//   - `./gradlew build` / `./gradlew assemble` / `./gradlew check` 只会处理 Debug 变体，
//     不会隐式编译 Release；
//   - 需要正式包时必须显式放行：`./gradlew assembleRelease -PbuildRelease=true`
//     （GitHub Actions 通过手动触发并勾选 build_release 传入该参数）。
//
// 说明：`VariantBuilder.enable` 继承自 AGP 的 `com.android.build.api.variant.ComponentBuilder`
// （getEnable/setEnable），是官方推荐替代 `variantFilter`（AGP 7.0 弃用、8.0 移除）的写法。
//
// 另外：`./gradlew`（不带任务名）的默认任务声明在**根项目** build.gradle.kts 中
// （Gradle 只从根项目解析 default tasks，写在子项目里对根目录的调用无效）。
// ─────────────────────────────────────────────────────────────────────────────
val buildReleaseRequested = (findProperty("buildRelease") as? String)?.toBoolean() ?: false

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        variantBuilder.enable = buildReleaseRequested
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    // Miuix（HyperOS 设计）UI 组件库
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // 单页面 + 弹层架构：不再引入页面导航栈（原 androidx.navigation 已移除）
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.health.connect)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
