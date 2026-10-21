package org.rafikioutreach.pin.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import org.rafikioutreach.pin.Lang
import org.rafikioutreach.pin.MainActivity
import org.rafikioutreach.pin.R
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.t
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "RAFIKI Pin" on the home screen: visits due, urgent repairs and work for you, the RAFIKI drop,
 * and one-tap "Visit nearby" and "Pin a location" (they open the app on those lists).
 * Worked out from the list saved on the phone; refreshed when the app fetches the list,
 * by the 15-minute background check, and every 30 minutes by Android.
 */
open class PinWidget : AppWidgetProvider() {
    /** The size this widget starts at in the picker (the small one says true). */
    open val startsSmall = false

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { push(ctx, mgr, it, startsSmall) } }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) { push(ctx, mgr, id, startsSmall) }

    companion object {
        /** Quick actions the app understands (MainActivity reads the "quick" extra). */
        const val NEAR = "near"
        const val PIN = "missing"

        /** Everything the widget shows, from what's saved on the phone. */
        data class Data(val counts: WidgetCounts?, val updated: Long)

        fun load(ctx: Context): Data {
            val prefs = Prefs(ctx)
            Lang.sw = prefs.lang.ifBlank { if (Locale.getDefault().language == "sw") "sw" else "en" } == "sw"
            val signedIn = prefs.pin.isNotBlank() || prefs.session.isNotBlank()
            val snap = if (signedIn) prefs.loadSnapshot()?.let { runCatching { Snapshot.from(it) }.getOrNull() } else null
            return Data(WidgetText.counts(snap, prefs.country), File(ctx.filesDir, "projects.json").lastModified())
        }

        /** Redraw every RAFIKI Pin widget on the home screen (quietly does nothing when there are none). */
        fun update(ctx: Context) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(ctx) ?: return
                listOf(PinWidget::class.java to false, PinWidgetSmall::class.java to true).forEach { (k, small) ->
                    mgr.getAppWidgetIds(ComponentName(ctx, k)).forEach { push(ctx, mgr, it, small) }
                }
            }
        }

        private fun push(ctx: Context, mgr: AppWidgetManager, id: Int, startsSmall: Boolean) {
            runCatching {
                val d = load(ctx)
                val rv = if (Build.VERSION.SDK_INT >= 31) RemoteViews(mapOf(SizeF(110f, 40f) to views(ctx, d, true), SizeF(220f, 100f) to views(ctx, d, false)))
                else {
                    val o = mgr.getAppWidgetOptions(id)
                    val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH); val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                    views(ctx, d, if (w == 0 || h == 0) startsSmall else w < 220 || h < 100)
                }
                mgr.updateAppWidget(id, rv)
            }
        }

        /** Opens the app; [quick] = NEAR or PIN opens Projects on that list. */
        fun open(ctx: Context, quick: String): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).setAction("org.rafikioutreach.pin.WIDGET_" + quick.ifBlank { "OPEN" })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("quick", quick)
            return PendingIntent.getActivity(ctx, 7100 + quick.hashCode() % 50, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun updatedText(at: Long, now: Long = System.currentTimeMillis()): String {
            if (at <= 0) return ""
            val today = SimpleDateFormat("yyyyMMdd", Locale.US).let { it.format(Date(at)) == it.format(Date(now)) }
            return if (today) SimpleDateFormat("HH:mm", Locale.US).format(Date(at)) else SimpleDateFormat("d MMM", Locale.US).format(Date(at))
        }

        /** The small (2×1) or medium (4×2) widget. */
        fun views(ctx: Context, d: Data, small: Boolean): RemoteViews {
            val c = d.counts
            val rv = RemoteViews(ctx.packageName, if (small) R.layout.widget_small else R.layout.widget_medium)
            rv.setOnClickPendingIntent(R.id.w_root, open(ctx, ""))
            rv.setOnClickPendingIntent(R.id.w_go_near, open(ctx, NEAR))
            rv.setOnClickPendingIntent(R.id.w_go_pin, open(ctx, PIN))
            rv.setTextViewText(R.id.w_go_near_t, WidgetText.nearLabel(small)); rv.setContentDescription(R.id.w_go_near, WidgetText.nearLabel(false))
            rv.setTextViewText(R.id.w_go_pin_t, WidgetText.pinLabel(small)); rv.setContentDescription(R.id.w_go_pin, WidgetText.pinLabel(false))
            if (small) {
                rv.setTextViewText(R.id.w_short, WidgetText.short(c))
                rv.setContentDescription(R.id.w_short, "RAFIKI Pin: " + WidgetText.line(c))
                return rv
            }
            rv.setTextViewText(R.id.w_time, if (c == null) "" else updatedText(d.updated))
            if (c == null || (c.due == 0 && c.urgent == 0 && c.mine == 0)) {
                rv.setViewVisibility(R.id.w_tiles, View.GONE); rv.setViewVisibility(R.id.w_msg, View.VISIBLE)
                rv.setTextViewText(R.id.w_msg, if (c == null) WidgetText.line(null) else t("All caught up ✓ Nothing due, no urgent repairs.", "Umemaliza yote ✓ Hakuna ziara inayosubiri wala matengenezo ya haraka."))
            } else {
                rv.setViewVisibility(R.id.w_tiles, View.VISIBLE); rv.setViewVisibility(R.id.w_msg, View.GONE)
                rv.setTextViewText(R.id.w_n_due, "${c.due}"); rv.setTextViewText(R.id.w_l_due, WidgetText.dueLabel(c.due))
                rv.setTextViewText(R.id.w_n_urgent, "${c.urgent}"); rv.setTextViewText(R.id.w_l_urgent, WidgetText.urgentLabel(c.urgent))
                rv.setTextViewText(R.id.w_n_yours, "${c.mine}"); rv.setTextViewText(R.id.w_l_yours, WidgetText.mineLabel())
                rv.setContentDescription(R.id.w_tiles, WidgetText.line(c))
            }
            return rv
        }
    }
}

/** The same widget, starting at 2×1 in the picker. */
class PinWidgetSmall : PinWidget() { override val startsSmall = true }
