package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 检查更新：
 * - 正式版（默认）：GitHub Release 的最新正式版，版本号比本机新就提示，下载对应平台的安装包
 * - 测试版 nightly：GitHub Pre-release 预发布包，直接下载对应平台的安装包（不再经由 Actions / nightly.link）
 */
object Updater {
    private const val REPO = "acnekot/NEKOPLAY"

    /** found：有更新；url：下载地址；page：网页（看说明 / 手动下载） */
    class Info(val found: Boolean, val title: String, val notes: String, val url: String?, val page: String)

    private val prefs by lazy { Plat.prefs("update") }
    var nightly by mutableStateOf(false); private set
    var autoOn by mutableStateOf(true); private set
    var skipped by mutableStateOf(""); private set
    var checking by mutableStateOf(false); private set
    var latest by mutableStateOf<Info?>(null); private set
    var dialog by mutableStateOf<Info?>(null)
    var error by mutableStateOf<String?>(null); private set

    fun load() {
        nightly = prefs.getBoolean("nightly", false)
        autoOn = prefs.getBoolean("auto", true)
        skipped = prefs.getString("skip", "") ?: ""
    }
    fun setAuto(v: Boolean) { autoOn = v; prefs.edit().putBoolean("auto", v).apply() }
    fun skip(title: String) { skipped = title; dialog = null; prefs.edit().putString("skip", title).apply() }
    fun useNightly(v: Boolean) { nightly = v; latest = null; error = null; prefs.edit().putBoolean("nightly", v).apply() }

    /** 本机这一版的说明：正式版只显示版本号，测试版带上提交号 */
    val current: String get() = APP_VERSION + (BuildMeta.COMMIT.takeIf { it.length >= 7 }?.let { " · " + it.take(7) } ?: "")

    private suspend fun api(path: String): String = withContext(Dispatchers.IO) {
        Ncm.http.newCall(Request.Builder().url("https://api.github.com/repos/$REPO/$path").header("Accept", "application/vnd.github+json").build()).execute().use { r ->
            if (r.code == 404) throw NcmError("还没有发布过正式版")
            if (r.code == 403) throw NcmError("GitHub 接口请求太频繁，过一会儿再试")
            if (!r.isSuccessful) throw NcmError("GitHub 返回 HTTP ${r.code}")
            r.body?.string() ?: ""
        }
    }

    /** 本平台该下哪个文件：按文件名挑 */
    private fun pick(names: List<String>): String? {
        val n = names.map { it.lowercase() }
        val i = when (Plat.impl.name) {
            "android" -> n.indexOfFirst { it.endsWith(".apk") && "arm64" in it }.takeIf { it >= 0 } ?: n.indexOfFirst { it.endsWith(".apk") }
            "ios" -> n.indexOfFirst { it.endsWith(".ipa") }
            else -> {
                val os = System.getProperty("os.name").orEmpty().lowercase()
                when {
                    "win" in os -> n.indexOfFirst { it.endsWith(".msi") }.takeIf { it >= 0 } ?: n.indexOfFirst { it.endsWith(".exe") }
                    "mac" in os -> n.indexOfFirst { it.endsWith(".dmg") }
                    else -> n.indexOfFirst { it.endsWith(".deb") }
                }
            }
        }
        return i.takeIf { it >= 0 }?.let { names[it] }
    }

    private fun pickAsset(o: JSONObject): String? {
        val assets = o.optJSONArray("assets") ?: JSONArray()
        val names = (0 until assets.length()).map { assets.getJSONObject(it).optString("name") }
        return pick(names)?.let { n ->
            (0 until assets.length()).map { assets.getJSONObject(it) }
                .first { it.optString("name") == n }
                .optString("browser_download_url")
                .takeIf { it.isNotEmpty() }
        }
    }

    suspend fun check(): Info {
        checking = true; error = null
        try {
            val info = if (nightly) checkNightly() else checkRelease()
            latest = info; prefs.edit().putLong("last", System.currentTimeMillis()).apply()
            return info
        } catch (e: Exception) { error = e.message; throw e } finally { checking = false }
    }

    private suspend fun checkRelease(): Info {
        val o = JSONObject(api("releases/latest"))
        val tag = o.optString("tag_name").removePrefix("v")
        return Info(PluginSubs.newer(tag, APP_VERSION), "v$tag", o.optString("body").take(1500), pickAsset(o), o.optString("html_url"))
    }

    private suspend fun checkNightly(): Info {
        val arr = JSONArray(api("releases?per_page=20"))
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !it.optBoolean("draft") }
        val o = list.firstOrNull { it.optBoolean("prerelease") } ?: throw NcmError("还没有发布过预发布版")
        val tag = o.optString("tag_name").removePrefix("v")
        val baseVer = tag.substringBefore('-')
        val pubTime = epoch(o.optString("published_at").ifEmpty { o.optString("created_at") })
        val sha = o.optString("target_commitish")
        val sameCommit = sha.length >= 7 && BuildMeta.COMMIT.length >= 7 && sha.startsWith(BuildMeta.COMMIT.take(7))
        val found = when {
            sameCommit -> false
            PluginSubs.newer(baseVer, APP_VERSION) -> true
            baseVer == APP_VERSION -> BuildMeta.COMMIT_TIME == 0L || (pubTime > 0L && pubTime > BuildMeta.COMMIT_TIME + 120)
            else -> false
        }
        return Info(found, "v$tag", o.optString("body").take(1500), pickAsset(o), o.optString("html_url"))
    }

    /** "2026-11-20T17:44:00Z" / "+08:00" → 秒（不依赖 java.time，iOS 也能用） */
    internal fun epoch(iso: String): Long {
        val m = Regex("""(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.\d+)?(Z|[+-]\d\d:\d\d)?""").find(iso) ?: return 0
        val g = m.groupValues
        var y = g[1].toLong(); val mo = g[2].toLong(); val d = g[3].toLong()
        if (mo <= 2) y -= 1
        val era = y / 400; val yoe = y - era * 400
        val doy = (153 * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5 + d - 1
        val days = era * 146097 + yoe * 365 + yoe / 4 - yoe / 100 + doy - 719468
        var sec = days * 86400 + g[4].toLong() * 3600 + g[5].toLong() * 60 + g[6].toLong()
        g[7].takeIf { it.length == 6 }?.let { z -> val off = z.substring(1, 3).toLong() * 3600 + z.substring(4).toLong() * 60; sec -= if (z[0] == '+') off else -off }
        return sec
    }

    /** 启动时自动检查：默认开启，10 分钟内不重复请求，已跳过的版本不再弹窗，出错不打扰 */
    suspend fun autoCheck(): Info? {
        load()
        if (!autoOn) return null
        if (System.currentTimeMillis() - prefs.getLong("last", 0) < 10 * 60_000L) return null
        val info = runCatching { check() }.getOrNull()?.takeIf { it.found && it.title != skipped } ?: return null
        dialog = info
        return info
    }
}
