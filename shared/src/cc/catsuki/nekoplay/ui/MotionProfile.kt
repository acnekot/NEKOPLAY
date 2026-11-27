package cc.catsuki.nekoplay

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import org.json.JSONObject

/** 由外部 @type motion 插件（如 osu_motion.js）下发的完整规范化动效配置包 */
data class MotionPluginProfile(
    val pluginId: String, val pluginName: String, val presetLabel: String,
    val quick: MotionChannel, val standard: MotionChannel, val page: MotionChannel, val crossfade: MotionChannel, val hero: MotionChannel,
    val hoverShiftDp: Float? = null, val hoverTint: Boolean? = null, val pressScale: Float? = null, val pageScale: Boolean? = null,
    val listStaggerMs: Int? = null, val listSlideDp: Float? = null,
    val originExpand: Boolean? = null, val originRipple: Boolean? = null, val originInherit: Boolean? = null,
    val originCornerDp: Float? = null, val selectCoverScale: Float? = null, val selectFontScale: Float? = null,
    val geom: GeomRevealConfig? = null,
) {
    val displayName: String get() = if (presetLabel.isNotBlank()) "$pluginName · $presetLabel" else pluginName
}

/** 将 @type motion 插件返回的 profile() JSON 解析为原生 120fps 动效通道配置 */
internal fun parseMotionPluginProfile(src: JsSource, o: JSONObject): MotionPluginProfile {
    val fx = o.optJSONObject("effects")
    fun ch(key: String, defEase: Easing, defMs: Int): MotionChannel {
        val c = o.optJSONObject(key) ?: return MotionChannel(defEase, defMs)
        val ms = c.optInt("ms", defMs).coerceIn(40, 1200)
        val easeName = c.optString("easing", "").lowercase()
        if (easeName == "spring") {
            return MotionChannel(
                FastOutSlowInEasing, ms, isSpring = true,
                damping = c.optDouble("damping", 0.82).toFloat().coerceIn(0.3f, 1f),
                stiffness = c.optDouble("stiffness", 420.0).toFloat().coerceIn(80f, 1400f),
            )
        }
        val easing = if (easeName == "bezier" || c.has("x1")) {
            SmoothBezierEasing(
                c.optDouble("x1", 0.16).toFloat(), c.optDouble("y1", 1.0).toFloat(),
                c.optDouble("x2", 0.30).toFloat(), c.optDouble("y2", 1.0).toFloat(),
            )
        } else MotionEasings.byName(easeName, defEase)
        return MotionChannel(easing, ms)
    }
    return MotionPluginProfile(
        pluginId = src.id,
        pluginName = o.optString("name").ifBlank { src.name },
        presetLabel = o.optString("presetLabel"),
        quick = ch("quick", MotionEasings.OutExpo, 140),
        standard = ch("standard", MotionEasings.OutQuint, 300),
        page = ch("page", MotionEasings.OutQuint, 420),
        crossfade = ch("crossfade", MotionEasings.OutQuint, 460),
        hero = ch("hero", MotionEasings.OutElasticQuarter, 440),
        hoverShiftDp = fx?.takeIf { it.has("hoverShiftDp") }?.optDouble("hoverShiftDp", 5.0)?.toFloat()?.coerceIn(0f, 12f),
        hoverTint = fx?.takeIf { it.has("hoverTint") }?.optBoolean("hoverTint"),
        pressScale = fx?.takeIf { it.has("pressScale") }?.optDouble("pressScale", 0.985)?.toFloat()?.coerceIn(0.92f, 1.0f),
        pageScale = fx?.takeIf { it.has("pageScale") }?.optBoolean("pageScale"),
        listStaggerMs = fx?.takeIf { it.has("listStaggerMs") }?.optInt("listStaggerMs", 24)?.coerceIn(0, 80),
        listSlideDp = fx?.takeIf { it.has("listSlideDp") }?.optDouble("listSlideDp", 36.0)?.toFloat()?.coerceIn(0f, 80f),
        originExpand = fx?.takeIf { it.has("originExpand") }?.optBoolean("originExpand"),
        originRipple = fx?.takeIf { it.has("originRipple") }?.optBoolean("originRipple"),
        originInherit = fx?.takeIf { it.has("originInherit") }?.optBoolean("originInherit"),
        originCornerDp = fx?.takeIf { it.has("originCornerDp") }?.optDouble("originCornerDp", 18.0)?.toFloat()?.coerceIn(0f, 36f),
        selectCoverScale = fx?.takeIf { it.has("selectCoverScale") }?.optDouble("selectCoverScale", 1.09)?.toFloat()?.coerceIn(1f, 1.25f),
        selectFontScale = fx?.takeIf { it.has("selectFontScale") }?.optDouble("selectFontScale", 1.05)?.toFloat()?.coerceIn(1f, 1.18f),
        geom = parseGeomReveal(fx),
    )
}
