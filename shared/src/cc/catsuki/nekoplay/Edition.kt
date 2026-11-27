package cc.catsuki.nekoplay

/**
 * 发行渠道差异（打包时由 BuildMeta.CHANNEL 决定）：
 * - direct：官网 / GitHub 直装版，默认订阅官方插件源，应用内检查更新
 * - store：Microsoft Store 版，不默认订阅任何插件源（由用户自行添加），更新交给商店
 */
object Edition {
    /** 测试用：临时切换渠道 */
    @Volatile internal var override: String? = null
    val channel: String get() = override ?: BuildMeta.CHANNEL
    val store: Boolean get() = channel == "store"
    /** 应用内检查 / 下载更新：商店版由商店负责更新 */
    val selfUpdate: Boolean get() = !store
    /** 首次启动时默认订阅的插件源 */
    val defaultSubs: List<String> get() = if (store) emptyList() else listOf(PluginSubs.OFFICIAL)
    const val PRIVACY_URL = "https://nekoplay.catsuki.cc/privacy.html"
}
