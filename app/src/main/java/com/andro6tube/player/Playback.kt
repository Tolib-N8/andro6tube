package com.andro6tube.player

import android.content.Context
import android.content.Intent
import com.andro6tube.data.Video
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Точка входа UI в воспроизведение (один процесс → прямые вызовы, без IPC).
 */
object Playback {

    data class State(
        val queue: List<Video> = emptyList(),
        val index: Int = -1,
        val related: List<Video> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val quality: String = "",
        val heights: List<Int> = emptyList(),
        val decoder: String = "",
        val playing: Boolean = false,
    ) {
        val current: Video? get() = queue.getOrNull(index)
    }

    internal val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState

    @Volatile
    var service: PlaybackService? = null
        internal set

    private var pending: Pair<List<Video>, Int>? = null

    internal fun takePending(): Pair<List<Video>, Int>? = pending.also { pending = null }

    fun play(context: Context, queue: List<Video>, index: Int) {
        if (queue.isEmpty()) return
        val idx = index.coerceIn(0, queue.lastIndex)
        val svc = service
        if (svc == null) {
            // Сразу показываем видео в UI, сервис подхватит очередь при старте
            mutableState.value = mutableState.value.copy(queue = queue, index = idx, related = emptyList(), loading = true, error = null)
        }
        if (svc != null) {
            svc.setQueue(queue, index)
        } else {
            pending = queue to index
        }
        // Запускаем сервис явно: он переживёт уход из приложения (фоновое аудио)
        context.startService(Intent(context, PlaybackService::class.java))
    }

    fun next() = service?.next()
    fun previous() = service?.previous()
    fun jumpTo(index: Int) = service?.jumpTo(index)
    fun playRelated(index: Int) = service?.playRelated(index)
    fun togglePlay() = service?.togglePlay()
    fun seekBy(deltaMs: Long) = service?.seekBy(deltaMs)
    fun setQuality(height: Int) = service?.setQuality(height)
    fun retry() = service?.retry()

    /** Видео нужно только когда экран плеера виден; иначе — только звук. */
    fun setVideoVisible(visible: Boolean) = service?.setVideoEnabled(visible)
}
