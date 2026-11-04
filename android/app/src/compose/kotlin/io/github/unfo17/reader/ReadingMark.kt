package io.github.unfo17.reader

import android.os.Parcel
import android.os.Parcelable
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.navigator.html.toCss
import org.readium.r2.shared.publication.Locator
import java.util.UUID

/** Upstream annotation metadata, anchored by Readium's source locator instead of screen geometry. */
enum class MarkStyle(val label: String) { UNDERLINE("划线"), HIGHLIGHT("高亮"), OUTLINE("边框"), NONE("无样式") }
enum class MarkColor(val label: String, val argb: Int?) {
    INK("原色", null), AMBER("琥珀", 0xFFC69A32.toInt()), MINT("薄荷", 0xFF4A9C83.toInt()), BLUE("湖蓝", 0xFF5289BB.toInt()), ROSE("蔷薇", 0xFFC87386.toInt()), VIOLET("紫藤", 0xFF9773B7.toInt())
}
data class ReadingMark(
    val id: String, val locator: String, val quote: String, val style: MarkStyle, val color: MarkColor = MarkColor.INK,
    val note: String = "", val category: String = "", val createdAt: Long, val createdOffsetMinutes: Int,
    val updatedAt: Long, val categoryAt: Long = updatedAt, val rangeUpdatedAt: Long = updatedAt,
    val stateAt: Long = updatedAt, val deleted: Boolean = false
) {
    val revision get() = maxOf(updatedAt, categoryAt, rangeUpdatedAt, stateAt)
    fun json() = JSONObject().put("id", id).put("locator", JSONObject(locator)).put("quote", quote).put("style", style.name).put("color", color.name).put("note", note).put("category", category)
        .put("createdAt", createdAt).put("createdOffsetMinutes", createdOffsetMinutes).put("updatedAt", updatedAt).put("categoryAt", categoryAt).put("rangeUpdatedAt", rangeUpdatedAt).put("stateAt", stateAt).put("deleted", deleted)
    fun sameAnchor(other: ReadingMark): Boolean {
        val a = JSONObject(locator); val b = JSONObject(other.locator)
        if (a.getString("href") != b.getString("href")) return false
        val left = a.optJSONObject("locations"); val right = b.optJSONObject("locations")
        val ar = left?.optJSONObject("forkSourceRange"); val br = right?.optJSONObject("forkSourceRange")
        fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString("|", "{", "}") { key -> key + ":" + canonical(value.get(key)) }
            else -> value.toString()
        }
        if (ar != null && br != null) return canonical(ar) == canonical(br)
        val at = a.optJSONObject("text"); val bt = b.optJSONObject("text")
        if (at?.optString("highlight").isNullOrBlank() || bt?.optString("highlight").isNullOrBlank()) return locator == other.locator
        return listOf("highlight", "before", "after").all { at?.optString(it).orEmpty() == bt?.optString(it).orEmpty() } &&
            (left?.optString("cssSelector").isNullOrBlank() || right?.optString("cssSelector").isNullOrBlank() || left?.optString("cssSelector") == right?.optString("cssSelector"))
    }
    fun decoration(ink: Int): Decoration? {
        if (deleted || style == MarkStyle.NONE) return null
        val location = Locator.fromJSON(JSONObject(locator)) ?: return null
        val tint = color.argb ?: ink
        val appearance = when (style) { MarkStyle.UNDERLINE -> Decoration.Style.Underline(tint); MarkStyle.HIGHLIGHT -> Decoration.Style.Highlight(tint); MarkStyle.OUTLINE -> OutlineStyle(tint); MarkStyle.NONE -> return null }
        return Decoration(id, location, appearance)
    }
    companion object {
        fun create(locator: Locator, style: MarkStyle): ReadingMark {
            val now = System.currentTimeMillis()
            return ReadingMark(UUID.randomUUID().toString(), locator.toJSON().toString(), locator.text.highlight.orEmpty(), style, createdAt = now,
                createdOffsetMinutes = java.util.TimeZone.getDefault().getOffset(now) / 60000, updatedAt = now).also { from(it.json()) }
        }
        fun from(json: JSONObject): ReadingMark {
            val location = json.getJSONObject("locator").toString()
            require(location.length <= 262144 && Locator.fromJSON(JSONObject(location)) != null) { "批注位置不正确" }
            val mark = ReadingMark(json.getString("id"), location, json.getString("quote"), MarkStyle.valueOf(json.getString("style")), MarkColor.valueOf(json.getString("color")), json.optString("note"), json.optString("category"),
                json.getLong("createdAt"), json.getInt("createdOffsetMinutes"), json.getLong("updatedAt"), json.getLong("categoryAt"), json.getLong("rangeUpdatedAt"), json.getLong("stateAt"), json.getBoolean("deleted"))
            require(mark.id.length in 1..128 && mark.quote.length <= 1000000 && mark.note.length <= 100000 && mark.category.length <= 100 && mark.createdOffsetMinutes in -1080..1080 &&
                listOf(mark.createdAt, mark.updatedAt, mark.categoryAt, mark.rangeUpdatedAt, mark.stateAt).all { it in 0..1000000000000000L }) { "批注内容超过限制或日期不正确" }
            return mark
        }
        fun merge(a: ReadingMark, b: ReadingMark): ReadingMark {
            require(a.id == b.id)
            val annotation = listOf(a, b).maxWith(compareBy<ReadingMark> { it.updatedAt }.thenBy { "${it.style.name}|${it.color.name}|${it.note}" })
            val category = listOf(a, b).maxWith(compareBy<ReadingMark> { it.categoryAt }.thenBy { it.category })
            val range = listOf(a, b).maxWith(compareBy<ReadingMark> { it.rangeUpdatedAt }.thenBy { it.locator + it.quote })
            val state = listOf(a, b).maxWith(compareBy<ReadingMark> { it.stateAt }.thenBy { it.deleted })
            val created = listOf(a, b).minWith(compareBy<ReadingMark> { it.createdAt }.thenBy { it.createdOffsetMinutes })
            return annotation.copy(locator = range.locator, quote = range.quote, rangeUpdatedAt = range.rangeUpdatedAt, category = category.category, categoryAt = category.categoryAt,
                stateAt = state.stateAt, deleted = state.deleted, createdAt = created.createdAt, createdOffsetMinutes = created.createdOffsetMinutes)
        }
        fun mergeLists(a: List<ReadingMark>, b: List<ReadingMark>): List<ReadingMark> {
            val result = a.associateBy { it.id }.toMutableMap()
            b.forEach { mark -> result[mark.id] = result[mark.id]?.let { merge(it, mark) } ?: mark }
            require(result.size <= 10000) { "合并后的批注记录过多" }
            return result.values.toList()
        }
    }
}

/** Readium style is Parcelable because the navigator may save its state. */
data class OutlineStyle(override val tint: Int) : Decoration.Style, Decoration.Style.Tinted {
    override fun writeToParcel(destination: Parcel, flags: Int) { destination.writeInt(tint) }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<OutlineStyle> {
        override fun createFromParcel(source: Parcel) = OutlineStyle(source.readInt())
        override fun newArray(size: Int): Array<OutlineStyle?> = arrayOfNulls(size)
    }
}
fun annotationTemplates() = HtmlDecorationTemplates.defaultTemplates().apply {
    this[OutlineStyle::class] = HtmlDecorationTemplate(layout = HtmlDecorationTemplate.Layout.BOXES, element = { decoration ->
        val color = (decoration.style as OutlineStyle).tint.toCss()
        "<div class=\"fork-reader-outline\" style=\"border-color:$color\"></div>"
    }, stylesheet = ".fork-reader-outline{border:1.5px solid;border-radius:3px;box-sizing:border-box;}")
}
