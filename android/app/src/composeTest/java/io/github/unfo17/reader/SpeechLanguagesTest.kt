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
class SpeechLanguagesTest {
    @Test fun languagePolicyRetainsUpstreamBoundariesAndVoicePreference() {
        assertEquals("zh", SpeechLanguages.code("zho_TW"))
        assertEquals("", SpeechLanguages.code("en<script>"))
        assertEquals("en", SpeechLanguages.paragraph("en-US", "かな", "zh"))
        assertEquals("ja", SpeechLanguages.paragraph("", "かな", "zh"))
        assertEquals("zh", SpeechLanguages.paragraph("", "汉".repeat(40) + "か", "zh"))
        assertEquals("ko", SpeechLanguages.paragraph("und", "한국어", "en"))
        assertEquals("ja", SpeechLanguages.paragraph("", "汉字", "ja"))
        val online = SystemVoice("online", "en-US", 500, 0, true, true)
        val offline = SystemVoice("offline", "eng-GB", 100, 0, false, true)
        assertEquals(offline, SpeechLanguages.voice("en", "@default", listOf(online, offline)))
        assertEquals(online, SpeechLanguages.voice("en", "online", listOf(online, offline)))
        assertNull(SpeechLanguages.voice("en", "@default", listOf(offline), "removed"))
        assertNull(SpeechLanguages.voice("ja", "offline", listOf(offline)))
        assertNull(SpeechLanguages.voice("en", "offline", listOf(offline.copy(installed = false))))
    }
    @Test fun actualEngineSwitchesForTaggedParagraphsAndSkipsMissingModels() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val library = Library.get(context); val previous = SystemSpeechSettings.load(context)
        val nonce = System.nanoTime().toString()
        val source = File(context.cacheDir, "speech-languages-$nonce.epub")
        ZipOutputStream(source.outputStream()).use { zip ->
            fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("content.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">listen-$nonce</dc:identifier><dc:title>段内章节听书</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-11-05T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="body" href="body.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="body"/></spine></package>""")
            entry("nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol><li><a href="body.xhtml#a">第一节</a></li><li><a href="body.xhtml#%E7%AC%AC%E4%BA%8C%E8%8A%82">第二节</a></li></ol></nav></body></html>""")
            entry("body.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Language</title></head><body><p id="a" lang="en-US">FIRST_$nonce</p><p id="第二节" xml:lang="zh-CN">中文 SECOND_$nonce</p><p lang="ja">かな MISSING_$nonce</p></body></html>""")
        }
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { if (intent != null) requests += intent.getStringExtra("text").orEmpty() to intent.getStringExtra("voice").orEmpty() } }
        ContextCompat.registerReceiver(context, receiver, IntentFilter("io.github.unfo17.reader.TEST_SPEECH_REQUEST"), ContextCompat.RECEIVER_EXPORTED)
        val book = library.import(Uri.fromFile(source))
        SystemSpeechSettings(engine = instrumentation.context.packageName, voice = "test.en", locale = "en-US", follow = false, autoLanguage = true).save(context)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ListeningSession.start(context, book.id, null, null)
                withTimeout(20000) { while (ListeningSession.state.value.phase != ListeningPhase.COMPLETED) { check(ListeningSession.state.value.phase != ListeningPhase.FAILED) { ListeningSession.state.value.error.orEmpty() }; delay(25) } }
                assertEquals("test.en", requests.single { it.first.contains("FIRST_") }.second)
                assertEquals("test.zh", requests.single { it.first.contains("SECOND_") }.second)
                assertTrue(requests.none { it.first.contains("MISSING_") })
                assertEquals(setOf("ja"), ListeningSession.state.value.missingLanguages)
            }
        } finally {
            if (ListeningSession.state.value.active) ListeningSession.command(context, ListeningSession.STOP)
            withTimeout(5000) { while (ListeningSession.state.value.active) delay(25) }
            context.unregisterReceiver(receiver); previous.save(context); ListeningStore(context).remove(book.id)
            library.books().firstOrNull { it.id == book.id }?.let(library::remove); source.delete()
        }
    }
}
