import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.aicode"
version = providers.gradleProperty("pluginVersion").getOrElse("1.0.0")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // org.json 平台核心未内置（仅 design-tools 插件携带），显式声明后随插件 zip 一起打包
    implementation("org.json:json:20240303")

    intellijPlatform {
        // 默认针对 Android Studio Otter 3 Feature Drop (2025.2.3) 构建。
        // 此处填 AS 的“发行版本号”（如 2025.2.3.9），可从
        // https://developer.android.com/studio/releases 对应版本页查到；
        // 注意不是 AI- 开头的完整 build 号，也不是 platformBuild(252.x)。
        //
        // 开发调试：设置 -PplatformLocalPath=/path/to/ide 可改为使用本机已安装的 IDE
        // （目录需包含 bin/ 与 lib/，例如 Android Studio 或 PyCharm 安装目录）。
        if (providers.gradleProperty("platformLocalPath").isPresent) {
            local(providers.gradleProperty("platformLocalPath").get().let { file(it) })
        } else {
            androidStudio("2025.2.3.9")
        }

        testFramework(TestFrameworkType.Platform)
    }

    // 测试编译需要平台自带的 testFramework.jar（LightPlatformTestCase 等基类）。
    // -PplatformLocalPath 模式下该 jar 在 <ide>/lib/testFramework.jar；
    // 走远程 SDK（androidStudio(...)）时该 jar 已随 test-framework 依赖提供，此处文件不存在则跳过。
    val localIdePath = providers.gradleProperty("platformLocalPath")
    val localTestFramework =
        if (localIdePath.isPresent) {
            file(localIdePath.get()).resolve("lib/testFramework.jar")
        } else {
            null
        }
    localTestFramework?.takeIf { it.exists() }?.let { testImplementation(files(it)) }

    // JUnit 3/4 风格平台测试基类（LightCodeInsightFixtureTestCase 等）需要 junit4
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    // 不强制 toolchain：使用运行 Gradle 的 JDK（需 17+，如 IDE 自带 JBR），
    // 字节码目标固定为 17（Android Studio 242+ 的运行时 JBR 为 17）。
    // 这样无需从 foojay 下载 JDK，用户环境 JDK 17/21 均可直接构建。
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"   // Android Studio Hedgehog (2024.2) 起
            untilBuild = "261.*" // 兼容到 Quail (2026.1)
        }
    }

    // 不构建 searchable options（会启动沙箱 IDE，纯后台构建时跳过）
    buildSearchableOptions = false

}

// 平台测试框架（LightPlatformTestCase 等）会通过 JarRepository 解析测试类路径上的
// Maven 依赖（如 org.jetbrains:annotations），本地仓库默认为 ~/.m2/repository。
// 若该路径实际不可写（无头/沙箱环境，或 ~/.m2 不存在且父目录受限），测试项目初始化
// 会抛 "No roots for ..."。此时把测试 JVM 的 user.home 指到项目内可写目录。
// 正常开发机 ~/.m2 可写则不受影响。
// 注意：必须在 tasks{} 之前求值（java 插件在脚本求值期间即创建 test 任务）。
val aicodeTestHomeSystemProperties: Map<String, Any> = run {
    val defaultM2 = File(System.getProperty("user.home"), ".m2")
    val m2Usable = if (defaultM2.isDirectory) defaultM2.canWrite()
    else runCatching {
        val probe = File(defaultM2, ".aicode-probe-" + System.nanoTime())
        val ok = probe.mkdirs()
        probe.deleteRecursively()
        ok
    }.getOrDefault(false)
    if (m2Usable) {
        emptyMap()
    } else {
        val testHome = File(projectDir, ".tools/test-home")
        File(testHome, ".m2").mkdirs()
        mapOf("user.home" to testHome.absolutePath)
    }
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
    withType<KotlinCompile> {
        compilerOptions {
            // 如需实现含 default 方法的 Java 接口，可加 "-jvm-default=enable"
        }
    }

    // 打包产物
    buildPlugin {
        // 默认输出到 build/distributions
    }

    // 若默认 ~/.m2 不可用，覆写测试 JVM 的 user.home（见上方 val 注释）
    withType<Test> {
        systemProperties.putAll(aicodeTestHomeSystemProperties)
    }
}
