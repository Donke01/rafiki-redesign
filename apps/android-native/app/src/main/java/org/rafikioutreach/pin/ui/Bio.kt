package org.rafikioutreach.pin.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

/** Shown instead of the app while it waits for the fingerprint. */
@Composable
fun BioLockScreen(m: AppModel) {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var usePin by remember { mutableStateOf(false) }
    fun ask() { Bio.ask(ctx, t("Unlock RAFIKI Pin", "Fungua RAFIKI Pin")) { ok -> if (ok) m.unlocked() else msg = t("Not unlocked. Try again.", "Haijafunguliwa. Jaribu tena.") } }
    LaunchedEffect(Unit) { ask() }
    Column(Modifier.fillMaxSize().padding(28.dp).testTag("bio_lock"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(C.tint), contentAlignment = Alignment.Center) { Icon(Ic.get("finger"), null, tint = C.blue, modifier = Modifier.size(52.dp)) }
        Text(t("RAFIKI Pin is locked", "RAFIKI Pin imefungwa"), style = MaterialTheme.typography.headlineSmall, color = C.ink, modifier = Modifier.padding(top = 20.dp))
        Text(m.prefs.name.ifBlank { " " }, style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(top = 4.dp))
        Btn(t("Unlock", "Fungua"), { msg = null; ask() }, Modifier.padding(top = 24.dp).fillMaxWidth().testTag("bio_unlock"), icon = "finger")
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.bad, modifier = Modifier.padding(top = 10.dp)) }
        if (m.prefs.pin.isNotBlank()) {
            if (!usePin) Text(t("Use my RAFIKI PIN instead", "Tumia PIN yangu ya RAFIKI"), style = MaterialTheme.typography.labelLarge, color = C.blue,
                modifier = Modifier.padding(top = 16.dp).clip(RoundedCornerShape(10.dp)).clickable { usePin = true }.padding(8.dp).testTag("bio_usepin"))
            else {
                androidx.compose.material3.OutlinedTextField(pin, { v -> pin = v.filter { it.isDigit() }.take(8); if (pin == m.prefs.pin) m.unlocked() }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("bio_pin"),
                    singleLine = true, label = { Text(t("Your PIN", "PIN yako")) }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
            }
        }
        Text(t("Sign out", "Toka"), style = MaterialTheme.typography.labelLarge, color = C.muted, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(10.dp)).clickable { m.lock() }.padding(8.dp).testTag("bio_signout"))
    }
}

/** Me › Unlock with fingerprint or face. */
@Composable
fun BioCard(m: AppModel) {
    val ctx = LocalContext.current
    val can = remember { Bio.available(ctx) }
    var on by remember { mutableStateOf(m.prefs.bio) }
    var msg by remember { mutableStateOf<String?>(null) }
    Card(Modifier.padding(top = 14.dp).testTag("bio_card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val hu = C.hue("pin"); IconTile("finger", hu.fg, hu.bg, 40.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Unlock with fingerprint or face", "Fungua kwa alama ya kidole au uso"), style = MaterialTheme.typography.titleSmall, color = C.ink)
                Text(if (!can) t("Set up a fingerprint, face or screen lock in your phone’s settings first.", "Weka kwanza alama ya kidole, uso au kufuli ya skrini kwenye mipangilio ya simu.")
                    else if (on) t("Asked when the app opens and after a minute away.", "Inaulizwa programu inapofunguliwa na baada ya dakika moja nje.")
                    else t("Keeps the app private if someone else picks up your phone.", "Inaweka programu faragha mtu mwingine akishika simu yako."), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
            androidx.compose.material3.Switch(on, { want ->
                if (!want) { on = false; m.prefs.bio = false; return@Switch }
                Bio.ask(ctx, t("Turn on fingerprint unlock", "Washa kufungua kwa alama ya kidole")) { ok -> if (ok) { on = true; m.prefs.bio = true; msg = null } else msg = t("Not turned on.", "Haijawashwa.") }
            }, enabled = can || on, modifier = Modifier.testTag("bio_switch"))
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.bad, modifier = Modifier.padding(top = 6.dp)) }
    }
}
