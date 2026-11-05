package io.github.unfo17.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
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
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class ListeningGapTest {
    @Test fun paragraphGapPersistsAndDoesNotRepeatForEverySentenceOfOneParagraph() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val library = Library.get(context); val previous = SystemSpeechSettings.load(context)
        val settings = SystemSpeechSettings(engine = instrumentation.context.packageName, voice = "test.en", locale = "en-US", follow = false, paragraphGapMillis = 1200)
        settings.save(context)
        assertEquals(1200, SystemSpeechSettings.load(context).paragraphGapMillis)
        assertTrue(runCatching { settings.copy(paragraphGapMillis = 1250).save(context) }.isFailure)
        val source = File(context.cacheDir, "speech-gap-${System.nanoTime()}.txt").apply { writeText("第一章 间隔\n" + "A".repeat(600) + "\nGAP_END_${System.nanoTime()}") }
        val requests = CopyOnWriteArrayList<Pair<String, Long>>()
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { intent?.getStringExtra("text")?.let { requests += it to SystemClock.elapsedRealtime() } } }
        ContextCompat.registerReceiver(context, receiver, IntentFilter("io.github.unfo17.reader.TEST_SPEECH_REQUEST"), ContextCompat.RECEIVER_EXPORTED)
        val book = library.import(Uri.fromFile(source))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ListeningSession.start(context, book.id, null, null)
                withTimeout(20000) { while (ListeningSession.state.value.phase != ListeningPhase.COMPLETED) { check(ListeningSession.state.value.phase != ListeningPhase.FAILED) { ListeningSession.state.value.error.orEmpty() }; delay(25) } }
                val parts = requests.filter { it.first.all { char -> char == 'A' } }
                assertEquals(3, parts.size)
                assertEquals(600, parts.sumOf { it.first.length })
                assertTrue("同一段落的分句不应各停顿 1.2 秒", parts.last().second - parts.first().second < 2000)
                val end = requests.single { it.first.startsWith("GAP_END_") }
                assertTrue("新段落未保留配置的停顿", end.second - parts.last().second >= 1200)
                requests.clear()
                settings.copy(paragraphGapMillis = 3000).save(context)
                ListeningSession.start(context, book.id, null, null)
                withTimeout(10000) { while (requests.isEmpty()) delay(25) }
                withTimeout(2500) { while (!ListeningSession.state.value.let { state -> state.phase == ListeningPhase.PREPARING && state.durationMillis > 0 && state.positionMillis >= state.durationMillis }) delay(25) }
                val disabled = SystemClock.elapsedRealtime()
                settings.copy(paragraphGapMillis = 0).save(context)
                withTimeout(2000) { while (requests.none { it.first.all { char -> char == 'A' } }) delay(25) }
                val resumed = requests.first { it.first.all { char -> char == 'A' } }.second
                assertTrue("关闭段落间隔后仍等待旧定时", resumed - disabled < 1500)
                ListeningSession.command(context, ListeningSession.STOP)
                withTimeout(5000) { while (ListeningSession.state.value.active) delay(25) }
                withTimeout(5000) { while (File(context.cacheDir, "system-speech").listFiles().orEmpty().isNotEmpty()) delay(25) }
            }
        } finally {
            if (ListeningSession.state.value.active) ListeningSession.command(context, ListeningSession.STOP)
            withTimeout(5000) { while (ListeningSession.state.value.active) delay(25) }
            context.unregisterReceiver(receiver); previous.save(context); ListeningStore(context).remove(book.id)
            library.books().firstOrNull { it.id == book.id }?.let(library::remove); source.delete()
        }
    }
}
