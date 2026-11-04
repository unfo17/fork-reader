package io.github.unfo17.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SystemSpeechTest {
    @Test fun decodedSpeechPausesAtTheExactAudioPositionAndResumesWithoutRestartingTheClip() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext; val engine = instrumentation.context.packageName
        val library = Library.get(context)
        val source = File(context.cacheDir, "audio-position.txt").apply { writeText("第一章 音频\n精确暂停验收 ${System.nanoTime()}") }
        val book = library.import(Uri.fromFile(source))
        val client = SystemSpeechClient(context)
        val audio = SpeechAudioOutput(context, pauseOnInterruption = true)
        var file: File? = null
        suspend fun readyWhen(predicate: (SpeechAudioOutput) -> Boolean) = withTimeout(8000) {
            while (!withContext(Dispatchers.Main) { predicate(audio) }) delay(20)
        }
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(context, ReaderActivity::class.java).putExtra("book", book.id)).use {
                file = client.synthesize("[test-audio-long] exact-position", engine, "test.en", "en-US")
                val playback = async { audio.play(file!!, startPlaying = false) }
                readyWhen { it.ready }
                delay(150)
                assertEquals(0L, withContext(Dispatchers.Main) { audio.positionMillis })
                withContext(Dispatchers.Main) { audio.resume() }
                readyWhen { it.positionMillis >= 200 }
                val paused = withContext(Dispatchers.Main) { audio.pause(); audio.positionMillis }
                withContext(Dispatchers.Main) { audio.setSpeed(2f) }
                delay(300)
                assertTrue(kotlin.math.abs(withContext(Dispatchers.Main) { audio.positionMillis } - paused) <= 40)
                assertTrue(playback.isActive)
                withContext(Dispatchers.Main) { audio.seekTo(audio.durationMillis - 300) }
                readyWhen { it.positionMillis >= it.durationMillis - 400 }
                withContext(Dispatchers.Main) { audio.resume() }
                withTimeout(2000) { playback.await() }
                withContext(Dispatchers.Main) {
                    assertEquals(audio.durationMillis, audio.positionMillis)
                    audio.resume() // Completion stays complete; it must not restart from the beginning.
                    assertFalse(audio.ready)
                }
            }
        } finally {
            withContext(Dispatchers.Main) { audio.close(); client.close() }
            file?.delete(); library.books().firstOrNull { it.id == book.id }?.let(library::remove); source.delete()
        }
    }
    @Test fun upstreamSpeechSegmentationKeepsOriginalOffsetsAndNeverCutsSurrogatePairs() {
        val text = "😀".repeat(119) + "AB。" + "一个长段落，包含 1,000,000 和 emoji 😀。".repeat(40) + "\u200b\u0001\uDBFF\uDFFF结尾」』”’"
        val clean = org.mewx.wenku8_flutter.SpeechSegments.clean(text)
        assertEquals(text.length, clean.length)
        val segments = org.mewx.wenku8_flutter.SpeechSegments.split(text).toList()
        assertTrue(segments.size > 3)
        assertTrue(segments.all { it.text.length <= 244 && it.end > it.start })
        assertTrue(segments.all { it.text == org.mewx.wenku8_flutter.SpeechSegments.clean(text.substring(it.start, it.end)) })
        assertTrue(segments.all { !Character.isLowSurrogate(text[it.start]) && !Character.isHighSurrogate(text[it.end - 1]) })
        assertTrue(segments.zipWithNext().all { (a, b) -> a.end <= b.start })
        assertTrue(org.mewx.wenku8_flutter.SpeechSegments.split("<!--image-->private.jpg<!--image-->").none())
        assertTrue(org.mewx.wenku8_flutter.SpeechSegments.split("[插图]").none())
        assertTrue(org.mewx.wenku8_flutter.SpeechSegments.split("。😀！？").none())
    }
    @Test fun originalNativeCoreDiscoversRealEngineVoicesAndSynthesizesWavWithLanguageValidation() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext; val engine = instrumentation.context.packageName
        val client = SystemSpeechClient(context)
        try {
            assertTrue(client.engines().any { it.first == engine })
            val voices = withTimeout(20000) { client.voices(engine) }
            assertTrue(voices.any { it.id == "test.zh" && it.locale == "zh-CN" && it.installed && !it.networkRequired })
            val file = withTimeout(10000) { client.synthesize("真实系统语音验收", engine, "test.zh", "zh-CN") }
            try {
                val bytes = file.readBytes()
                assertEquals("RIFF", String(bytes.copyOfRange(0, 4)))
                assertEquals("WAVE", String(bytes.copyOfRange(8, 12)))
                assertTrue(bytes.size > 44)
            } finally { file.delete() }
            val mismatch = runCatching { client.synthesize("中文", engine, "test.en", "zh-CN") }.exceptionOrNull()
            assertEquals("TTS_LANGUAGE_UNAVAILABLE", (mismatch as SpeechFailure).code)
            val invalid = runCatching { client.request("synthesize", mapOf("requestId" to "a".repeat(32), "text" to "test", "engine" to engine, "voice" to "test.zh", "path" to File(context.filesDir, "b".repeat(64) + ".wav.part").path)) }.exceptionOrNull()
            assertEquals("TTS_INPUT", (invalid as SpeechFailure).code)
            val missing = runCatching { client.voices("io.github.uninstalled.speech") }.exceptionOrNull()
            assertEquals("TTS_ENGINE", (missing as SpeechFailure).code)
        } finally { withContext(Dispatchers.Main) { client.close(); client.close() } }
    }
    @Test fun cancellingPendingNativeSynthesisDoesNotBlockUiOrLeaveAnUnusableSession() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext; val engine = instrumentation.context.packageName
        val client = SystemSpeechClient(context)
        val marker = "[test-wait]${java.util.UUID.randomUUID()}"
        val started = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { if (intent.getStringExtra("text") == marker) started.complete(Unit) }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter("io.github.unfo17.reader.TEST_SPEECH_STARTED"), ContextCompat.RECEIVER_EXPORTED)
        try {
            val task = async { client.synthesize(marker, engine, "test.zh", "zh-CN") }
            withTimeout(20000) { started.await() }
            withTimeout(1000) { withContext(Dispatchers.Main) { assertTrue(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) } }
            task.cancelAndJoin()
            val file = withTimeout(10000) { client.synthesize("取消后继续", engine, "test.zh", "zh-CN") }
            assertTrue(file.length() > 44); file.delete()
            assertTrue(File(context.cacheDir, "system-speech").listFiles().orEmpty().isEmpty())
        } finally { context.unregisterReceiver(receiver); withContext(Dispatchers.Main) { client.close() } }
    }
}
