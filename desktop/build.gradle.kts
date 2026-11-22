import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.2"
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
    // 内嵌网页：Windows 用系统 WebView2；Linux 用 JavaFX WebView；macOS 不打包 JavaFX（避开与 Skiko Metal 冲突且省 42MB）
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch") in listOf("aarch64", "arm64")
    val fx = if ("linux" in os) (if (arm) "linux-aarch64" else "linux") else null
    for (m in listOf("base", "graphics", "controls", "media", "web", "swing")) {
        if (fx != null) implementation("org.openjfx:javafx-$m:21.0.6:$fx")
        else compileOnly("org.openjfx:javafx-$m:21.0.6:win")   // Windows / macOS 只用于编译，不打进安装包
    }
}

// 版本号统一写在 shared/…/Version.kt
val appVersion = Regex("APP_VERSION = \"([^\"]+)\"").find(file("../shared/src/cc/catsuki/nekoplay/Version.kt").readText())!!.groupValues[1]
// Windows 安装包和 macOS 要求主版本号 ≥ 1：这两个平台用 1.次版本.修订（0.5.0 → 1.5.0），程序里显示的仍是真实版本号
val installerVersion = appVersion.split('.').let { (_, b, c) -> "1.$b.$c" }
compose.desktop {
    application {
        mainClass = "cc.catsuki.nekoplay.MainKt"
        // Windows / Linux 省内存：小堆 + Serial GC + 空闲时把堆还给系统 + 只用 C1 编译 + 小代码缓存 / 线程栈
        // macOS 保流畅（120fps 基准）：分代 ZGC（亚毫秒停顿）+ C2 编译 + Metal 三缓冲 + 128MB GPU 纹理缓存 + 取消立即重绘 VSync 阻塞
        val mac = System.getProperty("os.name").lowercase().contains("mac")
        jvmArgs += if (mac) listOf("-Xms32m", "-Xmx256m", "-XX:+UseSerialGC",
            "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=25",
            "-XX:ReservedCodeCacheSize=64m", "-XX:MaxMetaspaceSize=96m",
            "-Dsun.awt.noerasebackground=true",
            "-Dskiko.vsync.enabled=true", "-Dskiko.buffering=TRIPLE",
            "-Dskiko.rendering.macos.waitForPreviousFrameVsyncOnRedrawImmediately=false",
            "-Dskiko.gpu.resourceCacheLimit=67108864")
        else listOf("-Xms24m", "-Xmx256m", "-XX:+UseSerialGC", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=25",
            "-XX:ReservedCodeCacheSize=48m", "-XX:MaxMetaspaceSize=96m", "-Xss512k",
            "-Dskiko.vsync.enabled=true")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb) // AppImage 和 dmg 共用输出目录会冲突；免安装版另外打 zip
            packageName = "NEKOPLAY"
            packageVersion = appVersion
            description = "NEKOPLAY music player" // 安装包元数据用英文：WiX 默认代码页处理不了中文
            vendor = "acnekot"
            modules("jdk.unsupported", "jdk.management", "jdk.jsobject", "jdk.xml.dom") // jdk.management：性能浮窗读 CPU
            // 播放内核 libmpv：打包时放进 resources/<系统>/（由 GitHub Actions 下载），装好就能直接播放
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            windows { packageVersion = installerVersion; menuGroup = "NEKOPLAY"; shortcut = true; upgradeUuid = "8f2a6c3e-5b1d-4e7a-9c40-3d2f1a6b7e91"; iconFile.set(project.file("icons/icon.ico")) }
            macOS { packageVersion = installerVersion; dmgPackageVersion = installerVersion; iconFile.set(project.file("icons/icon.icns")) }
            linux { iconFile.set(project.file("icons/icon.png")) }
        }
    }
}

// 0.6.0 架构守门测试：校验单向分层（core / data / player 禁止反向依赖 ui）与单文件行数上限（≤ 400）
tasks.register("archCheck") {
    group = "verification"
    description = "Verify layer boundaries (no reverse UI deps in core/data/player) and <=400 lines per shared file."
    doLast {
        val sharedRoot = rootDir.parentFile.resolve("shared/src/cc/catsuki/nekoplay")
        val errors = mutableListOf<String>()
        sharedRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val lines = f.readLines()
            if (lines.size > 400) errors += "${f.relativeTo(sharedRoot)}: ${lines.size} lines (> 400)"
        }
        val nonUiFiles = setOf(
            "Version.kt", "Perf.kt", "Hash.kt", "Debug.kt", "Features.kt", "AppGraph.kt",
            "Plugin.kt", "PluginSubs.kt", "Sources.kt", "Ncm.kt", "Store.kt", "LocalLib.kt", "LocalMusic.kt",
            "Dav.kt", "Download.kt", "Backup.kt", "Updater.kt", "Stats.kt",
            "Engine.kt", "Fx.kt", "Lrc.kt", "Sleep.kt",
        )
        val uiSymRegex = Regex("""\b(UiPref|ThemePref|Look|Monet|LocalNav|SheetSpec|SheetItem|SwitchRow|NavRow|SetLine)\b""")
        for (name in nonUiFiles) {
            val f = sharedRoot.resolve(name)
            if (!f.exists()) continue
            val text = f.readText()
            text.lines().forEachIndexed { idx, line ->
                if (line.startsWith("import cc.catsuki.nekoplay.ui.") ||
                    line.startsWith("import androidx.compose.ui.") ||
                    line.startsWith("import androidx.compose.foundation.") ||
                    line.startsWith("import androidx.compose.material")) {
                    errors += "$name:${idx + 1} forbidden UI import: $line"
                }
            }
            if ("@Composable" in text) errors += "$name contains @Composable"
            uiSymRegex.findAll(text).map { it.value }.distinct().forEach { sym ->
                errors += "$name references UI symbol '$sym'"
            }
        }
        if (errors.isNotEmpty()) {
            throw GradleException("Architecture check failed (${errors.size} violations):\n" + errors.joinToString("\n") { "  - $it" })
        }
        println("archCheck PASSED: 0 reverse UI dependencies, all shared .kt files <= 400 lines.")
    }
}
