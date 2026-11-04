package io.github.unfo17.reader

import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Drives the actual Compose accessibility tree, including recreation and persisted layout. */
@RunWith(AndroidJUnit4::class)
class LibraryUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun descendants(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(descendants(it)) }
    }
    private fun waitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            automation.rootInActiveWindow?.let { root -> descendants(root).firstOrNull(predicate)?.let { return it } }
            SystemClock.sleep(100)
        }
        error("页面中没有找到预期控件")
    }
    private fun text(value: String) = waitNode { it.text?.toString() == value }
    private fun click(value: String) {
        var node: AccessibilityNodeInfo? = text(value)
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("控件不可点击：$value", node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    @Test fun composeCategoryManagerAndGridPreferencesSurviveActivityRecreation() = runBlocking<Unit> {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("library-display", 0)
        val previous = prefs.all
        prefs.edit().clear().commit()
        val library = Library.get(context)
        val source = File(context.cacheDir, "ui-book.txt").apply { writeText("第一章 界面验收\n界面阅读内容 ${System.nanoTime()}") }
        val book = library.import(Uri.fromFile(source))
        val name = "界面分类${System.nanoTime().toString().takeLast(5)}"
        var group: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                click("本地")
                text(book.title)
                click("管理分类"); click("新建分类")
                val input = waitNode { it.className?.toString() == "android.widget.EditText" }
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name) }))
                click("保存")
                text(name)
                group = library.organization.groups().entries.first { it.value == name }.key
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                text("管理分类")
                click("书架与筛选")
                click("展示：立体书架 + 列表"); click("纯宫格")
                text("展示：纯宫格")
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                text("管理分类")
                scenario.recreate()
                text("管理分类") // The active local tab is restored, along with layout preferences.
                click("书架与筛选"); text("展示：纯宫格")
                assertEquals(LibraryView.GRID.name, prefs.getString("view", null))
            }
        } finally {
            group?.let { library.organization.remove(it) }
            library.books().firstOrNull { it.id == book.id }?.let(library::remove)
            source.delete()
            val edit = prefs.edit().clear()
            previous.forEach { (key, value) -> if (value is String) edit.putString(key, value) }
            edit.commit()
        }
    }
}
