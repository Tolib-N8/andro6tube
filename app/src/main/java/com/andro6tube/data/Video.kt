package com.andro6tube.data

import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Лёгкая модель видео для списков, очереди и БД. */
data class Video(
    val url: String,
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val views: Long = -1,
    val uploaded: String? = null,
    /** Последняя позиция просмотра (история). */
    val positionMs: Long = 0,
) {
    val id: String get() = idFromUrl(url) ?: url

    /** Маленькое превью 320×180 — дёшево декодировать на слабом ГУ. */
    val thumbUrl: String get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    companion object {
        private val ID_RE = Regex("(?:v=|youtu\\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})")

        fun idFromUrl(url: String): String? = ID_RE.find(url)?.groupValues?.get(1)

        fun canonicalUrl(id: String) = "https://www.youtube.com/watch?v=$id"

        fun from(item: StreamInfoItem) = Video(
            url = item.url,
            title = item.name ?: "",
            uploader = item.uploaderName ?: "",
            durationSec = item.duration,
            views = item.viewCount,
            uploaded = item.textualUploadDate,
        )

        fun from(info: StreamInfo) = Video(
            url = info.url,
            title = info.name ?: "",
            uploader = info.uploaderName ?: "",
            durationSec = info.duration,
            views = info.viewCount,
        )
    }
}

fun formatDuration(sec: Long): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatViews(v: Long): String = when {
    v < 0 -> ""
    v >= 1_000_000_000 -> "%.1f млрд просм.".format(v / 1e9)
    v >= 1_000_000 -> "%.1f млн просм.".format(v / 1e6)
    v >= 1_000 -> "%d тыс. просм.".format(v / 1000)
    else -> "$v просм."
}
