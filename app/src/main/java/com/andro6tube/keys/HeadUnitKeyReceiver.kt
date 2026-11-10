package com.andro6tube.keys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import com.andro6tube.ui.KeyLog

/**
 * Широковещательные команды, которыми китайские ГУ переключают треки в сторонних плеерах.
 * Всё полученное пишется в «Тест кнопок», известные команды выполняются.
 */
class HeadUnitKeyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val act = intent.action ?: return
        KeyLog.add("[Broadcast] $act ${describe(intent.extras)}")
        if (KeyLog.testScreenOpen) return

        val action: MediaKeys.Action? = when {
            act == MUSIC_CMD -> MediaKeys.actionForCommand(intent.getStringExtra("command"))
            act.startsWith("$MUSIC_CMD.") -> MediaKeys.actionForCommand(act.substringAfterLast('.'))
            act.contains("irkeyDown", ignoreCase = true) || act.contains("KEY_DOWN", ignoreCase = true) -> {
                val code = intent.getIntExtra("keyCode", intent.getIntExtra("keycode", -1))
                MediaKeys.actionFor(code)
            }
            else -> null
        }
        if (action != null) MediaKeys.perform(action)
    }

    private fun describe(extras: Bundle?): String {
        if (extras == null || extras.isEmpty) return ""
        return extras.keySet().joinToString(prefix = "{", postfix = "}") { k ->
            @Suppress("DEPRECATION")
            val v = extras.get(k)
            val s = if (v is KeyEvent) "${MediaKeys.name(v.keyCode)} a=${v.action}" else v.toString()
            "$k=$s"
        }
    }

    companion object {
        const val MUSIC_CMD = "com.android.music.musicservicecommand"
    }
}
