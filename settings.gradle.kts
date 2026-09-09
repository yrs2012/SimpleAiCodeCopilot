rootProject.name = "AiCode"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// 允许 Gradle 自动下载匹配的 JDK（foojay 解析器）
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
