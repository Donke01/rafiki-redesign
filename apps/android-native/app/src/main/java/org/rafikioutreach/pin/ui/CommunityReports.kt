package org.rafikioutreach.pin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.reasonText
import org.rafikioutreach.pin.t

/** What happened to a community SMS / WhatsApp message: label and tone ("bad", "warn", "ok", "has", "off"). */
fun smsResult(result: String): Pair<String, String> = when (result) {
    "reported-broken" -> t("Not working", "Haifanyi kazi") to "bad"
    "reported-repair" -> t("Needs repair", "Inahitaji ukarabati") to "warn"
    "already-open" -> t("Already reported", "Imesharipotiwa") to "has"
    "fixed" -> t("Working again", "Inafanya kazi tena") to "ok"
    "fixed-unconfirmed" -> t("Says it works: check", "Wanasema inafanya kazi: hakikisha") to "ok"
    "noted-working" -> t("Says it works", "Wanasema inafanya kazi") to "ok"
    "help" -> t("Asked how to use it", "Waliuliza jinsi ya kutumia") to "has"
    "unknown" -> t("Not understood", "Haikueleweka") to "has"
    "no-project" -> t("Unknown project number", "Namba ya mradi haijulikani") to "has"
    "ignored-unregistered" -> t("Ignored: phone not on the list", "Imepuuzwa: simu haipo kwenye orodha") to "off"
    "ignored-no-project" -> t("Ignored: no such project", "Imepuuzwa: mradi haupo") to "off"
    "rate-limited", "daily-limit" -> t("Ignored: too many messages", "Imepuuzwa: ujumbe mwingi mno") to "off"
    "error" -> t("monday.com didn’t answer", "monday.com haikujibu") to "off"
    else -> result.ifBlank { "?" } to "off"
}

/**
 * Office › Community: water committees' SMS / WhatsApp reports (from /api/sms-admin), what is switched on in
 * Cloudflare, the committee phones, and whether voice notes are typed out. Read-only here: the phone list is
 * edited in the web Office.
 */
@Composable
fun CommunityReports(m: AppModel, open: (String) -> Unit) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val r = m.api.smsAdmin()
        if (r.ok) data = r.json else err = if (r.status == 404) t("Community reports aren’t on the server yet.", "Ripoti za jamii bado hazipo kwenye seva.") else reasonText(r.reason)
    }
    val j = data
    if (j == null) { if (err != null) Msg(err, "bad") else Text(t("Loading…", "Inapakia…"), color = C.muted); return }
    val reps = j.optJSONArray("reports") ?: JSONArray()
    val phones = j.optJSONArray("phones") ?: JSONArray()
    val setup = j.optJSONObject("setup") ?: JSONObject()
    val at = setup.optJSONObject("at") ?: JSONObject(); val tw = setup.optJSONObject("twilio") ?: JSONObject()
    val voice = j.optJSONObject("voice") ?: JSONObject()

    Card(Modifier.testTag("sms_head")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile("chat", C.blue, C.tint, 44.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Community reports", "Ripoti za jamii"), style = MaterialTheme.typography.titleMedium, color = C.ink)
                Text(t("Water committees report a broken water point by SMS or WhatsApp. Reports open a repair straight away.", "Kamati za maji zinaripoti kituo kilichoharibika kwa SMS au WhatsApp. Ripoti zinafungua ukarabati mara moja."), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
        }
    }
    Text(t("Latest messages", "Ujumbe wa karibuni"), style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))
    if (reps.length() == 0) EmptyDrop(t("No messages yet. When a water committee texts the RAFIKI number, it shows here.", "Hakuna ujumbe bado. Kamati ya maji ikituma ujumbe kwa namba ya RAFIKI, utaonekana hapa."))
    (0 until minOf(reps.length(), 40)).map { reps.getJSONObject(it) }.forEach { x ->
        val (label, tone) = smsResult(x.optString("result"))
        val (fg, bg) = when (tone) { "bad" -> C.bad to C.badBg; "warn" -> C.warn to C.warnBg; "ok" -> C.ok to C.okBg; "has" -> C.blue2 to C.tint; else -> C.muted to C.bg }
        Card(Modifier.padding(top = 8.dp).testTag("sms_msg"), pad = 14.dp) {
            Row {
                IconTile(if (x.optString("ch") == "whatsapp") "chat" else "phone", fg, bg, 36.dp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val id = x.optString("id"); val title = x.optString("t")
                        Text(title.ifBlank { if (id.isBlank()) t("No project", "Hakuna mradi") else t("Project ", "Mradi ") + id }, style = MaterialTheme.typography.titleSmall,
                            color = if (title.isNotBlank()) C.blue2 else C.ink, modifier = Modifier.weight(1f).then(if (title.isNotBlank() && id.isNotBlank()) Modifier.clickable { open(id) } else Modifier))
                        Spacer(Modifier.width(6.dp)); Tag(label, bg, fg)
                    }
                    Text("“" + x.optString("text") + "”", style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 4.dp))
                    Text(listOf(x.optString("from"), x.optString("name"), if (x.optString("ch") == "whatsapp") "WhatsApp" else "SMS", whenText(x.optString("at"))).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 4.dp))
                    x.optString("reply").takeIf { it.isNotBlank() }?.let { Text("↩ $it", style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 2.dp)) }
                }
            }
        }
    }

    Card(Modifier.padding(top = 16.dp).testTag("sms_setup")) {
        Text(t("Set-up", "Mipangilio"), style = MaterialTheme.typography.titleMedium, color = C.ink)
        OnRow(at.optBoolean("on"), "SMS (Africa’s Talking)", "sms_at")
        OnRow(at.optBoolean("replies"), t("Answers by SMS", "Majibu kwa SMS"), "sms_replies")
        OnRow(tw.optBoolean("on"), "WhatsApp / SMS (Twilio)", "sms_twilio")
        Vars(at.optJSONObject("vars")); Vars(tw.optJSONObject("vars"))
        Text(t("Webhook addresses and phone numbers are set in the Office on the web.", "Anwani za webhook na namba za simu zinawekwa kwenye Ofisi ya wavuti."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 8.dp))
        (j.optJSONObject("urls") ?: JSONObject()).let { u -> listOf(u.optString("at"), u.optString("twilio")).filter { it.isNotBlank() }.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = C.ink, modifier = Modifier.padding(top = 4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(C.bg).padding(8.dp)) } }
    }
    Card(Modifier.padding(top = 12.dp).testTag("sms_phones")) {
        Text(t("Committee phones", "Simu za kamati") + " (" + phones.length() + ")", style = MaterialTheme.typography.titleMedium, color = C.ink)
        if (phones.length() == 0) Text(t("None yet.", "Bado hakuna."), style = MaterialTheme.typography.bodySmall, color = C.muted)
        (0 until phones.length()).map { phones.getJSONObject(it) }.forEach { p ->
            Column(Modifier.padding(top = 8.dp)) {
                Text(p.optString("phone"), style = MaterialTheme.typography.titleSmall, color = C.ink)
                Text(listOf(p.optString("name"), p.optString("t")).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
        }
    }
    Card(Modifier.padding(top = 12.dp).testTag("sms_voice")) {
        Text(t("Voice notes typed out", "Sauti kuandikwa"), style = MaterialTheme.typography.titleMedium, color = C.ink)
        OnRow(voice.optBoolean("on"), t("Typed out on monday.com", "Huandikwa kwenye monday.com"), "voice_on")
        val missing = voice.optJSONArray("missing")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        Text(if (voice.optBoolean("on")) t("Voice notes from visits are typed out (Kiswahili or English) and added to the visit’s note, so History shows them.", "Sauti za ziara zinaandikwa (Kiswahili au Kiingereza) na kuongezwa kwenye maelezo ya ziara.")
            else t("Off. Add ", "Imezimwa. Ongeza ") + missing.ifEmpty { listOf("OPENAI_API_KEY") }.joinToString(", ") + t(" in Cloudflare to switch it on.", " kwenye Cloudflare kuiwasha."),
            style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun OnRow(on: Boolean, label: String, tag: String) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.get(if (on) "check" else "x"), null, tint = if (on) C.ok else C.muted, modifier = Modifier.size(22.dp).clip(CircleShape).background(if (on) C.okBg else C.bg).padding(4.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.weight(1f))
        Text(if (on) t("On", "Imewashwa") else t("Off", "Imezimwa"), style = MaterialTheme.typography.labelLarge, color = if (on) C.ok else C.muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Vars(v: JSONObject?) {
    if (v == null) return
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        v.keys().asSequence().toList().forEach { k -> val on = v.optBoolean(k); Tag((if (on) "✓ " else "") + k, if (on) C.okBg else C.bg, if (on) C.ok else C.muted) }
    }
}
