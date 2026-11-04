package io.github.unfo17.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ListeningCursorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private suspend fun waitUntil(description: String, predicate: () -> Boolean) {
        try { withTimeout(20000) { while (!predicate()) delay(25) } }
        catch (e: kotlinx.coroutines.TimeoutCancellationException) { error("$description：${ListeningSession.state.value}") }
    }
    @Test fun cursorSurvivesServiceRecreationAndChangedVoiceRestartsOnlyItsSavedSentence() = runBlocking<Unit> {
        val library = Library.get(context); val store = ListeningStore(context)
        val previous = SystemSpeechSettings.load(context)
        val voice = SystemSpeechSettings(engine = instrumentation.context.packageName, voice = "test.en", locale = "en-US", follow = false)
        voice.save(context)
        val source = File(context.cacheDir, "listening-resume.txt").apply { writeText("第一章 续听\n[test-audio-session] exact resume ${System.nanoTime()}") }
        val book = library.import(Uri.fromFile(source))
        suspend fun stop() {
            if (ListeningSession.state.value.active) ListeningSession.command(context, ListeningSession.STOP)
            waitUntil("服务未停止") { !ListeningSession.state.value.active }
            waitUntil("临时音频仍被占用") { File(context.cacheDir, "system-speech").listFiles().orEmpty().isEmpty() }
        }
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(context, ReaderActivity::class.java).putExtra("book", book.id)).use {
                ListeningSession.start(context, book.id, null, null)
                waitUntil("首段音频未开始") { ListeningSession.state.value.let { state -> state.phase == ListeningPhase.PLAYING && state.durationMillis >= 14000 && state.positionMillis >= 1200 } }
                ListeningSession.command(context, ListeningSession.PAUSE)
                waitUntil("暂停未落盘") { ListeningSession.state.value.phase == ListeningPhase.PAUSED && (store.cursor(book.id)?.positionMillis ?: 0) >= 1200 }
                val pausedCursor = store.cursor(book.id)!!
                assertTrue(pausedCursor.locator.contains("exact resume"))
                stop()
                val saved = store.cursor(book.id)!!
                assertEquals(pausedCursor.positionMillis, saved.positionMillis)
                val rebuiltStore = ListeningStore(context)
                assertEquals(saved, rebuiltStore.cursor(book.id))
                ListeningSession.continueFromRecord(context, book.id)
                waitUntil("重建后没有回到音频位置") { ListeningSession.state.value.let { state -> state.phase == ListeningPhase.PLAYING && state.positionMillis >= saved.positionMillis && state.positionMillis < saved.positionMillis + 1200 } }
                ListeningSession.command(context, ListeningSession.PAUSE)
                waitUntil("重建后的暂停失败") { ListeningSession.state.value.phase == ListeningPhase.PAUSED }
                val resumed = store.cursor(book.id)!!
                assertEquals(saved.paragraphKey, resumed.paragraphKey)
                assertEquals(saved.audioHash, resumed.audioHash)
                assertTrue(resumed.positionMillis >= saved.positionMillis)
                stop()
                voice.copy(voice = "test.zh", locale = "zh-CN").save(context)
                ListeningSession.continueFromRecord(context, book.id)
                waitUntil("更换音色后未开始") { ListeningSession.state.value.phase == ListeningPhase.PLAYING }
                assertTrue(ListeningSession.state.value.positionMillis < 900)
                ListeningSession.command(context, ListeningSession.PAUSE)
                waitUntil("更换音色后的暂停失败") { ListeningSession.state.value.phase == ListeningPhase.PAUSED }
                assertNotEquals(saved.speechHash, store.cursor(book.id)?.speechHash)
                assertEquals(saved.paragraphKey, store.cursor(book.id)?.paragraphKey)
                stop()
            }
        } finally { stop(); ListeningSession.dismissError(); store.remove(book.id); previous.save(context); library.books().firstOrNull { it.id == book.id }?.let(library::remove); source.delete() }
    }
    @Test fun invalidResumeDoesNotReplaceItsSavedAnchorAndDamagedRecordsStayIsolated() = runBlocking<Unit> {
        val library = Library.get(context); val store = ListeningStore(context)
        val previous = SystemSpeechSettings.load(context)
        SystemSpeechSettings(engine = instrumentation.context.packageName, voice = "test.en", locale = "en-US", follow = false).save(context)
        val source = File(context.cacheDir, "listening-invalid-resume.txt").apply { writeText("第一章 原文\n不会被伪造记录替换 ${System.nanoTime()}") }
        val book = library.import(Uri.fromFile(source))
        val pub = library.openPublication(book); val link = pub.readingOrder.first(); val location = pub.locatorFromLink(link)!!; pub.close()
        val invalid = ListeningCursor(book.id, null, book.title, link.href.toString(), "f".repeat(64), 0, "b".repeat(64), "c".repeat(64), 1000, location.toJSON().toString(), System.currentTimeMillis())
        val prefs = context.getSharedPreferences(ListeningStore.PREFERENCES, Context.MODE_PRIVATE)
        val badOwner = "damaged-${System.nanoTime()}"
        try {
            store.save(invalid)
            prefs.edit().putString(ListeningStore.PREFIX + badOwner, "{invalid-json").commit()
            assertNull(store.cursor(badOwner))
            assertTrue(store.cursors().any { it.ownerId == book.id })
            assertEquals("{invalid-json", prefs.getString(ListeningStore.PREFIX + badOwner, null))
            assertTrue(runCatching { ListeningCursor.from(JSONObject(invalid.json().toString()).put("positionMillis", -1)) }.isFailure)
            ActivityScenario.launch<ReaderActivity>(Intent(context, ReaderActivity::class.java).putExtra("book", book.id)).use {
                ListeningSession.continueFromRecord(context, book.id)
                waitUntil("伪造位置没有被拒绝") { ListeningSession.state.value.phase == ListeningPhase.FAILED }
                assertTrue(ListeningSession.state.value.error.orEmpty().contains("续听原文已变化"))
                assertEquals(invalid, store.cursor(book.id))
            }
        } finally {
            if (ListeningSession.state.value.active) ListeningSession.command(context, ListeningSession.STOP)
            waitUntil("损坏记录测试未清理") { !ListeningSession.state.value.active }
            ListeningSession.dismissError(); store.remove(book.id); store.remove(badOwner); previous.save(context)
            library.books().firstOrNull { it.id == book.id }?.let(library::remove); source.delete()
        }
    }
}
