package cc.catsuki.nekoplay

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.*
import platform.Foundation.NSStringFromClass
import platform.UIKit.*
import platform.darwin.NSObject

/** 程序入口（GitHub Actions 打的 IPA 用）：自己起 UIApplication，不需要 Swift / Xcode 工程 */
fun main(args: Array<String>) {
    memScoped {
        val argv = (listOf("NEKOPLAY") + args).map { it.cstr.ptr }.toCValues()
        autoreleasepool { UIApplicationMain(args.size + 1, argv, null, NSStringFromClass(AppDelegate)) }
    }
}

class AppDelegate @OverrideInit constructor() : UIResponder(), UIApplicationDelegateProtocol {
    companion object : UIResponderMeta(), UIApplicationDelegateProtocolMeta

    private var win: UIWindow? = null
    override fun window(): UIWindow? = win
    override fun setWindow(window: UIWindow?) { win = window }

    override fun application(application: UIApplication, didFinishLaunchingWithOptions: Map<Any?, *>?): Boolean {
        val w = UIWindow(frame = UIScreen.mainScreen.bounds)
        w.rootViewController = MainViewController()
        win = w
        w.makeKeyAndVisible()
        return true
    }

    override fun application(app: UIApplication, openURL: platform.Foundation.NSURL, options: Map<Any?, *>): Boolean { onOpenUrl(openURL.absoluteString ?: ""); return true }
    override fun applicationDidEnterBackground(application: UIApplication) = onBackground()
    override fun applicationWillTerminate(application: UIApplication) = onBackground()
}

private var booted = false

/** 整个界面（Xcode 工程里的 Swift 入口也调用这个） */
fun MainViewController(): UIViewController {
    if (!booted) {
        booted = true
        Plat.impl = IosPlat()
        // 老设备第一次启动默认开「省内存模式」（图片缓存小、逐字高亮约 20 帧），设置里可以关
        if (IosPlat.lowEnd) Plat.prefs("ui").let { if (!it.all().containsKey("lowMem")) it.edit().putBoolean("lowMem", true).apply() }
        Boot.init()
        Perf.start()
        Store.refreshAll()
        UIApplication.sharedApplication.beginReceivingRemoteControlEvents()
    }
    return ComposeUIViewController {
        NekoTheme {
            Box(Modifier.fillMaxSize()) {
                Root()
                // 左边缘右滑 = 返回（iOS 没有返回键）
                var dx = 0f
                Box(Modifier.align(Alignment.CenterStart).width(22.dp).fillMaxHeight().pointerInput(Unit) {
                    detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (dx > 56.dp.toPx()) IosPlat.back() }) { _, d -> dx += d }
                })
            }
        }
    }
}

/** 退到后台 / 退出时保存播放进度 */
fun onBackground() { if (booted) Engine.savePosition() }

/** QQ 授权后跳回来的 nekoplay://…（Xcode 工程的 Swift 入口也调用这个） */
fun onOpenUrl(u: String) { if (u.startsWith("nekoplay:") || "ptlogin2.qq.com/jump" in u) DeepLink.url.value = u }
