package com.andro6tube.net

import android.util.Log
import com.andro6tube.App
import com.andro6tube.R
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object Http {
    /** Браузерный UA для запросов страниц/API (как в NewPipe). */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

    val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
        try {
            val tm = trustManagerWithExtraRoots()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
            builder.sslSocketFactory(ssl.socketFactory, tm)
        } catch (e: Exception) {
            Log.e("A6Http", "extra roots failed, using system trust only", e)
        }
        builder.build()
    }

    /**
     * Системные корневые сертификаты + ISRG Root X1/X2 (Let's Encrypt).
     * В Android 6 их нет (добавлены в 7.1), а через Let's Encrypt GitHub отдаёт файлы релизов.
     */
    private fun trustManagerWithExtraRoots(): X509TrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }

        val system = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
        for (alias in system.aliases()) {
            system.getCertificate(alias)?.let { ks.setCertificateEntry("sys:$alias", it) }
        }

        val cf = CertificateFactory.getInstance("X.509")
        val res = App.instance.resources
        for ((name, id) in listOf("isrg_x1" to R.raw.isrg_root_x1, "isrg_x2" to R.raw.isrg_root_x2)) {
            res.openRawResource(id).use { ks.setCertificateEntry(name, cf.generateCertificate(it) as X509Certificate) }
        }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
