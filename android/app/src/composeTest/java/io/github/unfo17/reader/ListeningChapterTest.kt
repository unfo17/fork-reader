package io.github.unfo17.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ListeningChapterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private suspend fun waitUntil(description: String, predicate: () -> Boolean) {
        try { withTimeout(20000) { while (!predicate()) delay(25) } }
        catch (e: kotlinx.coroutines.TimeoutCancellationException) { error("$description：${ListeningSession.state.value}") }
    }
    @Test fun chapterTimerStopsBeforeAnInlineTocAnchorWithoutSynthesizingTheNextChapter() = runBlocking<Unit> {
        val library = Library.get(context); val previous = SystemSpeechSettings.load(context)
        val nonce = System.nanoTime().toString()
        val first = "[test-audio-long] FIRST_$nonce "
        val second = "[test-audio-long] SECOND_$nonce"
        val source = File(context.cacheDir, "listening-inline-$nonce.epub")
        ZipOutputStream(source.outputStream()).use { zip ->
            fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("content.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">listen-$nonce</dc:identifier><dc:title>段内章节听书</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-11-05T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="body" href="body.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="body"/></spine></package>""")
            entry("nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol><li><a href="body.xhtml#a">第一节</a></li><li><a href="body.xhtml#%E7%AC%AC%E4%BA%8C%E8%8A%82">第二节</a></li></ol></nav></body></html>""")
            entry("body.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>正文</title></head><body><p id="a">$first<span id="第二节"></span>$second</p></body></html>""")
        }
        val requests = CopyOnWriteArrayList<String>()
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { intent?.getStringExtra("text")?.let(requests::add) } }
        ContextCompat.registerReceiver(context, receiver, IntentFilter("io.github.unfo17.reader.TEST_SPEECH_REQUEST"), ContextCompat.RECEIVER_EXPORTED)
        val book = library.import(Uri.fromFile(source))
        SystemSpeechSettings(engine = instrumentation.context.packageName, voice = "test.en", locale = "en-US", follow = false).save(context)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ListeningSession.start(context, book.id, null, null)
                waitUntil("第一节未开始播放") { ListeningSession.state.value.let { it.phase == ListeningPhase.PLAYING && it.text.contains("FIRST_") } }
                assertFalse(ListeningSession.state.value.text.contains("SECOND_"))
                ListeningSession.sleepAfterChapter(context, true)
                waitUntil("段内章节结束未暂停") { ListeningSession.state.value.phase == ListeningPhase.PAUSED }
                assertFalse(ListeningSession.state.value.sleepAfterChapter)
                assertTrue(ListeningSession.state.value.text.contains("FIRST_"))
                delay(300)
                assertTrue(requests.none { it.contains("SECOND_") })
                assertFalse(ListeningStore(context).cursor(book.id)!!.completed)
                ListeningSession.command(context, ListeningSession.RESUME)
                waitUntil("继续后未读第二节") { ListeningSession.state.value.let { it.phase == ListeningPhase.PLAYING && it.text.contains("SECOND_") } }
                assertEquals(1, requests.count { it.contains("FIRST_") })
                assertEquals(1, requests.count { it.contains("SECOND_") })
                assertFalse(ListeningSession.state.value.text.contains("FIRST_"))
                ListeningSession.sleepAfterChapter(context, true)
                waitUntil("全书最后一章未按要求暂停") { ListeningSession.state.value.phase == ListeningPhase.PAUSED }
                assertFalse(ListeningSession.state.value.sleepAfterChapter)
                assertFalse(ListeningStore(context).cursor(book.id)!!.completed)
                ListeningSession.command(context, ListeningSession.RESUME)
                waitUntil("最后一章续播后未结束") { ListeningSession.state.value.phase == ListeningPhase.COMPLETED }
            }
        } finally {
            if (ListeningSession.state.value.active) ListeningSession.command(context, ListeningSession.STOP)
            waitUntil("章节听书清理失败") { !ListeningSession.state.value.active }
            context.unregisterReceiver(receiver); ListeningStore(context).remove(book.id)
            previous.save(context); library.books().firstOrNull { it.id == book.id }?.let(library::remove); source.delete()
        }
    }

    @Test fun lastChapterTimerPausesBeforeTheNextSeriesVolumeAndCanBeCancelled() = runBlocking<Unit> {
        val library = Library.get(context); val previous = SystemSpeechSettings.load(context)
        SystemSpeechSettings(engine = instrumentation.context.packageName, voice = "test.en", locale = "en-US", follow = false).save(context)
        val sources = listOf("upper", "lower").map { File(context.cacheDir, "chapter-sleep-$it.txt").apply { writeText("第一章 $it\n[test-audio-long] ${it}_${System.nanoTime()}") } }
        val books = sources.map { library.import(Uri.fromFile(it)) }
        val series = library.saveSeries(null, "听书章节定时", books.map { it.id to it.title }, null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ListeningSession.start(context, books[0].id, series.id, null)
                waitUntil("上卷未播放") { ListeningSession.state.value.let { it.phase == ListeningPhase.PLAYING && it.durationMillis >= 2900 } }
                ListeningSession.sleepAfterChapter(context, true)
                waitUntil("上卷结束未暂停") { ListeningSession.state.value.phase == ListeningPhase.PAUSED }
                assertEquals(books[0].id, ListeningSession.state.value.bookId)
                ListeningSession.command(context, ListeningSession.RESUME)
                waitUntil("下卷未继续") { ListeningSession.state.value.let { it.phase == ListeningPhase.PLAYING && it.bookId == books[1].id && it.durationMillis >= 2900 } }
                ListeningSession.sleepAfterChapter(context, true)
                waitUntil("章节定时未开启") { ListeningSession.state.value.sleepAfterChapter }
                ListeningSession.sleep(context, 0)
                waitUntil("章节定时未关闭") { !ListeningSession.state.value.sleepAfterChapter }
                waitUntil("关闭定时后未完成") { ListeningSession.state.value.phase == ListeningPhase.COMPLETED }
            }
        } finally {
            if (ListeningSession.state.value.active) ListeningSession.command(context, ListeningSession.STOP)
            waitUntil("分卷章节测试清理失败") { !ListeningSession.state.value.active }
            ListeningStore(context).remove(series.id); library.series.dissolve(series.id); previous.save(context)
            library.books().filter { it.id in books.map { b -> b.id } }.forEach(library::remove); sources.forEach { it.delete() }
        }
    }
}
