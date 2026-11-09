package com.andro6tube.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import com.andro6tube.player.Playback

/**
 * Общая обработка медиакнопок (руль): работает на любом экране приложения,
 * даже если ГУ шлёт клавиши в активное окно, а не в MediaSession.
 */
abstract class BaseActivity : ComponentActivity() {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            KeyLog.add("key ${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode}) scan=${event.scanCode} rep=${event.repeatCount}")
        }
        if (handleMediaKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleMediaKey(e: KeyEvent): Boolean {
        val svc = Playback.service ?: return false
        val action: (() -> Unit) = when (e.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_CHANNEL_UP -> svc::next
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> svc::previous
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> svc::togglePlay
            KeyEvent.KEYCODE_MEDIA_PLAY -> { { if (!svc.player.playWhenReady) svc.togglePlay() } }
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> { { svc.player.pause() } }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { { svc.seekBy(10_000) } }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { { svc.seekBy(-10_000) } }
            else -> return false
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            action()
            onMediaKeyHandled(e.keyCode)
        }
        return true
    }

    /** Хук для экранов: например, показать OSD в плеере. */
    protected open fun onMediaKeyHandled(keyCode: Int) = Unit
}
