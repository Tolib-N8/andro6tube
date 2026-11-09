package com.andro6tube.data

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** Доступ к YouTube через NewPipeExtractor. Все вызовы — в IO-потоке. */
object Repo {
    private val yt get() = ServiceList.YouTube

    class SearchPage(val videos: List<Video>, val next: Page?)

    suspend fun search(query: String): SearchPage = withContext(Dispatchers.IO) {
        val qh = yt.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        val info = SearchInfo.getInfo(yt, qh)
        SearchPage(info.relatedItems.toVideos(), info.nextPage)
    }

    suspend fun searchMore(query: String, page: Page): SearchPage = withContext(Dispatchers.IO) {
        val qh = yt.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        val more = SearchInfo.getMoreItems(yt, qh, page)
        SearchPage(more.items.toVideos(), more.nextPage)
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { yt.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
    }

    // Ссылки на потоки живут ~6 ч; держим недолго, чтобы не протухли.
    private class Cached(val info: StreamInfo, val at: Long)
    private val cache = LruCache<String, Cached>(12)
    private const val TTL_MS = 60 * 60 * 1000L

    fun cachedInfo(url: String): StreamInfo? {
        val c = synchronized(cache) { cache.get(key(url)) } ?: return null
        return if (System.currentTimeMillis() - c.at < TTL_MS) c.info else null
    }

    suspend fun streamInfo(url: String, force: Boolean = false): StreamInfo = withContext(Dispatchers.IO) {
        if (!force) cachedInfo(url)?.let { return@withContext it }
        val info = StreamInfo.getInfo(yt, url)
        synchronized(cache) { cache.put(key(url), Cached(info, System.currentTimeMillis())) }
        info
    }

    fun invalidate(url: String) { synchronized(cache) { cache.remove(key(url)) } }

    fun related(info: StreamInfo): List<Video> = info.relatedItems.toVideos()

    private fun key(url: String) = Video.idFromUrl(url) ?: url

    private fun List<*>.toVideos(): List<Video> = filterIsInstance<StreamInfoItem>()
        .filter { it.streamType == StreamType.VIDEO_STREAM || it.streamType == StreamType.LIVE_STREAM }
        .map(Video::from)
}
