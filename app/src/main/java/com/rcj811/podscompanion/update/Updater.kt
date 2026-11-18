package com.rcj811.podscompanion.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rcj811.podscompanion.BuildConfig
import com.rcj811.podscompanion.Prefs
import com.rcj811.podscompanion.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the GitHub "latest" release for a newer build, downloads it and installs it.
 * The first update asks the user to confirm; after the app has installed itself once,
 * Android 12+ allows later updates without asking.
 */
object Updater {
    private const val TAG = "Updater"
    private const val BASE = "https://github.com/rcj811/airpods-companion/releases/download/latest/"
    private const val CHANNEL = "update"
    private const val NOTIF_ID = 10
    private val mutex = Mutex()

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data class UpToDate(val version: String) : Status
        data class Downloading(val version: String, val percent: Int) : Status
        data class Installing(val version: String) : Status
        data class NeedsPermission(val version: String) : Status
        data class Error(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    val currentVersion get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /** @param manual true when the user pressed "check now" (always checks, shows errors). */
    suspend fun check(context: Context, manual: Boolean = false, force: Boolean = manual) = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext
        try {
            val now = System.currentTimeMillis()
            if (!force && now - Prefs.lastUpdateCheck < 3 * 60 * 60 * 1000L) return@withContext
            Prefs.lastUpdateCheck = now
            _status.value = Status.Checking
            val info = JSONObject(httpGet(BASE + "version.json"))
            val code = info.getInt("versionCode")
            val name = info.optString("versionName", code.toString())
            if (code <= BuildConfig.VERSION_CODE) {
                _status.value = Status.UpToDate(currentVersion)
                return@withContext
            }
            if (!context.packageManager.canRequestPackageInstalls()) {
                _status.value = Status.NeedsPermission(name)
                notify(context, "有新版本 $name", "請允許「安裝未知應用程式」以自動更新", null)
                return@withContext
            }
            val apk = download(BASE + "pods-companion.apk", File(context.cacheDir, "update.apk")) { p ->
                _status.value = Status.Downloading(name, p)
            }
            _status.value = Status.Installing(name)
            install(context, apk)
        } catch (e: Throwable) {
            Log.w(TAG, "update failed", e)
            _status.value = if (manual) Status.Error(e.message ?: e.javaClass.simpleName) else Status.Idle
        } finally {
            mutex.unlock()
        }
    }

    private fun httpGet(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 15000; instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, out: File, progress: (Int) -> Unit): File {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
        }
        try {
            if (c.responseCode != 200) throw IllegalStateException("下載失敗 HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var done = 0L
            var last = -1
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        val p = if (total > 0) (done * 100 / total).toInt() else 0
                        if (p != last) { last = p; progress(p) }
                    }
                }
            }
            if (total > 0 && done != total) throw IllegalStateException("下載不完整")
            return out
        } finally {
            c.disconnect()
        }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 34) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, id, intent, flags)
            session.commit(pi.intentSender)
        }
    }

    internal fun onInstallResult(context: Context, intent: Intent) {
        when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Starting an activity from the background may be blocked, so also offer a notification.
                runCatching { context.startActivity(confirm) }
                notify(context, "新版本已下載", "點這裡完成更新", confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> _status.value = Status.Idle
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "錯誤 $st"
                _status.value = Status.Error("安裝失敗：$msg")
            }
        }
    }

    private fun notify(context: Context, title: String, text: String, intent: Intent?) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App 更新", NotificationManager.IMPORTANCE_HIGH))
        val target = intent ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pi = target?.let {
            PendingIntent.getActivity(context, 7, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_pods)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }
}
