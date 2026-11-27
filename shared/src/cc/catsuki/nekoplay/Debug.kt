package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow

/** 调试模式：记下最近的接口请求，出错时把真实返回（状态码、类型、内容开头）显示出来 */
object Debug {
    data class Req(val at: Long, val path: String, val code: Int, val ms: Long, val type: String, val body: String)
    var on by mutableStateOf(false)
    val reqs = MutableStateFlow<List<Req>>(emptyList())
    fun load() { on = Plat.prefs("ui").getBoolean("debug", false) }
    fun set(v: Boolean) { on = v; Plat.prefs("ui").edit().putBoolean("debug", v).apply() }
    fun log(path: String, code: Int, ms: Long, type: String, body: String) {
        if (!on) return
        reqs.value = (listOf(Req(System.currentTimeMillis(), path, code, ms, type, redact(body.take(4000)).take(2000))) + reqs.value).take(80)
    }
    /** 凭据字段打码：JSON 里的 "token": "…"、Cookie / Authorization 头、URL 参数里的 token=… 只留前 4 位 */
    private val SECRET_KEYS = "(?:[a-z_]*token|cookie|set-cookie|authorization|password|passwd|secret|session(?:id)?|sid|csrf|ticket|music_u|musicu|uin)"
    private val jsonSecret = Regex("(\"$SECRET_KEYS\"\\s*:\\s*)(\"[^\"]*\"|\\[[^\\]]*\\])", RegexOption.IGNORE_CASE)
    private val kvSecret = Regex("((?<![a-z0-9])$SECRET_KEYS\\s*[=:]\\s*)([^&;,\\s\"]+)", RegexOption.IGNORE_CASE)
    private fun mask(v: String): String {
        val quoted = v.length > 1 && v.startsWith("\"") && v.endsWith("\"")
        val t = v.trim('"')
        val m = if (t.length <= 4) "***" else t.take(4) + "***"
        return if (quoted) "\"$m\"" else m
    }
    fun redact(s: String): String {
        if (s.isEmpty()) return s
        val j = jsonSecret.replace(s) { val v = it.groupValues[2]; it.groupValues[1] + if (v.startsWith("[")) "[***]" else mask(v) }
        return kvSecret.replace(j) { it.groupValues[1] + mask(it.groupValues[2]) }
    }
    /** 拼到错误信息后面的真实数据（调试模式才有） */
    fun detail(path: String, code: Int, type: String, body: String): String =
        if (!on) "" else "\n[$path] HTTP $code · ${type.ifEmpty { "无类型" }} · ${body.length} 字节\n" +
            redact(body.take(800)).take(400).replace(Regex("\\s+"), " ").ifEmpty { "（空响应）" }
}
