import Foundation
import SwiftUI
import UIKit

// App updates on iPhone (Android 2.12.7 and later, without the in-app installer): the website says which iPhone build is
// the newest (/app/ios-version.json: { "build": 26, "versionName": "2.29", "notes": "…", "url": "<TestFlight or App Store link>" }).
// When it's newer than this one, Me shows a card and a pop-up says so once, with a button to TestFlight or the App Store.

struct MeUpdate: Equatable {
    var build: Int; var name: String; var notes: String; var url: String; var at: Double
}

@MainActor final class MeUpdates: ObservableObject {
    static let shared = MeUpdates()
    static let path = "/app/ios-version.json"

    /// The newest iPhone app the website knows of, as last seen (build 0: none published).
    @Published var latest: MeUpdate? = MeUpdates.load()
    /// Me: bring the update card into view (after "Check" finds one, or "See how" on the pop-up).
    @Published var focus = false
    /// The pop-up was closed with ✕ for this build: not again until the app is opened next time.
    var promptClosedBuild = 0

    var ready: Bool { (latest?.build ?? 0) > AppVer.build }

    /// Asks the website. Returns false when it couldn't (no signal).
    @discardableResult func check(_ api: Api) async -> Bool {
        let r = await api.get(MeUpdates.path)
        if r.status == 404 {
            let u = MeUpdate(build: 0, name: "", notes: "", url: "", at: Date().timeIntervalSince1970)
            MeUpdates.save(u); latest = u; return true
        }
        guard let u = MeUpdates.parse(r) else { return false }
        MeUpdates.save(u); latest = u
        return true
    }
    /// Checks if the last check was more than [minutes] ago (on open and on coming back).
    func checkSoon(_ api: Api, minutes: Double = 2) {
        if Date().timeIntervalSince1970 - (latest?.at ?? 0) > minutes * 60 { Task { await check(api) } }
    }

    /// TestFlight or the App Store: the link the website gives, else the TestFlight app (or its website).
    func openStore() {
        let given = latest?.url ?? ""
        if let u = URL(string: given), !given.isEmpty { UIApplication.shared.open(u); return }
        guard let beta = URL(string: "itms-beta://") else { return }
        UIApplication.shared.open(beta, options: [:]) { ok in
            if !ok, let web = URL(string: "https://testflight.apple.com/") { UIApplication.shared.open(web) }
        }
    }

    // --- kept on the phone (also used by the background check) ---------------------------------------------------
    nonisolated static func parse(_ r: Reply) -> MeUpdate? {
        guard (200..<300).contains(r.status) else { return nil }
        let b = r.json.int("build") > 0 ? r.json.int("build") : r.json.int("versionCode")
        guard b > 0 else { return nil }
        let name = r.json.str("versionName").isEmpty ? r.json.str("name") : r.json.str("versionName")
        return MeUpdate(build: b, name: name, notes: r.json.str("notes"), url: r.json.str("url"), at: Date().timeIntervalSince1970)
    }
    nonisolated static func fetch(_ api: Api) async -> MeUpdate? { parse(await api.get(path)) }
    nonisolated static func save(_ u: MeUpdate) {
        let d = MeKV.d
        d.set(u.build, forKey: "iosUpdBuild"); d.set(u.name, forKey: "iosUpdName"); d.set(u.notes, forKey: "iosUpdNotes"); d.set(u.url, forKey: "iosUpdUrl"); d.set(u.at, forKey: "iosUpdAt")
    }
    nonisolated static func load() -> MeUpdate? {
        let d = MeKV.d
        guard d.object(forKey: "iosUpdAt") != nil else { return nil }
        return MeUpdate(build: d.integer(forKey: "iosUpdBuild"), name: d.string(forKey: "iosUpdName") ?? "", notes: d.string(forKey: "iosUpdNotes") ?? "",
                        url: d.string(forKey: "iosUpdUrl") ?? "", at: d.double(forKey: "iosUpdAt"))
    }
}

/// " · 2.29", or nothing when the version's name isn't known.
func verTail(_ name: String) -> String { name.isEmpty ? "" : " · " + name }

/// "Checked just now", "Checked 5 min ago", "Checked yesterday"…
func checkedText(_ at: Double) -> String {
    if at <= 0 { return t("Not checked yet", "Bado haijaangaliwa") }
    let min = Int((Date().timeIntervalSince1970 - at) / 60)
    if min < 2 { return t("Checked just now", "Imeangaliwa sasa hivi") }
    if min < 60 { return t("Checked \(min) min ago", "Imeangaliwa dakika \(min) zilizopita") }
    if min < 1440 { return t("Checked \(min / 60) h ago", "Imeangaliwa saa \(min / 60) zilizopita") }
    if min < 2880 { return t("Checked yesterday", "Imeangaliwa jana") }
    return t("Checked \(min / 1440) days ago", "Imeangaliwa siku \(min / 1440) zilizopita")
}
