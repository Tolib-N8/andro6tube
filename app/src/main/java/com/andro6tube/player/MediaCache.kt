package com.andro6tube.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.andro6tube.App
import java.io.File

/**
 * Дисковый кэш видео: плеер читает из него, Prefetcher докачивает в него видео до конца.
 * Ключ — id видео + itag, поэтому кэш переживает обновление протухших ссылок YouTube.
 */
@UnstableApi
object MediaCache {
    const val MAX_BYTES = 1536L * 1024 * 1024 // 1,5 ГБ

    private val dir: File
        get() = File(App.instance.externalCacheDir ?: App.instance.cacheDir, "media")

    val cache: SimpleCache by lazy {
        SimpleCache(dir, LeastRecentlyUsedCacheEvictor(MAX_BYTES), StandaloneDatabaseProvider(App.instance))
    }

    /** "id_itag" для ссылок googlevideo.com/videoplayback, иначе null. */
    fun keyFor(uri: Uri): String? {
        if (uri.path?.startsWith("/videoplayback") != true) return null
        val id = uri.getQueryParameter("id") ?: return null
        val itag = uri.getQueryParameter("itag") ?: return null
        return "${id}_$itag"
    }

    private val keyFactory = CacheKeyFactory { spec -> keyFor(spec.uri) ?: spec.key ?: spec.uri.toString() }

    fun factory(upstream: DataSource.Factory): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheKeyFactory(keyFactory)
        // Маленькие фрагменты: докачанное сразу становится доступно плееру
        .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setFragmentSize(512L * 1024))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun sizeBytes(): Long = runCatching { cache.cacheSpace }.getOrDefault(0)

    fun clear() {
        for (key in cache.keys.toList()) runCatching { cache.removeResource(key) }
    }
}
