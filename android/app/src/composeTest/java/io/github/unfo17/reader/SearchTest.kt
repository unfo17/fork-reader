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

@RunWith(AndroidJUnit4::class)
class SearchTest {
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
