plugins {
    kotlin("multiplatform") version "2.4.20"
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
    // 只出真机（arm64），最低 iOS 14；直接链接成可执行文件，不需要 Xcode 工程 / Swift
    iosArm64 {
        // 官方工具链默认最低 iOS 15，这里压到 14
        compilerOptions { freeCompilerArgs.add("-Xoverride-konan-properties=minVersion.ios=14.0") }
        binaries.executable {
            entryPoint = "cc.catsuki.nekoplay.main"
            baseName = "NEKOPLAY"
            freeCompilerArgs += listOf("-Xbinary=bundleId=cc.catsuki.nekoplay", "-Xoverride-konan-properties=minVersion.ios=14.0")
        }
        // Xcode 工程（iosApp/）用的静态框架：Swift 入口调用 MainViewController()
        binaries.framework {
            baseName = "NekoApp"
            isStatic = true
            freeCompilerArgs += listOf("-Xbinary=bundleId=cc.catsuki.nekoplay.kit", "-Xoverride-konan-properties=minVersion.ios=14.0")
        }
    }
    sourceSets {
        val iosArm64Main by getting {
            // 和安卓 / 桌面共用同一份界面与逻辑代码
            kotlin.srcDir("../shared/src")
            kotlin.srcDir(buildMetaDir)
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.12.2")
                implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
                implementation("org.jetbrains.compose.ui:ui:1.12.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("io.coil-kt.coil3:coil-compose:3.3.0")
                implementation("io.coil-kt.coil3:coil-network-ktor3:3.3.0")
                implementation("io.ktor:ktor-client-darwin:3.3.1")
                implementation("io.github.dokar3:quickjs-kt:1.0.18")
            }
        }
        all { languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi"); languageSettings.optIn("kotlinx.cinterop.BetaInteropApi") }
    }
}
