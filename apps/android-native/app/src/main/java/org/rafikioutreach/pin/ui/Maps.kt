package org.rafikioutreach.pin.ui

import org.rafikioutreach.pin.t
import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.rafikioutreach.pin.BuildConfig
import org.rafikioutreach.pin.data.LatLng
import java.io.File

/** Satellite photos (Esri World Imagery), the same as the web app's satellite view. */
object Satellite : OnlineTileSourceBase("Esri World Imagery", 1, 19, 256, "", arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")) {
    override fun getTileURLString(i: Long) = baseUrl + MapTileIndex.getZoom(i) + "/" + MapTileIndex.getY(i) + "/" + MapTileIndex.getX(i)
}

object MapSetup {
    private var done = false
    fun init(ctx: Context) {
        if (done) return
        val c = Configuration.getInstance()
        c.userAgentValue = "RAFIKI-Pin/" + BuildConfig.VERSION_NAME + " (" + BuildConfig.APPLICATION_ID + ")"
        c.osmdroidBasePath = File(ctx.filesDir, "osm")
        c.osmdroidTileCache = File(ctx.cacheDir, "tiles")
        c.tileFileSystemCacheMaxBytes = 120L * 1024 * 1024 // map tiles seen before still show with no signal
        done = true
    }
}

data class Dot(val id: String, val ll: LatLng, val color: Int, val big: Boolean, val title: String)

private fun dotDrawable(color: Int, big: Boolean) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL; setColor(color); setStroke(5, android.graphics.Color.WHITE)
    val s = if (big) 46 else 38; setSize(s, s)
}

/** A map of project dots. Tap a dot to open the project.
 *  Dots are only rebuilt when the projects change; your own dot just moves, so live updates never flash. */
@Composable
fun ProjectsMap(dots: List<Dot>, me: LatLng?, onDot: (String) -> Unit, modifier: Modifier = Modifier, fitKey: Any? = null) {
    val ctx = LocalContext.current
    MapSetup.init(ctx)
    var sat by remember { mutableStateOf(false) }
    val onDotNow by androidx.compose.runtime.rememberUpdatedState(onDot)
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            controller.setZoom(6.0); controller.setCenter(GeoPoint(-2.5, 35.5)); minZoomLevel = 3.0
            isTilesScaledToDpi = true
        }
    }
    val holder = remember { MapState() }
    var fitted by remember(fitKey) { mutableStateOf(false) }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    Box(modifier.clipToBounds()) {
        AndroidView({ map }, Modifier.fillMaxSize().clipToBounds(), update = { mv ->
            val src = if (sat) Satellite else TileSourceFactory.MAPNIK
            if (mv.tileProvider.tileSource != src) mv.setTileSource(src)
            var changed = false
            if (holder.dots != dots) {
                holder.dots = dots; changed = true
                holder.markers.forEach { mv.overlays.remove(it) }
                holder.markers = dots.map { d ->
                    Marker(mv).apply {
                        position = GeoPoint(d.ll.lat, d.ll.lng); icon = dotDrawable(d.color, d.big); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = d.title; setOnMarkerClickListener { _, _ -> onDotNow(d.id); true }
                    }
                }
                mv.overlays.addAll(0, holder.markers)
            }
            if (me != null) {
                val m = holder.me ?: Marker(mv).apply { icon = dotDrawable(0xFF098BE1.toInt(), false); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null) }.also { holder.me = it; mv.overlays.add(it) }
                if (holder.meAt != me) { holder.meAt = me; m.position = GeoPoint(me.lat, me.lng); changed = true }
            } else holder.me?.let { mv.overlays.remove(it); holder.me = null; holder.meAt = null; changed = true }
            if (!fitted && dots.size > 1) {
                fitted = true
                val box = BoundingBox.fromGeoPointsSafe(dots.map { GeoPoint(it.ll.lat, it.ll.lng) })
                mv.post { runCatching { mv.zoomToBoundingBox(box.increaseByScale(1.25f), false) } }
            }
            if (changed) mv.invalidate()
        })
        MapButton(if (sat) "map" else "layers", if (sat) t("Show the street map", "Onyesha ramani ya barabara") else t("Show satellite photos", "Onyesha picha za satelaiti"), Modifier.align(Alignment.TopEnd).padding(10.dp)) { sat = !sat }
        if (me != null) MapButton("target", t("Go to where I am", "Nenda nilipo"), Modifier.align(Alignment.TopEnd).padding(top = 66.dp, end = 10.dp)) {
            map.controller.animateTo(GeoPoint(me.lat, me.lng), 15.0, 600L)
        }
    }
}

private class MapState { var dots: List<Dot>? = null; var markers: List<Marker> = emptyList(); var me: Marker? = null; var meAt: LatLng? = null }

/** Choose a spot: move the map under the cross in the middle. */
@Composable
fun PickMap(start: LatLng?, onMove: (LatLng) -> Unit, modifier: Modifier = Modifier, jumpTo: LatLng? = null, others: List<Dot> = emptyList()) {
    val ctx = LocalContext.current
    MapSetup.init(ctx)
    var sat by remember { mutableStateOf(true) }
    val map = remember {
        MapView(ctx).apply {
            setTileSource(Satellite); setMultiTouchControls(true); zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            controller.setZoom(if (start != null) 17.0 else 6.0); controller.setCenter(start?.let { GeoPoint(it.lat, it.lng) } ?: GeoPoint(-2.5, 35.5))
            addMapListener(object : MapListener {
                override fun onScroll(e: ScrollEvent?): Boolean { mapCenter?.let { onMoveRef?.invoke(LatLng(it.latitude, it.longitude)) }; return false }
                override fun onZoom(e: ZoomEvent?): Boolean = false
            })
        }
    }
    onMoveRef = onMove
    var lastJump by remember { mutableStateOf<LatLng?>(null) }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause(); map.onDetach(); onMoveRef = null } }
    val held = remember { MapState() }
    Box(modifier.clip(RoundedCornerShape(18.dp)).clipToBounds()) {
        AndroidView({ map }, Modifier.fillMaxSize().clipToBounds(), update = { mv ->
            val src = if (sat) Satellite else TileSourceFactory.MAPNIK
            if (mv.tileProvider.tileSource != src) mv.setTileSource(src)
            if (held.dots != others) {
                held.dots = others
                held.markers.forEach { mv.overlays.remove(it) }
                held.markers = others.map { d -> Marker(mv).apply { position = GeoPoint(d.ll.lat, d.ll.lng); icon = dotDrawable(d.color, false); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null) } }
                mv.overlays.addAll(held.markers)
            }
            if (jumpTo != null && jumpTo != lastJump) {
                lastJump = jumpTo
                mv.controller.animateTo(GeoPoint(jumpTo.lat, jumpTo.lng), maxOf(mv.zoomLevelDouble, 17.0), 500L)
                onMove(jumpTo)
            }
            mv.invalidate()
        })
        // the cross marks the chosen spot
        Box(Modifier.align(Alignment.Center).size(44.dp), contentAlignment = Alignment.Center) {
            Icon(Ic.get("target"), null, tint = Color.White, modifier = Modifier.size(44.dp))
            Icon(Ic.get("target"), null, tint = Red, modifier = Modifier.size(38.dp))
        }
        MapButton(if (sat) "map" else "layers", if (sat) t("Show the street map", "Onyesha ramani ya barabara") else t("Show satellite photos", "Onyesha picha za satelaiti"), Modifier.align(Alignment.TopEnd).padding(10.dp)) { sat = !sat }
    }
}

private var onMoveRef: ((LatLng) -> Unit)? = null

@Composable
private fun MapButton(icon: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.dp, Color(0x33000000), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(Ic.get(icon), null, tint = Color(0xFF04263F), modifier = Modifier.size(24.dp)) }
}

