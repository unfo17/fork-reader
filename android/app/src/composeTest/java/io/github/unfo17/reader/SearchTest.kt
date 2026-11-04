@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package io.github.unfo17.reader

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

@RunWith(AndroidJUnit4::class)
class SearchTest {
    @Test fun chapterSearchUsesSourceAnchorsAndClipsMidParagraphBoundaries() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext; val library = Library.get(context)
        val source = File(context.cacheDir, "search-anchors.epub")
        ZipOutputStream(source.outputStream()).use { zip ->
            fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("content.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">anchors-${System.nanoTime()}</dc:identifier><dc:title>目录锚点搜索</dc:title><dc:language>zh</dc:language><meta property="dcterms:modified">2026-11-04T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="body" href="body.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="body"/></spine></package>""")
            entry("nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol><li><a href="body.xhtml#a">第一节</a></li><li><a href="body.xhtml#%E7%AC%AC%E4%BA%8C%E8%8A%82">第二节</a></li><li><a href="body.xhtml#c">第三节</a></li></ol></nav></body></html>""")
            entry("body.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>同文件多章节</title></head><body><h1 id="a">第一节</h1><p id="shared">needle 第一节 交界<span id="第二节"></span>词语 needle 第二节 😀 needle</p><p id="breaks">第二节 needle<br/>还有 NEEDLE</p><div id="nested">needle 重复<section>needle 重复</section><span id="c"></span>needle 重复</div><h2>第三节</h2><p>第三节 needle</p></body></html>""")
        }
        val book = library.import(Uri.fromFile(source))
        try {
            val publication = library.openPublication(book)
            val href = publication.readingOrder.single().href.toString()
            val second = publication.tableOfContents[1].href.toString()
            val third = publication.tableOfContents[2].href.toString()
            val base = publication.locatorFromLink(publication.readingOrder.single())!!.toJSON()
            publication.close()
            suspend fun search(term: String, anchor: String, locator: Locator? = null): List<PublicationSearch.Hit> {
                val hits = mutableListOf<PublicationSearch.Hit>()
                PublicationSearch(library, listOf(book), term, anchor, locator).use { cursor ->
                    do { val page = cursor.nextPage(1); hits += page.hits; val done = page.finished } while (!done)
                }
                return hits
            }
            assertEquals(1, search("needle", href, Locator.fromJSON(JSONObject(base.toString()).apply { getJSONObject("locations").put("progression", 0.0) })).size)
            val hits = search("needle", second)
            assertEquals(6, hits.size)
            assertTrue(hits.none { it.snippet.contains("第一节") || it.snippet.contains("第三节") })
            assertEquals(2, search("needle", third).size)
            assertTrue(search("交界词语", second).isEmpty()) // A query spanning the chapter boundary is excluded.
            val current = JSONObject(base.toString()).apply {
                getJSONObject("locations").put("cssSelector", "#breaks")
                put("text", JSONObject().put("highlight", "第二节 needle"))
            }
            assertEquals(6, search("needle", href, Locator.fromJSON(current)).size)
            val sourcePoint = JSONObject(base.toString()).apply {
                getJSONObject("locations").put("forkSourceRange", JSONObject().put("start", JSONObject().put("selector", "#shared").put("childNodeIndex", 2).put("offset", 3)))
            }
            assertEquals(6, search("needle", href, Locator.fromJSON(sourcePoint)).size)
            assertTrue(runCatching { search("needle", href) }.isFailure)
            PublicationSearch(library, listOf(book), "needle").use { assertEquals(9, it.nextPage().hits.size) }
        } finally { library.books().firstOrNull { it.id == book.id }?.let(library::remove); source.delete() }
    }
    @Test fun searchPagesEveryOccurrenceBeyondOneHundredAndLimitsCurrentChapter() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = Library.get(context)
        val source = File(context.cacheDir, "search-many.txt").apply { writeText("第一章 查找\n" + (1..110).joinToString("\n") { "needle 第 $it 段 😀 and NEEDLE" } + "\n第二章 末尾\nneedle tail needle. ${System.nanoTime()}") }
        val book = library.import(Uri.fromFile(source))
        try {
            val hits = mutableListOf<PublicationSearch.Hit>()
            PublicationSearch(library, listOf(book), "needle").use { cursor ->
                var pages = 0
                do { val page = cursor.nextPage(37); hits += page.hits; pages++; val done = page.finished } while (!done)
                assertTrue(pages > 3)
            }
            assertEquals(222, hits.size)
            assertEquals(222, hits.map { it.locator.toJSON().toString() + ":" + it.offset }.distinct().size)
            assertTrue(hits.any { it.locator.text.highlight == "NEEDLE" })
            assertTrue(hits.all { it.bookId == book.id })
            val publication = library.openPublication(book)
            val href = publication.readingOrder.last().href.toString(); publication.close()
            PublicationSearch(library, listOf(book), "needle", href).use { cursor ->
                val first = cursor.nextPage(50)
                assertEquals(2, first.hits.size); assertTrue(first.finished)
                assertTrue(first.hits.all { it.locator.href.toString() == href })
            }
            val closed = PublicationSearch(library, listOf(book), "needle"); closed.close()
            assertTrue(runCatching { closed.nextPage() }.isFailure)
        } finally { library.books().firstOrNull { it.id == book.id }?.let(library::remove); source.delete() }
    }
    @Test fun seriesSearchResumesAcrossVolumeBoundariesAndSupportsUnicode() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = Library.get(context)
        val sources = listOf(
            File(context.cacheDir, "search-volume-one.txt").apply { writeText("第一章 上卷\n😀 关键词，上卷的关键词。 ${System.nanoTime()}") },
            File(context.cacheDir, "search-volume-two.txt").apply { writeText("第一章 下卷\n下卷的关键词，以及😀。 ${System.nanoTime()}") })
        val books = sources.map { library.import(Uri.fromFile(it)) }
        try {
            val result = mutableListOf<PublicationSearch.Hit>()
            PublicationSearch(library, books, "关键词").use { cursor ->
                do { val page = cursor.nextPage(1); result += page.hits; val done = page.finished } while (!done)
            }
            assertEquals(listOf(books[0].id, books[0].id, books[1].id), result.map { it.bookId })
            PublicationSearch(library, books, "😀").use { cursor ->
                val page = cursor.nextPage(50)
                assertEquals(2, page.hits.size); assertTrue(page.finished)
                assertTrue(page.hits.all { it.locator.text.highlight == "😀" })
            }
            assertTrue(runCatching { PublicationSearch(library, books, "字".repeat(121)) }.isFailure)
        } finally { library.books().filter { b -> b.id in books.map { it.id } }.forEach(library::remove); sources.forEach { it.delete() } }
    }
}
