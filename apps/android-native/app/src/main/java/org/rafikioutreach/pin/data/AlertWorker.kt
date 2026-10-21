package org.rafikioutreach.pin.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import org.rafikioutreach.pin.BuildConfig
import org.rafikioutreach.pin.MainActivity
import org.rafikioutreach.pin.R
import org.rafikioutreach.pin.t
import java.util.concurrent.TimeUnit

/**
 * Phone alerts while the app is closed: about every 15 minutes (when there's signal) Android lets this
 * check the same things as the bell, and shows a notification for anything new. No Google account needed.
 */
class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.phoneAlerts || (prefs.pin.isBlank() && prefs.session.isBlank())) return Result.success()
        org.rafikioutreach.pin.Lang.sw = prefs.lang == "sw"
        val api = Api(BuildConfig.SERVER, { prefs.pin }, { prefs.session })
        val snapR = api.list(); if (!snapR.ok) return Result.success()
        val raw = snapR.json; val n = raw.optInt("reviewOpen")
        val old = prefs.loadSnapshot()?.optInt("reviewOpen") ?: 0
        if (n > old) prefs.reviewAt = java.time.Instant.now().toString()
        prefs.saveSnapshot(raw)
        val a = api.activity().json.optJSONArray("activity")
        val act = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }
        val list = alertsFor(Snapshot.from(raw), act, prefs.name, prefs.noteOff, prefs.reviewAt)
        val since = prefs.notifiedAt
        val newest = list.maxOfOrNull { it.at } ?: return Result.success()
        // the first check only remembers where things are, so nobody gets a flood of old news
        if (since.isBlank()) { prefs.notifiedAt = newest; return Result.success() }
        val fresh = list.filter { it.at > since && it.at > prefs.noteSeen }
        if (fresh.isNotEmpty()) show(applicationContext, fresh)
        prefs.notifiedAt = maxOf(since, newest)
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "alerts"
        private const val WORK = "rafiki-alerts"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            runCatching { WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req) }
        }
        fun cancel(ctx: Context) { runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(WORK) } }

        /** May this app show notifications? (Android 13+ asks once.) */
        fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun show(ctx: Context, items: List<Alert>) {
            if (!allowed(ctx)) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, t("Team news", "Habari za timu"), NotificationManager.IMPORTANCE_DEFAULT).apply { description = t("Repairs, visits and jobs for you", "Matengenezo, ziara na kazi zako") })
            items.take(5).forEachIndexed { i, al ->
                val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("open", al.id ?: "").putExtra("tab", "me")
                val pi = PendingIntent.getActivity(ctx, (al.at + al.text).hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_pin).setContentTitle("RAFIKI Pin").setContentText(al.text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(al.text)).setContentIntent(pi).setAutoCancel(true).setGroup("rafiki").setColor(0xFF0A78C2.toInt()).build()
                runCatching { NotificationManagerCompat.from(ctx).notify((al.at + al.text).hashCode(), n) }
            }
        }
    }
}
