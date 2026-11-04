package io.github.unfo17.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Plays the original speech core's WAV output, retaining Android speech audio-focus policy. */
class SpeechAudioOutput(context: Context, private val pauseOnInterruption: Boolean = false, private val onInterrupted: () -> Unit = {}) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val manager = this.context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var player: MediaPlayer? = null
    private var completion: CancellableContinuation<Unit>? = null
    private var closed = false
    private var focused = false
    private var prepared = false
    private var requested = true
    private var speed = 1f
    private var keepFocus = false
    private var lastPosition = 0L
    private var lastDuration = 0L
    val ready get() = prepared
    val positionMillis: Long get() = if (prepared) runCatching { player?.currentPosition?.toLong() ?: lastPosition }.getOrDefault(lastPosition) else lastPosition
    val durationMillis: Long get() = if (prepared) runCatching { player?.duration?.toLong()?.coerceAtLeast(0) ?: lastDuration }.getOrDefault(lastDuration) else lastDuration
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener({ change -> if (change < 0) interrupt() }, main).build()
    private fun interrupt() { if (pauseOnInterruption) pause() else stop(); onInterrupted() }
    private val noisy = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { interrupt() } }
    init { ContextCompat.registerReceiver(this.context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED) }

    suspend fun play(file: File, speed: Float = 1f, keepFocus: Boolean = false, startPlaying: Boolean = true): Unit = withContext(Dispatchers.Main.immediate) {
        check(!closed) { "音频播放器已关闭" }
        require(speed.isFinite() && speed in .5f..3f)
        val previous = completion
        release(abandonFocus = !keepFocus); previous?.cancel()
        this@SpeechAudioOutput.speed = speed; this@SpeechAudioOutput.keepFocus = keepFocus
        requested = startPlaying; lastPosition = 0; lastDuration = 0
        suspendCancellableCoroutine { continuation ->
            val local = MediaPlayer()
            player = local; completion = continuation
            continuation.invokeOnCancellation { main.post { if (player === local) release() } }
            local.setAudioAttributes(attributes)
            local.setWakeMode(context, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            local.setOnPreparedListener {
                if (player !== local || !continuation.isActive) return@setOnPreparedListener
                prepared = true
                try {
                    if (requested) startPrepared(local)
                } catch (e: Exception) { finish(local, e, keepFocus) }
            }
            local.setOnCompletionListener { finish(local, null, keepFocus) }
            local.setOnErrorListener { _, _, _ -> finish(local, SpeechFailure("AUDIO_DECODE", "语音音频播放失败，请重新检测音色"), keepFocus); true }
            try { local.setDataSource(file.path); local.prepareAsync() }
            catch (e: Exception) { finish(local, e, keepFocus) }
        }
    }
    private fun finish(local: MediaPlayer, error: Exception?, keepFocus: Boolean) {
        if (player !== local) return
        val result = completion
        val duration = durationMillis
        release(abandonFocus = error != null || !keepFocus)
        if (error == null) { lastDuration = duration; lastPosition = duration }
        if (result?.isActive == true) { if (error == null) result.resume(Unit) else result.resumeWithException(error) }
    }
    private fun release(abandonFocus: Boolean = true) {
        lastPosition = positionMillis; lastDuration = durationMillis
        val previous = player; player = null; completion = null
        prepared = false
        previous?.setOnPreparedListener(null); previous?.setOnCompletionListener(null); previous?.setOnErrorListener(null)
        previous?.release()
        if (focused && abandonFocus) { focused = false; manager.abandonAudioFocusRequest(focus) }
    }
    private fun startPrepared(local: MediaPlayer) {
        if (!focused && manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw SpeechFailure("AUDIO_FOCUS", "音频正在被其他应用使用，请稍后重试")
        focused = true
        // start() in PlaybackCompleted restarts from zero. Forward the completion instead.
        if (durationMillis > 0 && positionMillis >= durationMillis) { finish(local, null, keepFocus); return }
        local.playbackParams = android.media.PlaybackParams().setSpeed(speed).setPitch(1f)
        local.start()
    }
    fun pause() {
        check(Looper.myLooper() == Looper.getMainLooper())
        requested = false
        if (prepared && player?.isPlaying == true) player?.pause()
        if (focused) { focused = false; manager.abandonAudioFocusRequest(focus) }
    }
    fun resume() {
        check(Looper.myLooper() == Looper.getMainLooper())
        requested = true
        val local = player ?: return
        if (prepared) try { startPrepared(local) } catch (e: Exception) { requested = false; throw e }
    }
    fun seekTo(position: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (prepared) player?.seekTo(position.coerceIn(0, durationMillis), MediaPlayer.SEEK_CLOSEST)
    }
    fun setSpeed(value: Float) {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(value.isFinite() && value in .5f..3f)
        speed = value
        // Setting non-zero PlaybackParams can start a paused decoder. Defer until resume.
        if (prepared && requested) player?.playbackParams = android.media.PlaybackParams().setSpeed(value).setPitch(1f)
    }
    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val previous = completion
        release(); previous?.cancel()
    }
    override fun close() { if (closed) return; closed = true; stop(); context.unregisterReceiver(noisy) }
}
