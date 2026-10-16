plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.dianzicheng"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.dianzicheng"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Debug 是默认构建类型：BLE 协议完整抓包导出在此类型下真正生效。
            // 抓包开关通过 BuildConfig.BLE_PROTOCOL_CAPTURE 下发，Release 恒为 false。
            buildConfigField("boolean", "BLE_PROTOCOL_CAPTURE", "true")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 显式关闭：Release 包不包含任何蓝牙协议抓包与导出能力
            buildConfigField("boolean", "BLE_PROTOCOL_CAPTURE", "false")
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
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
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
