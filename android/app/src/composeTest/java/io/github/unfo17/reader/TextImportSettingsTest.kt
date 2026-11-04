package io.github.unfo17.reader

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mewx.wenku8_flutter.TextImportRules

@RunWith(AndroidJUnit4::class)
class TextImportSettingsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun nodes(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence { yield(root); for (i in 0 until root.childCount) root.getChild(i)?.let { yieldAll(nodes(it)) } }
    private fun find(scroll: Boolean = false, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                nodes(root).firstOrNull(predicate)?.let { return it }
                if (scroll) nodes(root).firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            SystemClock.sleep(150)
        }
        val visible = instrumentation.uiAutomation.rootInActiveWindow?.let { root -> nodes(root).map { "${it.className}:${it.text}:${it.contentDescription}" }.toList() }
        error("没有找到导入设置控件：$visible")
    }
    private fun click(text: String) {
        val end = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < end) {
            var node: AccessibilityNodeInfo? = find(scroll = true) { it.text?.toString() == text }
            while (node != null && !node.isClickable) node = node.parent
            if (node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { instrumentation.waitForIdleSync(); return }
            SystemClock.sleep(100)
        }
        error("导入设置控件未就绪：$text")
    }
    private fun set(node: AccessibilityNodeInfo, value: String) { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })) }
    @Test fun settingsPreviewAndSaveUseTheSameIsolatedDirectoryRules() {
        val context = instrumentation.targetContext; val preferences = context.getSharedPreferences("import-options", 0)
        val previous = preferences.getString("textRules", null)
        preferences.edit().remove("textRules").commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val show = mutableStateOf(true)
                scenario.onActivity { activity -> activity.setContent { ReaderTheme { if (show.value) TextImportSettings { show.value = false } else Text("设置已保存") } } }
                val toggle = find { it.isCheckable }
                assertTrue(toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val chapter = find(scroll = true) { it.className?.toString() == "android.widget.EditText" && it.text?.toString() == TextImportRules.DEFAULT_CHAPTER }
                set(chapter, "^CH\\d+")
                val volume = find(scroll = true) { it.className?.toString() == "android.widget.EditText" && it.text?.toString() == TextImportRules.DEFAULT_VOLUME }
                set(volume, "^VOL\\d+")
                val sample = find(scroll = true) { it.className?.toString() == "android.widget.EditText" && it.text?.toString()?.contains("这里是正文") == true }
                set(sample, "VOL1\nCH1\n正文\nCH2")
                click("预览目录")
                find(scroll = true) { it.text?.toString() == "卷 · VOL1" }
                click("保存导入设置")
                find { it.text?.toString() == "设置已保存" }
                val saved = TextImportRules.decode(preferences.getString("textRules", null))
                assertTrue(saved.customToc); assertEquals("^CH\\d+", saved.chapterRule); assertEquals("^VOL\\d+", saved.volumeRule)
            }
        } finally { if (previous == null) preferences.edit().remove("textRules").commit() else preferences.edit().putString("textRules", previous).commit() }
    }
}
