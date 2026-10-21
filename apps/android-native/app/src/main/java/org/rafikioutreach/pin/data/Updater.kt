package org.rafikioutreach.pin.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates the app from inside the app: downloads the newest APK from the website (with a progress bar),
 * checks it, and hands it to Android's own installer, which asks "Update this app?" once.
 */
object Updater {
    sealed class State {
        object Idle : State()
        data class Downloading(val done: Long, val total: Long) : State() { val f: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f }
        /** Android needs the person to allow "Install unknown apps" for RAFIKI Pin once. */
        object NeedsPermission : State()
        object Installing : State()
        data class Failed(val why: String) : State()
    }
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    @Volatile private var cancelled = false

    fun file(ctx: Context) = File(ctx.cacheDir, "updates/rafiki-pin.apk")

    /** May this app install packages? (Asked once, in Android's settings.) */
    fun allowed(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun permissionIntent(ctx: Context) = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun cancel() { cancelled = true }
    fun reset() { _state.value = State.Idle }

    /** Downloads [url] (checking [sha256] when given), then installs it. */
    suspend fun run(ctx: Context, url: String, sha256: String?) {
        cancelled = false
        val out = file(ctx)
        val ok = withContext(Dispatchers.IO) {
            try {
                out.parentFile?.mkdirs(); out.delete()
                var c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 60000; c.instanceFollowRedirects = true
                if (c.responseCode in 300..399) { val loc = c.getHeaderField("Location"); c.disconnect(); c = URL(URL(url), loc).openConnection() as HttpURLConnection }
                if (c.responseCode != 200) { _state.value = State.Failed("http"); return@withContext false }
                val total = c.contentLengthLong
                val md = MessageDigest.getInstance("SHA-256")
                var done = 0L
                c.inputStream.use { inp -> out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) { _state.value = State.Idle; return@withContext false }
                        val n = inp.read(buf); if (n < 0) break
                        o.write(buf, 0, n); md.update(buf, 0, n); done += n
                        _state.value = State.Downloading(done, total)
                    }
                } }
                if (total > 0 && done != total) { _state.value = State.Failed("short"); return@withContext false }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (!sha256.isNullOrBlank() && !hex.equals(sha256.trim(), ignoreCase = true)) { out.delete(); _state.value = State.Failed("check"); return@withContext false }
                true
            } catch (e: Exception) { _state.value = State.Failed("offline"); false }
        }
        if (ok) install(ctx)
    }

    /** Hands the downloaded file to Android's installer. */
    fun install(ctx: Context) {
        val f = file(ctx)
        if (!f.exists()) { _state.value = State.Failed("missing"); return }
        if (!allowed(ctx)) { _state.value = State.NeedsPermission; return }
        _state.value = State.Installing
        try {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(ctx.packageName) }
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("rafiki-pin.apk", 0, f.length()).use { o -> f.inputStream().use { it.copyTo(o) }; s.fsync(o) }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val back = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResult::class.java).setPackage(ctx.packageName), flags)
                s.commit(back.intentSender)
            }
        } catch (e: Exception) { _state.value = State.Failed("install") }
    }

    internal fun onResult(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { ctx.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.Idle   // the new app takes over
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = State.Failed("aborted")
            else -> _state.value = State.Failed("install")
        }
    }
}

/** Android's installer reports back here (confirm, done, or why it failed). */
class InstallResult : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Updater.onResult(ctx, intent)
}
