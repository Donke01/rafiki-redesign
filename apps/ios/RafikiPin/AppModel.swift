import AuthenticationServices
import CryptoKit
import Foundation
import SwiftUI
import UIKit

/// What happened to a change: sent now, saved for later, or refused.
enum Outcome {
    case sent(JSON, String?)
    case queued
    case failed(String)
    case locked
}

/// Where you are in the app: a tab, or a project / sub-screen on top of it.
struct Place: Equatable {
    var tab: String; var project: String? = nil; var projectTab = "pin"; var sub: String? = nil
}

@MainActor
final class AppModel: ObservableObject {
    static let server = (Bundle.main.object(forInfoDictionaryKey: "RafikiServer") as? String) ?? "https://rafiki-redesign.pages.dev"

    let prefs: Prefs
    let api: Api
    let outbox: Outbox
    let locator = Locator.shared
    let demo: Bool

    @Published var snap: Snapshot?
    @Published var signedIn: Bool
    @Published var waiting: Int
    @Published var banner: String?
    @Published var busy = false
    @Published var activity: [JSON]?
    @Published var team: [Mate]?
    /// True while the app waits for Face ID / Touch ID before showing anything.
    @Published var bioLocked = false
    @Published var stack: [Place] = [Place(tab: "list")]
    /// Bumped when the language or colours change, so every screen redraws.
    @Published var look = 0

    private var sending = false
    private var timer: Timer?

    init(demo: Bool = ProcessInfo.processInfo.arguments.contains("-demo")) {
        self.demo = demo
        let p = Prefs(suite: demo ? "rafiki-demo" : nil)
        prefs = p
        api = Api(server: AppModel.server, pin: { p.pin }, session: { p.session })
        outbox = Outbox(file: Prefs.dataDir.appendingPathComponent(demo ? "outbox-demo.json" : "outbox.json"))
        if demo { Demo.setUp(p, api) }
        let s = p.loadSnapshot().map(Snapshot.from)
        snap = s
        signedIn = (!p.pin.isEmpty || !p.session.isEmpty) && s != nil
        waiting = 0
        waiting = outbox.count()
        let l = p.lang.isEmpty ? (Locale.preferredLanguages.first?.hasPrefix("sw") == true ? "sw" : "en") : p.lang
        Lang.sw = l == "sw"
        Look.mode = p.theme
        bioLocked = p.bio && signedIn
        timer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            Task { @MainActor in if self?.signedIn == true { _ = await self?.sendWaiting() } }
        }
    }

    var miles: Bool { prefs.miles }
    var fix: Fix? { locator.fix }

    // --- navigation ------------------------------------------------------------------------
    var here: Place { stack.last ?? Place(tab: "list") }
    func go(_ p: Place) { stack.append(p) }
    func back() { if stack.count > 1 { stack.removeLast() } }
    func tab(_ t: String) { stack = [Place(tab: t)] }

    func setLang(_ sw: Bool) { Lang.sw = sw; prefs.lang = sw ? "sw" : "en"; look += 1 }
    func setTheme(_ k: String) { Look.mode = k; prefs.theme = k; look += 1 }

    func showBanner(_ s: String?) { banner = s }

    /// Sign in with a PIN; shared team PINs need a name. Returns an error text, or nil when in.
    func signIn(pin: String, name: String) async -> String? {
        prefs.pin = pin
        let r = await api.list()
        if !r.ok {
            prefs.pin = ""
            return r.status == 0 ? t("No signal. Connect to the internet to sign in the first time.", "Hakuna mtandao. Unganisha intaneti ili uingie mara ya kwanza.") : reasonText(r.reason)
        }
        let who = r.json.str("who")
        if who.isEmpty && name.trimmingCharacters(in: .whitespaces).isEmpty { prefs.pin = ""; return "NEED_NAME" }
        prefs.name = who.isEmpty ? name.trimmingCharacters(in: .whitespaces) : who
        accept(r.json)
        signedIn = true
        return nil
    }

    // --- Face ID / Touch ID lock ----------------------------------------------------------
    private var awayAt: Date?
    func wentAway() { awayAt = Date() }
    /// Back in the app: after a minute away, ask for Face ID again.
    func cameBack() { if prefs.bio, signedIn, let a = awayAt, Date().timeIntervalSince(a) > 60 { bioLocked = true }; awayAt = nil }
    func unlocked() { bioLocked = false }
    /// "Lock the app": with Face ID on it only locks (the PIN stays); otherwise it signs out.
    func lockOrSignOut() { if prefs.bio { bioLocked = true } else { lock() } }

    // --- monday.com with a personal token ---------------------------------------------------
    /// Sign in on the lock screen with a personal monday.com token. Returns an error text, or nil when in.
    func mondayToken(_ token: String) async -> String? {
        let x = await api.authToken(token.trimmingCharacters(in: .whitespacesAndNewlines))
        let sid = x.json.str("sid")
        if !x.ok || sid.isEmpty { return x.status == 0 ? reasonText("offline") : reasonText(x.reason) }
        prefs.pin = ""; prefs.session = sid
        let r = await api.list()
        if !r.ok { prefs.session = ""; return reasonText(r.reason) }
        prefs.name = r.json.str("who")
        accept(r.json); signedIn = true
        return nil
    }
    /// Me › Your name on monday.com: link this person's own token to their PIN. Returns an error text, or nil.
    func linkMonday(_ token: String) async -> String? {
        let r = await api.post(["action": "monday-link", "token": token.trimmingCharacters(in: .whitespacesAndNewlines)])
        if !r.ok { return r.status == 0 ? reasonText("offline") : reasonText(r.reason) }
        if var s = snap { s.linked = r.json.str("name").isEmpty ? prefs.name : r.json.str("name"); snap = s }
        return nil
    }
    func unlinkMonday() async { let r = await api.post(["action": "monday-unlink"]); if r.ok, var s = snap { s.linked = ""; snap = s } }

    // --- the team: names, home country, phone and when each was last seen -------------------
    @discardableResult func loadTeam() async -> Bool {
        let r = await api.team()
        guard r.ok else { return false }
        team = r.json.objs("team").map { Mate(name: $0.str("name"), home: $0.str("home"), phone: $0.str("phone"), lastSeen: $0.str("lastSeen").isEmpty || $0.str("lastSeen") == "null" ? nil : $0.str("lastSeen")) }.filter { !$0.name.isEmpty }
        return true
    }

    func lock() {
        bioLocked = false
        let s = prefs.session
        prefs.forget(); signedIn = false
        if !s.isEmpty {
            let a = Api(server: AppModel.server, pin: { "" }, session: { s }); a.transport = api.transport
            Task { _ = await a.logout() }
        }
    }

    // --- monday.com sign-in ----------------------------------------------------------------
    func mondayAvailable() async -> Bool { await api.authMe().json.bool("available") }

    /// The address that starts a monday.com sign-in. A secret stays on the phone; only its fingerprint goes out.
    func mondayStartUrl() -> URL? {
        var b = [UInt8](repeating: 0, count: 48)
        _ = SecRandomCopyBytes(kSecRandomDefault, b.count, &b)
        let verifier = AppModel.b64url(Data(b))
        prefs.verifier = verifier
        let cc = AppModel.b64url(Data(SHA256.hash(data: Data(verifier.utf8))))
        return URL(string: AppModel.server + "/api/pin-auth/start?app=1&cc=" + cc)
    }
    static func b64url(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    /// monday.com sent the person back to the app. Returns an error text, or nil when signed in.
    func mondayReturn(code: String?, fail: String?) async -> String? {
        if fail != nil || (code ?? "").isEmpty {
            switch fail {
            case "denied": return t("Sign-in was cancelled.", "Kuingia kumeghairiwa.")
            case "account": return t("That monday.com login isn’t part of RAFIKI’s monday.com account. Sign in with your RAFIKI login.", "Akaunti hiyo ya monday.com si sehemu ya akaunti ya RAFIKI. Ingia kwa akaunti yako ya RAFIKI.")
            case "board": return t("Your monday.com login can’t see the projects board yet. Ask an admin to add you to it, then try again.", "Akaunti yako ya monday.com bado haioni bodi ya miradi. Muombe msimamizi akuongeze, kisha ujaribu tena.")
            case "expired": return t("Sign-in took too long. Please try again.", "Kuingia kumechukua muda mrefu. Tafadhali jaribu tena.")
            default: return t("monday.com didn’t finish the sign-in. Please try again.", "monday.com haikukamilisha kuingia. Tafadhali jaribu tena.")
            }
        }
        let x = await api.authExchange(code: code!, verifier: prefs.verifier)
        prefs.verifier = ""
        let sid = x.json.str("sid")
        if !x.ok || sid.isEmpty { return x.status == 0 ? reasonText("offline") : t("Sign-in took too long. Please try again.", "Kuingia kumechukua muda mrefu. Tafadhali jaribu tena.") }
        prefs.pin = ""; prefs.session = sid
        let r = await api.list()
        if !r.ok { prefs.session = ""; return reasonText(r.reason) }
        prefs.name = r.json.str("who")
        accept(r.json); signedIn = true
        return nil
    }

    /// Opens monday.com in a secure browser sheet; it comes back to org.rafikioutreach.pin://signin.
    private var authSession: ASWebAuthenticationSession?
    private let presenter = AuthPresenter()
    func mondaySignIn() {
        guard let url = mondayStartUrl() else { return }
        let s = ASWebAuthenticationSession(url: url, callbackURLScheme: "org.rafikioutreach.pin") { [weak self] cb, err in
            Task { @MainActor in
                guard let self else { return }
                if cb == nil { if (err as? ASWebAuthenticationSessionError)?.code != .canceledLogin { self.banner = t("monday.com didn’t finish the sign-in. Please try again.", "monday.com haikukamilisha kuingia. Tafadhali jaribu tena.") }; return }
                let q = URLComponents(url: cb!, resolvingAgainstBaseURL: false)?.queryItems ?? []
                self.banner = t("Signing in…", "Inaingia…")
                self.banner = await self.mondayReturn(code: q.first { $0.name == "code" }?.value, fail: q.first { $0.name == "fail" }?.value)
            }
        }
        s.presentationContextProvider = presenter
        s.prefersEphemeralWebBrowserSession = false
        authSession = s
        s.start()
    }

    // --- activity (Office overview and the monthly report) ---------------------------------
    @discardableResult func loadActivity() async -> Bool {
        let r = await api.activity()
        guard let a = r.json["activity"] as? [Any] else { return false }
        activity = a.compactMap { $0 as? JSON }; return true
    }

    private func accept(_ raw: JSON) { prefs.saveSnapshot(raw); snap = Snapshot.from(raw) }

    /// Sends anything waiting, then fetches the latest list.
    func refresh() { Task { _ = await refreshNow() } }
    func reload() { refresh() }
    @discardableResult func refreshNow() async -> Bool {
        busy = true
        _ = await sendWaiting()
        let r = await api.list()
        busy = false
        if r.ok {
            accept(r.json); let w = r.json.str("who"); if !w.isEmpty { prefs.name = w }
            if banner == t("No signal: showing the list saved on this phone.", "Hakuna mtandao: inaonyesha orodha iliyohifadhiwa kwenye simu hii.") { banner = nil }
            return true
        }
        if r.status == 401 { lock(); banner = reasonText(r.reason) }
        else if r.status == 0 { banner = t("No signal: showing the list saved on this phone.", "Hakuna mtandao: inaonyesha orodha iliyohifadhiwa kwenye simu hii.") }
        return false
    }

    func sendWaiting() async -> Outbox.Result {
        while sending { try? await Task.sleep(nanoseconds: 200_000_000) }
        sending = true; defer { sending = false }
        let res = await outbox.flush(api)
        waiting = outbox.count()
        if res.locked { lock(); banner = t("Your PIN stopped working. Please sign in again; nothing was lost.", "PIN yako imeacha kufanya kazi. Tafadhali ingia tena; hakuna kilichopotea.") }
        return res
    }

    /// Queue a change, try to send it now, and say what happened.
    func submit(_ body: JSON, _ files: [OutFile]) async -> Outcome {
        var b = body; b["by"] = prefs.name
        if b["savedAt"] == nil { b["savedAt"] = When.now() }
        let k = outbox.add(b, files)
        waiting = outbox.count()
        let res = await sendWaiting()
        if res.locked { return .locked }
        if let f = res.failed[k], !res.sent.contains(k) { return .failed(f) }
        if res.sent.contains(k) { return .sent(res.results[k]?.json ?? [:], res.failed[k]) }
        return .queued
    }

    // --- local updates so the screens change straight away ---------------------------------
    func localPin(_ p: Project, _ gps: LatLng?) { edit { s in if let i = s.projects.firstIndex(where: { $0.id == p.id }) { s.projects[i].gps = gps } } }
    func localVisit(_ p: Project, state: String, note: String, urgency: String, problems: [String]) {
        edit { s in
            let now = When.now()
            if state == "working" { s.repairs[p.id] = nil } else { s.repairs[p.id] = Repair(state: state, by: prefs.name, at: now, note: note, urgency: urgency, problems: problems) }
            s.visits[p.id] = LastVisit(at: now, by: prefs.name, state: state)
        }
    }
    func localFixed(_ p: Project) { edit { $0.repairs[p.id] = nil } }
    private func edit(_ f: (inout Snapshot) -> Void) { if var s = snap { f(&s); snap = s } }

    func history(_ id: String) async -> ([Note]?, String?) {
        let r = await api.history(id)
        if !r.ok { return (nil, reasonText(r.reason)) }
        let notes = r.json.objs("notes").map { o in
            Note(id: o.str("id"), text: o.str("text"), at: o.str("at"), by: o.str("by"), files: o.objs("files").map { NoteFile(name: $0.str("name"), ext: $0.str("ext"), url: $0.str("url")) })
        }
        return (notes, nil)
    }

    // --- photos and voice notes, kept until sent -------------------------------------------
    static var mediaDir: URL {
        let u = Prefs.dataDir.appendingPathComponent("photos", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }
    /// A photo, turned upright and shrunk to 1600 px. Returns its path inside the app's data folder.
    func keepPhoto(_ img: UIImage) -> String? {
        let k = min(1, 1600 / max(img.size.width, img.size.height))
        let size = CGSize(width: (img.size.width * k).rounded(), height: (img.size.height * k).rounded())
        let f = UIGraphicsImageRendererFormat(); f.scale = 1
        let out = UIGraphicsImageRenderer(size: size, format: f).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
        guard let data = out.jpegData(compressionQuality: 0.82) else { return nil }
        let name = "p\(Int(Date().timeIntervalSince1970 * 1000)).jpg"
        do { try data.write(to: AppModel.mediaDir.appendingPathComponent(name)) } catch { return nil }
        return "photos/" + name
    }
}

final class AuthPresenter: NSObject, ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first ?? ASPresentationAnchor()
    }
}
