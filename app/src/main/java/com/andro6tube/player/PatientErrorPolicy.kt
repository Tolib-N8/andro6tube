package com.andro6tube.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Слабый интернет в машине: сетевые ошибки повторяем бесконечно (раз в 1–5 с),
 * а не останавливаем видео. Ответы 403/404/410 (протухшие ссылки) сразу отдаём
 * наверх — сервис перезапросит ссылки и продолжит с того же места.
 */
@UnstableApi
class PatientErrorPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val e = loadErrorInfo.exception
        if (e is HttpDataSource.InvalidResponseCodeException && e.responseCode in 400..499) return C.TIME_UNSET
        val base = super.getRetryDelayMsFor(loadErrorInfo)
        if (base == C.TIME_UNSET) return C.TIME_UNSET
        return minOf(loadErrorInfo.errorCount * 1_000L, 5_000L)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE
}
