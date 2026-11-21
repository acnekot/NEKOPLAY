import UIKit
import NekoApp

// Xcode 工程入口：界面和逻辑都在 Kotlin 编译出的 NekoApp 框架里
@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let w = UIWindow(frame: UIScreen.main.bounds)
        w.rootViewController = MainKt.MainViewController()
        w.makeKeyAndVisible()
        window = w
        return true
    }

    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool { MainKt.onOpenUrl(u: url.absoluteString); return true }
    func applicationDidEnterBackground(_ application: UIApplication) { MainKt.onBackground() }
    func applicationWillTerminate(_ application: UIApplication) { MainKt.onBackground() }
}
