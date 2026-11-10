package com.andro6tube.player

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Обёртка над ExoPlayer: команды «следующий/предыдущий» (кнопки руля, уведомление,
 * MediaSession) переключают НАШУ очередь, а не плейлист ExoPlayer.
 */
class QueuePlayer(
    player: Player,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
) : ForwardingSimpleBasePlayer(player) {

    override fun getState(): State {
        val state = super.getState()
        val commands = state.availableCommands.buildUpon()
            .addAll(
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_PLAY_PAUSE,
            )
            .build()
        return state.buildUpon().setAvailableCommands(commands).build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val name = when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> "следующее"
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> "предыдущее"
            else -> null
        }
        if (name != null) com.andro6tube.ui.KeyLog.add("[Session] команда «$name»")
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> onNext()
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> onPrevious()
            COMMAND_SEEK_TO_PREVIOUS -> {
                // Как в обычных плеерах: если играет дольше 5 с — в начало, иначе — предыдущее
                if (player.currentPosition > 5_000) player.seekTo(0) else onPrevious()
            }
            else -> return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        return Futures.immediateVoidFuture()
    }
}
