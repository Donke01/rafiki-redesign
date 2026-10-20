package org.rafikioutreach.pin.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.t

/** Sign-in: a big number pad. Four digits unlock by themselves. */
@Composable
fun LockScreen(m: AppModel) {
    val scope = rememberScope()
    val view = LocalView.current
    var pin by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(m.prefs.name) }
    var longPin by remember { mutableStateOf(false) }
    var needName by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf(m.banner.value) }
    var working by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val banner by m.banner.collectAsState()
    var monday by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { monday = runCatching { m.mondayAvailable() }.getOrDefault(false) }

    fun go() {
        if (working || pin.isEmpty()) return
        working = true; msg = t("Checking…", "Inakagua…")
        scope.launch {
            val err = m.signIn(pin, if (needName) name else "")
            working = false
            when (err) {
                null -> { msg = null; m.showBanner(null) }
                "NEED_NAME" -> { needName = true; msg = t("This is the shared team PIN, so please add your name too.", "Hii ni PIN ya timu nzima, kwa hiyo tafadhali ongeza jina lako pia.") }
                else -> {
                    msg = err; pin = ""
                    for (x in listOf(-14f, 14f, -10f, 10f, 0f)) shake.animateTo(x, tween(60))
                }
            }
        }
    }
    fun key(d: String) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (d == "del") { pin = pin.dropLast(1); return }
        if (pin.length >= (if (longPin) 8 else 4)) return
        pin += d
        if (!longPin && pin.length == 4 && !needName) go()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Drop(84.dp, DropC.Blue, Mood.Smile)
            Text("RAFIKI Pin", style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.5.sp), color = C.ink, modifier = Modifier.padding(top = 12.dp))
            Text(t("Enter your PIN", "Weka PIN yako"), style = MaterialTheme.typography.headlineMedium, color = C.ink, modifier = Modifier.padding(top = 14.dp))
            Text(if (m.prefs.name.isNotBlank()) t("Welcome back, ${m.prefs.name.substringBefore(' ')}.", "Habari ${m.prefs.name.substringBefore(' ')}, karibu tena.") else t("Welcome back.", "Habari, karibu tena."), style = MaterialTheme.typography.bodyMedium, color = C.muted)

            val slots = if (longPin) maxOf(6, pin.length) else 4
            Row(
                Modifier.padding(top = 26.dp, bottom = 6.dp).offset(x = shake.value.dp).semantics { contentDescription = t("${pin.length} of $slots digits entered", "Tarakimu ${pin.length} kati ya $slots") }.testTag("dots"),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                repeat(slots) { i ->
                    Box(Modifier.size(18.dp).clip(CircleShape).then(if (i < pin.length) Modifier.background(C.blue) else Modifier.border(2.dp, C.off, CircleShape)))
                }
            }
            Msg(msg, if (msg == t("Checking…", "Inakagua…")) "" else "bad")
            if (msg == null && banner != null) Msg(banner, if (banner == t("Signing in…", "Inaingia…")) "" else "bad")
            if (needName) Field(name, { name = it }, t("Your name", "Jina lako"), placeholder = t("e.g. Meril", "mf. Meril"))
            Spacer(Modifier.height(14.dp))

            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "del"))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        r.forEach { d ->
                            val mod = Modifier.weight(1f).height(72.dp)
                            when (d) {
                                "" -> if (monday) Column(
                                    mod.clip(RoundedCornerShape(22.dp)).clickable(role = Role.Button) {
                                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(m.mondayStartUrl()))) }
                                    }.testTag("monday"),
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                                ) {
                                    NetImage("https://www.google.com/s2/favicons?domain=monday.com&sz=64", Modifier.size(26.dp).clip(RoundedCornerShape(7.dp))) { Icon(Ic.get("people"), null, tint = C.blue) }
                                    Text(t("monday.com\nsign-in", "Ingia kwa\nmonday.com"), style = MaterialTheme.typography.labelSmall, color = C.blue2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                                } else Spacer(mod)
                                "del" -> Box(mod.clip(RoundedCornerShape(22.dp)).clickable(role = Role.Button) { key("del") }.semantics { contentDescription = t("Delete last digit", "Futa tarakimu ya mwisho") }, contentAlignment = Alignment.Center) {
                                    Icon(Ic.get("del"), null, tint = C.ink, modifier = Modifier.size(30.dp))
                                }
                                else -> Box(
                                    mod.shadow(2.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(C.card)
                                        .then(if (C.dark) Modifier.border(1.dp, C.line, RoundedCornerShape(22.dp)) else Modifier)
                                        .clickable(role = Role.Button) { key(d) }.testTag("key$d"),
                                    contentAlignment = Alignment.Center,
                                ) { Text(d, fontSize = 26.sp, style = MaterialTheme.typography.titleLarge, color = C.ink) }
                            }
                        }
                    }
                }
            }
            Btn(if (working) t("Checking…", "Inakagua…") else t("Unlock", "Fungua"), { go() }, Modifier.fillMaxWidth().padding(top = 16.dp), enabled = pin.isNotEmpty() && !working)
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                TextButton({ longPin = !longPin; pin = "" }) { Text(if (longPin) t("My PIN has 4 digits", "PIN yangu ina tarakimu 4") else t("My PIN is longer", "PIN yangu ni ndefu zaidi"), color = C.blue2, style = MaterialTheme.typography.labelMedium) }
                if (!needName) TextButton({ needName = true }) { Text(t("Shared PIN? Add your name", "PIN ya timu? Ongeza jina lako"), color = C.blue2, style = MaterialTheme.typography.labelMedium) }
            }
            Text(t("Needs signal the first time. After that it opens without it.", "Inahitaji mtandao mara ya kwanza. Baada ya hapo inafunguka bila mtandao."), style = MaterialTheme.typography.bodySmall, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            LangSwitch(m.prefs, Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
fun rememberScope() = rememberCoroutineScope()
