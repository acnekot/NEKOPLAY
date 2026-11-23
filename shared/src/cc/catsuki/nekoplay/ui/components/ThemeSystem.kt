package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** 可制作、导入、导出的主题包 */
data class ThemePack(
    val id: String,
    val name: String,
    val author: String = "自定义",
    val dark: Boolean = false,
    val flat: Boolean = true,
    val page: String = "#D3E5EA",
    val sheet: String = "#E2EDEE",
    val bar: String = "#CBE0E7",
    val accent: String = "#1A4BE0",
    val ink: String = "#0E1117",
    val muted: String = "#46505E",
    val builtin: Boolean = false,
) {
    fun toPal(): Pal {
        val pg = parseHex(page, if (dark) Color(0xFF2D333B) else Color(0xFFD3E5EA))
        val sh = parseHex(sheet, pg)
        val br = parseHex(bar, pg)
        val ac = parseHex(accent, if (dark) Color(0xFF5580F7) else Color(0xFF1A4BE0))
        val ik = parseHex(ink, if (dark) Color(0xFFE6EDF3) else Color(0xFF0E1117))
        val mt = parseHex(muted, ik.copy(alpha = .68f))
        val pop = if (dark) sh.copy(red = (sh.red + .05f).coerceAtMost(1f), green = (sh.green + .05f).coerceAtMost(1f), blue = (sh.blue + .05f).coerceAtMost(1f))
        else sh.copy(red = (sh.red + .06f).coerceAtMost(1f), green = (sh.green + .06f).coerceAtMost(1f), blue = (sh.blue + .06f).coerceAtMost(1f))
        return Pal(
            dark = dark, k = ik, p = ac, s = mt, s2 = mt,
            l1 = ik.copy(alpha = .12f), l2 = ik.copy(alpha = .20f), l3 = ik.copy(alpha = .28f),
            bar = br, pop = pop, tagBg = ik, tagFg = if (dark) pg else Color.White,
            hl = Color.White.copy(alpha = if (dark) .08f else .50f),
            lyBg = if (dark) Color(0xFFF0F2F5) else Color(0x8CFFFFFF),
            lyFg = if (dark) Color(0xFF0E1117) else ac, lyFg2 = if (dark) Color(0xFF0E1117) else mt,
            bad = if (dark) Color(0xFFF85149) else Color(0xFFC42B2B),
            ph = if (dark) br else br, page = pg, sheet = sh,
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("format", "nekoplay-theme-v1")
        .put("id", id).put("name", name).put("author", author)
        .put("dark", dark).put("flat", flat)
        .put("page", page).put("sheet", sheet).put("bar", bar)
        .put("accent", accent).put("ink", ink).put("muted", muted)

    companion object {
        fun parseHex(hex: String, fallback: Color): Color = runCatching {
            val s = hex.trim().removePrefix("#")
            val v = s.toLong(16)
            when (s.length) {
                6 -> Color(0xFF000000L or v)
                8 -> Color(v)
                else -> fallback
            }
        }.getOrDefault(fallback)

        fun fromJson(o: JSONObject, builtin: Boolean = false): ThemePack {
            val dark = o.optBoolean("dark", false)
            val id = o.optString("id").trim().ifEmpty { "custom_" + System.currentTimeMillis() }
            val name = o.optString("name").trim().ifEmpty { "导入主题" }.take(24)
            return ThemePack(
                id = id, name = name, author = o.optString("author", "自定义").trim().take(24),
                dark = dark, flat = o.optBoolean("flat", true),
                page = o.optString("page", if (dark) "#22272E" else "#D3E5EA"),
                sheet = o.optString("sheet", if (dark) "#2D333B" else "#E2EDEE"),
                bar = o.optString("bar", if (dark) "#1B2027" else "#CBE0E7"),
                accent = o.optString("accent", if (dark) "#5580F7" else "#1A4BE0"),
                ink = o.optString("ink", if (dark) "#E6EDF3" else "#0E1117"),
                muted = o.optString("muted", if (dark) "#9DA7B3" else "#46505E"),
                builtin = builtin,
            )
        }
    }
}

object ThemeStore {
    val BUILTINS = listOf(
        ThemePack("builtin_benchlab", "BenchLab", "官方", dark = false, flat = false, page = "#D3E5EA", sheet = "#E2EDEE", bar = "#CBE0E7", accent = "#1A4BE0", ink = "#0E1117", muted = "#46505E", builtin = true),
        ThemePack("builtin_sakura", "樱吹雪", "官方", dark = false, flat = true, page = "#F6E9EE", sheet = "#FBF2F5", bar = "#EEDAE2", accent = "#D9386A", ink = "#23141B", muted = "#705360", builtin = true),
        ThemePack("builtin_matcha", "抹茶纸墨", "官方", dark = false, flat = true, page = "#E4ECE0", sheet = "#EFF4EC", bar = "#D7E3D2", accent = "#2B6E47", ink = "#16211A", muted = "#526658", builtin = true),
        ThemePack("builtin_cyber", "赛博青", "官方", dark = true, flat = false, page = "#11141C", sheet = "#181D29", bar = "#0C0E14", accent = "#00D4FF", ink = "#EAF4FF", muted = "#8A9BB3", builtin = true),
        ThemePack("builtin_midnight", "午夜紫", "官方", dark = true, flat = true, page = "#171624", sheet = "#211F33", bar = "#12111D", accent = "#9B72FF", ink = "#EFEAFF", muted = "#9A93B8", builtin = true),
    )

    var activeId by mutableStateOf("")
    var customs by mutableStateOf<List<ThemePack>>(emptyList())
    private val k get() = Plat.prefs("ui")

    val all: List<ThemePack> get() = BUILTINS + customs
    fun active(): ThemePack? = if (activeId.isEmpty()) null else all.firstOrNull { it.id == activeId }
    fun activePal(): Pal? = active()?.toPal()

    fun load() {
        activeId = k.getString("active_theme", "") ?: ""
        customs = runCatching {
            val arr = JSONArray(k.getString("custom_themes", "[]") ?: "[]")
            (0 until arr.length()).mapNotNull { runCatching { ThemePack.fromJson(arr.getJSONObject(it), builtin = false) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    private fun persist() {
        val arr = JSONArray()
        customs.forEach { arr.put(it.toJson()) }
        k.edit().putString("active_theme", activeId).putString("custom_themes", arr.toString()).apply()
    }

    fun select(id: String) {
        activeId = id
        active()?.let { Look.set(if (it.flat) 1 else 0) }
        persist()
    }

    fun saveCustom(pack: ThemePack) {
        val p = pack.copy(builtin = false)
        customs = customs.filter { it.id != p.id } + p
        activeId = p.id
        Look.set(if (p.flat) 1 else 0)
        persist()
    }

    fun deleteCustom(id: String) {
        customs = customs.filter { it.id != id }
        if (activeId == id) activeId = ""
        persist()
    }

    fun exportCurrent(pal: Pal): String {
        val cur = active()
        if (cur != null) return cur.toJson().toString(2)
        fun Color.hex(): String = "#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
        return ThemePack(
            id = "export_" + System.currentTimeMillis(),
            name = if (pal.dark) "NEKOPLAY 深色" else "NEKOPLAY 浅色",
            author = "NEKOPLAY", dark = pal.dark, flat = Look.flat,
            page = pal.page.hex(), sheet = pal.sheet.hex(), bar = pal.bar.hex(),
            accent = pal.p.hex(), ink = pal.k.hex(), muted = pal.s.hex(),
        ).toJson().toString(2)
    }

    fun importJson(raw: String): ThemePack {
        val o = JSONObject(raw.trim())
        if (!o.has("page") && !o.has("accent")) throw IllegalArgumentException("缺少 page 或 accent 颜色字段")
        val p = ThemePack.fromJson(o, builtin = false)
        val safeId = if (p.id.startsWith("builtin_")) "custom_" + System.currentTimeMillis() else p.id
        val saved = p.copy(id = safeId)
        saveCustom(saved)
        return saved
    }
}

private val ACCENT_SWATCHES = listOf("#1A4BE0", "#5580F7", "#D9386A", "#FF5C7A", "#2B6E47", "#00B8D9", "#9B72FF", "#E07A1A")
private val LIGHT_BGS = listOf("#D3E5EA", "#F6E9EE", "#E4ECE0", "#F3EFE6", "#E8EBF5", "#F5F5F7")
private val DARK_BGS = listOf("#1F242B", "#11141C", "#171624", "#181C18", "#20171B", "#141416")

/** 设置 → 外观 里的主题商店、主题制作器与 JSON 导入导出 */
@Composable fun ThemeStudioSection() {
    val nav = LocalNav.current
    val p = c
    val copy = rememberCopy()
    var making by remember { mutableStateOf(false) }

    var dName by remember { mutableStateOf("我的主题") }
    var dDark by remember { mutableStateOf(p.dark) }
    var dFlat by remember { mutableStateOf(Look.flat) }
    var dAccent by remember { mutableStateOf(if (p.dark) "#5580F7" else "#1A4BE0") }
    var dPage by remember { mutableStateOf(if (p.dark) "#1F242B" else "#D3E5EA") }
    var dSheet by remember { mutableStateOf(if (p.dark) "#282E37" else "#E2EDEE") }
    var dInk by remember { mutableStateOf(if (p.dark) "#E6EDF3" else "#0E1117") }

    SetLine("主题系统", "一键切换预设主题，或自制 / 导出 / 导入主题 JSON（长按自定义主题可删除）") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            item {
                val on = ThemeStore.activeId.isEmpty()
                Column(Modifier.width(96.dp).press { ThemeStore.select("") }.background(p.sheet).border(if (on) 2.dp else 1.dp, if (on) p.p else p.l2).padding(8.dp)) {
                    Row(Modifier.fillMaxWidth().height(26.dp)) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFD3E5EA)))
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF2D333B)))
                        Box(Modifier.width(14.dp).fillMaxHeight().background(Color(0xFF1A4BE0)))
                    }
                    Spacer(Modifier.height(6.dp))
                    T(if (on) "✓ 默认" else "系统默认", 12, if (on) p.p else p.k, 700, lines = 1)
                }
            }
            items(ThemeStore.all, key = { it.id }) { tp ->
                val on = ThemeStore.activeId == tp.id
                val pal = remember(tp) { tp.toPal() }
                Column(Modifier.width(96.dp).press(onLong = {
                    if (!tp.builtin) {
                        nav.sheet = SheetSpec(tp.name, "自定义主题", listOf(
                            SheetItem("复制主题 JSON") { copy(tp.toJson().toString(2), "主题 JSON") },
                            SheetItem("删除主题", danger = true) { ThemeStore.deleteCustom(tp.id); nav.toast = "已删除主题 ${tp.name}" },
                        ))
                    } else copy(tp.toJson().toString(2), "主题 JSON")
                }) { ThemeStore.select(tp.id) }.background(pal.page).border(if (on) 2.dp else 1.dp, if (on) pal.p else p.l2).padding(8.dp)) {
                    Row(Modifier.fillMaxWidth().height(26.dp)) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(pal.sheet))
                        Box(Modifier.width(18.dp).fillMaxHeight().background(pal.p))
                    }
                    Spacer(Modifier.height(6.dp))
                    T((if (on) "✓ " else "") + tp.name, 12, pal.k, 700, lines = 1)
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.press { making = !making }.background(if (making) p.p else p.k).padding(horizontal = 12.dp, vertical = 6.dp)) {
                T(if (making) "收起制作器" else "制作主题", 12, if (making) Color.White else p.tagFg, 700)
            }
            Box(Modifier.press { copy(ThemeStore.exportCurrent(p), "主题 JSON") }.nbf(1.dp, p.k).padding(horizontal = 10.dp, vertical = 6.dp)) {
                T("导出 JSON", 12, p.k, 700)
            }
            Box(Modifier.press {
                nav.input = InputSpec("导入主题 JSON", "粘贴导出的主题 JSON（format: nekoplay-theme-v1）") { raw ->
                    val tp = runCatching { ThemeStore.importJson(raw) }.getOrElse { nav.toast = "主题 JSON 无效：${it.message}"; return@InputSpec }
                    nav.toast = "已导入并应用主题：${tp.name}"
                }
            }.nbf(1.dp, p.k).padding(horizontal = 10.dp, vertical = 6.dp)) {
                T("导入 JSON", 12, p.k, 700)
            }
        }
        if (making) {
            val preview = remember(dName, dDark, dFlat, dAccent, dPage, dSheet, dInk) {
                ThemePack("preview", dName, "自定义", dDark, dFlat, dPage, dSheet, dPage, dAccent, dInk, if (dDark) "#9DA7B3" else "#46505E")
            }
            val pp = remember(preview) { preview.toPal() }
            Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(p.sheet).nbf(1.dp, p.k).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 实时迷你预览卡
                Row(Modifier.fillMaxWidth().background(pp.page).then(if (dFlat) Modifier else Modifier.border(1.dp, pp.k)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.background(pp.tagBg).padding(horizontal = 6.dp, vertical = 2.dp)) { T("PREVIEW", 10, pp.tagFg, 700, mono = true) }
                        Spacer(Modifier.height(4.dp))
                        T(dName.ifBlank { "未命名主题" }, 15, pp.k, 800)
                    }
                    Box(Modifier.background(pp.p).padding(horizontal = 12.dp, vertical = 6.dp)) { T("主色按钮", 12, Color.White, 700) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("名称", 12, p.s, modifier = Modifier.width(42.dp))
                    Box(Modifier.weight(1f).height(34.dp).nbf(1.dp, p.l2).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                        BasicTextField(dName, { dName = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = p.k, fontSize = 13.sp, fontFamily = Typo.family), cursorBrush = SolidColor(p.p))
                    }
                    LinkBtn(if (dDark) "暗色底" else "浅色底", 12) {
                        dDark = !dDark
                        dPage = if (dDark) "#1F242B" else "#D3E5EA"
                        dSheet = if (dDark) "#282E37" else "#E2EDEE"
                        dInk = if (dDark) "#E6EDF3" else "#0E1117"
                    }
                    LinkBtn(if (dFlat) "无边框" else "线框", 12) { dFlat = !dFlat }
                }
                SwatchRow("主色", dAccent, ACCENT_SWATCHES) { dAccent = it }
                SwatchRow("背景", dPage, if (dDark) DARK_BGS else LIGHT_BGS) { dPage = it; dSheet = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HexField("主色 Hex", dAccent, Modifier.weight(1f)) { dAccent = it }
                    HexField("背景 Hex", dPage, Modifier.weight(1f)) { dPage = it }
                    HexField("文字 Hex", dInk, Modifier.weight(1f)) { dInk = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).height(36.dp).press {
                        val tp = preview.copy(id = "custom_" + System.currentTimeMillis(), name = dName.trim().ifEmpty { "自定义主题" }, bar = dPage)
                        ThemeStore.saveCustom(tp); making = false; nav.toast = "已保存并应用主题：${tp.name}"
                    }.background(p.p), contentAlignment = Alignment.Center) { T("保存并应用", 13, Color.White, 700) }
                    Box(Modifier.height(36.dp).press {
                        copy(preview.copy(bar = dPage).toJson().toString(2), "制作中的主题 JSON")
                    }.nbf(1.dp, p.k).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { T("复制 JSON", 12, p.k, 700) }
                }
            }
        }
    }
}

@Composable private fun SwatchRow(label: String, cur: String, list: List<String>, onPick: (String) -> Unit) {
    val p = c
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        T(label, 12, p.s, modifier = Modifier.width(34.dp))
        list.forEach { hex ->
            val col = ThemePack.parseHex(hex, p.p)
            val sel = cur.equals(hex, ignoreCase = true)
            Box(Modifier.size(22.dp).press { onPick(hex) }.background(col).border(if (sel) 2.dp else 1.dp, if (sel) p.k else p.l2))
        }
    }
}

@Composable private fun HexField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    val p = c
    Column(modifier) {
        T(label, 10, p.s, mono = true)
        Box(Modifier.fillMaxWidth().height(32.dp).padding(top = 2.dp).nbf(1.dp, p.l2).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = p.k, fontSize = 12.sp, fontFamily = Mono), cursorBrush = SolidColor(p.p))
        }
    }
}
