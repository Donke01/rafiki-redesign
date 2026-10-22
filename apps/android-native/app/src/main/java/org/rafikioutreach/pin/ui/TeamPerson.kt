package org.rafikioutreach.pin.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.data.stateName
import org.rafikioutreach.pin.t
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Office › Team › a person (feat-teama): a row of quick actions under the name (contact, app access, their work),
// an app invite by e-mail (preview, then send; never with the PIN), make or remove admin with a confirm sheet,
// and their visits and activity. Me › Team › a teammate's picture opens the same contact actions and activity.

/** Jumps from a person's sheet to another Office tab: Team map with them in focus, or Assign a visit for them. */
object TeamGo {
    var section by mutableStateOf<String?>(null)
    var map by mutableStateOf<String?>(null)
    var assign by mutableStateOf<String?>(null)
}

const val PIN_APP_URL = "https://pin.rafikioutreach.org/pin"

/** The invite for WhatsApp or SMS: the same words as the e-mail. Never the PIN; that goes separately. */
internal fun inviteText(who: String, from: String, admin: Boolean): String {
    val first = who.trim().substringBefore(' ')
    val sign = from.trim().ifBlank { "RAFIKI Outreach" }
    return t(
        "Hi $first, you’re invited to RAFIKI Pin, the RAFIKI Outreach field app for pinning our water projects and reporting site visits.\n\n" +
            (if (admin) "As an admin you’ll also see the Office tab, for running repairs, visits and the team.\n\n" else "") +
            "Open it here: $PIN_APP_URL\n" +
            "On an iPhone, open the link in Safari, tap Share, then “Add to Home Screen”. On Android or a computer, just open the link.\n\n" +
            "Sign in with your PIN, which I’m sending you separately.\n\n— $sign",
        "Habari $first, umealikwa kwenye RAFIKI Pin, programu ya uwanjani ya RAFIKI Outreach ya kuweka mahali pa miradi yetu ya maji na kuripoti ziara.\n\n" +
            (if (admin) "Kama msimamizi utaona pia kichupo cha Ofisi, cha kusimamia matengenezo, ziara na timu.\n\n" else "") +
            "Ifungue hapa: $PIN_APP_URL\n" +
            "Kwenye iPhone, fungua kiungo kwenye Safari, gusa Shiriki, kisha “Ongeza kwenye Skrini ya Nyumbani”. Kwenye Android au kompyuta, fungua kiungo tu.\n\n" +
            "Ingia kwa PIN yako, nitakayokutumia kando.\n\n— $sign",
    )
}

/** One round quick action: an icon in a circle over a short label. */
data class QuickAct(val key: String, val icon: String, val label: String, val hue: String, val brand: String? = null, val onClick: () -> Unit)

/** The quick actions in neat rows of four, so all of them fit on a 360-wide phone without scrolling. */
@Composable
internal fun QuickActions(acts: List<QuickAct>, modifier: Modifier = Modifier) {
    if (acts.isEmpty()) return
    val h = rememberHaptics()
    Column(modifier.fillMaxWidth().testTag("tma_row"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        acts.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { a ->
                    val hu = if (a.hue == "wa") Hue(WhatsAppGreen, WhatsAppGreen.copy(alpha = 0.14f)) else C.hue(a.hue)
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { h.tap(); a.onClick() }.semantics { contentDescription = a.label }
                        .padding(vertical = 4.dp).testTag("tma_${a.key}"), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(hu.bg).then(if (!C.glassy) Modifier.border(1.5.dp, hu.fg.copy(alpha = 0.5f), CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                            if (a.brand != null) Brand(a.brand, 24.dp, a.icon, hu.fg)
                            else if (a.icon == "crown") Icon(Ic.crownFill, null, tint = hu.fg, modifier = Modifier.size(22.dp))
                            else Icon(Ic.get(a.icon), null, tint = hu.fg, modifier = Modifier.size(22.dp))
                        }
                        FitLabel(a.label, Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp))
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A label of at most two lines that shrinks a little rather than ever breaking a word in two. */
@Composable
private fun FitLabel(text: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val base = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val w = constraints.maxWidth
        val size = remember(text, w, base) {
            listOf(12f, 11.5f, 11f, 10.5f, 10f, 9.5f, 9f).firstOrNull { s ->
                val st = base.copy(fontSize = s.sp, lineHeight = (s + 3).sp)
                text.split(' ').all { measurer.measure(it, st, maxLines = 1, softWrap = false).size.width <= w } &&
                    measurer.measure(text, st, constraints = Constraints(maxWidth = w)).lineCount <= 2
            } ?: 9f
        }
        Text(text, style = base.copy(fontSize = size.sp, lineHeight = (size + 3).sp), color = C.ink, maxLines = 2, modifier = Modifier.fillMaxWidth())
    }
}

/** A field with a small copy button next to it (e-mail, phone). */
@Composable
internal fun CopyField(value: String, onChange: (String) -> Unit, label: String, placeholder: String, tag: String, copyLabel: String, phone: Boolean = false, onCopied: () -> Unit) {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    Row(verticalAlignment = Alignment.Bottom) {
        Field(value, onChange, label, Modifier.weight(1f), placeholder = placeholder, phone = phone)
        if (value.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.padding(bottom = 4.dp).size(48.dp).clip(CircleShape).clickable(role = Role.Button) { clip.setText(androidx.compose.ui.text.AnnotatedString(value.trim())); onCopied() }
                .semantics { contentDescription = copyLabel }.testTag(tag), contentAlignment = Alignment.Center) {
                Icon(Ic.get("copy"), null, tint = C.blue, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private val WORK_ACTS = setOf("pin", "visit", "fixed", "new")
private fun sameName(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
private fun localDay(iso: String): LocalDate? = runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()

/** Their pins, visits and fixes: today and this month from the team feed. Tap one to open the project. */
@Composable
internal fun PersonActivity(name: String, act: List<JSONObject>?, snap: Snapshot?, open: (String) -> Unit) {
    DqmLabel(t("Their visits and activity", "Ziara na kazi zake"))
    if (act == null) { Text(t("Loading…", "Inapakia…"), style = MaterialTheme.typography.bodyMedium, color = C.muted); return }
    val today = LocalDate.now(); val first = today.withDayOfMonth(1)
    val month = act.filter { sameName(it.optString("by"), name) && it.optString("a") in WORK_ACTS && localDay(it.optString("at"))?.let { d -> !d.isBefore(first) } == true }.sortedByDescending { it.optString("at") }
    val nToday = month.count { localDay(it.optString("at")) == today }
    Row(Modifier.fillMaxWidth().testTag("tma_counts"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(nToday to t("today", "leo"), month.size to t("this month", "mwezi huu")).forEach { (n, l) ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(C.tint).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("$n", style = MaterialTheme.typography.titleLarge, color = C.deep)
                Text(l, style = MaterialTheme.typography.labelMedium, color = C.deep, maxLines = 1)
            }
        }
    }
    if (month.isEmpty()) { Text(t("Nothing yet this month.", "Bado hakuna kitu mwezi huu."), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(top = 10.dp)); return }
    var more by remember(name) { mutableStateOf(false) }
    month.take(if (more) 30 else 5).forEach { e ->
        val id = e.optString("id")
        val p = snap?.projects?.firstOrNull { it.id == id }
        val (icon, hue) = when (e.optString("a")) { "pin" -> "pin" to "pin"; "visit" -> "clip" to "near"; "fixed" -> "check" to "near"; else -> "plus" to "new" }
        val hu = C.hue(hue)
        Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(if (p != null) Modifier.clickable(role = Role.Button) { open(id) } else Modifier)
            .heightIn(min = 52.dp).padding(vertical = 6.dp, horizontal = 4.dp).testTag("tma_act_$id"), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(hu.bg), contentAlignment = Alignment.Center) { Icon(Ic.get(icon), null, tint = hu.fg, modifier = Modifier.size(17.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(p?.title ?: e.optString("t"), style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(actVerb(e) + (if (e.optString("a") == "visit" && e.optString("s").isNotBlank()) " · " + stateName(e.optString("s")) else "") + " · " + ago(e.optString("at")),
                    style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (p != null) Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
        }
    }
    if (month.size > 5) Text(if (more) t("Show less", "Onyesha kidogo") else t("Show all ${month.size}", "Onyesha zote ${month.size}"), style = MaterialTheme.typography.labelLarge, color = C.blue,
        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).clickable { more = !more }.padding(8.dp).testTag("tma_act_more"))
}

private fun actVerb(e: JSONObject) = when (e.optString("a")) {
    "pin" -> t("Pinned", "Aliweka mahali"); "visit" -> t("Visited", "Alitembelea"); "fixed" -> t("Fixed", "Alirekebisha"); else -> t("Added", "Aliongeza")
}

/** Call, WhatsApp and SMS: only when there is a phone number. */
internal fun phoneActs(ctx: android.content.Context, name: String, phone: String, home: String): List<QuickAct> {
    val ph = phone.trim().takeIf { it.isNotBlank() && it != "null" } ?: return emptyList()
    return listOf(
        QuickAct("call", "phone", t("Call", "Piga simu"), "near") { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + ph.replace(" ", "")))) } },
        QuickAct("wa", "chat", "WhatsApp", "wa", brand = "business.whatsapp.com") { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + waNumber(ph, home)))) } },
        QuickAct("sms", "text", "SMS", "blue") { runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + ph.replace(" ", "")))) } },
    )
}

/** A small sheet that slides up over the person's sheet, with an X; back closes only this one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamSubSheet(tag: String, close: () -> Unit, content: @Composable ColumnScope.(hide: (() -> Unit) -> Unit) -> Unit) {
    val scope = rememberScope()
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hide: (() -> Unit) -> Unit = { then -> sheetHide(scope, state) { close(); then() } }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = close, sheetState = state, containerColor = C.card, modifier = Modifier.testTag(tag)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) { SheetCloseX(tag + "_x") { hide {} } }
            content(hide)
        }
    }
}

/** Make admin or remove admin: says what changes, then saves. Nobody can remove their own admin role. */
@Composable
internal fun AdminConfirmSheet(m: AppModel, p: JSONObject, makeAdmin: Boolean, close: () -> Unit, done: () -> Unit) {
    val scope = rememberScope()
    val name = p.optString("name")
    val self = !makeAdmin && sameName(name, m.prefs.name)
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    TeamSubSheet("tma_admin_sheet", close) { hide ->
        val hu = C.hue("amber")
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(hu.bg).padding(13.dp)) { Icon(Ic.crownFill, null, tint = hu.fg, modifier = Modifier.fillMaxSize()) }
            Text(if (makeAdmin) t("Make $name an admin?", "Mfanye $name msimamizi?") else t("Remove $name’s admin role?", "Ondoa nafasi ya msimamizi ya $name?"),
                style = MaterialTheme.typography.titleLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        }
        val lines = if (makeAdmin) listOf(
            t("They’ll see the Office tab: repairs, costs, visits, the team map and the team.", "Ataona kichupo cha Ofisi: matengenezo, gharama, ziara, ramani ya timu na timu."),
            t("They can add people, make PINs and send e-mails.", "Anaweza kuongeza watu, kutengeneza PIN na kutuma barua pepe."),
            t("They get e-mail alerts when a site stops working, if they have an e-mail.", "Atapata taarifa kwa barua pepe eneo likiacha kufanya kazi, kama ana barua pepe."),
        ) else listOf(
            t("The Office tab goes away for them the next time the app refreshes.", "Kichupo cha Ofisi kitaondoka kwake programu ikijisasisha tena."),
            t("They keep their PIN and can still pin sites and report visits.", "Anabaki na PIN yake na bado anaweza kuweka mahali na kuripoti ziara."),
            t("No more admin e-mail alerts.", "Hakuna tena taarifa za barua pepe za msimamizi."),
        )
        Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (self) C.badBg else C.hue("slate").bg).padding(14.dp).testTag("tma_admin_what")) {
            if (self) Text(t("You can’t remove your own admin role. Ask another admin to do it.", "Huwezi kuondoa nafasi yako mwenyewe ya msimamizi. Mwombe msimamizi mwingine afanye hivyo."),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.bad, modifier = Modifier.testTag("tma_admin_self"))
            else lines.forEach { l ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                    Icon(Ic.get(if (makeAdmin) "check" else "arrow"), null, tint = C.ink, modifier = Modifier.padding(top = 2.dp).size(16.dp)); Spacer(Modifier.width(8.dp))
                    Text(l, style = MaterialTheme.typography.bodyMedium, color = C.ink)
                }
            }
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(if (self) t("Close", "Funga") else t("Cancel", "Ghairi"), { hide {} }, Modifier.weight(1f).testTag("tma_admin_no"), light = true, small = true)
            if (!self) Btn(if (makeAdmin) t("Make admin", "Mfanye msimamizi") else t("Remove admin", "Ondoa usimamizi"), {
                busy = true; msg = null
                scope.launch {
                    val x = m.api.admin(JSONObject().put("action", "team-update").put("name", name).put("home", p.optString("home").takeIf { it != "null" } ?: "")
                        .put("email", p.optString("email").takeIf { it != "null" } ?: "").put("phone", p.optString("phone").takeIf { it != "null" } ?: "").put("admin", makeAdmin))
                    busy = false
                    if (x.ok) hide { done() }
                    else msg = if (x.reason == "self") t("You can’t remove your own admin role.", "Huwezi kuondoa nafasi yako mwenyewe ya msimamizi.") else org.rafikioutreach.pin.data.reasonText(x.reason)
                }
            }, Modifier.weight(1f).testTag("tma_admin_yes"), small = true, enabled = !busy, color = if (makeAdmin) null else C.bad)
        }
        Msg(msg, "bad")
    }
}

/** The app invite by e-mail: the server's ready-made message, previewed here, then sent to this one person. Also shared by WhatsApp or SMS. */
@Composable
internal fun InviteSheet(m: AppModel, p: JSONObject, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberScope()
    val name = p.optString("name")
    val email = p.optString("email").takeIf { it != "null" }.orEmpty()
    val phone = p.optString("phone").takeIf { it != "null" }.orEmpty()
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    LaunchedEffect(name) {
        val r = m.api.mail(JSONObject().put("action", "invite").put("to", name).put("preview", true))
        if (r.ok) { preview = r.json.optString("subject") to r.json.optString("html"); err = null } else err = mailWhy(r.reason, r.json)
    }
    val share = inviteText(name, m.prefs.name, p.optBoolean("admin"))
    TeamSubSheet("tma_invite_sheet", close) { hide ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val hu = C.hue("teal"); IconTile("mail", hu.fg, hu.bg, 52.dp)
            Text(t("Send app invite", "Tuma mwaliko wa programu"), style = MaterialTheme.typography.titleLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            Text(t("To ", "Kwa ") + name + (if (email.isNotBlank()) " · $email" else ""), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
        }
        Text(t("The PIN is never in the e-mail. Send it to them separately.", "PIN haiwi kamwe kwenye barua pepe. Wapelekee kando."), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = C.warn,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        val pv = preview
        Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.bg).border(1.dp, C.line, RoundedCornerShape(18.dp)).padding(14.dp).testTag("tma_invite_preview")) {
            if (pv == null) { Text(err ?: t("Getting the preview…", "Inaandaa onyesho…"), style = MaterialTheme.typography.bodyMedium, color = if (err != null) C.bad else C.muted, modifier = Modifier.testTag("tma_invite_msg")); return@Column }
            Text(t("Subject", "Kichwa"), style = MaterialTheme.typography.labelSmall, color = C.muted)
            Text(pv.first, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.testTag("tma_invite_subject"))
            Spacer(Modifier.height(8.dp)); Box(Modifier.fillMaxWidth().height(1.dp).background(C.line)); Spacer(Modifier.height(8.dp))
            InviteBody(pv.second)
        }
        if (sent) Msg(t("Invite sent to $name ✓ Now send their PIN separately.", "Mwaliko umetumwa kwa $name ✓ Sasa mtumie PIN yake kando."), "ok")
        else Btn(t("Send the invite", "Tuma mwaliko"), {
            busy = true; err = null
            scope.launch {
                val r = m.api.mail(JSONObject().put("action", "invite").put("to", name))
                busy = false
                if (r.ok) sent = true else err = mailWhy(r.reason, r.json)
            }
        }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("tma_invite_send"), icon = "mail", enabled = !busy && pv != null)
        if (pv != null) Msg(err, "bad")
        DqmLabel(t("Or share the invite", "Au shiriki mwaliko"))
        val ph = phone.trim().takeIf { it.isNotBlank() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("WhatsApp", { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + (ph?.let { waNumber(it, p.optString("home")) } ?: "") + "?text=" + Uri.encode(share)))) } },
                Modifier.weight(1f).testTag("tma_invite_wa"), icon = "chat", light = true, small = true)
            Btn("SMS", { runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + (ph?.replace(" ", "") ?: ""))).putExtra("sms_body", share)) } },
                Modifier.weight(1f).testTag("tma_invite_sms"), icon = "text", light = true, small = true)
        }
        if (sent) Btn(t("Done", "Imekamilika"), { hide {} }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("tma_invite_done"), light = true)
    }
}

/** The e-mail's words as text, with its "Open RAFIKI Pin" link drawn as the button it is in the e-mail. */
@Composable
private fun InviteBody(html: String) {
    val text = remember(html) {
        androidx.core.text.HtmlCompat.fromHtml(html.replace(Regex("(?is)<(style|script|head)[^>]*>.*?</\\1>"), ""), androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
            .toString().replace(' ', ' ').replace('￼', ' ').lines().map { it.trim() }.filter { it.isNotBlank() }
    }
    text.forEach { l ->
        if (l.equals("Open RAFIKI Pin", true) || l.equals("Fungua RAFIKI Pin", true))
            Box(Modifier.padding(vertical = 6.dp).clip(RoundedCornerShape(99.dp)).background(C.primary).padding(horizontal = 18.dp, vertical = 10.dp).testTag("tma_invite_btn")) {
                Text(l, color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        else Text(l, style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(vertical = 3.dp))
    }
}

/** Me › Team › a teammate: contact actions and their activity (no admin actions here). */
@Composable
internal fun MateSheet(mate: Mate, act: List<JSONObject>?, snap: Snapshot?, close: () -> Unit, open: (String) -> Unit) {
    val ctx = LocalContext.current
    TeamSubSheet("mate_sheet", close) { hide ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            DqmAvatar(mate.name, Color(0xFF0A78C2), Color.White, 64.dp)
            Text(mate.name, style = MaterialTheme.typography.titleLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            Text(t("Last seen ", "Alionekana ") + ago(mate.lastSeen), style = MaterialTheme.typography.bodySmall, color = C.muted, textAlign = TextAlign.Center)
        }
        val acts = phoneActs(ctx, mate.name, mate.phone, mate.home)
        if (acts.isNotEmpty()) QuickActions(acts, Modifier.padding(top = 14.dp))
        else Text(t("No phone number on the team list for them.", "Hakuna namba ya simu yake kwenye orodha ya timu."), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
        PersonActivity(mate.name, act, snap) { id -> hide { open(id) } }
    }
}
