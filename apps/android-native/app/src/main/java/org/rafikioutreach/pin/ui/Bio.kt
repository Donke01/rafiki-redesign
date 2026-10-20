package org.rafikioutreach.pin.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.withStyle
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.t

/** The phone's fingerprint / face check (with the phone's own screen lock as a fallback). */
object Bio {
    private const val KINDS = BIOMETRIC_STRONG or BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    /** Lets tests stand in for the phone. */
    var available: (Context) -> Boolean = { ctx -> BiometricManager.from(ctx).canAuthenticate(KINDS) == BiometricManager.BIOMETRIC_SUCCESS }
    var ask: (Context, String, (Boolean) -> Unit) -> Unit = { ctx, title, done ->
        val act = ctx.activity() ?: run { done(false); null }
        if (act != null) {
        val p = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = done(true)
            override fun onAuthenticationError(code: Int, msg: CharSequence) = done(false)
        })
        p.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(t("Use your fingerprint, face or phone lock", "Tumia alama ya kidole, uso au kufuli ya simu")).setAllowedAuthenticators(KINDS).build())
        }
    }
}

internal fun Context.activity(): FragmentActivity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) { if (c is FragmentActivity) return c; c = c.baseContext }
    return null
}

/** Shown instead of the app while it waits for the fingerprint (design: Figma 06 / 07). */
@Composable
fun BioLockScreen(m: AppModel) {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var usePin by remember { mutableStateOf(false) }
    val h = rememberHaptics()
    fun ask() { Bio.ask(ctx, t("Unlock RAFIKI Pin", "Fungua RAFIKI Pin")) { ok -> if (ok) m.unlocked() else msg = t("Not unlocked. Try again.", "Haijafunguliwa. Jaribu tena.") } }
    LaunchedEffect(Unit) { ask() }
    val name = m.prefs.name
    val first = name.split(" ").firstOrNull().orEmpty()
    val country = mapOf("ke" to "Kenya", "tz" to "Tanzania", "ug" to "Uganda", "mw" to "Malawi")[m.prefs.country]
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 20.dp).testTag("bio_lock"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(androidx.compose.ui.text.buildAnnotatedString {
            append("RAFIKI "); withStyle(androidx.compose.ui.text.SpanStyle(color = C.blue)) { append("Pin") }
        }, style = MaterialTheme.typography.titleMedium.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(3f, androidx.compose.ui.unit.TextUnitType.Sp)), color = C.ink, modifier = Modifier.padding(top = 12.dp))
        if (!usePin) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                FingerEmblem()
                Text(t("WELCOME BACK", "KARIBU TENA"), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp)), color = C.blue, modifier = Modifier.padding(top = 18.dp))
                Text(if (first.isNotBlank()) "Habari, $first" else t("Welcome back", "Karibu tena"), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold), color = C.ink, modifier = Modifier.padding(top = 6.dp))
                Text(t("Touch the sensor or look at your phone to carry on where you left off.", "Gusa kitambuzi au tazama simu yako ili kuendelea ulipoishia."), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp).widthIn(max = 310.dp))
                if (name.isNotBlank()) Row(Modifier.padding(top = 12.dp).clip(RoundedCornerShape(99.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(99.dp)).padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color(0xFF47B4F5), androidx.compose.ui.graphics.Color(0xFF0A78C2)))), contentAlignment = Alignment.Center) {
                        Text(name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(name + (country?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelLarge, color = C.ink)
                }
            }
            Btn(t("Unlock", "Fungua"), { msg = null; ask() }, Modifier.fillMaxWidth().testTag("bio_unlock"), icon = "finger")
            if (m.prefs.pin.isNotBlank()) Btn(t("Use my RAFIKI PIN instead", "Tumia PIN yangu ya RAFIKI"), { usePin = true; msg = null }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("bio_usepin"), light = true)
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.bad, modifier = Modifier.padding(top = 10.dp).testTag("bio_msg")) }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.get("lock"), null, tint = C.muted, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
                Text(t("Your fingerprint never leaves this phone.", "Alama ya kidole chako haitoki kwenye simu hii."), style = MaterialTheme.typography.labelSmall, color = C.muted)
            }
        } else {
            Column(Modifier.padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(t("USE YOUR PIN", "TUMIA PIN YAKO"), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp)), color = C.blue)
                Text(t("Enter your RAFIKI PIN", "Weka PIN yako ya RAFIKI"), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold), color = C.ink, modifier = Modifier.padding(top = 6.dp))
                Text(t("The digits the office gave you.", "Tarakimu ulizopewa na ofisi."), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(top = 4.dp))
                val n = maxOf(4, m.prefs.pin.length)
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    repeat(n) { i -> Box(Modifier.size(16.dp).clip(CircleShape).then(if (i < pin.length) Modifier.background(C.blue) else Modifier.border(2.dp, C.muted.copy(alpha = 0.6f), CircleShape))) }
                }
                msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.bad, modifier = Modifier.padding(top = 10.dp).testTag("bio_msg")) }
                Column(Modifier.padding(top = 22.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("fp", "0", "del")).forEach { r ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            r.forEach { k ->
                                val mod = Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(22.dp))
                                when (k) {
                                    "fp" -> Column(mod.clickable { h.tap(); usePin = false; pin = ""; ask() }.testTag("bio_key_fp"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Icon(Ic.get("finger"), null, tint = C.blue, modifier = Modifier.size(26.dp))
                                        Text(t("Fingerprint", "Alama ya kidole"), style = MaterialTheme.typography.labelSmall, color = C.blue)
                                    }
                                    "del" -> Box(mod.clickable { h.tap(); pin = pin.dropLast(1) }.semantics { contentDescription = t("Delete last digit", "Futa tarakimu ya mwisho") }, contentAlignment = Alignment.Center) { Icon(Ic.get("del"), null, tint = C.ink, modifier = Modifier.size(28.dp)) }
                                    else -> Box(mod.background(C.card).clickable {
                                        h.tap(); msg = null
                                        if (pin.length < n) pin += k
                                        if (pin == m.prefs.pin) m.unlocked() else if (pin.length >= n) { msg = t("That PIN isn’t right.", "PIN hiyo si sahihi."); pin = "" }
                                    }.testTag("bio_key$k"), contentAlignment = Alignment.Center) { Text(k, style = MaterialTheme.typography.headlineSmall, color = C.ink) }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }
        Text(t("Sign out", "Toka"), style = MaterialTheme.typography.labelLarge, color = C.blue, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(10.dp)).clickable { m.lock() }.padding(8.dp).testTag("bio_signout"))
    }
}

/** The fingerprint inside soft rings, with two arcs slowly turning around it. */
@Composable
private fun FingerEmblem() {
    val turn = if (Motion.on && Motion.loops) androidx.compose.animation.core.rememberInfiniteTransition(label = "fp").animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(3200, easing = androidx.compose.animation.core.LinearEasing)), label = "turn").value else 0f
    val blue = C.blue
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(220.dp).clip(CircleShape).background(blue.copy(alpha = 0.07f)))
        Box(Modifier.size(168.dp).clip(CircleShape).background(blue.copy(alpha = 0.12f)))
        androidx.compose.foundation.Canvas(Modifier.size(140.dp)) {
            val st = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawArc(blue.copy(alpha = 0.7f), turn - 126f, 74f, false, style = st)
            drawArc(blue.copy(alpha = 0.3f), turn + 54f, 74f, false, style = st)
        }
        Box(Modifier.size(116.dp).shadow(10.dp, CircleShape).clip(CircleShape).background(C.card), contentAlignment = Alignment.Center) {
            Icon(Ic.get("finger"), null, tint = blue, modifier = Modifier.size(56.dp))
        }
    }
}

/** Me › Unlock with fingerprint or face (design: Figma 08). */
@Composable
fun BioCard(m: AppModel) {
    val ctx = LocalContext.current
    val can = remember { Bio.available(ctx) }
    var on by remember { mutableStateOf(m.prefs.bio) }
    var msg by remember { mutableStateOf<String?>(null) }
    Card(Modifier.padding(top = 14.dp).testTag("bio_card")) {
        Row(verticalAlignment = Alignment.Top) {
            val hu = C.hue(if (on) "near" else "pin"); IconTile("finger", hu.fg, hu.bg, 44.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Unlock with fingerprint or face", "Fungua kwa alama ya kidole au uso"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f, fill = false))
                    if (on) { Spacer(Modifier.width(8.dp)); Tag(t("On", "Imewashwa"), C.okBg, C.ok) }
                }
                Text(if (!can && !on) t("Set up a fingerprint, face or screen lock in your phone’s settings first.", "Weka kwanza alama ya kidole, uso au kufuli ya skrini kwenye mipangilio ya simu.")
                    else if (on) t("Asked when the app opens and after a minute away. Lock the app now keeps your PIN, so you’re back in with a touch.", "Inaulizwa programu inapofunguliwa na baada ya dakika moja nje. Kufunga programu sasa kunahifadhi PIN yako, hivyo unarudi kwa kugusa tu.")
                    else t("Keeps the app private if someone else picks up your phone. Uses your fingerprint, face or the phone’s screen lock.", "Inaweka programu faragha mtu mwingine akishika simu yako. Inatumia alama ya kidole, uso au kufuli ya skrini ya simu."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (on) Btn(t("Turn off", "Zima"), { on = false; m.prefs.bio = false }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("bio_switch"), light = true)
        else if (can) Btn(t("Turn on", "Washa"), {
            Bio.ask(ctx, t("Turn on fingerprint unlock", "Washa kufungua kwa alama ya kidole")) { ok -> if (ok) { on = true; m.prefs.bio = true; msg = null } else msg = t("Not turned on.", "Haijawashwa.") }
        }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("bio_switch"), icon = "finger")
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.bad, modifier = Modifier.padding(top = 6.dp)) }
    }
}
