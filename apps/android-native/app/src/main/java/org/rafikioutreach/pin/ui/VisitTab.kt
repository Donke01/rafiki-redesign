package org.rafikioutreach.pin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
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
    // schools
    "sroof" to ("Roof, gutters and ceiling" to "Paa, mifereji na dari"), "walls" to ("Walls and floor (no cracks)" to "Kuta na sakafu (bila nyufa)"),
    "doors" to ("Doors, windows and locks" to "Milango, madirisha na kufuli"), "desks" to ("Desks and furniture" to "Madawati na samani"),
    "boards" to ("Chalkboards and paint" to "Ubao na rangi"), "inuse" to ("Classrooms in use" to "Madarasa yanatumika"),
    // clinics
    "bldg" to ("Building, roof and walls" to "Jengo, paa na kuta"), "equip" to ("Equipment and beds working" to "Vifaa na vitanda vinafanya kazi"),
    "supplies" to ("Medicines and supplies stocked and in date" to "Dawa na vifaa vipo na havijaisha muda"), "washrooms" to ("Water and washrooms" to "Maji na vyoo"),
    "light" to ("Power and lighting" to "Umeme na taa"), "waste" to ("Incinerator and waste disposal" to "Kichomea taka na utupaji wa taka"),
    // children's homes
    "dorm" to ("Dormitories" to "Mabweni"), "beds" to ("Beds, mattresses and bedding" to "Vitanda, magodoro na matandiko"),
    "kitchen" to ("Kitchen and food store" to "Jiko na stoo ya chakula"), "wallgate" to ("Wall, fence and gate" to "Ukuta, uzio na lango"),
    // farms
    "pen" to ("Pen or coop: roof, walls and floor" to "Banda: paa, kuta na sakafu"), "animals" to ("Animals healthy" to "Mifugo ni wazima"),
    "feed" to ("Feed and water for the animals" to "Chakula na maji ya mifugo"), "drained" to ("Clean and drained" to "Safi na maji hayatuami"),
    "records" to ("Records kept (births, deaths, sales)" to "Kumbukumbu zinawekwa (kuzaliwa, vifo, mauzo)"),
    // any other building
    "furniture" to ("Equipment and furniture" to "Vifaa na samani"), "used" to ("Being used as planned" to "Inatumika kama ilivyopangwa"),
    // building sites
    "materials" to ("Materials on site and stored safely" to "Vifaa vya ujenzi vipo na vimehifadhiwa salama"), "workers" to ("Workers on site" to "Mafundi wako kazini"),
    "plumb" to ("Walls straight, no cracks" to "Kuta zimenyooka, bila nyufa"), "timber" to ("Roof and timber" to "Paa na mbao"),
    "fitted" to ("Doors and windows fitted" to "Milango na madirisha yamewekwa"), "plan" to ("Built to the plan and size agreed" to "Inajengwa kulingana na mpango na ukubwa uliokubaliwa"),
    "safety" to ("Site safe and tidy" to "Eneo ni salama na nadhifu"), "schedule" to ("On schedule" to "Inaenda kwa ratiba"),
)

/** Numbers counted on visits that aren't water (key → English, Kiswahili). */
private val NUM = mapOf(
    "pupils" to ("Pupils enrolled" to "Wanafunzi waliosajiliwa"), "teachers" to ("Teachers" to "Walimu"), "patients" to ("Patients a week" to "Wagonjwa kwa wiki"),
    "staff" to ("Staff on duty" to "Wafanyakazi kazini"), "beds" to ("Beds in use" to "Vitanda vinavyotumika"), "children" to ("Children living there" to "Watoto wanaoishi hapo"),
    "carers" to ("Carers and staff" to "Walezi na wafanyakazi"), "animals" to ("Animals now" to "Mifugo sasa"), "born" to ("Born or hatched since the last visit" to "Waliozaliwa au kuanguliwa tangu ziara iliyopita"),
    "died" to ("Died since the last visit" to "Waliokufa tangu ziara iliyopita"), "sold" to ("Sold since the last visit" to "Waliouzwa tangu ziara iliyopita"),
    "income" to ("Income from sales since the last visit" to "Mapato ya mauzo tangu ziara iliyopita"), "users" to ("People using it (estimate)" to "Watu wanaoitumia (makadirio)"),
    "workers" to ("Workers on site today" to "Mafundi kazini leo"),
)
private val NUMS = mapOf("school" to listOf("pupils", "teachers"), "health" to listOf("patients", "staff", "beds"), "home" to listOf("children", "carers"),
    "farm" to listOf("animals", "born", "died", "sold", "income"), "building" to listOf("users"), "build" to listOf("workers"))
private val CARER = mapOf("school" to ("Head teacher" to "Mwalimu mkuu"), "health" to ("Person in charge (e.g. clinical officer)" to "Msimamizi (mf. afisa wa kliniki)"),
    "home" to ("Home manager" to "Msimamizi wa makao"), "farm" to ("Farmer" to "Mkulima"), "building" to ("Person in charge" to "Msimamizi"), "build" to ("Fundi or site foreman" to "Fundi au msimamizi wa ujenzi"))
private val STAGES = listOf("foundation" to ("Foundation" to "Msingi"), "walls" to ("Walls" to "Kuta"), "roof" to ("Roof" to "Paa"), "finishing" to ("Finishing" to "Umaliziaji"), "done" to ("Finished" to "Imekamilika"))
internal fun isWater(p: Project) = "borehole" in p.type.lowercase() || "collection" in p.type.lowercase()
/** On a building site the condition reads On track / Problems to fix / Work stopped. */
private fun stateLabel(p: Project, k: String) = if (kind(p) == "build") when (k) { "working" -> t("On track", "Inaendelea vizuri"); "repair" -> t("Problems to fix", "Kuna matatizo ya kurekebisha"); else -> t("Work stopped", "Kazi imesimama") } else stateName(k)

private fun kind(p: Project): String {
    val ty = p.type.lowercase(); val pump = p.pump.lowercase()
    return when {
        "borehole" in ty -> when (pump) { "manual" -> "manual"; "solar" -> "solar"; "electric" -> "electric"; else -> "borehole" }
        "collection" in ty -> "tankk"
        // not water: a building site while under construction, otherwise by type
        "under construction" in p.status.lowercase() -> "build"
        "school" in ty -> "school"
        "health" in ty -> "health"
        "orphan" in ty -> "home"
        "farm" in ty -> "farm"
        else -> "building"
    }
}
private val LISTS = mapOf(
    "manual" to listOf("handle", "rods", "spout", "apron", "drain", "fence", "lock", "clean"),
    "electric" to listOf("power", "pumpruns", "control", "tank", "taps", "pipes", "meter", "fence", "clean"),
    "solar" to listOf("panels", "pumpruns", "control", "tank", "taps", "pipes", "fence", "clean"),
    "borehole" to listOf("pumpworks", "spout", "apron", "drain", "fence", "lock", "clean"),
    "tankk" to listOf("gutters", "firstflush", "tankwalls", "tap", "lid", "base", "clean"),
    "school" to listOf("sroof", "walls", "doors", "desks", "boards", "latrines", "handwash", "inuse"),
    "health" to listOf("bldg", "equip", "supplies", "washrooms", "light", "waste", "fence", "clean"),
    "home" to listOf("dorm", "beds", "bldg", "kitchen", "washrooms", "wallgate", "clean"),
    "farm" to listOf("pen", "animals", "feed", "drained", "records", "fence"),
    "building" to listOf("bldg", "doors", "furniture", "washrooms", "used", "clean"),
    "build" to listOf("materials", "workers", "plumb", "timber", "fitted", "plan", "safety", "schedule"),
)

private fun slots(p: Project): List<Slot> {
    fun s(en: String, sw: String, hen: String = "", hsw: String = "") = Slot(en, t(en, sw), if (hen.isBlank()) "" else t(hen, hsw))
    val damage = Slot("Any damage", t("Any damage", "Uharibifu wowote"), t("Close up of anything broken", "Karibu na kilichoharibika"))
    val out = s("Outside, the whole building", "Nje, jengo lote")
    when (kind(p)) {
        "school" -> return listOf(out, s("A classroom inside", "Ndani ya darasa"), s("Desks and boards", "Madawati na ubao"), s("Pupils and teachers", "Wanafunzi na walimu", "Only with the school’s permission", "Kwa ruhusa ya shule tu"), damage)
        "health" -> return listOf(out, s("Equipment and beds", "Vifaa na vitanda"), s("Medicine and supplies store", "Stoo ya dawa na vifaa"), s("Water and washrooms", "Maji na vyoo"), damage)
        "home" -> return listOf(out, s("Dormitory and beds", "Bweni na vitanda"), s("Kitchen", "Jiko"), s("Water and latrines", "Maji na vyoo"), damage)
        "farm" -> return listOf(s("The animals", "Mifugo"), s("The pen or coop", "Banda"), s("Feed and water for the animals", "Chakula na maji ya mifugo"), s("The farmer at work", "Mkulima kazini"), damage)
        "building" -> return listOf(out, s("Inside", "Ndani"), s("People using it", "Watu wakiitumia"), damage)
        "build" -> return listOf(s("The whole building now", "Jengo lote sasa", "Same spot as last time, so progress shows", "Mahali pale pale kama mara iliyopita, ili maendeleo yaonekane"),
            s("Work done since the last visit", "Kazi iliyofanyika tangu ziara iliyopita", "Close up", "Kwa karibu"), s("Materials on site", "Vifaa vya ujenzi"), s("Anything wrong", "Kitu chochote kibaya", "Cracks, leaks, poor work", "Nyufa, kuvuja, kazi mbaya"))
    }
    val b = false
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
    /** "Add to this project's story": open its Stories tab once the visit is sent (feat-mea). */
    var toStory by mutableStateOf(false)
    var note by mutableStateOf("")
    var stage by mutableStateOf("")
    val nums = mutableStateMapOf<String, String>()
    var shots by mutableStateOf(listOf<Shot>())
    var voice by mutableStateOf<java.io.File?>(null)
    var sent by mutableStateOf<String?>(null)

    private fun texts() = mapOf(
        "state" to state, "urgency" to urgency, "other" to other, "parts" to parts, "cost" to cost, "strokes" to strokes, "flow" to flow, "queue" to queue, "users" to users,
        "clarity" to clarity, "ecoli" to ecoli, "active" to active, "chair" to chair, "phone" to phone, "fee" to fee, "fund" to fund, "quote" to quote, "quoteBy" to quoteBy, "note" to note, "stage" to stage,
    )
    /** Nothing typed or chosen yet (so there's nothing to keep). */
    fun blank() = step == 0 && arrived == null && texts().values.all { it.isBlank() } && check.values.all { it.isBlank() } && nums.values.all { it.isBlank() } && shots.isEmpty() && voice == null && !consent

    /** Everything in the visit, with the paths of its photos and voice note, to keep on the phone. */
    fun toJson(): JSONObject = JSONObject().put("v", 1).put("step", step).put("consent", consent).apply {
        arrived?.let { put("arrived", it) }; arrivedAt?.let { put("lat", it.lat).put("lng", it.lng) }
        texts().forEach { (k, s) -> if (s.isNotEmpty()) put(k, s) }
        put("check", JSONObject().apply { check.forEach { (k, s) -> if (s.isNotBlank()) put(k, s) } })
        put("nums", JSONObject().apply { nums.forEach { (k, s) -> if (s.isNotBlank()) put(k, s) } })
        put("shots", JSONArray().apply { shots.forEach { put(JSONObject().put("path", it.file.path).put("label", it.label)) } })
        voice?.let { put("voice", it.path) }
    }

    /** Puts a kept visit back; photos that are no longer on the phone are left out. */
    fun fill(j: JSONObject) {
        step = j.optInt("step"); consent = j.optBoolean("consent")
        arrived = j.optString("arrived").ifBlank { null }
        arrivedAt = if (j.has("lat") && j.has("lng")) LatLng(j.optDouble("lat"), j.optDouble("lng")) else null
        state = j.optString("state"); urgency = j.optString("urgency"); other = j.optString("other"); parts = j.optString("parts"); cost = j.optString("cost")
        strokes = j.optString("strokes"); flow = j.optString("flow"); queue = j.optString("queue"); users = j.optString("users"); clarity = j.optString("clarity"); ecoli = j.optString("ecoli")
        active = j.optString("active"); chair = j.optString("chair"); phone = j.optString("phone"); fee = j.optString("fee"); fund = j.optString("fund")
        quote = j.optString("quote"); quoteBy = j.optString("quoteBy"); note = j.optString("note"); stage = j.optString("stage")
        check.clear(); j.optJSONObject("check")?.let { o -> o.keys().forEach { check[it] = o.optString(it) } }
        nums.clear(); j.optJSONObject("nums")?.let { o -> o.keys().forEach { nums[it] = o.optString(it) } }
        shots = j.optJSONArray("shots")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Shot(java.io.File(it.optString("path")), it.optString("label")) } } }.orEmpty().filter { it.file.exists() }
        voice = j.optString("voice").ifBlank { null }?.let { java.io.File(it) }?.takeIf { it.exists() }
        sent = null
    }
}

private val drafts = HashMap<String, VisitDraft>()
internal fun clearDrafts() = drafts.clear()

/**
 * A half-done visit kept on the phone (files/visits/<project>.json), so it survives Android closing the app.
 * It's written on every change, removed once the visit is sent or saved to send, or when "Start again" is chosen.
 */
object VisitDrafts {
    /** A kept visit: what was typed and when it was last changed (ms). */
    data class Kept(val json: JSONObject, val saved: Long) {
        val started: Long get() = json.optString("arrived").takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: saved
        val photos: Int get() = json.optJSONArray("shots")?.length() ?: 0
    }
    private fun file(ctx: android.content.Context, id: String) = java.io.File(java.io.File(ctx.filesDir, "visits").apply { mkdirs() }, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
    fun save(ctx: android.content.Context, id: String, json: JSONObject) = runCatching {
        val f = file(ctx, id); val tmp = java.io.File(f.path + ".tmp")
        tmp.writeText(JSONObject(json.toString()).put("saved", System.currentTimeMillis()).toString()); tmp.renameTo(f)
    }.isSuccess
    fun load(ctx: android.content.Context, id: String): Kept? = runCatching {
        val j = JSONObject(file(ctx, id).readText()); Kept(j, j.optLong("saved"))
    }.getOrNull()
    fun has(ctx: android.content.Context, id: String) = file(ctx, id).exists()
    fun remove(ctx: android.content.Context, id: String) { runCatching { file(ctx, id).delete() } }
    /** Start again: the kept visit and its photos and voice note go. */
    fun discard(ctx: android.content.Context, id: String) {
        load(ctx, id)?.json?.let { j ->
            j.optJSONArray("shots")?.let { a -> (0 until a.length()).forEach { i -> a.optJSONObject(i)?.optString("path")?.takeIf { it.isNotBlank() }?.let { runCatching { java.io.File(it).delete() } } } }
            j.optString("voice").takeIf { it.isNotBlank() }?.let { runCatching { java.io.File(it).delete() } }
        }
        remove(ctx, id)
    }
}

/** A checklist problem as monday.com keeps it (English, "Pump works"), in the app's language. Anything else stays as it is. */
fun problemName(en: String): String = ITEMS.values.firstOrNull { it.first.equals(en.trim(), ignoreCase = true) }?.let { t(it.first, it.second) } ?: en

@Composable
fun VisitTab(m: AppModel, p: Project) {
    val v = remember(p.id) { drafts.getOrPut(p.id) { VisitDraft() } }
    val fix by m.fix.collectAsState()
    val scope = rememberScope()
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val steps = listOf(t("Arrive", "Fika"), t("Condition", "Hali"), t("Checklist", "Ukaguzi"), if (isWater(p)) t("Readings", "Vipimo") else t("Numbers", "Idadi"), t("Photos", "Picha"), t("People", "Watu"), t("Send", "Tuma"))
    val items = LISTS.getValue(kind(p))
    val wet = isWater(p); val k0 = kind(p)
    // scanned the site's QR sticker (Scan QR): that counts as arriving
    androidx.compose.runtime.LaunchedEffect(p.id) { QrCheckin.at(p.id)?.takeIf { QrCheckin.isFor(p.id) && v.arrived == null }?.let { v.arrived = Instant.ofEpochMilli(it).toString(); v.arrivedAt = fix?.ll } }
    // a visit kept on the phone from before Android closed the app: offer to carry on with it
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var offer by remember(p.id) { mutableStateOf(if (v.blank() && v.sent == null) VisitDrafts.load(ctx, p.id) else null) }
    androidx.compose.runtime.LaunchedEffect(p.id, offer == null) {
        if (offer != null) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { if (v.sent != null || v.blank()) null else v.toJson() }.collectLatest { j ->
            if (j != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { VisitDrafts.save(ctx, p.id, j) }
        }
    }

    // back: one step back (what was typed stays); on the first step, ask before leaving the visit (it is kept on the phone)
    val backs = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var askLeave by remember(p.id) { mutableStateOf(false) }
    var leaving by remember(p.id) { mutableStateOf(false) }
    val inSteps = offer == null && v.sent == null
    BackStep(inSteps && v.step > 0) { v.step-- }
    BackStep(inSteps && v.step == 0 && !v.blank() && !leaving) { askLeave = true }
    androidx.compose.runtime.LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }  // the question has stepped off the back stack: the next back leaves
        backs?.onBackPressed(); leaving = false
    }
    if (askLeave) DqmSheet("visit_leave_sheet", { askLeave = false }) { hide ->
        DqmSheetHead(t("Leave this visit?", "Uondoke kwenye ziara hii?"), listOf(t("It’s kept on this phone. Open Site visit again to carry on.", "Imehifadhiwa kwenye simu hii. Fungua Ziara tena kuendelea.")),
            null, "visit_leave_x", { hide {} }) { IconTile("clock", C.blue, C.tint, 56.dp) }
        Btn(t("Keep going", "Endelea na ziara"), { hide {} }, Modifier.fillMaxWidth().padding(top = 18.dp).testTag("visit_leave_stay"), icon = "arrow")
        Btn(t("Leave", "Ondoka"), { hide { leaving = true } }, Modifier.fillMaxWidth().padding(top = 10.dp).testTag("visit_leave_go"), icon = "back", light = true, outline = true)
    }

    offer?.let { o ->
        Card(Modifier.testTag("visit_resume")) {
            IconTile("clock", C.blue, C.tint, 56.dp)
            val at = Dates.timeOrDateTime(Instant.ofEpochMilli(o.started))
            Text(t("Carry on with your visit from $at?", "Uendelee na ziara uliyoanza $at?"), style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.padding(top = 12.dp))
            val n = o.photos; val stepNo = o.json.optInt("step") + 1
            Text(t("It was kept on this phone: step $stepNo of ${steps.size}" + if (n > 0) ", $n ${if (n == 1) "photo" else "photos"}." else ".", "Ilihifadhiwa kwenye simu hii: hatua $stepNo kati ya ${steps.size}" + if (n > 0) ", picha $n." else "."),
                style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(top = 4.dp))
            Btn(t("Resume", "Endelea nayo"), { v.fill(o.json); offer = null }, Modifier.fillMaxWidth().padding(top = 16.dp).testTag("resume_yes"), icon = "arrow")
            Btn(t("Start again", "Anza upya"), { VisitDrafts.discard(ctx, p.id); offer = null }, Modifier.fillMaxWidth().padding(top = 10.dp).testTag("resume_no"), icon = "refresh", light = true, outline = true)
        }
        return
    }

    if (v.sent != null) {
        Card {
            IconTile("okc", C.ok, C.okBg, 64.dp)
            Text(if (v.sent == "sent") t("Visit report sent", "Ripoti ya ziara imetumwa") else t("Saved on this phone", "Imehifadhiwa kwenye simu"), style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.padding(top = 12.dp))
            Text(if (v.sent == "sent") t("It’s on the project in monday.com.", "Iko kwenye mradi katika monday.com.") else t("It will be sent to monday.com when you have signal.", "Itatumwa monday.com ukipata mtandao."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
            Btn(t("Start another visit here", "Anza ziara nyingine hapa"), { drafts.remove(p.id); VisitDrafts.remove(ctx, p.id); v.sent = null; v.step = 0; v.state = ""; v.shots = emptyList(); v.check.clear(); v.note = "" }, Modifier.padding(top = 14.dp), light = true, small = true)
        }
        return
    }

    // step bar: the segments, and only the current step named ("Step 2 of 7 · Condition")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEachIndexed { i, s ->
            Box(Modifier.weight(1f).clickable(role = Role.Tab, onClickLabel = s) { v.step = i }.padding(vertical = 8.dp).testTag("stepbar_$i")) {
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(when { i < v.step -> SolidColor(C.ok); i == v.step -> BlueBrush; else -> SolidColor(C.line) }))
            }
        }
    }
    Text(t("Step ${v.step + 1} of ${steps.size}", "Hatua ${v.step + 1} kati ya ${steps.size}") + " · " + steps[v.step], style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = C.blue2,
        maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 2.dp).testTag("step_label"))
    Spacer(Modifier.height(12.dp))
    Card {
        when (v.step) {
            0 -> {
                H2(t("Check in", "Ingia"))
                Text(t("Records the time you arrived and where you are.", "Inarekodi muda ulipofika na mahali ulipo."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
                QrCheckedIn(p.id)
                if (v.arrived == null) Btn(t("I’ve arrived", "Nimefika"), { v.arrived = Instant.now().toString(); v.arrivedAt = fix?.ll; v.step = 1 }, Modifier.fillMaxWidth().padding(top = 14.dp), icon = "pin")
                else Text(t("Arrived ", "Umefika ") + whenText(v.arrived!!) + (v.arrivedAt?.let { " · " + it.text() } ?: ""), color = C.ok, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            }
            1 -> {
                H2(if (k0 == "build") t("How is the building going?", "Ujenzi unaendeleaje?") else t("How is it?", "Iko hali gani?"))
                STATES.forEach { k ->
                    val (icon, tint, bg) = when (k) { "working" -> Triple("okc", C.ok, C.okBg); "repair" -> Triple("alert", Amber, C.warnBg); else -> Triple("xc", C.bad, C.badBg) }
                    Choice(stateLabel(p, k), icon, tint, bg, v.state == k) { v.state = k }
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
                if (k0 == "build") {
                    Text(t("Stage the building has reached", "Hatua ambayo jengo limefikia"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(top = 4.dp))
                    STAGES.chunked(3).forEach { row ->
                        Row(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (k, l) -> Pill(t(l.first, l.second), v.stage == k, Modifier.weight(1f).testTag("stage_$k")) { v.stage = if (v.stage == k) "" else k } }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Text(t("Tap OK or Problem for each. Skip anything you couldn’t check.", "Gusa Sawa au Tatizo kwa kila kimoja. Ruka usichoweza kukagua."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
                items.forEach { k ->
                    val (en, sw) = ITEMS.getValue(k); val cur = v.check[k]
                    Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(when (cur) { "ok" -> C.okBg; "bad" -> C.badBg; else -> C.card })
                        .border(1.dp, when (cur) { "ok" -> C.ok; "bad" -> C.bad; else -> C.line }, RoundedCornerShape(16.dp)).padding(10.dp).testTag("ck_$k")) {
                        Text(t(en, sw), style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
                        OkProblem(cur, k) { v.check[k] = if (cur == it) "" else it }
                    }
                }
                Field(v.other, { v.other = it }, t("Anything else wrong?", "Kuna kingine kibaya?"), placeholder = if (wet) t("e.g. tap drips", "mf. bomba linavuja") else if (k0 == "farm") t("e.g. feeder broken", "mf. chombo cha chakula kimevunjika") else t("e.g. window glass broken", "mf. kioo cha dirisha kimevunjika"))
                if (v.state != "working" && v.state.isNotBlank()) {
                    Field(v.parts, { v.parts = it }, if (wet) t("Parts needed", "Vipuri vinavyohitajika") else t("Materials or parts needed", "Vifaa vinavyohitajika"),
                        placeholder = if (wet) t("e.g. 2 pump rods, handle bolt", "mf. fimbo 2 za pampu, boliti ya mkono") else if (k0 == "farm") t("e.g. 4 iron sheets, wire mesh", "mf. mabati 4, wavu wa waya") else t("e.g. 6 iron sheets, 2 door hinges", "mf. mabati 6, bawaba 2 za mlango"))
                    Field(v.cost, { v.cost = it }, t("Rough cost", "Gharama ya makadirio"), Modifier.testTag("cost"), money = true)
                }
            }
            3 -> if (!wet) {
                H2(t("Numbers", "Idadi"))
                Text(t("All optional.", "Vyote si lazima."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
                NUMS.getValue(k0).forEach { k -> val (en, sw) = NUM.getValue(k); Field(v.nums[k] ?: "", { v.nums[k] = it }, t(en, sw), Modifier.testTag("num_$k"), if (k == "income") "" else t("e.g. 12", "mf. 12"), number = k != "income", money = k == "income") }
            } else {
                H2(t("Readings", "Vipimo"))
                Text(t("All optional.", "Vyote si lazima."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(v.strokes, { v.strokes = it }, t("Pump strokes to fill 20 L", "Mipigo ya pampu kujaza lita 20"), Modifier.weight(1f), t("e.g. 35", "mf. 35"), number = true)
                    Field(v.flow, { v.flow = it }, t("Flow (litres a minute)", "Mtiririko (lita kwa dakika)"), Modifier.weight(1f), t("e.g. 12", "mf. 12"), number = true)
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
                if (k0 == "home") Text(t("Children: no close-up faces unless the home has agreed.", "Watoto: usipige picha za nyuso kwa karibu isipokuwa makao yamekubali."), style = MaterialTheme.typography.labelLarge, color = C.warn,
                    modifier = Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.warnBg).padding(12.dp).testTag("children_note"))
                PhotoSlots(m, slots(p), v.shots, { v.shots = it })
                VoiceNote(v.voice) { v.voice = it }
            }
            5 -> if (!wet) {
                val (cen, csw) = CARER.getValue(k0)
                H2(t("Who looks after it", "Anayeisimamia"))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(v.chair, { v.chair = it }, t(cen, csw), Modifier.weight(1f).testTag("carer_name"), t("Name", "Jina"))
                    Field(v.phone, { v.phone = it }, t("Their phone", "Simu yake"), Modifier.weight(1f))
                }
                StoryShortcut(v)
            } else {
                H2(t("Water committee", "Kamati ya maji"))
                Text(t("Is the committee active?", "Je, kamati iko hai?"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("yes" to t("Yes", "Ndiyo"), "no" to t("No", "Hapana"), "unsure" to t("Not sure", "Sina uhakika")).forEach { (k, l) -> Pill(l, v.active == k, Modifier.weight(1f)) { v.active = if (v.active == k) "" else k } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(v.chair, { v.chair = it }, t("Chairperson", "Mwenyekiti"), Modifier.weight(1f))
                    Field(v.phone, { v.phone = it }, t("Their phone", "Simu yake"), Modifier.weight(1f))
                }
                Field(v.fund, { v.fund = it }, t("Maintenance fund now", "Mfuko wa matengenezo sasa"), Modifier.testTag("fund"), money = true)
                StoryShortcut(v)
            }
            else -> {
                H2(t("Check and send", "Hakiki na utume"))
                STAGES.firstOrNull { it.first == v.stage }?.let { SumRow(t("Stage", "Hatua"), t(it.second.first, it.second.second)) }
                SumRow(t("Condition", "Hali"), if (v.state.isBlank()) t("Not chosen yet", "Bado haijachaguliwa") else stateLabel(p, v.state), bad = v.state.isBlank())
                v.nums.count { it.value.trim().toDoubleOrNull() != null }.takeIf { it > 0 }?.let { SumRow(t("Numbers", "Idadi"), it.toString()) }
                SumRow(t("Problems", "Matatizo"), (v.check.count { it.value == "bad" } + if (v.other.isNotBlank()) 1 else 0).toString())
                SumRow(t("Photos", "Picha"), v.shots.size.toString() + if (v.voice != null) t(" + voice note", " + ujumbe wa sauti") else "")
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
                            .apply {
                                if (!wet) {
                                    put("kind", k0); if (v.stage.isNotBlank()) put("stage", v.stage)
                                    put("numbers", JSONObject().apply { v.nums.forEach { (k, s) -> num(s)?.let { put(k, if (it % 1.0 == 0.0) it.toLong() else it) } } })
                                    put("carer", JSONObject().put("name", v.chair.trim()).put("phone", v.phone.trim()))
                                }
                            }
                            .put("note", v.note.trim()).put("leave", Instant.now().toString())
                        v.arrived?.let { body.put("arrive", it) }
                        if (QrCheckin.isFor(p.id)) body.put("qr", true)
                        (v.arrivedAt ?: fix?.ll)?.let { body.put("lat", it.lat).put("lng", it.lng) }
                        val files = v.shots.map { OutFile(it.file.path, "photo", it.label) } + listOfNotNull(v.voice?.let { OutFile(it.path, "voice", "Voice note") })
                        when (val o = m.submit(body, files)) {
                            is Outcome.Sent -> { m.localVisit(p, v.state, v.note, v.urgency, bad); testedNow(m, p, body); v.sent = "sent"; msg = o.note?.let { "warn" to it }; if (v.toStory) StoryNav.open = p.id }
                            Outcome.Queued -> { m.localVisit(p, v.state, v.note, v.urgency, bad); testedNow(m, p, body); v.sent = "queued"; if (v.toStory) StoryNav.open = p.id }
                            is Outcome.Failed -> msg = "bad" to o.text
                            Outcome.Locked -> {}
                        }
                        if (v.sent != null) { QrCheckin.clear(p.id); VisitDrafts.remove(ctx, p.id) }
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
/** A card heading, centred (set [wide] = false when it sits in a row beside a count). */
fun H2(text: String, modifier: Modifier = Modifier, wide: Boolean = true) = Text(text, style = MaterialTheme.typography.titleLarge, color = C.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = (if (wide) modifier.fillMaxWidth() else modifier).padding(bottom = 4.dp))

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
        Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (on) C.primary else SolidColor(C.card))
            .border(1.dp, if (on) Color.Transparent else C.edge, RoundedCornerShape(20.dp)).clickable(role = Role.RadioButton, onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint, if (on) Color.White else bg)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (on) Color.White else C.ink)
    }
}

/** A checklist row's answer: one 48 dp pill in two halves, OK (turns green) and Problem (turns red). */
@Composable
private fun OkProblem(cur: String?, key: String, pick: (String) -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Row(Modifier.fillMaxWidth().height(48.dp).clip(shape).background(C.card).border(1.dp, C.edge, shape)) {
        listOf(Triple("ok", t("OK", "Sawa"), C.ok), Triple("bad", t("Problem", "Tatizo"), C.bad)).forEachIndexed { i, (k, label, col) ->
            val on = cur == k
            val onText = if (C.dark) Color(0xFF0D1318) else Color.White // dark text on the light green and red of dark mode
            if (i == 1) Box(Modifier.width(1.dp).fillMaxHeight().background(if (cur.isNullOrBlank()) C.edge else Color.Transparent))
            Row(Modifier.weight(1f).fillMaxHeight().background(if (on) col else Color.Transparent).clickable(role = Role.RadioButton, onClickLabel = label) { pick(k) }.testTag("ck_${key}_$k"),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.get(if (k == "ok") "check" else "alert"), null, tint = if (on) onText else col, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, color = if (on) onText else C.ink, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
fun Pill(label: String, on: Boolean, modifier: Modifier = Modifier, color: Color? = null, onClick: () -> Unit) {
    val c = color ?: C.selected
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(if (on) c else if (C.glassy) C.glass2 else C.card).border(1.dp, if (on) c else C.edge, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) Color.White else C.ink, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center) }
}


/** The visit's story moment is now one shortcut (feat-mea, me-brief §9): the Stories tab opens after the visit. */
@Composable
private fun StoryShortcut(v: VisitDraft) {
    Row(Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tint).clickable(role = androidx.compose.ui.semantics.Role.Checkbox) { v.toStory = !v.toStory }
        .heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 6.dp).testTag("visit_to_story"), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(v.toStory, { v.toStory = it }, colors = CheckboxDefaults.colors(checkedColor = C.blue))
        Column(Modifier.weight(1f)) {
            Text(t("Add to this project’s story", "Ongeza kwenye hadithi ya mradi huu"), style = MaterialTheme.typography.titleSmall, color = C.ink)
            Text(t("After sending, the Stories tab opens: photos, questions and a quote.", "Ukishatuma, kichupo cha Hadithi kitafunguka: picha, maswali na nukuu."), style = MaterialTheme.typography.bodySmall, color = C.muted)
        }
    }
}
