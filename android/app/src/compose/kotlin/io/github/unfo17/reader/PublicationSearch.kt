@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package io.github.unfo17.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content

/** One forward iterator across originals. Paging resumes within a paragraph, without rescanning. */
class PublicationSearch(private val library: Library, private val books: List<Book>, query: String, private val chapterHref: String? = null, private val chapterLocator: Locator? = null) : AutoCloseable {
    data class Hit(val bookId: String, val bookTitle: String, val locator: Locator, val snippet: String, val offset: Int)
    data class Page(val hits: List<Hit>, val finished: Boolean)
    private val term = query.trim().also { require(it.isNotEmpty() && it.length <= 120) { "搜索文字需要 1–120 个字符" } }
    private val mutex = Mutex()
    private val lifecycleLock = Any()
    @Volatile private var closed = false
    @Volatile private var publication: Publication? = null
    private var volume = 0
    private var current: Book? = null
    private var iterator: Content.Iterator? = null
    private var pending: Content.TextualElement? = null
    private var offset = 0
    private var chapterWindow: ChapterSearchWindow? = null
    private var pendingWindow: ChapterSearchWindow.Window? = null
    private var finished = false
    suspend fun nextPage(size: Int = 50): Page = mutex.withLock { withContext(Dispatchers.IO) {
        require(size in 1..100)
        check(!closed) { "搜索已结束" }
        val hits = mutableListOf<Hit>()
        while (hits.size < size && !finished) {
            currentCoroutineContext().ensureActive()
            check(!closed) { "搜索已关闭" }
            val paragraph = pending
            if (paragraph != null) {
                val text = paragraph.text.orEmpty()
                val start = text.indexOf(term, offset, ignoreCase = true)
                if (start < 0 || start + term.length > (pendingWindow?.end ?: text.length)) { pending = null; continue }
                val end = start + term.length
                offset = end
                val before = suffix(paragraph.locator.text.before.orEmpty() + text.substring(0, start), 80)
                val after = prefix(text.substring(end) + paragraph.locator.text.after.orEmpty(), 100)
                val match = text.substring(start, end)
                val locator = paragraph.locator.copy(text = Locator.Text(before, match, after))
                val snippet = pendingWindow?.let { window -> suffix(text.substring(window.start, start), 80) + match + prefix(text.substring(end, window.end), 100) } ?: (before + match + after)
                hits += Hit(current!!.id, current!!.title, locator, snippet, start)
                continue
            }
            if (iterator == null) {
                if (volume >= books.size) { finished = true; break }
                val book = books[volume]
                val opened = library.openPublication(book)
                if (closed) { opened.close(); error("搜索已关闭") }
                val content = try {
                    val link = chapterHref?.let { href -> opened.readingOrder.firstOrNull { resource(it.href.toString()) == resource(href) } }
                    require(chapterHref == null || link != null) { "当前章节已不在原书中" }
                    chapterWindow = chapterHref?.let { ChapterSearchWindow.create(opened, it, chapterLocator) }
                    opened.content(link?.let { opened.locatorFromLink(it) })?.iterator() ?: error("《${book.title}》无法提取正文")
                } catch (e: Exception) { opened.close(); throw e }
                synchronized(lifecycleLock) {
                    if (closed) { opened.close(); error("搜索已关闭") }
                    publication = opened; current = book; iterator = content; volume++
                }
            }
            val element = iterator!!.nextOrNull()
            if (element == null || chapterHref != null && resource(element.locator.href.toString()) != resource(chapterHref)) {
                publication?.close(); publication = null; iterator = null; continue
            }
            if (element is Content.TextualElement && !element.text.isNullOrEmpty()) {
                pendingWindow = chapterWindow?.window(element)
                if (pendingWindow?.let { it.start >= it.end } == true) continue
                pending = element; offset = pendingWindow?.start ?: 0
            }
        }
        Page(hits, finished)
    } }
    override fun close() { synchronized(lifecycleLock) { closed = true; publication?.close(); publication = null } }
    companion object {
        private fun resource(href: String) = href.substringBefore('#').substringBefore('?')
        private fun suffix(value: String, length: Int): String {
            var start = (value.length - length).coerceAtLeast(0)
            if (start > 0 && start < value.length && Character.isLowSurrogate(value[start]) && Character.isHighSurrogate(value[start - 1])) start++
            return value.substring(start)
        }
        private fun prefix(value: String, length: Int): String {
            var end = minOf(length, value.length)
            if (end < value.length && end > 0 && Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) end--
            return value.substring(0, end)
        }
    }
}
