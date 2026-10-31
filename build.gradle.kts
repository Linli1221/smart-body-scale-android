// Top-level build file where you can add configuration options common to all sub-projects/modules.

// ─────────────────────────────────────────────────────────────────────────────
// Kotlin/KSP 版本注入（AGP 9 内置 Kotlin）
//
// miuix 0.9.4 由 Kotlin 2.4.20 构建（metadata 2.4），需要 ≥2.4 的编译器读取；
// AGP 9 的 built-in Kotlin 默认带 KGP 2.2.10，且禁止应用独立的
// org.jetbrains.kotlin.android 插件。官方升级方式（kotl.in/gradle/agp-built-in-kotlin）
// 是在顶层 buildscript classpath 声明更高版本的 KGP / KSP。
// ─────────────────────────────────────────────────────────────────────────────
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.13")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// ─────────────────────────────────────────────────────────────────────────────
// 默认只构建 Debug 版本
//
// 直接运行 `./gradlew`（不带任何任务名）时，Gradle 会执行这里声明的默认任务：
//   :app:testDebugUnitTest + :app:assembleDebug
// 因此默认行为与「只构建 Debug」的约定一致。
//
// ⚠️ 必须写在**根项目**：Gradle 只从根项目解析 default tasks，
//    写在 app/build.gradle.kts（子项目）里对根目录的调用无效。
//
// 另外 app/build.gradle.kts 已在配置阶段禁用 release 变体，
// 所以 `./gradlew build` / `./gradlew assemble` 这类聚合任务同样只会处理 Debug。
// ─────────────────────────────────────────────────────────────────────────────
defaultTasks(":app:testDebugUnitTest", ":app:assembleDebug")