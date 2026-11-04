package io.github.unfo17.reader

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderLifecycleTest {
    @Test fun stoppingReaderAfterOriginalRemovalPreservesTombstoneAndNeverCrashes() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val library = Library.get(context)
        val source = File(context.cacheDir, "reader-removal-lifecycle.txt").apply { writeText("第一章 保留记录\n阅读中移除原书 ${System.nanoTime()}") }
        val book = library.import(Uri.fromFile(source))
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(context, ReaderActivity::class.java).putExtra("book", book.id)).use { scenario ->
                val deadline = android.os.SystemClock.uptimeMillis() + 20000
                var ready = false
                while (!ready && android.os.SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { ready = it.supportFragmentManager.fragments.any { fragment -> fragment is EpubNavigatorFragment } }
                    if (!ready) android.os.SystemClock.sleep(100)
                }
                assertTrue(ready)
                library.remove(library.books().first { it.id == book.id })
                scenario.moveToState(Lifecycle.State.CREATED)
                assertTrue(library.records().first { it.id == book.id }.removed)
                assertFalse(library.books().any { it.id == book.id })
            }
            assertTrue(library.records().first { it.id == book.id }.removed)
            assertTrue(source.exists())
        } finally { library.books().firstOrNull { it.id == book.id }?.let(library::remove); source.delete() }
    }
}
