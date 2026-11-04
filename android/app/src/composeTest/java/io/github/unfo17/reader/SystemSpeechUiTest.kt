package io.github.unfo17.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class SystemSpeechUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun nodes(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence { yield(node); for (i in 0 until node.childCount) node.getChild(i)?.let { yieldAll(nodes(it)) } }
    private fun waitFor(description: String, timeout: Long = 15000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; SystemClock.sleep(100) }
        val visible = instrumentation.uiAutomation.rootInActiveWindow?.let { nodes(it).mapNotNull { n -> n.text?.toString() ?: n.contentDescription?.toString() }.toList() }
        error("$description：$visible")
    }
    private fun click(label: String) {
        waitFor("找不到 $label") {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@waitFor false
            var target = nodes(root).firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
            while (target != null && !target.isClickable) target = target.parent
            if (target?.isEnabled == true && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync(); true
            } else { nodes(root).firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
        }
    }
    @Test fun selectedSystemVoiceIsSavedPreviewedAndReadsPastTheOldThreeThousandCharacterLimit() = runBlocking<Unit> {
        val context = instrumentation.targetContext; val engine = instrumentation.context.packageName
        val library = Library.get(context); val previous = SystemSpeechSettings.load(context)
        SystemSpeechSettings(engine = engine, speed = 1.25f).save(context)
        val endMarker = "READING_END_${System.nanoTime()}"
        val source = File(context.cacheDir, "continuous-system-speech.txt").apply { writeText("第一章 连续朗读\n" + "word ".repeat(900) + endMarker) }
        val book = library.import(Uri.fromFile(source))
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { requests += intent.getStringExtra("text").orEmpty() to intent.getStringExtra("voice").orEmpty() }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter("io.github.unfo17.reader.TEST_SPEECH_REQUEST"), ContextCompat.RECEIVER_EXPORTED)
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(context, ReaderActivity::class.java).putExtra("book", book.id)).use {
                click("阅读设置"); click("系统语音与音色")
                click("音色：系统默认音色"); click("test.en · en-US")
                click("试听")
                waitFor("未收到实际试听请求") { requests.isNotEmpty() }
                waitFor("试听未结束") { instrumentation.uiAutomation.rootInActiveWindow?.let { root -> nodes(root).any { n -> n.text?.toString() == "试听" } } == true }
                click("保存语音设置")
                val saved = SystemSpeechSettings.load(context)
                assertEquals(engine, saved.engine); assertEquals("test.en", saved.voice)
                assertEquals("en-US", saved.locale); assertEquals(1.25f, saved.speed)
                click("朗读")
                waitFor("长正文没有读到结尾", 60000) { synchronized(requests) { requests.any { pair -> pair.first.contains(endMarker) } } }
                waitFor("朗读未完成", 10000) { instrumentation.uiAutomation.rootInActiveWindow?.let { root -> nodes(root).any { n -> n.contentDescription?.toString() == "朗读" } } == true }
                val spoken = synchronized(requests) { requests.toList() }
                assertTrue(spoken.sumOf { pair -> pair.first.length } > 4000)
                assertTrue(spoken.all { pair -> pair.second == "test.en" })
                assertTrue(spoken.all { pair -> pair.first.length <= 244 })
            }
            waitFor("临时语音音频未清理") { File(context.cacheDir, "system-speech").listFiles().orEmpty().isEmpty() }
        } finally {
            context.unregisterReceiver(receiver); previous.save(context)
            library.books().firstOrNull { it.id == book.id }?.let(library::remove); source.delete()
        }
    }
}
