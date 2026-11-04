package io.github.unfo17.reader

import org.json.JSONObject

/** Reader layout fields preserve the names/units of lib/reader_preferences.dart where possible. */
data class ReaderOptions(
    val fontSize: Float = 100f, val night: Boolean = false, val scroll: Boolean = true,
    val lineHeight: Float = 1.8f, val letterSpacing: Float = 0.1f,
    val paragraphGap: Float = 12f, val margin: Float = 26f,
    val indent: Boolean = false, val justify: Boolean = false,
    val publisherStyles: Boolean = true, val vertical: Boolean = false,
    val twoPages: Boolean = false, val weight: Float = 1f,
    val keepScreenOn: Boolean = false, val volumePaging: Boolean = false,
    val hideSystemStatus: Boolean = false, val brightness: Float = -1f,
    val textColor: String = "", val backgroundColor: String = "", val fontId: String = ""
) {
    fun json(): String = JSONObject().put("fontSize", fontSize).put("night", night).put("scroll", scroll)
        .put("lineHeight", lineHeight).put("letterSpacing", letterSpacing).put("paragraphGap", paragraphGap).put("margin", margin)
        .put("indent", indent).put("justify", justify).put("publisherStyles", publisherStyles).put("vertical", vertical)
        .put("twoPages", twoPages).put("weight", weight).put("keepScreenOn", keepScreenOn).put("volumePaging", volumePaging)
        .put("hideSystemStatus", hideSystemStatus).put("brightness", brightness).put("textColor", textColor)
        .put("backgroundColor", backgroundColor).put("fontId", fontId).toString()
    companion object {
        fun decode(raw: String?): ReaderOptions {
            val j = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return ReaderOptions()
            fun number(key: String, fallback: Float, min: Float, max: Float): Float =
                j.optDouble(key, fallback.toDouble()).toFloat().let { if (it.isFinite()) it.coerceIn(min, max) else fallback }
            return ReaderOptions(number("fontSize", 100f, 50f, 300f), j.optBoolean("night"), j.optBoolean("scroll", true),
                number("lineHeight", 1.8f, 1f, 3f), number("letterSpacing", .1f, 0f, 3f),
                number("paragraphGap", 12f, 0f, 32f), number("margin", 26f, 0f, 80f),
                j.optBoolean("indent"), j.optBoolean("justify"), j.optBoolean("publisherStyles", true), j.optBoolean("vertical"),
                j.optBoolean("twoPages"), number("weight", 1f, .5f, 2f), j.optBoolean("keepScreenOn"), j.optBoolean("volumePaging"),
                j.optBoolean("hideSystemStatus"), number("brightness", -1f, -1f, 1f), j.optString("textColor"), j.optString("backgroundColor"), j.optString("fontId"))
        }
    }
}
