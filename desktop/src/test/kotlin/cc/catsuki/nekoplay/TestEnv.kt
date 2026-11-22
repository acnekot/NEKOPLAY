package cc.catsuki.nekoplay

import cc.catsuki.nekoplay.Plat
import java.io.File

/** 测试环境：桌面平台实现 + 临时用户目录（build.gradle.kts 里把 user.home 指到 build/test-home） */
object TestEnv {
    /** 仓库根目录 */
    val repo: File by lazy { File(System.getProperty("nekoplay.repo") ?: "..").absoluteFile }

    private var ready = false
    @Synchronized fun init() {
        if (ready) return
        Plat.impl = DesktopPlat()
        ready = true
    }
}
