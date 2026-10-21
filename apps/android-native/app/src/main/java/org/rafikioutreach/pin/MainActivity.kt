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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.togetherWith
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
import org.rafikioutreach.pin.ui.hue
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

class MainActivity : androidx.fragment.app.FragmentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.sw = model.prefs.lang.ifBlank { if (java.util.Locale.getDefault().language == "sw") "sw" else "en" } == "sw"
        enableEdgeToEdge()
        org.rafikioutreach.pin.ui.Look.mode = model.prefs.theme
        org.rafikioutreach.pin.ui.TextSize.value = model.prefs.textSize
        setContent { RafikiTheme { App(model) } }
        handle(intent)
        // phone alerts while the app is closed (Me › Settings › Notifications)
        if (model.prefs.phoneAlerts) org.rafikioutreach.pin.data.AlertWorker.schedule(applicationContext)
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handle(intent) }

    /** monday.com sign-in sends the person back with org.rafikioutreach.pin://signin?code=… */
    private fun handle(i: android.content.Intent?) {
        // a tap on a phone alert: open that project, or Me
        i?.getStringExtra("open")?.let { id -> i.removeExtra("open"); if (id.isNotBlank()) model.goProject = id else model.goTab = "me" }
        // the home-screen widget: Visit nearby / Pin a location open Projects on that list (like Home's quick actions)
        i?.getStringExtra("quick")?.let { q -> i.removeExtra("quick"); model.quick(q) }
        val u = i?.data ?: return
        // a RAFIKI QR sticker scanned with the phone's own camera: https://pin.rafikioutreach.org/pin#p=<id>
        if (org.rafikioutreach.pin.ui.QrCheckin.fromLink(u)) { i.data = null; return }
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
        val o = LifecycleEventObserver { _, e -> when (e) { Lifecycle.Event.ON_RESUME -> if (m.signedIn.value) {
                m.locator.start()
                // back from the camera, a photo or a permission box: no reload; away a while: fetch quietly
                if (System.currentTimeMillis() - m.lastRefresh > 5 * 60_000) m.refresh(quiet = true)
            }; Lifecycle.Event.ON_PAUSE -> m.locator.stop(); Lifecycle.Event.ON_STOP -> m.wentAway(); Lifecycle.Event.ON_START -> m.cameBack(); else -> {} } }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    // welcome: before signing in, and for a moment every time the app opens; the full one the first time
    var helloDone by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(!org.rafikioutreach.pin.ui.Onb.enabled) }
    var onbSeen by remember { androidx.compose.runtime.mutableStateOf(m.prefs.onb) }
    Box(Modifier.fillMaxSize()) {
        org.rafikioutreach.pin.ui.AppBackground(Modifier.matchParentSize())
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            val bioLocked by m.bioLocked.collectAsState()
            if (!signedIn) LockScreen(m) else if (bioLocked) org.rafikioutreach.pin.ui.BioLockScreen(m) else Shell(m)
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun Shell(m: AppModel) {
    val stack = remember { mutableStateListOf(Place("list")) }
    val here = stack.last()
    val waiting by m.waiting.collectAsState()
    val upd by m.update.collectAsState()
    val updReady = (upd?.code ?: 0) > BuildConfig.VERSION_CODE
    // a newer app: checked on opening, whenever the app comes back to the front, and every 15 minutes while open,
    // so the pop-up appears by itself (no need to tap Check)
    LaunchedEffect(Unit) { m.checkUpdateSoon(0); while (true) { kotlinx.coroutines.delay(15 * 60_000L); m.checkUpdateSoon(10) } }
    val life = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(life) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) m.checkUpdateSoon(2) }
        life.lifecycle.addObserver(o); onDispose { life.lifecycle.removeObserver(o) }
    }
    val busy by m.busy.collectAsState()
    // tabs visited before this one, so the phone's back button steps back through them
    val tabsBefore = remember { mutableStateListOf<String>() }
    fun go(p: Place) { stack.add(p) }
    fun tab(t: String) {
        val from = stack.first().tab
        if (from != t) { tabsBefore.remove(t); tabsBefore.add(from) }
        stack.clear(); stack.add(Place(t))
    }
    // back: leave the open screen, then the previous tab, then Projects. On Projects it never quits:
    // the first press says "Press back again to leave", the second puts the app in the background (still running).
    val act = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    var backAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    BackHandler(true) {
        when {
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            tabsBefore.isNotEmpty() -> { val t = tabsBefore.removeAt(tabsBefore.lastIndex); stack.clear(); stack.add(Place(t)) }
            here.tab != "list" -> { stack.clear(); stack.add(Place("list")) }
            System.currentTimeMillis() - backAt < 2000 -> { backAt = 0L; act?.moveTaskToBack(true) }
            else -> { backAt = System.currentTimeMillis(); act?.let { android.widget.Toast.makeText(it, t("Press back again to leave", "Bonyeza rudi tena kuondoka"), android.widget.Toast.LENGTH_SHORT).show() } }
        }
    }
    // notifications: the bell (top right) needs what the team did, so keep it fresh
    LaunchedEffect(Unit) { while (true) { runCatching { m.loadActivity() }; kotlinx.coroutines.delay(5 * 60_000L) } }
    val goTab = m.goTab
    LaunchedEffect(goTab) { if (goTab != null) { tab(goTab); m.goTab = null } }
    val goProject = m.goProject
    LaunchedEffect(goProject) { if (goProject != null) { go(Place(here.tab, goProject)); m.goProject = null } }
    // a QR sticker opened from outside the app: that project's Site visit, checked in by QR
    val qrOpen = org.rafikioutreach.pin.ui.QrCheckin.openReq
    LaunchedEffect(qrOpen) {
        if (qrOpen != null) {
            org.rafikioutreach.pin.ui.QrCheckin.openReq = null
            if (m.snap.value?.projects?.any { it.id == qrOpen } == true) { org.rafikioutreach.pin.ui.QrCheckin.mark(qrOpen); go(Place(here.tab, qrOpen, "visit")) }
            else m.showBanner(t("That QR code’s project isn’t in your list. Pull down to refresh, then scan again.", "Mradi wa msimbo huo wa QR haupo kwenye orodha yako. Vuta chini kuonyesha upya, kisha changanua tena."))
        }
    }
    val sub = here.project != null || here.sub != null
    val hp = org.rafikioutreach.pin.ui.rememberHaptics()
    org.rafikioutreach.pin.ui.UpdatePrompt(m, updReady)

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
            // the wordmark, or the screen's title once its big title has scrolled away
            Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedContent(m.bar.text, transitionSpec = {
                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) + androidx.compose.animation.slideInVertically { it / 2 }) togetherWith
                        (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)) + androidx.compose.animation.slideOutVertically { -it / 2 })
                }, label = "bar") { title ->
                    if (title == null) org.rafikioutreach.pin.ui.Wordmark()
                    else Text(title, style = MaterialTheme.typography.titleMedium, color = C.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.testTag("bar_title"))
                }
            }
            // notifications, top right (pull a screen down to refresh it)
            org.rafikioutreach.pin.ui.Bell(m) { go(Place(here.tab, sub = "notes")) }
        }
        // "Sharing location with the office · Stop", on every screen while sharing (Me › Settings › Share my location)
        org.rafikioutreach.pin.ui.SharingBar(m)
        // pull down to refresh (on screens that scroll)
        var pulling by remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(pulling, { pulling = true; m.refresh { pulling = false } }, Modifier.weight(1f).fillMaxWidth().testTag("pull")) {
            // screens slide in from the right when you go deeper and slide back out when you go back;
            // switching tabs fades through
            val pop = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
            androidx.compose.animation.AnimatedContent(
                here to stack.size, Modifier.fillMaxSize(), label = "screens",
                contentKey = { it.first },
                transitionSpec = {
                    val (_, from) = initialState; val (_, to) = targetState
                    val ms = if (org.rafikioutreach.pin.ui.Motion.on) 300 else 0
                    val ease = androidx.compose.animation.core.FastOutSlowInEasing
                    when {
                        ms == 0 -> androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                        to > from -> (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(ms, easing = ease)) { it } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(ms / 2))) togetherWith
                            (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(ms, easing = ease)) { -it / 4 } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(ms)))
                        to < from -> (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(ms, easing = ease)) { -it / 4 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(ms))) togetherWith
                            (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(ms, easing = ease)) { it } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(ms / 2)))
                        else -> (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 60)) + androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(220, delayMillis = 60), initialScale = 0.98f)) togetherWith
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90))
                    }.using(androidx.compose.animation.SizeTransform(clip = false))
                },
            ) { (pl, _) ->
                Box(Modifier.fillMaxSize()) {
                    val pid = pl.project
                    when {
                        pid != null -> ProjectScreen(m, pid, pl.projectTab) { pop() }
                        pl.sub == "search" -> { val snap = m.snap.collectAsState().value; val fx = m.fix.collectAsState().value; if (snap != null) org.rafikioutreach.pin.ui.SearchScreen(m, snap, fx?.ll, { go(Place(pl.tab, it)) }) { pop() } }
                        pl.sub == "report" -> org.rafikioutreach.pin.ui.ReportScreen(m) { pop() }
                        pl.sub == "notes" -> org.rafikioutreach.pin.ui.NotificationsScreen(m, { go(Place(pl.tab, it)) }, { go(Place(pl.tab, sub = "set_notify")) }) { pop() }
                        pl.sub == "set_share" -> org.rafikioutreach.pin.ui.ShareSettingsPage(m) { pop() }
                        pl.sub?.startsWith("set_") == true -> org.rafikioutreach.pin.ui.SettingsPage(m, pl.sub.removePrefix("set_")) { pop() }
                        pl.sub == "route" -> org.rafikioutreach.pin.ui.RouteScreen(m, { go(Place(pl.tab, it)) }) { pop() }
                        pl.sub == "offline" -> org.rafikioutreach.pin.ui.OfflineScreen(m) { pop() }
                        pl.sub == "scan" -> org.rafikioutreach.pin.ui.ScanScreen(m, { id -> pop(); go(Place(pl.tab, id, "visit")) }) { pop() }
                        pl.tab == "map" -> MapScreen(m, { go(Place("map", it)) }, { go(Place("map", it, "visit")) }) { go(Place("map", sub = "offline")) }
                        pl.tab == "new" -> NewSiteScreen(m) { id -> if (id != null) { tab("list"); go(Place("list", id)) } }
                        pl.tab == "me" -> MeScreen(m, { go(Place("me", it)) }, { go(Place("me", sub = "report")) }, notes = { go(Place("me", sub = "notes")) }) { go(Place("me", sub = "set_$it")) }
                        pl.tab == "office" -> org.rafikioutreach.pin.ui.OfficeScreen(m) { go(Place("office", it)) }
                        else -> ProjectsScreen(m, { go(Place("list", it)) }, { go(Place("list", sub = "route")) }, { tab("new") }, { go(Place("list", it, "visit")) }, scan = { go(Place("list", sub = "scan")) }) { go(Place("list", sub = "search")) }
                    }
                }
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
                val hu = org.rafikioutreach.pin.ui.C.hue(k)
                Column(Modifier.weight(1f).clickable(role = Role.Tab) { hp.tap(); tab(k) }.padding(vertical = 6.dp).testTag("nav_$k"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        if (k == "new") Box(Modifier.size(48.dp, 30.dp).clip(RoundedCornerShape(14.dp)).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF9A78F0), Color(0xFF6E46C8)))), contentAlignment = Alignment.Center) { Icon(Ic.get(icon), null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                        else Box(Modifier.size(52.dp, 30.dp).clip(RoundedCornerShape(99.dp)).background(if (on) hu.bg else Color.Transparent), contentAlignment = Alignment.Center) { Icon(Ic.get(icon), null, tint = if (on) hu.fg else C.muted, modifier = Modifier.size(24.dp)) }
                        if (k == "office" && review > 0) Text("$review", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(99.dp)).background(C.warn).padding(horizontal = 5.dp))
                        if (k == "me" && waiting == 0 && updReady) Box(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 10.dp).size(10.dp).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape).background(C.blue).testTag("update_dot"))
                        if (k == "me" && waiting > 0) Text("$waiting", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(99.dp)).background(C.warn).padding(horizontal = 5.dp))
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall, color = if (on) hu.fg else C.muted, modifier = Modifier.padding(top = 3.dp))
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
