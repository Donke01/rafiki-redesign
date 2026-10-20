package org.rafikioutreach.pin

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import org.rafikioutreach.pin.ui.BlueBrush
import org.rafikioutreach.pin.ui.C
import org.rafikioutreach.pin.ui.Ic
import org.rafikioutreach.pin.ui.LockScreen
import org.rafikioutreach.pin.ui.MapScreen
import org.rafikioutreach.pin.ui.MeScreen
import org.rafikioutreach.pin.ui.NewSiteScreen
import org.rafikioutreach.pin.ui.ProjectScreen
import org.rafikioutreach.pin.ui.ProjectsScreen
import org.rafikioutreach.pin.ui.RafikiTheme

/** Where you are in the app: a tab, or a project on top of it. */
data class Place(val tab: String, val project: String? = null, val projectTab: String = "pin", val sub: String? = null)

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.sw = model.prefs.lang.ifBlank { if (java.util.Locale.getDefault().language == "sw") "sw" else "en" } == "sw"
        enableEdgeToEdge()
        org.rafikioutreach.pin.ui.Look.mode = model.prefs.theme
        setContent { RafikiTheme { App(model) } }
        handle(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handle(intent) }

    /** monday.com sign-in sends the person back with org.rafikioutreach.pin://signin?code=… */
    private fun handle(i: android.content.Intent?) {
        val u = i?.data ?: return
        if (u.scheme != "org.rafikioutreach.pin" || u.host != "signin") return
        i.data = null
        model.showBanner(t("Signing in…", "Inaingia…"))
        lifecycleScope.launch { model.showBanner(model.mondayReturn(u.getQueryParameter("code"), u.getQueryParameter("fail"))) }
    }
}

@Composable
fun App(m: AppModel) {
    val signedIn by m.signedIn.collectAsState()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.values.any { ok -> ok }) m.locator.start() }
    LaunchedEffect(signedIn) { if (signedIn) ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    // GPS only while the app is open, and restarted when coming back to it
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> when (e) { Lifecycle.Event.ON_RESUME -> if (m.signedIn.value) { m.locator.start(); m.refresh() }; Lifecycle.Event.ON_PAUSE -> m.locator.stop(); else -> {} } }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    // welcome: before signing in, and for a moment every time the app opens; the full one the first time
    var helloDone by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(!org.rafikioutreach.pin.ui.Onb.enabled) }
    var onbSeen by remember { androidx.compose.runtime.mutableStateOf(m.prefs.onb) }
    Box(Modifier.fillMaxSize()) {
        org.rafikioutreach.pin.ui.AppBackground(Modifier.matchParentSize())
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            if (!signedIn) LockScreen(m) else Shell(m)
        }
        when {
            !signedIn && !helloDone -> org.rafikioutreach.pin.ui.Welcome(m, false) { helloDone = true }
            signedIn && org.rafikioutreach.pin.ui.Onb.enabled && (!onbSeen || m.replayOnb) -> org.rafikioutreach.pin.ui.Onboarding(m) { role ->
                onbSeen = true; helloDone = true; m.replayOnb = false
                m.goTab = if (role == "office" && m.snap.value?.admin == true) "office" else "list"
            }
            signedIn && !helloDone -> org.rafikioutreach.pin.ui.Welcome(m, true) { helloDone = true }
        }
    }
}

@Composable
fun Shell(m: AppModel) {
    val stack = remember { mutableStateListOf(Place("list")) }
    val here = stack.last()
    val waiting by m.waiting.collectAsState()
    val busy by m.busy.collectAsState()
    BackHandler(stack.size > 1) { stack.removeAt(stack.lastIndex) }
    fun go(p: Place) { stack.add(p) }
    fun tab(t: String) { stack.clear(); stack.add(Place(t)) }
    val goTab = m.goTab
    LaunchedEffect(goTab) { if (goTab != null) { tab(goTab); m.goTab = null } }
    val sub = here.project != null || here.sub != null

    Column(Modifier.fillMaxSize()) {
        // app header: you (or back), the wordmark, refresh
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                if (sub) RoundBtn("back", t("Back", "Rudi"), "hback") { stack.removeAt(stack.lastIndex) }
                else Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF47B4F5), Color(0xFF0A78C2))))
                    .clickable(role = Role.Button) { tab("me") }.semantics { contentDescription = t("Me", "Mimi") }.testTag("hav"), contentAlignment = Alignment.Center) {
                    Text(m.prefs.name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp))
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { org.rafikioutreach.pin.ui.Wordmark() }
            RoundBtn("refresh", t("Refresh", "Onyesha upya"), "hrefresh", turn = busy) { m.refresh() }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val pid = here.project
            when {
                pid != null -> ProjectScreen(m, pid, here.projectTab) { stack.removeAt(stack.lastIndex) }
                here.sub == "report" -> org.rafikioutreach.pin.ui.ReportScreen(m) { stack.removeAt(stack.lastIndex) }
                here.sub == "route" -> org.rafikioutreach.pin.ui.RouteScreen(m, { go(Place(here.tab, it)) }) { stack.removeAt(stack.lastIndex) }
                here.tab == "map" -> MapScreen(m, { go(Place("map", it)) }) { go(Place("map", it, "visit")) }
                here.tab == "new" -> NewSiteScreen(m) { id -> if (id != null) { tab("list"); go(Place("list", id)) } }
                here.tab == "me" -> MeScreen(m) { go(Place("me", sub = "report")) }
                here.tab == "office" -> org.rafikioutreach.pin.ui.OfficeScreen(m) { go(Place("office", it)) }
                else -> ProjectsScreen(m, { go(Place("list", it)) }, { go(Place("list", sub = "route")) }, { tab("new") }) { go(Place("list", it, "visit")) }
            }
        }
        // bottom tabs, floating
        val glassy = with(org.rafikioutreach.pin.ui.C) { this === org.rafikioutreach.pin.ui.Light || this === org.rafikioutreach.pin.ui.Dark }
        val barShape = RoundedCornerShape(28.dp)
        Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp, top = 6.dp).fillMaxWidth().shadow(16.dp, barShape, spotColor = Color(0x33144678)).clip(barShape)
            .background(if (glassy) (if (C.dark) Color(0xEB1E2A36) else Color(0xF2FFFFFF)) else C.card).border(1.dp, if (glassy) (if (C.dark) Color(0x17FFFFFF) else Color.White) else C.line, barShape).height(68.dp), verticalAlignment = Alignment.CenterVertically) {
            val admin = m.snap.collectAsState().value?.admin == true
            val review = m.snap.collectAsState().value?.reviewOpen ?: 0
            (listOf(Triple("map", t("Map", "Ramani"), "map"), Triple("list", t("Projects", "Miradi"), "list"), Triple("new", t("New site", "Eneo jipya"), "plus"), Triple("me", t("Me", "Mimi"), "person")) +
                (if (admin) listOf(Triple("office", t("Office", "Ofisi"), "inbox")) else emptyList())).forEach { (k, label, icon) ->
                val on = here.tab == k && here.project == null
                Column(Modifier.weight(1f).clickable(role = Role.Tab) { tab(k) }.padding(vertical = 6.dp).testTag("nav_$k"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        if (k == "new") Box(Modifier.size(48.dp, 34.dp).clip(RoundedCornerShape(14.dp)).background(with(org.rafikioutreach.pin.ui.C) { if (this === org.rafikioutreach.pin.ui.Light) org.rafikioutreach.pin.ui.InkBrush else BlueBrush }), contentAlignment = Alignment.Center) { Icon(Ic.get(icon), null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                        else Box(Modifier.size(52.dp, 30.dp).clip(RoundedCornerShape(99.dp)).background(if (on) C.tint else Color.Transparent), contentAlignment = Alignment.Center) { Icon(Ic.get(icon), null, tint = if (on) C.blue else C.muted, modifier = Modifier.size(24.dp)) }
                        if (k == "office" && review > 0) Text("$review", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(99.dp)).background(C.warn).padding(horizontal = 5.dp))
                        if (k == "me" && waiting > 0) Text("$waiting", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(99.dp)).background(C.warn).padding(horizontal = 5.dp))
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall, color = if (on) C.blue else C.muted, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun RoundBtn(icon: String, label: String, tag: String, turn: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(C.card.copy(alpha = .8f)).border(1.dp, C.line, androidx.compose.foundation.shape.CircleShape)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        // turns smoothly while refreshing, then settles
        val spin = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
        androidx.compose.runtime.LaunchedEffect(turn) {
            if (turn) while (true) { spin.animateTo(spin.value + 360f, androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)) }
            else spin.animateTo((kotlin.math.ceil(spin.value / 360f) * 360f), androidx.compose.animation.core.tween(300))
        }
        Icon(Ic.get(icon), null, tint = C.ink, modifier = Modifier.size(20.dp).rotate(spin.value))
    }
}
