plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
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
val buildMetaDir = genBuildMeta(layout.buildDirectory.dir("generated/buildmeta").get().asFile, rootDir)
// 版本号统一写在 shared/…/Version.kt
val appVersion = Regex("APP_VERSION = \"([^\"]+)\"").find(rootProject.file("shared/src/cc/catsuki/nekoplay/Version.kt").readText())!!.groupValues[1]
android {
    namespace = "cc.catsuki.nekoplay"
    compileSdk = 37
    defaultConfig {
        applicationId = "cc.catsuki.nekoplay.native"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersion.split('.').map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }   // 0.5.0 → 500
        versionName = appVersion
    }
    // 正式签名：环境变量里有密钥库就用（GitHub Actions 从 Secrets 解出来），没有就退回 debug 签名
    val ks = System.getenv("NEKOPLAY_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    if (ks != null) signingConfigs.create("release") {
        storeFile = ks; storePassword = System.getenv("NEKOPLAY_KEYSTORE_PASS")
        keyAlias = System.getenv("NEKOPLAY_KEY_ALIAS"); keyPassword = System.getenv("NEKOPLAY_KEY_PASS")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (ks != null) "release" else "debug")
        }
    }
    buildFeatures { compose = true }
    // 共用代码（界面、接口、播放队列）在 ../shared，桌面版也用同一份
    sourceSets["main"].kotlin.srcDir("../shared/src")
    sourceSets["main"].kotlin.srcDir(buildMetaDir)
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a"); isUniversalApk = false } }
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("io.coil-kt.coil3:coil-gif:3.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.github.dokar3:quickjs-kt:1.0.18")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
