// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
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