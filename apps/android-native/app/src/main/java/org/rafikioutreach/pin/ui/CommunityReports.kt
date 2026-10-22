package org.rafikioutreach.pin.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.reasonText
import org.rafikioutreach.pin.t
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

/** The filter a result falls under: broken, working, other or ignored. */
internal fun smsBucket(result: String): String = when (result) {
    "reported-broken", "reported-repair", "already-open" -> "broken"
    "fixed", "fixed-unconfirmed", "noted-working" -> "working"
    "help", "unknown", "no-project", "error" -> "other"
    else -> "ignored"
}

/** One sentence on what the app did with the message. */
internal fun smsExplain(result: String): String = when (result) {
    "reported-broken" -> t("A repair was opened and the admins were e-mailed.", "Ukarabati ulifunguliwa na wasimamizi walitumiwa barua pepe.")
    "reported-repair" -> t("A repair was opened.", "Ukarabati ulifunguliwa.")
    "already-open" -> t("A repair was already open, so a note was added.", "Ukarabati ulikuwa wazi tayari, kwa hiyo maelezo yaliongezwa.")
    "fixed" -> t("The open repair was closed.", "Ukarabati uliokuwa wazi ulifungwa.")
    "fixed-unconfirmed" -> t("Noted on monday.com; the repair stays open until the team checks.", "Imeandikwa kwenye monday.com; ukarabati unabaki wazi hadi timu ihakikishe.")
    "noted-working" -> t("Noted on monday.com.", "Imeandikwa kwenye monday.com.")
    "help" -> t("They were sent the instructions.", "Walitumiwa maelekezo.")
    "unknown" -> t("The message wasn’t understood; they were sent the instructions.", "Ujumbe haukueleweka; walitumiwa maelekezo.")
    "no-project" -> t("There is no project with that number; they were told.", "Hakuna mradi wenye namba hiyo; waliambiwa.")
    "ignored-no-project" -> t("There is no project with that number and the phone isn’t registered, so nothing was done.", "Hakuna mradi wenye namba hiyo na simu haijasajiliwa, kwa hiyo hakuna kilichofanyika.")
    "ignored-unregistered" -> t("No project number, and the phone isn’t on the committee list, so nothing was done.", "Hakuna namba ya mradi, na simu haipo kwenye orodha ya kamati, kwa hiyo hakuna kilichofanyika.")
    "rate-limited", "daily-limit" -> t("Too many messages from this phone today, so it was ignored.", "Ujumbe mwingi mno kutoka simu hii leo, kwa hiyo ulipuuzwa.")
    "error" -> t("monday.com didn’t answer; try again later.", "monday.com haikujibu; jaribu tena baadaye.")
    else -> ""
}

/** "+254712345678" → "+254 712 ••• 678"; "wa:KE.1461161065410436" → "WhatsApp user ••• 436". */
internal fun smsSender(from: String): String = when {
    from.startsWith("wa:") -> t("WhatsApp user ••• ", "Mtumiaji wa WhatsApp ••• ") + from.takeLast(3)
    from.startsWith("+") && from.length > 10 -> from.take(4) + " " + from.substring(4, 7) + " ••• " + from.takeLast(3)
    else -> from
}

/** A real phone number we can call (not a hidden WhatsApp ID). */
internal fun smsDialable(from: String) = Regex("^\\+\\d{9,15}$").matches(from)

/** "8 min", "5 h", "Yesterday", "3 Oct". */
internal fun smsAgo(iso: String, now: Instant = Instant.now()): String = runCatching {
    val at = Instant.parse(iso); val m = Duration.between(at, now).toMinutes()
    when {
        m < 1 -> t("now", "sasa")
        m < 60 -> "$m min"
        m < 24 * 60 -> "${m / 60} h"
        m < 48 * 60 -> t("Yesterday", "Jana")
        else -> Dates.day(at.atZone(Dates.zone()).toLocalDate())
    }
}.getOrDefault("")

private fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }
@Composable
private fun toneColors(tone: String): Pair<Color, Color> = when (tone) {
    "bad" -> C.bad to C.badBg; "warn" -> C.warn to C.warnBg; "ok" -> C.ok to C.okBg; "has" -> C.blue2 to C.tint; else -> C.muted to C.bg
}
private val WaGreen = Color(0xFF1E9E4A)

/**
 * Office › Community: water committees' SMS / WhatsApp reports (from /api/sms-admin). Numbers for the last
 * 7 days, filters, message cards that open a sheet with what happened and what to do next, and the
 * settings (set-up, committee phones, voice notes) folded away until tapped.
 */
@Composable
fun CommunityReports(m: AppModel, open: (String) -> Unit) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("all") }
    var shown by remember { mutableIntStateOf(40) }
    var picked by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(reload) {
        val r = m.api.smsAdmin()
        if (r.ok) { data = r.json; err = null } else err = if (r.status == 404) t("Community reports aren’t on the server yet.", "Ripoti za jamii bado hazipo kwenye seva.") else reasonText(r.reason)
    }
    val j = data
    if (j == null) { if (err != null) Msg(err, "bad") else Text(t("Loading…", "Inapakia…"), color = C.muted); return }
    val reps = (j.optJSONArray("reports") ?: JSONArray()).objs()
    val phones = (j.optJSONArray("phones") ?: JSONArray()).objs()
    val week = Instant.now().minus(Duration.ofDays(7))
    val recent = reps.filter { runCatching { Instant.parse(it.optString("at")).isAfter(week) }.getOrDefault(false) }

    // the head: what it is, and the last 7 days in numbers
    Card(Modifier.testTag("sms_head")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile("chat", C.blue, C.tint, 44.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Community reports", "Ripoti za jamii"), style = MaterialTheme.typography.titleMedium, color = C.ink)
                Text(t("Water committees text or WhatsApp when a water point breaks.", "Kamati za maji zinatuma SMS au WhatsApp kituo cha maji kikiharibika."), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
        }
        Row(Modifier.padding(top = 14.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmsStat(Modifier.weight(1f).testTag("sms_n_week"), recent.size, t("last 7 days", "siku 7 zilizopita"), C.ink)
            SmsStat(Modifier.weight(1f).testTag("sms_n_broken"), recent.count { smsBucket(it.optString("result")) == "broken" }, t("not working", "hazifanyi kazi"), C.bad)
            SmsStat(Modifier.weight(1f).testTag("sms_n_ignored"), recent.count { smsBucket(it.optString("result")) == "ignored" }, t("ignored", "zimepuuzwa"), C.muted)
        }
    }

    // filters
    val filters = listOf(
        Triple("all", t("All", "Zote"), reps.size),
        Triple("broken", t("Broken", "Zimeharibika"), reps.count { smsBucket(it.optString("result")) == "broken" }),
        Triple("working", t("Working", "Zinafanya kazi"), reps.count { smsBucket(it.optString("result")) == "working" }),
        Triple("other", t("Other", "Nyingine"), reps.count { smsBucket(it.optString("result")) == "other" }),
        Triple("ignored", t("Ignored", "Zimepuuzwa"), reps.count { smsBucket(it.optString("result")) == "ignored" }),
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        filters.forEach { (k, label, n) -> Chip("$label · $n", null, filter == k, { filter = k; shown = 40 }, tag = "sms_filter_$k", hue = "blue") }
    }
    val list = reps.filter { filter == "all" || smsBucket(it.optString("result")) == filter }
    if (list.isEmpty()) EmptyDrop(if (reps.isEmpty()) t("No messages yet. When a water committee texts the RAFIKI number, it shows here.", "Hakuna ujumbe bado. Kamati ya maji ikituma ujumbe kwa namba ya RAFIKI, utaonekana hapa.")
                                  else t("No messages here.", "Hakuna ujumbe hapa."))
    list.take(shown).forEachIndexed { i, x -> SmsCard(x, Modifier.padding(top = 10.dp).rise(minOf(i, 6))) { picked = x } }
    if (list.size > shown) Btn(t("Show more", "Onyesha zaidi") + " (" + (list.size - shown) + ")", { shown += 40 }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("sms_more"), light = true, small = true)

    // settings, folded away
    val setup = j.optJSONObject("setup") ?: JSONObject()
    val at = setup.optJSONObject("at") ?: JSONObject(); val tw = setup.optJSONObject("twilio") ?: JSONObject()
    val voice = j.optJSONObject("voice") ?: JSONObject()
    Text(t("Settings", "Mipangilio"), style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
    Card(pad = 4.dp) {
        val sum = listOf("WhatsApp " + (if (tw.optBoolean("on")) t("on", "imewashwa") else t("off", "imezimwa")), "SMS " + (if (at.optBoolean("on")) t("on", "imewashwa") else t("off", "imezimwa"))).joinToString(" · ")
        Fold("sms_setup", "cog", t("Set-up", "Usanidi"), sum, if (tw.optBoolean("on") || at.optBoolean("on")) "ok" else "off") {
            OnRow(at.optBoolean("on"), "SMS (Africa’s Talking)", "sms_at")
            OnRow(at.optBoolean("replies"), t("Answers by SMS", "Majibu kwa SMS"), "sms_replies")
            OnRow(tw.optBoolean("on"), "WhatsApp / SMS (Twilio)", "sms_twilio")
            Vars(at.optJSONObject("vars")); Vars(tw.optJSONObject("vars"))
            Text(t("Paste these addresses in Africa’s Talking or Twilio:", "Bandika anwani hizi kwenye Africa’s Talking au Twilio:"), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 12.dp))
            (j.optJSONObject("urls") ?: JSONObject()).let { u -> listOf(u.optString("at"), u.optString("twilio")).filter { it.isNotBlank() }.forEach { CopyLine(it) } }
        }
        HorizontalDivider(color = C.line, modifier = Modifier.padding(horizontal = 14.dp))
        Fold("sms_phones", "phone", t("Committee phones", "Simu za kamati"), if (phones.size == 1) t("1 phone", "simu 1") else t("${phones.size} phones", "simu ${phones.size}"), if (phones.isNotEmpty()) "has" else "off") {
            if (phones.isEmpty()) Text(t("None yet. Open a message from a committee and tap “Add as committee phone”.", "Bado hakuna. Fungua ujumbe kutoka kwa kamati na uguse “Ongeza kama simu ya kamati”."), style = MaterialTheme.typography.bodySmall, color = C.muted)
            phones.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile("phone", C.blue, C.tint, 34.dp); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.optString("phone"), style = MaterialTheme.typography.titleSmall, color = C.ink)
                        Text(listOf(p.optString("name"), p.optString("t")).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted)
                    }
                }
            }
        }
        HorizontalDivider(color = C.line, modifier = Modifier.padding(horizontal = 14.dp))
        Fold("sms_voice", "mic", t("Voice notes typed out", "Sauti kuandikwa"), if (voice.optBoolean("on")) t("On", "Imewashwa") else t("Off", "Imezimwa"), if (voice.optBoolean("on")) "ok" else "off") {
            OnRow(voice.optBoolean("on"), t("Typed out on monday.com", "Huandikwa kwenye monday.com"), "voice_on")
            val missing = voice.optJSONArray("missing")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            Text(if (voice.optBoolean("on")) t("Voice notes from visits are typed out (Kiswahili or English) and added to the visit’s note, so History shows them.", "Sauti za ziara zinaandikwa (Kiswahili au Kiingereza) na kuongezwa kwenye maelezo ya ziara.")
                else t("Off. Add ", "Imezimwa. Ongeza ") + missing.ifEmpty { listOf("OPENAI_API_KEY") }.joinToString(", ") + t(" in Cloudflare to switch it on.", " kwenye Cloudflare kuiwasha."),
                style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 6.dp))
        }
    }

    picked?.let { x -> SmsSheet(m, x, phones, { picked = null }, { id -> picked = null; open(id) }, { reload++ }) }
}

@Composable
private fun SmsStat(modifier: Modifier, n: Int, label: String, color: Color) {
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(C.bg).padding(vertical = 12.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$n", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = C.muted, textAlign = TextAlign.Center)
    }
}

/** One message: who and which project, what happened, and the message in a chat bubble. Tap for more. */
@Composable
private fun SmsCard(x: JSONObject, modifier: Modifier, onTap: () -> Unit) {
    val result = x.optString("result"); val (label, tone) = smsResult(result); val (fg, bg) = toneColors(tone)
    val wa = x.optString("ch") == "whatsapp"
    val id = x.optString("id"); val title = x.optString("t")
    val h = rememberHaptics()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button) { h.tap(); onTap() }.padding(14.dp).alpha(if (tone == "off") 0.72f else 1f).testTag("sms_msg"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (wa) WaGreen.copy(alpha = 0.14f) else C.tint), contentAlignment = Alignment.Center) {
                Icon(Ic.get(if (wa) "chat" else "phone"), null, tint = if (wa) WaGreen else C.blue, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { if (id.isBlank()) t("No project number", "Hakuna namba ya mradi") else t("Project ", "Mradi ") + id }, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(fg)); Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(smsAgo(x.optString("at")), style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1)
            Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
        }
        Text(x.optString("text"), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp), color = C.text,
            modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)).background(if (C.dark) C.bg else Color(0xFFF1F5F9)).padding(horizontal = 12.dp, vertical = 9.dp))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listOf(smsSender(x.optString("from")), x.optString("name"), if (wa) "WhatsApp" else "SMS").filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (x.optString("reply").isNotBlank()) Text("↩ " + t("Replied", "Imejibiwa"), style = MaterialTheme.typography.bodySmall, color = C.muted)
        }
    }
}

/** What happened with one message, and the next steps: open the project, call, WhatsApp, add the phone. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SmsSheet(m: AppModel, x: JSONObject, phones: List<JSONObject>, close: () -> Unit, open: (String) -> Unit, changed: () -> Unit) {
    val scope = rememberScope()
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val result = x.optString("result"); val (label, tone) = smsResult(result); val (fg, bg) = toneColors(tone)
    val from = x.optString("from"); val wa = x.optString("ch") == "whatsapp"
    val id = x.optString("id"); val title = x.optString("t")
    val dial = smsDialable(from)
    val registered = x.optBoolean("reg") || phones.any { it.optString("phone") == from }
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf(false) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = close, sheetState = state, containerColor = C.card, modifier = Modifier.testTag("sms_sheet")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(when (tone) { "bad" -> "alert"; "warn" -> "wrench"; "ok" -> "check"; else -> "chat" }, fg, bg, 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = fg)
                    Text(title.ifBlank { if (id.isBlank()) t("No project number", "Hakuna namba ya mradi") else t("Project ", "Mradi ") + id }, style = MaterialTheme.typography.titleLarge, color = C.ink)
                }
                Box(Modifier.size(44.dp).clip(CircleShape).background(C.bg).clickable(role = Role.Button) { scope.launch { state.hide() }.invokeOnCompletion { close() } }.testTag("sms_close"), contentAlignment = Alignment.Center) {
                    Icon(Ic.get("x"), t("Close", "Funga"), tint = C.ink, modifier = Modifier.size(20.dp))
                }
            }
            // the conversation
            Text(t("Their message", "Ujumbe wao"), style = MaterialTheme.typography.labelMedium, color = C.muted, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
            Text(x.optString("text"), style = MaterialTheme.typography.bodyLarge, color = C.text,
                modifier = Modifier.clip(RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)).background(if (C.dark) C.bg else Color(0xFFF1F5F9)).padding(horizontal = 14.dp, vertical = 10.dp))
            x.optString("reply").takeIf { it.isNotBlank() }?.let { r ->
                Text(t("RAFIKI replied", "RAFIKI ilijibu"), style = MaterialTheme.typography.labelMedium, color = C.muted, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Text(r, style = MaterialTheme.typography.bodyMedium, color = Color.White,
                        modifier = Modifier.padding(start = 40.dp).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomEnd = 18.dp, bottomStart = 18.dp)).background(C.blue).padding(horizontal = 14.dp, vertical = 10.dp).testTag("sms_reply"))
                }
            }
            smsExplain(result).takeIf { it.isNotBlank() }?.let {
                Row(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Ic.get("info"), null, tint = fg, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = C.ink, modifier = Modifier.testTag("sms_explain"))
                }
            }
            // facts
            Column(Modifier.padding(top = 14.dp)) {
                Fact(t("From", "Kutoka"), smsSender(from) + (x.optString("name").takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") + if (registered) t(" (committee phone)", " (simu ya kamati)") else "")
                Fact(t("Channel", "Njia"), if (wa) "WhatsApp" else "SMS")
                Fact(t("Received", "Ilipokelewa"), whenText(x.optString("at")))
            }
            // next steps
            FlowRow(Modifier.padding(top = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (title.isNotBlank() && id.isNotBlank()) Btn(t("Open project", "Fungua mradi"), { open(id) }, Modifier.fillMaxWidth().testTag("sms_open"), icon = "pin")
                if (dial) {
                    Btn(t("Call", "Piga simu"), { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$from"))) } }, Modifier.weight(1f).testTag("sms_call"), icon = "phone", light = true, small = true)
                    Btn("WhatsApp", { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + from.filter { it.isDigit() }))) } }, Modifier.weight(1f).testTag("sms_wa"), icon = "chat", light = true, small = true)
                    Btn(t("Copy number", "Nakili namba"), { clip.setText(AnnotatedString(from)); msg = "ok" to t("Copied ✓", "Imenakiliwa ✓") }, Modifier.fillMaxWidth().testTag("sms_copy"), icon = "copy", light = true, small = true)
                }
                if (dial && title.isNotBlank() && id.isNotBlank() && !registered && !added) {
                    Btn(t("Add as committee phone", "Ongeza kama simu ya kamati"), {
                        busy = true
                        scope.launch {
                            val r = m.api.smsAdminPost(JSONObject().put("action", "phone-add").put("phone", from).put("id", id).put("name", x.optString("name")).put("cc", ""))
                            busy = false
                            if (r.ok) { added = true; msg = "ok" to t("Added ✓ This phone can now report and close repairs for $title.", "Imeongezwa ✓ Simu hii sasa inaweza kuripoti na kufunga matengenezo ya $title."); changed() }
                            else msg = "bad" to reasonText(r.reason)
                        }
                    }, Modifier.fillMaxWidth().testTag("sms_add"), icon = "plus", light = true, enabled = !busy)
                }
            }
            if (!dial && from.startsWith("wa:")) Text(t("WhatsApp hid this person’s number, so you can’t call them from here.", "WhatsApp imeficha namba ya mtu huyu, kwa hiyo huwezi kumpigia kutoka hapa."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 10.dp).testTag("sms_hidden"))
            msg?.let { (tn, text) -> Msg(text, tn) }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.ink, modifier = Modifier.weight(1f))
    }
}

/** A settings row that opens and closes with a tap; the summary shows what's inside without opening it. */
@Composable
private fun Fold(tag: String, icon: String, title: String, summary: String, tone: String, content: @Composable () -> Unit) {
    var openNow by remember { mutableStateOf(false) }
    val h = rememberHaptics()
    val turn by animateFloatAsState(if (openNow) 90f else 0f, tween(if (Motion.on) 220 else 0), label = "chev")
    val (fg, bg) = toneColors(tone)
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { h.tap(); openNow = !openNow }.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp).testTag(tag + "_toggle"),
            verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, C.blue, C.tint, 36.dp); Spacer(Modifier.width(12.dp))
            // the title, and under it what's inside, so neither is squeezed
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink)
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (tone == "off") C.muted else fg)); Spacer(Modifier.width(6.dp))
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = C.muted)
                }
            }
            Spacer(Modifier.width(6.dp))
            Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp).rotate(turn))
        }
        AnimatedVisibility(openNow, enter = if (Motion.on) expandVertically(tween(240)) + fadeIn(tween(240)) else fadeIn(tween(0)), exit = if (Motion.on) shrinkVertically(tween(200)) + fadeOut(tween(200)) else fadeOut(tween(0))) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp).testTag(tag + "_body")) { content() }
        }
    }
}

@Composable
private fun CopyLine(text: String) {
    val clip = LocalClipboardManager.current
    var done by remember { mutableStateOf(false) }
    Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.bg).clickable { clip.setText(AnnotatedString(text)); done = true }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        // a long address breaks before "?token=", never in the middle of a word
        Text(text.replace("?", "\n?"), style = MaterialTheme.typography.bodySmall, color = C.ink, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Icon(Ic.get(if (done) "check" else "copy"), null, tint = if (done) C.ok else C.muted, modifier = Modifier.size(18.dp))
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
