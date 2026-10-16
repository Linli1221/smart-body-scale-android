# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# 当前 release 构建未开启代码压缩（isMinifyEnabled = false），此文件为占位与后续扩展使用。
# 注意：Room / Compose / Kotlin 协程的 keep 规则由 AGP 与各库自带 consumer rules 提供。

# 保留 Room 实体与 DAO 的泛型签名，避免后续开启 R8 时反射失败
-keepattributes Signature
-keepattributes *Annotation*
