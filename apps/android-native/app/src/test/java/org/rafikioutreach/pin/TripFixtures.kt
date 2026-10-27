package org.rafikioutreach.pin

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Trips (feat-itina): records shaped exactly like S/itin-contract-changes.md (v1), and a fake server for the screens.
 * Day 0 is today: Kitale office → Bishop Luge (school) → Kapsaret Borehole → Chepkongi Dispensary → overnight Iten.
 * Day 1: Iten → Kaptagat Water Point → Moiben Primary School (a long murram drive: after school closes) → back.
 */
object TripFixtures {
    val today: LocalDate get() = LocalDate.now()
    fun stop(sid: String, id: String, name: String, lat: Double, lng: Double, type: String, visit: Int, window: JSONObject? = null, why: List<Pair<String, String>> = emptyList(), status: String = "planned") =
        JSONObject().put("sid", sid).put("id", id).put("name", name).put("lat", lat).put("lng", lng).put("type", type).put("visitMin", visit).put("window", window ?: JSONObject.NULL)
            .put("fixed", JSONObject.NULL).put("locked", false).put("day", 0).put("pinDay", false).put("status", status).put("checkin", JSONObject.NULL).put("note", "")
            .put("why", JSONArray(why.map { (en, sw) -> JSONObject().put("code", "x").put("en", en).put("sw", sw) }))
    private fun school() = JSONObject().put("open", "08:00").put("close", "15:30").put("src", "type")
    private fun leg(km: Int, min: Int, surface: Pair<String, String>, rain: Pair<String, String>?, risk: String = "ok", level: String = "dry") = JSONObject().put("km", km).put("min", min).put("maxMin", (min * 1.4).toInt()).put("src", "trip")
        .put("key", "k$km").put("roads", true).put("riskLevel", risk).put("roadLevel", level)
        .put("chips", JSONObject().put("surface", JSONObject().put("en", surface.first).put("sw", surface.second))
            .put("time", JSONObject().put("en", "~" + (if (min >= 60) "${min / 60} h " + "%02d".format(min % 60) else "$min min")).put("sw", "~" + (if (min >= 60) "saa\u00A0${min / 60}\u00A0dak\u00A0" + "%02d".format(min % 60) else "dak\u00A0$min")))
            .apply { if (rain != null) put("rain", JSONObject().put("en", rain.first).put("sw", rain.second)) })
        .put("warnings", JSONArray())

    fun itin(id: String = "it_1", status: String = "planned", from: LocalDate = today, people: List<String> = listOf("Meril Kiprop", "Grace Wanjiru")): JSONObject {
        val d0 = from.toString(); val d1 = from.plusDays(1).toString()
        return JSONObject().put("id", id).put("v", 3).put("title", "Moiben repairs").put("status", status).put("owner", people.first()).put("people", JSONArray(people)).put("viewers", JSONArray())
            .put("ck", "ke").put("tz", "Africa/Nairobi").put("from", d0).put("to", d1)
            .put("hours", JSONObject().put("start", "07:30").put("end", "17:30")).put("lunch", JSONObject().put("at", "12:30").put("min", 45).put("skip", false))
            .put("start", JSONObject().put("kind", "office").put("name", "Kitale office").put("lat", 1.015).put("lng", 35.0))
            .put("end", JSONObject().put("kind", "same").put("name", "Kitale office").put("lat", 1.015).put("lng", 35.0))
            .put("vehicleId", "vh_1").put("passengers", 2).put("cargoKg", 0)
            .put("stops", JSONArray()
                .put(stop("s1", "104", "Bishop Luge Memorial", 0.385, 35.0, "School", 60, school(), listOf("due a visit · 8 months" to "Ziara inahitajika · miezi 8")))
                .put(stop("s2", "105", "Kapsaret Borehole", 0.47, 35.25, "Borehole well", 45, null, listOf("open repair · urgent" to "matengenezo yaliyo wazi · ya dharura")))
                .put(stop("s3", "101", "Chepkongi Dispensary", 0.52, 35.28, "Health", 50, JSONObject().put("open", "08:00").put("close", "17:00"), listOf("water test due" to "kipimo cha maji kinahitajika")))
                .put(stop("s4", "106", "Kaptagat Water Point", 0.42, 35.47, "Borehole well", 45).put("day", 1))
                .put(stop("s5", "107", "Moiben Primary School", 0.82, 35.39, "School", 60, school(), listOf("community report 2 days ago" to "ripoti ya jamii siku 2 zilizopita")).put("day", 1)))
            .put("days", JSONArray()
                .put(JSONObject().put("date", d0).put("stops", JSONArray(listOf("s1", "s2", "s3"))).put("startAt", JSONObject().put("name", "Kitale office").put("lat", 1.015).put("lng", 35.0))
                    .put("endAt", JSONObject().put("kind", "overnight").put("name", "Iten").put("lat", 0.67).put("lng", 35.51))
                    .put("overnight", JSONObject().put("town", "Iten").put("lat", 0.67).put("lng", 35.51).put("suggested", true).put("why", JSONObject().put("en", "near tomorrow's first stop (25 min)").put("sw", "karibu na kituo cha kwanza cha kesho (dak 25)"))
                        .put("lodging", JSONObject().put("name", "Kerio View Hotel").put("phone", "").put("kes", 3500).put("note", "")))
                    .put("km", 157).put("driveMin", 232).put("endsAt", "14:42").put("riskLevel", "caution").put("weather", JSONObject().put("rainMm", 4).put("roadLevel", "light"))
                    .put("warnings", JSONArray().put(JSONObject().put("code", "ROAD_REPORT").put("level", "caution").put("leg", "S>s1").put("en", "Potholes reported 3 km before the site by Grace, 12 days ago.").put("sw", "Ripoti: Mashimo barabarani, km 3 kabla ya kufika — Grace, siku 12 zilizopita."))))
                .put(JSONObject().put("date", d1).put("stops", JSONArray(listOf("s4", "s5"))).put("startAt", JSONObject().put("name", "Iten").put("lat", 0.67).put("lng", 35.51))
                    .put("endAt", JSONObject().put("kind", "home").put("name", "Kitale office").put("lat", 1.015).put("lng", 35.0)).put("overnight", JSONObject.NULL)
                    .put("km", 330).put("driveMin", 525).put("endsAt", "18:40").put("riskLevel", "difficult").put("weather", JSONObject().put("rainMm", 25).put("roadLevel", "heavy"))
                    .put("warnings", JSONArray()
                        .put(JSONObject().put("code", "SWAP_DAY").put("level", "difficult").put("en", "Heavy rain ${org.rafikioutreach.pin.ui.Dates.weekday(from.plusDays(1)).substringBefore(' ')} on the murram road to Moiben. Swap with ${org.rafikioutreach.pin.ui.Dates.weekday(from).substringBefore(' ')}?")
                            .put("sw", "Mvua kubwa kwenye barabara ya murram kwenda Moiben. Badilisha na siku nyingine?").put("fix", JSONObject().put("action", "swap").put("day", 1).put("with", 0)))
                        .put(JSONObject().put("code", "AFTER_CLOSE").put("level", "caution").put("sid", "s5").put("en", "Arrives 15:40, after the school closes").put("sw", "Inafika 15:40, baada ya shule kufungwa").put("fix", JSONObject().put("action", "move").put("sid", "s5").put("toDay", 0))))))
            .put("unplanned", JSONArray())
            .put("legs", JSONObject()
                .put("S>s1", leg(70, 112, "62 km tarmac · 8 km murram" to "km 62 lami · km 8 murram", "Rain 4 mm" to "Mvua mm 4", "caution", "light"))
                .put("s1>s2", leg(32, 40, "32 km tarmac" to "km 32 lami", null))
                .put("s2>s3", leg(9, 30, "6 km tarmac · 3 km murram" to "km 6 lami · km 3 murram", null))
                .put("s3>T:Iten", leg(46, 50, "46 km tarmac" to "km 46 lami", null))
                .put("T:Iten>s4", leg(18, 25, "18 km tarmac" to "km 18 lami", null))
                .put("s4>s5", leg(190, 380, "150 km tarmac · 40 km murram" to "km 150 lami · km 40 murram", "Rain 25 mm" to "Mvua mm 25", "difficult", "heavy"))
                .put("s5>E", leg(122, 120, "122 km tarmac" to "km 122 lami", null)))
            .put("cost", JSONObject().put("currency", "KES")
                .put("fuel", JSONObject().put("litres", 51.6).put("lPer100", 10.6).put("basis", JSONObject().put("en", "Hilux 2.4 diesel pickup · model 9.0 L/100 km, murram +25%").put("sw", "Hilux 2.4 dizeli · modeli 9.0 L/100 km, murram +25%"))
                    .put("price", JSONObject().put("kes", 171.6).put("fuel", "diesel").put("town", "Eldoret").put("source", "EPRA").put("from", "2026-10-15").put("to", "2026-11-14").put("kind", "official"))
                    .put("kes", 8855).put("reason", JSONObject.NULL).put("label", JSONObject().put("en", "Fuel: KES 171.60/L diesel, Eldoret, EPRA 15 Oct–14 Nov").put("sw", "Mafuta: KES 171.60/L dizeli, Eldoret, EPRA 15 Okt–14 Nov")))
                .put("days", JSONArray().put(JSONObject().put("date", d0).put("km", 157).put("totalKes", 11695)).put(JSONObject().put("date", d1).put("km", 330).put("totalKes", 10660)))
                .put("est", JSONObject().put("fuelKes", 8855).put("lunchKes", 2000).put("perDiemKes", 6000).put("lodgingKes", 3500).put("extrasKes", 2000).put("totalKes", 22355))
                .put("actual", JSONObject().put("fuelKes", 6893).put("fuelLitres", 40.2).put("extrasKes", 200).put("lodgingKes", 0).put("totalKes", 7093).put("logs", 1)))
            .put("packing", JSONArray()
                .put(JSONObject().put("key", "auto:strips").put("auto", true).put("done", false).put("en", "Water test strips (1 site)").put("sw", "Vipande vya kupima maji (eneo 1)"))
                .put(JSONObject().put("key", "auto:parts:105").put("auto", true).put("done", true).put("en", "Parts for Kapsaret Borehole: rising main pipes").put("sw", "Vipuri vya Kapsaret Borehole: mabomba"))
                .put(JSONObject().put("key", "auto:consent").put("auto", true).put("done", false).put("en", "Consent forms (1 site with a story moment)").put("sw", "Fomu za idhini (eneo 1 lenye hadithi)"))
                .put(JSONObject().put("key", "std:firstaid").put("auto", true).put("done", false).put("en", "First aid kit").put("sw", "Sanduku la huduma ya kwanza"))
                .put(JSONObject().put("key", "std:offline").put("auto", true).put("done", false).put("en", "Offline map saved for this trip").put("sw", "Ramani ya nje ya mtandao imehifadhiwa kwa safari hii")))
            .put("extras", JSONArray().put(JSONObject().put("xid", "x1").put("day", 0).put("kind", "parking").put("label", "Parking Eldoret").put("kes", 200).put("actual", true).put("receipt", JSONObject.NULL)))
            .put("share", JSONObject.NULL).put("warnings", JSONArray()).put("created", "2026-10-20T08:00:00Z").put("updated", "2026-10-25T08:00:00Z").put("by", people.first())
    }
    fun row(it: JSONObject) = JSONObject().put("id", it.getString("id")).put("title", it.getString("title")).put("owner", it.getString("owner")).put("people", it.getJSONArray("people"))
        .put("from", it.getString("from")).put("to", it.getString("to")).put("status", it.getString("status")).put("ck", "ke").put("projects", JSONArray(listOf("104", "105", "101", "106", "107")))

    fun vehicles() = JSONObject().put("ok", true).put("vehicles", JSONArray()
        .put(JSONObject().put("vid", "vh_1").put("name", "Hilux KDA 123X").put("plate", "KDA 123X").put("type", "pickup").put("cc", 2400).put("fuel", "diesel").put("lPer100", JSONObject.NULL).put("owner", JSONObject.NULL)
            .put("model", JSONObject().put("lPer100", 9.0).put("band", "2001–2500 cc")).put("real", JSONObject().put("lPer100", 10.9).put("logs", 4).put("km", 1830)).put("use", JSONObject().put("lPer100", 10.5).put("src", "blend")))
        .put(JSONObject().put("vid", "vh_2").put("name", "Meril's motorbike").put("plate", "KMEX 456").put("type", "motorbike").put("cc", 150).put("fuel", "petrol").put("owner", "Meril Kiprop")
            .put("model", JSONObject().put("lPer100", 2.6).put("band", "126–200 cc")).put("use", JSONObject().put("lPer100", 2.6).put("src", "model"))))

    fun prices() = JSONObject().put("ok", true).put("countries", JSONObject()
        .put("ke", JSONObject().put("ck", "ke").put("source", "EPRA").put("sourceName", "Energy and Petroleum Regulatory Authority").put("from", "2026-10-15").put("to", "2026-11-14").put("fetchedAt", "2026-10-15T06:00:00Z")
            .put("kind", "official").put("currency", "KES")
            .put("towns", JSONObject().put("Nairobi", JSONObject().put("petrol", 184.52).put("diesel", 171.47)).put("Eldoret", JSONObject().put("petrol", 187.7).put("diesel", 174.65))
                .put("Kitale", JSONObject().put("petrol", 188.4).put("diesel", 175.35)).put("Kisumu", JSONObject().put("petrol", 187.2).put("diesel", 174.15)).put("Nakuru", JSONObject().put("petrol", 185.9).put("diesel", 172.85))
                .put("Mombasa", JSONObject().put("petrol", 181.38).put("diesel", 168.33)))
            .put("overrides", JSONObject().put("Eldoret", JSONObject().put("petrol", 189.84).put("diesel", 177.0).put("by", "Jessica")))
            .put("status", JSONObject().put("ok", true)))
        .put("tz", JSONObject().put("ck", "tz").put("source", "EWURA").put("from", "2026-10-01").put("to", "2026-10-31").put("fetchedAt", "2026-10-01T06:00:00Z").put("kind", "cached").put("currency", "TZS")
            .put("towns", JSONObject().put("Dar es Salaam", JSONObject().put("petrol", 2905.0).put("diesel", 2871.0)).put("Arusha", JSONObject().put("petrol", 2985.0).put("diesel", 2951.0)))
            .put("status", JSONObject().put("ok", false).put("reason", "blocked")))
        .put("mw", JSONObject().put("ck", "mw").put("kind", "none").put("status", JSONObject().put("ok", false).put("reason", "no-source"))))

    fun group(withPrivate: Boolean = true): JSONObject {
        val y = today.year + 1
        return JSONObject().put("id", "gt_1").put("v", 2).put("name", "Kenya water trip, July $y").put("from", "$y-07-03").put("to", "$y-07-14").put("country", "ke").put("destination", "Kitale, Trans Nzoia")
            .put("summary", "Twelve days drilling with the RAFIKI team, school visits and a safari.").put("public", false).put("leaders", JSONArray().put("Jessica")).put("contact", "info@rafikioutreach.org")
            .apply { if (withPrivate) put("participants", JSONArray()
                .put(JSONObject().put("pid", "p1").put("name", "Sam Lee").put("email", "sam@example.org").put("phone", "+1 555 0100").put("role", "volunteer").put("diet", "vegetarian").put("medical", "asthma").put("passportReminder", true).put("passportExpiry", "$y-02"))
                .put(JSONObject().put("pid", "p2").put("name", "Ann Ochieng").put("email", "ann@example.org").put("role", "donor").put("diet", "").put("medical", "").put("passportReminder", false)))
                .put("payments", JSONArray().put(JSONObject().put("payid", "y1").put("pid", "p1").put("amount", 1200).put("currency", "USD").put("at", "2026-09-01")))
                .put("costs", JSONArray().put(JSONObject().put("cid", "c1").put("label", "Lodging, 11 nights").put("amount", 450).put("currency", "USD").put("per", "person"))
                    .put(JSONObject().put("cid", "c2").put("label", "Safari day").put("amount", 18000).put("currency", "KES").put("per", "person")))
                .put("totals", JSONObject().put("participants", 2).put("perPerson", JSONObject().put("byCurrency", JSONObject().put("USD", 450).put("KES", 18000)).put("USD", 589).put("KES", 76100)).put("group", JSONObject().put("USD", 1178).put("KES", 152200))
                    .put("paid", JSONObject().put("p1", JSONObject().put("USD", 1200))).put("due", JSONObject().put("p2", JSONObject().put("USD", 589))).put("fx", JSONObject().put("at", "2026-10-26").put("USDKES", 129.2))) }
            .put("items", JSONArray()
                .put(JSONObject().put("iid", "i1").put("date", "$y-07-03").put("start", "12:40").put("end", "13:40").put("kind", "flight").put("title", "Fly Nairobi to Eldoret")
                    .put("flight", JSONObject().put("airline", "KQ").put("number", "KQ 640").put("from", "NBO").put("to", "EDL").put("depart", "12:40").put("arrive", "13:40")).put("location", JSONObject().put("name", "Eldoret International Airport").put("lat", 0.4045).put("lng", 35.2389)).put("remind", JSONObject().put("before", "24h")))
                .put(JSONObject().put("iid", "i2").put("date", "$y-07-03").put("start", "14:00").put("kind", "transfer").put("title", "Airport pick-up").put("notes", "Look for the RAFIKI sign"))
                .put(JSONObject().put("iid", "i3").put("date", "$y-07-03").put("start", "15:30").put("kind", "lodging").put("title", "Check in").put("lodging", JSONObject().put("name", "Kitale Club").put("address", "Kitale"))
                    .put("location", JSONObject().put("name", "Kitale Club").put("lat", 1.016).put("lng", 35.003)))
                .put(JSONObject().put("iid", "i4").put("date", "$y-07-04").put("start", "08:00").put("end", "16:00").put("kind", "project").put("title", "Drilling day: Kapsaret Borehole").put("projectId", "105")
                    .put("location", JSONObject().put("name", "Kapsaret").put("lat", 0.47).put("lng", 35.25)))
                .put(JSONObject().put("iid", "i5").put("date", "$y-07-05").put("start", "09:00").put("end", "12:00").put("kind", "school").put("title", "Visit Bishop Luge Memorial")))
            .put("packing", "Passport and visa\nWork clothes and closed shoes for drilling days\nSun hat and sunscreen").put("expect", "Drilling days are long, dusty and hot.\nMalaria: ask about tablets.")
            .put("emergency", JSONArray().put(JSONObject().put("name", "Jessica").put("phone", "+254 700 000 000").put("role", "Trip leader")))
            .put("share", JSONObject.NULL)
    }

    /** The projects in the trip, as /api/pin lists them. */
    fun snapshot(who: String, admin: Boolean) = JSONObject().put("ok", true).put("who", who).put("admin", admin).put("reviewOpen", 0)
        .put("projects", JSONArray().apply {
            listOf(Triple("104", "Bishop Luge Memorial", "School"), Triple("105", "Kapsaret Borehole", "Borehole well"), Triple("101", "Chepkongi Dispensary", "Health"),
                Triple("106", "Kaptagat Water Point", "Borehole well"), Triple("107", "Moiben Primary School", "School"), Triple("108", "Sergoit Borehole", "Borehole well")).forEachIndexed { i, (id, title, type) ->
                put(JSONObject().put("id", id).put("title", title).put("country", "Kenya").put("ck", "ke").put("area", if (i < 3) "Kapsaret" else "Moiben").put("type", type).put("status", "Completed").put("pump", "")
                    .put("gps", JSONArray().put(0.4 + i * 0.07).put(35.1 + i * 0.06)))
            }
        })
        .put("repairs", JSONObject().put("105", JSONObject().put("state", "notworking").put("by", "Bertin").put("at", "2026-10-18T08:00:00Z").put("urgency", "urgent").put("parts", "Rising main pipes").put("t", "Kapsaret Borehole")))
        .put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())

    /** A fake server that keeps one trip and answers like /api/itin and friends. [posts] records every request. */
    class Server(val posts: MutableList<String>, var it: JSONObject = itin(), val who: String = "Meril Kiprop", val admin: Boolean = false) {
        var offline = false
        fun answer(method: String, url: String, body: String): Pair<Int, String> {
            if (offline) return 0 to ""
            posts += "$method $url $body"
            val b = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
            return when {
                url.contains("/api/itin?alerts=1") -> 200 to JSONObject().put("ok", true).put("alerts", JSONArray().put(JSONObject().put("at", "2026-10-26T09:10:00Z").put("id", "it_1").put("title", "Moiben repairs").put("by", "Meril Kiprop").put("kind", "late").put("en", "Running 40 min late; 1 stop moved to tomorrow").put("sw", "Imechelewa dakika 40; kituo 1 kimehamishiwa kesho"))).toString()
                url.contains("/api/itin?settings=1") -> 200 to JSONObject().put("ok", true).put("settings", JSONObject().put("lunchKes", 500).put("perDiemKes", 1500).put("lodgingKes", 3500).put("hours", JSONObject().put("start", "07:30").put("end", "17:30")).put("lunch", JSONObject().put("at", "12:30").put("min", 45))).toString()
                method == "GET" && Regex("/api/itin(\\?|$)").containsMatchIn(url) -> 200 to JSONObject().put("ok", true).put("items", JSONArray().put(row(it))).toString()
                method == "GET" && url.contains("/api/itin/") -> 200 to JSONObject().put("ok", true).put("itin", it).put("can", JSONObject().put("edit", true).put("admin", admin)).apply {
                    if (url.contains("progress=1")) put("progress", JSONObject().put("day", 0).put("now", "11:05").put("stops", JSONArray()
                        .put(JSONObject().put("sid", "s1").put("state", "done").put("planned", "09:22").put("at", "09:41").put("lateMin", 19).put("by", "Meril Kiprop"))
                        .put(JSONObject().put("sid", "s2").put("state", "next").put("planned", "11:02")))
                        .put("where", JSONArray().put(JSONObject().put("name", "Meril Kiprop").put("lat", 0.44).put("lng", 35.18).put("at", java.time.Instant.now().minusSeconds(240).toString()).put("acc", 12))))
                }.toString()
                url.endsWith("/api/itin/suggest") -> 200 to JSONObject().put("ok", true).put("areas", JSONArray(listOf("Kapsaret", "Moiben"))).put("items", JSONArray()
                    .put(JSONObject().put("id", "105").put("name", "Kapsaret Borehole").put("area", "Kapsaret").put("ck", "ke").put("lat", 0.47).put("lng", 35.25).put("score", 92).put("why", JSONArray().put(JSONObject().put("code", "urgent").put("en", "open repair · urgent").put("sw", "matengenezo yaliyo wazi · ya dharura"))))
                    .put(JSONObject().put("id", "104").put("name", "Bishop Luge Memorial").put("area", "Kapsaret").put("ck", "ke").put("lat", 0.385).put("lng", 35.0).put("score", 70).put("why", JSONArray().put(JSONObject().put("code", "overdue").put("en", "due a visit · 8 months").put("sw", "Ziara inahitajika · miezi 8"))))
                    .put(JSONObject().put("id", "101").put("name", "Chepkongi Dispensary").put("area", "Kapsaret").put("ck", "ke").put("lat", 0.52).put("lng", 35.28).put("score", 41).put("why", JSONArray().put(JSONObject().put("code", "test").put("en", "water test due").put("sw", "kipimo cha maji kinahitajika")).put(JSONObject().put("code", "community").put("en", "community report 2 days ago").put("sw", "ripoti ya jamii siku 2 zilizopita"))))).toString()
                url.endsWith("/api/itin/optimise") -> 200 to JSONObject().put("ok", true).put("plan", itin().apply { remove("id"); remove("v") }).toString()
                method == "POST" && url.endsWith("/api/itin") -> 201 to JSONObject().put("ok", true).put("itin", itin("it_2")).toString()
                method == "POST" && url.contains("/api/itin/") -> { apply(b); 200 to JSONObject().put("ok", true).put("itin", it).apply { if (b.optString("action") == "share") { put("url", "https://pin.rafikioutreach.org/pin-trip?t=abc123"); put("token", "abc123") } }.toString() }
                url.contains("/api/trip/week") -> 200 to JSONObject().put("ok", true).put("bestDay", JSONObject().put("date", today.plusDays(2).toString()).put("name", JSONObject().put("en", org.rafikioutreach.pin.ui.Dates.weekday(today.plusDays(2))).put("sw", org.rafikioutreach.pin.ui.Dates.weekday(today.plusDays(2)))).put("roadLevel", "dry")).toString()
                url.contains("/api/vehicles") -> 200 to vehicles().toString()
                url.contains("/api/fuel?prices=1") -> 200 to prices().toString()
                url.contains("/api/fuel?itin=") -> 200 to JSONObject().put("ok", true).put("logs", JSONArray().put(JSONObject().put("lid", "fl_1").put("vid", "vh_1").put("itinId", "it_1").put("at", "2026-10-26T06:40:00Z").put("by", "Meril Kiprop").put("litres", 40.2).put("kesPerL", 171.47).put("totalKes", 6893).put("odometer", 120455).put("full", true).put("town", "Kitale"))).toString()
                url.contains("/api/fuel") -> 200 to JSONObject().put("ok", true).put("log", JSONObject().put("lid", "fl_2")).put("results", JSONObject().put("ke", JSONObject().put("ok", true)).put("tz", JSONObject().put("ok", false).put("reason", "blocked"))).toString()
                url.contains("/api/group-trips/gt_1") && method == "GET" -> 200 to JSONObject().put("ok", true).put("trip", group()).toString()
                url.contains("/api/group-trips/gt_1") -> 200 to JSONObject().put("ok", true).put("trip", group()).apply { if (b.optString("action") == "remind") { put("preview", JSONObject().put("subject", "Reminder: Fly Nairobi to Eldoret tomorrow at 12:40").put("text", "Hello Sam,\nYour flight KQ 640 leaves Nairobi (NBO) at 12:40. Check in online the day before.")); put("to", 2); put("missing", 0); put("sent", 2) } }.toString()
                url.contains("/api/group-trips") && method == "GET" -> 200 to JSONObject().put("ok", true).put("items", JSONArray().put(JSONObject().put("id", "gt_1").put("name", group().getString("name")).put("from", group().getString("from")).put("to", group().getString("to")).put("country", "ke").put("participants", 2).put("public", true))).toString()
                url.contains("/api/group-trips") -> 201 to JSONObject().put("ok", true).put("trip", group()).toString()
                url.contains("/api/pin-photo") -> 201 to JSONObject().put("ok", true).put("id", "rc_1").put("url", "/api/pin-photo?receipt=rc_1").toString()
                url.contains("/api/road-reports") -> 200 to "{\"ok\":true,\"rid\":\"r1\"}"
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                url.contains("team=1") || url.contains("view=team") -> 200 to JSONObject().put("ok", true).put("team", JSONArray().put(JSONObject().put("name", "Meril Kiprop")).put(JSONObject().put("name", "Grace Wanjiru")).put(JSONObject().put("name", "Jessica").put("admin", true))).toString()
                url.contains("view=") -> 200 to "{\"ok\":true,\"items\":[]}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                url.contains("version.json") -> 0 to ""
                url.contains("history=") -> 200 to "{\"ok\":true,\"notes\":[]}"
                url.contains("?id=") -> 200 to "{\"ok\":true,\"last\":null,\"canUndo\":false,\"visits\":[]}"
                method == "GET" && url.contains("/api/pin") -> 200 to snapshot(who, admin).toString()
                else -> 200 to "{\"ok\":true,\"updateId\":\"9\"}"
            }
        }
        /** The server's side of a change: enough of it for the screens to show what the server would. */
        private fun apply(b: JSONObject) {
            val r = JSONObject(it.toString()).put("v", it.optInt("v") + 1)
            when (b.optString("action")) {
                "stops" -> { val ds = b.optJSONArray("days") ?: JSONArray(); for (i in 0 until ds.length()) r.getJSONArray("days").getJSONObject(i).put("stops", ds.getJSONObject(i).getJSONArray("stops"))
                    b.optJSONArray("stops")?.let { a -> for (i in 0 until a.length()) { val s = a.getJSONObject(i); val st = r.getJSONArray("stops"); for (k in 0 until st.length()) if (st.getJSONObject(k).optString("sid") == s.optString("sid")) st.getJSONObject(k).put("fixed", s.opt("fixed")).put("locked", s.optBoolean("locked")).put("visitMin", s.optInt("visitMin")) } } }
                "swap-days" -> { val d = r.getJSONArray("days"); val a = d.getJSONObject(b.optInt("day")).getJSONArray("stops"); d.getJSONObject(b.optInt("day")).put("stops", d.getJSONObject(b.optInt("with")).getJSONArray("stops")); d.getJSONObject(b.optInt("with")).put("stops", a)
                    d.getJSONObject(1).put("warnings", JSONArray()) }
                "checkin" -> { val st = r.getJSONArray("stops"); for (k in 0 until st.length()) if (st.getJSONObject(k).optString("sid") == b.optString("sid")) st.getJSONObject(k).put("status", "done").put("checkin", JSONObject().put("at", b.optString("at")).put("by", who)) }
                "packing" -> { val p = r.getJSONArray("packing"); b.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) for (k in 0 until p.length()) if (p.getJSONObject(k).optString("key") == a.getJSONObject(i).optString("key")) { a.getJSONObject(i).let { x -> if (x.has("done")) p.getJSONObject(k).put("done", x.optBoolean("done")); if (x.optBoolean("removed")) p.getJSONObject(k).put("removed", true) } } }
                    b.optJSONArray("add")?.let { a -> for (i in 0 until a.length()) p.put(JSONObject().put("key", "u:$i").put("auto", false).put("done", false).put("en", a.getJSONObject(i).optString("en")).put("sw", a.getJSONObject(i).optString("en"))) } }
                "update" -> { listOf("status", "vehicleId").forEach { k -> if (b.has(k)) r.put(k, b.get(k)) } }
                "share" -> r.put("share", JSONObject().put("url", "https://pin.rafikioutreach.org/pin-trip?t=abc123"))
                "summary" -> r.put("summary", JSONObject().put("visited", JSONArray().put(JSONObject().put("sid", "s1").put("name", "Bishop Luge Memorial")).put(JSONObject().put("sid", "s2").put("name", "Kapsaret Borehole")).put(JSONObject().put("sid", "s3").put("name", "Chepkongi Dispensary")).put(JSONObject().put("sid", "s4").put("name", "Kaptagat Water Point")))
                    .put("skipped", JSONArray().put(JSONObject().put("sid", "s5").put("name", "Moiben Primary School"))).put("km", JSONObject().put("value", 312).put("src", "gps").put("planned", 298))
                    .put("cost", JSONObject().put("estKes", 22355).put("actualKes", 19950)).put("problems", JSONArray().put(JSONObject().put("id", "105").put("name", "Kapsaret Borehole").put("state", "notworking"))).put("repairsOpened", 1).put("waterTests", 2))
                "replan" -> { if (b.optString("mode") == "push") { val d0 = r.getJSONArray("days").getJSONObject(0); val l = (0 until d0.getJSONArray("stops").length()).map { d0.getJSONArray("stops").getString(it) }
                    d0.put("stops", JSONArray(l.dropLast(1))); val d1 = r.getJSONArray("days").getJSONObject(1); d1.put("stops", JSONArray(listOf(l.last()) + (0 until d1.getJSONArray("stops").length()).map { d1.getJSONArray("stops").getString(it) })) } }
            }
            it = r
        }
    }
}
