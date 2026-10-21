package org.rafikioutreach.pin.data

import org.json.JSONObject
import org.rafikioutreach.pin.t

/**
 * Notifications (the bell in Me & team, and phone alerts): worked out from what the team did,
 * repair jobs and visits for you, and the review queue. Same as the web app.
 */
data class Alert(val kind: String, val at: String, val id: String?, val text: String)

/** Kinds of notification: key, English, Kiswahili, icon, colour. */
val ALERT_KINDS = listOf(
    listOf("assign", "Visits assigned to me", "Ziara nilizopewa", "cal", "teal"),
    listOf("job", "Repair jobs for me", "Kazi za matengenezo kwangu", "wrench", "amber"),
    listOf("repair", "Repairs reported", "Matengenezo yaliyoripotiwa", "alert", "coral"),
    listOf("fixed", "Repairs fixed", "Matengenezo yaliyokamilika", "okc", "green"),
    listOf("new", "New sites added", "Maeneo mapya", "plus", "purple"),
    listOf("review", "Waiting for review (admins)", "Zinasubiri kuidhinishwa (wasimamizi)", "inbox", "blue"),
)

private fun same(a: String?, b: String?) = a.orEmpty().trim().equals(b.orEmpty().trim(), ignoreCase = true)

fun alertsFor(s: Snapshot, act: List<JSONObject>, me: String, off: Set<String>, reviewAt: String): List<Alert> {
    val cut = java.time.Instant.now().minus(java.time.Duration.ofDays(30)).toString()
    val out = mutableListOf<Alert>()
    act.forEach { e ->
        val at = e.optString("at"); val by = e.optString("by"); val tt = e.optString("t"); val id = e.optString("id").takeIf { it.isNotBlank() }
        if (at < cut || same(by, me)) return@forEach
        when (e.optString("a")) {
            "assign" -> if (same(e.optString("to"), me)) { val what = tt.replace(Regex(" for .*$"), ""); out += Alert("assign", at, null, t("$by asked you to make $what", "$by amekuomba ufanye $what")) }
            "visit" -> if (e.optString("s") in setOf("repair", "notworking")) out += Alert("repair", at, id, "$by: $tt · " + if (e.optString("s") == "notworking") t("not working", "haifanyi kazi") else t("needs repair", "inahitaji matengenezo"))
            "fixed" -> out += Alert("fixed", at, id, t("$by fixed $tt", "$by amerekebisha $tt"))
            "new" -> if (s.admin) out += Alert("new", at, id, t("$by added a new site: $tt", "$by ameongeza eneo jipya: $tt"))
        }
    }
    s.jobs.forEach { id -> s.repairs[id]?.let { r -> out += Alert("job", r.stepAt["assigned"] ?: r.at, id, t("Repair job for you: ", "Kazi ya matengenezo kwako: ") + r.title.ifBlank { s.projects.firstOrNull { it.id == id }?.title.orEmpty() }) } }
    if (s.admin && s.reviewOpen > 0) out += Alert("review", reviewAt.ifBlank { java.time.Instant.now().toString() }, null,
        if (s.reviewOpen == 1) t("1 change waiting for your review", "Badiliko 1 linasubiri uidhinishe") else t("${s.reviewOpen} changes waiting for your review", "Mabadiliko ${s.reviewOpen} yanasubiri uidhinishe"))
    return out.filter { it.kind !in off }.sortedByDescending { it.at }.take(40)
}
