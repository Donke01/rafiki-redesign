package org.rafikioutreach.pin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.BadgeMemory
import org.rafikioutreach.pin.data.BadgeState
import org.rafikioutreach.pin.data.Badges
import org.rafikioutreach.pin.t
import kotlin.math.cos
import kotlin.math.sin

fun badgeDrop(c: String) = when (c) { "g" -> DropC.Green; "c" -> DropC.Coral; "y" -> DropC.Amber; "p" -> DropC.Purple; else -> DropC.Blue }

/** Celebrations move unless the app's motion is off or the phone asks for less motion (animations off). */
@Composable
fun badgeMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { Motion.on && runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f }
}

/**
 * Me › Your stamps: badges for real work (first pin, 10 visits this month, all 4 countries, a 5-day streak…).
 * Locked ones are grey with how far along you are; a new one lands with a burst of confetti and a note at the top.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BadgeStamps(m: AppModel) {
    val act by m.activity.collectAsState()
    val snap by m.snap.collectAsState()
    LaunchedEffect(Unit) { m.loadActivity() }
    val ctx = LocalContext.current
    val mem = remember { BadgeMemory(ctx) }
    val who = m.prefs.name
    val ck = remember(snap) { snap?.projects?.associate { it.id to it.ck }.orEmpty() }
    val states = remember(act, ck, who, org.rafikioutreach.pin.Lang.sw) { Badges.compute(act.orEmpty(), who, { ck[it] }) }
    // new since this phone last looked: celebrate once (the very first look only remembers)
    var fresh by remember { mutableStateOf(emptyList<BadgeState>()) }
    var toast by remember { mutableStateOf(false) }
    LaunchedEffect(act, who) {
        if (act == null || who.isBlank()) return@LaunchedEffect
        val seen = mem.seen(who)
        val got = states.filter { it.got }.map { it.def.k }.toSet()
        val f = Badges.fresh(states, seen)
        mem.save(who, (seen ?: emptySet()) + got)
        if (f.isNotEmpty()) { fresh = f; toast = true }
    }
    val moving = badgeMotion()
    val here = remember { BringIntoViewRequester() }
    val scope = rememberScope()
    val earned = states.count { it.got }
    Card(Modifier.padding(top = 14.dp).bringIntoViewRequester(here).testTag("stamps")) {
        H2(t("Your stamps", "Stempu zako"))
        Text(t("Every point you pin and every visit you report adds to your collection.", "Kila mahali unapoweka na kila ziara unayoripoti inaongeza kwenye mkusanyiko wako."), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        // how much of the album is filled
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(99.dp)).background(C.line)) {
                Box(Modifier.fillMaxWidth(rememberFill(earned.toFloat() / states.size)).height(8.dp).clip(RoundedCornerShape(99.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF2FB872), Color(0xFF3EA7EE), Color(0xFF8C6CF2)))))
            }
            Text(t("$earned of ${states.size}", "$earned kati ya ${states.size}"), style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.padding(start = 10.dp).testTag("stamps_count"))
        }
        states.chunked(3).forEachIndexed { r, row ->
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { c, st ->
                    val k = r * 3 + c; val got = st.got
                    val party = got && fresh.any { it.def.k == st.def.k }
                    Column(Modifier.weight(1f).testTag(if (got) "stamp_got" else "stamp_no"), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(if (party && moving) Modifier.confetti(k) else Modifier, contentAlignment = Alignment.Center) {
                            Box(if (party) Modifier.celebrateIn(moving, k).testTag("stamp_new") else Modifier.stampIn(k, got)) {
                                Stamp(84.dp, badgeDrop(st.def.color), "", "%03d".format(k + 1), got, listOf(Mood.Smile, Mood.Wink, Mood.O)[k % 3], rot = if (k % 2 == 0) -2f else 2f)
                            }
                        }
                        Text(st.def.title, style = MaterialTheme.typography.labelMedium, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                        Text(st.progress, style = MaterialTheme.typography.bodySmall, color = if (got) C.ok else C.muted, textAlign = TextAlign.Center, modifier = Modifier.testTag("badge_" + st.def.k))
                        if (!got && st.def.goal > 1 && st.n > 0) Box(Modifier.padding(top = 4.dp).width(56.dp).height(4.dp).clip(RoundedCornerShape(99.dp)).background(C.line)) {
                            Box(Modifier.fillMaxWidth(st.fraction).height(4.dp).clip(RoundedCornerShape(99.dp)).background(badgeDrop(st.def.color).mid))
                        }
                    }
                }
            }
        }
        Text(t("Show the welcome screens again", "Onyesha skrini za kukaribisha tena"), style = MaterialTheme.typography.labelLarge, color = C.blue2,
            modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(10.dp)).clickable { m.replayOnb = true }.padding(vertical = 10.dp).testTag("replay"))
    }
    if (toast && fresh.isNotEmpty()) BadgeToast(fresh, moving, { toast = false }) { toast = false; scope.launch { here.bringIntoView() } }
}

/** "New badge!" at the top of the screen, like a notification; goes away by itself. */
@Composable
fun BadgeToast(fresh: List<BadgeState>, moving: Boolean, gone: () -> Unit, open: () -> Unit) {
    val hp = rememberHaptics()
    val a = remember { Animatable(if (moving) 0f else 1f) }
    LaunchedEffect(Unit) {
        hp.ok()
        if (moving) a.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 320f))
        delay(5200)
        if (moving) a.animateTo(0f, tween(260))
        gone()
    }
    val top = with(LocalDensity.current) { 64.dp.roundToPx() }
    Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, top)) {
        val first = fresh.first()
        Row(Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp).fillMaxWidth()
            .graphicsLayer { val v = a.value; alpha = v.coerceIn(0f, 1f); translationY = (1f - v) * -40.dp.toPx(); scaleX = .92f + .08f * v; scaleY = scaleX }
            .shadow(18.dp, RoundedCornerShape(22.dp), spotColor = Color(0x55144678)).clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1A8BD6), Color(0xFF054F81), Color(0xFF04263F))))
            .clickable(onClick = open).padding(horizontal = 14.dp, vertical = 12.dp).testTag("badge_toast")
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x26FFFFFF)), contentAlignment = Alignment.Center) {
                Drop(28.dp, badgeDrop(first.def.color), Mood.Smile, still0 = !moving)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (fresh.size == 1) t("New stamp earned!", "Umepata stempu mpya!") else t("${fresh.size} new stamps earned!", "Umepata stempu ${fresh.size} mpya!"),
                    color = Color(0xFFFFD66B), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .4.sp)
                Text(fresh.joinToString(" · ") { it.def.title }, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                if (fresh.size == 1) Text(first.def.how, color = Color(0xFFCFE8FA), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

/** A new stamp lands: from nothing to a little too big, a wiggle, then it settles. */
fun Modifier.celebrateIn(moving: Boolean, index: Int): Modifier = composed {
    val p = remember { Animatable(if (moving) 0f else 1f) }
    LaunchedEffect(Unit) { if (p.value < 1f) { delay(350L + (index % 3) * 60L); p.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 210f)) } }
    graphicsLayer {
        val v = p.value
        alpha = v.coerceIn(0f, 1f)
        val s = 0.2f + 0.8f * v; scaleX = s; scaleY = s
        rotationZ = (1f - v) * 22f
    }
}

private val CONFETTI = listOf(Color(0xFF3EA7EE), Color(0xFF2FB872), Color(0xFFF26A4F), Color(0xFFF2B13A), Color(0xFF8C6CF2), Color(0xFF5CD3D6))

/** A burst of confetti around a new stamp, drawn past its edges without moving anything (once; not with less motion). */
fun Modifier.confetti(seed: Int): Modifier = composed {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(380L + (seed % 3) * 60L); p.animateTo(1f, tween(1500, easing = LinearEasing)) }
    val bits = remember(seed) {
        val r = java.util.Random(seed * 31L + 7)
        List(22) { i -> floatArrayOf((i * (360f / 22) + r.nextFloat() * 14f), 0.55f + r.nextFloat() * 0.45f, r.nextFloat() * 360f, (i % CONFETTI.size).toFloat(), 0.6f + r.nextFloat() * 0.6f) }
    }
    testTag("confetti").drawWithContent {
        drawContent()
        val v = p.value; if (v <= 0f || v >= 1f) return@drawWithContent
        val out = FastOutSlowInEasing.transform((v * 1.6f).coerceAtMost(1f))
        val c = Offset(size.width / 2, size.height / 2); val reach = 95.dp.toPx()
        bits.forEach { b ->
            val ang = Math.toRadians(b[0].toDouble())
            val d = reach * b[1] * out
            val x = c.x + (cos(ang) * d).toFloat(); val y = c.y + (sin(ang) * d).toFloat() + v * v * reach * 0.45f
            val w = 2.7.dp.toPx() * b[4]; val h = 4.2.dp.toPx() * b[4]
            rotate(b[2] + v * 540f, Offset(x, y)) {
                drawRect(CONFETTI[b[3].toInt()].copy(alpha = (1f - v * v).coerceIn(0f, 1f)), Offset(x - w / 2, y - h / 2), Size(w, h))
            }
        }
    }
}
