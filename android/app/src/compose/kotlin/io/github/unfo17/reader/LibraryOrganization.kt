package io.github.unfo17.reader

import org.json.JSONObject
import java.util.UUID

/** Port of local_library_preferences.dart: stable groups, tombstones and atomic assignments. */
class LibraryOrganization(private val read: () -> String?, private val write: (String) -> Boolean) {
    companion object { const val ALL = "_all"; const val UNFILED = "_unfiled" }
    private val revisions = mutableMapOf<String, Long>()
    private var revision = 0L
    private fun state() = read()?.let(::JSONObject) ?: JSONObject().put("version", 1).put("groups", JSONObject()).put("assignments", JSONObject())
    @Synchronized fun snapshot(): String = state().toString()
    @Synchronized fun groups(): Map<String, String> = state().getJSONObject("groups").let { groups ->
        groups.keys().asSequence().filter { !groups.getJSONObject(it).optBoolean("deleted") }.associateWith { groups.getJSONObject(it).getString("name") }
    }
    @Synchronized fun resolvedGroups(): Map<String, String> {
        val state = state(); val groups = state.getJSONObject("groups"); val assignments = state.getJSONObject("assignments")
        return assignments.keys().asSequence().associateWith { id ->
            assignments.getJSONObject(id).getString("group").takeIf { groups.optJSONObject(it)?.optBoolean("deleted") == false } ?: UNFILED
        }
    }
    @Synchronized fun groupOf(bookId: String): String = resolvedGroups()[bookId] ?: UNFILED
    @Synchronized fun revisionOf(bookId: String): Long = revisions[bookId] ?: 0L
    fun nameOf(group: String): String = when (group) { ALL -> "全部书籍"; UNFILED -> "未分类"; else -> groups()[group] ?: "未分类" }
    private fun stamp(state: JSONObject): Long {
        val next = maxOf(System.currentTimeMillis(), state.optLong("clock") + 1)
        state.put("clock", next); return next
    }
    private fun save(state: JSONObject) { check(write(state.toString())) { "分类保存失败，书库未改动" } }
    private fun checkedName(name: String, groups: JSONObject, except: String? = null): String {
        val value = name.trim()
        require(value.isNotEmpty() && value.codePointCount(0, value.length) <= 30) { "分类名称须为 1–30 个字" }
        require(groups.keys().asSequence().none { it != except && !groups.getJSONObject(it).optBoolean("deleted") && groups.getJSONObject(it).getString("name") == value }) { "分类名称已存在" }
        return value
    }
    @Synchronized fun create(name: String): String {
        val state = state(); val groups = state.getJSONObject("groups")
        val checked = checkedName(name, groups)
        require(groups.keys().asSequence().count { !groups.getJSONObject(it).optBoolean("deleted") } < 100 && groups.length() < 1000) { "分类数量已达上限" }
        val id = "g${UUID.randomUUID()}"
        groups.put(id, JSONObject().put("name", checked).put("at", stamp(state)).put("deleted", false)); save(state)
        return id
    }
    @Synchronized fun rename(id: String, name: String) {
        val state = state(); val groups = state.getJSONObject("groups")
        val checked = checkedName(name, groups, id)
        require(groups.optJSONObject(id)?.optBoolean("deleted") == false) { "分类已不存在" }
        groups.getJSONObject(id).put("name", checked).put("at", stamp(state)); save(state)
    }
    @Synchronized fun remove(id: String) {
        val state = state(); val group = state.getJSONObject("groups").optJSONObject(id) ?: return
        group.put("deleted", true).put("at", stamp(state)); save(state)
        state.getJSONObject("assignments").let { assignments -> assignments.keys().forEach { if (assignments.getJSONObject(it).optString("group") == id) revisions[it] = ++revision } }
    }
    @Synchronized fun assign(ids: Set<String>, group: String, expected: Map<String, Long>? = null): Set<String> {
        val state = state()
        require(group == UNFILED || state.getJSONObject("groups").optJSONObject(group)?.optBoolean("deleted") == false) { "目标分类已不存在" }
        val changed = ids.filter { expected == null || expected[it] == revisionOf(it) }.toSet()
        if (changed.isEmpty()) return emptySet()
        val at = stamp(state); val assignments = state.getJSONObject("assignments")
        changed.forEach { assignments.put(it, JSONObject().put("group", group).put("at", at)) }
        save(state); changed.forEach { revisions[it] = ++revision }; return changed
    }
    /** Older migration builds used category labels on books. Import once, without changing book identity. */
    @Synchronized fun migrateLegacy(books: List<Book>) {
        if (read() != null) return
        val state = state(); val groups = state.getJSONObject("groups"); val assignments = state.getJSONObject("assignments")
        val names = books.map { it.category }.filter { it.isNotBlank() && it != "未分类" }.distinct()
        require(names.size <= 100) { "旧书库分类过多" }
        names.forEach { name ->
            val id = "g${UUID.randomUUID()}"
            groups.put(id, JSONObject().put("name", name).put("at", stamp(state)).put("deleted", false))
            books.filter { it.category == name }.forEach { assignments.put(it.id, JSONObject().put("group", id).put("at", stamp(state))) }
        }
        save(state)
    }
    /** Backups from the first native builds contain labels only. Existing assignments always win. */
    @Synchronized fun mergedLegacy(books: List<Book>): String {
        val state = state(); val groups = state.getJSONObject("groups"); val assignments = state.getJSONObject("assignments")
        val knownNames = groups.keys().asSequence().filter { !groups.getJSONObject(it).optBoolean("deleted") }.associateBy { groups.getJSONObject(it).getString("name") }
        books.filter { !assignments.has(it.id) && it.category.isNotBlank() && it.category != "未分类" }.forEach { book ->
            require(book.category.codePointCount(0, book.category.length) <= 30) { "旧备份分类名称过长" }
            val id = knownNames[book.category] ?: "glegacy-" + java.security.MessageDigest.getInstance("SHA-256").digest(book.category.toByteArray()).joinToString("") { "%02x".format(it) }
            if (!groups.has(id)) groups.put(id, JSONObject().put("name", book.category).put("at", 0).put("deleted", false))
            assignments.put(book.id, JSONObject().put("group", id).put("at", 0))
        }
        return merged(state.toString())
    }
    /** Pure merge permits backup validation before any records or files are committed. */
    @Synchronized fun merged(incoming: String): String {
        val other = JSONObject(incoming)
        require(other.getInt("version") == 1) { "分类备份版本不支持" }
        val current = state()
        for (kind in listOf("groups", "assignments")) {
            val source = other.getJSONObject(kind); val target = current.getJSONObject(kind)
            require(source.length() <= if (kind == "groups") 1000 else 10000) { "分类备份记录过多" }
            source.keys().forEach { id ->
                val item = source.getJSONObject(id)
                require(item.get("at") is Number && item.getDouble("at").isFinite() && item.getDouble("at") == item.getLong("at").toDouble() && item.getLong("at") >= 0) { "分类时间不正确" }
                if (kind == "groups") {
                    require(id.startsWith("g") && id.length <= 80 && (!item.has("deleted") || item.get("deleted") is Boolean) && item.getString("name").isNotBlank() && item.getString("name").codePointCount(0, item.getString("name").length) <= 30) { "分类备份名称不正确" }
                } else require(id.length in 1..128 && (item.getString("group") == UNFILED || item.getString("group").startsWith("g") && item.getString("group").length <= 80)) { "分类备份关联不正确" }
                current.put("clock", maxOf(current.optLong("clock"), item.getLong("at")))
                val existing = target.optJSONObject(id)
                // On equal timestamps, deletion wins; otherwise compare canonical data deterministically.
                if (existing == null || item.getLong("at") > existing.getLong("at") || item.getLong("at") == existing.getLong("at") && (item.optBoolean("deleted") && !existing.optBoolean("deleted") || item.optBoolean("deleted") == existing.optBoolean("deleted") && (if (kind == "groups") item.getString("name") > existing.getString("name") else item.getString("group") > existing.getString("group")))) target.put(id, item)
            }
        }
        require(current.getJSONObject("groups").length() <= 1000 && current.getJSONObject("assignments").length() <= 10000) { "分类记录合并后过多" }
        current.put("clock", maxOf(current.optLong("clock"), other.optLong("clock"), System.currentTimeMillis()))
        return current.toString()
    }
    @Synchronized fun invalidateAssignments() { state().getJSONObject("assignments").keys().forEach { revisions[it] = ++revision } }
}
