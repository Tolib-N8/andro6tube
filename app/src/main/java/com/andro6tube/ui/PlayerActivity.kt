package com.andro6tube.ui

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.recyclerview.widget.LinearLayoutManager
import com.andro6tube.R
import com.andro6tube.data.Db
import com.andro6tube.data.Video
import com.andro6tube.data.formatViews
import com.andro6tube.databinding.ActivityPlayerBinding
import com.andro6tube.player.Playback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : BaseActivity() {

    private lateinit var b: ActivityPlayerBinding
    private lateinit var adapter: VideoAdapter
    private val db by lazy { Db.get(this) }
    private val handler = Handler(Looper.getMainLooper())

    private var fullscreen = false
    private var upcomingCount = 0
    private var shownUrl: String? = null
    private var userSeeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        adapter = VideoAdapter(onClick = ::onListClick)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.itemAnimator = null
        b.list.setHasFixedSize(true)

        b.btnPrev.setOnClickListener { Playback.previous() }
        b.btnNext.setOnClickListener { Playback.next() }
        b.btnRew.setOnClickListener { seek(-10_000) }
        b.btnFfwd.setOnClickListener { seek(10_000) }
        b.btnPlay.setOnClickListener { Playback.togglePlay() }
        b.btnQuality.setOnClickListener { showQualityDialog() }
        b.btnLater.setOnClickListener { toggleLater() }
        b.btnFull.setOnClickListener { setFullscreen(!fullscreen) }

        b.videoBox.setOnClickListener {
            if (Playback.state.value.error != null) Playback.retry() else Playback.togglePlay()
        }
        b.videoBox.setOnLongClickListener { setFullscreen(!fullscreen); true }
        b.videoBox.setOnKeyListener { _, keyCode, e -> onVideoKey(keyCode, e) }

        b.seek.keyProgressIncrement = 10 // секунды
        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.pos.text = fmt(progress * 1000L)
                    // D-pad: перематываем сразу; палец — по отпусканию
                    if (!userSeeking) Playback.service?.seekTo(progress * 1000L)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                Playback.service?.seekTo(sb.progress * 1000L)
            }
        })

        applyVideoHeight()
        b.btnPlay.requestFocus()

        // «Назад» в полноэкранном режиме — выход из полноэкранного
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) setFullscreen(false) else finish()
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Playback.state.collect(::render)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        attachPlayer()
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        b.playerView.player = null
        Playback.setVideoVisible(false) // дальше играет только звук
        super.onStop()
    }

    private fun attachPlayer() {
        val svc = Playback.service ?: return
        if (b.playerView.player !== svc.player) {
            b.playerView.player = svc.player
        }
        Playback.setVideoVisible(true)
    }

    private fun render(s: Playback.State) {
        if (b.playerView.player == null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) attachPlayer()

        val cur = s.current
        if (s.queue.isEmpty()) {
            finish(); return
        }
        b.title.text = cur?.title?.ifEmpty { "Загрузка…" } ?: ""
        b.sub.text = listOfNotNull(cur?.uploader, cur?.views?.let(::formatViews)).filter { it.isNotBlank() }.joinToString(" • ")
        b.loading.visibility = if (s.loading) View.VISIBLE else View.GONE
        b.error.visibility = if (s.error != null) View.VISIBLE else View.GONE
        b.error.text = s.error?.let { "$it\n\nНажмите OK для повтора" }
        b.btnPlay.setImageResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        b.btnQuality.text = if (s.quality.isEmpty()) "Качество" else "Качество: ${s.quality}"

        // Список: сначала очередь, затем похожие
        val upcoming = if (s.index >= 0) s.queue.drop(s.index + 1) else emptyList()
        upcomingCount = upcoming.size
        val combined = upcoming + s.related
        if (combined != adapter.items) adapter.submit(combined)
        b.listHeader.text = when {
            upcoming.isNotEmpty() && s.related.isNotEmpty() -> "${getString(R.string.up_next)} (${upcoming.size}), затем похожие"
            upcoming.isNotEmpty() -> getString(R.string.up_next)
            else -> getString(R.string.related)
        }

        if (cur != null && cur.url != shownUrl) {
            shownUrl = cur.url
            b.list.scrollToPosition(0)
            updateLaterButton(cur)
        }
    }

    private fun onListClick(position: Int) {
        val s = Playback.state.value
        if (position < upcomingCount) Playback.jumpTo(s.index + 1 + position)
        else Playback.playRelated(position - upcomingCount)
        b.btnPlay.requestFocus()
    }

    // ------------------------------------------------------------- Прогресс

    private val ticker = object : Runnable {
        override fun run() {
            val svc = Playback.service
            if (svc != null && !userSeeking) {
                val dur = svc.durationMs
                val pos = svc.positionMs
                if (dur != C.TIME_UNSET && dur > 0) {
                    val maxSec = (dur / 1000).toInt()
                    if (b.seek.max != maxSec) b.seek.max = maxSec
                    b.seek.progress = (pos / 1000).toInt()
                    b.dur.text = fmt(dur)
                } else {
                    b.dur.text = ""
                }
                b.pos.text = fmt(pos)
            }
            handler.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------- Клавиши

    /** Джойстик на видео: ←/→ перемотка, OK — пауза. */
    private fun onVideoKey(keyCode: Int, e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) {
            return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { seek(if (e.repeatCount > 3) -30_000 else -10_000); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { seek(if (e.repeatCount > 3) 30_000 else 10_000); true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> fullscreen // в полноэкранном фокус не уходит
            else -> false
        }
    }

    override fun onMediaKeyHandled(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> osd("⏭")
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> osd("⏮")
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> osd("+10 с")
            KeyEvent.KEYCODE_MEDIA_REWIND -> osd("−10 с")
        }
    }


    private fun seek(delta: Long) {
        Playback.seekBy(delta)
        osd(if (delta > 0) "+${delta / 1000} с" else "−${-delta / 1000} с")
    }

    private val hideOsd = Runnable { b.osd.visibility = View.GONE }

    private fun osd(text: String) {
        b.osd.text = text
        b.osd.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, 900)
    }

    // ------------------------------------------------------------- Экран

    private fun applyVideoHeight() {
        val lp = b.videoBox.layoutParams as LinearLayout.LayoutParams
        if (fullscreen) {
            lp.height = 0
            lp.weight = 1f
        } else {
            val w = resources.displayMetrics.widthPixels
            val h = resources.displayMetrics.heightPixels
            // На вертикальном экране — 16:9 по ширине; на горизонтальном — не больше половины высоты
            lp.height = minOf(w * 9 / 16, h / 2)
            lp.weight = 0f
        }
        b.videoBox.layoutParams = lp
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        val vis = if (on) View.GONE else View.VISIBLE
        b.panel.visibility = vis
        b.listHeader.visibility = vis
        b.list.visibility = vis
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (on) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else 0
        applyVideoHeight()
        if (on) b.videoBox.requestFocus() else b.btnFull.requestFocus()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyVideoHeight()
    }

    // ------------------------------------------------------------- Диалоги

    private fun showQualityDialog() {
        val s = Playback.state.value
        val heights = s.heights.ifEmpty { listOf(1080, 720, 480, 360) }
        val labels = heights.map { "${it}p" }.toTypedArray()
        val title = buildString {
            append("Качество (H.264)")
            if (s.decoder.isNotEmpty()) append("\nДекодер: ").append(s.decoder)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, i -> Playback.setQuality(heights[i]) }
            .show()
    }

    private fun toggleLater() {
        val v = Playback.state.value.current ?: return
        lifecycleScope.launch {
            val now = withContext(Dispatchers.IO) {
                if (db.isLater(v.url)) { db.removeLater(v.url); false } else { db.addLater(v); true }
            }
            b.btnLater.text = getString(if (now) R.string.later_remove else R.string.later_add)
        }
    }

    private fun updateLaterButton(v: Video) {
        lifecycleScope.launch {
            val inLater = withContext(Dispatchers.IO) { db.isLater(v.url) }
            b.btnLater.text = getString(if (inLater) R.string.later_remove else R.string.later_add)
        }
    }

    private fun fmt(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
