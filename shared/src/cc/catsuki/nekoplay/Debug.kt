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
        reqs.value = (listOf(Req(System.currentTimeMillis(), path, code, ms, type, body.take(2000))) + reqs.value).take(80)
    }
    /** 拼到错误信息后面的真实数据（调试模式才有） */
    fun detail(path: String, code: Int, type: String, body: String): String =
        if (!on) "" else "\n[$path] HTTP $code · ${type.ifEmpty { "无类型" }} · ${body.length} 字节\n" + body.take(400).replace(Regex("\\s+"), " ").ifEmpty { "（空响应）" }
}
