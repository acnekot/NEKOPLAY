package cc.catsuki.nekoplay.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.catsuki.nekoplay.*
import cc.catsuki.nekoplay.core.*
import cc.catsuki.nekoplay.player.*
import cc.catsuki.nekoplay.ui.*

/** 搜索页当前选的来源：开着且能搜索的插件 */
object SearchSource {
    var key by mutableStateOf("")
    val plugin: String? get() = key.ifEmpty { null }
    /** (名字, 插件 id) */
    val options: List<Pair<String, String>> get() = Plugins.enabled.filter { it.has("search") || it.caps.isEmpty() }.map { it.name to it.id }
    val labels get() = options.map { it.first }
    /** 当前选的来源没了就换成第一个（首页来源优先） */
    fun fix() {
        val o = options.map { it.second }
        if (key !in o) key = o.firstOrNull { it == UiPref.homePlugin } ?: o.firstOrNull() ?: ""
    }
}
