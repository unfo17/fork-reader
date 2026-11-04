package io.github.unfo17.reader

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LibraryTest {
    @Test fun epubImportDedupParseAndRender() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "fixture.epub")
        instrumentation.context.assets.open("fixture.epub").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val library = Library.get(context)
        val first = library.import(Uri.fromFile(fixture))
        assertEquals("Readium 验证书", first.title)
        assertTrue(first.author.contains("测试作者"))
        val second = library.import(Uri.fromFile(fixture))
        assertEquals(first.id, second.id)
        assertEquals(1, library.books().count { it.id == first.id })
        val publication = library.engine.open(File(first.file))
        assertEquals(1, publication.readingOrder.size)
        assertFalse(publication.tableOfContents.isEmpty())
        publication.close()
        ActivityScenario.launch<ReaderActivity>(Intent(context, ReaderActivity::class.java).putExtra("book", first.id)).use { scenario ->
            // Wait for asynchronous publication opening and WebView pagination.
            val deadline = System.currentTimeMillis() + 15000
            var rendered = false
            while (!rendered && System.currentTimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity -> rendered = activity.supportFragmentManager.fragments.any { it is org.readium.r2.navigator.epub.EpubNavigatorFragment && it.view != null } }
                if (!rendered) Thread.sleep(100)
            }
            assertTrue("Readium navigator must render in Compose", rendered)
        }
        assertTrue(library.books().first { it.id == first.id }.lastRead > 0)
        val backup = File(context.cacheDir, "library-backup.zip")
        val current = library.books().first { it.id == first.id }
        library.update(current.copy(bookmarks = listOf("test-mark"), category = "测试分类"))
        exportLibrary(context, Uri.fromFile(backup))
        library.remove(library.books().first { it.id == first.id })
        assertFalse(File(first.file).exists())
        assertTrue(restoreLibrary(context, Uri.fromFile(backup)) >= 1)
        val restored = library.books().first { it.id == first.id }
        assertEquals(listOf("test-mark"), restored.bookmarks)
        assertEquals("测试分类", restored.category)
        assertTrue(File(restored.file).exists())
        library.remove(restored)
        backup.delete()
        fixture.delete()
    }

    @Test fun failedImportLeavesNoLibraryEntry() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = Library.get(context)
        val count = library.books().size
        val broken = File(context.cacheDir, "broken.epub").apply { writeText("not an epub") }
        assertTrue(runCatching { library.import(Uri.fromFile(broken)) }.isFailure)
        assertEquals(count, library.books().size)
        broken.delete()
    }
    @Test fun upstreamTextImportEncodingsHeadingsAndLongParagraphs() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = Library.get(context)
        val text = "序章\n这是一段中文正文。\n=== 第一章 开始 ===\n" + "没有换行的正文".repeat(1800) + "😀结尾\n第二章 结束\n最后一段。"
        for (encoding in listOf("UTF-8", "UTF-16LE", "UTF-16BE", "GB18030", "Big5")) {
            val source = File(context.cacheDir, "原项目文本-$encoding.txt")
            val encodable = if (encoding == "Big5") text.replace("😀", "").replace("这", "這").replace("结", "結") else text
            val bytes = encodable.toByteArray(charset(encoding))
            val bom = when (encoding) { "UTF-16LE" -> byteArrayOf(0xff.toByte(), 0xfe.toByte()); "UTF-16BE" -> byteArrayOf(0xfe.toByte(), 0xff.toByte()); else -> byteArrayOf() }
            source.writeBytes(bom + bytes)
            val imported = library.import(Uri.fromFile(source))
            try {
                val readingFile = library.publicationFile(imported)
                assertTrue(readingFile.isFile)
                assertArrayEquals(bom + bytes, File(imported.file).readBytes())
                val pub = library.engine.open(readingFile)
                try {
                    assertTrue("TXT chapter rules must become a Readium TOC", pub.tableOfContents.size >= 3)
                } finally { pub.close() }
                java.util.zip.ZipFile(readingFile).use { zip ->
                    val html = zip.entries().asSequence().filter { it.name.startsWith("c") && it.name.endsWith(".xhtml") }.joinToString("") { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
                    assertTrue(html.contains("最后一段"))
                    assertTrue(html.length > 10000)
                }
                assertEquals(imported.id, library.import(Uri.fromFile(source)).id)
            } finally { library.remove(imported); source.delete() }
        }
    }

    @Test fun upstreamHeadingRuleSemanticsArePreserved() {
        val heading = org.mewx.wenku8_flutter.LocalTextPublication
        assertTrue(heading.isHeading("=== 第一章 开始 ==="))
        assertTrue(heading.isHeading("第十二卷"))
        assertTrue(heading.isHeading("CHAPTER 123"))
        assertFalse(heading.isHeading("这是第一章的正文"))
        assertFalse(heading.isHeading("= 第一章 ="))
        assertFalse(heading.isHeading("第一章" + "很长的正文".repeat(30)))
    }

    @Test fun linkedFilesRelinkAndDeletionKeepReadingRecords() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = Library.get(context)
        val source = File(context.cacheDir, "linked-original.txt").apply { writeText("第一章 关联测试\n原书无需复制。\n" + "正文".repeat(200)) }
        val replacement = File(context.cacheDir, "linked-moved.txt")
        val wrong = File(context.cacheDir, "linked-wrong.txt").apply { writeText("这不是原来的书") }
        val book = library.import(Uri.fromFile(source), copy = false)
        try {
            assertNotNull(book.sourceReference)
            assertFalse("Linking must not keep another raw original", File(book.file).exists())
            library.update(book.copy(progress = 0.42, bookmarks = listOf("{\"txtProgress\":0.42}")))
            source.copyTo(replacement, overwrite = true)
            val moved = library.relink(library.books().first { it.id == book.id }, Uri.fromFile(replacement))
            assertEquals(0.42, moved.progress, 0.001)
            assertTrue(runCatching { library.relink(moved, Uri.fromFile(wrong)) }.isFailure)
            assertEquals(moved.sourceReference, library.books().first { it.id == book.id }.sourceReference)
            source.delete()
            library.openPublication(moved).close()
            replacement.setLastModified(moved.sourceModified + 5000)
            assertTrue("Changed files must request relinking instead of silently using stale caches", runCatching { library.openPublication(moved) }.isFailure)
            val refreshed = library.relink(moved, Uri.fromFile(replacement))
            library.remove(refreshed)
            assertTrue(replacement.exists())
            assertFalse(library.books().any { it.id == book.id })
            val record = library.records().first { it.id == book.id }
            assertTrue(record.removed)
            assertEquals(0.42, record.progress, 0.001)
            assertEquals(1, record.bookmarks.size)
            val restored = library.import(Uri.fromFile(replacement), copy = false)
            assertEquals(0.42, restored.progress, 0.001)
            assertEquals(1, restored.bookmarks.size)
            library.remove(restored)
        } finally { source.delete(); replacement.delete(); wrong.delete() }
    }

    @Test fun descriptorInputHonorsLogicalDocumentOffset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "descriptor-offset.bin")
        val payload = "仅仅读取授权范围".toByteArray()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5) + payload + byteArrayOf(6, 7))
        try {
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val input = org.mewx.wenku8_flutter.BookDocumentInput(fd.fd, payload.size.toLong(), 5)
                assertArrayEquals(payload, input.inputStream().use { it.readBytes() })
            }
        } finally { file.delete() }
    }

    @Test fun customCoverAndReaderOptionsSurviveBackup() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = Library.get(context)
        val original = File(context.cacheDir, "cover-options-original.txt").apply { writeText("第一章 设置与封面\n原书保留。") }
        val image = File(context.cacheDir, "custom-cover.png")
        val backup = File(context.cacheDir, "cover-options-backup.zip")
        val bitmap = android.graphics.Bitmap.createBitmap(2400, 1600, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val book = library.import(Uri.fromFile(original), copy = false)
        try {
            val covered = library.replaceCover(book, Uri.fromFile(image))
            assertTrue(covered.coverRevision > 0)
            val preview = loadCover(covered)!!
            try { assertTrue(preview.width <= 512 && preview.height <= 768) } finally { preview.recycle() }
            val options = ReaderOptions(fontSize = 135f, lineHeight = 2.1f, vertical = true, volumePaging = true, backgroundColor = "#F5EBD8")
            library.update(covered.copy(summary = "测试简介", tags = listOf("测试", "离线"), status = "paused", readerOptions = options.json()))
            exportLibrary(context, Uri.fromFile(backup))
            java.util.zip.ZipFile(backup).use { zip ->
                val manifest = zip.getInputStream(zip.getEntry("library.json")).bufferedReader().use { it.readText() }
                assertFalse(manifest.contains(original.path))
                assertFalse(manifest.contains(book.sourceReference!!))
            }
            library.remove(library.books().first { it.id == book.id })
            restoreLibrary(context, Uri.fromFile(backup))
            val restored = library.books().first { it.id == book.id }
            assertNull(restored.sourceReference)
            assertTrue(File(restored.file).exists())
            assertEquals("测试简介", restored.summary)
            assertEquals(listOf("测试", "离线"), restored.tags)
            assertEquals("paused", restored.readingState)
            assertEquals(options, ReaderOptions.decode(restored.readerOptions))
            assertNotNull(loadCover(restored)?.also { it.recycle() })
            val reset = library.restoreCover(restored)
            assertFalse(File(reset.file + ".jpg").exists())
            assertTrue(original.exists())
        } finally {
            library.books().firstOrNull { it.id == book.id }?.let { library.remove(it) }
            original.delete(); image.delete(); backup.delete()
        }
    }

    @Test fun malformedBackupNeverChangesExistingLibrary() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = Library.get(context)
        val before = library.records().map { it.json().toString() }
        val archive = File(context.cacheDir, "malformed-library.zip")
        try {
            java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("../outside.txt")); zip.write("invalid".toByteArray()); zip.closeEntry()
            }
            assertTrue(runCatching { restoreLibrary(context, Uri.fromFile(archive)) }.isFailure)
            assertEquals(before, library.records().map { it.json().toString() })
            assertFalse(File(context.cacheDir, "outside.txt").exists())
        } finally { archive.delete() }
    }

}
