package org.rafikioutreach.pin.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToLong

/** Settings › Display › Currency, the same as the web app (money.js) and the iPhone app.
 *  Costs are kept and typed in KES. When another currency is chosen, amounts show converted with "≈" and the KES
 *  amount next to them: "≈ $35 (KES 4,500)". "Automatic" follows the country the phone is in: Kenya → KES,
 *  USA → USD, Uganda → UGX, Tanzania → TZS, Malawi → MWK … When that isn't known (offline, no SIM), the user's home
 *  country comes before the phone's language region, which is often "United States" on phones in Kenya.
 *  Rates come from /api/fx once a day; rough ones until then. */
object Currency {
    data class Cur(val code: String, val en: String, val sw: String, val sym: String, val step: Long, val flag: String)

    val LIST = listOf(
        Cur("USD", "US dollar", "Dola ya Marekani", "$", 1, "🇺🇸"),
        Cur("KES", "Kenyan shilling", "Shilingi ya Kenya", "KES ", 10, "🇰🇪"),
        Cur("UGX", "Ugandan shilling", "Shilingi ya Uganda", "UGX ", 1000, "🇺🇬"),
        Cur("TZS", "Tanzanian shilling", "Shilingi ya Tanzania", "TZS ", 1000, "🇹🇿"),
        Cur("MWK", "Malawian kwacha", "Kwacha ya Malawi", "MWK ", 100, "🇲🇼"),
        Cur("GBP", "British pound", "Pauni ya Uingereza", "£", 1, "🇬🇧"),
        Cur("EUR", "Euro", "Yuro", "€", 1, "🇪🇺"),
        Cur("CAD", "Canadian dollar", "Dola ya Kanada", "CA$", 1, "🇨🇦"),
        Cur("AUD", "Australian dollar", "Dola ya Australia", "A$", 1, "🇦🇺"),
    )
    private val BY = LIST.associateBy { it.code }
    private val ROUGH = mapOf("USD" to 1.0, "KES" to 129.0, "UGX" to 3650.0, "TZS" to 2600.0, "MWK" to 1735.0, "GBP" to 0.77, "EUR" to 0.92, "CAD" to 1.38, "AUD" to 1.52)
    private val EURO = "AT BE CY DE EE ES FI FR GR HR IE IT LT LU LV MT NL PT SI SK".split(" ").toSet()

    /** The currency shown now (a Compose state, so screens redraw when it changes). */
    val code = mutableStateOf("KES")
    /** true when chosen in Settings; false = automatic. */
    var picked = false; private set
    var rates: Map<String, Double> = ROUGH; private set
    /** The day the rates are from ("2026-10-26"), "" while only the rough ones are known. */
    var at = ""; private set
    /** Where the phone is: the country the server sees, else the mobile network or SIM. */
    private var country = ""
    /** The user's home country (the team list, or the one picked at the start), and the phone's language region. */
    private var home = ""
    private var region = ""
    private var got = 0L
    private var sp: android.content.SharedPreferences? = null

    fun forCountry(cc: String): String = when (val c = cc.uppercase()) {
        "US" -> "USD"; "KE" -> "KES"; "UG" -> "UGX"; "TZ" -> "TZS"; "MW" -> "MWK"; "GB" -> "GBP"; "CA" -> "CAD"; "AU" -> "AUD"
        else -> if (c in EURO) "EUR" else ""
    }
    fun name(c: String, sw: Boolean = org.rafikioutreach.pin.Lang.sw) = BY[c]?.let { if (sw) it.sw else it.en } ?: c
    fun flag(c: String) = BY[c]?.flag ?: ""

    /** Called once when the app starts. */
    fun init(ctx: Context, home: String = "") {
        this.home = home
        val p = ctx.getSharedPreferences("rafiki-pin", Context.MODE_PRIVATE); sp = p
        runCatching {
            val j = JSONObject(p.getString("fx", "") ?: "")
            val r = j.optJSONObject("rates"); if (r != null) rates = ROUGH + r.keys().asSequence().associateWith { r.optDouble(it) }.filterValues { it > 0 }
            at = j.optString("at"); country = j.optString("country"); got = j.optLong("got")
        }
        if (country.isBlank()) country = phoneCountry(ctx)
        region = java.util.Locale.getDefault().country
        val own = p.getString("currency", "") ?: ""
        picked = BY.containsKey(own)
        code.value = if (picked) own else auto()
    }
    private fun phoneCountry(ctx: Context): String {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        return listOfNotNull(runCatching { tm?.networkCountryIso }.getOrNull(), runCatching { tm?.simCountryIso }.getOrNull())
            .firstOrNull { it.isNotBlank() && forCountry(it).isNotBlank() } ?: ""
    }
    private fun auto() = autoFor(country, home, region)
    /** Automatic: where the phone is, else the home country, else the language region, else US dollars. */
    fun autoFor(where: String, home: String, region: String): String =
        listOf(where, home, region).map { forCountry(it) }.firstOrNull { it.isNotBlank() } ?: "USD"
    /** The user's home country is known (or changed): automatic follows it when the phone's country isn't known. */
    fun home(cc: String) {
        if (cc == home) return
        home = cc; if (!picked) code.value = auto()
    }

    /** Pick a currency, or null for automatic. */
    fun set(c: String?) {
        if (c != null && BY.containsKey(c)) { picked = true; code.value = c; sp?.edit()?.putString("currency", c)?.apply() }
        else { picked = false; code.value = auto(); sp?.edit()?.remove("currency")?.apply() }
    }

    /** Fresh rates from the server, at most twice a day. */
    suspend fun refresh(server: String) = withContext(Dispatchers.IO) {
        if (at.isNotBlank() && System.currentTimeMillis() - got < 12 * 3600_000L) return@withContext
        runCatching {
            val c = URL(server.trimEnd('/') + "/api/fx").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000; c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) return@runCatching
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val r = j.optJSONObject("rates") ?: return@runCatching
            val nr = r.keys().asSequence().associateWith { r.optDouble(it) }.filterValues { it > 0 }
            if (!j.optBoolean("fallback")) { rates = ROUGH + nr; at = j.optString("at") }
            j.optString("country").takeIf { it.isNotBlank() }?.let { country = it }
            got = System.currentTimeMillis()
            sp?.edit()?.putString("fx", JSONObject().put("rates", JSONObject(rates)).put("at", at).put("country", country).put("got", got).toString())?.apply()
            if (!picked) withContext(Dispatchers.Main) { code.value = auto() }
        }
    }

    private fun rate(c: String) = rates[c] ?: ROUGH[c] ?: 1.0
    private fun round(v: Double, to: String): Double {
        var st = (BY[to]?.step ?: 1L).toDouble()
        if (st > 1 && v < st * 10) st = maxOf(1.0, st / 10)
        if (v < 1 && to != "UGX" && to != "TZS") return Math.round(v * 100) / 100.0
        return (v / st).roundToLong() * st
    }
    private fun group(v: Double) = if (v > 0 && v < 1) String.format(java.util.Locale.US, "%.2f", v) else String.format(java.util.Locale.US, "%,d", v.roundToLong())

    /** An amount written in [from], in the chosen currency: "$35", "UGX 128,000". */
    fun fmt(v: Double, from: String = "KES", to: String = code.value): String {
        val x = if (from == to) v else round(v / rate(from) * rate(to), to)
        return (BY[to]?.sym ?: "$to ") + group(x)
    }
    /** The rounded number only (for counters that count up). */
    fun num(v: Long, from: String = "KES"): Long = if (from == code.value) v else round(v / rate(from) * rate(code.value), code.value).roundToLong()

    /** A KES amount for the screen: "KES 4,500", or "≈ $35 (KES 4,500)" in another currency. */
    fun kes(n: Long): String {
        val k = "KES " + String.format(java.util.Locale.US, "%,d", n)
        val c = code.value
        return if (c == "KES") k else "≈ " + fmt(n.toDouble(), "KES", c) + " (" + k + ")"
    }
}
