@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package io.github.unfo17.reader

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.mewx.wenku8_flutter.SpeechSegments
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content

/** Native counterpart of the upstream listening controller/output: playback outlives reader pages. */
class ListeningService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var client: SystemSpeechClient
    private lateinit var audio: SpeechAudioOutput
    private lateinit var media: MediaSession
    private lateinit var notifications: NotificationManager
    private lateinit var wake: PowerManager.WakeLock
    private var generation = 0
    private var playback: Job? = null
    private var timer: Job? = null
    private var paused = false
    private var foreground = false
    private var decoding = false
    private var lastNotification = 0L
    private var lastWakeRenewal = 0L
    private val resumes = Channel<Unit>(Channel.CONFLATED)
    private var snapshot: ListeningSnapshot
        get() = ListeningSession.mutable.value
        set(value) { ListeningSession.mutable.value = value }

    override fun onCreate() {
        super.onCreate()
        client = SystemSpeechClient(this)
        audio = SpeechAudioOutput(this, pauseOnInterruption = true) { pause() }
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "听书播放", NotificationManager.IMPORTANCE_LOW))
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fork-reader:listening").apply { setReferenceCounted(false) }
        media = MediaSession(this, "本地书房听书").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onStop() = finish(ListeningPhase.STOPPED)
                override fun onSeekTo(pos: Long) { audio.seekTo(pos); updateProgress() }
                override fun onFastForward() { audio.seekTo(audio.positionMillis + 10000); updateProgress() }
                override fun onRewind() { audio.seekTo(audio.positionMillis - 10000); updateProgress() }
            })
        }
        scope.launch {
            while (isActive) {
                if (snapshot.active) {
                    updateProgress()
                    if (!paused && SystemClock.elapsedRealtime() - lastWakeRenewal > 5 * 60 * 1000) acquireWake()
                }
                delay(250)
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try { when (intent?.action) {
            ListeningSession.START -> start(intent)
            ListeningSession.PAUSE -> if (snapshot.active) pause() else stopSelf(startId)
            ListeningSession.RESUME -> if (snapshot.active) resume() else stopSelf(startId)
            ListeningSession.STOP -> finish(ListeningPhase.STOPPED)
            ListeningSession.SLEEP -> if (snapshot.active) setTimer(intent.getLongExtra("until", 0L).takeIf { it > 0 }) else stopSelf(startId)
            ListeningSession.SPEED -> if (snapshot.active) {
                val speed = intent.getFloatExtra("speed", 1f)
                require(speed.isFinite() && speed in .5f..3f)
                audio.setSpeed(speed); snapshot = snapshot.copy(speed = speed); publish(force = true)
            } else stopSelf(startId)
            else -> stopSelf(startId)
        } } catch (e: Exception) { finish(ListeningPhase.FAILED, e.message ?: "听书控制失败") }
        return START_NOT_STICKY
    }
    private fun start(intent: Intent) {
        val epoch = ++generation
        playback?.cancel(); audio.stop(); timer?.cancel(); paused = false
        val bookId = intent.getStringExtra("book").orEmpty()
        val seriesId = intent.getStringExtra("series")
        val initial = intent.getStringExtra("locator")?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        snapshot = ListeningSnapshot(ListeningPhase.PREPARING, bookId, seriesId)
        media.isActive = true
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        foreground = true; acquireWake()
        playback = scope.launch {
            try {
                val library = Library.get(this@ListeningService)
                val series = seriesId?.let { library.series.get(it) }?.takeIf { !it.deleted && it.volumes.any { part -> part.bookId == bookId } }
                val originals = series?.volumes?.map { it.bookId }?.dropWhile { it != bookId } ?: listOf(bookId)
                var any = false
                originals.forEachIndexed { index, originalId ->
                    ensureActive()
                    val book = library.books().firstOrNull { it.id == originalId } ?: error("分卷原书已移除，请重新导入后继续听书")
                    var publication: Publication? = null
                    try {
                        // Assign ownership inside IO before returning across a cancellable boundary.
                        withContext(Dispatchers.IO) { library.openPublication(book).also { publication = it } }
                        val iterator = withContext(Dispatchers.IO) { publication!!.content(if (index == 0) initial else null)?.iterator() }
                            ?: error("这本书没有可提取的朗读正文")
                        while (true) {
                            ensureActive()
                            val paragraph = withContext(Dispatchers.IO) { iterator.nextOrNull() } ?: break
                            if (paragraph !is Content.TextualElement) continue
                            val text = paragraph.text.orEmpty()
                            for (segment in SpeechSegments.split(text)) {
                                while (paused) resumes.receive()
                                ensureActive()
                                val preferences = SystemSpeechSettings.load(this@ListeningService)
                                val locator = paragraph.locator.copy(text = Locator.Text(textSuffix(text.substring((segment.start - 81).coerceAtLeast(0), segment.start), 80), text.substring(segment.start, segment.end), textPrefix(text.substring(segment.end, minOf(text.length, segment.end + 101)), 100)))
                                snapshot = snapshot.copy(phase = ListeningPhase.PREPARING, bookId = book.id, seriesId = series?.id,
                                    title = series?.let { "${it.title} · ${it.volumes.first { volume -> volume.bookId == book.id }.title}" } ?: book.title,
                                    text = segment.text, locator = locator.toJSON().toString(), positionMillis = 0, durationMillis = 0, speed = preferences.speed)
                                publish()
                                decoding = false
                                val file = client.synthesize(segment.text, preferences.engine, preferences.voice, preferences.locale)
                                try {
                                    any = true; decoding = true
                                    audio.play(file, preferences.speed, keepFocus = true, startPlaying = !paused)
                                    snapshot = snapshot.copy(positionMillis = audio.positionMillis, durationMillis = audio.durationMillis)
                                } finally { decoding = false; file.delete() }
                            }
                        }
                    } finally { withContext(NonCancellable + Dispatchers.IO) { publication?.close() } }
                }
                if (!any) error("当前位置没有可朗读的文字")
                if (epoch == generation) finish(ListeningPhase.COMPLETED)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { coroutineContext.ensureActive(); if (epoch == generation) finish(ListeningPhase.FAILED, e.message ?: "听书失败，请检查系统语音设置") }
        }
    }
    private fun pause() {
        if (!snapshot.active) return
        paused = true; audio.pause(); releaseWake()
        snapshot = snapshot.copy(phase = ListeningPhase.PAUSED,
            positionMillis = if (decoding) audio.positionMillis else snapshot.positionMillis,
            durationMillis = if (decoding) audio.durationMillis else snapshot.durationMillis)
        publish(force = true)
    }
    private fun resume() {
        if (!snapshot.active) return
        if (snapshot.sleepUntil?.let { it <= System.currentTimeMillis() } == true) { timer?.cancel(); snapshot = snapshot.copy(sleepUntil = null) }
        try {
            audio.resume(); paused = false; resumes.trySend(Unit); acquireWake()
            snapshot = snapshot.copy(phase = if (audio.ready) ListeningPhase.PLAYING else ListeningPhase.PREPARING, error = null)
            publish(force = true)
        } catch (e: Exception) { paused = true; snapshot = snapshot.copy(phase = ListeningPhase.PAUSED, error = e.message); publish(force = true) }
    }
    private fun setTimer(until: Long?) {
        require(until == null || until in 1..1000000000000000L)
        timer?.cancel()
        snapshot = snapshot.copy(sleepUntil = until)
        if (until != null) timer = scope.launch { delay((until - System.currentTimeMillis()).coerceAtLeast(0)); snapshot = snapshot.copy(sleepUntil = null); pause() }
        publish(force = true)
    }
    private fun updateProgress() {
        val nextPhase = if (paused) ListeningPhase.PAUSED else if (decoding && audio.ready) ListeningPhase.PLAYING else ListeningPhase.PREPARING
        snapshot = snapshot.copy(phase = nextPhase, positionMillis = if (decoding) audio.positionMillis else snapshot.positionMillis, durationMillis = if (decoding) audio.durationMillis else snapshot.durationMillis)
        publish()
    }
    private fun acquireWake() { wake.acquire(10 * 60 * 1000L); lastWakeRenewal = SystemClock.elapsedRealtime() }
    private fun releaseWake() { if (wake.isHeld) wake.release() }
    private fun finish(phase: ListeningPhase, error: String? = null) {
        generation++; playback?.cancel(); audio.stop(); timer?.cancel(); releaseWake()
        snapshot = snapshot.copy(phase = phase, error = error, sleepUntil = null)
        media.isActive = false
        if (foreground) { foreground = false; stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }
    @SuppressLint("MissingPermission", "NotificationPermission") // MediaSession notifications are exempt from POST_NOTIFICATIONS.
    private fun publish(force: Boolean = false) {
        val state = snapshot
        media.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, state.title).build())
        val phase = when (state.phase) {
            ListeningPhase.PLAYING -> PlaybackState.STATE_PLAYING
            ListeningPhase.PAUSED -> PlaybackState.STATE_PAUSED
            ListeningPhase.PREPARING -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_STOPPED
        }
        media.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
            .setState(phase, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (phase == PlaybackState.STATE_PLAYING) state.speed else 0f).build())
        if (foreground && (force || SystemClock.elapsedRealtime() - lastNotification >= 1000)) {
            lastNotification = SystemClock.elapsedRealtime(); notifications.notify(NOTIFICATION_ID, notification())
        }
    }
    private fun action(command: String): PendingIntent = PendingIntent.getService(this, command.hashCode(), Intent(this, ListeningService::class.java).setAction(command), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification(): Notification {
        val state = snapshot
        val open = PendingIntent.getActivity(this, 0, Intent(this, ReaderActivity::class.java).putExtra("book", state.bookId).putExtra("series", state.seriesId).putExtra("jumpLocator", state.locator), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val paused = state.phase == ListeningPhase.PAUSED
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.title.ifBlank { "本地书房听书" }).setContentText(textPrefix(state.text.ifBlank { "正在准备朗读" }, 100))
            .setContentIntent(open).setDeleteIntent(action(ListeningSession.STOP)).setOnlyAlertOnce(true).setOngoing(!paused).setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause, if (paused) "继续" else "暂停", action(if (paused) ListeningSession.RESUME else ListeningSession.PAUSE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", action(ListeningSession.STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken).setShowActionsInCompactView(0, 1)).build()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (paused || !snapshot.active) finish(ListeningPhase.STOPPED)
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        generation++; scope.cancel(); audio.close(); client.close(); releaseWake(); media.release()
        if (snapshot.active) snapshot = snapshot.copy(phase = ListeningPhase.STOPPED, sleepUntil = null)
        super.onDestroy()
    }
    companion object { const val CHANNEL = "listening"; const val NOTIFICATION_ID = 0x574b38 }
}
