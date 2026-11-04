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
class SpeechAudioOutput(context: Context, private val onInterrupted: () -> Unit = {}) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val manager = this.context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var player: MediaPlayer? = null
    private var completion: CancellableContinuation<Unit>? = null
    private var closed = false
    private var focused = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change -> if (change < 0) { stop(); onInterrupted() } }, main).build()
    private val noisy = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { stop(); onInterrupted() } }
    init { ContextCompat.registerReceiver(this.context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED) }

    suspend fun play(file: File, speed: Float = 1f, keepFocus: Boolean = false): Unit = withContext(Dispatchers.Main.immediate) {
        check(!closed) { "音频播放器已关闭" }
        require(speed.isFinite() && speed in .5f..3f)
        val previous = completion
        release(abandonFocus = !keepFocus); previous?.cancel()
        suspendCancellableCoroutine { continuation ->
            val local = MediaPlayer()
            player = local; completion = continuation
            continuation.invokeOnCancellation { main.post { if (player === local) release() } }
            local.setAudioAttributes(attributes)
            local.setOnPreparedListener {
                if (player !== local || !continuation.isActive) return@setOnPreparedListener
                try {
                    if (!focused && manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw SpeechFailure("AUDIO_FOCUS", "音频正在被其他应用使用，请稍后重试")
                    focused = true
                    if (speed != 1f) local.playbackParams = android.media.PlaybackParams().setSpeed(speed).setPitch(1f)
                    local.start()
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
        release(abandonFocus = error != null || !keepFocus)
        if (result?.isActive == true) { if (error == null) result.resume(Unit) else result.resumeWithException(error) }
    }
    private fun release(abandonFocus: Boolean = true) {
        val previous = player; player = null; completion = null
        previous?.setOnPreparedListener(null); previous?.setOnCompletionListener(null); previous?.setOnErrorListener(null)
        previous?.release()
        if (focused && abandonFocus) { focused = false; manager.abandonAudioFocusRequest(focus) }
    }
    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val previous = completion
        release(); previous?.cancel()
    }
    override fun close() { if (closed) return; closed = true; stop(); context.unregisterReceiver(noisy) }
}
