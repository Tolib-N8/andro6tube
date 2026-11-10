package com.andro6tube.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.andro6tube.data.Video
import com.andro6tube.net.Http
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Превращает StreamInfo в MediaSource.
 * Главное правило для DRA74X: только H.264 (avc1) + AAC (m4a) — их декодирует железо.
 * VP9/AV1 декодировались бы программно на CPU → лаги.
 */
object SourceBuilder {
    private const val TAG = "A6Source"

    class Result(
        val source: MediaSource,
        val label: String,
        val height: Int,
        /** Потоки для фоновой докачки до конца. */
        val targets: List<Prefetcher.Target> = emptyList(),
    )

    // Всё читается через дисковый кэш (MediaCache), туда же докачивает Prefetcher
    private val dashDs by lazy { MediaCache.factory(YtDataSource.Factory(rangeParam = true, rnParam = true)) }
    private val progressiveDs by lazy { MediaCache.factory(YtDataSource.Factory(rangeParam = false, rnParam = true)) }
    private val errorPolicy by lazy { PatientErrorPolicy() }
    private val hlsDs by lazy {
        OkHttpDataSource.Factory(Http.client)
            .setUserAgent(YoutubeParsingHelper.getVisionOsUserAgent(null))
    }

    /** Доступные высоты H.264 видео — для диалога качества. */
    fun availableHeights(info: StreamInfo): List<Int> =
        h264VideoOnly(info).map { it.height }.distinct().sortedDescending()

    fun build(info: StreamInfo, video: Video, maxHeight: Int, prefer30: Boolean): Result {
        val meta = MediaMetadata.Builder()
            .setTitle(video.title.ifEmpty { info.name })
            .setArtist(video.uploader.ifEmpty { info.uploaderName })
            .setArtworkUri(Uri.parse(video.thumbUrl))
            .build()

        // Прямые трансляции — HLS (там есть H.264 варианты; ограничение — в TrackSelector)
        if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) {
            val hls = info.hlsUrl
            require(!hls.isNullOrEmpty()) { "Трансляция недоступна" }
            val src = HlsMediaSource.Factory(hlsDs).setLoadErrorHandlingPolicy(errorPolicy).createMediaSource(
                MediaItem.Builder().setUri(hls).setMediaMetadata(meta).setMediaId(video.url).build()
            )
            return Result(src, "LIVE", 0)
        }

        val v = pickVideo(info, maxHeight, prefer30)
        val a = pickAudio(info)
        if (v != null && a != null) {
            try {
                val vs = dashSource(info, v, meta, video.url)
                val asrc = dashSource(info, a, null, video.url + "#a")
                val fps = if (v.fps > 30) "${v.fps}" else ""
                Log.i(TAG, "video itag=${v.itag} ${v.codec} ${v.height}p${v.fps}  audio itag=${a.itag} ${a.averageBitrate}k")
                return Result(
                    MergingMediaSource(true, vs, asrc), "${v.height}p$fps", v.height,
                    targets = listOfNotNull(target(v), target(a)),
                )
            } catch (e: Exception) {
                Log.w(TAG, "DASH build failed, falling back", e)
            }
        }

        // Запасной вариант: совмещённый MP4 (обычно 360p H.264, itag 18)
        val muxed = info.videoStreams
            .filter { !it.isVideoOnly && it.format == MediaFormat.MPEG_4 && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .maxByOrNull { it.height }
        if (muxed != null) {
            Log.i(TAG, "muxed itag=${muxed.itag} ${muxed.height}p")
            val src = ProgressiveMediaSource.Factory(progressiveDs)
                .setLoadErrorHandlingPolicy(errorPolicy)
                .createMediaSource(
                    MediaItem.Builder().setUri(muxed.content).setMediaMetadata(meta).setMediaId(video.url).build()
                )
            return Result(src, "${muxed.height}p", muxed.height, targets = listOfNotNull(target(muxed)))
        }

        val hls = info.hlsUrl
        if (!hls.isNullOrEmpty()) {
            val src = HlsMediaSource.Factory(hlsDs).setLoadErrorHandlingPolicy(errorPolicy).createMediaSource(
                MediaItem.Builder().setUri(hls).setMediaMetadata(meta).setMediaId(video.url).build()
            )
            return Result(src, "HLS", 0)
        }
        error("Нет подходящего потока H.264 для этого видео")
    }

    /** Докачиваем только цельные файлы (не сегментированные OTF-потоки). */
    private fun target(s: Stream): Prefetcher.Target? {
        val itag = s.itagItem ?: return null
        if (s.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP || !s.isUrl) return null
        val len = itag.contentLength
        return if (len > 0) Prefetcher.Target(itag.id, s.content, len) else null
    }

    private fun h264VideoOnly(info: StreamInfo): List<VideoStream> = info.videoOnlyStreams.filter {
        it.format == MediaFormat.MPEG_4 &&
            it.codec?.startsWith("avc1") == true &&
            it.itagItem != null &&
            (it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.deliveryMethod == DeliveryMethod.DASH)
    }

    private fun pickVideo(info: StreamInfo, maxHeight: Int, prefer30: Boolean): VideoStream? {
        val all = h264VideoOnly(info)
        val fitting = all.filter { it.height in 1..maxHeight }.ifEmpty { all.sortedBy { it.height }.take(1) }
        return fitting.sortedWith(
            compareByDescending<VideoStream> { it.height }
                .thenBy { if (prefer30 && it.fps > 30) 1 else 0 }
                .thenByDescending { it.fps }
        ).firstOrNull()
    }

    private fun pickAudio(info: StreamInfo): AudioStream? = info.audioStreams
        .filter {
            it.format == MediaFormat.M4A && it.itagItem != null &&
                (it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.deliveryMethod == DeliveryMethod.DASH)
        }
        .sortedWith(
            // Оригинальная дорожка (не дубляж), затем лучшее качество
            compareByDescending<AudioStream> {
                it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL
            }.thenByDescending { it.averageBitrate }
        )
        .firstOrNull()

    private fun dashSource(info: StreamInfo, s: Stream, meta: MediaMetadata?, id: String): MediaSource {
        val itag = requireNotNull(s.itagItem)
        val manifest = when (s.deliveryMethod) {
            DeliveryMethod.PROGRESSIVE_HTTP ->
                YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(s.content, itag, info.duration)
            DeliveryMethod.DASH ->
                YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(s.content, itag, info.duration)
            else -> error("unsupported delivery ${s.deliveryMethod}")
        }
        val uri = Uri.parse(s.content)
        val parsed = DashManifestParser().parse(uri, manifest.byteInputStream())
        val item = MediaItem.Builder().setUri(uri).setMediaId(id)
        if (meta != null) item.setMediaMetadata(meta)
        return DashMediaSource.Factory(DefaultDashChunkSource.Factory(dashDs), dashDs)
            .setLoadErrorHandlingPolicy(errorPolicy)
            .createMediaSource(parsed, item.build())
    }
}
