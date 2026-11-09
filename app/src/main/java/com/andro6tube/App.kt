package com.andro6tube

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.andro6tube.data.Prefs
import com.andro6tube.net.Http
import com.andro6tube.net.OkHttpDownloader
import org.conscrypt.Conscrypt
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.security.Security

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Современный TLS-стек вместо системного Android 6
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        } catch (t: Throwable) {
            Log.w(TAG, "Conscrypt unavailable, using platform TLS", t)
        }
        Prefs.init(this)
        initExtractor()
    }

    fun initExtractor() {
        NewPipe.init(
            OkHttpDownloader,
            Localization(Prefs.language),
            ContentCountry(Prefs.country),
        )
    }

    /** Лёгкий загрузчик превью: RGB_565, без анимаций, ограниченный кэш. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Http.client)
        .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
        .allowHardware(false)
        .crossfade(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("thumbs"))
                .maxSizeBytes(60L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .build()

    companion object {
        const val TAG = "Andro6Tube"
        lateinit var instance: App
            private set
    }
}
