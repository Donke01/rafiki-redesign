package org.rafikioutreach.pin.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.Reply
import org.rafikioutreach.pin.data.reasonText
import org.rafikioutreach.pin.t

// feat-conta: one contact sheet for every team member, the same wherever a person is tapped (Office › Team, Me › Team,
// the Team map, notifications, project history, repairs, Plan and Visits, the Office feed, the review sheets). A clear
// menu of actions like a phone's contact card; each action does one thing in its own small sheet (one field, Save, X).
// Community (SMS) reporters are not team members and keep their own sheet in Community reports.

/** Who's contact sheet is open (by name). Anything can open it: Contact.show("Grace Wanjiru"). */
object Contact {
    var who by mutableStateOf<String?>(null)
    /** Bumped when someone's details changed here, so lists of people (Office › Team) reload. */
    var changed by mutableIntStateOf(0)
    fun show(name: String?) { val n = name?.trim().orEmpty(); if (isPerson(n)) who = n }
    /** A team member's name, not a blank, a dash or a community (SMS/WhatsApp) reporter. */
    fun isPerson(n: String) = n.isNotBlank() && n != "—" && n != "-" && !n.startsWith("SMS from") && !n.startsWith("WhatsApp from") && n != communityName() && !n.equals("RAFIKI Pin", true)
}

/** What blocked means for one person: when, by whom and why. */
data class Blocked(val at: String, val by: String, val reason: String)

/** One person on the team, as the contact sheet needs them. [src]: "app", "cloudflare", "monday" or "" (unknown). */
data class Person(
    val name: String, val home: String = "", val email: String = "", val phone: String = "", val admin: Boolean = false, val src: String = "",
    val lastSeen: String? = null, val role: String = "", val aka: List<String> = emptyList(), val blocked: Blocked? = null, val known: Boolean = true,
    /** Admin through PIN_ADMINS in Cloudflare (can't be made a field officer in the app); also has a Cloudflare entry; signed out of all phones at. */
    val adminCf: Boolean = false, val alsoCloudflare: Boolean = false, val out: String? = null,
) {
    val app get() = src == "app"
    val cloudflare get() = src == "cloudflare"
    fun names() = listOf(name) + aka
    fun isCalled(n: String) = names().any { it.trim().equals(n.trim(), ignoreCase = true) }
    /** For the sheets that still take the team list's JSON (invite, make admin). */
    fun json(): JSONObject = JSONObject().put("name", name).put("home", home).put("email", email).put("phone", phone).put("admin", admin).put("role", role).put("source", src)

    companion object {
        private fun JSONObject.s(k: String) = optString(k).takeIf { it != "null" }.orEmpty().trim()
        fun from(o: JSONObject): Person {
            val b = o.optJSONObject("blocked")
            val aka = o.optJSONArray("aka")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
            // the admin team view has blocked {at, by, reason} or null; Me › Team's list only true/false
            val blocked = b?.let { Blocked(it.s("at"), it.s("by"), it.s("reason")) } ?: if (o.optBoolean("blocked")) Blocked("", "", "") else null
            return Person(o.s("name"), o.s("home"), o.s("email"), o.s("phone"), o.optBoolean("admin"), o.s("src").ifBlank { o.s("source") },
                o.s("lastSeen").ifBlank { null }, o.s("role"), aka, blocked, adminCf = o.optBoolean("adminCf"), alsoCloudflare = o.optBoolean("alsoCloudflare"), out = o.s("out").ifBlank { null })
        }
    }
}

/** A PIN the app refuses before asking the server: not 4 to 8 digits, all one digit, or a run like 1234 or 9876. */
fun pinProblem(pin: String): String? {
    val p = pin.trim()
    if (!Regex("^\\d{4,8}$").matches(p)) return "digits"
    if (p.all { it == p[0] }) return "weak-pin"
    val steps = p.zipWithNext { a, b -> b - a }.toSet()
    if (steps == setOf(1) || steps == setOf(-1)) return "weak-pin"
    // two digits taking turns (1212, 2525) or a repeated half (123123) are easy to guess too
    if (p.length % 2 == 0 && p.substring(0, p.length / 2) == p.substring(p.length / 2)) return "weak-pin"
    return null
}

/** The words for what the server said no to, in plain language. */
fun contactWhy(reason: String?): String = when (reason) {
    "exists" -> t("Someone with that name is already on the team.", "Mtu mwenye jina hilo tayari yuko kwenye timu.")
    "weak-pin" -> t("That PIN is too easy to guess. Choose another.", "PIN hiyo ni rahisi kubahatisha. Chagua nyingine.")
    "digits" -> t("Use 4 to 8 digits.", "Tumia tarakimu 4 hadi 8.")
    "self" -> t("You can’t do that to yourself. Ask another admin.", "Huwezi kujifanyia hivyo. Mwombe msimamizi mwingine.")
    "last-admin" -> t("They’re the last admin, so this would leave nobody to run the Office. Make someone else an admin first.", "Ndiye msimamizi wa mwisho, kwa hiyo hakuna atakayebaki kusimamia Ofisi. Mfanye mtu mwingine msimamizi kwanza.")
    else -> reasonText(reason)
}

/** The person's round picture with initials (the same colours as notifications). */
@Composable
internal fun PersonAvatar(name: String, size: Dp = 44.dp) {
    val hu = C.hue(nameHue(name))
    DqmAvatar(name, hu.bg, hu.fg, size)
}

/** A label and a team member's name with their picture and a chevron: a tap opens their contact sheet. */
@Composable
internal fun PersonFact(label: String, name: String, sub: String = "", tag: String = "pf_" + name.trim(), divider: Boolean = true) {
    if (name.isBlank()) return
    val tappable = Contact.isPerson(name.trim())
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(if (tappable) Modifier.clickable(role = Role.Button) { Contact.show(name) } else Modifier)
        .heightIn(min = 48.dp).padding(vertical = 6.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(end = 12.dp))
        Spacer(Modifier.weight(1f))
        if (tappable) { PersonAvatar(name, 28.dp); Spacer(Modifier.width(8.dp)) }
        Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.End) {
            Text(name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = if (tappable) C.blue2 else C.ink, textAlign = TextAlign.End)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = C.muted, textAlign = TextAlign.End)
        }
        if (tappable) Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.padding(start = 4.dp).size(16.dp))
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
}

/** A team member's name as a small pill with their picture: a tap opens their contact sheet (44 dp tall to tap). */
@Composable
internal fun PersonChip(name: String, modifier: Modifier = Modifier, tag: String = "pc_" + name.trim()) {
    if (!Contact.isPerson(name.trim())) { Text(name, style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = modifier); return }
    Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button) { Contact.show(name) }.semantics { contentDescription = name }.testTag(tag), contentAlignment = Alignment.CenterStart) {
        Row(Modifier.clip(RoundedCornerShape(99.dp)).background(C.tint).padding(start = 3.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(name, 24.dp); Spacer(Modifier.width(6.dp))
            Text(name, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = C.deep, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A person's picture (or anything else showing them) that opens their contact sheet; at least 44 dp to tap. */
@Composable
internal fun PersonTap(name: String, modifier: Modifier = Modifier, tag: String = "pt_" + name.trim(), content: @Composable () -> Unit) {
    if (!Contact.isPerson(name.trim())) { Box(modifier) { content() }; return }
    Box(modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).clip(CircleShape).clickable(role = Role.Button) { Contact.show(name) }.semantics { contentDescription = name }.testTag(tag),
        contentAlignment = Alignment.Center) { content() }
}

/** A feed row's picture: the person, with what they did as a small coloured badge (like notifications). */
@Composable
internal fun PersonBadgeAvatar(name: String, icon: String, hue: Hue, ring: Color, size: Dp = 36.dp) {
    Box(Modifier.size(size + 6.dp)) {
        Box(Modifier.align(Alignment.TopStart)) { PersonAvatar(name, size) }
        Box(Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(ring).padding(2.dp).clip(CircleShape).background(hue.fg), contentAlignment = Alignment.Center) {
            Icon(Ic.get(icon), null, tint = C.card, modifier = Modifier.size(11.dp))
        }
    }
}

/** Opens the contact sheet when Contact.who is set. [go]: ("project", id), ("office", section) or ("settings", page). */
@Composable
fun ContactHost(m: AppModel, go: (String, String) -> Unit) {
    val who = Contact.who ?: return
    key(who) { ContactSheet(m, who, close = { if (Contact.who == who) Contact.who = null }, go = go) }
}

/** The menu row: an icon, the label, the current value under it, and a chevron. */
@Composable
private fun CtRow(tag: String, icon: String, hue: String, label: String, value: String = "", red: Boolean = false, chevron: Boolean = true, brand: String? = null, onClick: (() -> Unit)?) {
    val h = rememberHaptics()
    val hu = if (hue == "wa") Hue(WhatsAppGreen, WhatsAppGreen.copy(alpha = 0.14f)) else C.hue(hue)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(if (onClick != null) Modifier.clickable(role = Role.Button) { h.tap(); onClick() } else Modifier)
        .heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 8.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(if (red) C.badBg else hu.bg), contentAlignment = Alignment.Center) {
            when {
                brand != null -> Brand(brand, 20.dp, icon, hu.fg)
                icon == "crown" -> Icon(Ic.crownFill, null, tint = hu.fg, modifier = Modifier.size(18.dp))
                else -> Icon(DqmIc.get(icon), null, tint = if (red) C.bad else hu.fg, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = if (red) C.bad else C.ink)
            if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null && chevron) { Spacer(Modifier.width(6.dp)); Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp)) }
    }
}

/** A group of rows on a card, under a small label. */
@Composable
private fun CtGroup(label: String, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.card).border(if (C.glassy) 1.dp else 2.dp, C.line, RoundedCornerShape(22.dp)).padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp)) {
        DqmLabel(label, Modifier.padding(start = 6.dp).testTag(tag))
        content()
    }
}

/** A big quick-action tile: a round coloured icon over its label. */
@Composable
private fun RowScope.CtTile(tag: String, label: String, circle: Color, fg: Color, icon: String, brand: String? = null, onClick: () -> Unit) {
    val h = rememberHaptics()
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.weight(1f).heightIn(min = 96.dp).press(src).clip(shape).background(C.hue("slate").bg).border(if (C.glassy) 1.dp else 2.dp, C.line, shape)
        .clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), role = Role.Button) { h.tap(); onClick() }.padding(horizontal = 2.dp, vertical = 12.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(circle), contentAlignment = Alignment.Center) {
            if (brand != null) BrandIcon(brand, 24.dp) else Icon(DqmIc.get(icon), null, tint = fg, modifier = Modifier.size(22.dp))
        }
        // shrinks to fit on one line, never cut short with "…" ("WhatsApp", "Barua pepe" with five tiles); the tiles
        // are tall enough for a full-size label, so a shrunk one doesn't make its tile shorter than the others
        WordFitText(label, MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), C.ink, 1, Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
    }
}

/** A role badge in the header. */
@Composable
private fun CtBadge(text: String, hue: String, icon: String? = null, crown: Boolean = false, red: Boolean = false, tag: String = "") {
    val hu = C.hue(hue)
    val fg = if (red) C.bad else hu.fg; val bg = if (red) C.badBg else hu.bg
    Row(Modifier.clip(RoundedCornerShape(99.dp)).background(bg).then(if (!C.glassy) Modifier.border(1.5.dp, fg, RoundedCornerShape(99.dp)) else Modifier)
        .padding(horizontal = 9.dp, vertical = 4.dp).then(if (tag.isNotBlank()) Modifier.testTag(tag) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        if (crown) Icon(Ic.crownFill, null, tint = fg, modifier = Modifier.size(13.dp)) else if (icon != null) Icon(DqmIc.get(icon), null, tint = fg, modifier = Modifier.size(13.dp))
        if (crown || icon != null) Spacer(Modifier.width(4.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 12.sp), maxLines = 1, softWrap = false)
    }
}

/** E-mail or phone under the name, with a copy button. */
@Composable
private fun CtLine(icon: String, value: String, copyLabel: String, tag: String, copied: () -> Unit) {
    val clip = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(DqmIc.get(icon), null, tint = C.muted, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = C.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { clip.setText(AnnotatedString(value)); copied() }.semantics { contentDescription = copyLabel }.testTag(tag), contentAlignment = Alignment.Center) {
            Icon(Ic.get("copy"), null, tint = C.blue, modifier = Modifier.size(19.dp))
        }
    }
}

private fun dial(ctx: android.content.Context, phone: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.replace(" ", "")))) }
private fun sms(ctx: android.content.Context, phone: String, body: String = "") = runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phone.replace(" ", ""))).apply { if (body.isNotBlank()) putExtra("sms_body", body) }) }
private fun whatsapp(ctx: android.content.Context, phone: String, home: String, body: String = "") =
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + (if (phone.isNotBlank()) waNumber(phone, home) else "") + (if (body.isNotBlank()) "?text=" + Uri.encode(body) else "")))) }

/** A vCard for "Share contact". */
internal fun vcard(name: String, phone: String, email: String, role: String): String = buildString {
    append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
    append("FN:").append(name.replace(",", "\\,")).append("\r\n")
    val parts = name.trim().split(Regex("\\s+"))
    append("N:").append(if (parts.size > 1) parts.last() else "").append(";").append(if (parts.size > 1) parts.dropLast(1).joinToString(" ") else parts.first()).append(";;;\r\n")
    append("ORG:RAFIKI Outreach\r\n")
    if (role.isNotBlank()) append("TITLE:").append(role.replace(",", "\\,")).append("\r\n")
    if (phone.isNotBlank()) append("TEL;TYPE=CELL:").append(phone).append("\r\n")
    if (email.isNotBlank()) append("EMAIL:").append(email).append("\r\n")
    append("END:VCARD\r\n")
}

private fun shareContact(ctx: android.content.Context, p: Person) {
    val text = listOf(p.name, p.role, p.phone, p.email).filter { it.isNotBlank() }.joinToString("\n")
    runCatching {
        val dir = java.io.File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = java.io.File(dir, p.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "contact" } + ".vcf")
        f.writeText(vcard(p.name, p.phone, p.email, p.role))
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "org.rafikioutreach.pin.files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/x-vcard").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), t("Share contact", "Shiriki mawasiliano")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), t("Share contact", "Shiriki mawasiliano")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** The one-step sheets the menu opens. */
private enum class CtSub { NAME, EMAIL, PHONE, MYPHONE, HOME, ROLE, INVITE, NEWPIN, RESETPIN, SETPIN, ADMIN, UNADMIN, BLOCK, UNBLOCK, SIGNOUT, REMOVE, MOVE, GIVEPIN, JOBS }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactSheet(m: AppModel, who: String, close: () -> Unit, go: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val snap by m.snap.collectAsState()
    val act by m.activity.collectAsState()
    val iAmAdmin = snap?.admin == true
    var name by remember { mutableStateOf(who) }
    var tick by remember { mutableIntStateOf(0) }
    var person by remember { mutableStateOf<Person?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<CtSub?>(null) }
    var said by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(said) { if (said != null) { kotlinx.coroutines.delay(2500); said = null } }
    LaunchedEffect(Unit) { launch { m.loadActivity() } }
    LaunchedEffect(tick, name) {
        val list: List<Person>? = if (iAmAdmin) {
            val r = m.api.adminGet("team"); r.json.optJSONArray("team")?.let { a -> (0 until a.length()).map { Person.from(a.getJSONObject(it)) } }
        } else {
            val r = m.api.team(); r.json.optJSONArray("team")?.let { a -> (0 until a.length()).map { Person.from(a.getJSONObject(it)) } }
        }
        val found = list?.firstOrNull { it.isCalled(name) }
        // your own: how you sign in comes with the list (Me › Settings) when the team list doesn't say
        val mine = found != null && found.isCalled(m.prefs.name)
        person = (if (mine && found!!.src.isBlank()) found.copy(src = snap?.me?.kind?.takeIf { it in setOf("app", "cloudflare", "monday") } ?: "app") else found)
            ?: person?.takeIf { it.isCalled(name) } ?: Person(name, known = false)
        loaded = true
        if (iAmAdmin) { val r = m.api.adminGet("teammap"); if (r.ok) sharing = TeamMapData.from(r.json).people.any { person?.isCalled(it.name) == true } }
    }
    val me = name.trim().equals(m.prefs.name.trim(), ignoreCase = true) || person?.isCalled(m.prefs.name) == true
    val p = person ?: Person(name, known = false)
    val changed = { tick++; Contact.changed++ }
    TeamSubSheet("ct_sheet", close, x = false) { hide ->
        fun leave(then: () -> Unit) = hide { then() }
        // the header: picture, name, role badges, status, e-mail and phone (each with copy)
        // a soft banner behind the picture (the Figma design)
        val live = p.blocked == null && p.lastSeen != null && runCatching { java.time.Instant.parse(p.lastSeen).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == java.time.LocalDate.now() }.getOrDefault(false)
        val dot = when { p.blocked != null -> Color(0xFFD93A2E); live -> Color(0xFF22B566); p.lastSeen != null -> Color(0xFFF0A630); else -> Color(0xFFAEB8C1) }
        Box(Modifier.fillMaxWidth()) {
            if (C.glassy) Box(Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(22.dp))
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(C.hue("green").bg, C.hue("teal").bg, C.hue("blue").bg))).testTag("ct_banner"))
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    Box(Modifier.size(104.dp).clip(CircleShape).background(C.card).padding(4.dp).then(if (live) Modifier.border(3.dp, Color(0xFF22B566), CircleShape).padding(3.dp) else Modifier), contentAlignment = Alignment.Center) { PersonAvatar(p.name, if (live) 90.dp else 96.dp) }
                    if (p.known) Box(Modifier.align(Alignment.BottomEnd).offset((-4).dp, (-4).dp).size(22.dp).clip(CircleShape).background(C.card).padding(3.dp).clip(CircleShape).background(dot).testTag("ct_dot"))
                    if (p.admin) Box(Modifier.align(Alignment.TopStart).size(30.dp).clip(CircleShape).background(C.card).border(1.dp, C.line, CircleShape).padding(6.dp)) {
                        Icon(Ic.crownFill, t("Admin", "Msimamizi"), tint = if (C.dark) Color(0xFFF2C14E) else Color(0xFFE0A100), modifier = Modifier.fillMaxSize())
                    }
                }
                Text(p.name, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp, start = 36.dp, end = 36.dp).testTag("ct_name"))
                if (p.role.isNotBlank()) Text(p.role, style = MaterialTheme.typography.bodyMedium, color = C.text, textAlign = TextAlign.Center, modifier = Modifier.testTag("ct_role"))
                FlowRow(Modifier.padding(top = 8.dp).testTag("ct_badges"), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (p.known) {
                        if (p.admin) CtBadge(t("Admin", "Msimamizi"), "amber", crown = true, tag = "ct_b_admin") else CtBadge(t("Field", "Uwanjani"), "slate", "people", tag = "ct_b_field")
                        when (p.src) {
                            "app" -> CtBadge(t("PIN from app", "PIN ya programu"), "green", "key", tag = "ct_b_app")
                            "cloudflare" -> CtBadge(t("PIN in Cloudflare", "PIN ya Cloudflare"), "purple", "cloud", tag = "ct_b_cf")
                            "monday" -> CtBadge("monday.com", "blue", "people", tag = "ct_b_monday")
                        }
                        if (p.blocked != null) CtBadge(t("Blocked", "Amezuiwa"), "coral", "x", red = true, tag = "ct_b_blocked")
                    }
                }
                val status = when {
                    !loaded -> t("Loading…", "Inapakia…")
                    p.blocked != null && p.blocked.at.isBlank() -> t("Blocked by the office", "Amezuiwa na ofisi")
                    p.blocked != null -> t("Blocked by ${p.blocked.by.ifBlank { "the office" }} on ${Dates.day(p.blocked.at)}", "Amezuiwa na ${p.blocked.by.ifBlank { "ofisi" }} tarehe ${Dates.day(p.blocked.at)}")
                    !p.known -> t("Not on the team list", "Hayupo kwenye orodha ya timu")
                    p.lastSeen != null -> t("Active · last seen ", "Anatumia · alionekana ") + Dates.dateTime(p.lastSeen)
                    p.app -> t("Invited · hasn’t signed in yet", "Amealikwa · bado hajaingia")
                    else -> t("Hasn’t signed in yet", "Bado hajaingia")
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (p.blocked == null && p.lastSeen != null) { BreathDot(Color(0xFF22B566), live, 8.dp); Spacer(Modifier.width(6.dp)) }
                    Text(status, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = if (p.blocked != null) C.bad else if (p.lastSeen != null) C.hue("green").fg else C.muted, textAlign = TextAlign.Center, modifier = Modifier.testTag("ct_status"))
                }
                if (p.blocked == null && p.out != null) Text(t("Signed out of all phones on ", "Ametolewa kwenye simu zote tarehe ") + Dates.day(p.out), style = MaterialTheme.typography.bodySmall, color = C.muted,
                    textAlign = TextAlign.Center, modifier = Modifier.testTag("ct_out"))
                p.blocked?.reason?.takeIf { it.isNotBlank() }?.let { why -> Text("“$why”", style = MaterialTheme.typography.bodySmall, color = C.muted, textAlign = TextAlign.Center) }
            }
            Box(Modifier.align(Alignment.TopEnd)) { SheetCloseX("ct_x") { hide {} } }
        }
        if (p.email.isNotBlank() || p.phone.isNotBlank()) Column(Modifier.padding(top = 10.dp)) {
            if (p.email.isNotBlank()) CtLine("mail", p.email, t("Copy e-mail", "Nakili barua pepe"), "ct_copy_email") { said = "ok" to t("E-mail copied ✓", "Barua pepe imenakiliwa ✓") }
            if (p.phone.isNotBlank()) CtLine("phone", p.phone, t("Copy phone", "Nakili simu"), "ct_copy_phone") { said = "ok" to t("Phone copied ✓", "Simu imenakiliwa ✓") }
        }
        said?.let { (tone, text) -> Box(Modifier.testTag("ct_msg")) { Msg(text, tone) } }

        // Contact: Call, WhatsApp, SMS, E-mail and Share as big tiles (the Figma design)
        if (!me) {
            Row(Modifier.padding(top = 16.dp).fillMaxWidth().testTag("ct_g_contact"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.phone.isNotBlank()) {
                    CtTile("ct_call", t("Call", "Piga"), Color(0xFF1C7C4A), Color.White, icon = "phone") { dial(ctx, p.phone) }
                    CtTile("ct_wa", "WhatsApp", WhatsAppGreen, Color.White, icon = "chat", brand = "whatsapp") { whatsapp(ctx, p.phone, p.home) }
                    CtTile("ct_sms", "SMS", C.hue("teal").bg, C.hue("teal").fg, icon = "text") { sms(ctx, p.phone) }
                }
                if (p.email.isNotBlank() && (iAmAdmin || p.phone.isBlank())) CtTile("ct_mail", t("E-mail", "Barua pepe"), C.hue("blue").bg, C.hue("blue").fg, icon = "mail") {
                    if (iAmAdmin) leave { MailNav.preset = MailPreset("pick", picks = listOf("t:" + p.name.lowercase())); go("office", "mail") }
                    else runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + p.email))) }
                }
                CtTile("ct_share", t("Share", "Shiriki"), C.card, C.ink, icon = "share") { shareContact(ctx, p) }
            }
            if (p.email.isBlank() && p.phone.isBlank()) Text(t("No e-mail or phone on the team list for them yet.", "Bado hakuna barua pepe wala simu yake kwenye orodha ya timu."), style = MaterialTheme.typography.bodySmall, color = C.muted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("ct_nocontact"))
        }

        // Details: admins (app people), or the person themselves; others see them read-only
        val canEdit = p.known && p.app && (iAmAdmin || me)
        // your own entry when your PIN isn't from the app: name and e-mail are the office's; the phone is yours (profile)
        val selfFixed = me && p.known && !p.app
        if (p.known) CtGroup(t("Details", "Maelezo"), "ct_g_details") {
            if (canEdit) {
                CtRow("ct_name_row", "edit", "blue", t("Name", "Jina"), p.name) { sub = CtSub.NAME }
                CtRow("ct_email_row", "mail", "teal", if (p.email.isBlank()) t("Add e-mail", "Ongeza barua pepe") else t("Change e-mail", "Badilisha barua pepe"), p.email.ifBlank { t("None yet", "Bado hakuna") }) { sub = CtSub.EMAIL }
                CtRow("ct_phone_row", "phone", "near", if (p.phone.isBlank()) t("Add phone", "Ongeza simu") else t("Change phone", "Badilisha simu"), p.phone.ifBlank { t("None yet", "Bado hakuna") }) { sub = CtSub.PHONE }
                if (iAmAdmin) {
                    CtRow("ct_home_row", "pin", "green", t("Home country", "Nchi ya nyumbani"), COUNTRIES[p.home] ?: t("All countries", "Nchi zote")) { sub = CtSub.HOME }
                    CtRow("ct_role_row", "people", "slate", t("Role or title", "Cheo au kazi"), p.role.ifBlank { t("Not set (optional)", "Haijawekwa (si lazima)") }) { sub = CtSub.ROLE }
                }
            } else if (selfFixed) {
                CtRow("ct_name_ro", "edit", "blue", t("Name", "Jina"), p.name, onClick = null)
                CtRow("ct_email_ro", "mail", "teal", t("E-mail", "Barua pepe"), p.email.ifBlank { t("None yet", "Bado hakuna") }, onClick = null)
                Text(t("Set by the office", "Imewekwa na ofisi"), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("ct_setby"))
                CtRow("ct_phone_row", "phone", "near", if (p.phone.isBlank()) t("Add phone", "Ongeza simu") else t("Change phone", "Badilisha simu"), p.phone.ifBlank { t("None yet", "Bado hakuna") }) { sub = CtSub.MYPHONE }
            } else {
                CtRow("ct_home_ro", "pin", "green", t("Home country", "Nchi ya nyumbani"), COUNTRIES[p.home] ?: t("All countries", "Nchi zote"), onClick = null)
                if (p.role.isNotBlank()) CtRow("ct_role_ro", "people", "slate", t("Role or title", "Cheo au kazi"), p.role, onClick = null)
                if (iAmAdmin && p.cloudflare && !me) Text(t("Their PIN is set in Cloudflare, so their details can’t be changed here. Move them to an app PIN below to manage them here.", "PIN yake imewekwa kwenye Cloudflare, kwa hiyo maelezo yake hayawezi kubadilishwa hapa. Mhamishe kwenye PIN ya programu hapa chini ili umsimamie hapa."),
                    style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("ct_cf_note"))
            }
            if (me) CtRow("ct_mypin", "key", "amber", t("Change my PIN", "Badilisha PIN yangu"), t("In Settings", "Kwenye Mipangilio")) { leave { go("settings", "pin") } }
        }

        // Access: admins only
        if (iAmAdmin && p.known) CtGroup(t("Access", "Ufikiaji"), "ct_g_access") {
            when {
                p.app -> {
                    CtRow("ct_invite", "share", "purple", t("Send app invite", "Tuma mwaliko wa programu"), t("E-mail, WhatsApp or SMS. Never with the PIN.", "Barua pepe, WhatsApp au SMS. Kamwe pamoja na PIN.")) { sub = CtSub.INVITE }
                    CtRow("ct_newpin", "key", "green", t("New PIN", "PIN mpya"), t("Makes one and shows it once", "Inatengeneza moja na kuionyesha mara moja")) { sub = CtSub.NEWPIN }
                    CtRow("ct_setpin", "lock", "teal", t("Set a PIN", "Weka PIN"), t("You type 4 to 8 digits", "Unaandika tarakimu 4 hadi 8")) { sub = CtSub.SETPIN }
                    if (p.alsoCloudflare) CtRow("ct_alsocf", "cloud", "purple", t("Also in Cloudflare", "Pia yuko kwenye Cloudflare"), t("Their old entry there can be removed", "Ingizo lake la zamani huko linaweza kuondolewa"), onClick = null)
                    CtRow("ct_resetpin", "refresh", "blue", t("Reset and send a new PIN", "Badilisha na utume PIN mpya"), t("If they forgot it: by WhatsApp or SMS", "Kama ameisahau: kwa WhatsApp au SMS")) { sub = CtSub.RESETPIN }
                    if (!me && p.adminCf) CtRow("ct_admincf", "crown", "amber", t("Admin set in Cloudflare", "Msimamizi kupitia Cloudflare"), t("Can’t be made a field officer in the app", "Hawezi kufanywa afisa wa uwanjani kwenye programu"), onClick = null)
                    else if (!me) CtRow(if (p.admin) "ct_unadmin" else "ct_admin", "crown", "amber", if (p.admin) t("Remove admin", "Ondoa usimamizi") else t("Make admin", "Mfanye msimamizi"),
                        if (p.admin) t("They keep their PIN", "Anabaki na PIN yake") else t("They’ll see the Office", "Ataona Ofisi")) { sub = if (p.admin) CtSub.UNADMIN else CtSub.ADMIN }
                }
                p.cloudflare -> {
                    CtRow("ct_cf", "cloud", "purple", t("PIN set in Cloudflare", "PIN imewekwa kwenye Cloudflare"), t("Can’t be changed from the app", "Haiwezi kubadilishwa kwenye programu"), onClick = null)
                    CtRow("ct_move", "key", "green", t("Move to an app PIN", "Hamia PIN ya programu"), t("Then you can manage them here", "Kisha unaweza kumsimamia hapa")) { sub = CtSub.MOVE }
                }
                else -> CtRow("ct_givepin", "key", "green", t("Give them an app PIN", "Mpe PIN ya programu"), t("They sign in with monday.com now", "Kwa sasa anaingia kwa monday.com")) { sub = CtSub.GIVEPIN }
            }
            if (!me) {
                if (p.blocked == null) CtRow("ct_block", "x", "coral", t("Block", "Zuia"), t("Stops their PIN and signs them out", "Inasimamisha PIN yake na kumtoa"), red = true) { sub = CtSub.BLOCK }
                else CtRow("ct_unblock", "check", "green", t("Unblock", "Ondoa zuio"), t("The same PIN works again", "PIN ileile itafanya kazi tena")) { sub = CtSub.UNBLOCK }
            }
            CtRow("ct_signout", "upload", "slate", t("Sign out of all phones", "Toa kwenye simu zote"), if (me) t("Including this one. Your PIN stays.", "Pamoja na hii. PIN yako inabaki.") else t("Keeps their PIN", "PIN yake inabaki")) { sub = CtSub.SIGNOUT }
            if (!me && p.app) CtRow("ct_remove", "trash", "coral", t("Remove from the team", "Ondoa kwenye timu"), t("Their history is kept", "Historia yake inabaki"), red = true) { sub = CtSub.REMOVE }
        }

        // Their work
        CtGroup(if (me) t("Your work", "Kazi yako") else t("Their work", "Kazi yake"), "ct_g_work") {
            Column(Modifier.padding(horizontal = 10.dp)) { PersonActivity(p.names(), act, snap, label = false) { id -> leave { go("project", id) } } }
            if (iAmAdmin && p.known) CtRow("ct_assign", "cal", "blue", t("Assign a visit", "Gawa ziara"), t("Plan with them chosen", "Panga ukiwa umemchagua")) { leave { TeamGo.assign = p.name; go("office", "visits") } }
            val jobs = snap?.repairs?.filter { (_, r) -> p.isCalled(r.assignee) }.orEmpty()
            CtRow("ct_jobs", "wrench", "repairs", t("Repair jobs assigned", "Kazi za matengenezo alizopewa"), if (jobs.isEmpty()) t("None open", "Hakuna iliyo wazi") else if (jobs.size == 1) t("1 open", "1 iko wazi") else t("${jobs.size} open", "${jobs.size} ziko wazi"),
                onClick = if (jobs.isEmpty()) null else ({ sub = CtSub.JOBS }))
            if (iAmAdmin && sharing) CtRow("ct_map", "pin", "green", t("Show on team map", "Onyesha kwenye ramani ya timu"), t("Sharing their location now", "Anashiriki mahali alipo sasa")) { leave { TeamGo.map = p.name; go("office", "teammap") } }
        }

        // the small sheets, one step each; back or X closes only that one
        val s = sub
        val done = { sub = null }
        when (s) {
            CtSub.NAME -> FieldSheet(m, p, "name", me, iAmAdmin, done) { to -> said = "ok" to t("Renamed ✓", "Jina limebadilishwa ✓"); if (me) m.prefs.name = to; name = to; changed() }
            CtSub.EMAIL -> FieldSheet(m, p, "email", me, iAmAdmin, done) { said = "ok" to t("Saved ✓", "Imehifadhiwa ✓"); changed() }
            CtSub.PHONE -> FieldSheet(m, p, "phone", me, iAmAdmin, done) { said = "ok" to t("Saved ✓", "Imehifadhiwa ✓"); changed() }
            CtSub.MYPHONE -> FieldSheet(m, p, "phone", me, iAmAdmin, done, profile = true) { said = "ok" to t("Saved ✓", "Imehifadhiwa ✓"); changed() }
            CtSub.ROLE -> FieldSheet(m, p, "role", me, iAmAdmin, done) { said = "ok" to t("Saved ✓", "Imehifadhiwa ✓"); changed() }
            CtSub.HOME -> HomeSheet(m, p, done) { said = "ok" to t("Saved ✓", "Imehifadhiwa ✓"); changed() }
            CtSub.INVITE -> InviteSheet(m, p.json(), done)
            CtSub.NEWPIN, CtSub.RESETPIN -> NewPinSheet(m, p, s == CtSub.RESETPIN, done)
            CtSub.SETPIN -> SetPinSheet(m, p, done)
            CtSub.ADMIN, CtSub.UNADMIN -> AdminConfirmSheet(m, p.json(), s == CtSub.ADMIN, done) { said = "ok" to (if (s == CtSub.ADMIN) t("${p.name} is now an admin ✓", "${p.name} sasa ni msimamizi ✓") else t("${p.name} is no longer an admin ✓", "${p.name} si msimamizi tena ✓")); changed() }
            CtSub.BLOCK -> AskSheet(m, p, "team-block", done) { said = "ok" to t("${p.name} is blocked", "${p.name} amezuiwa"); changed() }
            CtSub.UNBLOCK -> AskSheet(m, p, "team-unblock", done) { said = "ok" to t("${p.name} can sign in again ✓", "${p.name} anaweza kuingia tena ✓"); changed() }
            CtSub.SIGNOUT -> AskSheet(m, p, "team-signout", done) { said = "ok" to t("Signed out of all phones ✓", "Ametolewa kwenye simu zote ✓"); changed() }
            CtSub.REMOVE -> AskSheet(m, p, "team-remove", done) { changed(); hide {} }
            CtSub.MOVE -> AskSheet(m, p, "team-move", done, keepOpen = true) { changed() }
            CtSub.GIVEPIN -> AskSheet(m, p, "team-add", done, keepOpen = true) { changed() }
            CtSub.JOBS -> JobsSheet(m, p, done) { id -> sub = null; leave { go("project", id) } }
            null -> {}
        }
    }
}

/** The small sheet's head: an icon tile and the title, centred. */
@Composable
private fun SubHead(icon: String, hue: String, title: String, sub: String = "", red: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val hu = C.hue(hue)
        if (icon == "crown") Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(hu.bg).padding(13.dp)) { Icon(Ic.crownFill, null, tint = hu.fg, modifier = Modifier.fillMaxSize()) }
        else DqmTile(icon, if (red) C.bad else hu.fg, if (red) C.badBg else hu.bg, 56.dp)
        Text(title, style = MaterialTheme.typography.titleLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
    }
}

/** team-update with everything the server keeps (it replaces the whole entry), changing only [field]. */
private fun updateBody(p: Person, field: String, value: String, me: Boolean, admin: Boolean): JSONObject {
    val b = JSONObject().put("action", "team-update").put("name", p.name)
    if (admin) b.put("home", p.home).put("admin", p.admin).put("email", p.email).put("phone", p.phone).put("role", p.role)
    else { b.put("email", p.email).put("phone", p.phone) } // a person editing their own e-mail and phone
    b.put(field, value)
    return b
}

/** One field, Save, and (for e-mail and phone) Remove: name, e-mail, phone, or role/title. */
@Composable
private fun FieldSheet(m: AppModel, p: Person, field: String, me: Boolean, admin: Boolean, close: () -> Unit, profile: Boolean = false, saved: (String) -> Unit) {
    val scope = rememberScope()
    val start = when (field) { "name" -> p.name; "email" -> p.email; "phone" -> p.phone; else -> p.role }
    var v by remember { mutableStateOf(start) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    TeamSubSheet("ct_field_sheet", close) { hide ->
        when (field) {
            "name" -> SubHead("edit", "blue", t("Rename", "Badilisha jina"), t("Their pins, visits and repair jobs stay linked to them.", "Mahali, ziara na kazi zake za matengenezo zinabaki zimeunganishwa naye."))
            "email" -> SubHead("mail", "teal", if (start.isBlank()) t("Add e-mail", "Ongeza barua pepe") else t("Change e-mail", "Badilisha barua pepe"), t("For alerts, invites and e-mails from the Office.", "Kwa taarifa, mialiko na barua pepe kutoka Ofisi."))
            "phone" -> SubHead("phone", "near", if (start.isBlank()) t("Add phone", "Ongeza simu") else t("Change phone", "Badilisha simu"), t("The team sees it, for calls and WhatsApp.", "Timu inaiona, kwa kupiga na WhatsApp."))
            else -> SubHead("people", "slate", t("Role or title", "Cheo au kazi"), t("Optional. Shown under their name.", "Si lazima. Inaonyeshwa chini ya jina lake."))
        }
        Box(Modifier.testTag("ct_field")) {
            Field(v, { v = it; msg = null }, when (field) { "name" -> t("Name", "Jina"); "email" -> t("E-mail", "Barua pepe"); "phone" -> t("Phone", "Simu"); else -> t("Role or title", "Cheo au kazi") },
                placeholder = when (field) { "name" -> t("e.g. Grace Wanjiru", "mf. Grace Wanjiru"); "email" -> "name@rafikioutreach.org"; "phone" -> "+254 712 345 678"; else -> t("e.g. Field officer, Kitale", "mf. Afisa wa uwanjani, Kitale") },
                phone = field == "phone")
        }
        fun send(value: String) {
            val x = value.trim()
            if (field == "name" && x.isBlank()) { msg = t("Type a name.", "Andika jina."); return }
            if (field == "email" && x.isNotBlank() && !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(x)) { msg = t("That doesn’t look like an e-mail address.", "Hiyo haionekani kama anwani ya barua pepe."); return }
            if (field == "phone" && x.isNotBlank() && x.count { it.isDigit() } < 7) { msg = t("That phone number looks too short.", "Namba hiyo ya simu inaonekana fupi mno."); return }
            if (x == start) { hide {}; return }
            busy = true; msg = null
            scope.launch {
                // your own phone when your PIN isn't from the app: Me › Settings › Personal details (profile)
                if (profile) {
                    val me0 = m.snap.value?.me
                    val err = m.saveProfile(me0?.nick.orEmpty(), x, me0?.home?.ifBlank { p.home } ?: p.home)
                    busy = false
                    if (err == null) hide { saved(x) } else msg = err
                    return@launch
                }
                val r: Reply = if (field == "name") m.api.admin(JSONObject().put("action", "team-rename").put("name", p.name).put("to", x))
                    else m.api.admin(updateBody(p, field, x, me, admin))
                busy = false
                if (r.ok) hide { saved(x) } else msg = contactWhy(r.reason)
            }
        }
        Btn(t("Save", "Hifadhi"), { send(v) }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("ct_field_save"), icon = "check", enabled = !busy && v.trim() != start.trim())
        if ((field == "email" || field == "phone") && start.isNotBlank())
            DqmLineBtn(if (field == "email") t("Remove e-mail", "Ondoa barua pepe") else t("Remove phone", "Ondoa simu"), "trash", { send("") }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("ct_field_clear"), red = true, enabled = !busy)
        Box(Modifier.testTag("ct_field_msg")) { Msg(msg, "bad") }
    }
}

/** Home country: a short list to tap, then Save. */
@Composable
private fun HomeSheet(m: AppModel, p: Person, close: () -> Unit, saved: () -> Unit) {
    val scope = rememberScope()
    var v by remember { mutableStateOf(p.home) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    TeamSubSheet("ct_home_sheet", close) { hide ->
        SubHead("pin", "green", t("Home country", "Nchi ya nyumbani"), t("Their map and lists start in this country.", "Ramani na orodha zao zinaanzia nchi hii."))
        Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, C.line, RoundedCornerShape(16.dp))) {
            (listOf("" to t("All countries", "Nchi zote")) + COUNTRIES.toList()).forEach { (k, l) ->
                Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { v = k }.heightIn(min = 52.dp).padding(horizontal = 14.dp).testTag("ct_home_" + k.ifBlank { "all" }), verticalAlignment = Alignment.CenterVertically) {
                    Text(l, style = MaterialTheme.typography.bodyLarge, color = C.ink, modifier = Modifier.weight(1f))
                    if (v == k) Icon(Ic.get("check"), null, tint = C.blue, modifier = Modifier.size(20.dp))
                }
            }
        }
        Btn(t("Save", "Hifadhi"), {
            busy = true; msg = null
            scope.launch {
                val r = m.api.admin(updateBody(p, "home", v, false, true))
                busy = false
                if (r.ok) hide { saved() } else msg = contactWhy(r.reason)
            }
        }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("ct_home_save"), icon = "check", enabled = !busy && v != p.home)
        Msg(msg, "bad")
    }
}

/** New PIN (or "Reset and send a new PIN"): asks, then shows it once with Copy, WhatsApp and SMS. */
@Composable
private fun NewPinSheet(m: AppModel, p: Person, reset: Boolean, close: () -> Unit) {
    val scope = rememberScope()
    var pin by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    TeamSubSheet("ct_newpin_sheet", close) { hide ->
        SubHead(if (reset) "refresh" else "key", if (reset) "blue" else "green", if (reset) t("Reset and send a new PIN", "Badilisha na utume PIN mpya") else t("New PIN for ${p.name}", "PIN mpya ya ${p.name}"))
        val pn = pin
        if (pn == null) {
            CtLines(if (reset) listOf(
                t("PINs are stored locked, so nobody can look up their old one.", "PIN zinahifadhiwa zikiwa zimefungwa, kwa hiyo hakuna anayeweza kuona ya zamani."),
                t("This makes a new PIN. Their old PIN stops working.", "Hii inatengeneza PIN mpya. PIN ya zamani itaacha kufanya kazi."),
                t("Then send it to them by WhatsApp or SMS.", "Kisha mtumie kwa WhatsApp au SMS."),
            ) else listOf(
                t("Their old PIN stops working.", "PIN ya zamani itaacha kufanya kazi."),
                t("The new PIN shows once, for a minute. Send it to them privately.", "PIN mpya inaonyeshwa mara moja, kwa dakika moja. Mtumie kwa siri."),
            ))
            CtYesNo(if (reset) t("Make and send a new PIN", "Tengeneza na utume PIN mpya") else t("Make a new PIN", "Tengeneza PIN mpya"), busy, false, { hide {} }) {
                busy = true; msg = null
                scope.launch { val r = m.api.admin(JSONObject().put("action", "team-pin").put("name", p.name)); busy = false; if (r.ok) pin = r.json.optString("pin") else msg = contactWhy(r.reason) }
            }
            Msg(msg, "bad")
        } else {
            PinShown(p.name, pn, p.phone, p.home)
            Btn(t("Done", "Imekamilika"), { hide {} }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("ct_pin_done"), light = true)
        }
    }
}

/** Set a PIN: the admin types 4 to 8 digits; easy ones and one already in use are refused. */
@Composable
private fun SetPinSheet(m: AppModel, p: Person, close: () -> Unit) {
    val scope = rememberScope()
    var v by remember { mutableStateOf("") }
    var set by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    TeamSubSheet("ct_setpin_sheet", close) { hide ->
        SubHead("lock", "teal", t("Set a PIN for ${p.name}", "Weka PIN ya ${p.name}"))
        val done = set
        if (done == null) {
            Text(t("4 to 8 digits, not easy to guess: no 1234, 0000 or one digit over and over. Their old PIN stops working.", "Tarakimu 4 hadi 8, isiwe rahisi kubahatisha: si 1234, 0000 wala tarakimu moja inayojirudia. PIN ya zamani itaacha kufanya kazi."),
                style = MaterialTheme.typography.bodyMedium, color = C.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            Box(Modifier.testTag("ct_setpin_field")) { Field(v, { v = it.filter { c -> c.isDigit() }.take(8); msg = null }, t("New PIN", "PIN mpya"), number = true, placeholder = "4–8") }
            Btn(t("Set PIN", "Weka PIN"), {
                val bad = pinProblem(v)
                if (bad != null) { msg = contactWhy(bad); return@Btn }
                busy = true; msg = null
                scope.launch {
                    val r = m.api.admin(JSONObject().put("action", "team-set-pin").put("name", p.name).put("pin", v.trim()))
                    busy = false
                    if (r.ok) set = v.trim() else msg = contactWhy(r.reason)
                }
            }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("ct_setpin_go"), icon = "check", enabled = !busy && v.length >= 4)
            Box(Modifier.testTag("ct_setpin_msg")) { Msg(msg, "bad") }
        } else {
            Text(t("PIN set ✓", "PIN imewekwa ✓"), style = MaterialTheme.typography.titleMedium, color = C.ok, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("ct_setpin_ok"))
            PinShown(p.name, done, p.phone, p.home)
            Btn(t("Done", "Imekamilika"), { hide {} }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("ct_pin_done"), light = true)
        }
    }
}

@Composable
private fun CtLines(lines: List<String>, red: Boolean = false, icon: String = "check") {
    Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (red) C.badBg else C.hue("slate").bg).padding(14.dp).testTag("ct_lines")) {
        lines.forEach { l ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                Icon(DqmIc.get(icon), null, tint = C.ink, modifier = Modifier.padding(top = 2.dp).size(16.dp)); Spacer(Modifier.width(8.dp))
                Text(l, style = MaterialTheme.typography.bodyMedium, color = C.ink)
            }
        }
    }
}

@Composable
private fun CtYesNo(yes: String, busy: Boolean, red: Boolean, no: () -> Unit, go: () -> Unit) {
    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn(t("Cancel", "Ghairi"), no, Modifier.weight(1f).testTag("ct_no"), light = true, small = true)
        Btn(yes, go, Modifier.weight(1f).testTag("ct_yes"), small = true, enabled = !busy, color = if (red) C.bad else null)
    }
}

/** Block, unblock, sign out of all phones, remove, move to an app PIN, give an app PIN: says what happens, then does it. */
@Composable
private fun AskSheet(m: AppModel, p: Person, action: String, close: () -> Unit, keepOpen: Boolean = false, done: () -> Unit) {
    val scope = rememberScope()
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }
    val me = p.isCalled(m.prefs.name)
    TeamSubSheet("ct_ask_sheet", close) { hide ->
        val red = action == "team-block" || action == "team-remove"
        when (action) {
            "team-block" -> SubHead("x", "coral", t("Block ${p.name}?", "Mzuie ${p.name}?"), red = true)
            "team-unblock" -> SubHead("check", "green", t("Unblock ${p.name}?", "Mwondolee ${p.name} zuio?"))
            "team-signout" -> SubHead("upload", "slate", if (me) t("Sign out of all your phones?", "Toka kwenye simu zako zote?") else t("Sign ${p.name} out of all phones?", "Mtoe ${p.name} kwenye simu zote?"))
            "team-remove" -> SubHead("trash", "coral", t("Remove ${p.name} from the team?", "Mwondoe ${p.name} kwenye timu?"), red = true)
            "team-move" -> SubHead("key", "green", t("Move ${p.name} to an app PIN?", "Mhamishe ${p.name} kwenye PIN ya programu?"))
            else -> SubHead("key", "green", t("Give ${p.name} an app PIN?", "Mpe ${p.name} PIN ya programu?"))
        }
        val pn = pin
        if (pn != null) {
            if (action == "team-move") Text(t("Done ✓ The entry for ${p.name} in Cloudflare can now be removed.", "Imekamilika ✓ Ingizo la ${p.name} kwenye Cloudflare sasa linaweza kuondolewa."),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.ok, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("ct_moved"))
            PinShown(p.name, pn, p.phone, p.home)
            Btn(t("Done", "Imekamilika"), { hide {} }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("ct_pin_done"), light = true)
            return@TeamSubSheet
        }
        if (finished) return@TeamSubSheet
        CtLines(when (action) {
            "team-block" -> listOf(
                t("Their PIN stops working and they’re signed out on every phone straight away.", "PIN yake itaacha kufanya kazi na atatolewa kwenye kila simu mara moja."),
                t("Their history is kept, and they stay on the team list marked Blocked.", "Historia yake inabaki, na anabaki kwenye orodha ya timu akiwa na alama Amezuiwa."),
                t("You can unblock them later; the same PIN works again.", "Unaweza kumwondolea zuio baadaye; PIN ileile itafanya kazi tena."))
            "team-unblock" -> listOf(t("They can sign in again with the same PIN.", "Anaweza kuingia tena kwa PIN ileile."))
            "team-signout" -> listOf(
                if (me) t("Every phone you’re signed in on is signed out straight away, this one too.", "Kila simu uliyoingia nayo itatoka mara moja, pamoja na hii.") else t("Every phone they’re signed in on is signed out straight away.", "Kila simu aliyoingia nayo itatoka mara moja."),
                if (me) t("Your PIN keeps working, so you can sign in again.", "PIN yako inaendelea kufanya kazi, kwa hiyo unaweza kuingia tena.") else t("Their PIN keeps working, so they can sign in again.", "PIN yake inaendelea kufanya kazi, kwa hiyo anaweza kuingia tena."))
            "team-remove" -> listOf(
                t("Their PIN stops working straight away.", "PIN yake itaacha kufanya kazi mara moja."),
                t("Their pins, visits and history are kept.", "Mahali, ziara na historia yake vinabaki."))
            "team-move" -> listOf(
                t("They get a new PIN from the app, shown once. Send it to them privately.", "Atapata PIN mpya ya programu, inaonyeshwa mara moja. Mtumie kwa siri."),
                t("Then you can manage them here. Their entry in Cloudflare can be removed afterwards.", "Kisha unaweza kumsimamia hapa. Ingizo lake kwenye Cloudflare linaweza kuondolewa baadaye."))
            else -> listOf(
                t("They get their own PIN, shown once. Send it to them privately.", "Atapata PIN yake, inaonyeshwa mara moja. Mtumie kwa siri."),
                t("They can still sign in with monday.com too.", "Bado anaweza kuingia kwa monday.com pia."))
        }, red = red, icon = if (red) "x" else "check")
        if (action == "team-block") Box(Modifier.testTag("ct_reason")) { Field(reason, { reason = it }, t("Reason (optional)", "Sababu (si lazima)"), placeholder = t("e.g. phone lost", "mf. simu imepotea")) }
        if (action == "team-move") TechFold()
        CtYesNo(when (action) {
            "team-block" -> t("Block", "Zuia"); "team-unblock" -> t("Unblock", "Ondoa zuio"); "team-signout" -> t("Sign out", "Toa"); "team-remove" -> t("Remove", "Ondoa")
            "team-move" -> t("Move", "Hamisha"); else -> t("Make a PIN", "Tengeneza PIN")
        }, busy, red, { hide {} }) {
            busy = true; msg = null
            scope.launch {
                val body = JSONObject().put("action", action).put("name", p.name)
                if (action == "team-block") body.put("reason", reason.trim())
                if (action == "team-add") body.put("home", p.home).put("admin", p.admin).put("email", p.email).put("phone", p.phone)
                val r = m.api.admin(body)
                busy = false
                if (!r.ok) { msg = contactWhy(r.reason); return@launch }
                val newPin = r.json.optString("pin").takeIf { it.isNotBlank() && it != "null" }
                if (keepOpen && newPin != null) { pin = newPin; done() }
                else { finished = true; hide { done() } }
            }
        }
        Box(Modifier.testTag("ct_ask_msg")) { Msg(msg, "bad") }
    }
}

/** The technical names, folded away (for whoever looks after the website). */
@Composable
private fun TechFold() {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp))) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.heightIn(min = 48.dp).padding(horizontal = 14.dp).testTag("ct_tech"), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Technical details", "Maelezo ya kiufundi"), style = MaterialTheme.typography.labelLarge, color = C.ink, modifier = Modifier.weight(1f))
            Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(16.dp))
        }
        if (open) Text(t("Their name and PIN are in PIN_USERS (admins also in PIN_ADMINS) in the Cloudflare settings. After the move, take them out of there.",
            "Jina na PIN yake viko kwenye PIN_USERS (wasimamizi pia kwenye PIN_ADMINS) kwenye mipangilio ya Cloudflare. Baada ya kuhamisha, viondoe hapo."),
            style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp).testTag("ct_tech_body"))
    }
}

/** The open repair jobs given to them: tap one to open the project. */
@Composable
private fun JobsSheet(m: AppModel, p: Person, close: () -> Unit, open: (String) -> Unit) {
    val snap by m.snap.collectAsState()
    TeamSubSheet("ct_jobs_sheet", close) { _ ->
        SubHead("wrench", "repairs", t("Repair jobs assigned", "Kazi za matengenezo alizopewa"), p.name)
        val s = snap ?: return@TeamSubSheet
        s.repairs.filter { (_, r) -> p.isCalled(r.assignee) }.forEach { (id, r) ->
            val pr = s.projects.firstOrNull { it.id == id }
            Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp)).clickable(role = Role.Button) { open(id) }
                .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp).testTag("ct_job_$id"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pr?.title ?: r.title.ifBlank { id }, style = MaterialTheme.typography.titleSmall, color = C.ink)
                    Text(listOfNotNull(org.rafikioutreach.pin.data.stateName(if (r.state == "notworking") "notworking" else "repair"), r.due.takeIf { it.isNotBlank() }?.let { t("due ", "mwisho ") + Dates.due(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = C.muted)
                }
                Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}
