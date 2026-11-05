package io.github.unfo17.reader

import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ListeningUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun nodes(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence { yield(node); for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(nodes(it)) } }
    private fun click(label: String) {
        val end = SystemClock.uptimeMillis() + 12000
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        while (SystemClock.uptimeMillis() < end) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            var node = root?.let { nodes(it).firstOrNull { candidate -> candidate.text?.toString() == label || candidate.contentDescription?.toString() == label } }
            while (node != null && !node.isClickable) node = node.parent
            if (node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { instrumentation.waitForIdleSync(); return }
            root?.let { nodes(it).firstOrNull { candidate -> candidate.isScrollable } }?.let { if (!it.performAction(direction)) direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
            SystemClock.sleep(100)
        }
        error("没有找到听书控件：$label")
    }
    private suspend fun waitUntil(predicate: () -> Boolean) { withTimeout(15000) { while (!predicate()) delay(50) } }
    @Test fun savedListeningHistoryRestartsTheServiceFromItsPersistedPosition() = runBlocking<Unit> {
        val context = instrumentation.targetContext; val library = Library.get(context); val store = ListeningStore(context)
        val previous = SystemSpeechSettings.load(context)
        SystemSpeechSettings(engine = instrumentation.context.packageName, voice = "test.en", locale = "en-US", follow = false).save(context)
        val source = File(context.cacheDir, "listening-history-${System.nanoTime()}.txt").apply { writeText("第一章 记录\n[test-audio-session] history resume ${System.nanoTime()}") }
        val book = library.import(Uri.fromFile(source))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ListeningSession.start(context, book.id, null, null)
                waitUntil { ListeningSession.state.value.let { state -> state.phase == ListeningPhase.PLAYING && state.durationMillis >= 14000 && state.positionMillis >= 500 } }
                ListeningSession.command(context, ListeningSession.PAUSE)
                waitUntil { ListeningSession.state.value.phase == ListeningPhase.PAUSED && (store.cursor(book.id)?.positionMillis ?: 0) >= 500 }
                ListeningSession.command(context, ListeningSession.STOP)
                waitUntil { !ListeningSession.state.value.active }
                val saved = store.cursor(book.id)!!
                click("我的"); click("听书记录与续听"); click("继续听书")
                waitUntil { ListeningSession.state.value.let { state -> state.phase == ListeningPhase.PLAYING && state.positionMillis >= saved.positionMillis && state.bookId == book.id } }
                click("停止听书"); waitUntil { !ListeningSession.state.value.active }
                waitUntil { File(context.cacheDir, "system-speech").listFiles().orEmpty().isEmpty() }
            }
        } finally {
            if (ListeningSession.state.value.active) ListeningSession.command(context, ListeningSession.STOP)
            waitUntil { !ListeningSession.state.value.active }; ListeningSession.dismissError()
            store.remove(book.id); previous.save(context); library.books().firstOrNull { it.id == book.id }?.let(library::remove); source.delete()
        }
    }
    @Test fun libraryMiniPlayerAndSleepControlsOperateTheSameBackgroundSession() = runBlocking<Unit> {
        val context = instrumentation.targetContext; val library = Library.get(context)
        val previous = SystemSpeechSettings.load(context)
        SystemSpeechSettings(engine = instrumentation.context.packageName, voice = "test.en", locale = "en-US", follow = false).save(context)
        val source = File(context.cacheDir, "listening-controls.txt").apply { writeText("第一章 控制\n[test-audio-session] controls ${System.nanoTime()}") }
        val book = library.import(Uri.fromFile(source))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ListeningSession.start(context, book.id, null, null)
                waitUntil { ListeningSession.state.value.let { state -> state.phase == ListeningPhase.PLAYING && state.durationMillis >= 14000 } }
                click("正在听书"); click("暂停朗读")
                waitUntil { ListeningSession.state.value.phase == ListeningPhase.PAUSED }
                click("本章结束"); waitUntil { ListeningSession.state.value.sleepAfterChapter }
                assertNull(ListeningSession.state.value.sleepUntil)
                click("15 分钟")
                waitUntil { ListeningSession.state.value.sleepUntil != null }
                assertFalse(ListeningSession.state.value.sleepAfterChapter)
                assertTrue(ListeningSession.state.value.sleepUntil!! > System.currentTimeMillis() + 14 * 60000L)
                click("关闭定时"); waitUntil { ListeningSession.state.value.sleepUntil == null }
                click("系统语音与音色"); click("取消")
                click("继续听书"); waitUntil { ListeningSession.state.value.phase == ListeningPhase.PLAYING }
                click("停止听书"); waitUntil { !ListeningSession.state.value.active }
                waitUntil { File(context.cacheDir, "system-speech").listFiles().orEmpty().isEmpty() }
            }
        } finally {
            if (ListeningSession.state.value.active) ListeningSession.command(context, ListeningSession.STOP)
            waitUntil { !ListeningSession.state.value.active }
            previous.save(context); library.books().firstOrNull { it.id == book.id }?.let(library::remove); source.delete()
        }
    }
}
