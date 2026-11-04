@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package io.github.unfo17.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import java.io.File

@RunWith(AndroidJUnit4::class)
class AnnotationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun nodes(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence { yield(node); for (i in 0 until node.childCount) node.getChild(i)?.let { yieldAll(nodes(it)) } }
    private fun waitNodes(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val end = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < end) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { root -> nodes(root).filter(predicate).toList().takeIf { it.isNotEmpty() }?.let { return it } }
            SystemClock.sleep(100)
        }
        error("没有找到批注编辑控件")
    }
    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = waitNodes { it.text?.toString() == text }.first()
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        instrumentation.waitForIdleSync()
    }
    private suspend fun waitJavascript(scenario: ActivityScenario<ReaderActivity>, script: String): EpubNavigatorFragment {
        val deadline = System.currentTimeMillis() + 25000
        var last: String? = null
        while (System.currentTimeMillis() < deadline) {
            var navigator: EpubNavigatorFragment? = null
            scenario.onActivity { navigator = it.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull() }
            last = withTimeoutOrNull(2000) { withContext(Dispatchers.Main) { navigator?.evaluateJavascript(script) } }
            if (last == "true") return navigator!!
            SystemClock.sleep(100)
        }
        error("正文标记未按预期显示：$last")
    }
    @Test fun exactSelectionOutlineSurvivesReopenAndTypographyChanges() = runBlocking<Unit> {
        val context = instrumentation.targetContext
        val library = Library.get(context)
        val source = File(context.cacheDir, "annotation-render.txt").apply { writeText("第一章 批注\n批注验收独特文本，与相邻正文分开定位。\n第二段正文。${System.nanoTime()}") }
        val book = library.import(Uri.fromFile(source))
        val outlineVisible = "(function(){var e=document.querySelector('[data-group=\"annotations\"] .fork-reader-outline');return !!e && e.getBoundingClientRect().width>0 && parseFloat(getComputedStyle(e).borderTopWidth)>0;})()"
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(context, ReaderActivity::class.java).putExtra("book", book.id)).use { scenario ->
                waitJavascript(scenario, "!!document.body && document.body.textContent.indexOf('批注验收独特文本')>=0")
                val navigator = waitJavascript(scenario, "(function(){var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT),n;while(n=w.nextNode()){var i=n.textContent.indexOf('批注验收独特文本');if(i>=0){var r=document.createRange();r.setStart(n,i);r.setEnd(n,i+9);var s=window.getSelection();s.removeAllRanges();s.addRange(r);return s.toString().length>0;}}return false;})()")
                scenario.onActivity { it.annotateSelection(MarkStyle.OUTLINE) }
                waitNodes { it.text?.toString() == "保存批注" }
                val inputs = waitNodes { it.className?.toString() == "android.widget.EditText" }
                assertTrue(inputs.last().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "设备测试笔记") }))
                click("生成摘录图片")
                waitNodes { it.contentDescription?.toString() == "第 1 张摘录预览" }
                instrumentation.waitForIdleSync(); SystemClock.sleep(600)
                instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                    File(context.filesDir, "quote-ui-qa.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle()
                }
                click("关闭")
                click("保存批注")
                waitJavascript(scenario, outlineVisible)
                val saved = library.records().first { it.id == book.id }.annotations.single { !it.deleted }
                assertEquals("设备测试笔记", saved.note)
                assertTrue(saved.quote.startsWith("批注验收"))
                withContext(Dispatchers.Main) { navigator.submitPreferences(EpubPreferences(fontSize = 180.0, publisherStyles = false)) }
                waitJavascript(scenario, outlineVisible)
                scenario.recreate()
                waitJavascript(scenario, outlineVisible)
                val richerLocator = JSONObject(saved.locator).put("title", "新的页面标题")
                richerLocator.getJSONObject("locations").put("position", 42).put("totalProgression", .73)
                assertTrue(saved.sameAnchor(saved.copy(locator = richerLocator.toString())))
                assertEquals(saved.locator, library.records().first { it.id == book.id }.annotations.first { it.id == saved.id }.locator)
            }
        } finally { library.books().firstOrNull { it.id == book.id }?.let(library::remove); source.delete() }
    }
    @Test fun independentAnnotationFieldsMergeWithoutRestoringDeletedMarks() = runBlocking<Unit> {
        val context = instrumentation.targetContext
        val library = Library.get(context)
        val source = File(context.cacheDir, "annotation-backup.txt").apply { writeText("第一章 笔记\n批注持久化 ${System.nanoTime()}") }
        val book = library.import(Uri.fromFile(source)); val backup = File(context.cacheDir, "annotation-backup.zip")
        try {
            val publication = library.openPublication(book)
            val locator = publication.locatorFromLink(publication.readingOrder.first())!!; publication.close()
            val initial = ReadingMark.create(locator, MarkStyle.HIGHLIGHT)
            val note = initial.copy(note = "新笔记", updatedAt = initial.updatedAt + 20)
            val category = initial.copy(category = "引用", categoryAt = initial.categoryAt + 30)
            val merged = ReadingMark.merge(note, category)
            assertEquals("新笔记", merged.note); assertEquals("引用", merged.category)
            assertEquals(merged, ReadingMark.merge(category, note))
            library.saveAnnotation(book.id, initial)
            library.update(book.copy(progress = .1)) // A delayed reader progress write must not discard a newer note.
            assertTrue(library.records().first { it.id == book.id }.annotations.any { it.id == initial.id })
            exportLibrary(context, Uri.fromFile(backup))
            library.deleteAnnotation(book.id, initial.id, initial.revision)
            restoreLibrary(context, Uri.fromFile(backup))
            assertTrue(library.records().first { it.id == book.id }.annotations.first { it.id == initial.id }.deleted)
            val retained = ReadingMark.create(locator, MarkStyle.NONE)
            library.saveAnnotation(book.id, retained)
            library.remove(library.books().first { it.id == book.id })
            val updated = retained.copy(note = "原书移除后仍能编辑", updatedAt = retained.updatedAt + 100)
            library.saveAnnotation(book.id, updated, retained.revision)
            assertEquals(updated.note, library.records().first { it.id == book.id }.annotations.first { it.id == retained.id }.note)
            assertTrue(runCatching { library.saveAnnotation(book.id, retained, retained.revision) }.isFailure)
            assertTrue(runCatching { ReadingMark.from(updated.copy(note = "字".repeat(100001)).json()) }.isFailure)
            val reopened = library.import(Uri.fromFile(source))
            assertEquals(updated, reopened.annotations.first { it.id == retained.id })
        } finally { library.books().firstOrNull { it.id == book.id }?.let(library::remove); source.delete(); backup.delete() }
    }
}
