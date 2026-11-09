package com.andro6tube.player

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.andro6tube.data.Db
import com.andro6tube.data.Prefs
import com.andro6tube.data.Repo
import com.andro6tube.data.Video
import com.andro6tube.ui.KeyLog
import com.andro6tube.ui.PlayerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class PlaybackService : MediaSessionService() {

    private val scope = MainScope()
    private lateinit var exo: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var queuePlayer: QueuePlayer
    private lateinit var session: MediaSession
    private val db by lazy { Db.get(this) }

    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var retried = false
    private var videoEnabled = true
    private var currentHeightOverride = 0

    private val st get() = Playback.mutableState

    override fun onCreate() {
        super.onCreate()

        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
                    .setMaxVideoSize(Int.MAX_VALUE, Prefs.maxHeight)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setPreferredAudioMimeType(MimeTypes.AUDIO_AAC)
            )
        }

        // Умеренные буферы: старому ГУ не нужно держать минуты видео в памяти
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 1_000, 2_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)

        exo = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus= */ true, // навигатор приглушит/поставит на паузу
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        exo.addListener(playerListener)
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                Log.i(TAG, "video decoder: $decoderName")
                st.value = st.value.copy(decoder = decoderName)
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                Log.d(TAG, "dropped $droppedFrames frames in ${elapsedMs}ms")
            }
        })

        queuePlayer = QueuePlayer(exo, onNext = ::next, onPrevious = ::previous)

        val openPlayer = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, queuePlayer)
            .setSessionActivity(openPlayer)
            .setCallback(sessionCallback)
            .build()
        addSession(session)

        Playback.service = this
        Playback.takePending()?.let { (q, i) -> setQueue(q, i) }
        startPositionSaver()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Смахнули приложение: если на паузе — освобождаем ресурсы
        if (!exo.playWhenReady || exo.mediaItemCount == 0) {
            saveCurrentPosition()
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveCurrentPosition()
        Playback.service = null
        scope.cancel()
        session.release()
        exo.removeListener(playerListener)
        exo.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Очередь

    fun setQueue(queue: List<Video>, index: Int) {
        saveCurrentPosition()
        st.value = st.value.copy(queue = queue, index = index.coerceIn(0, queue.lastIndex), related = emptyList())
        load(resetOverride = true)
    }

    fun next() {
        val s = st.value
        when {
            s.index < s.queue.lastIndex -> moveTo(s.index + 1)
            s.related.isNotEmpty() -> playRelated(0)
            else -> exo.seekTo(exo.duration.coerceAtLeast(0)) // нечего играть дальше
        }
    }

    fun previous() {
        val s = st.value
        if (s.index > 0) moveTo(s.index - 1) else exo.seekTo(0)
    }

    fun jumpTo(index: Int) {
        if (index in st.value.queue.indices) moveTo(index)
    }

    /** Похожее видео: вставляем его в очередь сразу после текущего. */
    fun playRelated(relatedIndex: Int) {
        val s = st.value
        val v = s.related.getOrNull(relatedIndex) ?: return
        val q = s.queue.toMutableList()
        q.add(s.index + 1, v)
        st.value = s.copy(queue = q)
        moveTo(s.index + 1)
    }

    private fun moveTo(index: Int) {
        saveCurrentPosition()
        st.value = st.value.copy(index = index, related = emptyList())
        load(resetOverride = false)
    }

    /** Для отрисовки видео в PlayerView (тот же процесс). */
    val player: Player get() = exo

    val positionMs: Long get() = exo.currentPosition
    val durationMs: Long get() = exo.duration
    fun seekTo(ms: Long) = exo.seekTo(ms)

    fun togglePlay() {
        if (exo.playbackState == Player.STATE_IDLE && st.value.error != null) {
            retry(); return
        }
        if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
        exo.playWhenReady = !exo.playWhenReady
    }

    fun seekBy(deltaMs: Long) {
        val dur = exo.duration
        var pos = exo.currentPosition + deltaMs
        if (dur != C.TIME_UNSET) pos = pos.coerceAtMost(dur - 1000)
        exo.seekTo(pos.coerceAtLeast(0))
    }

    fun setQuality(height: Int) {
        Prefs.maxHeight = height
        currentHeightOverride = height
        trackSelector.setParameters(trackSelector.buildUponParameters().setMaxVideoSize(Int.MAX_VALUE, height))
        reloadKeepingPosition()
    }

    fun retry() {
        st.value.current?.let { Repo.invalidate(it.url) }
        reloadKeepingPosition(force = true)
    }

    private fun reloadKeepingPosition(force: Boolean = false) {
        val pos = exo.currentPosition
        load(resetOverride = false, startAt = pos.takeIf { it > 0 }, force = force)
    }

    fun setVideoEnabled(enabled: Boolean) {
        if (videoEnabled == enabled) return
        videoEnabled = enabled
        // В фоне отключаем видеодорожку: экономия CPU и трафика, звук продолжает играть
        trackSelector.setParameters(
            trackSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
        )
    }

    // ------------------------------------------------------------------ Загрузка

    private fun load(resetOverride: Boolean, startAt: Long? = null, force: Boolean = false) {
        val video = st.value.current ?: return
        if (resetOverride) currentHeightOverride = 0
        loadJob?.cancel()
        prefetchJob?.cancel()
        if (!force) retried = false
        st.value = st.value.copy(loading = true, error = null)
        exo.stop()

        loadJob = scope.launch {
            try {
                val info = Repo.streamInfo(video.url, force)
                val full = if (video.title.isEmpty()) Video.from(info) else video
                if (full !== video) replaceCurrent(full)

                val maxH = if (currentHeightOverride > 0) currentHeightOverride else Prefs.maxHeight
                val built = withContext(Dispatchers.Default) {
                    SourceBuilder.build(info, full, maxH, Prefs.prefer30fps)
                }

                val resumeAt = startAt ?: withContext(Dispatchers.IO) { resumePosition(full) }
                exo.setMediaSource(built.source, resumeAt)
                exo.prepare()
                exo.playWhenReady = true

                st.value = st.value.copy(
                    loading = false,
                    quality = built.label,
                    heights = SourceBuilder.availableHeights(info),
                    related = Repo.related(info).filter { it.url != full.url },
                )
                withContext(Dispatchers.IO) { db.addHistory(full) }
                prefetchNext()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "load failed: ${video.url}", e)
                st.value = st.value.copy(loading = false, error = humanError(e))
            }
        }
    }

    private fun replaceCurrent(v: Video) {
        val s = st.value
        val q = s.queue.toMutableList()
        if (s.index in q.indices) q[s.index] = v
        st.value = s.copy(queue = q)
    }

    private fun resumePosition(v: Video): Long {
        if (!Prefs.resume) return 0
        val pos = db.position(v.url)
        val dur = v.durationSec * 1000
        return if (pos > 10_000 && (dur <= 0 || pos < dur - 15_000)) pos else 0
    }

    /** Заранее получаем данные следующего видео — переключение кнопкой руля почти мгновенное. */
    private fun prefetchNext() {
        val s = st.value
        val nextUrl = s.queue.getOrNull(s.index + 1)?.url
            ?: if (Prefs.autoplay) s.related.firstOrNull()?.url else null
        nextUrl ?: return
        prefetchJob = scope.launch {
            delay(3_000) // сначала даём разогнаться текущему видео
            runCatching { Repo.streamInfo(nextUrl) }
        }
    }

    // ------------------------------------------------------------------ События плеера

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                st.value.current?.let { v -> scope.launch(Dispatchers.IO) { db.savePosition(v.url, 0) } }
                val s = st.value
                if (s.index < s.queue.lastIndex || (Prefs.autoplay && s.related.isNotEmpty())) next()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            st.value = st.value.copy(playing = isPlaying)
            if (!isPlaying) saveCurrentPosition()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "player error", error)
            val cause = error.cause
            // 403 = ссылки протухли/заблокированы — один раз перезапрашиваем
            if (!retried && (cause is HttpDataSource.InvalidResponseCodeException ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
            ) {
                retried = true
                retry()
                return
            }
            st.value = st.value.copy(loading = false, error = humanError(error))
        }
    }

    private fun saveCurrentPosition() {
        if (!::exo.isInitialized) return
        val v = st.value.current ?: return
        if (exo.playbackState == Player.STATE_IDLE) return
        val pos = exo.currentPosition
        scope.launch(Dispatchers.IO) { db.savePosition(v.url, pos) }
    }

    private fun startPositionSaver() {
        scope.launch {
            while (isActive) {
                delay(15_000)
                if (exo.isPlaying) saveCurrentPosition()
            }
        }
    }

    private fun humanError(e: Throwable): String {
        val msg = generateSequence(e) { it.cause }.mapNotNull { it.message }.firstOrNull() ?: e.javaClass.simpleName
        return when {
            e is java.net.UnknownHostException || msg.contains("Unable to resolve host") -> "Нет интернета"
            msg.contains("age", true) && msg.contains("restrict", true) -> "Видео 18+ — недоступно без входа"
            msg.contains("bot", true) -> "YouTube временно ограничил доступ с этого IP"
            else -> "Ошибка: $msg"
        }
    }

    // ------------------------------------------------------------------ Медиакнопки

    private val sessionCallback = object : MediaSession.Callback {
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            @Suppress("DEPRECATION")
            val ev = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (ev != null) KeyLog.add("MediaSession: ${KeyEvent.keyCodeToString(ev.keyCode)} action=${ev.action} from=${controllerInfo.packageName}")
            return false // стандартная обработка → QueuePlayer
        }
    }

    companion object {
        private const val TAG = "A6Player"
    }
}
