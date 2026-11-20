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

/** 程序入口：自己起 UIApplication，不需要 Swift / Xcode 工程 */
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
        Plat.impl = IosPlat()
        Boot.init()
        Perf.start()
        Store.refreshAll()
        val w = UIWindow(frame = UIScreen.mainScreen.bounds)
        w.rootViewController = ComposeUIViewController {
            NekoTheme {
                Box(Modifier.fillMaxSize()) {
                    Root()
                    // 左边缘右滑 = 返回（iOS 没有返回键）
                    var dx = 0f
                    Box(Modifier.align(Alignment.CenterStart).width(18.dp).fillMaxHeight().pointerInput(Unit) {
                        detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (dx > 80.dp.toPx()) IosPlat.back() }) { _, d -> dx += d }
                    })
                }
            }
        }
        win = w
        w.makeKeyAndVisible()
        UIApplication.sharedApplication.beginReceivingRemoteControlEvents()
        return true
    }

    override fun applicationDidEnterBackground(application: UIApplication) { Engine.savePosition() }
    override fun applicationWillTerminate(application: UIApplication) { Engine.savePosition() }
}
