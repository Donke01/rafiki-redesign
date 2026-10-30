import Foundation
import SwiftUI
import UIKit

// Me › Settings and the Me area's own small settings (Android 2.14–2.25): personal details, how you sign in, Change my
// PIN, sign out of this phone, the notifications' read marks, the welcome screens and the Me hint.

/// Me › Settings › Personal details, and how this person signs in: "app" (a PIN given out in the app), "cloudflare"
/// (a PIN set by the office), "monday" (signed in with monday.com) or "shared" (the shared team PIN).
struct MeRec: Equatable {
    var kind = ""; var nick = ""; var phone = ""; var home = ""; var photo = ""
    static func from(_ o: JSON?) -> MeRec {
        guard let o else { return MeRec() }
        func s(_ k: String) -> String { let v = o.str(k); return v == "null" ? "" : v }
        return MeRec(kind: s("kind"), nick: s("nick"), phone: s("phone"), home: s("home"), photo: s("photo"))
    }
}

/// Where the Me area keeps its small settings: the same place as the rest of the app's settings (a separate one in demo mode).
enum MeKV {
    static let demo = ProcessInfo.processInfo.arguments.contains("-demo")
    static var d: UserDefaults { demo ? (UserDefaults(suiteName: "rafiki-demo") ?? .standard) : .standard }
}

extension Prefs {
    private func kvs(_ k: String) -> String { MeKV.d.string(forKey: k) ?? "" }
    /// Notifications: "Mark all as read" time, ones opened one by one, kinds turned off, phone alerts on, the newest one the phone was told about.
    var noteSeen: String { get { kvs("noteSeen") } set { MeKV.d.set(newValue, forKey: "noteSeen") } }
    var noteRead: Set<String> { get { Set(MeKV.d.stringArray(forKey: "noteRead") ?? []) } set { MeKV.d.set(Array(newValue), forKey: "noteRead") } }
    var noteOff: Set<String> { get { Set(kvs("noteOff").split(separator: ",").map(String.init).filter { !$0.isEmpty }) } set { MeKV.d.set(newValue.sorted().joined(separator: ","), forKey: "noteOff") } }
    var phoneAlerts: Bool { get { MeKV.d.object(forKey: "phoneAlerts") as? Bool ?? true } set { MeKV.d.set(newValue, forKey: "phoneAlerts") } }
    var notifiedAt: String { get { kvs("notifiedAt") } set { MeKV.d.set(newValue, forKey: "notifiedAt") } }
    /// When the review queue last grew (so "Waiting for review" counts as new), and how many were open then.
    var reviewAt: String { get { kvs("reviewAt") } set { MeKV.d.set(newValue, forKey: "reviewAt") } }
    var reviewN: Int { get { MeKV.d.integer(forKey: "reviewN") } set { MeKV.d.set(newValue, forKey: "reviewN") } }
    /// The welcome screens were finished on this phone.
    var onb: Bool { get { MeKV.d.bool(forKey: "onb") } set { MeKV.d.set(newValue, forKey: "onb") } }
    /// "Me is here now" was closed: never shown again.
    var meHint: Bool { get { MeKV.d.bool(forKey: "meHint") } set { MeKV.d.set(newValue, forKey: "meHint") } }
    /// The update pop-up: "Remind me later" until this time, for this build; the newest build the phone was told about.
    var updLater: Double { get { MeKV.d.double(forKey: "updLater") } set { MeKV.d.set(newValue, forKey: "updLater") } }
    var updLaterBuild: Int { get { MeKV.d.integer(forKey: "updLaterBuild") } set { MeKV.d.set(newValue, forKey: "updLaterBuild") } }
    var updNotified: Int { get { MeKV.d.integer(forKey: "updNotified") } set { MeKV.d.set(newValue, forKey: "updNotified") } }
    /// The stamps this phone has already celebrated, per person (nil the first time: nothing celebrates then).
    func badgesSeen(_ who: String) -> Set<String>? {
        let k = "badges:" + who.trimmingCharacters(in: .whitespaces).lowercased()
        guard let a = MeKV.d.stringArray(forKey: k) else { return nil }
        return Set(a)
    }
    func saveBadges(_ who: String, _ keys: Set<String>) { MeKV.d.set(keys.sorted(), forKey: "badges:" + who.trimmingCharacters(in: .whitespaces).lowercased()) }
}

/// The words for what the server said no to in the Me area (falls back to the app's usual words).
func meReason(_ reason: String?) -> String {
    switch reason {
    case "pin-current": return t("Your current PIN isn’t right.", "PIN yako ya sasa si sahihi.")
    case "pin-weak": return t("Choose 4 to 6 digits that aren’t easy to guess (not 1234 or 0000).", "Chagua tarakimu 4 hadi 6 zisizo rahisi kubahatisha (si 1234 wala 0000).")
    case "pin-taken": return t("That PIN can’t be used. Choose another.", "PIN hiyo haiwezi kutumika. Chagua nyingine.")
    case "pin-cloudflare": return t("Your PIN was set by the office, so only they can change it.", "PIN yako iliwekwa na ofisi, kwa hiyo wao pekee wanaweza kuibadilisha.")
    case "blocked": return t("The office has paused your access to RAFIKI Pin. Nothing you saved is lost. If you think this is a mistake, please talk to the office.", "Ofisi imesimamisha ufikiaji wako wa RAFIKI Pin. Hakuna ulichohifadhi kilichopotea. Kama unadhani ni kosa, tafadhali ongea na ofisi.")
    case "signed-out": return t("The office signed you out of this phone. Sign in again with your PIN; nothing was lost.", "Ofisi imekutoa kwenye simu hii. Ingia tena kwa PIN yako; hakuna kilichopotea.")
    case "bad-email": return t("That doesn’t look like an e-mail address. Please check it.", "Hiyo haionekani kama anwani ya barua pepe. Tafadhali iangalie.")
    case "cloudflare": return t("This PIN is set in Cloudflare, so it can’t be changed in the app. Move them to an app PIN first.", "PIN hii imewekwa kwenye Cloudflare, kwa hiyo haiwezi kubadilishwa kwenye programu. Mhamishe kwenye PIN ya programu kwanza.")
    case "not-in-app": return t("Their PIN wasn’t made in the app, so it can’t be changed here.", "PIN yake haikutengenezwa kwenye programu, kwa hiyo haiwezi kubadilishwa hapa.")
    case "not-cloudflare": return t("They aren’t set up in Cloudflare, so there’s nothing to move.", "Hawajawekwa kwenye Cloudflare, kwa hiyo hakuna cha kuhamisha.")
    case "not-admin": return t("Only admins can do this.", "Wasimamizi pekee wanaweza kufanya hivi.")
    case "weak-pin": return t("That PIN is too easy to guess. Choose another.", "PIN hiyo ni rahisi kubahatisha. Chagua nyingine.")
    case "exists": return t("Someone with that name is already on the team.", "Mtu mwenye jina hilo tayari yuko kwenye timu.")
    case "digits": return t("Use 4 to 8 digits.", "Tumia tarakimu 4 hadi 8.")
    case "self": return t("You can’t do that to yourself. Ask another admin.", "Huwezi kujifanyia hivyo. Mwombe msimamizi mwingine.")
    case "last-admin": return t("They’re the last admin, so this would leave nobody to run the Office. Make someone else an admin first.", "Ndiye msimamizi wa mwisho, kwa hiyo hakuna atakayebaki kusimamia Ofisi. Mfanye mtu mwingine msimamizi kwanza.")
    default: return reasonText(reason)
    }
}

/// The parsed "me" of the last list, kept until the list changes.
@MainActor enum MeCache {
    static var snap: Snapshot?
    static var rec = MeRec()
    static var override: MeRec?
}

extension AppModel {
    /// This person's own details (Me › Settings), from the last list the server sent.
    var me: MeRec {
        if let o = MeCache.override { return o }
        if MeCache.snap != snap || MeCache.snap == nil {
            MeCache.snap = snap
            MeCache.rec = MeRec.from(prefs.loadSnapshot()?.obj("me"))
        }
        return MeCache.rec
    }

    /// Personal details: a display name, phone and home country. Returns an error text, or nil.
    func saveProfile(nick: String, phone: String, home: String) async -> String? {
        let r = await api.post(["action": "profile", "nick": nick.trimmingCharacters(in: .whitespaces), "phone": phone.trimmingCharacters(in: .whitespaces), "home": home])
        if !r.ok { return r.status == 0 ? meReason("offline") : meReason(r.reason) }
        if let o = r.json.obj("me") {
            MeCache.override = MeRec.from(o)
            if var raw = prefs.loadSnapshot() { raw["me"] = o; prefs.saveSnapshot(raw); MeCache.override = nil; MeCache.snap = nil }
            if !home.isEmpty { prefs.country = home }
        }
        objectWillChange.send()
        return nil
    }

    /// Change my PIN (a PIN given out in the app). Returns an error text, or nil.
    func changePin(current: String, new: String) async -> String? {
        let r = await api.post(["action": "pin-change", "current": current.trimmingCharacters(in: .whitespaces), "pin": new.trimmingCharacters(in: .whitespaces)])
        if !r.ok { return r.status == 0 ? meReason("offline") : meReason(r.reason) }
        prefs.pin = new.trimmingCharacters(in: .whitespaces)
        return nil
    }

    /// Sign out of this phone: forgets the PIN and the name (changes waiting to send stay).
    func signOut() {
        NotifPhone.cancelAll()
        lock()
        prefs.name = ""
    }

    /// The team list for Me & team with everything it carries (photos, role, old names, blocked), and the
    /// faces it brings with it. Fills `team` as before.
    @discardableResult func loadPeople() async -> [JSON]? {
        let r = await api.team()
        guard r.ok else { return nil }
        let list = r.json.objs("team")
        TeamPhotos.shared.absorbPeople(list)
        team = list.map { o in
            let seen = o.str("lastSeen")
            return Mate(name: o.str("name"), home: o.str("home"), phone: o.str("phone"), lastSeen: seen.isEmpty || seen == "null" ? nil : seen)
        }.filter { !$0.name.isEmpty }
        return list
    }

    /// Opens a project from anywhere in the Me area (on top of what's open now).
    func openProject(_ id: String) { go(Place(tab: here.tab, project: id)) }
    /// Opens a Settings page ("profile", "pin", "notify"…) on top of what's open now.
    func openSettings(_ page: String) { go(Place(tab: here.tab, sub: "set_" + page)) }
}

/// The installed app: "2.28" and its build number.
enum AppVer {
    static var name: String { (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "" }
    static var build: Int { Int((Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String) ?? "") ?? 0 }
}

/// Opens Mail with a message to the office.
enum MeMail {
    static let office = "info@rafikioutreach.org"
    static func open(_ subject: String, _ body: String = "") {
        var s = "mailto:" + office + "?subject=" + Api.enc(subject)
        if !body.isEmpty { s += "&body=" + Api.enc(body) }
        Share.open(s)
    }
    static func web(_ path: String) { Share.open("https://www.rafikioutreach.org" + path) }
}
