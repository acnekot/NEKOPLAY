plugins {
    kotlin("multiplatform") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.2"
}

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
    }
    sourceSets {
        val iosArm64Main by getting {
            // 和安卓 / 桌面共用同一份界面与逻辑代码
            kotlin.srcDir("../shared/src")
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.12.2")
                implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
                implementation("org.jetbrains.compose.ui:ui:1.12.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("io.coil-kt.coil3:coil-compose:3.3.0")
                implementation("io.coil-kt.coil3:coil-network-ktor3:3.3.0")
                implementation("io.ktor:ktor-client-darwin:3.3.1")
            }
        }
        all { languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi"); languageSettings.optIn("kotlinx.cinterop.BetaInteropApi") }
    }
}
