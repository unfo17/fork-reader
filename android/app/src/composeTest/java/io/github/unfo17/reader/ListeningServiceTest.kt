package io.github.unfo17.reader

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import androidx.lifecycle.Lifecycle
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
class ListeningServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private suspend fun waitUntil(description: String, predicate: () -> Boolean) {
        try { withTimeout(20000) { while (!predicate()) delay(25) } }
        catch (e: kotlinx.coroutines.TimeoutCancellationException) { error("$description：${ListeningSession.state.value}") }
    }
    private fun notification(): Notification? = context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == ListeningService.NOTIFICATION_ID }?.notification
    @Test fun foregroundSessionSurvivesReaderDestructionAndMediaControlsPreserveAudioPosition() = runBlocking<Unit> {
        val library = Library.get(context); val previous = SystemSpeechSettings.load(context)
        SystemSpeechSettings(engine = instrumentation.context.packageName, voice = "test.en", locale = "en-US", follow = false).save(context)
        val source = File(context.cacheDir, "background-listening.txt").apply { writeText("第一章 后台\n[test-audio-session] background playback ${System.nanoTime()}") }
        val book = library.import(Uri.fromFile(source))
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(context, ReaderActivity::class.java).putExtra("book", book.id)).use { scenario ->
                ListeningSession.start(context, book.id, null, null)
                waitUntil("未开始解码长音频") { ListeningSession.state.value.let { it.phase == ListeningPhase.PLAYING && it.durationMillis >= 14000 && it.positionMillis >= 250 } }
                val before = ListeningSession.state.value.positionMillis
                scenario.moveToState(Lifecycle.State.DESTROYED)
                waitUntil("阅读页销毁后播放没有继续") { ListeningSession.state.value.positionMillis >= before + 300 }
                val posted = notification() ?: error("媒体通知未发布")
                @Suppress("DEPRECATION")
                val token = posted.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION) ?: error("通知没有媒体会话")
                val media = MediaController(context, token)
                media.transportControls.pause()
                waitUntil("系统媒体控制未暂停") { ListeningSession.state.value.phase == ListeningPhase.PAUSED }
                val paused = ListeningSession.state.value.positionMillis
                delay(350)
                assertTrue(kotlin.math.abs(ListeningSession.state.value.positionMillis - paused) <= 40)
                assertEquals(PlaybackState.STATE_PAUSED, media.playbackState?.state)
                assertTrue(File(context.cacheDir, "system-speech").listFiles().orEmpty().isNotEmpty())
                notification()!!.actions.first { it.title.toString() == "继续" }.actionIntent.send()
                waitUntil("通知按钮未从原位置继续") { ListeningSession.state.value.let { it.phase == ListeningPhase.PLAYING && it.positionMillis >= paused + 150 } }
                ListeningSession.sleepUntil(context, System.currentTimeMillis() + 500)
                waitUntil("睡眠定时没有保留位置暂停") { ListeningSession.state.value.phase == ListeningPhase.PAUSED && ListeningSession.state.value.sleepUntil == null }
                assertTrue(ListeningSession.state.value.positionMillis > paused)
                notification()!!.actions.first { it.title.toString() == "停止" }.actionIntent.send()
                waitUntil("通知停止后会话仍活跃") { !ListeningSession.state.value.active }
                waitUntil("停止后临时音频未释放") { File(context.cacheDir, "system-speech").listFiles().orEmpty().isEmpty() }
                waitUntil("停止后媒体通知未移除") { notification() == null }
            }
        } finally {
            if (ListeningSession.state.value.active) ListeningSession.command(context, ListeningSession.STOP)
            waitUntil("听书测试未清理会话") { !ListeningSession.state.value.active }
            previous.save(context); library.books().firstOrNull { it.id == book.id }?.let(library::remove); source.delete()
        }
    }
    @Test fun seriesPlaybackContinuesBetweenOriginalVolumesAndLeavesTheirBytesUnchanged() = runBlocking<Unit> {
        val library = Library.get(context); val previous = SystemSpeechSettings.load(context)
        SystemSpeechSettings(engine = instrumentation.context.packageName, voice = "test.en", locale = "en-US", follow = false).save(context)
        val files = listOf("alpha", "omega").map { name -> File(context.cacheDir, "listen-$name.txt").apply { writeText("第一章 $name\n${name.repeat(5)} ${System.nanoTime()}\n第二章 tail\n${name}_ending") } }
        val originals = files.map { it.readBytes() }; val books = files.map { library.import(Uri.fromFile(it)) }
        var seriesId: String? = null
        try {
            val series = library.saveSeries(null, "听书分卷", books.mapIndexed { index, book -> book.id to "第 ${index + 1} 卷" }, null)
            seriesId = series.id
            ActivityScenario.launch<ReaderActivity>(Intent(context, ReaderActivity::class.java).putExtra("book", books.first().id).putExtra("series", series.id)).use {
                ListeningSession.start(context, books.first().id, series.id, null)
                waitUntil("跨卷朗读没有完成") { ListeningSession.state.value.let { state -> state.phase == ListeningPhase.COMPLETED && state.bookId == books.last().id } }
                assertTrue(ListeningSession.state.value.text.contains("omega_ending"))
                assertTrue(ListeningSession.state.value.title.contains("第 2 卷"))
                files.forEachIndexed { index, file -> assertArrayEquals(originals[index], file.readBytes()) }
                assertEquals(books.map { book -> book.id }, library.series.get(series.id)?.volumes?.map { volume -> volume.bookId })
            }
        } finally {
            if (ListeningSession.state.value.active) ListeningSession.command(context, ListeningSession.STOP)
            waitUntil("分卷听书测试未清理会话") { !ListeningSession.state.value.active }
            previous.save(context); seriesId?.let { library.series.dissolve(it) }
            library.books().filter { it.id in books.map { book -> book.id } }.forEach(library::remove); files.forEach { it.delete() }
        }
    }
}
