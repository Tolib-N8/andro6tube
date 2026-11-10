package com.andro6tube.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import com.andro6tube.keys.MediaKeys
import com.andro6tube.player.Playback

/**
 * Общая обработка медиакнопок (руль): работает на любом экране приложения,
 * даже если ГУ шлёт клавиши в активное окно, а не в MediaSession.
 */
abstract class BaseActivity : ComponentActivity() {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            KeyLog.add("[Окно] ${MediaKeys.name(event.keyCode)} scan=${event.scanCode} rep=${event.repeatCount}")
        }
        if (handleMediaKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleMediaKey(e: KeyEvent): Boolean {
        val action = MediaKeys.actionFor(e.keyCode) ?: return false
        if (Playback.service == null) return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            // Нажатие в нашем окне — однозначно для нас, даже если звук был у другого приложения
            MediaKeys.perform(action, force = true)
            onMediaKeyHandled(e.keyCode)
        }
        return true
    }

    /** Хук для экранов: например, показать OSD в плеере. */
    protected open fun onMediaKeyHandled(keyCode: Int) = Unit
}
