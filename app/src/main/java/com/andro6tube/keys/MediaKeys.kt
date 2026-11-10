package com.andro6tube.keys

import android.view.KeyEvent
import com.andro6tube.player.Playback

/**
 * Единая логика медиакнопок для всех путей: окно приложения, спецвозможности,
 * широковещательные команды ГУ. Вызывать в главном потоке.
 */
object MediaKeys {

    enum class Action { NEXT, PREVIOUS, TOGGLE, PLAY, PAUSE, FFWD, REWIND }

    fun actionFor(keyCode: Int): Action? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
        KeyEvent.KEYCODE_CHANNEL_UP -> Action.NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
        KeyEvent.KEYCODE_CHANNEL_DOWN -> Action.PREVIOUS
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK -> Action.TOGGLE
        KeyEvent.KEYCODE_MEDIA_PLAY -> Action.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP -> Action.PAUSE
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Action.FFWD
        KeyEvent.KEYCODE_MEDIA_REWIND -> Action.REWIND
        else -> null
    }

    /** Команды AOSP-плеера (`com.android.music.musicservicecommand`), которые шлют многие ГУ. */
    fun actionForCommand(cmd: String?): Action? = when (cmd?.lowercase()) {
        "next" -> Action.NEXT
        "previous", "prev" -> Action.PREVIOUS
        "togglepause", "playpause", "toggle" -> Action.TOGGLE
        "play" -> Action.PLAY
        "pause", "stop" -> Action.PAUSE
        else -> null
    }

    /**
     * Наше приложение — текущий медиаисточник: есть очередь и звук не забрало другое
     * приложение. Иначе не трогаем кнопки, чтобы родное радио/музыка работали как раньше.
     */
    val active: Boolean
        get() = Playback.service?.ownsMedia == true

    /** Выполняет действие. false — нашего плеера нет или он не текущий источник. */
    fun perform(action: Action, force: Boolean = false): Boolean {
        val svc = Playback.service ?: return false
        if (!force && !svc.ownsMedia) return false
        when (action) {
            Action.NEXT -> svc.next()
            Action.PREVIOUS -> svc.previous()
            Action.TOGGLE -> svc.togglePlay()
            Action.PLAY -> if (!svc.player.playWhenReady) svc.togglePlay()
            Action.PAUSE -> svc.player.pause()
            Action.FFWD -> svc.seekBy(10_000)
            Action.REWIND -> svc.seekBy(-10_000)
        }
        return true
    }

    fun name(keyCode: Int) = "${KeyEvent.keyCodeToString(keyCode)} ($keyCode)"
}
