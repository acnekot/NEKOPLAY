package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class AppLang(val code: String, val nativeName: String)

/**
 * 轻量多语言引擎：支持跟随系统、简体中文、繁體中文、English、日本語。
 * - 切换后通过 Compose 状态实时生效，无需重启；
 * - 简体中文模式下 O(1) 零开销直返；
 * - 区分 `tr()`（固定界面文案，繁体模式含简繁字表回退）与 `trKnown()`（仅命中已知文案与动态模板，绝不误伤歌名/歌手/歌词）。
 */
object I18n {
    const val AUTO = "auto"
    const val ZH_CN = "zh-CN"
    const val ZH_TW = "zh-TW"
    const val EN = "en"
    const val JA = "ja"

    val options = listOf(
        AppLang(AUTO, "跟随系统"),
        AppLang(ZH_CN, "简体中文"),
        AppLang(ZH_TW, "繁體中文"),
        AppLang(EN, "English"),
        AppLang(JA, "日本語"),
    )

    var code by mutableStateOf(AUTO); private set

    val active: String
        get() = if (code == AUTO) resolveSystem(runCatching { Plat.impl.systemLang() }.getOrDefault(ZH_CN)) else code

    fun nativeOf(c: String): String = options.firstOrNull { it.code == c }?.nativeName ?: "简体中文"

    val currentLabel: String
        get() = if (code == AUTO) "${t("跟随系统")} · ${nativeOf(active)}" else nativeOf(code)

    fun optionLabel(opt: AppLang): String =
        if (opt.code == AUTO) "${t("跟随系统")} (${nativeOf(resolveSystem(runCatching { Plat.impl.systemLang() }.getOrDefault(ZH_CN)))})"
        else opt.nativeName

    fun resolveSystem(raw: String): String {
        val tag = raw.replace('_', '-').lowercase()
        return when {
            tag.startsWith("en") -> EN
            tag.startsWith("ja") -> JA
            tag.startsWith("zh") && ("tw" in tag || "hk" in tag || "mo" in tag || "hant" in tag) -> ZH_TW
            else -> ZH_CN
        }
    }

    fun load() {
        val saved = Plat.prefs("ui").getString("lang", AUTO) ?: AUTO
        code = if (options.any { it.code == saved }) saved else AUTO
    }

    fun set(c: String) {
        val next = if (options.any { it.code == c }) c else AUTO
        code = next
        Plat.prefs("ui").edit().putString("lang", next).apply()
    }

    /** 完整翻译（用于确定属于界面文案的文本） */
    fun t(s: String): String = translate(s, fallbackTrad = true)

    /** 仅翻译已知词条与动态模板（用于可能混入歌名、歌单名或路径的通用容器） */
    fun tKnown(s: String): String = translate(s, fallbackTrad = false)

    private fun translate(s: String, fallbackTrad: Boolean): String {
        val lang = active
        if (lang == ZH_CN || s.isEmpty()) return s
        val prefix = when {
            s.startsWith("✓  ") -> "✓  "
            s.startsWith("    ") -> "    "
            else -> ""
        }
        val core = if (prefix.isEmpty()) s else s.removePrefix(prefix)
        val out = when (lang) {
            EN -> I18nDict.en[core] ?: translatePattern(core, EN) ?: core
            JA -> I18nDict.ja[core] ?: translatePattern(core, JA) ?: core
            ZH_TW -> I18nDict.zhTw[core] ?: translatePattern(core, ZH_TW) ?: if (fallbackTrad) I18nDict.toTraditional(core) else core
            else -> core
        }
        return if (prefix.isEmpty()) out else prefix + out
    }

    private val rxTracksDur = Regex("""^(\d+)\s*首\s*·\s*(.+)$""")
    private val rxTracks = Regex("""^(\d+)\s*首$""")
    private val rxPlaylists = Regex("""^(\d+)\s*个歌单$""")
    private val rxCount = Regex("""^(\d+)\s*个$""")
    private val rxAlbums = Regex("""^(\d+)\s*张$""")
    private val rxItems = Regex("""^(\d+)\s*条$""")
    private val rxPlTracks = Regex("""^歌单\s*·\s*(\d+)\s*首$""")
    private val rxLocalPl = Regex("""^本地歌单\s*·\s*(\d+)\s*首$""")
    private val rxViewAll = Regex("""^查看全部\s*(\d+)\s*首$""")
    private val rxNewVer = Regex("""^有新版本：(.+)$""")
    private val rxUpToDate = Regex("""^已经是最新（(.+)）$""")
    private val rxCheckFail = Regex("""^检查失败：(.+)$""")
    private val rxSkipped = Regex("""^已跳过\s+(.+)$""")
    private val rxSignedIn = Regex("""^已登录\s*·\s*(.+)$""")
    private val rxNowPlaying = Regex("""^正在播放\s*·\s*(.+)$""")
    private val rxEnabledRatio = Regex("""^(\d+)/(\d+)\s*个已开启$""")
    private val rxFeatsRatio = Regex("""^内置模块\s*(\d+)/(\d+)\s*·\s*外置功能\s*(\d+)/(\d+)$""")
    private val rxMinutesAgo = Regex("""^(\d+)\s*分钟前$""")
    private val rxReplies = Regex("""^(\d+)\s*条回复\s*›$""")
    private val rxHoverUnlock = Regex("""^保持悬停\s*(\d+)s\s*解锁$""")
    private val rxCopied = Regex("""^已复制(.+)$""")
    private val rxQuality = Regex("""^音质：(.+)$""")
    private val rxCellQuality = Regex("""^流量音质：(.+)$""")
    private val rxLangSet = Regex("""^已切换语言：(.+)$""")
    private val rxHistMeta = Regex("""^(\d+)\s*条\s*·\s*(\d+)\s*天\s*·\s*(.+)$""")
    private val rxDayRel = Regex("""^(今天|昨天)\s*·\s*(.+)$""")
    private val rxDayWd = Regex("""^(\d{4}-\d{2}-\d{2})\s*·\s*周([日一二三四五六])$""")

    private fun translatePattern(s: String, lang: String): String? {
        rxTracksDur.matchEntire(s)?.let { m ->
            val (n, dur) = m.destructured
            return when (lang) { EN -> "$n tracks · $dur"; JA -> "$n 曲 · $dur"; else -> "$n 首 · $dur" }
        }
        rxTracks.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) { EN -> "$n tracks"; JA -> "$n 曲"; else -> "$n 首" }
        }
        rxPlaylists.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) { EN -> "$n playlists"; JA -> "$n プレイリスト"; else -> "$n 個歌單" }
        }
        rxCount.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) { EN -> "$n items"; JA -> "$n 件"; else -> "$n 個" }
        }
        rxAlbums.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) { EN -> "$n albums"; JA -> "$n 枚"; else -> "$n 張" }
        }
        rxItems.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) { EN -> "$n items"; JA -> "$n 件"; else -> "$n 條" }
        }
        rxPlTracks.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) { EN -> "Playlist · $n tracks"; JA -> "プレイリスト · $n 曲"; else -> "歌單 · $n 首" }
        }
        rxLocalPl.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) { EN -> "Local · $n tracks"; JA -> "ローカル · $n 曲"; else -> "本機歌單 · $n 首" }
        }
        rxViewAll.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) { EN -> "View all $n"; JA -> "全 $n 曲を表示"; else -> "查看全部 $n 首" }
        }
        rxNewVer.matchEntire(s)?.let { m ->
            val v = m.groupValues[1]
            return when (lang) { EN -> "Update available: $v"; JA -> "新バージョン: $v"; else -> "有新版本：$v" }
        }
        rxUpToDate.matchEntire(s)?.let { m ->
            val v = m.groupValues[1]
            return when (lang) { EN -> "Up to date ($v)"; JA -> "最新です ($v)"; else -> "已經是最新（$v）" }
        }
        rxCheckFail.matchEntire(s)?.let { m ->
            val e = m.groupValues[1]
            return when (lang) { EN -> "Check failed: $e"; JA -> "確認に失敗: $e"; else -> "檢查失敗：$e" }
        }
        rxSkipped.matchEntire(s)?.let { m ->
            val v = m.groupValues[1]
            return when (lang) { EN -> "Skipped $v"; JA -> "$v をスキップしました"; else -> "已略過 $v" }
        }
        rxSignedIn.matchEntire(s)?.let { m ->
            val u = m.groupValues[1]
            return when (lang) { EN -> "Signed in · $u"; JA -> "ログイン済み · $u"; else -> "已登入 · $u" }
        }
        rxNowPlaying.matchEntire(s)?.let { m ->
            val t = m.groupValues[1]
            return when (lang) { EN -> "Now playing · $t"; JA -> "再生中 · $t"; else -> "正在播放 · $t" }
        }
        rxEnabledRatio.matchEntire(s)?.let { m ->
            val (a, b) = m.destructured
            return when (lang) { EN -> "$a/$b enabled"; JA -> "$a/$b 有効"; else -> "$a/$b 個已開啟" }
        }
        rxFeatsRatio.matchEntire(s)?.let { m ->
            val (a, b, c, d) = m.destructured
            return when (lang) { EN -> "Built-in $a/$b · Extensions $c/$d"; JA -> "内蔵 $a/$b · 拡張 $c/$d"; else -> "內建模組 $a/$b · 外掛功能 $c/$d" }
        }
        rxMinutesAgo.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) { EN -> "${n}m ago"; JA -> "${n}分前"; else -> "$n 分鐘前" }
        }
        rxReplies.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) { EN -> "$n replies ›"; JA -> "$n 件の返信 ›"; else -> "$n 則回覆 ›" }
        }
        rxHoverUnlock.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) { EN -> "Hold hover ${n}s to unlock"; JA -> "${n}秒ホバーでロック解除"; else -> "保持懸停 ${n}s 解鎖" }
        }
        rxCopied.matchEntire(s)?.let { m ->
            val what = m.groupValues[1]
            return when (lang) { EN -> "Copied ${tKnown(what)}"; JA -> "${tKnown(what)}をコピーしました"; else -> "已複製${I18nDict.toTraditional(what)}" }
        }
        rxQuality.matchEntire(s)?.let { m ->
            val q = tKnown(m.groupValues[1])
            return when (lang) { EN -> "Quality: $q"; JA -> "音質: $q"; else -> "音質：$q" }
        }
        rxCellQuality.matchEntire(s)?.let { m ->
            val q = tKnown(m.groupValues[1])
            return when (lang) { EN -> "Cellular quality: $q"; JA -> "モバイル音質: $q"; else -> "行動網路音質：$q" }
        }
        rxLangSet.matchEntire(s)?.let { m ->
            val l = m.groupValues[1]
            return when (lang) { EN -> "Language: $l"; JA -> "言語を変更しました: $l"; else -> "已切換語言：$l" }
        }
        rxHistMeta.matchEntire(s)?.let { m ->
            val (n, d, dur) = m.destructured
            return when (lang) { EN -> "$n plays · $d days · $dur"; JA -> "$n 件 · $d 日 · $dur"; else -> "$n 條 · $d 天 · $dur" }
        }
        rxDayRel.matchEntire(s)?.let { m ->
            val (rel, date) = m.destructured
            val w = when (lang) {
                EN -> if (rel == "今天") "Today" else "Yesterday"
                JA -> if (rel == "今天") "今日" else "昨日"
                else -> rel
            }
            return "$w · $date"
        }
        rxDayWd.matchEntire(s)?.let { m ->
            val (date, ch) = m.destructured
            val wd = when (lang) {
                EN -> mapOf("日" to "Sun", "一" to "Mon", "二" to "Tue", "三" to "Wed", "四" to "Thu", "五" to "Fri", "六" to "Sat")[ch] ?: ch
                JA -> ch
                else -> "週$ch"
            }
            return "$date · $wd"
        }
        return when (s) {
            "更早记录" -> when (lang) { EN -> "Earlier"; JA -> "以前の履歴"; else -> "更早記錄" }
            "本机记录 · 按天划分" -> when (lang) { EN -> "Local history · Grouped by day"; JA -> "ローカル履歴 · 日別"; else -> "本機記錄 · 按天劃分" }
            "播放当天" -> when (lang) { EN -> "Play day"; JA -> "この日を再生"; else -> "播放當天" }
            else -> null
        }
    }
}

fun tr(s: String): String = I18n.t(s)
fun trKnown(s: String): String = I18n.tKnown(s)
