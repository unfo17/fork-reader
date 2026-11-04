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
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ArchiveImportTest {
    @Test fun selectedLinkedMembersKeepResourcesAndBackupOriginalArchiveOnce() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val library = Library.get(context)
        val source = File(context.cacheDir, "book-collection.zip")
        val relocated = File(context.cacheDir, "book-collection-moved.zip")
        val backup = File(context.cacheDir, "archive-backup.zip")
        val pixel = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        val image = java.io.ByteArrayOutputStream()
        pixel.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, image); pixel.recycle()
        ZipOutputStream(source.outputStream()).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            entry("folder/novel.html", "<html><head><title>压缩包内的书</title></head><body><h1>第一章</h1><p>图片应离线可见。</p><img src=\"images/cover.png\"/></body></html>".toByteArray())
            entry("folder/images/cover.png", image.toByteArray())
            entry("other/notes.txt", "第一章 笔记\n正文保留。".toByteArray())
            entry("original.epub", instrumentation.context.assets.open("fixture.epub").use { it.readBytes() })
            entry("ignored.dat", byteArrayOf(1, 2, 3))
        }
        val candidates = library.preview(Uri.fromFile(source))
        assertEquals(3, candidates.size)
        val html = library.importCandidate(candidates.first { it.entry!!.name.endsWith(".html") }, copy = false, tree = null)
        val text = library.importCandidate(candidates.first { it.entry!!.name.endsWith(".txt") }, copy = false, tree = null)
        try {
            assertTrue(html.warnings.isEmpty())
            assertEquals(html.sourceReference, text.sourceReference)
            assertFalse(File(html.file).exists())
            ZipFile(library.publicationFile(html)).use { zip -> assertTrue(zip.entries().asSequence().any { it.name.startsWith("image-") }) }
            library.update(html.copy(progress = .37, bookmarks = listOf("{\"txtProgress\":0.37}")))
            source.copyTo(relocated, overwrite = true)
            val linked = library.relink(library.books().first { it.id == html.id }, Uri.fromFile(relocated))
            assertEquals(.37, linked.progress, .001)
            library.openPublication(text).close() // Relinking one member must not revoke another member's source.
            exportLibrary(context, Uri.fromFile(backup))
            ZipFile(backup).use { zip ->
                assertEquals(1, zip.entries().asSequence().count { it.name.startsWith("archive-") })
                val manifest = zip.getInputStream(zip.getEntry("library.json")).bufferedReader().use { it.readText() }
                assertFalse(manifest.contains(source.path)); assertFalse(manifest.contains(relocated.path))
            }
            library.remove(library.books().first { it.id == html.id })
            library.openPublication(text).close()
            library.remove(library.books().first { it.id == text.id })
            assertTrue(source.exists()); assertTrue(relocated.exists())
            restoreLibrary(context, Uri.fromFile(backup))
            val restored = library.books().first { it.id == html.id }
            assertNull(restored.sourceReference)
            assertNotNull(restored.bundleFile)
            assertEquals(.37, restored.progress, .001)
            source.delete(); relocated.delete()
            library.openPublication(restored).close()
            ZipFile(library.publicationFile(restored)).use { zip -> assertTrue(zip.entries().asSequence().any { it.name.startsWith("image-") }) }
        } finally {
            library.books().filter { it.id in setOf(html.id, text.id) }.forEach { library.remove(it) }
            source.delete(); relocated.delete(); backup.delete()
        }
    }

    @Test fun explicitArchiveCopyIsSharedAndReleasedOnlyAfterLastBook() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = Library.get(context)
        val source = File(context.cacheDir, "managed-collection.zip")
        ZipOutputStream(source.outputStream()).use { zip ->
            for ((name, body) in listOf("one.txt" to "第一章 甲\n甲书正文。", "two.txt" to "第一章 乙\n乙书正文。")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
            }
        }
        val selections = library.preview(Uri.fromFile(source))
        val books = selections.map { library.importCandidate(it, copy = true, tree = null) }
        try {
            assertEquals(2, books.size)
            assertEquals(books[0].bundleFile, books[1].bundleFile)
            val archive = File(books[0].bundleFile!!)
            source.delete()
            library.remove(books[0])
            assertTrue(archive.isFile)
            library.openPublication(books[1]).close()
            library.remove(books[1])
            assertFalse(archive.exists())
        } finally {
            library.books().filter { active -> books.any { it.id == active.id } }.forEach { library.remove(it) }
            source.delete()
        }
    }

    @Test fun invalidArchivePreviewDoesNotChangeRecords() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = Library.get(context)
        val before = library.records().map { it.json().toString() }
        val source = File(context.cacheDir, "invalid-collection.zip")
        try {
            ZipOutputStream(source.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("../escape.txt")); zip.write("invalid".toByteArray()); zip.closeEntry() }
            assertTrue(runCatching { library.preview(Uri.fromFile(source)) }.isFailure)
            assertEquals(before, library.records().map { it.json().toString() })
        } finally { source.delete() }
    }
    @Test fun legacyChineseZipNamesRemainReadableAndSelectionIsExplicit() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = Library.get(context)
        val source = File(context.cacheDir, "legacy-names.zip")
        InstrumentationRegistry.getInstrumentation().context.assets.open("legacy-names.zip").use { input -> source.outputStream().use { input.copyTo(it) } }
        val candidates = library.preview(Uri.fromFile(source))
        assertEquals(2, candidates.size)
        assertTrue("Chinese archive names must be preserved: ${candidates.map { it.entry!!.name }}", candidates.any { it.entry!!.name == "中文目录/分卷一.txt" })
        val book = library.importCandidate(candidates.first { it.entry!!.name.contains("分卷一") }, copy = false, tree = null)
        try {
            assertTrue(book.originalName.contains("分卷一"))
            assertFalse(library.books().any { it.originalName.contains("分卷二") })
            library.openPublication(book).close()
        } finally { library.remove(book); source.delete() }
    }

}
