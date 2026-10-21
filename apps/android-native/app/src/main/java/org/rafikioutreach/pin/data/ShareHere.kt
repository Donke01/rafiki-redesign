package org.rafikioutreach.pin.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.rafikioutreach.pin.t
import java.time.LocalDateTime

/**
 * Me › Settings › Security and alerts › Share my location with the office.
 *
 * Privacy first: off unless the person turns it on; only in the work hours they choose (Mon–Sat 07:00–18:00 to
 * start with); at most every 10 minutes; while the app is open, and from the background alert check only when
 * the location permission was already given (the app never asks for background location). The server keeps
 * only the latest point, for 12 hours (lib/pin-here.js on the website), and only admins see it on the Team map.
 * The settings are kept on this phone.
 */
object ShareHere {
    /** [days]: 1 = Monday … 7 = Sunday. [from] / [to]: minutes after midnight. */
    data class Hours(val on: Boolean = false, val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6), val from: Int = 7 * 60, val to: Int = 18 * 60)

    const val GAP_MS = 10 * 60_000L
    /** Bumped on every change, so the screens (the "Sharing" bar, Settings) redraw at once. */
    val rev = MutableStateFlow(0)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("rafiki-share", Context.MODE_PRIVATE)

    fun load(ctx: Context): Hours {
        val s = sp(ctx)
        val days = (s.getString("days", null) ?: "1,2,3,4,5,6").split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()
        return Hours(s.getBoolean("on", false), days, s.getInt("from", 7 * 60).coerceIn(0, 1439), s.getInt("to", 18 * 60).coerceIn(0, 1439))
    }
    fun save(ctx: Context, h: Hours) {
        sp(ctx).edit().putBoolean("on", h.on).putString("days", h.days.sorted().joinToString(",")).putInt("from", h.from).putInt("to", h.to).apply()
        if (!h.on) lastSent(ctx, 0L)
        rev.value++
    }
    fun lastSent(ctx: Context): Long = sp(ctx).getLong("sent", 0L)
    fun lastSent(ctx: Context, at: Long) { sp(ctx).edit().putLong("sent", at).apply() }

    fun inHours(h: Hours, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val m = now.hour * 60 + now.minute
        if (now.dayOfWeek.value !in h.days) return false
        return if (h.from <= h.to) m >= h.from && m < h.to else m >= h.from || m < h.to
    }

    fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)
    private val DAYS = listOf("Mon" to "Jtt", "Tue" to "Jnn", "Wed" to "Jtn", "Thu" to "Alh", "Fri" to "Ijm", "Sat" to "Jms", "Sun" to "Jpl")
    fun dayName(d: Int) = DAYS[d - 1].let { t(it.first, it.second) }
    /** "Mon–Sat · 07:00–18:00" */
    fun hoursText(h: Hours): String {
        val ds = h.days.sorted()
        val run = ds.size > 2 && ds.zipWithNext().all { (a, b) -> b == a + 1 }
        val d = when { ds.isEmpty() -> t("No days", "Hakuna siku"); run -> dayName(ds.first()) + "–" + dayName(ds.last()); else -> ds.joinToString(", ") { dayName(it) } }
        return d + " · " + hhmm(h.from) + "–" + hhmm(h.to)
    }

    /** Sharing right now: turned on, signed in with their own name (not the shared team PIN), and in work hours. */
    fun active(h: Hours, ownName: Boolean, now: LocalDateTime = LocalDateTime.now()) = h.on && ownName && inHours(h, now)

    /** Is a new point due? (at most every 10 minutes) */
    fun due(ctx: Context, now: Long = System.currentTimeMillis()) = now - lastSent(ctx) >= GAP_MS

    fun body(f: Fix): JSONObject = JSONObject().put("action", "here").put("lat", f.ll.lat).put("lng", f.ll.lng).put("acc", f.acc)

    /** Sends where the phone is, if sharing and due. Returns true when a point went out. */
    suspend fun maybeSend(ctx: Context, api: Api, ownName: Boolean, f: Fix?): Boolean {
        val h = load(ctx)
        if (f == null || !active(h, ownName) || !due(ctx)) return false
        lastSent(ctx, System.currentTimeMillis()); rev.value++
        return api.post(body(f)).ok
    }

    /** Turned off: the server forgets the point straight away. */
    suspend fun stop(ctx: Context, api: Api) {
        save(ctx, load(ctx).copy(on = false))
        runCatching { api.post(JSONObject().put("action", "here").put("off", true)) }
    }

    private fun granted(ctx: Context) = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /**
     * From the background alert check (about every 15 minutes): only in work hours, only with the location
     * permission already given, and only with a recent position the phone already has (no GPS is switched on
     * from the background, and background location is never asked for).
     */
    @SuppressLint("MissingPermission")
    suspend fun fromWorker(ctx: Context, prefs: Prefs, api: Api) {
        val own = prefs.loadSnapshot()?.optString("who").orEmpty().isNotBlank()
        if (!active(load(ctx), own) || !due(ctx) || !granted(ctx)) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val best = runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER).filter { lm.allProviders.contains(it) }
                .mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        }.getOrNull() ?: return
        if (System.currentTimeMillis() - best.time > 15 * 60_000L) return
        maybeSend(ctx, api, own, Fix(LatLng(best.latitude, best.longitude), best.accuracy.toInt().coerceAtLeast(1), best.time))
    }
}
