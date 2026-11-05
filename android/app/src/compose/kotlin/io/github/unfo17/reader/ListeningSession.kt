package io.github.unfo17.reader

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.readium.r2.shared.publication.Locator

enum class ListeningPhase { STOPPED, PREPARING, PLAYING, PAUSED, COMPLETED, FAILED }
data class ListeningSnapshot(
    val phase: ListeningPhase = ListeningPhase.STOPPED,
    val bookId: String = "", val seriesId: String? = null, val title: String = "", val text: String = "",
    val locator: String? = null, val positionMillis: Long = 0, val durationMillis: Long = 0,
    val speed: Float = 1f, val error: String? = null, val sleepUntil: Long? = null, val sleepAfterChapter: Boolean = false, val missingLanguages: Set<String> = emptySet(),
) {
    val active get() = phase in listOf(ListeningPhase.PREPARING, ListeningPhase.PLAYING, ListeningPhase.PAUSED)
}

/** UI observes playback; the foreground service owns its publications, speech engine and decoder. */
object ListeningSession {
    internal val mutable = MutableStateFlow(ListeningSnapshot())
    val state = mutable.asStateFlow()
    const val START = "io.github.unfo17.reader.listening.START"
    const val PAUSE = "io.github.unfo17.reader.listening.PAUSE"
    const val RESUME = "io.github.unfo17.reader.listening.RESUME"
    const val STOP = "io.github.unfo17.reader.listening.STOP"
    const val SPEED = "io.github.unfo17.reader.listening.SPEED"
    const val CHAPTER_SLEEP = "io.github.unfo17.reader.listening.CHAPTER_SLEEP"
    const val SLEEP = "io.github.unfo17.reader.listening.SLEEP"
    fun start(context: Context, bookId: String, seriesId: String?, locator: Locator?) {
        ContextCompat.startForegroundService(context, Intent(context, ListeningService::class.java).setAction(START)
            .putExtra("book", bookId).putExtra("series", seriesId).putExtra("locator", locator?.toJSON()?.toString()))
    }
    fun continueFromRecord(context: Context, ownerId: String) {
        ContextCompat.startForegroundService(context, Intent(context, ListeningService::class.java).setAction(START).putExtra("resumeOwner", ownerId))
    }
    fun dismissError() { if (mutable.value.phase == ListeningPhase.FAILED) mutable.value = mutable.value.copy(phase = ListeningPhase.STOPPED, error = null) }
    fun command(context: Context, action: String) { context.startService(Intent(context, ListeningService::class.java).setAction(action)) }
    fun sleep(context: Context, minutes: Int) {
        require(minutes in 0..120)
        sleepUntil(context, if (minutes == 0) null else System.currentTimeMillis() + minutes * 60000L)
    }
    fun sleepUntil(context: Context, deadline: Long?) {
        require(deadline == null || deadline - System.currentTimeMillis() in 0..24 * 60 * 60 * 1000L)
        context.startService(Intent(context, ListeningService::class.java).setAction(SLEEP).putExtra("until", deadline ?: 0L))
    }
    fun sleepAfterChapter(context: Context, enabled: Boolean) {
        context.startService(Intent(context, ListeningService::class.java).setAction(CHAPTER_SLEEP).putExtra("enabled", enabled))
    }
    fun speed(context: Context, value: Float) {
        require(value.isFinite() && value in .5f..3f)
        context.startService(Intent(context, ListeningService::class.java).setAction(SPEED).putExtra("speed", value))
    }
}
