package io.github.unfo17.reader

import java.util.Locale

/** Metadata search contract ported from lib/book_search.dart. */
enum class BookSearchField(val label: String) { ALL("全部"), TITLE("书名"), AUTHOR("作者"), TAGS("标签"), ID("书籍 ID") }
class BookQuery(query: String, private val field: BookSearchField = BookSearchField.ALL) {
    companion object {
        private fun width(value: String) = buildString { value.forEach { append(when (it.code) { in 0xff01..0xff5e -> (it.code - 0xfee0).toChar(); 0x3000 -> ' '; else -> it }) } }
        private val spacing = Regex("[\\s·・_\\-—–，,。.:：()（）\\[\\]【】「」『』《》]")
        private fun normalize(value: String) = width(value).lowercase(Locale.ROOT).replace(spacing, "")
        fun author(value: String) = value.trim().replace(Regex("\\s+"), " ").takeUnless { it.isEmpty() || it == "本地书籍" } ?: "未知作者"
    }
    private val terms = width(query).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.startsWith('#') to normalize(it.removePrefix("#")) }
    fun matches(book: Book): Boolean {
        val tags = normalize((book.tags + book.category).joinToString(" "))
        val fields = when (field) {
            BookSearchField.TITLE -> listOf(normalize(book.title))
            BookSearchField.AUTHOR -> listOf(normalize(book.author))
            BookSearchField.TAGS -> listOf(tags)
            BookSearchField.ID -> listOf(book.id)
            BookSearchField.ALL -> listOf(normalize(book.title), normalize(book.author), tags, normalize(book.summary), book.id)
        }
        return terms.all { (tag, term) -> term.isNotEmpty() && if (field == BookSearchField.ID) book.id == term else if (tag) term in tags else fields.any { term in it } }
    }
}

enum class LibrarySort(val label: String) { RECENT_ADDED("最近添加"), RECENT_READ("最近阅读"), TITLE("按书名") }
enum class LibraryView(val label: String) { SHELF("立体书架 + 列表"), LIST("纯列表"), GRID("纯宫格"), SHELF_GRID("立体书架 + 宫格"); val grid get() = this == GRID || this == SHELF_GRID; val shelf get() = this == SHELF || this == SHELF_GRID }
data class LibraryFilter(val query: String = "", val field: BookSearchField = BookSearchField.ALL, val category: String = LibraryOrganization.ALL, val author: String? = null, val reading: String = "all", val source: String = "all", val sort: LibrarySort = LibrarySort.RECENT_ADDED) {
    fun apply(books: List<Book>, organization: LibraryOrganization): List<Book> {
        val search = BookQuery(query, field)
        val assignments = if (category == LibraryOrganization.ALL) emptyMap() else organization.resolvedGroups()
        return books.filter { book ->
            (category == LibraryOrganization.ALL || (assignments[book.id] ?: LibraryOrganization.UNFILED) == category) &&
                (author == null || BookQuery.author(book.author) == author) && search.matches(book) &&
                (reading == "all" || effectiveReadingState(book) == reading) &&
                when (source) { "linked" -> book.sourceReference != null; "copied" -> book.sourceReference == null; "archive" -> book.archiveEntry != null; else -> true }
        }.let { result -> when (sort) {
            LibrarySort.RECENT_ADDED -> result.sortedWith(compareByDescending<Book> { it.addedAt }.thenBy { it.title })
            LibrarySort.RECENT_READ -> result.sortedWith(compareByDescending<Book> { it.lastRead }.thenBy { it.title })
            LibrarySort.TITLE -> result.sortedBy { it.title }
        } }
    }
}
fun effectiveReadingState(book: Book): String = if (book.status != "auto") book.status else when { book.progress >= .999 -> "finished"; book.lastRead > 0 || book.progress > 0 -> "reading"; else -> "unread" }
