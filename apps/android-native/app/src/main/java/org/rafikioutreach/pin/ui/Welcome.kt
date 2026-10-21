package org.rafikioutreach.pin.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.Lang
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.t

/** Welcome screens. Tests switch them off. */
object Onb { var enabled = true }

private val Warm = Color(0xFFFBFAF7)
private val FLAGS = mapOf("ke" to "🇰🇪", "tz" to "🇹🇿", "ug" to "🇺🇬", "mw" to "🇲🇼")

@Composable
private fun OnbFrame(back: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val dark = C.dark
    Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF0D1318) else Warm)) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(Color(0x406EC3FF), Color.Transparent), Offset(size.width * .2f, size.height * .1f), size.width * .7f), size.width * .7f, Offset(size.width * .2f, size.height * .1f))
            drawCircle(Brush.radialGradient(listOf(Color(0x3878E8D2), Color.Transparent), Offset(size.width * .9f, size.height * .85f), size.width * .65f), size.width * .65f, Offset(size.width * .9f, size.height * .85f))
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 10.dp)) {
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                if (back != null) Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = back).testTag("onb_back"), contentAlignment = Alignment.Center) {
                    Icon(Ic.get("back"), t("Back", "Rudi"), tint = if (dark) Color.White else Ink, modifier = Modifier.size(22.dp))
                }
                Wordmark(Modifier.align(Alignment.Center), if (dark) Color.White else Ink)
            }
            content()
        }
    }
}

/** The RAFIKI logo and "Pin": the black logo on light screens, the white one on dark. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, color: Color = C.ink, height: androidx.compose.ui.unit.Dp = 24.dp) {
    val light = color.luminance() > 0.5f
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "RAFIKI Pin" }.testTag("wordmark"), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(if (light) org.rafikioutreach.pin.R.drawable.rafiki_logo_light else org.rafikioutreach.pin.R.drawable.rafiki_logo_dark), null,
            modifier = Modifier.height(height), contentScale = androidx.compose.ui.layout.ContentScale.FillHeight)
        Spacer(Modifier.width(height / 3))
        Text("Pin", color = if (light) Color(0xFF5BB4F2) else Color(0xFF0A78C2), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = (height.value * 0.72f).sp))
    }
}

@Composable
private fun BigTitle(text: String) = Text(text, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp, lineHeight = 35.sp, letterSpacing = (-1).sp), color = if (C.dark) Color.White else Color(0xFF141618), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp))

@Composable
private fun Sub(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = if (C.dark) Color(0xFF9CA8B2) else Color(0xFF5A6066), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp))

@Composable
private fun Go(text: String, enabled: Boolean = true, tag: String = "onb_go", onClick: () -> Unit) {
    val dark = C.dark
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (enabled) Modifier.shadow(12.dp, RoundedCornerShape(999.dp), spotColor = Color(0x550C1924)) else Modifier)
            .clip(RoundedCornerShape(999.dp)).background(if (dark) Brush.verticalGradient(listOf(Color.White, Color.White)) else InkBrush)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = (if (dark) Ink else Color.White).copy(alpha = if (enabled) 1f else .4f), style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp)) }
}

@Composable
private fun Cap(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF7A8086), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

@Composable
private fun Link(text: String, tag: String, onClick: () -> Unit) = Text(text, style = MaterialTheme.typography.labelLarge, color = if (C.dark) Color.White else Ink,
    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(10.dp).testTag(tag))

/** Sticky notes with real project names, around the welcome. */
@Composable
private fun BoxScope.Notes(names: List<String>) {
    val n = (names + listOf("Kiptoo Well", "Turbo Spring", "Chepkongi Dispensary", "Kapsaret Borehole")).take(4)
    val notes = listOf(
        Triple(Alignment.TopStart, -7f, Triple(Color(0xFFFFF7C2), Color(0xFF4A3B00), t("PINNED", "IMEWEKWA"))),
        Triple(Alignment.TopEnd, 6f, Triple(Color(0xFFDDEFFF), Color(0xFF0B3A5E), t("VISIT", "ZIARA"))),
        Triple(Alignment.BottomStart, 5f, Triple(Color(0xFFDDF6E8), Color(0xFF0B4D2C), t("WORKING", "INAFANYA KAZI"))),
        Triple(Alignment.BottomEnd, -6f, Triple(Color(0xFFFFE3DC), Color(0xFF7A1E12), t("REPAIR", "MATENGENEZO"))),
    )
    notes.forEachIndexed { i, (al, rot, c) ->
        val a = remember { Animatable(0f) }
        LaunchedEffect(Unit) { delay(100L * i); a.animateTo(1f, tween(500)) }
        Column(
            Modifier.align(al).padding(top = if (i < 2) 40.dp + (i * 30).dp else 0.dp, bottom = if (i >= 2) 120.dp + ((3 - i) * 30).dp else 0.dp)
                .graphicsAlpha(a.value).rotate(rot).width(118.dp).shadow(8.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)).background(c.first).padding(10.dp),
        ) {
            Text(c.third, color = c.second.copy(alpha = .6f), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            Text(n[i], color = c.second, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 15.sp)
        }
    }
}

private fun Modifier.graphicsAlpha(a: Float) = this.graphicsLayer { alpha = a; translationY = (1f - a) * 30f }

@Composable
private fun Hero() {
    val dark = C.dark
    Text(buildAnnotatedString {
        append(t("Every water point.", "Kila kituo cha maji.") + "\n" + t("One map.", "Ramani moja.") + "\n")
        withStyle(SpanStyle(color = Color(0xFF8A9096))) { append(t("Right in your pocket.", "Mfukoni mwako.")) }
    }, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold), color = if (dark) Color.White else Color(0xFF141618), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

/** Before signing in ("hello") and, for a moment, every time the app opens ("splash"). */
@Composable
fun Welcome(m: AppModel, splash: Boolean, onSignIn: () -> Unit) {
    val names = m.snap.collectAsState().value?.projects?.map { it.title } ?: emptyList()
    if (splash) LaunchedEffect(Unit) { delay(1600); onSignIn() }
    OnbFrame(null) {
        Box(Modifier.weight(1f).fillMaxWidth().then(if (splash) Modifier.clickable(onClick = onSignIn) else Modifier)) {
            Notes(names)
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Hero(); Spacer(Modifier.height(22.dp)); Drop(130.dp, DropC.Blue, Mood.Smile)
            }
        }
        val first = m.prefs.name.substringBefore(' ')
        if (splash) Cap(if (first.isNotBlank()) "Habari $first, karibu tena." else "Habari, karibu tena.")
        else {
            Box(Modifier.fillMaxWidth().padding(bottom = 14.dp), contentAlignment = Alignment.Center) { LangSwitch(m.prefs) }
            Go(t("Sign in", "Ingia"), tag = "onb_signin", onClick = onSignIn)
            Cap(if (first.isNotBlank()) "Habari $first, karibu tena." else t("New here? Ask your team for a PIN.", "Mgeni hapa? Omba PIN kwa timu yako."))
        }
    }
}

/** The first time on a phone: what you do, language, country, location, and a first stamp. */
@Composable
fun Onboarding(m: AppModel, done: (String) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var role by remember { mutableStateOf("") }
    var ck by remember { mutableStateOf(m.prefs.country) }
    val admin = m.snap.collectAsState().value?.admin == true
    val names = m.snap.collectAsState().value?.projects?.map { it.title } ?: emptyList()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.values.any { ok -> ok }) m.locator.start(); step = 5 }
    val back: (() -> Unit)? = if (step > 0) ({ step -= 1 }) else null
    OnbFrame(back) {
        when (step) {
            0 -> {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Notes(names)
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Hero(); Spacer(Modifier.height(22.dp)); Drop(130.dp, DropC.Blue, Mood.Smile) }
                }
                Go(t("Get started", "Anza")) { step = 1 }
                Cap(t("Takes 30 seconds", "Inachukua sekunde 30"))
            }
            1 -> {
                BigTitle(t("What will you do most?", "Utafanya nini zaidi?"))
                Sub(t("We’ll open the app on it. You can change this anytime.", "Tutafungua programu hapo. Unaweza kubadilisha wakati wowote."))
                val opts = listOf(
                    Triple("pin", t("Pin locations", "Weka mahali") to t("Put every project on the map", "Weka kila mradi kwenye ramani"), DropC.Blue to Mood.Smile),
                    Triple("visit", t("Site visits", "Ziara") to t("Check water points and report", "Kagua vituo vya maji na uripoti"), DropC.Green to Mood.O),
                    Triple("repairs", t("Repairs", "Ukarabati") to t("Follow what needs fixing", "Fuatilia kinachohitaji kurekebishwa"), DropC.Coral to Mood.Wink),
                ) + if (admin) listOf(Triple("office", t("Office work", "Kazi za ofisi") to t("Review, assign and report", "Idhinisha, gawa na ripoti"), DropC.Purple to Mood.Smile)) else emptyList()
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    opts.forEach { (k, l, d) -> Opt(role == k, "onb_r_$k", { role = k }) { Column(Modifier.weight(1f)) { Text(l.first, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (role == k) Color.White else Color.Unspecified); Text(l.second, fontSize = 12.sp, color = (if (role == k) Color.White else Color.Unspecified).copy(alpha = .75f)) }; Drop(40.dp, d.first, d.second) } }
                }
                Spacer(Modifier.weight(1f))
                Go(t("Continue", "Endelea"), enabled = role.isNotBlank()) {
                    m.prefs.filter = when (role) { "pin" -> "missing"; "visit" -> "near"; "repairs" -> "repairs"; else -> m.prefs.filter }
                    step = 2
                }
                Cap(t("Pick one", "Chagua kimoja"))
            }
            2 -> {
                BigTitle(t("Which language?", "Lugha gani?"))
                val pager = rememberPagerState(initialPage = if (Lang.sw) 1 else 0) { 2 }
                val scope = rememberCoroutineScope()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    HorizontalPager(pager, contentPadding = PaddingValues(horizontal = 48.dp), pageSpacing = 14.dp, modifier = Modifier.fillMaxWidth().testTag("onb_pager"),
                        flingBehavior = PagerDefaults.flingBehavior(pager, PagerSnapDistance.atMost(1))) { pg ->
                        val sw = pg == 1
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(if (sw) Color(0xFFDDF6E8) else Color(0xFFE3F1FC)).clickable { scope.launch { pager.animateScrollToPage(pg) } }.padding(horizontal = 18.dp, vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Drop(104.dp, if (sw) DropC.Green else DropC.Blue, if (sw) Mood.Smile else Mood.O)
                            Text(if (sw) "KISWAHILI" else "ENGLISH", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = if (sw) Color(0xFF0B4D2C) else Color(0xFF0B3A5E),
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).padding(horizontal = 14.dp, vertical = 6.dp))
                            Text(if (sw) "Habari! Tutafute vituo vya maji vya leo." else "Hello! Let’s find today’s water points.", fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp, textAlign = TextAlign.Center, color = if (sw) Color(0xFF0B4D2C) else Color(0xFF0B3A5E))
                        }
                    }
                    Text(if (pager.currentPage == 1) "Kiswahili" else "English", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, textAlign = TextAlign.Center, color = if (C.dark) Color.White else Color(0xFF141618), modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("onb_lang"))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                        repeat(2) { i -> Box(Modifier.padding(horizontal = 3.dp).size(if (pager.currentPage == i) 18.dp else 6.dp, 6.dp).clip(RoundedCornerShape(3.dp)).background(if (pager.currentPage == i) Color(0xFF0A78C2) else Color(0xFFC9CDD1))) }
                    }
                }
                Go(t("That’s my language", "Hiyo ndiyo lugha yangu")) { Lang.sw = pager.currentPage == 1; m.prefs.lang = if (Lang.sw) "sw" else "en"; step = 3 }
                Cap(t("Swipe to see both. Switch anytime in Me.", "Telezesha kuona zote. Badilisha wakati wowote kwenye Mimi."))
            }
            3 -> {
                BigTitle(t("Where do you work?", "Unafanya kazi wapi?"))
                Sub(t("Lists and the map start there. You still see every country.", "Orodha na ramani zinaanzia hapo. Bado unaona nchi zote."))
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    COUNTRIES.forEach { (k, n) -> Opt(ck == k, "onb_c_$k", { ck = k }) { Text(n, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f), color = if (ck == k) Color.White else Color.Unspecified); Text(FLAGS[k] ?: "", fontSize = 24.sp) } }
                }
                Spacer(Modifier.weight(1f))
                Go(t("Continue", "Endelea"), enabled = ck.isNotBlank()) { m.prefs.country = ck; step = 4 }
                Link(t("Skip", "Ruka"), "onb_skip") { step = 4 }
            }
            4 -> {
                BigTitle(t("Water points near you", "Vituo vya maji karibu nawe"))
                Sub(t("RAFIKI Pin uses your location only while it’s open, to show the closest projects and save exact points.", "RAFIKI Pin inatumia mahali ulipo tu ikiwa wazi, kuonyesha miradi iliyo karibu na kuhifadhi mahali kamili."))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Drop(130.dp, DropC.Amber, Mood.Smile) }
                Go(t("Allow location", "Ruhusu mahali"), tag = "onb_loc") { runCatching { ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }.onFailure { step = 5 } }
                Link(t("Not now", "Si sasa"), "onb_notnow") { step = 5 }
            }
            else -> {
                BigTitle(t("A good start", "Mwanzo mzuri"))
                Sub(t("You’re all set, ", "Uko tayari, ") + m.prefs.name.substringBefore(' ') + ".")
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Stamp(210.dp, DropC.Blue, "RAFIKI · FIRST DROP", "001", true, Mood.Smile)
                    Text(t("Your first stamp", "Stempu yako ya kwanza"), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = if (C.dark) Color.White else Color(0xFF141618), modifier = Modifier.padding(top = 22.dp))
                    Sub(t("Every point you pin and every visit you report adds to your collection.", "Kila mahali unapoweka na kila ziara unayoripoti inaongeza kwenye mkusanyiko wako."))
                }
                Go(t("Open RAFIKI Pin", "Fungua RAFIKI Pin"), tag = "onb_done") { m.prefs.onb = true; done(role) }
                Cap(t("Small steps, clean water.", "Hatua ndogo, maji safi."))
            }
        }
    }
}

@Composable
private fun Opt(on: Boolean, tag: String, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val dark = C.dark
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).then(if (on) Modifier.shadow(10.dp, RoundedCornerShape(18.dp), spotColor = Color(0x660A78C2)) else Modifier)
            .clip(RoundedCornerShape(18.dp)).background(if (on) Color(0xFF0A78C2) else if (dark) Color(0xFF18222B) else Color(0xFFF1F1EE))
            .clickable(role = Role.RadioButton, onClick = onClick).padding(start = 18.dp, end = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides if (on) Color.White else if (dark) Color.White else Color(0xFF141618)) { content() } }
}

/** A postage stamp with a drop on it: earned ones in colour, the rest grey. */
@Composable
fun Stamp(width: Dp, color: DropC, top: String, num: String, got: Boolean, mood: Mood = Mood.Smile, rot: Float = -2f) {
    val inner = if (!got) Brush.linearGradient(listOf(Color(0xFFE9EEF2), Color(0xFFE9EEF2))) else Brush.linearGradient(listOf(color.mid, color.lo))
    Box(Modifier.rotate(rot).width(width).aspectRatio(.82f).shadow(10.dp, RoundedCornerShape(4.dp), spotColor = Color(0x33000000))) {
        Canvas(Modifier.matchParentSize()) {
            // perforated white edge
            drawRect(Color.White)
            val r = size.width * .028f; val step = r * 3f
            var x = step / 2; while (x < size.width) { drawCircle(Color(0xFFE6E9EC), r, Offset(x, 0f)); drawCircle(Color(0xFFE6E9EC), r, Offset(x, size.height)); x += step }
            var y = step / 2; while (y < size.height) { drawCircle(Color(0xFFE6E9EC), r, Offset(0f, y)); drawCircle(Color(0xFFE6E9EC), r, Offset(size.width, y)); y += step }
        }
        Column(Modifier.matchParentSize().padding(width * .065f).clip(RoundedCornerShape(2.dp)).background(inner).padding(width * .05f)) {
            if (top.isNotBlank()) Text(top, color = Color.White.copy(alpha = if (got) .9f else 0f), fontSize = (width.value * .045f).sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, maxLines = 1)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.graphicsAlphaPlain(if (got) 1f else .4f)) { Drop(width * .48f, if (got) color else DropC.Grey, mood, still0 = !got) }
            }
            Text(num, color = if (got) Color.White else Color(0xFF9AA4AC), fontSize = (width.value * .05f).coerceAtLeast(8f).sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.End))
        }
    }
}
private fun Modifier.graphicsAlphaPlain(a: Float) = this.graphicsLayer { alpha = a }
