package com.andro6tube.ui

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import com.andro6tube.databinding.ActivityKeyTestBinding

/**
 * Диагностика: показывает коды всех кнопок (руль, джойстик, крутилка).
 * Медиакнопки здесь НЕ переключают видео — только логируются.
 */
class KeyTestActivity : ComponentActivity() {

    private lateinit var b: ActivityKeyTestBinding
    private var lastBack = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityKeyTestBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.log.text = KeyLog.all().takeLast(30).joinToString("\n")
        KeyLog.listener = { line ->
            b.log.append(line + "\n")
            b.scroll.post { b.scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
        KeyLog.add("--- тест кнопок открыт ---")
    }

    override fun onDestroy() {
        KeyLog.listener = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val act = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            else -> "MULTI"
        }
        val dev = event.device?.name ?: "?"
        KeyLog.add("$act ${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode}) scan=${event.scanCode} rep=${event.repeatCount} long=${event.isLongPress} dev=$dev")

        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            val now = System.currentTimeMillis()
            if (now - lastBack < 1500) finish() else lastBack = now
        }
        return true // глотаем всё, чтобы ничего не срабатывало
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL) {
            KeyLog.add("SCROLL v=${ev.getAxisValue(MotionEvent.AXIS_VSCROLL)} h=${ev.getAxisValue(MotionEvent.AXIS_HSCROLL)} src=0x${Integer.toHexString(ev.source)}")
            return true
        }
        if (ev.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            KeyLog.add("JOY x=${ev.getAxisValue(MotionEvent.AXIS_HAT_X)} y=${ev.getAxisValue(MotionEvent.AXIS_HAT_Y)}")
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }
}
