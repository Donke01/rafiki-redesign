package org.rafikioutreach.pin.ui

import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.rafikioutreach.pin.AppModel

/**
 * Back, one step at a time (feat-nava).
 *
 * Every expanded thing (a card, a fold, a sub-tab, a visit step) calls [BackStep] while it is open. The step is put on the
 * phone's back stack at the moment it opens, so the most recently opened thing closes first; the screens' own back
 * (Shell in MainActivity.kt) only runs once nothing is open. Sheets and dialogs close themselves (their own window).
 */
@Composable
fun BackStep(open: Boolean, close: () -> Unit) {
    val owner = LocalOnBackPressedDispatcherOwner.current ?: return
    val life = LocalLifecycleOwner.current
    val now = rememberUpdatedState(close)
    DisposableEffect(open, owner, life) {
        if (!open) return@DisposableEffect onDispose {}
        val cb = object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { now.value() } }
        owner.onBackPressedDispatcher.addCallback(life, cb)
        onDispose { cb.remove() }
    }
}

/** "Start over" when a screen is opened from the bottom bar or a fresh open (back keeps where you were). */
object Nav {
    /** After this long in the background the map goes back to its default view. */
    const val MAP_RESET_MS = 5 * 60_000L
    /** The clock (tests move it on). */
    var now: () -> Long = { System.currentTimeMillis() }

    /** Clears what a tab keeps outside its screen: temporary filters and chips, the Me | Team switch, the map's
     *  filter, card-free view and layers. Saved settings (country, map style, Today's route), drafts and sign-in stay.
     *  [keepFilter]: the app itself asked for a list (Office overview, the widget, the welcome), so its filter stays. */
    fun startOver(m: AppModel, tab: String, keepFilter: Boolean = false) {
        val ctx = m.getApplication<android.app.Application>()
        when (tab) {
            "list" -> { if (!keepFilter) m.prefs.filter = "all"; m.prefs.ftype = "" }
            "map" -> {
                ctx.getSharedPreferences("map_style", android.content.Context.MODE_PRIVATE).edit().putString("mapf", "").apply()
                m.prefs.ftype = ""
                MapClean.on = false
                HomeLayers.heat = ""; HomeLayers.years = false; HomeLayers.playing = false; HomeLayers.team = false
                HomeLayers.follow = false; HomeLayers.north = false; HomeLayers.rot = 0f; HomeLayers.pick = null
            }
            "me" -> m.prefs.mePane = "me"
        }
    }

    /** A fresh open of the app: every tab starts over. */
    fun startOverAll(m: AppModel) {
        val asked = m.goFilter != null
        listOf("list", "map", "me").forEach { startOver(m, it, keepFilter = asked) }
    }
}
