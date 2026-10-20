package org.rafikioutreach.pin.ui

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** A Google map style (Map Tiles API): one session gives the tiles for street or satellite. */
class GoogleSource(name: String, private val session: String, private val key: String, maxZoom: Int) :
    OnlineTileSourceBase(name, 3, maxZoom, 256, "", arrayOf("https://tile.googleapis.com/v1/2dtiles/"), "Map data © Google") {
    override fun getTileURLString(i: Long) = baseUrl + MapTileIndex.getZoom(i) + "/" + MapTileIndex.getX(i) + "/" + MapTileIndex.getY(i) +
        "?session=" + URLEncoder.encode(session, "UTF-8") + "&key=" + URLEncoder.encode(key, "UTF-8")
    val sess get() = session
    val apiKey get() = key
}

class GoogleMaps(val road: GoogleSource, val sat: GoogleSource)

/** Google maps for the app when the office has set an Android key; otherwise the maps stay OpenStreetMap / Esri.
 *  The key is checked by Google against this app's package name and signing certificate. */
object GoogleTiles {
    private val _maps = MutableStateFlow<GoogleMaps?>(null)
    val maps: StateFlow<GoogleMaps?> = _maps
    private var key = ""
    private var busy = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Lets tests swap the network. */
    var transport: (url: String, body: String?, headers: Map<String, String>) -> Pair<Int, String> = ::http
    private var headers: Map<String, String> = emptyMap()

    fun configure(ctx: Context, k: String?) {
        val nk = k.orEmpty()
        if (nk.isBlank()) { key = ""; _maps.value = null; return }
        if (nk == key && (_maps.value != null || busy)) return
        key = nk
        val app = ctx.applicationContext
        headers = mapOf("X-Android-Package" to app.packageName, "X-Android-Cert" to cert(app))
        // the same headers on every tile request, so Google accepts the key from this app
        runCatching { Configuration.getInstance().additionalHttpRequestProperties.putAll(headers) }
        val sp = app.getSharedPreferences("gtiles", Context.MODE_PRIVATE)
        val saved = runCatching { JSONObject(sp.getString("s", "") ?: "") }.getOrNull()
        if (saved != null && saved.optString("k") == nk && saved.optLong("exp") > System.currentTimeMillis() + 3_600_000) {
            _maps.value = GoogleMaps(GoogleSource("Google Roadmap", saved.getString("road"), nk, 21), GoogleSource("Google Satellite", saved.getString("sat"), nk, 21)); return
        }
        busy = true
        scope.launch {
            try {
                val road = session(nk, JSONObject().put("mapType", "roadmap").put("language", "en-US").put("region", "KE"))
                val sat = session(nk, JSONObject().put("mapType", "satellite").put("layerTypes", JSONArray().put("layerRoadmap")).put("language", "en-US").put("region", "KE"))
                if (road != null && sat != null && key == nk) {
                    val exp = minOf(road.second, sat.second)
                    sp.edit().putString("s", JSONObject().put("k", nk).put("road", road.first).put("sat", sat.first).put("exp", exp).toString()).apply()
                    _maps.value = GoogleMaps(GoogleSource("Google Roadmap", road.first, nk, 21), GoogleSource("Google Satellite", sat.first, nk, 21))
                }
            } finally { busy = false }
        }
    }

    private fun session(k: String, body: JSONObject): Pair<String, Long>? = runCatching {
        val (st, txt) = transport("https://tile.googleapis.com/v1/createSession?key=" + URLEncoder.encode(k, "UTF-8"), body.toString(), headers)
        if (st !in 200..299) return null
        val j = JSONObject(txt); val s = j.optString("session"); if (s.isBlank()) return null
        s to ((j.optString("expiry").toLongOrNull() ?: 0L) * 1000L).let { if (it > 0) it else System.currentTimeMillis() + 86_400_000L }
    }.getOrNull()

    /** The copyright Google asks to show for what is on screen. */
    fun copyright(src: GoogleSource, zoom: Int, n: Double, s: Double, e: Double, w: Double): String? = runCatching {
        val u = "https://tile.googleapis.com/tile/v1/viewport?session=" + URLEncoder.encode(src.sess, "UTF-8") + "&key=" + URLEncoder.encode(src.apiKey, "UTF-8") +
            "&zoom=$zoom&north=${"%.5f".format(java.util.Locale.US, n)}&south=${"%.5f".format(java.util.Locale.US, s)}&east=${"%.5f".format(java.util.Locale.US, e)}&west=${"%.5f".format(java.util.Locale.US, w)}"
        val (st, txt) = transport(u, null, headers)
        if (st in 200..299) JSONObject(txt).optString("copyright").ifBlank { null } else null
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun cert(ctx: Context): String = runCatching {
        val pm = ctx.packageManager
        val sig = if (android.os.Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
            else pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        MessageDigest.getInstance("SHA-1").digest(sig!!.toByteArray()).joinToString("") { "%02X".format(it) }
    }.getOrDefault("")

    private fun http(url: String, body: String?, h: Map<String, String>): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        h.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val st = c.responseCode
        val txt = (if (st in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        return st to txt
    }
}
