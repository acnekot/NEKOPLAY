import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.2"
}

kotlin {
    jvmToolchain(21)
    // 和安卓版共用同一份代码
    sourceSets["main"].kotlin.srcDir("../shared/src")
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
}

compose.desktop {
    application {
        mainClass = "cc.catsuki.nekoplay.MainKt"
        jvmArgs += listOf("-Xmx256m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb) // AppImage 和 dmg 共用输出目录会冲突；免安装版另外打 zip
            packageName = "NEKOPLAY"
            packageVersion = "1.0.0" // Windows 安装包和 macOS 要求主版本号 ≥ 1
            description = "NEKOPLAY music player" // 安装包元数据用英文：WiX 默认代码页处理不了中文
            vendor = "acnekot"
            modules("java.sql", "jdk.unsupported")
            // 播放内核 libmpv：打包时放进 resources/<系统>/（由 GitHub Actions 下载），装好就能直接播放
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            windows { menuGroup = "NEKOPLAY"; shortcut = true; upgradeUuid = "8f2a6c3e-5b1d-4e7a-9c40-3d2f1a6b7e91" }
        }
    }
}
