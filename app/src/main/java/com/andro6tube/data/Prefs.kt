package com.andro6tube.data

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

    /** Максимальная высота видео. 720p — оптимум для DRA74X. */
    var maxHeight: Int
        get() = sp.getInt("max_height", 720)
        set(v) = sp.edit().putInt("max_height", v).apply()

    /** Предпочитать 30 fps вместо 60 (меньше нагрузка). */
    var prefer30fps: Boolean
        get() = sp.getBoolean("prefer_30fps", true)
        set(v) = sp.edit().putBoolean("prefer_30fps", v).apply()

    /** Автовоспроизведение похожего видео в конце очереди. */
    var autoplay: Boolean
        get() = sp.getBoolean("autoplay", true)
        set(v) = sp.edit().putBoolean("autoplay", v).apply()

    /** Продолжать с места, где остановились. */
    var resume: Boolean
        get() = sp.getBoolean("resume", true)
        set(v) = sp.edit().putBoolean("resume", v).apply()

    var language: String
        get() = sp.getString("language", "ru") ?: "ru"
        set(v) = sp.edit().putString("language", v).apply()

    var country: String
        get() = sp.getString("country", "UZ") ?: "UZ"
        set(v) = sp.edit().putString("country", v).apply()

    var lastQuery: String
        get() = sp.getString("last_query", "") ?: ""
        set(v) = sp.edit().putString("last_query", v).apply()
}
