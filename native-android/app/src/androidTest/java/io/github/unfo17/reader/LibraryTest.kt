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
        assertEquals(1, restoreLibrary(context, Uri.fromFile(backup)))
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
}
