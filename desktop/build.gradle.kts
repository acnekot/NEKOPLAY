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
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
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
        // 全桌面端统一低停顿 G1GC + 禁止 AWT 擦白底 + Skiko 三缓冲与 64MB 纹理缓存，彻底消除 Windows 拖窗闪白与 GC 掉帧
        val mac = System.getProperty("os.name").lowercase().contains("mac")
        jvmArgs += listOf(
            "-Xms48m", "-Xmx384m",
            "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=10",
            "-XX:ReservedCodeCacheSize=64m", "-XX:MaxMetaspaceSize=128m",
            "-Dsun.awt.noerasebackground=true",
            "-Djna.encoding=UTF-8",
            "-Dskiko.vsync.enabled=true", "-Dskiko.buffering=TRIPLE",
            "-Dskiko.gpu.resourceCacheLimit=67108864",
        ) + if (mac) listOf("-Dskiko.rendering.macos.waitForPreviousFrameVsyncOnRedrawImmediately=false") else emptyList()
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb) // AppImage 和 dmg 共用输出目录会冲突；免安装版另外打 zip
            packageName = "NEKOPLAY"
            packageVersion = appVersion
            description = "NEKOPLAY music player" // 安装包元数据用英文：WiX 默认代码页处理不了中文
            vendor = "acnekot"
            modules("jdk.unsupported", "jdk.management", "jdk.jsobject", "jdk.xml.dom") // jdk.management：性能浮窗读 CPU
            // 播放内核 libmpv：打包时放进 resources/<系统>/（由 GitHub Actions 下载），装好就能直接播放
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            windows {
                packageVersion = installerVersion
                menu = true
                menuGroup = "NEKOPLAY"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "8f2a6c3e-5b1d-4e7a-9c40-3d2f1a6b7e91"
                iconFile.set(project.file("icons/icon.ico"))
            }
            macOS {
                packageVersion = installerVersion
                dmgPackageVersion = installerVersion
                bundleID = "cc.catsuki.nekoplay"
                iconFile.set(project.file("icons/icon.icns"))
            }
            linux { iconFile.set(project.file("icons/icon.png")) }
        }
    }
}

// 0.6.0 架构守门测试：校验单向分层（core / data / player 禁止反向依赖 ui）与单文件行数上限（≤ 400）
val ARCH_MAX_COL = 200
val archBaselineFile = rootDir.parentFile.resolve("shared/arch-baseline.txt")
val archCjkText = Regex("""\bT\("[^"]*[\u4e00-\u9fff]""")
/** 每个 shared 文件：[超长行数, 未翻译 T("中文") 数] */
fun archStyleCounts(root: File): Map<String, List<Int>> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { f ->
    val lines = f.readLines()
    f.relativeTo(root).invariantSeparatorsPath to listOf(lines.count { it.length > ARCH_MAX_COL }, lines.sumOf { l -> archCjkText.findAll(l).count() })
}.filterValues { it[0] > 0 || it[1] > 0 }.toSortedMap()

tasks.register("archBaseline") {
    group = "verification"
    description = "Rewrite shared/arch-baseline.txt with the current long-line / untranslated-text counts."
    doLast {
        val root = rootDir.parentFile.resolve("shared/src/cc/catsuki/nekoplay")
        val c = archStyleCounts(root)
        archBaselineFile.writeText("# archCheck 规范基线：文件=超长行(>$ARCH_MAX_COL 字符)数,未翻译 T(\"中文\")数 —— 只允许减少，改善后运行 ./gradlew archBaseline 更新\n" +
            c.entries.joinToString("\n") { "${it.key}=${it.value[0]},${it.value[1]}" } + "\n")
        println("archBaseline: ${c.size} files, ${c.values.sumOf { it[0] }} long lines, ${c.values.sumOf { it[1] }} untranslated literals")
    }
}

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
            "Plugin.kt", "Plugins.kt", "PluginSubs.kt", "Sources.kt", "Ncm.kt", "Store.kt", "LocalLib.kt", "LocalMusic.kt",
            "Dav.kt", "Download.kt", "Backup.kt", "Updater.kt", "Stats.kt",
            "Engine.kt", "EngineQueue.kt", "Fx.kt", "Lrc.kt", "Sleep.kt",
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
        // 代码规范棘轮：超长行（> 200 字符）与 T("中文") 未走 tr() 的数量，每个文件不得超过基线；存量只减不增
        val baseline = archBaselineFile.takeIf { it.exists() }?.readLines()?.filter { "=" in it && !it.startsWith("#") }
            ?.associate { l -> l.substringBefore('=') to l.substringAfter('=').split(',').map { it.trim().toInt() } } ?: emptyMap()
        val now = archStyleCounts(sharedRoot)
        var shrunk = 0
        now.forEach { (file, cnt) ->
            val base = baseline[file] ?: listOf(0, 0)
            if (cnt[0] > base[0]) errors += "$file: ${cnt[0]} lines > $ARCH_MAX_COL chars (baseline ${base[0]}); wrap new long lines"
            if (cnt[1] > base[1]) errors += "$file: ${cnt[1]} untranslated T(\"中文\") literals (baseline ${base[1]}); wrap new text with tr()"
            if (cnt[0] < base[0] || cnt[1] < base[1]) shrunk++
        }
        if (shrunk > 0) println("archCheck: $shrunk file(s) improved below baseline — run ./gradlew archBaseline to lock in the gains.")
        if (errors.isNotEmpty()) {
            throw GradleException("Architecture check failed (${errors.size} violations):\n" + errors.joinToString("\n") { "  - $it" })
        }
        println("archCheck PASSED: 0 reverse UI dependencies, all shared .kt files <= 400 lines, style counts within baseline.")
    }
}

// 单元测试：用临时目录作为 user.home，避免影响本机 NEKOPLAY 数据
tasks.test {
    useJUnitPlatform()
    val home = layout.buildDirectory.dir("test-home").get().asFile
    doFirst { home.deleteRecursively(); home.mkdirs() }
    systemProperty("user.home", home.absolutePath)
    systemProperty("nekoplay.repo", rootDir.parentFile.absolutePath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
