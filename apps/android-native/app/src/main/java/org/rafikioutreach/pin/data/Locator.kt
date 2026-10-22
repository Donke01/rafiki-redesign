package org.rafikioutreach.pin.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the phone is: live, from GPS and the network, no Google services needed. */
data class Fix(val ll: LatLng, val acc: Int, val at: Long, /** which way you are moving (degrees from north), only while moving */ val bearing: Float? = null)

class Locator(context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix
    private var running = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val f = Fix(LatLng(l.latitude, l.longitude), l.accuracy.toInt().coerceAtLeast(1), System.currentTimeMillis(), if (l.hasBearing() && l.hasSpeed() && l.speed > 1.5f) l.bearing else null)
            val cur = _fix.value
            // keep a better recent fix rather than jumping to a vague network one
            if (!(cur == null || l.provider == LocationManager.GPS_PROVIDER || f.acc <= cur.acc || f.at - cur.at > 20000)) return
            // tiny GPS wobbles don't redraw the screens: only real moves or clearly better accuracy
            if (cur != null && Geo.metres(cur.ll, f.ll) < 4 && kotlin.math.abs(cur.acc - f.acc) < 5 && f.at - cur.at < 15000) return
            _fix.value = f
        }
        @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    val gpsOn: Boolean get() = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
                if (lm.allProviders.contains(p)) {
                    lm.getLastKnownLocation(p)?.let { l -> if (System.currentTimeMillis() - l.time < 120000) listener.onLocationChanged(l) }
                    lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
                }
            }
            running = true
        }
    }

    /** For tests: pretend the phone is here. */
    fun pretend(f: Fix) { _fix.value = f }

    fun stop() { runCatching { lm.removeUpdates(listener) }; running = false }
}
