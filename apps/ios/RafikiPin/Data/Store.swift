import Foundation
import Security

/// Small settings (name, units…) plus the last project list, kept on the phone.
/// The PIN and the monday.com session are kept in the Keychain.
final class Prefs {
    private let d: UserDefaults
    private let dir: URL
    private let secure: Bool

    init(suite: String? = nil) {
        d = suite.flatMap { UserDefaults(suiteName: $0) } ?? .standard
        secure = suite == nil
        dir = Prefs.dataDir
    }

    static var dataDir: URL {
        let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("rafiki", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    private func s(_ k: String, _ def: String = "") -> String { d.string(forKey: k) ?? def }
    var name: String { get { s("name") } set { d.set(newValue, forKey: "name") } }
    var miles: Bool { get { d.bool(forKey: "miles") } set { d.set(newValue, forKey: "miles") } }
    var filter: String { get { s("filter", "missing") } set { d.set(newValue, forKey: "filter") } }
    var country: String { get { s("country") } set { d.set(newValue, forKey: "country") } }
    var verifier: String { get { s("verifier") } set { d.set(newValue, forKey: "verifier") } }
    var route: String { get { s("route") } set { d.set(newValue, forKey: "route") } }
    var theme: String { get { s("theme") } set { d.set(newValue, forKey: "theme") } }
    var lang: String { get { s("lang") } set { d.set(newValue, forKey: "lang") } }
    /// The PIN session's number from the server (GET /api/pin "gen"), sent back as X-Pin-Gen; "" until known.
    var pinGen: String { get { s("pinGen") } set { d.set(newValue, forKey: "pinGen") } }
    /// The welcome screens have been seen.
    var onb: Bool { get { d.bool(forKey: "onb") } set { d.set(newValue, forKey: "onb") } }
    /// Notifications: when the list was marked read, kinds turned off, phone alerts on, and the newest one the phone was told about.
    var noteSeen: String { get { s("noteSeen") } set { d.set(newValue, forKey: "noteSeen") } }
    var noteOff: Set<String> { get { Set(s("noteOff").split(separator: ",").map(String.init).filter { !$0.isEmpty }) } set { d.set(newValue.sorted().joined(separator: ","), forKey: "noteOff") } }
    /// Notifications opened one by one since "Mark all as read" (their keys).
    var noteRead: Set<String> { get { Set((d.array(forKey: "noteRead") as? [String]) ?? []) } set { d.set(Array(newValue), forKey: "noteRead") } }
    var phoneAlerts: Bool { get { d.object(forKey: "phoneAlerts") == nil ? true : d.bool(forKey: "phoneAlerts") } set { d.set(newValue, forKey: "phoneAlerts") } }
    var notifiedAt: String { get { s("notifiedAt") } set { d.set(newValue, forKey: "notifiedAt") } }
    var reviewAt: String { get { s("reviewAt") } set { d.set(newValue, forKey: "reviewAt") } }
    /// Projects: the type of project shown ("" = all).
    var ftype: String { get { s("ftype") } set { d.set(newValue, forKey: "ftype") } }
    /// Text size: "s" small, "" normal, "l" large, "xl" larger.
    var textSize: String { get { s("textSize") } set { d.set(newValue, forKey: "textSize") } }
    /// Any small value a screen wants to remember (map style, layers, drafts).
    func get(_ k: String, _ def: String = "") -> String { s("x." + k, def) }
    func set(_ k: String, _ v: String) { d.set(v, forKey: "x." + k) }

    var pin: String { get { secret("pin") } set { setSecret("pin", newValue) } }
    var session: String { get { secret("session") } set { setSecret("session", newValue) } }

    private func secret(_ k: String) -> String {
        if !secure { return s("x-" + k) }
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "org.rafikioutreach.pin", kSecAttrAccount as String: k, kSecReturnData as String: true]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }
    private func setSecret(_ k: String, _ v: String) {
        if !secure { d.set(v, forKey: "x-" + k); return }
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "org.rafikioutreach.pin", kSecAttrAccount as String: k]
        SecItemDelete(q as CFDictionary)
        if v.isEmpty { return }
        var add = q; add[kSecValueData as String] = v.data(using: .utf8); add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    func saveSnapshot(_ raw: JSON) {
        if let data = try? JSONSerialization.data(withJSONObject: raw) { try? data.write(to: dir.appendingPathComponent("projects.json"), options: .atomic) }
    }
    func loadSnapshot() -> JSON? {
        guard let data = try? Data(contentsOf: dir.appendingPathComponent("projects.json")) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? JSON
    }
    var bio: Bool { get { d.bool(forKey: "bio") } set { d.set(newValue, forKey: "bio") } }
    /// Me & team: which half is showing, "me" or "team".
    var mePane: String { get { d.string(forKey: "mePane") ?? "me" } set { d.set(newValue, forKey: "mePane") } }
    func forget() { pin = ""; session = ""; pinGen = ""; bio = false }
}

/// A photo or voice note waiting to go with a change.
struct OutFile: Equatable { var path: String; var kind: String; var label: String }

/// One change waiting to be sent: the action, its files, and how far it got.
struct OutItem {
    var k: Int; var body: JSON; var files: [OutFile]; var updateId: String? = nil; var done = 0

    func toJson() -> JSON {
        ["k": k, "body": body, "updateId": updateId.map { $0 as Any } ?? NSNull(), "done": done, "files": files.map { ["path": $0.path, "kind": $0.kind, "label": $0.label] }]
    }
    static func from(_ o: JSON) -> OutItem {
        let u = o.str("updateId")
        return OutItem(k: o.int("k"), body: o.obj("body") ?? [:],
                       files: o.objs("files").map { OutFile(path: $0.str("path"), kind: $0.str("kind").isEmpty ? "photo" : $0.str("kind"), label: $0.str("label")) },
                       updateId: u.isEmpty || u == "null" ? nil : u, done: o.int("done"))
    }
}

/// Every change is written to the phone first, then sent; nothing is lost without signal.
final class Outbox {
    private let file: URL
    private let lock = NSLock()
    init(file: URL) { self.file = file }

    /// Files are kept relative to the app's data folder, because iOS moves the app's folder on updates.
    static func resolve(_ path: String) -> URL { path.hasPrefix("/") ? URL(fileURLWithPath: path) : Prefs.dataDir.appendingPathComponent(path) }

    func all() -> [OutItem] {
        lock.lock(); defer { lock.unlock() }
        return read()
    }
    private func read() -> [OutItem] {
        guard let data = try? Data(contentsOf: file), let a = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { return [] }
        return a.compactMap { ($0 as? JSON).map(OutItem.from) }
    }
    private func save(_ items: [OutItem]) {
        if let data = try? JSONSerialization.data(withJSONObject: items.map { $0.toJson() }) { try? data.write(to: file, options: .atomic) }
    }
    @discardableResult func add(_ body: JSON, _ files: [OutFile]) -> Int {
        lock.lock(); defer { lock.unlock() }
        let items = read(); let k = (items.map(\.k).max() ?? 0) + 1
        save(items + [OutItem(k: k, body: body, files: files)]); return k
    }
    func put(_ item: OutItem) { lock.lock(); defer { lock.unlock() }; save(read().map { $0.k == item.k ? item : $0 }) }
    func remove(_ k: Int) {
        lock.lock(); defer { lock.unlock() }
        let items = read()
        items.first { $0.k == k }?.files.forEach { try? FileManager.default.removeItem(at: Outbox.resolve($0.path)) }
        save(items.filter { $0.k != k })
    }
    func count() -> Int { all().count }

    struct Result { var sent: [Int] = []; var failed: [Int: String] = [:]; var waiting = 0; var locked = false; var results: [Int: Reply] = [:] }

    /// Sends everything waiting, oldest first. Same rules as the web and Android outbox.
    func flush(_ api: Api) async -> Result {
        var res = Result()
        for var it in all().sorted(by: { $0.k < $1.k }) {
            if res.locked { res.waiting += 1; continue }
            let r = it.updateId != nil ? Reply(ok: true, status: 200, json: ["ok": true, "updateId": it.updateId!], reason: nil) : await api.post(it.body)
            if !r.ok {
                if r.retryLater { res.waiting += 1 }
                else if r.status == 401 { res.locked = true; res.waiting += 1 }
                else { res.failed[it.k] = reasonText(r.reason); remove(it.k) }
                continue
            }
            res.results[it.k] = r
            let upd = r.json.str("updateId")
            if it.files.isEmpty || upd.isEmpty { res.sent.append(it.k); remove(it.k); continue }
            if it.updateId == nil { it.updateId = upd; it.done = 0; put(it) }
            var lost = 0, stopped = false
            for (i, f) in it.files.enumerated() where i >= it.done {
                let p = await api.upload(updateId: it.updateId!, file: Outbox.resolve(f.path), kind: f.kind, label: f.label)
                if p.ok { it.done = i + 1; put(it); continue }
                if p.retryLater || p.status == 401 { stopped = true; break }
                lost += 1; it.done = i + 1; put(it)
            }
            if stopped { res.waiting += 1; continue }
            res.sent.append(it.k)
            if lost > 0 { res.failed[it.k] = t("Saved, but " + (lost == 1 ? "one file" : "\(lost) files") + " couldn’t be added.", "Imehifadhiwa, lakini " + (lost == 1 ? "faili moja" : "faili \(lost)") + " hazikuweza kuongezwa.") }
            remove(it.k)
        }
        return res
    }
}
