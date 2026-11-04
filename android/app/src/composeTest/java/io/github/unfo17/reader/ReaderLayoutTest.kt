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
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ReaderLayoutTest {
    @Test fun independentPageEdgesAffectTheRealViewportAndSurviveChapterReopenAndBackup() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext; val library = Library.get(context)
        val source = File(context.cacheDir, "page-edges.txt").apply { writeText("第一章 边距\n四边独立留白 ${System.nanoTime()}\n第二章 下一页\n这一段用于排版验收。") }
        val backup = File(context.cacheDir, "page-edges-backup.zip")
        val book = library.import(Uri.fromFile(source))
        var options = ReaderOptions(marginLeft = 11f, marginRight = 37f, marginTop = 9f, marginBottom = 23f, scroll = false, publisherStyles = false, letterSpacing = 2f, paragraphGap = 20f, indent = true, justify = true, weight = 1.25f, backgroundColor = "#EAE5DD")
        library.update(book.copy(readerOptions = options.json()))
        val pub = library.openPublication(book); val nextChapter = pub.readingOrder.last(); pub.close()
        suspend fun assertViewport(scenario: ActivityScenario<ReaderActivity>, next: Boolean = false): EpubNavigatorFragment {
            val end = android.os.SystemClock.uptimeMillis() + 25000
            var diagnostic: String? = null
            var bounds = ""
            while (android.os.SystemClock.uptimeMillis() < end) {
                var navigator: EpubNavigatorFragment? = null
                var edges = false
                var expectedWidth = 0.0
                scenario.onActivity { activity ->
                    navigator = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull()
                    val container = navigator?.view?.parent as? android.view.View
                    if (container != null && container.width > 0) {
                        val density = container.resources.displayMetrics.density
                        edges = container.paddingLeft == (11 * density).roundToInt() && container.paddingRight == (37 * density).roundToInt() && container.paddingTop == (9 * density).roundToInt() && container.paddingBottom == (23 * density).roundToInt()
                        bounds = "${container.javaClass.simpleName}: ${container.paddingLeft},${container.paddingTop},${container.paddingRight},${container.paddingBottom}; density=$density; edges=$edges"
                        expectedWidth = (container.width - container.paddingLeft - container.paddingRight) / density.toDouble()
                        assertEquals(android.graphics.Color.parseColor("#EAE5DD"), (container.background as android.graphics.drawable.ColorDrawable).color)
                    }
                }
                val script = "(function(){return !!document.body && parseFloat(getComputedStyle(document.body).paddingLeft)<0.1 && !!document.querySelector('p') && Math.abs(parseFloat(getComputedStyle(document.querySelector('p')).letterSpacing)-(${options.letterSpacing}))<0.025 && getComputedStyle(document.querySelector('p')).fontWeight==='500' && getComputedStyle(document.querySelector('p')).textAlign==='justify' && parseFloat(getComputedStyle(document.querySelector('p')).textIndent)>0 && Math.abs(window.innerWidth-$expectedWidth)<2" + if (next) " && document.body.textContent.indexOf('这一段用于排版验收')>=0;})()" else ";})()"
                val rendered = withTimeoutOrNull(2000) { withContext(Dispatchers.Main) { navigator?.evaluateJavascript(script) } }
                diagnostic = withTimeoutOrNull(2000) { withContext(Dispatchers.Main) { navigator?.evaluateJavascript("JSON.stringify({spacing:getComputedStyle(document.querySelector('p')).letterSpacing,weight:getComputedStyle(document.querySelector('p')).fontWeight,root:getComputedStyle(document.documentElement).fontSize,cssSpacing:getComputedStyle(document.documentElement).getPropertyValue('--USER__letterSpacing'),width:innerWidth,padding:getComputedStyle(document.body).paddingLeft,pageMargins:getComputedStyle(document.documentElement).getPropertyValue('--USER__pageMargins')})") } }
                if (edges && rendered == "true") return navigator!!
                android.os.SystemClock.sleep(100)
            }
            fun texts(node: android.view.accessibility.AccessibilityNodeInfo): List<String> = listOfNotNull(node.text?.toString()) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::texts).orEmpty() }
            val visible = instrumentation.uiAutomation.rootInActiveWindow?.let(::texts)
            error("Options=$options; UI=$visible; Page edges did not reach the actual Readium viewport: next=$next $bounds $diagnostic")
        }
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(context, ReaderActivity::class.java).putExtra("book", book.id)).use { scenario ->
                val navigator = assertViewport(scenario)
                withContext(Dispatchers.Main) { navigator.go(nextChapter) }
                assertViewport(scenario, next = true)
                scenario.recreate(); assertViewport(scenario)
            }
            // Small default spacing and negative spacing must remain exact at a larger font size.
            for (spacing in listOf(.1f, -.5f)) {
                options = options.copy(fontSize = 200f, letterSpacing = spacing)
                library.updateReading(book.id) { it.copy(readerOptions = options.json()) }
                ActivityScenario.launch<ReaderActivity>(Intent(context, ReaderActivity::class.java).putExtra("book", book.id)).use { assertViewport(it) }
            }
            exportLibrary(context, Uri.fromFile(backup))
            library.remove(library.books().first { it.id == book.id })
            restoreLibrary(context, Uri.fromFile(backup))
            assertEquals(options, ReaderOptions.decode(library.books().first { it.id == book.id }.readerOptions))
        } finally { library.books().firstOrNull { it.id == book.id }?.let(library::remove); source.delete(); backup.delete() }
    }
}
