# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# Release 构建已开启 R8（isMinifyEnabled = true / isShrinkResources = true）。
# 注意：Room / Compose / Kotlin 协程 / OkHttp / DataStore / HealthConnect 的 keep 规则
# 由 AGP 与各库自带 consumer rules 提供，此处只做必要的补充与 dontwarn 兜底。

# 保留 Room 实体与 DAO 的泛型签名，避免 R8 压缩后反射失败
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions, InnerClasses, EnclosingMethod

# OkHttp / Okio（官方推荐规则，consumer rules 之外的双保险）
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin 协程调试代理（发布包不需要）
-dontwarn kotlinx.coroutines.debug.**

# Room / DataStore / HealthConnect / BLE 等均通过官方 consumer ProGuard rules 或
# 注解处理器生成的代码工作，无需额外 keep；如运行时出现反射类缺失再按需补充。
