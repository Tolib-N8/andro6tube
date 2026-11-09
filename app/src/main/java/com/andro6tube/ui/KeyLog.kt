package com.andro6tube.ui

import android.os.Handler
import android.os.Looper
import android.util.Log

/** Журнал нажатий (для экрана «Тест кнопок» и adb logcat -s A6Keys). */
object KeyLog {
    private val lines = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())
    var listener: ((String) -> Unit)? = null

    fun add(line: String) {
        Log.i("A6Keys", line)
        main.post {
            if (lines.size > 200) lines.removeFirst()
            lines.addLast(line)
            listener?.invoke(line)
        }
    }

    fun all(): List<String> = lines.toList()
}
