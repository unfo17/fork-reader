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
class SeriesTest {
    private fun book(char: Char) = Book(char.toString().repeat(64), "分卷$char", "作者", "unused.epub")
    @Test fun seriesChaptersKeepIdentityAcrossReorderAndRejectedDelayedEdits() {
        var stored: String? = null
        val store = SeriesStore({ stored }, { stored = it; true })
        val a = book('a'); val b = book('b'); val c = book('c')
        val first = store.save(null, "合订本", listOf(Triple(a, "第一卷", listOf("a.xhtml", "b.xhtml")), Triple(b, "第二卷", listOf("a.xhtml"))))
        val next = store.save(first.id, "合订本修订", listOf(Triple(b, "第二卷先读", listOf("a.xhtml")), Triple(a, "第一卷", listOf("a.xhtml", "b.xhtml", "c.xhtml"))), first.at)
        assertEquals(first.volumes[1].chapters, next.volumes[0].chapters)
        assertEquals(first.volumes[0].chapters["b.xhtml"], next.volumes[1].chapters["b.xhtml"])
        assertTrue(next.volumes[1].chapters.getValue("c.xhtml") >= first.nextChapterId)
        assertTrue(runCatching { store.save(first.id, "过时保存", listOf(Triple(a, "一", listOf("a.xhtml")), Triple(b, "二", listOf("a.xhtml"))), first.at) }.isFailure)
        assertEquals(next, store.get(first.id))
        assertTrue(runCatching { store.save(null, "重复归属", listOf(Triple(a, "一", listOf("a.xhtml")), Triple(c, "三", listOf("c.xhtml")))) }.isFailure)
        store.dissolve(first.id)
        stored = store.merged(SeriesStore.encode(listOf(first)))
        assertTrue(store.get(first.id)!!.deleted)
        assertEquals(next.volumes, store.get(first.id)!!.volumes)
    }

    @Test fun seriesSuggestionsRequireAnExplicitVolumeMarker() {
        assertNull(SeriesHint.infer("1984")); assertNull(SeriesHint.infer("86"))
        assertEquals(12.0, SeriesHint.infer("测试系列 第十二卷")!!.number, .0)
        assertEquals(3.0, SeriesHint.infer("测试系列 第３卷.epub")!!.number, .0)
        assertEquals(2.5, SeriesHint.infer("测试系列 Vol. 2.5")!!.number, .0)
        assertEquals(SeriesHint.infer("测试系列 第十二卷")!!.key, SeriesHint.infer("测试系列 - 01")!!.key)
    }

    @Test fun failedSeriesWriteRetainsMembershipAndChapterCoordinates() {
        var stored: String? = null; var writable = true
        val store = SeriesStore({ stored }, { if (writable) { stored = it; true } else false })
        val parts = listOf(Triple(book('a'), "一", listOf("a.xhtml")), Triple(book('b'), "二", listOf("b.xhtml")))
        val first = store.save(null, "原始分卷", parts)
        writable = false
        assertTrue(runCatching { store.save(first.id, "修改分卷", parts.reversed(), first.at) }.isFailure)
        assertTrue(runCatching { store.dissolve(first.id) }.isFailure)
        assertEquals(first, store.get(first.id))
    }

    @Test fun duplicateChapterCoordinatesAndSharedVolumeOwnersAreRejected() {
        val parts = listOf(SeriesVolume("a".repeat(64), "一", mapOf("a.xhtml" to 1)), SeriesVolume("b".repeat(64), "二", mapOf("b.xhtml" to 1)))
        val invalid = BookSeries("series-test", "书名", "", "", emptyList(), parts, 3, 0)
        assertTrue(runCatching { SeriesStore.decode(SeriesStore.encode(listOf(invalid))) }.isFailure)
        val valid = invalid.copy(volumes = parts.mapIndexed { i, v -> v.copy(chapters = mapOf("body.xhtml" to i + 1)) })
        assertTrue(runCatching { SeriesStore.decode(SeriesStore.encode(listOf(valid, valid.copy(id = "series-other")))) }.isFailure)
        val malformed = org.json.JSONObject(SeriesStore.encode(listOf(valid)))
        malformed.getJSONArray("series").getJSONObject(0).put("nextChapterId", 3.5)
        assertTrue(runCatching { SeriesStore.decode(malformed.toString()) }.isFailure)
        assertEquals(valid, SeriesStore.decode(SeriesStore.encode(listOf(valid))).single())
    }

    @Test fun originalFilesLocatorsAndBookmarksSurviveSeriesBackupReorderAndDissolution() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = Library.get(context)
        val sources = (1..2).map { number -> File(context.cacheDir, "series-$number.txt").apply { writeText("第一章 开始\n第 $number 卷 ${System.nanoTime()}\n第二章 后续\n正文内容") } }
        val books = sources.map { library.import(Uri.fromFile(it)) }
        val backup = File(context.cacheDir, "series-backup.zip")
        var seriesId: String? = null
        try {
            val publication = library.openPublication(books[0])
            val locator = publication.locatorFromLink(publication.readingOrder.last())!!.toJSON().toString()
            publication.close()
            library.update(books[0].copy(locator = locator, progress = .42, bookmarks = listOf(locator), lastRead = 100))
            val originalBytes = books.map { File(it.file).readBytes() }
            val series = library.saveSeries(null, "分卷备份", books.map { it.id to it.title }, null)
            seriesId = series.id
            assertTrue(library.collectionBooks().any { it.id == series.id })
            assertTrue(library.collectionBooks().none { it.id in books.map { b -> b.id } })
            val changed = library.saveSeries(series.id, series.title, series.volumes.reversed().map { it.bookId to it.title }, series.at)
            assertEquals(series.volumes.first().chapters, changed.volumes.last().chapters)
            books.forEachIndexed { i, book -> assertArrayEquals(originalBytes[i], File(book.file).readBytes()) }
            exportLibrary(context, Uri.fromFile(backup))
            library.remove(library.collectionBooks().first { it.id == series.id })
            assertTrue(books.all { book -> library.records().first { it.id == book.id }.removed })
            assertTrue(sources.all { it.exists() })
            restoreLibrary(context, Uri.fromFile(backup))
            assertTrue(library.collectionBooks().any { it.id == series.id })
            val restored = library.books().first { it.id == books[0].id }
            assertEquals(locator, restored.locator); assertEquals(listOf(locator), restored.bookmarks)
            library.openPublication(restored).close()
            library.series.dissolve(series.id)
            restoreLibrary(context, Uri.fromFile(backup)) // Older backup cannot revive a deliberately dissolved collection.
            assertFalse(library.collectionBooks().any { it.id == series.id })
            assertTrue(library.collectionBooks().any { it.id == books[0].id })
            assertEquals(locator, library.records().first { it.id == books[0].id }.locator)
        } finally {
            seriesId?.let { library.series.dissolve(it) }
            library.books().filter { book -> book.id in books.map { it.id } }.forEach(library::remove)
            sources.forEach { it.delete() }; backup.delete()
        }
    }
}
