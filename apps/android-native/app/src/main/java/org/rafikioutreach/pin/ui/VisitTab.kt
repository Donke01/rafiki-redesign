package org.rafikioutreach.pin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.Outcome
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.OutFile
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.STATES
import org.rafikioutreach.pin.data.URGENCY
import org.rafikioutreach.pin.data.stateName
import org.rafikioutreach.pin.data.urgencyName
import org.rafikioutreach.pin.t
import java.time.Instant

/** Checklist items: English (what monday.com shows) and Kiswahili. Same as the web app. */
private val ITEMS = mapOf(
    "handle" to ("Pump handle and pivot pins" to "Mkono wa pampu na pini zake"), "rods" to ("Chain or rods, pump head" to "Mnyororo au fimbo, kichwa cha pampu"),
    "spout" to ("Spout and water flow" to "Mdomo wa bomba na mtiririko wa maji"), "pumpworks" to ("Pump works" to "Pampu inafanya kazi"),
    "apron" to ("Concrete apron or platform (no cracks)" to "Sakafu ya saruji (bila nyufa)"), "drain" to ("Drainage and soak pit" to "Mfereji na shimo la maji machafu"),
    "fence" to ("Fence and gate" to "Uzio na lango"), "lock" to ("Lock and opening hours kept" to "Kufuli na saa za kufungua zinafuatwa"), "clean" to ("Area clean, no standing water" to "Eneo ni safi, hakuna maji yaliyosimama"),
    "power" to ("Power supply (mains or generator)" to "Umeme (KPLC au jenereta)"), "panels" to ("Solar panels clean and undamaged" to "Paneli za sola ni safi na nzima"),
    "pumpruns" to ("Pump starts and runs smoothly" to "Pampu inawaka na kufanya kazi vizuri"), "control" to ("Control box and wiring" to "Kisanduku cha kudhibiti na nyaya"),
    "tank" to ("Storage tank and tower" to "Tangi la maji na mnara wake"), "taps" to ("Taps and water kiosk" to "Mabomba na kioski cha maji"),
    "pipes" to ("Pipes and joints (no leaks)" to "Mabomba na viungo (hakuna kuvuja)"), "meter" to ("Water meter" to "Mita ya maji"),
    "gutters" to ("Gutters and downpipes" to "Mifereji ya paa na mabomba ya kushusha maji"), "firstflush" to ("First-flush diverter" to "Kifaa cha kuondoa maji ya kwanza"),
    "tankwalls" to ("Tank walls (no cracks or leaks)" to "Kuta za tangi (bila nyufa wala kuvuja)"), "tap" to ("Tap" to "Bomba"), "lid" to ("Tank cover and inlet screen" to "Kifuniko cha tangi na chujio"),
    "base" to ("Tank base or stand" to "Msingi wa tangi"), "waterpoint" to ("Water point works" to "Kituo cha maji kinafanya kazi"),
    "handwash" to ("Handwashing station (water and soap)" to "Kituo cha kunawa mikono (maji na sabuni)"), "latrines" to ("Latrines clean and usable" to "Vyoo ni safi na vinatumika"),
    "storage" to ("Water storage tank" to "Tangi la kuhifadhi maji"), "roof" to ("Roof and building" to "Paa na jengo"),
)

private fun kind(p: Project): String {
    val ty = p.type.lowercase(); val pump = p.pump.lowercase()
    return when {
        "borehole" in ty -> when (pump) { "manual" -> "manual"; "solar" -> "solar"; "electric" -> "electric"; else -> "borehole" }
        "collection" in ty -> "tankk"
        else -> "building"
    }
}
private val LISTS = mapOf(
    "manual" to listOf("handle", "rods", "spout", "apron", "drain", "fence", "lock", "clean"),
    "electric" to listOf("power", "pumpruns", "control", "tank", "taps", "pipes", "meter", "fence", "clean"),
    "solar" to listOf("panels", "pumpruns", "control", "tank", "taps", "pipes", "fence", "clean"),
    "borehole" to listOf("pumpworks", "spout", "apron", "drain", "fence", "lock", "clean"),
    "tankk" to listOf("gutters", "firstflush", "tankwalls", "tap", "lid", "base", "clean"),
    "building" to listOf("waterpoint", "handwash", "latrines", "storage", "roof", "clean"),
)

private fun slots(p: Project): List<Slot> {
    val b = kind(p) == "building"
    return listOf(
        Slot(if (b) "The water point" else "Pump or tank, close up", if (b) t("The water point", "Kituo cha maji") else t("Pump or tank, close up", "Pampu au tangi, kwa karibu"), t("Fill the frame with it", "Ijaze picha nzima")),
        Slot("Water flowing", t("Water flowing", "Maji yakitiririka"), t("Water coming out of the spout or tap", "Maji yakitoka kwenye bomba")),
        Slot(if (b) "Building and people" else "The site with people", if (b) t("Building and people", "Jengo na watu") else t("The site with people", "Eneo pamoja na watu"), t("Step back: the water point and people using it", "Rudi nyuma: kituo na watu wakikitumia")),
        Slot("Any damage", t("Any damage", "Uharibifu wowote"), t("Close up of anything broken", "Karibu na kilichoharibika")),
        Slot("Water test strip", t("Water test strip", "Kipimo cha maji"), t("Only if you tested the water", "Ikiwa tu umepima maji")),
    )
}

/** Everything typed in a visit, kept while moving between steps. */
class VisitDraft {
    var step by mutableStateOf(0)
    var arrived by mutableStateOf<String?>(null)
    var arrivedAt by mutableStateOf<LatLng?>(null)
    var state by mutableStateOf("")
    var urgency by mutableStateOf("")
    val check = mutableStateMapOf<String, String>()
    var other by mutableStateOf(""); var parts by mutableStateOf(""); var cost by mutableStateOf("")
    var strokes by mutableStateOf(""); var flow by mutableStateOf(""); var queue by mutableStateOf(""); var users by mutableStateOf(""); var clarity by mutableStateOf("")
    var ecoli by mutableStateOf("")
    var active by mutableStateOf(""); var chair by mutableStateOf(""); var phone by mutableStateOf(""); var fee by mutableStateOf(""); var fund by mutableStateOf("")
    var quote by mutableStateOf(""); var quoteBy by mutableStateOf(""); var consent by mutableStateOf(false)
    var note by mutableStateOf("")
    var shots by mutableStateOf(listOf<Shot>())
    var sent by mutableStateOf<String?>(null)
}

private val drafts = HashMap<String, VisitDraft>()
internal fun clearDrafts() = drafts.clear()

@Composable
fun VisitTab(m: AppModel, p: Project) {
    val v = remember(p.id) { drafts.getOrPut(p.id) { VisitDraft() } }
    val fix by m.fix.collectAsState()
    val scope = rememberScope()
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val steps = listOf(t("Arrive", "Fika"), t("Condition", "Hali"), t("Checklist", "Ukaguzi"), t("Readings", "Vipimo"), t("Photos", "Picha"), t("People", "Watu"), t("Send", "Tuma"))
    val items = LISTS.getValue(kind(p))

    if (v.sent != null) {
        Card {
            IconTile("okc", C.ok, C.okBg, 64.dp)
            Text(if (v.sent == "sent") t("Visit report sent", "Ripoti ya ziara imetumwa") else t("Saved on this phone", "Imehifadhiwa kwenye simu"), style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.padding(top = 12.dp))
            Text(if (v.sent == "sent") t("It’s on the project in monday.com.", "Iko kwenye mradi katika monday.com.") else t("It will be sent to monday.com when you have signal.", "Itatumwa monday.com ukipata mtandao."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
            Btn(t("Start another visit here", "Anza ziara nyingine hapa"), { drafts.remove(p.id); v.sent = null; v.step = 0; v.state = ""; v.shots = emptyList(); v.check.clear(); v.note = "" }, Modifier.padding(top = 14.dp), light = true, small = true)
        }
        return
    }

    // step bar
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEachIndexed { i, s ->
            Column(Modifier.weight(1f).clickable(role = Role.Tab) { v.step = i }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(when { i < v.step -> SolidColor(C.ok); i == v.step -> BlueBrush; else -> SolidColor(C.line) }))
                Text(s, fontSize = 9.sp, color = if (i == v.step) C.blue else C.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Card {
        when (v.step) {
            0 -> {
                H2(t("Check in", "Ingia"))
                Text(t("Records the time you arrived and where you are.", "Inarekodi muda ulipofika na mahali ulipo."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
                if (v.arrived == null) Btn(t("I’ve arrived", "Nimefika"), { v.arrived = Instant.now().toString(); v.arrivedAt = fix?.ll; v.step = 1 }, Modifier.fillMaxWidth().padding(top = 14.dp), icon = "pin")
                else Text(t("Arrived ", "Umefika ") + whenText(v.arrived!!) + (v.arrivedAt?.let { " · " + it.text() } ?: ""), color = C.ok, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            }
            1 -> {
                H2(t("How is it?", "Iko hali gani?"))
                STATES.forEach { k ->
                    val (icon, tint, bg) = when (k) { "working" -> Triple("okc", C.ok, C.okBg); "repair" -> Triple("alert", Amber, C.warnBg); else -> Triple("xc", C.bad, C.badBg) }
                    Choice(stateName(k), icon, tint, bg, v.state == k) { v.state = k }
                }
                if (v.state.isNotBlank() && v.state != "working") {
                    Text(t("How urgent?", "Ni haraka kiasi gani?"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(top = 14.dp))
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        URGENCY.forEach { k -> Pill(urgencyName(k), v.urgency == k, Modifier.weight(1f)) { v.urgency = k } }
                    }
                }
            }
            2 -> {
                H2(t("Checklist", "Ukaguzi"))
                Text(t("Tap OK or Problem for each. Skip anything you couldn’t check.", "Gusa Sawa au Tatizo kwa kila kimoja. Ruka usichoweza kukagua."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
                items.forEach { k ->
                    val (en, sw) = ITEMS.getValue(k); val cur = v.check[k]
                    Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(when (cur) { "ok" -> C.okBg; "bad" -> C.badBg; else -> C.card })
                        .border(1.dp, when (cur) { "ok" -> C.ok; "bad" -> C.bad; else -> C.line }, RoundedCornerShape(16.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t(en, sw), style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.weight(1f))
                        Pill(t("OK", "Sawa"), cur == "ok", Modifier.padding(start = 6.dp), color = C.ok) { v.check[k] = if (cur == "ok") "" else "ok" }
                        Pill(t("Problem", "Tatizo"), cur == "bad", Modifier.padding(start = 6.dp), color = C.bad) { v.check[k] = if (cur == "bad") "" else "bad" }
                    }
                }
                Field(v.other, { v.other = it }, t("Anything else wrong?", "Kuna kingine kibaya?"), placeholder = t("e.g. tap drips", "mf. bomba linavuja"))
                if (v.state != "working" && v.state.isNotBlank()) {
                    Field(v.parts, { v.parts = it }, t("Parts needed", "Vipuri vinavyohitajika"), placeholder = t("e.g. 2 pump rods, handle bolt", "mf. fimbo 2 za pampu, boliti ya mkono"))
                    Field(v.cost, { v.cost = it }, t("Rough cost (KES)", "Gharama ya makadirio (KES)"), number = true)
                }
            }
            3 -> {
                H2(t("Readings", "Vipimo"))
                Text(t("All optional.", "Vyote si lazima."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(v.strokes, { v.strokes = it }, t("Pump strokes to fill 20 L", "Mipigo ya pampu kujaza lita 20"), Modifier.weight(1f), "e.g. 35", number = true)
                    Field(v.flow, { v.flow = it }, t("Flow (litres a minute)", "Mtiririko (lita kwa dakika)"), Modifier.weight(1f), "e.g. 12", number = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(v.users, { v.users = it }, t("People using it a day", "Watu wanaotumia kwa siku"), Modifier.weight(1f), number = true)
                    Field(v.queue, { v.queue = it }, t("Queue (minutes)", "Foleni (dakika)"), Modifier.weight(1f), number = true)
                }
                Text(t("Water looks", "Maji yanaonekana"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(top = 14.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("clear" to t("Clear", "Safi"), "cloudy" to t("Cloudy", "Mawingu"), "dirty" to t("Dirty", "Machafu")).forEach { (k, l) -> Pill(l, v.clarity == k, Modifier.weight(1f)) { v.clarity = if (v.clarity == k) "" else k } }
                }
                Text(t("E. coli test", "Kipimo cha E. coli"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(top = 14.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("neg" to t("None found", "Haikupatikana"), "pos" to t("Found", "Imepatikana")).forEach { (k, l) -> Pill(l, v.ecoli == k, Modifier.weight(1f), color = if (k == "pos") C.bad else C.ok) { v.ecoli = if (v.ecoli == k) "" else k } }
                }
            }
            4 -> {
                H2(t("Photos", "Picha"))
                Text(t("Tap a box to take that photo or choose one from your gallery. Each one is labelled in monday.com.", "Gusa kisanduku kupiga picha hiyo au kuchagua kutoka kwenye picha zako. Kila moja inapewa jina kwenye monday.com."), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(bottom = 10.dp))
                PhotoSlots(m, slots(p), v.shots, { v.shots = it })
            }
            5 -> {
                H2(t("Water committee", "Kamati ya maji"))
                Text(t("Is the committee active?", "Je, kamati iko hai?"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("yes" to t("Yes", "Ndiyo"), "no" to t("No", "Hapana"), "unsure" to t("Not sure", "Sina uhakika")).forEach { (k, l) -> Pill(l, v.active == k, Modifier.weight(1f)) { v.active = if (v.active == k) "" else k } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(v.chair, { v.chair = it }, t("Chairperson", "Mwenyekiti"), Modifier.weight(1f))
                    Field(v.phone, { v.phone = it }, t("Their phone", "Simu yake"), Modifier.weight(1f))
                }
                Field(v.fund, { v.fund = it }, t("Maintenance fund now (KES)", "Mfuko wa matengenezo sasa (KES)"), placeholder = "e.g. 3000", number = true)
                H2(t("Story moment (optional)", "Hadithi (si lazima)"), Modifier.padding(top = 18.dp))
                Text(t("A few words from someone who uses the water, for the website.", "Maneno machache kutoka kwa mtu anayetumia maji, kwa ajili ya tovuti."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
                Field(v.quote, { v.quote = it }, t("What they said", "Walichosema"), placeholder = t("e.g. “Now my girls get to school on time.”", "mf. “Sasa wasichana wangu wanafika shuleni kwa wakati.”"), lines = 3)
                Field(v.quoteBy, { v.quoteBy = it }, t("Who said it", "Aliyesema"), placeholder = t("e.g. Mary, mother of three", "mf. Mary, mama wa watoto watatu"))
                Row(Modifier.padding(top = 12.dp).clickable { v.consent = !v.consent }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(v.consent, { v.consent = it }, colors = CheckboxDefaults.colors(checkedColor = C.blue))
                    Text(t("They agreed to be quoted and photographed for RAFIKI’s website", "Wamekubali kunukuliwa na kupigwa picha kwa tovuti ya RAFIKI"), style = MaterialTheme.typography.bodyMedium, color = C.text)
                }
            }
            else -> {
                H2(t("Check and send", "Hakiki na utume"))
                SumRow(t("Condition", "Hali"), if (v.state.isBlank()) t("Not chosen yet", "Bado haijachaguliwa") else stateName(v.state), bad = v.state.isBlank())
                SumRow(t("Problems", "Matatizo"), (v.check.count { it.value == "bad" } + if (v.other.isNotBlank()) 1 else 0).toString())
                SumRow(t("Photos", "Picha"), v.shots.size.toString())
                Field(v.note, { v.note = it }, t("Notes", "Maelezo"), placeholder = t("Anything else the office should know", "Kitu kingine chochote ofisi inapaswa kujua"), lines = 3)
                Btn(if (busy) t("Sending…", "Inatuma…") else t("Send visit report", "Tuma ripoti ya ziara"), {
                    busy = true
                    scope.launch {
                        fun num(s: String) = s.trim().toDoubleOrNull()
                        val bad = items.filter { v.check[it] == "bad" }.map { ITEMS.getValue(it).first }
                        val body = JSONObject().put("action", "visit").put("id", p.id).put("ck", p.ck).put("state", v.state)
                            .put("urgency", if (v.state == "working") "" else v.urgency)
                            .put("problems", JSONArray(bad)).put("checked", JSONArray(items.filter { v.check[it] == "ok" }.map { ITEMS.getValue(it).first }))
                            .put("other", v.other.trim()).put("parts", v.parts.trim()).apply { num(v.cost)?.let { put("cost", it) } }
                            .put("readings", JSONObject().apply { num(v.strokes)?.let { put("strokes", it) }; num(v.flow)?.let { put("flow", it) }; num(v.queue)?.let { put("queue", it) }; num(v.users)?.let { put("users", it) }; if (v.clarity.isNotBlank()) put("clarity", v.clarity) })
                            .put("quality", JSONObject().apply { if (v.ecoli.isNotBlank()) put("ecoli", v.ecoli) })
                            .put("committee", JSONObject().apply { if (v.active.isNotBlank()) put("active", v.active); put("chair", v.chair.trim()); put("phone", v.phone.trim()); num(v.fund)?.let { put("fund", it) } })
                            .put("story", JSONObject().put("quote", v.quote.trim()).put("by", v.quoteBy.trim()).put("consent", v.consent))
                            .put("note", v.note.trim()).put("leave", Instant.now().toString())
                        v.arrived?.let { body.put("arrive", it) }
                        (v.arrivedAt ?: fix?.ll)?.let { body.put("lat", it.lat).put("lng", it.lng) }
                        val files = v.shots.map { OutFile(it.file.path, "photo", it.label) }
                        when (val o = m.submit(body, files)) {
                            is Outcome.Sent -> { m.localVisit(p, v.state, v.note, v.urgency, bad); v.sent = "sent"; msg = o.note?.let { "warn" to it } }
                            Outcome.Queued -> { m.localVisit(p, v.state, v.note, v.urgency, bad); v.sent = "queued" }
                            is Outcome.Failed -> msg = "bad" to o.text
                            Outcome.Locked -> {}
                        }
                        busy = false
                    }
                }, Modifier.fillMaxWidth().padding(top = 16.dp), icon = "upload", enabled = v.state.isNotBlank() && !busy)
                msg?.let { (tone, text) -> Msg(text, tone) }
            }
        }
    }
    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn(t("Back", "Rudi"), { if (v.step > 0) v.step-- }, Modifier.weight(1f), icon = "back", light = true, enabled = v.step > 0)
        if (v.step < steps.size - 1) Btn(t("Next", "Endelea"), { v.step++ }, Modifier.weight(1f), enabled = v.step != 1 || v.state.isNotBlank())
    }
}

@Composable
fun H2(text: String, modifier: Modifier = Modifier) = Text(text, style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = modifier.padding(bottom = 4.dp))

@Composable
private fun SumRow(k: String, v: String, bad: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.weight(1f))
        Text(v, style = MaterialTheme.typography.titleSmall, color = if (bad) C.bad else C.ink)
    }
}

@Composable
fun Choice(label: String, icon: String, tint: Color, bg: Color, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (on) BlueBrush else SolidColor(C.card))
            .border(1.dp, if (on) Color.Transparent else C.edge, RoundedCornerShape(20.dp)).clickable(role = Role.RadioButton, onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint, if (on) Color.White else bg)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (on) Color.White else C.ink)
    }
}

@Composable
fun Pill(label: String, on: Boolean, modifier: Modifier = Modifier, color: Color? = null, onClick: () -> Unit) {
    val c = color ?: C.blue
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(if (on) c else C.card).border(1.dp, if (on) c else C.edge, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) Color.White else C.ink, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center) }
}
