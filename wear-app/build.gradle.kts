// ==================== 顶层构建脚本（Gradle 8.7 / JDK 17 兼容） ====================
// 注意：由于本机是 Java 26，AGP 8.5 只支持 Java 17-22，
// 所以我们使用 gradle.properties 指定 JDK 工具链下载 JDK 21（兼容 AGP 8.5），
// Gradle 8.14.5 自身可支持 Java 24 运行时，但 daemon 会使用 JDK 21 工具链执行编译。
// AGP 8.5.2 + Kotlin 1.9.24 是成熟稳定的组合
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.25" apply false
}
