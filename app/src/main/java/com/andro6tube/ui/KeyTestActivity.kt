package com.andro6tube.ui

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import com.andro6tube.keys.SteeringKeyService
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
        b.btnBack.setOnClickListener { finish() }
        b.log.text = KeyLog.all().takeLast(30).joinToString("\n")
        KeyLog.listener = {
            // Держим в окне только последние строки — logcat может быть многословным
            b.log.text = KeyLog.all().takeLast(150).joinToString("\n")
            b.scroll.post { b.scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
        KeyLog.add("--- тест кнопок открыт ---")
        KeyLog.add(statusLine())
        startLogcatIfAllowed()
    }

    override fun onResume() {
        super.onResume()
        KeyLog.testScreenOpen = true
    }

    override fun onPause() {
        KeyLog.testScreenOpen = false
        super.onPause()
    }

    override fun onDestroy() {
        KeyLog.listener = null
        logcat?.destroy()
        super.onDestroy()
    }

    /** Какие пути приёма включены — чтобы по пустому журналу было понятно, что проверено. */
    private fun statusLine(): String {
        val a11y = if (SteeringKeyService.isEnabled(this)) "вкл" else "ВЫКЛ (Настройки → Включить кнопки руля)"
        val logs = if (canReadLogs()) "вкл" else "выкл"
        return "Пути: [Окно] вкл · [A11y] $a11y · [Broadcast] вкл · [Session] вкл · [logcat] $logs\n" +
            "Если при нажатии на руле нет ни одной строки — ГУ шлёт сигнал своим способом, нужен ADB."
    }

    // ------------------------------------------------------------- logcat (по желанию)

    private var logcat: Process? = null

    private fun canReadLogs() =
        checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Если выдано READ_LOGS (adb shell pm grant com.andro6tube android.permission.READ_LOGS),
     * показываем системный журнал: по нему видно, что делает ГУ при нажатии кнопок руля.
     */
    private fun startLogcatIfAllowed() {
        if (!canReadLogs()) return
        Thread {
            try {
                val p = ProcessBuilder("logcat", "-v", "brief", "-T", "1").redirectErrorStream(true).start()
                logcat = p
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (!line.contains("A6Keys")) KeyLog.add("[logcat] $line")
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // OK джойстика на кнопке «←» — выход (остальные клавиши только логируем)
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (ok && currentFocus === b.btnBack) return super.dispatchKeyEvent(event)

        val act = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            else -> "MULTI"
        }
        val dev = event.device?.name ?: "?"
        KeyLog.add("[Окно] $act ${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode}) scan=${event.scanCode} rep=${event.repeatCount} long=${event.isLongPress} dev=$dev")

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
