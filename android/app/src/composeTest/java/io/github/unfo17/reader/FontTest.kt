@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package io.github.unfo17.reader

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.io.File

@RunWith(AndroidJUnit4::class)
class FontTest {
    @Test fun importedFontIsActuallyLoadedByReadiumAndRemovalResetsPreference() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = FontStore(context)
        val library = Library.get(context)
        val source = File(context.cacheDir, "font-render.txt").apply { writeText("第一章 字体测试\nReadium imported font sample. 正文。") }
        val font = store.import(Uri.fromFile(File("/system/fonts/Roboto-Regular.ttf")))
        val book = library.import(Uri.fromFile(source))
        try {
            assertEquals(font.id, store.import(Uri.fromFile(File("/system/fonts/Roboto-Regular.ttf"))).id)
            library.update(book.copy(readerOptions = ReaderOptions(fontId = font.id, publisherStyles = false).json()))
            ActivityScenario.launch<ReaderActivity>(Intent(context, ReaderActivity::class.java).putExtra("book", book.id)).use { scenario ->
                val deadline = System.currentTimeMillis() + 25000
                var loaded = false
                var diagnostic: String? = null
                while (!loaded && System.currentTimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    var navigator: EpubNavigatorFragment? = null
                    scenario.onActivity { navigator = it.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull() }
                    val rendered = withTimeoutOrNull(3000) { withContext(Dispatchers.Main) {
                        navigator?.evaluateJavascript("Array.from(document.fonts).some(function(f){return f.family.indexOf('${font.id}') >= 0 && f.status === 'loaded';})")
                    } }
                    diagnostic = withTimeoutOrNull(2000) { withContext(Dispatchers.Main) { navigator?.evaluateJavascript("JSON.stringify({faces:Array.from(document.fonts).map(function(f){return [f.family,f.status];}),family:getComputedStyle(document.body).fontFamily,user:getComputedStyle(document.documentElement).getPropertyValue('--USER__fontFamily')})") } }
                    val applied = withTimeoutOrNull(2000) { withContext(Dispatchers.Main) { navigator?.evaluateJavascript("getComputedStyle(document.body).fontFamily.indexOf('${font.id}') >= 0") } }
                    loaded = rendered == "true" && applied == "true"
                    if (!loaded) Thread.sleep(100)
                }
                assertTrue("The imported font must load in the actual EPUB WebView, not only appear in settings: $diagnostic", loaded)
            }
            store.remove(font)
            assertFalse(File(font.file).exists())
            assertEquals("", ReaderOptions.decode(library.records().first { it.id == book.id }.readerOptions).fontId)
        } finally {
            if (store.fonts().any { it.id == font.id }) store.remove(font)
            library.books().firstOrNull { it.id == book.id }?.let { library.remove(it) }
            source.delete()
        }
    }

    @Test fun catalogueMatchesUpstreamAndInvalidFontsLeaveNoEntry() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = FontStore(context)
        val catalog = store.catalogue()
        assertEquals(13, catalog.size)
        assertEquals(22613144L, catalog.first { it.id == "sourceHanSerif" }.bytes)
        assertTrue(catalog.all { it.urls.isNotEmpty() && it.urls.all { url -> url.startsWith("https://") } })
        val before = store.fonts()
        val invalid = File(context.cacheDir, "invalid-font.ttf").apply { writeBytes(ByteArray(64)) }
        try {
            assertTrue(runCatching { store.import(Uri.fromFile(invalid)) }.isFailure)
            assertEquals(before, store.fonts())
        } finally { invalid.delete() }
    }
}
