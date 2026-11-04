@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package io.github.unfo17.reader

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mewx.wenku8_flutter.TextImportRules
import org.mewx.wenku8_flutter.TextRuleClient
import java.io.File

@RunWith(AndroidJUnit4::class)
class TextRulesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test fun isolatedRulesMatchCustomLinesAndRecoverFromInvalidExpressions() {
        val rules = TextImportRules(customToc = true, chapterRule = "^CH\\d+", volumeRule = "^VOL\\d+")
        TextRuleClient(context, rules).use { client -> assertEquals(listOf(1, 2, 0, 0), client.classify(listOf("VOL1", "CH2", "第一章 正文", "=== CH3 ==="))) }
        assertTrue(runCatching { TextRuleClient(context, rules.copy(chapterRule = "[")).use { } }.isFailure)
        TextRuleClient(context, rules.copy(volumeRule = "")).use { client -> assertEquals(listOf(0, 2), client.classify(listOf("VOL1", "CH1"))) }
    }
    @Test fun runawayExpressionDiesInItsOwnProcessAndMainThreadRemainsResponsive() = runBlocking<Unit> {
        val rules = TextImportRules(customToc = true, chapterRule = "^((a|aa)+)+$", volumeRule = "")
        val client = withContext(Dispatchers.IO) { TextRuleClient(context, rules) }
        val started = java.util.concurrent.CountDownLatch(1)
        val begin = android.os.SystemClock.elapsedRealtime()
        val match = async(Dispatchers.IO) { client.use { started.countDown(); runCatching { it.classify(listOf("a".repeat(88) + "!")) } } }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        var mainResponsive = false
        instrumentation.runOnMainSync { mainResponsive = true }
        assertTrue(mainResponsive)
        val result = withTimeout(8000) { match.await() }
        assertTrue("A timed-out native regex must not return a successful classification", result.isFailure)
        assertTrue(android.os.SystemClock.elapsedRealtime() - begin < 8000)
        withContext(Dispatchers.IO) { TextRuleClient(context, rules.copy(chapterRule = "^OK")).use { assertEquals(listOf(2), it.classify(listOf("OK"))) } }
    }
    @Test fun customVolumeTreeEncodingAndStoredRulesSurviveReimportAndBackup() = runBlocking<Unit> {
        val library = Library.get(context); val preferences = context.getSharedPreferences("import-options", 0)
        val old = preferences.getString("textRules", null)
        val rules = TextImportRules(customToc = true, chapterRule = "^CH\\d+", volumeRule = "^VOL\\d+", autoTitles = false, encoding = "GB18030")
        val source = File(context.cacheDir, "custom-directory.txt").apply { writeBytes(("无标题前言。${System.nanoTime()}\nVOL1\nCH1\n第一段正文。\nCH2\n第二段正文。\nVOL2\nCH3\n第三段正文。").toByteArray(charset("GB18030"))) }
        val backup = File(context.cacheDir, "custom-directory-backup.zip")
        var book: Book? = null
        try {
            preferences.edit().putString("textRules", rules.json()).commit()
            book = library.import(Uri.fromFile(source))
            val imported = book
            assertEquals(rules.json(), imported.textImportRules)
            val pub = library.openPublication(imported)
            val location = try {
                assertEquals("正文", pub.tableOfContents.first().title)
                assertEquals(listOf("CH1", "CH2"), pub.tableOfContents.first { it.title == "VOL1" }.children.map { it.title })
                assertEquals(listOf("CH3"), pub.tableOfContents.first { it.title == "VOL2" }.children.map { it.title })
                pub.locatorFromLink(pub.readingOrder.last())!!.toJSON().toString()
            } finally { pub.close() }
            library.update(imported.copy(locator = location, progress = .73, bookmarks = listOf(location)))
            preferences.edit().putString("textRules", TextImportRules().json()).commit()
            library.publicationFile(imported).delete()
            val rebuilt = library.openPublication(library.books().first { it.id == imported.id })
            try { assertTrue(rebuilt.tableOfContents.any { link -> link.title == "VOL1" }) } finally { rebuilt.close() }
            exportLibrary(context, Uri.fromFile(backup))
            library.remove(library.books().first { it.id == imported.id })
            val reimported = library.import(Uri.fromFile(source))
            assertEquals(rules.json(), reimported.textImportRules)
            assertEquals(location, reimported.locator)
            library.remove(reimported)
            restoreLibrary(context, Uri.fromFile(backup))
            val restored = library.books().first { it.id == imported.id }
            assertEquals(rules.json(), restored.textImportRules)
            assertEquals(listOf(location), restored.bookmarks)
            library.openPublication(restored).close()
        } finally {
            book?.let { original -> library.books().firstOrNull { it.id == original.id }?.let(library::remove) }
            source.delete(); backup.delete()
            if (old == null) preferences.edit().remove("textRules").commit() else preferences.edit().putString("textRules", old).commit()
        }
    }
    @Test fun invalidCustomRuleImportRollsBackOriginalAndMetadata() = runBlocking<Unit> {
        val library = Library.get(context); val preferences = context.getSharedPreferences("import-options", 0)
        val old = preferences.getString("textRules", null)
        val source = File(context.cacheDir, "invalid-directory-rule.txt").apply { writeText("第一章 规则失败\n原书应保留 ${System.nanoTime()}") }
        val before = library.records()
        val files = File(context.filesDir, "books").listFiles()?.map { it.name }?.toSet().orEmpty()
        try {
            preferences.edit().putString("textRules", TextImportRules(customToc = true, chapterRule = "[").json()).commit()
            assertTrue(runCatching { library.import(Uri.fromFile(source)) }.isFailure)
            assertEquals(before, library.records())
            assertEquals(files, File(context.filesDir, "books").listFiles()?.map { it.name }?.toSet().orEmpty())
            assertTrue(source.exists())
        } finally {
            source.delete()
            if (old == null) preferences.edit().remove("textRules").commit() else preferences.edit().putString("textRules", old).commit()
        }
    }
}
