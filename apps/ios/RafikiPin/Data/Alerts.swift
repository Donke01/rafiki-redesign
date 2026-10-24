import Foundation

/// Notifications (the bell in the header, and phone alerts): worked out from what the team did,
/// repair jobs and visits for you, and the review queue. Same as the web and Android apps.
struct Alert: Identifiable, Equatable {
    var kind: String; var at: String; var pid: String?; var text: String
    /// who did it ("" for a repair job or the review queue), the project's name, the short message, and the name shown when there's no person
    var by = ""; var title = ""; var msg = ""; var name = ""
    /// Stays the same for the same notification, so one can be marked read.
    var key: String { "\(kind)|\(pid ?? "")|\(at)" }
    var id: String { key }
}

/// Unread: newer than "Mark all as read" ([seen]) and not opened one by one ([read]).
func alertNew(_ a: Alert, seen: String, read: Set<String>) -> Bool { a.at > seen && !read.contains(a.key) }

/// Kinds of notification: key, English, Kiswahili, icon, colour.
let ALERT_KINDS: [(String, String, String, String, String)] = [
    ("assign", "Visits assigned to me", "Ziara nilizopewa", "cal", "teal"),
    ("job", "Repair jobs for me", "Kazi za matengenezo kwangu", "wrench", "amber"),
    ("repair", "Repairs reported", "Matengenezo yaliyoripotiwa", "alert", "coral"),
    ("fixed", "Repairs fixed", "Matengenezo yaliyokamilika", "okc", "green"),
    ("new", "New sites added", "Maeneo mapya", "plus", "purple"),
    ("review", "Waiting for review (admins)", "Zinasubiri kuidhinishwa (wasimamizi)", "inbox", "blue"),
]

private func sameName(_ a: String?, _ b: String?) -> Bool {
    (a ?? "").trimmingCharacters(in: .whitespaces).lowercased() == (b ?? "").trimmingCharacters(in: .whitespaces).lowercased()
}

func alertsFor(_ s: Snapshot, _ act: [JSON], me: String, off: Set<String>, reviewAt: String) -> [Alert] {
    let cut = When.iso.string(from: Date().addingTimeInterval(-30 * 86400))
    var out: [Alert] = []
    for e in act {
        let at = e.str("at"), by = e.str("by"), tt = e.str("t"), id = e.str("id").isEmpty ? nil : e.str("id")
        if at < cut || sameName(by, me) { continue }
        switch e.str("a") {
        case "assign":
            if sameName(e.str("to"), me) {
                let what = tt.replacingOccurrences(of: #" for .*$"#, with: "", options: .regularExpression)
                let sw = what.replacingOccurrences(of: #"visits?"#, with: "ziara", options: .regularExpression)
                let p = s.projects.first { $0.id == id }
                out.append(Alert(kind: "assign", at: at, pid: p?.id, text: t("\(by) asked you to make \(what)", "\(by) amekuomba ufanye \(sw)"), by: by, title: p?.title ?? "",
                                 msg: t("Asked you to make \(what)", "Amekuomba ufanye \(sw)")))
            }
        case "visit":
            if ["repair", "notworking"].contains(e.str("s")) {
                let nw = e.str("s") == "notworking"
                out.append(Alert(kind: "repair", at: at, pid: id, text: "\(by): \(tt) · " + (nw ? t("not working", "haifanyi kazi") : t("needs repair", "inahitaji matengenezo")), by: by, title: tt,
                                 msg: nw ? t("Reported it’s not working", "Ameripoti kuwa haifanyi kazi") : t("Reported it needs repair", "Ameripoti kuwa inahitaji matengenezo")))
            }
        case "fixed":
            out.append(Alert(kind: "fixed", at: at, pid: id, text: t("\(by) fixed \(tt)", "\(by) amerekebisha \(tt)"), by: by, title: tt, msg: t("Marked it fixed ✓", "Ameiweka kuwa imerekebishwa ✓")))
        case "new":
            if s.admin { out.append(Alert(kind: "new", at: at, pid: id, text: t("\(by) added a new site: \(tt)", "\(by) ameongeza eneo jipya: \(tt)"), by: by, title: tt, msg: t("Added a new site", "Ameongeza eneo jipya"))) }
        default: break
        }
    }
    for id in s.jobs {
        guard let r = s.repairs[id] else { continue }
        let pt = r.title.isEmpty ? (s.projects.first { $0.id == id }?.title ?? "") : r.title
        out.append(Alert(kind: "job", at: r.stepAt["assigned"] ?? r.at, pid: id, text: t("Repair job for you: ", "Kazi ya matengenezo kwako: ") + pt, title: pt,
                         msg: t("This repair is yours to do", "Matengenezo haya ni yako kufanya"), name: t("Repair job", "Kazi ya matengenezo")))
    }
    if s.admin && s.reviewOpen > 0 {
        let rv = s.reviewOpen == 1 ? t("1 change waiting for your review", "Badiliko 1 linasubiri uidhinishe") : t("\(s.reviewOpen) changes waiting for your review", "Mabadiliko \(s.reviewOpen) yanasubiri uidhinishe")
        out.append(Alert(kind: "review", at: reviewAt.isEmpty ? When.now() : reviewAt, pid: nil, text: rv, msg: rv, name: t("Office review", "Ukaguzi wa ofisi")))
    }
    return Array(out.filter { !off.contains($0.kind) }.sorted { $0.at > $1.at }.prefix(40))
}
