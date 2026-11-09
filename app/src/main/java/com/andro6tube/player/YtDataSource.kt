package com.andro6tube.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.andro6tube.net.Http
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * DataSource для ссылок googlevideo.com/videoplayback.
 * YouTube ожидает POST-запросы, параметр `range=` в URL вместо заголовка Range
 * и счётчик запросов `rn=`; без этого скорость режется (троттлинг).
 */
class YtDataSource(
    private val upstream: HttpDataSource,
    private val rangeParam: Boolean,
    private val rnParam: Boolean,
) : DataSource by upstream {

    private var requestNumber = 0L

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.path?.startsWith("/videoplayback") != true) return upstream.open(dataSpec)

        val sb = StringBuilder(uri.toString())
        if (rnParam && !sb.contains("&rn=")) sb.append("&rn=").append(requestNumber++)

        val builder = dataSpec.buildUpon()
        if (rangeParam && !(dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong())) {
            sb.append("&range=").append(dataSpec.position).append('-')
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                sb.append(dataSpec.position + dataSpec.length - 1)
            }
            // Диапазон уже в URL — сервер вернёт ровно эти байты с позиции 0
            builder.setPosition(0).setLength(C.LENGTH_UNSET.toLong())
        }

        val url = sb.toString()
        if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            upstream.setRequestProperty("User-Agent", YoutubeParsingHelper.getVisionOsUserAgent(null))
        } else {
            upstream.setRequestProperty("User-Agent", Http.USER_AGENT)
        }
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            upstream.setRequestProperty("Origin", "https://www.youtube.com")
            upstream.setRequestProperty("Referer", "https://www.youtube.com/")
        }

        return upstream.open(
            builder.setUri(Uri.parse(url))
                .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                .setHttpBody(POST_BODY)
                .build()
        )
    }

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    class Factory(private val rangeParam: Boolean, private val rnParam: Boolean) : DataSource.Factory {
        private val okFactory = OkHttpDataSource.Factory(Http.client).setUserAgent(Http.USER_AGENT)
        override fun createDataSource(): DataSource =
            YtDataSource(okFactory.createDataSource(), rangeParam, rnParam)
    }

    companion object {
        private val POST_BODY = byteArrayOf(0x78, 0)
    }
}
