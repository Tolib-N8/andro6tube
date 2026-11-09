package com.andro6tube.ui

import android.app.AlertDialog
import android.content.Intent
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.andro6tube.App
import com.andro6tube.BuildConfig
import com.andro6tube.R
import com.andro6tube.data.Db
import com.andro6tube.data.Prefs
import com.andro6tube.databinding.ActivitySettingsBinding
import com.andro6tube.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SettingsActivity : BaseActivity() {

    private lateinit var b: ActivitySettingsBinding
    private val qualities = listOf(360, 480, 720, 1080)
    private val qualityButtons = mutableListOf<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        qualities.forEachIndexed { i, h ->
            val btn = Button(this, null, 0, R.style.Btn).apply {
                text = "${h}p"
                layoutParams = LinearLayout.LayoutParams(0, resources.getDimensionPixelSize(R.dimen.btn_h), 1f)
                    .apply { if (i > 0) marginStart = (10 * resources.displayMetrics.density).toInt() }
                setOnClickListener { Prefs.maxHeight = h; refreshQuality() }
            }
            qualityButtons += btn
            b.qualityRow.addView(btn)
        }
        refreshQuality()

        b.prefer30.isChecked = Prefs.prefer30fps
        b.autoplay.isChecked = Prefs.autoplay
        b.resume.isChecked = Prefs.resume
        b.prefer30.setOnCheckedChangeListener { _, c -> Prefs.prefer30fps = c }
        b.autoplay.setOnCheckedChangeListener { _, c -> Prefs.autoplay = c }
        b.resume.setOnCheckedChangeListener { _, c -> Prefs.resume = c }
        b.language.setText(Prefs.language)
        b.country.setText(Prefs.country)

        b.checkUpdate.text = "Проверить обновления (сейчас ${BuildConfig.VERSION_NAME})"
        b.checkUpdate.setOnClickListener { checkUpdate() }
        b.updateRoot.isChecked = Prefs.updateViaRoot
        b.updateRoot.text = if (Updater.hasRoot()) "Ставить обновления молча через root"
        else "Ставить обновления молча через root (root не найден)"
        b.updateRoot.setOnCheckedChangeListener { _, c -> Prefs.updateViaRoot = c }
        b.keyTest.setOnClickListener { startActivity(Intent(this, KeyTestActivity::class.java)) }
        b.clearHistory.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) { Db.get(this@SettingsActivity).clearHistory() }
            Toast.makeText(this, "История очищена", Toast.LENGTH_SHORT).show()
        }

        b.info.text = deviceInfo()
        qualityButtons.firstOrNull()?.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        val lang = b.language.text.toString().trim().ifEmpty { "ru" }
        val country = b.country.text.toString().trim().uppercase().ifEmpty { "UZ" }
        if (lang != Prefs.language || country != Prefs.country) {
            Prefs.language = lang
            Prefs.country = country
            App.instance.initExtractor()
        }
    }

    // ------------------------------------------------------------- Обновления

    private var updateJob: Job? = null

    private fun checkUpdate() {
        if (updateJob?.isActive == true) return
        b.checkUpdate.text = "Проверяю…"
        updateJob = lifecycleScope.launch {
            try {
                val rel = Updater.latest()
                when {
                    rel == null -> message("Релизов с APK пока нет")
                    !rel.isNewer -> message("У вас последняя версия (${BuildConfig.VERSION_NAME})")
                    else -> AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Доступна версия ${rel.version}")
                        .setMessage(buildString {
                            append("Сейчас: ").append(BuildConfig.VERSION_NAME)
                            if (rel.apkSize > 0) append("\nРазмер: %.1f МБ".format(rel.apkSize / 1048576.0))
                            if (rel.notes.isNotEmpty()) append("\n\n").append(rel.notes)
                        })
                        .setPositiveButton("Скачать и установить") { _, _ -> downloadAndInstall(rel) }
                        .setNegativeButton("Позже", null)
                        .show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("Не удалось проверить: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                b.checkUpdate.text = "Проверить обновления (сейчас ${BuildConfig.VERSION_NAME})"
            }
        }
    }

    private fun downloadAndInstall(rel: Updater.Release) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Обновление ${rel.version}")
            .setMessage("Загрузка…")
            .setCancelable(false)
            .setNegativeButton("Отмена") { _, _ -> updateJob?.cancel() }
            .show()
        updateJob = lifecycleScope.launch {
            try {
                val file = Updater.download(this@SettingsActivity, rel) { pct ->
                    dialog.setMessage(if (pct >= 0) "Загрузка: $pct%" else "Загрузка…")
                }
                dialog.setMessage("Установка…")
                Updater.install(this@SettingsActivity, file)
                dialog.dismiss()
            } catch (e: CancellationException) {
                dialog.dismiss()
                throw e
            } catch (e: Exception) {
                dialog.dismiss()
                message("Ошибка обновления: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun message(text: String) {
        AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun refreshQuality() {
        qualityButtons.forEachIndexed { i, btn -> btn.isSelected = qualities[i] == Prefs.maxHeight }
    }

    /** Показывает, есть ли аппаратный H.264 декодер (OMX.TI.* на DRA74X). */
    private fun deviceInfo(): String = buildString {
        append("Andro6Tube ").append(BuildConfig.VERSION_NAME).append('\n')
        append("Устройство: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (").append(Build.HARDWARE).append(")\n")
        append("Android ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append('\n')
        append("Декодеры видео:\n")
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { !it.isEncoder }
                .filter { ci -> ci.supportedTypes.any { it.startsWith("video/") } }
                .forEach { ci ->
                    val sw = ci.name.startsWith("OMX.google") || ci.name.startsWith("c2.android")
                    append(if (sw) "  [SW] " else "  [HW] ")
                    append(ci.name).append(": ").append(ci.supportedTypes.joinToString()).append('\n')
                }
        }
    }
}
