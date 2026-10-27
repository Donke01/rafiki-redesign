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
        // a donor's phone (feat-donorapp): only their own projects' news, never the team's
        val dp = DonorPrefs(applicationContext)
        if (dp.token.isNotBlank()) { if (prefs.phoneAlerts) donorWork(applicationContext, prefs, dp); return Result.success() }
        if (!prefs.phoneAlerts || (prefs.pin.isBlank() && prefs.session.isBlank())) return Result.success()
        org.rafikioutreach.pin.Lang.sw = prefs.lang == "sw"
        val api = Api(BuildConfig.SERVER, { prefs.pin }, { prefs.session })
        // a newer app on the website: one notification per version, even when the app is closed
        runCatching {
            if (BuildConfig.PLAY) {
                // the Google Play build: Play says whether there is a newer version (data/Distro.kt); the website's
                // version.json only gives its name and notes, when it is about that same version
                val code = Distro.availableCode(applicationContext) ?: 0
                if (code > BuildConfig.VERSION_CODE && code > prefs.updNotified) {
                    val v = api.appVersion().takeIf { it.ok && it.json.optInt("versionCode") == code }
                    val name = v?.json?.optString("versionName").orEmpty(); val notes = v?.json?.optString("notes").orEmpty()
                    prefs.updCode = code; prefs.updName = name; prefs.updNotes = notes; prefs.updSha = ""; prefs.updAt = System.currentTimeMillis()
                    showUpdate(applicationContext, name, notes); prefs.updNotified = code
                }
                return@runCatching
            }
            val v = api.appVersion()
            val code = v.json.optInt("versionCode")
            if (v.ok && code > BuildConfig.VERSION_CODE && code > prefs.updNotified) {
                prefs.updCode = code; prefs.updName = v.json.optString("versionName"); prefs.updNotes = v.json.optString("notes"); prefs.updSha = v.json.optString("sha256"); prefs.updAt = System.currentTimeMillis()
                showUpdate(applicationContext, v.json.optString("versionName"), v.json.optString("notes")); prefs.updNotified = code
            }
        }
        // Share my location with the office: only if turned on, in work hours, with the permission already given
        runCatching { ShareHere.fromWorker(applicationContext, prefs, api) }
        val snapR = api.list(); if (!snapR.ok) return Result.success()
        val raw = snapR.json; val n = raw.optInt("reviewOpen")
        val old = prefs.loadSnapshot()?.optInt("reviewOpen") ?: 0
        if (n > old) prefs.reviewAt = java.time.Instant.now().toString()
        prefs.saveSnapshot(raw)
        org.rafikioutreach.pin.widget.PinWidget.update(applicationContext)
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
        private const val WORK = "rafiki-alerts"
        private const val GROUP = "rafiki"
        private const val SUMMARY_ID = 7000

        /** The phone's notification channels, so each kind can be turned off in Android's own settings. */
        internal const val CH_JOBS = "jobs"; internal const val CH_REPAIRS = "repairs"; internal const val CH_TEAM = "team"; internal const val CH_UPDATES = "updates"
        /** Donor mode: one channel of its own (a donor's phone never gets the staff channels). */
        internal const val CH_DONOR = "donor"
        /** Which channel an alert kind goes to: visits and repair jobs for me, repairs reported or fixed, the team's news, app updates. */
        internal fun channelOf(kind: String) = when (kind) { "assign", "job" -> CH_JOBS; "repair", "fixed" -> CH_REPAIRS; "update" -> CH_UPDATES; else -> CH_TEAM }
        /** A notification's title: the person who did it, or what it is when nobody did (a repair job, the review queue). */
        internal fun titleOf(a: Alert): String = when {
            a.by.startsWith("SMS from") || a.by.startsWith("WhatsApp from") -> t("Community report", "Ripoti ya jamii")
            a.by.isNotBlank() -> a.by
            a.name.isNotBlank() -> a.name
            else -> "RAFIKI Pin"
        }
        /** Its words: the project and what happened ("Kapsaret Borehole · Reported it needs repair"). */
        internal fun textOf(a: Alert): String = listOf(a.title, a.msg).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { a.text }

        private fun channels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            listOf(
                Triple(CH_JOBS, t("Jobs for me", "Kazi zangu"), t("Visits and repair jobs given to you", "Ziara na kazi za matengenezo ulizopewa")),
                Triple(CH_REPAIRS, t("Repairs", "Matengenezo"), t("Water points reported broken or fixed", "Vituo vya maji vilivyoripotiwa kuharibika au kurekebishwa")),
                Triple(CH_TEAM, t("Team", "Timu"), t("New sites and changes waiting for review", "Maeneo mapya na mabadiliko yanayosubiri kukaguliwa")),
                Triple(CH_UPDATES, t("Updates", "Masasisho"), t("A newer RAFIKI Pin is ready", "RAFIKI Pin mpya iko tayari")),
            ).forEach { (id, name, desc) -> nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply { description = desc }) }
            // the old single channel ("Team news") is replaced by these
            runCatching { nm.deleteNotificationChannel("alerts") }
        }

        private fun donorChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CH_DONOR, t("New photos & updates from your projects", "Picha mpya na habari za miradi yako"), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = t("Visits, photos and thank-you messages from the projects you funded", "Ziara, picha na ujumbe wa shukrani kutoka kwa miradi uliyofadhili") })
        }

        /** A donor's check: their projects' news since the last one (the first check only remembers where things are).
         *  A switched-off link signs the app out; it says so kindly when opened. Returns what was shown. */
        suspend fun donorWork(ctx: Context, prefs: Prefs, dp: DonorPrefs, api: DonorApi = DonorApi(BuildConfig.SERVER) { dp.token }): List<Triple<String, String, String>> {
            org.rafikioutreach.pin.Lang.sw = prefs.lang == "sw"
            val r = api.view(bg = true)
            if (!r.ok) { if (r.status == 404 && r.reason == "link") { dp.clear(); dp.gone = true }; return emptyList() }
            dp.save(r.json)
            val v = runCatching { DonorView.from(r.json) }.getOrNull() ?: return emptyList()
            val since = dp.notifiedAt
            if (since.isBlank()) { dp.notifiedAt = v.newest; return emptyList() }
            val news = donorNews(v, maxOf(since, dp.seen))
            if (v.newest > since) dp.notifiedAt = v.newest
            if (news.isNotEmpty()) showDonor(ctx, news)
            return news
        }
        fun showDonor(ctx: Context, items: List<Triple<String, String, String>>) {
            if (!allowed(ctx)) return
            donorChannel(ctx)
            items.takeLast(5).forEach { (title, text, pid) ->
                val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("donorOpen", pid)
                val id = (title + text).hashCode()
                val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val n = NotificationCompat.Builder(ctx, CH_DONOR).setSmallIcon(R.drawable.ic_stat_pin).setContentTitle(title).setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true).setGroup("rafiki-donor").setColor(0xFF0A78C2.toInt()).build()
                runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
            }
        }

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            runCatching { WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req) }
        }
        fun cancel(ctx: Context) { runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(WORK) } }

        /** May this app show notifications? (Android 13+ asks once.) */
        fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        /** "Update available · 2.15.1": opens the app, where the update pop-up is waiting. */
        fun showUpdate(ctx: Context, name: String, notes: String) {
            if (!allowed(ctx)) return
            channels(ctx)
            val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("open", "").putExtra("tab", "me")
            val pi = PendingIntent.getActivity(ctx, 7001, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val text = notes.ifBlank { if (BuildConfig.PLAY) t("Tap to update from Google Play.", "Gusa kusasisha kutoka Google Play.") else t("Tap to update. It installs right in the app.", "Gusa kusasisha. Inasakinishwa ndani ya programu.") }
            val n = NotificationCompat.Builder(ctx, CH_UPDATES).setSmallIcon(R.drawable.ic_stat_pin).setContentTitle(t("RAFIKI Pin update", "Sasisho la RAFIKI Pin") + (if (name.isBlank()) "" else " · $name")).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true).setColor(0xFF0A78C2.toInt()).build()
            runCatching { NotificationManagerCompat.from(ctx).notify(7001, n) }
        }

        /** Each new thing as its own notification, titled by the person (or the job), in its channel, all
         *  grouped under one summary ("3 new · RAFIKI Pin") so a busy day doesn't fill the shade. */
        fun show(ctx: Context, items: List<Alert>) {
            if (!allowed(ctx)) return
            channels(ctx)
            val shown = items.take(5)
            shown.forEach { al ->
                val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("open", al.id ?: "").putExtra("tab", "me")
                val pi = PendingIntent.getActivity(ctx, (al.at + al.text).hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val body = textOf(al)
                val n = NotificationCompat.Builder(ctx, channelOf(al.kind)).setSmallIcon(R.drawable.ic_stat_pin).setContentTitle(titleOf(al)).setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(pi).setAutoCancel(true).setGroup(GROUP).setColor(0xFF0A78C2.toInt()).build()
                runCatching { NotificationManagerCompat.from(ctx).notify((al.at + al.text).hashCode(), n) }
            }
            if (shown.isEmpty()) return
            val inbox = NotificationCompat.InboxStyle().setSummaryText("RAFIKI Pin")
            shown.forEach { inbox.addLine(titleOf(it) + ": " + textOf(it)) }
            val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("open", "").putExtra("tab", "me")
            val pi = PendingIntent.getActivity(ctx, SUMMARY_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val title = if (shown.size == 1) t("1 new notification", "Arifa 1 mpya") else t("${shown.size} new notifications", "Arifa ${shown.size} mpya")
            val sum = NotificationCompat.Builder(ctx, channelOf(shown.first().kind)).setSmallIcon(R.drawable.ic_stat_pin).setContentTitle(title).setContentText(shown.joinToString(", ") { titleOf(it) })
                .setStyle(inbox).setGroup(GROUP).setGroupSummary(true).setContentIntent(pi).setAutoCancel(true).setColor(0xFF0A78C2.toInt()).build()
            runCatching { NotificationManagerCompat.from(ctx).notify(SUMMARY_ID, sum) }
        }
    }
}
