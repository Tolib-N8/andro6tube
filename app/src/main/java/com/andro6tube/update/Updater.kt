package com.andro6tube.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.andro6tube.BuildConfig
import com.andro6tube.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Обновления из GitHub Releases: тег `vX.Y.Z` + файл .apk в релизе.
 * Установка: с root — полностью сама (`pm install`), без root — системное окно «Установить».
 */
object Updater {
    private const val TAG = "A6Update"

    class Release(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
    ) {
        val isNewer: Boolean get() = compareVersions(version, BuildConfig.VERSION_NAME) > 0
    }

    /** Последний релиз; null — релизов нет или в нём нет APK. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Andro6Tube/${BuildConfig.VERSION_NAME}")
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            if (!resp.isSuccessful) throw IOException("GitHub ответил ${resp.code}")
            val json = JSONObject(resp.body!!.string())
            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return@withContext Release(
                        version = json.optString("tag_name").removePrefix("v").removePrefix("V"),
                        notes = json.optString("body").trim(),
                        apkUrl = a.getString("browser_download_url"),
                        apkSize = a.optLong("size", -1),
                    )
                }
            }
            null
        }
    }

    /** Скачивает APK, сообщая прогресс 0..100 (или -1, если размер неизвестен). */
    suspend fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "andro6tube-${release.version}.apk")

            // Медленная сеть в машине: большой таймаут на чтение
            val client = Http.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(release.apkUrl)
                .header("User-Agent", "Andro6Tube/${BuildConfig.VERSION_NAME}").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Загрузка: HTTP ${resp.code}")
                val body = resp.body!!
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -2
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != lastPct) {
                                lastPct = pct
                                withContext(Dispatchers.Main) { onProgress(pct) }
                            }
                        }
                    }
                }
            }
            verify(context, file)
            file
        }

    /** APK должен быть нашим пакетом и подписан тем же ключом — иначе Android его не поставит. */
    private fun verify(context: Context, file: File) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val apk: PackageInfo = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
            ?: throw IOException("Файл повреждён (не APK)")
        if (apk.packageName != context.packageName) throw IOException("Чужой APK: ${apk.packageName}")
        @Suppress("DEPRECATION")
        val mine = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        @Suppress("DEPRECATION")
        val theirs = apk.signatures
        if (mine == null || theirs == null || !mine.contentEquals(theirs)) {
            throw IOException("Подпись APK не совпадает с установленной версией.\nПереустановите приложение через ADB.")
        }
    }

    /**
     * Устанавливает APK. true — установка запущена (с root приложение перезапустится само,
     * без root появится системное окно подтверждения).
     */
    suspend fun install(context: Context, file: File): Boolean = withContext(Dispatchers.IO) {
        if (hasRoot() && installWithRoot(file)) return@withContext true
        installWithSession(context, file)
        true
    }

    private fun hasRoot(): Boolean =
        listOf("/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su").any { File(it).exists() }

    private fun installWithRoot(file: File): Boolean = try {
        // Перезапуск после установки: pm убьёт процесс, am снова откроет приложение
        val cmd = "pm install -r '${file.absolutePath}' && am start -n ${BuildConfig.APPLICATION_ID}/.ui.MainActivity"
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        // readText ждёт завершения su; waitFor(timeout) недоступен на Android 6
        val out = p.inputStream.bufferedReader().readText()
        val ok = p.waitFor() == 0 && out.contains("Success")
        Log.i(TAG, "root install ok=$ok: $out")
        ok
    } catch (e: Exception) {
        Log.w(TAG, "root install failed", e)
        false
    }

    private fun installWithSession(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            val pi = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateReceiver::class.java), flags,
            )
            session.commit(pi.intentSender)
        }
    }

    /** "1.2.10" > "1.2.9"; нечисловые хвосты (-beta) игнорируются. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }
}
