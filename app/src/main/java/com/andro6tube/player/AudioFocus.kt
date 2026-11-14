package com.andro6tube.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player

/**
 * Аудиофокус держим сами, а не через ExoPlayer: ExoPlayer отдаёт фокус при каждой
 * остановке (смена видео, загрузка, ошибка). Служба кнопок ГУ отдаёт медиаклавиши
 * тому, кто последним играл звук, — значит, фокус должен быть у нас всё время,
 * пока очередь активна, в том числе на паузе.
 */
class AudioFocus(
    context: Context,
    private val player: Player,
    /** Фокус насовсем забрало другое приложение (радио, музыка, звонок навсегда). */
    private val onLost: () -> Unit,
) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    var held = false
        private set
    private var resumeOnGain = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post { onChange(change) }
    }

    private val request: AudioFocusRequest? = if (Build.VERSION.SDK_INT >= 26) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener(listener, main)
            .build()
    } else {
        null
    }

    /** true — можно играть. Повторный вызов при уже полученном фокусе ничего не делает. */
    fun request(): Boolean {
        if (held) return true
        val result = if (Build.VERSION.SDK_INT >= 26) {
            am.requestAudioFocus(request!!)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (held) player.volume = 1f
        return held
    }

    fun abandon() {
        if (!held) return
        if (Build.VERSION.SDK_INT >= 26) {
            am.abandonAudioFocusRequest(request!!)
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(listener)
        }
        held = false
        resumeOnGain = false
    }

    private fun onChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                held = true
                player.volume = 1f
                if (resumeOnGain) player.playWhenReady = true
                resumeOnGain = false
            }
            // Подсказка навигатора — приглушаем
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player.volume = 0.2f
            // Временно (звонок, голосовой ассистент) — пауза, потом продолжим
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnGain = resumeOnGain || player.playWhenReady
                player.playWhenReady = false
            }
            // Насовсем — звук теперь у другого приложения
            AudioManager.AUDIOFOCUS_LOSS -> {
                held = false
                resumeOnGain = false
                player.playWhenReady = false
                onLost()
            }
        }
    }
}
