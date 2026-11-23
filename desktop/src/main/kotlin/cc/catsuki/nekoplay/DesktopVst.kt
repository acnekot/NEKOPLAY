package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.json.JSONArray
import java.io.File

/**
 * 桌面端 VST2 / VST3 实时宿主桥接：通过 JNA 加载原生 libnekoplay_vst，
 * 并将 libmpv 的音频输出热切换到 PCM 管道 → VST 插件链 → miniaudio 声卡输出。
 */
class DesktopVstHost(private val setMpvPipe: (String?) -> Unit) : VstHost {
    @Suppress("FunctionName")
    private interface NvLib : Library {
        fun nv_init(): Int
        fun nv_shutdown()
        fun nv_start_engine(sampleRate: Int, channels: Int): Pointer?
        fun nv_stop_engine()
        fun nv_engine_running(): Int
        fun nv_scan_dirs(dirsJson: String?): Pointer?
        fun nv_add_plugin(path: String): Int
        fun nv_remove_plugin(id: Int): Int
        fun nv_move_plugin(id: Int, newIndex: Int): Int
        fun nv_set_bypass(id: Int, bypass: Int): Int
        fun nv_get_chain(): Pointer?
        fun nv_get_params(id: Int): Pointer?
        fun nv_set_param(id: Int, index: Int, value: Float): Int
        fun nv_open_editor(id: Int, alwaysOnTop: Int): Int
        fun nv_set_editor_ontop(id: Int, alwaysOnTop: Int): Int
        fun nv_close_editor(id: Int): Int
        fun nv_last_error(): Pointer?
        fun nv_free_str(p: Pointer)
    }

    private var errMsg: String? = null
    private val lib: NvLib? by lazy {
        val opts = mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")
        val resDir = System.getProperty("compose.application.resources.dir")?.let { File(it) }
        val devDirs = listOfNotNull(
            resDir,
            File("desktop/resources/macos"), File("desktop/resources/windows"), File("desktop/resources/linux"),
            File("resources/macos"), File("resources/windows"), File("resources/linux"),
        )
        val candidates = buildList {
            for (d in devDirs) {
                d.listFiles { f -> f.name.contains("nekoplay_vst") && (f.name.endsWith(".dylib") || f.name.endsWith(".dll") || f.name.endsWith(".so")) }
                    ?.forEach { add(it.absolutePath) }
            }
            add("nekoplay_vst")
        }
        for (c in candidates) {
            val loaded = runCatching { Native.load(c, NvLib::class.java, opts) }.onFailure { errMsg = it.message }.getOrNull()
            if (loaded != null) {
                loaded.nv_init()
                errMsg = null
                return@lazy loaded
            }
        }
        null
    }

    private val prefs by lazy { Plat.prefs("vst") }
    override val available: Boolean get() = lib != null
    override val loadError: String? get() { lib; return errMsg }
    private var _enabled by mutableStateOf(false)
    private var _onTop by mutableStateOf(true)
    override val enabled get() = _enabled
    override val onTop get() = _onTop
    override var customDirs by mutableStateOf<List<String>>(emptyList()); private set
    override var chain by mutableStateOf<List<VstSlot>>(emptyList()); private set
    private var loadedPrefs = false

    fun initSaved() {
        if (loadedPrefs) return
        loadedPrefs = true
        _onTop = prefs.getBoolean("onTop", true)
        customDirs = prefs.getString("dirs", "")?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (lib == null) return
        val savedPaths = prefs.getString("chain", "")?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        for (line in savedPaths) {
            val parts = line.split('\t')
            val p = parts[0]
            val bp = parts.getOrNull(1) == "1"
            val id = lib!!.nv_add_plugin(p)
            if (id >= 0 && bp) lib!!.nv_set_bypass(id, 1)
        }
        refreshChain()
        if (prefs.getBoolean("enabled", false) && chain.isNotEmpty()) {
            setEnabled(true)
        }
    }

    private fun readStr(p: Pointer?): String {
        if (p == null) return ""
        val s = p.getString(0, "UTF-8")
        lib?.nv_free_str(p)
        return s
    }

    private fun lastErr(): String = readStr(lib?.nv_last_error()).ifEmpty { "操作失败" }

    private fun saveChain() {
        val raw = chain.joinToString("\n") { "${it.path}\t${if (it.bypass) "1" else "0"}" }
        prefs.edit().putString("chain", raw).apply()
    }

    private fun refreshChain() {
        val l = lib ?: return
        val raw = readStr(l.nv_get_chain())
        val out = ArrayList<VstSlot>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += VstSlot(
                    id = o.getInt("id"),
                    path = o.optString("path"),
                    name = o.optString("name"),
                    format = o.optString("format", "VST3"),
                    bypass = o.optBoolean("bypass", false),
                    editorOpen = o.optBoolean("editorOpen", false),
                )
            }
        }
        chain = out
    }

    override fun setEnabled(on: Boolean): String? {
        val l = lib ?: return "未找到原生 VST 宿主库（${errMsg ?: "libnekoplay_vst"}）"
        if (on) {
            val pipe = readStr(l.nv_start_engine(48000, 2))
            if (pipe.isEmpty()) return lastErr()
            setMpvPipe(pipe)
            _enabled = true
        } else {
            setMpvPipe(null)
            l.nv_stop_engine()
            _enabled = false
        }
        prefs.edit().putBoolean("enabled", _enabled).apply()
        return null
    }

    override fun setOnTop(on: Boolean) {
        _onTop = on
        prefs.edit().putBoolean("onTop", on).apply()
        val l = lib ?: return
        chain.forEach { if (it.editorOpen) l.nv_set_editor_ontop(it.id, if (on) 1 else 0) }
    }

    override fun addDir(dir: String) {
        val d = dir.trim()
        if (d.isEmpty() || d in customDirs) return
        customDirs = customDirs + d
        prefs.edit().putString("dirs", customDirs.joinToString("\n")).apply()
    }

    override fun removeDir(dir: String) {
        customDirs = customDirs - dir
        prefs.edit().putString("dirs", customDirs.joinToString("\n")).apply()
    }

    override fun scan(): List<VstFound> {
        val l = lib ?: return emptyList()
        val json = if (customDirs.isEmpty()) null else JSONArray(customDirs).toString()
        val raw = readStr(l.nv_scan_dirs(json))
        val out = ArrayList<VstFound>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += VstFound(o.optString("path"), o.optString("name"), o.optString("format", "VST3"))
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    override fun addPlugin(path: String): String? {
        val l = lib ?: return "未找到原生 VST 宿主库"
        val id = l.nv_add_plugin(path)
        if (id < 0) return lastErr()
        refreshChain()
        saveChain()
        if (!enabled) setEnabled(true)
        return null
    }

    override fun removePlugin(id: Int) {
        lib?.nv_remove_plugin(id)
        refreshChain()
        saveChain()
        if (chain.isEmpty() && enabled) setEnabled(false)
    }

    override fun movePlugin(id: Int, newIndex: Int) {
        lib?.nv_move_plugin(id, newIndex)
        refreshChain()
        saveChain()
    }

    override fun setBypass(id: Int, bypass: Boolean) {
        lib?.nv_set_bypass(id, if (bypass) 1 else 0)
        refreshChain()
        saveChain()
    }

    override fun params(id: Int): List<VstParam> {
        val l = lib ?: return emptyList()
        val raw = readStr(l.nv_get_params(id))
        val out = ArrayList<VstParam>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += VstParam(o.getInt("index"), o.optLong("id"), o.optString("name"), o.optDouble("value", 0.0).toFloat(), o.optString("display"))
            }
        }
        return out
    }

    override fun setParam(id: Int, index: Int, value: Float) {
        lib?.nv_set_param(id, index, value.coerceIn(0f, 1f))
    }

    override fun openEditor(id: Int): String? {
        val l = lib ?: return "未找到原生 VST 宿主库"
        val ok = l.nv_open_editor(id, if (onTop) 1 else 0)
        refreshChain()
        return if (ok == 1) null else lastErr()
    }

    override fun closeEditor(id: Int) {
        lib?.nv_close_editor(id)
        refreshChain()
    }

    fun shutdown() {
        if (enabled) setMpvPipe(null)
        runCatching { lib?.nv_shutdown() }
    }
}
