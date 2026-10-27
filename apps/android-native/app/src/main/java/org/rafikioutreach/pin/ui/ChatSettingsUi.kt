package org.rafikioutreach.pin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.QUICK_MAX
import org.rafikioutreach.pin.data.dataText
import org.rafikioutreach.pin.t

/**
 * Chat settings (chatpa): the double-tap reaction, quick replies, Do-Not-Disturb hours (synced with the server, so
 * push follows them too), sounds, and the data saver with what chat used this month.
 */
@Composable
fun ChatSettingsScreen(m: AppModel) {
    val hub = m.chat
    val st = hub.settings
    val tick by hub.settingsTick.collectAsState()
    val ctx = LocalContext.current
    var add by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 110.dp).testTag("chat_settings")) {
        tick.let { }
        Text(t("Chat settings", "Mipangilio ya gumzo"), style = MaterialTheme.typography.headlineMedium, color = C.ink, modifier = Modifier.padding(start = 4.dp))

        SetCard(t("DOUBLE-TAP TO REACT", "GUSA MARA MBILI KUJIBU")) {
            Text(t("Double-tap a message to react with:", "Gusa ujumbe mara mbili kujibu kwa:"), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(horizontal = 8.dp))
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("❤️", "👍").forEach { e ->
                    val on = st.doubleTap == e
                    Box(Modifier.size(56.dp).clip(CircleShape).background(if (on) C.hue("blue").bg else C.card).border(if (on) 3.dp else 1.dp, if (on) C.blue else C.line, CircleShape)
                        .clickable(role = Role.RadioButton) { st.doubleTap = e; hub.saveSettings("doubleTap") }.semantics { contentDescription = e + if (on) t(", chosen", ", imechaguliwa") else "" }.testTag("dt_$e"), contentAlignment = Alignment.Center) {
                        Text(e, fontSize = 26.sp)
                    }
                }
            }
        }

        SetCard(t("QUICK REPLIES", "MAJIBU YA HARAKA")) {
            Text(t("Shown above an empty message box; one tap sends. Up to $QUICK_MAX.", "Huonekana juu ya kisanduku kitupu; mguso mmoja unatuma. Hadi $QUICK_MAX."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(horizontal = 8.dp))
            val list = st.quick
            list.forEachIndexed { i, q ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(q, style = MaterialTheme.typography.bodyLarge, color = C.ink, modifier = Modifier.weight(1f).testTag("qr_$i"))
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { val nl = list.filterIndexed { k, _ -> k != i }; if (nl.isEmpty()) st.quickReset() else st.quick = nl; hub.saveSettings("quick") }
                        .semantics { contentDescription = t("Remove “$q”", "Ondoa “$q”") }.testTag("qr_del_$i"), contentAlignment = Alignment.Center) { Icon(Ic.get("x"), null, tint = C.muted, modifier = Modifier.size(16.dp)) }
                }
            }
            if (list.size < QUICK_MAX) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Bottom) {
                Field(add, { add = it.take(60) }, t("New quick reply", "Jibu jipya la haraka"), Modifier.weight(1f).testTag("qr_new"))
                Spacer(Modifier.width(8.dp))
                Btn(t("Add", "Ongeza"), { val x = add.trim(); if (x.isNotBlank()) { st.quick = list + x; add = ""; hub.saveSettings("quick") } }, Modifier.testTag("qr_add"), small = true, icon = "plus", enabled = add.isNotBlank())
            } else Text(t("That’s the most ($QUICK_MAX). Remove one to add another.", "Hiyo ndiyo idadi ya juu ($QUICK_MAX). Ondoa moja ili kuongeza nyingine."), style = MaterialTheme.typography.bodySmall, color = C.warn, modifier = Modifier.padding(8.dp).testTag("qr_full"))
            if (st.quickCustom) SheetRow("qr_reset", "undo", t("Back to the usual replies", "Rudisha majibu ya kawaida")) { st.quickReset(); hub.saveSettings("quick") }
        }

        SetCard(t("DO NOT DISTURB", "USINISUMBUE")) {
            val d = st.dnd
            Text(t("During these hours: no sounds, quiet notifications and no push.", "Katika saa hizi: hakuna sauti, arifa kimya na hakuna push."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(horizontal = 8.dp))
            SetSwitch("dnd_on", t("Quiet hours", "Saa za utulivu"), d.on) { st.dnd = d.copy(on = it); hub.saveSettings("dnd") }
            if (d.on) {
                fun pick(cur: String, set: (String) -> Unit) {
                    val tm = org.rafikioutreach.pin.data.Dnd.parse(cur) ?: java.time.LocalTime.of(21, 0)
                    runCatching { android.app.TimePickerDialog(ctx, { _, h, mi -> set("%02d:%02d".format(h, mi)) }, tm.hour, tm.minute, true).show() }
                }
                SheetRow("dnd_from", "moon", t("From", "Kuanzia"), value = d.from) { pick(d.from) { v -> st.dnd = st.dnd.copy(from = v); hub.saveSettings("dnd") } }
                SheetRow("dnd_to", "sun", t("Until", "Hadi"), value = d.to) { pick(d.to) { v -> st.dnd = st.dnd.copy(to = v); hub.saveSettings("dnd") } }
                SetSwitch("dnd_mentions", t("Let @mentions through", "Ruhusu @kutajwa kupita"), d.mentions) { st.dnd = d.copy(mentions = it); hub.saveSettings("dnd") }
                if (d.active()) Text(t("Quiet now, until ${d.to}.", "Kimya sasa, hadi ${d.to}."), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = C.hue("purple").fg, modifier = Modifier.padding(8.dp).testTag("dnd_now"))
            }
        }

        SetCard(t("SOUNDS", "SAUTI")) {
            SetSwitch("snd_send", t("Soft sound when I send", "Sauti laini ninapotuma"), st.sendSound) { st.sendSound = it; hub.settingsTick.value++ }
            SetSwitch("snd_mention", t("A chime when I’m @mentioned", "Kengele ninapotajwa kwa @"), st.mentionSound) { st.mentionSound = it; hub.settingsTick.value++ }
        }

        SetCard(t("DATA SAVER", "KUOKOA DATA")) {
            Text(t("Photos load as small previews; full size, videos and documents when you tap them. Voice messages still load by themselves.", "Picha hupakia kama hakikisho ndogo; ukubwa kamili, video na hati unapozigusa. Ujumbe wa sauti bado hupakia wenyewe."),
                style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(horizontal = 8.dp))
            listOf("off" to t("Off", "Imezimwa"), "mobile" to t("On mobile data", "Kwenye data ya simu"), "always" to t("Always", "Daima")).forEach { (k, label) ->
                val on = st.saver == k
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.RadioButton) { st.saver = k; hub.settingsTick.value++ }.heightIn(min = 48.dp).padding(horizontal = 8.dp).testTag("saver_$k"), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (on) C.blue else C.edge, CircleShape), contentAlignment = Alignment.Center) { if (on) Box(Modifier.size(11.dp).clip(CircleShape).background(C.blue)) }
                    Text(label, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(start = 12.dp))
                }
            }
            Text(t("Data used by chat this month: ", "Data iliyotumiwa na gumzo mwezi huu: ") + dataText(st.usedThisMonth), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.ink, modifier = Modifier.padding(8.dp).testTag("data_used"))
        }
        note?.let { Msg(it, "ok") }
    }
}

@Composable
private fun SetCard(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.card).border(if (C.glassy) 1.dp else 2.dp, C.line, RoundedCornerShape(22.dp)).padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp, fontWeight = FontWeight.ExtraBold), color = C.muted, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp))
        content()
    }
}

@Composable
private fun SetSwitch(tag: String, label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Switch) { set(!on) }.heightIn(min = 52.dp).padding(start = 8.dp, end = 6.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
        Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = ChatLook.online))
    }
}
