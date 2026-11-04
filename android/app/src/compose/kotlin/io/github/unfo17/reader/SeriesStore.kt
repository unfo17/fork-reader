package io.github.unfo17.reader

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Durable original references, mirroring lib/local_book_series.dart; no rewritten original EPUBs. */
data class SeriesVolume(val bookId: String, val title: String, val chapters: Map<String, Int>) {
    fun json() = JSONObject().put("bookId", bookId).put("title", title).put("chapters", JSONObject(chapters))
}
data class BookSeries(val id: String, val title: String, val author: String, val summary: String, val tags: List<String>, val volumes: List<SeriesVolume>, val nextChapterId: Int, val at: Long, val deleted: Boolean = false) {
    fun json() = JSONObject().put("id", id).put("title", title).put("author", author).put("summary", summary).put("tags", JSONArray(tags)).put("volumes", JSONArray(volumes.map { it.json() })).put("nextChapterId", nextChapterId).put("at", at).put("deleted", deleted)
}
class SeriesStore(private val read: () -> String?, private val write: (String) -> Boolean) {
    companion object {
        private fun integer(json: JSONObject, key: String, maximum: Long = Long.MAX_VALUE - 1): Long {
            val value = json.get(key)
            require((value is Int || value is Long) && (value as Number).toLong() in 0..maximum) { "分卷数值不正确" }
            return value.toLong()
        }
        fun decode(raw: String): List<BookSeries> {
            require(raw.toByteArray().size <= 4 * 1024 * 1024) { "分卷记录超过 4MiB" }
            val json = JSONObject(raw); require(json.getInt("version") == 1) { "分卷记录版本不支持" }
            val values = json.getJSONArray("series"); require(values.length() <= 2000) { "合订本过多" }
            val identities = mutableSetOf<String>(); val owners = mutableSetOf<String>()
            val result = List(values.length()) { index ->
                val item = values.getJSONObject(index); val id = item.getString("id"); val title = item.getString("title"); val author = item.getString("author"); val summary = item.getString("summary")
                val next = integer(item, "nextChapterId", Int.MAX_VALUE.toLong()).toInt(); val at = integer(item, "at"); val deleted = item.getBoolean("deleted")
                require(id.startsWith("series-") && id.length <= 80 && identities.add(id) && title.isNotBlank() && title.length <= 160 && author.length <= 160 && summary.length <= 4000 && next > 0 && at >= 0) { "分卷元数据不正确" }
                val volumes = item.getJSONArray("volumes"); require(volumes.length() in 2..2000) { "合订本需要 2–2000 卷" }
                val members = mutableSetOf<String>(); val chapters = mutableSetOf<Int>()
                val parts = List(volumes.length()) { position ->
                    val volume = volumes.getJSONObject(position); val bookId = volume.getString("bookId"); val name = volume.getString("title"); val mappings = volume.getJSONObject("chapters")
                    require(bookId.matches(Regex("[a-f0-9]{64}")) && members.add(bookId) && (deleted || owners.add(bookId)) && name.isNotBlank() && name.length <= 160 && mappings.length() in 1..100000) { "分卷顺序或归属不正确" }
                    val mapped = mappings.keys().asSequence().associateWith { href ->
                        val chapter = integer(mappings, href, Int.MAX_VALUE.toLong()).toInt()
                        require(href.isNotBlank() && href.length <= 4096 && chapter in 1 until next && chapters.add(chapter) && chapters.size <= 100000) { "分卷章节坐标不正确" }
                        chapter
                    }
                    SeriesVolume(bookId, name, mapped)
                }
                val tags = item.getJSONArray("tags"); require(tags.length() <= 100) { "标签过多" }
                BookSeries(id, title.trim(), author.trim(), summary.trim(), List(tags.length()) { tags.getString(it).also { tag -> require(tag.length <= 100) { "标签过长" } } }, parts, next, at, deleted)
            }
            require(owners.none { it in identities }) { "合订本不能嵌套" }
            return result
        }
        fun encode(series: List<BookSeries>) = JSONObject().put("version", 1).put("series", JSONArray(series.map { it.json() })).toString()
    }
    @Synchronized fun definitions(): List<BookSeries> = read()?.let(::decode) ?: emptyList()
    @Synchronized fun snapshot() = encode(definitions())
    @Synchronized fun get(id: String) = definitions().firstOrNull { it.id == id }
    private fun commit(values: List<BookSeries>) { val raw = encode(values); decode(raw); check(write(raw)) { "分卷保存失败，原书和坐标保留" } }
    @Synchronized fun save(id: String?, title: String, parts: List<Triple<Book, String, List<String>>>, expectedAt: Long? = null, author: String? = null, summary: String? = null, tags: List<String>? = null): BookSeries {
        val all = definitions(); val old = id?.let { identity -> all.firstOrNull { it.id == identity } }
        require(id == null || old != null && !old.deleted && old.at == expectedAt) { "合订本已变化，请重新打开管理分卷" }
        require(parts.size in 2..2000 && parts.map { it.first.id }.distinct().size == parts.size) { "请选择至少两本不同的原书" }
        parts.forEach { (book, _, _) -> require(!book.removed && all.none { !it.deleted && it.id != id && it.volumes.any { volume -> volume.bookId == book.id } }) { "《${book.title}》已属于其他合订本或已移除" } }
        var next = old?.nextChapterId ?: 1
        val volumes = parts.map { (book, label, hrefs) ->
            val previous = old?.volumes?.firstOrNull { it.bookId == book.id }?.chapters.orEmpty()
            SeriesVolume(book.id, label.trim(), hrefs.distinct().associateWith { href -> previous[href] ?: next.also { require(it < Int.MAX_VALUE) { "分卷章节数量过多" }; next++ } })
        }
        val first = parts.first().first
        val changed = BookSeries(id ?: "series-${UUID.randomUUID()}", title.trim(), author ?: old?.author ?: first.author, summary ?: old?.summary ?: first.summary, tags ?: old?.tags ?: first.tags, volumes, next, maxOf(System.currentTimeMillis(), (old?.at ?: 0) + 1))
        commit(all.filterNot { it.id == changed.id } + changed); return changed
    }
    @Synchronized fun dissolve(id: String) {
        val all = definitions(); val current = all.firstOrNull { it.id == id } ?: return
        if (!current.deleted) commit(all.map { if (it.id == id) it.copy(deleted = true, at = maxOf(System.currentTimeMillis(), it.at + 1)) else it })
    }
    @Synchronized fun merged(incoming: String): String {
        val result = definitions().associateBy { it.id }.toMutableMap()
        decode(incoming).forEach { item ->
            val previous = result[item.id]
            if (previous == null || item.at > previous.at || item.at == previous.at && (item.deleted && !previous.deleted || item.deleted == previous.deleted && item.json().toString() > previous.json().toString())) result[item.id] = item
        }
        return encode(result.values.toList()).also { decode(it) }
    }
}
