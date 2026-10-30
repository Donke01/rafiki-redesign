import BackgroundTasks
import Foundation
import SwiftUI
import UserNotifications

// Notifications (Android 2.14–2.15): the bell at the top right and phone alerts. Worked out on the phone from what the
// team did, repair jobs and visits for you, and the review queue; the same rules as Android and the web app.

/// One notification. `pid` is the project it opens (nil for the review queue).
struct NotifAlert: Identifiable, Equatable {
    let kind: String; let at: String; let pid: String?; let text: String
    /// who did it ("" for a repair job or the review queue), the project's name, the short message, the name shown when there's no person
    var by = ""; var title = ""; var msg = ""; var name = ""
    /// Stays the same for the same notification, so one can be marked read.
    var key: String { kind + "|" + (pid ?? "") + "|" + at }
    var id: String { key }
}

/// Kinds of notification: key, English, Kiswahili, icon (a system symbol) and colour.
struct NotifKind { let k: String; let en: String; let sw: String; let icon: String; let hue: String }
let NOTIF_KINDS: [NotifKind] = [
    NotifKind(k: "assign", en: "Visits assigned to me", sw: "Ziara nilizopewa", icon: "calendar", hue: "teal"),
    NotifKind(k: "job", en: "Repair jobs for me", sw: "Kazi za matengenezo kwangu", icon: "wrench.adjustable", hue: "amber"),
    NotifKind(k: "repair", en: "Repairs reported", sw: "Matengenezo yaliyoripotiwa", icon: "exclamationmark.triangle", hue: "coral"),
    NotifKind(k: "fixed", en: "Repairs fixed", sw: "Matengenezo yaliyokamilika", icon: "checkmark.circle", hue: "green"),
    NotifKind(k: "new", en: "New sites added", sw: "Maeneo mapya", icon: "plus", hue: "purple"),
    NotifKind(k: "review", en: "Waiting for review (admins)", sw: "Zinasubiri kuidhinishwa (wasimamizi)", icon: "tray", hue: "blue"),
]
func notifKind(_ k: String) -> NotifKind { NOTIF_KINDS.first { $0.k == k } ?? NOTIF_KINDS[0] }

/// A colour by its name ("teal", "amber"…), from the app's colours.
func meHue(_ name: String) -> Hue {
    switch name {
    case "blue": return C.hue("pin")
    case "green": return C.hue("near")
    case "purple": return C.hue("new")
    case "amber": return C.hue("repairs")
    case "teal": return C.hue("due")
    case "coral": return C.hue("me")
    default: return C.hue("slate")
    }
}

private func sameN(_ a: String, _ b: String) -> Bool { a.trimmingCharacters(in: .whitespaces).lowercased() == b.trimmingCharacters(in: .whitespaces).lowercased() }

/// Unread: newer than "Mark all as read" ([seen]) and not opened one by one ([read]).
func notifIsNew(_ a: NotifAlert, _ seen: String, _ read: Set<String>) -> Bool { a.at > seen && !read.contains(a.key) }

/// The notifications for [me], newest first (at most 40, the last 30 days).
func notifAlertsFor(_ s: Snapshot, _ act: [JSON], me: String, off: Set<String>, reviewAt: String) -> [NotifAlert] {
    let cut = When.iso.string(from: Date().addingTimeInterval(-30 * 86400))
    var out: [NotifAlert] = []
    for e in act {
        let at = e.str("at"), by = e.str("by"), tt = e.str("t"), rawId = e.str("id")
        let id: String? = rawId.isEmpty ? nil : rawId
        if at < cut || sameN(by, me) { continue }
        switch e.str("a") {
        case "assign":
            guard sameN(e.str("to"), me) else { continue }
            let what = tt.replacingOccurrences(of: #" for .*$"#, with: "", options: .regularExpression)
            let sw = what.replacingOccurrences(of: #"visits?"#, with: "ziara", options: .regularExpression)
            let p = s.projects.first { $0.id == id }
            out.append(NotifAlert(kind: "assign", at: at, pid: p?.id, text: t("\(by) asked you to make \(what)", "\(by) amekuomba ufanye \(sw)"),
                                  by: by, title: p?.title ?? "", msg: t("Asked you to make \(what)", "Amekuomba ufanye \(sw)")))
        case "visit":
            let st = e.str("s")
            guard st == "repair" || st == "notworking" else { continue }
            let nw = st == "notworking"
            out.append(NotifAlert(kind: "repair", at: at, pid: id, text: "\(by): \(tt) · " + (nw ? t("not working", "haifanyi kazi") : t("needs repair", "inahitaji matengenezo")),
                                  by: by, title: tt, msg: nw ? t("Reported it’s not working", "Ameripoti kuwa haifanyi kazi") : t("Reported it needs repair", "Ameripoti kuwa inahitaji matengenezo")))
        case "fixed":
            out.append(NotifAlert(kind: "fixed", at: at, pid: id, text: t("\(by) fixed \(tt)", "\(by) amerekebisha \(tt)"), by: by, title: tt, msg: t("Marked it fixed ✓", "Ameiweka kuwa imerekebishwa ✓")))
        case "new":
            if s.admin { out.append(NotifAlert(kind: "new", at: at, pid: id, text: t("\(by) added a new site: \(tt)", "\(by) ameongeza eneo jipya: \(tt)"), by: by, title: tt, msg: t("Added a new site", "Ameongeza eneo jipya"))) }
        default: break
        }
    }
    for id in s.jobs {
        guard let r = s.repairs[id] else { continue }
        let pt = r.title.isEmpty ? (s.projects.first { $0.id == id }?.title ?? "") : r.title
        out.append(NotifAlert(kind: "job", at: r.at, pid: id, text: t("Repair job for you: ", "Kazi ya matengenezo kwako: ") + pt, by: "", title: pt,
                              msg: t("This repair is yours to do", "Matengenezo haya ni yako kufanya"), name: t("Repair job", "Kazi ya matengenezo")))
    }
    if s.admin && s.reviewOpen > 0 {
        let rv = s.reviewOpen == 1 ? t("1 change waiting for your review", "Badiliko 1 linasubiri uidhinishe") : t("\(s.reviewOpen) changes waiting for your review", "Mabadiliko \(s.reviewOpen) yanasubiri uidhinishe")
        out.append(NotifAlert(kind: "review", at: reviewAt.isEmpty ? When.now() : reviewAt, pid: nil, text: rv, by: "", title: "", msg: rv, name: t("Office review", "Ukaguzi wa ofisi")))
    }
    return Array(out.filter { !off.contains($0.kind) }.sorted { $0.at > $1.at }.prefix(40))
}

/// The bell's state: what is unread, marking read, and the phone's permission for alerts.
@MainActor final class NotifCenter: ObservableObject {
    static let shared = NotifCenter()
    /// Goes up whenever something is read or the list changes, so the bell and the page redraw.
    @Published var tick = 0
    /// The phone lets RAFIKI Pin show notifications.
    @Published var allowed = false
    /// A notification tapped before the app was ready: the project to open ("" for the notifications page).
    var pendingOpen: String?
    weak var model: AppModel?

    func alerts(_ m: AppModel) -> [NotifAlert] {
        guard let s = m.snap else { return [] }
        return notifAlertsFor(s, m.activity ?? [], me: m.prefs.name, off: m.prefs.noteOff, reviewAt: m.prefs.reviewAt)
    }
    func isNew(_ a: NotifAlert, _ m: AppModel) -> Bool { notifIsNew(a, m.prefs.noteSeen, m.prefs.noteRead) }
    func unread(_ m: AppModel) -> Int {
        let seen = m.prefs.noteSeen, read = m.prefs.noteRead
        return alerts(m).filter { notifIsNew($0, seen, read) }.count
    }
    /// Mark all as read.
    func markAll(_ m: AppModel) { m.prefs.noteSeen = When.now(); m.prefs.noteRead = []; tick += 1 }
    /// One opened: read (only keys still in the list are kept).
    func markRead(_ a: NotifAlert, _ m: AppModel) {
        let keys = Set(alerts(m).map(\.key))
        m.prefs.noteRead = m.prefs.noteRead.union([a.key]).intersection(keys)
        tick += 1
    }
    func changed() { tick += 1 }

    /// A fresh list: if the review queue grew, "waiting for review" is new again.
    func watch(_ m: AppModel) {
        if let s = m.snap {
            if s.reviewOpen > m.prefs.reviewN { m.prefs.reviewAt = When.now() }
            m.prefs.reviewN = s.reviewOpen
        }
        tick += 1
    }

    /// Asks the phone what it allows.
    func refreshAllowed() async {
        if MeKV.demo { allowed = true; return }
        let st = await UNUserNotificationCenter.current().notificationSettings()
        allowed = st.authorizationStatus == .authorized || st.authorizationStatus == .provisional
    }
    /// Asks once for permission to show notifications. Returns whether they're allowed.
    func ask() async -> Bool {
        if MeKV.demo { allowed = true; return true }
        let st = await UNUserNotificationCenter.current().notificationSettings()
        if st.authorizationStatus == .notDetermined {
            let ok = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
            allowed = ok
            return ok
        }
        allowed = st.authorizationStatus == .authorized || st.authorizationStatus == .provisional
        return allowed
    }
}

/// Phone alerts while the app is closed: iOS lets the app look now and then (Background App Refresh, about every
/// 15 minutes at best, when the phone decides), checks the same things as the bell and shows anything new.
enum NotifPhone {
    static let taskId = "org.rafikioutreach.pin.alerts"
    private static var registered = false

    /// At launch: the background check and what a tapped notification opens. Does nothing unless the app's
    /// Info.plist allows the background check (BGTaskSchedulerPermittedIdentifiers).
    static func register() {
        UNUserNotificationCenter.current().delegate = NotifTap.shared
        let ok = (Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String])?.contains(taskId) == true
        guard ok, !MeKV.demo else { return }
        registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            guard let task = task as? BGAppRefreshTask else { task.setTaskCompleted(success: false); return }
            handle(task)
        }
    }

    /// Going to the background: ask iOS for the next check.
    static func schedule() {
        guard registered else { return }
        let r = BGAppRefreshTaskRequest(identifier: taskId)
        r.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(r)
    }

    static func cancelAll() {
        if registered { BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskId) }
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
    }

    private static func handle(_ task: BGAppRefreshTask) {
        schedule()
        let work = Task { await check() }
        task.expirationHandler = { work.cancel() }
        Task { let ok = await work.value; task.setTaskCompleted(success: ok) }
    }

    /// One check: the latest list and feed; a notification for anything new since the last check.
    static func check() async -> Bool {
        let prefs = Prefs()
        guard prefs.phoneAlerts, !(prefs.pin.isEmpty && prefs.session.isEmpty) else { return true }
        let st = await UNUserNotificationCenter.current().notificationSettings()
        guard st.authorizationStatus == .authorized || st.authorizationStatus == .provisional else { return true }
        Lang.sw = prefs.lang == "sw"
        let server = (Bundle.main.object(forInfoDictionaryKey: "RafikiServer") as? String) ?? "https://rafiki-redesign.pages.dev"
        let api = Api(server: server, pin: { prefs.pin }, session: { prefs.session })
        // a newer iPhone app: one notification per build
        if let u = await MeUpdates.fetch(api), u.build > AppVer.build, u.build > prefs.updNotified {
            MeUpdates.save(u)
            post(id: "update", title: t("RAFIKI Pin update", "Sasisho la RAFIKI Pin") + (u.name.isEmpty ? "" : " · " + u.name),
                 body: u.notes.isEmpty ? t("A newer RAFIKI Pin is ready. Tap to see how to get it.", "RAFIKI Pin mpya iko tayari. Gusa kuona jinsi ya kuipata.") : u.notes, open: "update")
            prefs.updNotified = u.build
        }
        let r = await api.list()
        guard r.ok else { return false }
        let raw = r.json
        let n = raw.int("reviewOpen")
        if n > prefs.reviewN { prefs.reviewAt = When.now() }
        prefs.reviewN = n
        prefs.saveSnapshot(raw)
        let act = (await api.activity()).json.objs("activity")
        let list = notifAlertsFor(Snapshot.from(raw), act, me: prefs.name, off: prefs.noteOff, reviewAt: prefs.reviewAt)
        guard let newest = list.map(\.at).max() else { return true }
        let since = prefs.notifiedAt
        // the first check only remembers where things are, so nobody gets a flood of old news
        if since.isEmpty { prefs.notifiedAt = newest; return true }
        let fresh = list.filter { $0.at > since && $0.at > prefs.noteSeen }
        for a in fresh.prefix(5) { post(id: a.key, title: title(a), body: text(a), open: a.pid ?? "") }
        prefs.notifiedAt = max(since, newest)
        return true
    }

    /// A notification's title: the person who did it, or what it is when nobody did (a repair job, the review queue).
    static func title(_ a: NotifAlert) -> String {
        if a.by.hasPrefix("SMS from") || a.by.hasPrefix("WhatsApp from") { return t("Community report", "Ripoti ya jamii") }
        if !a.by.isEmpty { return a.by }
        if !a.name.isEmpty { return a.name }
        return "RAFIKI Pin"
    }
    /// Its words: the project and what happened ("Kapsaret Borehole · Reported it needs repair").
    static func text(_ a: NotifAlert) -> String {
        let s = [a.title, a.msg].filter { !$0.isEmpty }.joined(separator: " · ")
        return s.isEmpty ? a.text : s
    }

    static func post(id: String, title: String, body: String, open: String) {
        let c = UNMutableNotificationContent()
        c.title = title; c.body = body; c.sound = .default
        c.threadIdentifier = "rafiki"
        c.userInfo = ["open": open]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: c, trigger: nil))
    }
}

/// What a tapped notification opens: its project, the notifications page, or Me (an app update).
final class NotifTap: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotifTap()
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let open = (response.notification.request.content.userInfo["open"] as? String) ?? ""
        Task { @MainActor in
            let nc = NotifCenter.shared
            if let m = nc.model, m.signedIn { NotifTap.go(m, open) } else { nc.pendingOpen = open }
        }
        completionHandler()
    }
    /// While the app is open the bell shows it; no banner on top.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([])
    }
    @MainActor static func go(_ m: AppModel, _ open: String) {
        if open == "update" { m.tab("me"); MeUpdates.shared.focus = true; return }
        if !open.isEmpty, m.snap?.projects.contains(where: { $0.id == open }) == true { m.tab("me"); m.go(Place(tab: "me", project: open)); return }
        m.tab("me"); m.go(Place(tab: "me", sub: "notes"))
    }
}
