import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.2"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

// 构建信息（提交号、Actions 运行号）：检查更新时和 Actions 上最新的构建比较
fun genBuildMeta(dir: File, repoRoot: File): File {
    val sha = System.getenv("GITHUB_SHA") ?: runCatching { providers.exec { commandLine("git", "-C", repoRoot.path, "rev-parse", "HEAD") }.standardOutput.asText.get().trim() }.getOrDefault("")
    val time = runCatching { providers.exec { commandLine("git", "-C", repoRoot.path, "log", "-1", "--format=%ct", sha.ifEmpty { "HEAD" }) }.standardOutput.asText.get().trim().toLong() }.getOrDefault(0L)
    val f = File(dir, "cc/catsuki/nekoplay/BuildMeta.kt"); f.parentFile.mkdirs()
    val text = "package cc.catsuki.nekoplay\n\n/** 打包时生成，不要手改 */\nobject BuildMeta {\n    const val COMMIT = \"$sha\"\n    /** 提交时间（秒） */\n    const val COMMIT_TIME = ${time}L\n    const val RUN = \"${System.getenv("GITHUB_RUN_ID") ?: ""}\"\n}\n"
    if (!f.exists() || f.readText() != text) f.writeText(text)
    return dir
}
val buildMetaDir = genBuildMeta(layout.buildDirectory.dir("generated/buildmeta").get().asFile, rootDir.parentFile)

kotlin {
    jvmToolchain(21)
    // 和安卓版共用同一份代码
    sourceSets["main"].kotlin.srcDir("../shared/src")
    sourceSets["main"].kotlin.srcDir(buildMetaDir)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(compose.runtime)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20250517")
    implementation("com.google.zxing:core:3.5.3")
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("io.github.dokar3:quickjs-kt:1.0.18")
    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // 内嵌网页：Windows 用系统 WebView2；macOS / Linux 用 JavaFX WebView（只打进这两个系统的安装包）
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch") in listOf("aarch64", "arm64")
    val fx = when { "mac" in os -> if (arm) "mac-aarch64" else "mac"; "linux" in os -> if (arm) "linux-aarch64" else "linux"; else -> null }
    for (m in listOf("base", "graphics", "controls", "media", "web", "swing")) {
        if (fx != null) implementation("org.openjfx:javafx-$m:21.0.6:$fx")
        else compileOnly("org.openjfx:javafx-$m:21.0.6:win")   // Windows 只用来编译，运行时不会碰到
    }
}

// 版本号统一写在 shared/…/Version.kt
val appVersion = Regex("APP_VERSION = \"([^\"]+)\"").find(file("../shared/src/cc/catsuki/nekoplay/core/Version.kt").readText())!!.groupValues[1]
// Windows 安装包和 macOS 要求主版本号 ≥ 1：这两个平台用 1.次版本.修订（0.5.0 → 1.5.0），程序里显示的仍是真实版本号
val installerVersion = appVersion.split('.').let { (_, b, c) -> "1.$b.$c" }
compose.desktop {
    application {
        mainClass = "cc.catsuki.nekoplay.MainKt"
        // Windows / Linux 省内存：小堆 + Serial GC + 空闲时把堆还给系统 + 只用 C1 编译 + 小代码缓存 / 线程栈
        // macOS 保流畅：Retina 屏 + 120Hz 下每帧要画的像素多，小堆会频繁 GC 掉帧，只用 C1 编译出来的代码也慢；
        // 所以 macOS 堆给大一些、不频繁缩堆，并打开 C2 优化编译（多占几十 MB 内存）
        val mac = System.getProperty("os.name").lowercase().contains("mac")
        jvmArgs += if (mac) listOf("-Xms96m", "-Xmx384m", "-XX:+UseSerialGC", "-XX:ReservedCodeCacheSize=96m", "-XX:MaxMetaspaceSize=128m", "-Dskiko.vsync.enabled=true")
        else listOf("-Xms24m", "-Xmx180m", "-XX:+UseSerialGC", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=25",
            "-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=40m", "-XX:MaxMetaspaceSize=96m", "-Xss512k", "-XX:CICompilerCount=2",
            "-Dskiko.vsync.enabled=true")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb) // AppImage 和 dmg 共用输出目录会冲突；免安装版另外打 zip
            packageName = "NEKOPLAY"
            packageVersion = appVersion
            description = "NEKOPLAY music player" // 安装包元数据用英文：WiX 默认代码页处理不了中文
            vendor = "acnekot"
            modules("java.sql", "jdk.unsupported", "jdk.management", "jdk.jsobject", "jdk.xml.dom") // jdk.management：性能浮窗读 CPU
            // 播放内核 libmpv：打包时放进 resources/<系统>/（由 GitHub Actions 下载），装好就能直接播放
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            windows { packageVersion = installerVersion; menuGroup = "NEKOPLAY"; shortcut = true; upgradeUuid = "8f2a6c3e-5b1d-4e7a-9c40-3d2f1a6b7e91"; iconFile.set(project.file("icons/icon.ico")) }
            macOS { packageVersion = installerVersion; dmgPackageVersion = installerVersion; iconFile.set(project.file("icons/icon.icns")) }
            linux { iconFile.set(project.file("icons/icon.png")) }
        }
    }
}

// 单元测试：共用代码（shared/src）跟着桌面模块一起编译，所以测试放在 desktop/src/test。
// 用一个临时目录当用户目录，测试读写的配置不会碰到本机真正的 NEKOPLAY 数据
tasks.test {
    useJUnitPlatform()
    val home = layout.buildDirectory.dir("test-home").get().asFile
    doFirst { home.deleteRecursively(); home.mkdirs() }
    systemProperty("user.home", home.absolutePath)
    systemProperty("nekoplay.repo", rootDir.parentFile.absolutePath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// 代码风格检查：./gradlew ktlintCheck（规则在仓库根目录的 .editorconfig）。
// 共用代码跟着桌面模块编译，所以这里一起检查 shared/src；打包时生成的 BuildMeta 不查
ktlint {
    version.set("1.8.0")
    filter { exclude { it.file.path.contains("generated") } }
}
