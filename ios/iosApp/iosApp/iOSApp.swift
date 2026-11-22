import UIKit
import CarPlay
import NekoApp

// Xcode 工程入口：界面和逻辑都在 Kotlin 编译出的 NekoApp 框架里
@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool { true }
    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession, options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        if connectingSceneSession.role == .carTemplateApplication {
            let cfg = UISceneConfiguration(name: "CarPlay Configuration", sessionRole: connectingSceneSession.role)
            cfg.sceneClass = CPTemplateApplicationScene.self
            cfg.delegateClass = CarPlaySceneDelegate.self
            return cfg
        }
        let cfg = UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
        cfg.delegateClass = SceneDelegate.self
        return cfg
    }
    func applicationWillTerminate(_ application: UIApplication) { MainKt.onBackground() }
}

class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard session.role == .windowApplication, let ws = scene as? UIWindowScene else { return }
        let w = UIWindow(windowScene: ws)
        w.rootViewController = MainKt.MainViewController()
        w.makeKeyAndVisible()
        window = w
        if let u = connectionOptions.urlContexts.first?.url { MainKt.onOpenUrl(u: u.absoluteString) }
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        if let u = URLContexts.first?.url { MainKt.onOpenUrl(u: u.absoluteString) }
    }

    func sceneDidEnterBackground(_ scene: UIScene) { MainKt.onBackground() }
}
