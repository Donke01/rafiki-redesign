package org.rafikioutreach.pin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.t
import java.io.File
import java.util.WeakHashMap

/** The trips of one signed-in app (one per [AppModel]): `m.trips`. */
val AppModel.trips: ItinRepo get() = synchronized(ItinRepo.all) { ItinRepo.all.getOrPut(this) { ItinRepo(api, File(getApplication<android.app.Application>().filesDir, "itins.json")) } }

/**
 * Field trips, vehicles, fuel and group trips (feat-itina), against /api/itin, /api/vehicles, /api/fuel and
 * /api/group-trips (S/itin-contract-changes.md).
 *
 * Offline: every trip opened is kept on the phone with its legs, so it works with no signal. A change made on the phone
 * shows at once ([ItinEdit]) and waits in a queue ([change]) until it can be sent; the server's answer replaces it.
 */
class ItinRepo(val api: Api, private val file: File) {
    companion object { val all = WeakHashMap<AppModel, ItinRepo>() }

    private val lock = Mutex()
    private var store: JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    private fun save() = runCatching { file.writeText(store.toString()) }
    private fun recs(): JSONObject = store.optJSONObject("recs") ?: JSONObject().also { store.put("recs", it) }
    private fun queue(): JSONArray = store.optJSONArray("queue") ?: JSONArray().also { store.put("queue", it) }

    private val _list = MutableStateFlow(store.optJSONArray("list").objs())
    /** The index rows of the trips this person can see (newest first). */
    val list: StateFlow<List<JSONObject>> = _list
    private val _tick = MutableStateFlow(0)
    /** Changes whenever a kept trip changes. */
    val tick: StateFlow<Int> = _tick

    /** A trip kept on this phone (with the changes not sent yet). */
    fun cached(id: String): Itin? = recs().optJSONObject(id)?.let { o -> o.optJSONObject("raw")?.let { Itin(it, o.optBoolean("edit", true), pending(id)) } }
    fun pending(id: String): Int = queue().objs().count { it.optString("id") == id }
    fun pendingAll(): Int = queue().length()
    private fun keep(it: Itin) { recs().put(it.id, JSONObject().put("raw", it.raw).put("edit", it.canEdit)); save(); _tick.value++ }
    /** Every trip kept on this phone (for Me › My trips with no signal). */
    fun kept(): List<Itin> = recs().keys().asSequence().mapNotNull { cached(it) }.toList()

    // --- field trips ---------------------------------------------------------------------------------------------
    suspend fun loadList(q: String = ""): Reply {
        val r = api.req("GET", "/api/itin" + q)
        if (r.ok) { val a = r.json.optJSONArray("items") ?: JSONArray(); if (q.isBlank()) { store.put("list", a); save() }; _list.value = a.objs() }
        return r
    }
    suspend fun alerts(): List<JSONObject> = api.req("GET", "/api/itin?alerts=1").json.optJSONArray("alerts").objs()
    suspend fun settings(): JSONObject? = api.req("GET", "/api/itin?settings=1").takeIf { it.ok }?.json?.optJSONObject("settings")?.also { store.put("settings", it); save() } ?: store.optJSONObject("settings")
    suspend fun saveSettings(s: JSONObject): Reply = api.req("POST", "/api/itin/settings", s)

    /** Fresh from the server (and kept); with no signal, the kept copy. Progress comes with [progress]. */
    suspend fun open(id: String, progress: Boolean = false): Triple<Itin?, TProgress?, String?> {
        flush()
        val r = api.req("GET", "/api/itin/" + Api.enc(id) + if (progress) "?progress=1" else "")
        if (r.ok) {
            val o = r.json.optJSONObject("itin") ?: return Triple(cached(id), null, null)
            val it = Itin(o, r.json.optJSONObject("can")?.optBoolean("edit", true) ?: true, pending(id))
            // changes still waiting here stay on top of the server's copy
            val mine = cached(id)?.takeIf { pending(id) > 0 } ?: it
            if (pending(id) == 0) keep(it)
            return Triple(mine, TProgress.from(r.json.optJSONObject("progress")), null)
        }
        return Triple(cached(id), null, if (r.status == 0) null else itinReason(r.reason))
    }

    suspend fun suggest(body: JSONObject): Reply = api.req("POST", "/api/itin/suggest", body)
    suspend fun optimise(body: JSONObject): Reply = api.req("POST", "/api/itin/optimise", body)
    suspend fun create(body: JSONObject): Pair<Itin?, String?> {
        val r = api.req("POST", "/api/itin", body)
        val o = r.json.optJSONObject("itin")
        if (r.ok && o != null) { val it = Itin(o); keep(it); loadList(); return it to null }
        return null to if (r.status == 0) t("No signal. Try again when you're connected.", "Hakuna mtandao. Jaribu tena ukiunganishwa.") else itinReason(r.reason)
    }

    /**
     * A change: [local] (already re-timed on the phone) is kept and shown at once; [body] ({action, …}) waits in the queue
     * and is sent now if there is signal. Returns null when sent (or waiting for signal), or why it was refused.
     */
    suspend fun change(local: Itin, body: JSONObject): String? {
        lock.withLock { queue().put(JSONObject().put("id", local.id).put("body", body)); keep(local) }
        return flush()[local.id]
    }

    /** A change that needs the server's answer (share, summary, re-plan preview): no queue. */
    suspend fun ask(id: String, body: JSONObject): Reply {
        val v = cached(id)?.v ?: 0
        var r = api.req("POST", "/api/itin/" + Api.enc(id), JSONObject(body.toString()).put("v", v))
        if (r.status == 409) r.json.optJSONObject("current")?.let { cur -> keep(Itin(cur)); r = api.req("POST", "/api/itin/" + Api.enc(id), JSONObject(body.toString()).put("v", cur.optInt("v"))) }
        r.json.optJSONObject("itin")?.let { if (r.ok && pending(id) == 0) keep(Itin(it, cached(id)?.canEdit ?: true)) }
        return r
    }

    /** Sends what's waiting, oldest first. Returns why a change was refused, by trip id. */
    suspend fun flush(): Map<String, String> = lock.withLock {
        val errs = HashMap<String, String>()
        while (queue().length() > 0) {
            val q = queue().getJSONObject(0); val id = q.optString("id"); val body = q.getJSONObject("body")
            val base = recs().optJSONObject(id)?.optJSONObject("raw")
            var r = api.req("POST", "/api/itin/" + Api.enc(id), JSONObject(body.toString()).put("v", base?.optInt("v") ?: 0))
            if (r.status == 409) r.json.optJSONObject("current")?.let { cur -> r = api.req("POST", "/api/itin/" + Api.enc(id), JSONObject(body.toString()).put("v", cur.optInt("v"))) }
            if (r.status == 0 || r.status >= 500 || r.status == 429) break // no signal: keep it for later
            queue().remove(0)
            if (r.ok) {
                r.json.optJSONObject("itin")?.let { o -> if (pending(id) == 0) keep(Itin(o, recs().optJSONObject(id)?.optBoolean("edit", true) ?: true)) else recs().optJSONObject(id)?.optJSONObject("raw")?.put("v", o.optInt("v")) }
            } else {
                errs[id] = itinReason(r.reason)
                // refused: back to what the server has
                r.json.optJSONObject("current")?.let { keep(Itin(it)) }
            }
        }
        save(); _tick.value++
        errs
    }

    // --- vehicles ------------------------------------------------------------------------------------------------
    private val _vehicles = MutableStateFlow(store.optJSONArray("vehicles").objs().map { Vehicle(it) })
    val vehicles: StateFlow<List<Vehicle>> = _vehicles
    suspend fun loadVehicles(): Boolean {
        val r = api.req("GET", "/api/vehicles"); val a = r.json.optJSONArray("vehicles") ?: return false
        store.put("vehicles", a); save(); _vehicles.value = a.objs().map { Vehicle(it) }; return true
    }
    suspend fun vehicle(body: JSONObject): Reply = api.req("POST", "/api/vehicles", body).also { if (it.ok) loadVehicles() }

    // --- fuel ----------------------------------------------------------------------------------------------------
    suspend fun prices(ck: String = ""): List<FuelCountry> {
        val r = api.req("GET", "/api/fuel?prices=1" + if (ck.isNotBlank()) "&ck=$ck" else "")
        val o = r.json.optJSONObject("countries")?.also { store.put("prices", it); save() } ?: store.optJSONObject("prices") ?: return emptyList()
        return o.keys().asSequence().map { FuelCountry(o.getJSONObject(it)) }.toList()
    }
    suspend fun fuel(body: JSONObject): Reply = api.req("POST", "/api/fuel", body)
    suspend fun logs(itinId: String): List<FuelLog> {
        val r = api.req("GET", "/api/fuel?itin=" + Api.enc(itinId))
        val key = "logs:$itinId"
        val a = r.json.optJSONArray("logs")?.also { store.put(key, it); save() } ?: store.optJSONArray(key)
        val waiting = store.optJSONArray("logq").objs().filter { it.optString("itinId") == itinId }.map { FuelLog(JSONObject(it.toString()).put("waiting", true)) }
        // a receipt taken on this phone shows from the phone
        return a.objs().map { o -> FuelLog(store.optString("rcpt:" + o.optString("lid")).takeIf { it.isNotBlank() }?.let { JSONObject(o.toString()).put("localReceipt", it) } ?: o) } + waiting
    }
    /**
     * A fuel log. Its receipt photo (kept on the phone as "localReceipt") is uploaded first (/api/pin-photo kind=receipt)
     * and its id goes in "receipt". With no signal the log waits on the phone and is sent with the next one.
     */
    suspend fun addLog(body: JSONObject): Reply {
        val r = sendLog(body)
        if (r.status == 0) { (store.optJSONArray("logq") ?: JSONArray().also { store.put("logq", it) }).put(body); save() }
        else sendLogs()
        return r
    }
    private suspend fun sendLog(body: JSONObject): Reply {
        val local = body.optString("localReceipt")
        if (local.isNotBlank() && body.optString("receipt").isBlank()) {
            val f = File(local)
            if (f.exists()) {
                val up = api.receipt(body.optString("itinId"), f)
                if (up.status == 0) return up
                up.json.optString("id").takeIf { up.ok && it.isNotBlank() }?.let { body.put("receipt", it) }
            }
        }
        // the photo stays on this phone for the list; the server only gets its id
        val send = JSONObject(body.toString()).apply { remove("localReceipt") }
        return api.req("POST", "/api/fuel", send).also { r -> if (r.ok) r.json.optJSONObject("log")?.optString("lid")?.let { lid -> if (local.isNotBlank()) { store.put("rcpt:$lid", local); save() } } }
    }
    private suspend fun sendLogs() {
        val q = store.optJSONArray("logq") ?: return
        val rest = JSONArray()
        q.objs().forEach { b -> val r = sendLog(b); if (r.status == 0) rest.put(b) }
        store.put("logq", rest); save()
    }

    // --- group trips (admins) ------------------------------------------------------------------------------------
    suspend fun groups(): List<JSONObject>? = api.req("GET", "/api/group-trips").takeIf { it.ok }?.json?.optJSONArray("items")?.objs()?.also { store.put("groups", JSONArray(it)); save() } ?: store.optJSONArray("groups")?.objs()
    suspend fun group(id: String): Pair<GroupTrip?, String?> {
        val r = api.req("GET", "/api/group-trips/" + Api.enc(id))
        r.json.optJSONObject("trip")?.let { store.put("group:$id", it); save(); return GroupTrip(it) to null }
        return store.optJSONObject("group:$id")?.let { GroupTrip(it) } to (if (r.status == 0) null else itinReason(r.reason))
    }
    suspend fun groupCreate(body: JSONObject): Reply = api.req("POST", "/api/group-trips", body)
    suspend fun groupChange(g: GroupTrip, body: JSONObject): Reply {
        var r = api.req("POST", "/api/group-trips/" + Api.enc(g.id), JSONObject(body.toString()).put("v", g.v))
        if (r.status == 409) r.json.optJSONObject("current")?.let { cur -> r = api.req("POST", "/api/group-trips/" + Api.enc(g.id), JSONObject(body.toString()).put("v", cur.optInt("v"))) }
        r.json.optJSONObject("trip")?.let { store.put("group:${g.id}", it); save() }
        return r
    }
}

/** A hook for Chat (feat-chata): when Chat is in the app it sets this, and trips show "Share in a chat". */
object TripShare {
    /** shareToChat(itineraryId): posts the trip's card (GET /api/itin/<id>?card=1) into a chat. Null = Chat isn't here. */
    @Volatile var shareToChat: ((String) -> Unit)? = null
}

/** Why the server refused a trip change, in words (the trip reasons first, then the app's usual ones). */
fun itinReason(reason: String?): String = when (reason) {
    "forbidden" -> t("This isn’t one of your trips.", "Hii si mojawapo ya safari zako.")
    "read-only" -> t("You can see this trip but not change it.", "Unaweza kuona safari hii lakini huwezi kuibadilisha.")
    "named-only" -> t("Sign in with your own PIN to plan trips.", "Ingia kwa PIN yako mwenyewe ili kupanga safari.")
    "not-found" -> t("That trip isn’t there any more.", "Safari hiyo haipo tena.")
    "too-many" -> t("Too many stops: at most 40 on one trip.", "Vituo vingi mno: visizidi 40 kwa safari moja.")
    "dates" -> t("Check the dates: at most 14 days, the end after the start.", "Angalia tarehe: zisizidi siku 14, mwisho baada ya mwanzo.")
    "stops" -> t("Some of those sites have no location yet.", "Baadhi ya maeneo hayo bado hayana mahali.")
    "start" -> t("Choose where the trip starts.", "Chagua safari inapoanzia.")
    "hours" -> t("Check the working hours.", "Angalia saa za kazi.")
    "in-use" -> t("That vehicle is on a planned trip.", "Gari hilo liko kwenye safari iliyopangwa.")
    "bad-type", "bad-cc", "bad-fuel" -> t("Check the vehicle’s type, engine size and fuel.", "Angalia aina ya gari, ukubwa wa injini na mafuta.")
    "bad-litres" -> t("Litres should be between 0.5 and 400.", "Lita ziwe kati ya 0.5 na 400.")
    "bad-price" -> t("Check the price.", "Angalia bei.")
    "bad-odometer" -> t("The odometer is lower than the last fill-up.", "Odomita iko chini kuliko ujazaji uliopita.")
    "no-vehicle" -> t("Choose a vehicle first.", "Chagua gari kwanza.")
    "passport-number" -> t("Passport numbers are never kept. Remove it and save again.", "Namba za pasipoti hazihifadhiwi kamwe. Iondoe kisha uhifadhi tena.")
    "bad-date" -> t("Check the date.", "Angalia tarehe.")
    "bad-currency" -> t("Choose a currency.", "Chagua sarafu.")
    "bad-item" -> t("Give the item a title and a date.", "Kipengele kipewe kichwa na tarehe.")
    "no-storage" -> t("Trips aren’t switched on yet on the server.", "Safari bado hazijawashwa kwenye seva.")
    "no-office" -> t("No office location is set for this country yet. Choose another place to start, or ask an admin to set it (Office › Vehicles › Trip settings).", "Mahali pa ofisi bado hapajawekwa kwa nchi hii. Chagua mahali pengine pa kuanzia, au muombe msimamizi apaweke (Ofisi › Magari › Mipangilio ya safari).")
    "receipt" -> t("That receipt photo couldn't be used. Take it again.", "Picha hiyo ya risiti haikuweza kutumika. Ipige tena.")
    "bad-name" -> t("Give the vehicle a name or a number plate.", "Lipe gari jina au namba.")
    "bad-consumption" -> t("Check the L/100 km.", "Angalia L/100 km.")
    "bad-amount" -> t("Check the amounts.", "Angalia viwango.")
    "office" -> t("Check the office location.", "Angalia mahali pa ofisi.")
    "too-large" -> t("That photo is too large.", "Picha hiyo ni kubwa mno.")
    "no-mail" -> t("E-mail isn’t set up yet.", "Barua pepe bado haijawekwa.")
    "no-recipients" -> t("Nobody in the group has an e-mail address.", "Hakuna mtu kwenye kikundi mwenye anwani ya barua pepe.")
    "cap", "day-cap", "month-cap" -> t("The e-mail limit is reached. Try again later.", "Kikomo cha barua pepe kimefikiwa. Jaribu tena baadaye.")
    "bad-request" -> t("Something is missing. Check the form and try again.", "Kuna kinachokosekana. Angalia fomu kisha ujaribu tena.")
    else -> reasonText(reason)
}
