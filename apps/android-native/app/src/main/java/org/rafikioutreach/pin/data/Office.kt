package org.rafikioutreach.pin.data

import org.json.JSONObject

/** The second approval on a big repair (as /api/pin and /api/admin keep it on the job). */
data class Approval(val status: String, val rid: String, val by: String, val at: String, val est: Long, val dec: String = "", val note: String = "") {
    companion object {
        fun from(o: JSONObject?): Approval? = o?.let {
            Approval(it.optString("status"), it.optString("rid"), it.optString("by"), it.optString("at"), it.optLong("est"), it.optString("dec"), it.optString("note"))
        }
    }
}

/** Repair money and the two-step approval: the same rules as lib/pin-office.js. */
object Money {
    const val APPROVE_DEFAULT = 50000L
    private val ORDER = listOf("reported", "assigned", "parts", "fixed")

    fun kes(n: Long?): String = "KES " + "%,d".format(java.util.Locale.US, n ?: 0L)
    /** The repair's estimate: the field estimate or the approved budget, whichever is higher. */
    fun estOf(r: Repair): Long = maxOf(r.cost ?: 0L, r.budget ?: 0L)
    fun past(step: String, ref: String) = ORDER.indexOf(step.ifBlank { "reported" }) >= ORDER.indexOf(ref)
    /** "none" (small enough), "needed", "pending", "approved" or "declined". */
    fun state(r: Repair, limit: Long): String {
        val est = estOf(r); val a = r.approval
        if (est <= limit) return "none"
        if (a?.status == "pending") return "pending"
        if (a != null && (a.status == "approved" || a.status == "declined") && a.est >= est) return a.status
        return "needed"
    }
    /** Why [step] can't be set yet ("" = it can): parts ordered waits for the second approval. */
    fun blocked(r: Repair, step: String, limit: Long): String {
        if (!past(step, "parts") || past(r.step, "parts")) return ""
        val s = state(r, limit)
        return if (s == "none" || s == "approved") "" else s
    }
    /** The person who asked can't approve their own request. */
    fun canDecide(askedBy: String, who: String) = who.isNotBlank() && !askedBy.trim().equals(who.trim(), ignoreCase = true)
    /** Whole shillings as typed ("12,500"), or null. */
    fun parse(s: String): Long? = s.replace(",", "").replace(" ", "").takeIf { it.isNotBlank() }?.toLongOrNull()?.takeIf { it >= 0 }
}

/** Office overview: people served now, by country and type, and the last 12 months (oldest first; null = not known). */
data class PeopleServed(val total: Long, val counted: Int, val measured: Int, val byCountry: Map<String, Long>, val byType: Map<String, Long>, val trend: List<Pair<String, Long?>>) {
    /** Growth since the first month known in the trend. */
    val growth: Long get() = trend.firstOrNull { it.second != null }?.second?.let { total - it } ?: 0L

    companion object {
        fun from(o: JSONObject?): PeopleServed? {
            if (o == null) return null
            fun map(j: JSONObject?) = j?.keys()?.asSequence()?.associateWith { j.optLong(it) } ?: emptyMap()
            val tr = o.optJSONArray("trend")
            return PeopleServed(o.optLong("total"), o.optInt("counted"), o.optInt("measured"), map(o.optJSONObject("byCountry")), map(o.optJSONObject("byType")),
                (0 until (tr?.length() ?: 0)).map { i -> tr!!.getJSONObject(i).let { it.optString("m") to (if (it.isNull("n")) null else it.optLong("n")) } })
        }
    }
}

/** One cost entry (Office › Costs and the export). */
data class CostRow(val id: String, val title: String, val at: String, val rid: String, val what: String, val est: Long?, val actual: Long, val by: String, val step: String, val receipt: Boolean) {
    companion object {
        fun from(o: JSONObject) = CostRow(o.optString("id"), o.optString("t"), o.optString("at"), o.optString("rid"), o.optString("what"),
            if (o.has("est") && !o.isNull("est")) o.optLong("est") else null, o.optLong("actual"), o.optString("by"), o.optString("step"), o.optBoolean("receipt"))
    }
}

/** What /api/admin?view=costs answers. */
data class CostView(val rows: List<CostRow>, val month: Long, val year: Long, val sum12: Long, val repairs: Int, val avg: Long, val yearKey: Int,
                    val byCountry: Map<String, Long>, val top: List<TopPoint>, val approveAbove: Long) {
    data class TopPoint(val id: String, val title: String, val ck: String, val sum: Long, val n: Int)
    companion object {
        fun from(o: JSONObject): CostView {
            val s = o.optJSONObject("stats") ?: JSONObject(); val r = o.optJSONArray("rows"); val t = s.optJSONArray("top"); val c = s.optJSONObject("byCountry")
            return CostView(
                (0 until (r?.length() ?: 0)).map { CostRow.from(r!!.getJSONObject(it)) }.sortedByDescending { it.at },
                s.optLong("month"), s.optLong("year"), s.optLong("sum12"), s.optInt("repairs"), s.optLong("avg"), s.optInt("yearKey"),
                c?.keys()?.asSequence()?.associateWith { c.optLong(it) } ?: emptyMap(),
                (0 until (t?.length() ?: 0)).map { i -> t!!.getJSONObject(i).let { TopPoint(it.optString("id"), it.optString("t"), it.optString("ck"), it.optLong("sum"), it.optInt("n")) } },
                if (o.has("approveAbove")) o.optLong("approveAbove") else Money.APPROVE_DEFAULT,
            )
        }
    }
}
