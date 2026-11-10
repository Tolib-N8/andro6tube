package com.andro6tube.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Фоновая докачка видео и звука до конца в дисковый кэш — чтобы на слабом интернете
 * воспроизведение не застревало. Качает кусками по 2 МБ: сначала от текущего места
 * до конца, потом начало. При обрыве ждёт и продолжает с того же куска.
 */
@UnstableApi
class Prefetcher(
    private val scope: CoroutineScope,
    private val onProgress: (Int) -> Unit,
    /** Перезапросить ссылки текущего видео (старые протухли, 403). */
    private val refresh: suspend () -> StreamInfo,
) {
    class Target(val itag: Int, @Volatile var url: String, val length: Long)

    private var job: Job? = null
    private val refreshLock = Mutex()
    private val factory = MediaCache.factory(YtDataSource.Factory(rangeParam = true, rnParam = true))

    fun start(targets: List<Target>, startFraction: Double) {
        stop()
        val list = targets.filter { it.length > 0 && MediaCache.keyFor(Uri.parse(it.url)) != null }
        if (list.isEmpty()) return
        job = scope.launch {
            report(list)
            // Звук и видео — параллельно
            val jobs = list.map { t -> launch(Dispatchers.IO) { download(t, startFraction, list) } }
            jobs.forEach { it.join() }
            report(list)
            Log.i(TAG, "докачка завершена")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun download(t: Target, startFraction: Double, all: List<Target>) {
        val key = MediaCache.keyFor(Uri.parse(t.url)) ?: return
        val count = ((t.length + CHUNK - 1) / CHUNK).toInt()
        val first = (startFraction.coerceIn(0.0, 1.0) * count).toInt().coerceIn(0, count - 1)
        val order = (first until count) + (0 until first)
        var fails = 0
        var denied = 0 // подряд 403 даже после обновления ссылок

        for (i in order) {
            val pos = i * CHUNK
            val len = minOf(CHUNK, t.length - pos)
            while (true) {
                coroutineContext.ensureActive()
                if (MediaCache.cache.isCached(key, pos, len)) break
                try {
                    readChunk(t.url, pos, len)
                    fails = 0
                    denied = 0
                    report(all)
                    break
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    Log.w(TAG, "HTTP ${e.responseCode} itag=${t.itag}")
                    if (e.responseCode == 403 || e.responseCode == 410) {
                        // YouTube не отдаёт поток и после новых ссылок — не долбим его, бросаем докачку
                        if (++denied > 3) {
                            Log.w(TAG, "доступ к потоку закрыт, докачка остановлена itag=${t.itag}")
                            return
                        }
                        refreshUrls(all)
                        delay(5_000L * denied)
                    } else {
                        backoff(++fails)
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "сеть: ${e.message} — жду и повторяю")
                    backoff(++fails)
                }
            }
        }
    }

    private suspend fun readChunk(url: String, pos: Long, len: Long) {
        val ds = factory.createDataSourceForDownloading()
        try {
            ds.open(DataSpec.Builder().setUri(url).setPosition(pos).setLength(len).build())
            val buf = ByteArray(32 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                if (ds.read(buf, 0, buf.size) == C.RESULT_END_OF_INPUT) break
            }
        } finally {
            runCatching { ds.close() }
        }
    }

    private suspend fun backoff(fails: Int) {
        delay(minOf(2_000L shl minOf(fails - 1, 3), 15_000L))
    }

    /** Новые ссылки на те же потоки (ключ кэша не меняется — скачанное сохраняется). */
    private suspend fun refreshUrls(all: List<Target>) = refreshLock.withLock {
        try {
            val info = refresh()
            val streams = info.videoOnlyStreams + info.videoStreams + info.audioStreams
            for (t in all) {
                streams.firstOrNull { it.itagItem?.id == t.itag && it.isUrl }?.let { t.url = it.content }
            }
        } catch (e: Exception) {
            Log.w(TAG, "не удалось обновить ссылки", e)
            delay(10_000)
        }
    }

    private suspend fun report(all: List<Target>) {
        var done = 0L
        var total = 0L
        for (t in all) {
            val key = MediaCache.keyFor(Uri.parse(t.url)) ?: continue
            done += MediaCache.cache.getCachedBytes(key, 0, t.length)
            total += t.length
        }
        val pct = if (total > 0) (done * 100 / total).toInt() else -1
        withContext(Dispatchers.Main) { onProgress(pct) }
    }

    companion object {
        private const val TAG = "A6Prefetch"
        private const val CHUNK = 2L * 1024 * 1024
    }
}
