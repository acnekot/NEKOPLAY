package cc.catsuki.nekoplay

/**
 * 播放器可注入配置（由 UI 层在启动或偏好变更时写入，player 层只读此配置，不反向依赖界面偏好）。
 */
data class PlayerConfig(
    val preferredHomePlugin: () -> String = { "" },
)

/**
 * 应用核心服务图（手写轻量依赖容器，零三方 DI 框架，Android / Desktop / iOS 三端共用）。
 * 将原本分散的全局有状态服务收口到单一入口，便于启动编排与单元测试替换。
 */
class AppGraph(
    val ncm: Ncm = Ncm,
    val plugins: Plugins = Plugins,
    val store: Store = Store,
    val localLib: LocalLib = LocalLib,
    val dav: DavMusic = DavMusic,
    val downloads: Downloads = Downloads,
    val engine: Engine = Engine,
    val fx: FxStore = FxStore,
    val stats: Stats = Stats,
    val localMusic: LocalMusic = LocalMusic,
    val backup: Backup = Backup,
    val updater: Updater = Updater,
    var playerConfig: PlayerConfig = PlayerConfig(),
) {
    /** 快速首屏初始化：只加载界面渲染必需的轻量偏好、音源配置与本地队列 */
    fun initFast(onReloadPrefs: () -> Unit) {
        plugins.preferredHomeId = playerConfig.preferredHomePlugin
        ncm.init()
        store.init()
        engine.init()
        onReloadPrefs()
    }

    /** 延后后台初始化：首帧画完后再加载插件、WebDAV、更新检查、本地库与统计 */
    fun initDeferred() {
        LegacyLogin.migrate()
        plugins.load()
        localLib.load()
        dav.load()
        updater.load()
        store.reloadLiked()
        fx.init()
        downloads.init()
        localMusic.load()
        stats.load()
        backup.load()
    }

    companion object {
        @kotlin.concurrent.Volatile var current: AppGraph = AppGraph()
    }
}
