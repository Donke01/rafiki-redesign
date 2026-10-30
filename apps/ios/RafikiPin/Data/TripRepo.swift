import Foundation
import UIKit

/// Field trips, vehicles, fuel and group trips, against /api/itin, /api/vehicles, /api/fuel and /api/group-trips.
/// Offline: every trip opened is kept on the phone with its legs, so it works with no signal. A change made on the phone
/// shows at once (TripEdit) and waits in a queue until it can be sent; the server's answer replaces it.
@MainActor
final class TripRepo: ObservableObject {
    private static var one: TripRepo?
    static func of(_ m: AppModel) -> TripRepo {
        if let r = one { return r }
        if m.demo { DemoChat.install(m.api) }
        let r = TripRepo(api: m.api, file: Prefs.dataDir.appendingPathComponent(m.demo ? "itins-demo.json" : "itins.json"))
        one = r
        return r
    }

    let api: Api
    private let file: URL
    private var store: JSON
    /// The index rows of the trips this person can see (newest first).
    @Published var list: [JSON]
    /// Changes whenever a kept trip changes.
    @Published var tick = 0
    @Published var vehicles: [TripVehicle]

    private init(api: Api, file: URL) {
        self.api = api; self.file = file
        let s = (try? Data(contentsOf: file)).flatMap { try? JSONSerialization.jsonObject(with: $0) as? JSON } ?? [:]
        store = s
        list = s.objs("list")
        vehicles = s.objs("vehicles").map { TripVehicle(raw: $0) }
    }
    private func save() { if let d = try? JSONSerialization.data(withJSONObject: store) { try? d.write(to: file, options: .atomic) } }
    private var recs: JSON { get { store.obj("recs") ?? [:] } set { store["recs"] = newValue } }
    private var queue: [JSON] { get { store.objs("queue") } set { store["queue"] = newValue } }

    /// A trip kept on this phone (with the changes not sent yet).
    func cached(_ id: String) -> TripItin? {
        guard let o = recs.obj(id), let raw = o.obj("raw") else { return nil }
        return TripItin(raw, canEdit: o["edit"] == nil ? true : o.bool("edit"), waiting: pending(id))
    }
    func pending(_ id: String) -> Int { queue.filter { $0.str("id") == id }.count }
    private func keep(_ it: TripItin) { var r = recs; r[it.id] = ["raw": it.raw, "edit": it.canEdit] as JSON; recs = r; save(); tick += 1 }
    /// Every trip kept on this phone (for Me › My trips with no signal).
    func kept() -> [TripItin] { recs.keys.compactMap { cached($0) } }

    // MARK: field trips

    @discardableResult func loadList(_ q: String = "") async -> Reply {
        let r = await api.get("/api/itin" + q)
        if r.ok { let a = r.json.objs("items"); if q.isEmpty || q == "?mine=1" { store["list"] = a; save() }; list = a }
        return r
    }
    func alerts() async -> [JSON] { await api.get("/api/itin?alerts=1").json.objs("alerts") }

    /// Fresh from the server (and kept); with no signal, the kept copy. Returns (trip, progress, error).
    func open(_ id: String, progress: Bool = false) async -> (TripItin?, TripProgress?, String?) {
        _ = await flush()
        let r = await api.get("/api/itin/" + Api.enc(id) + (progress ? "?progress=1" : ""))
        if r.ok {
            guard let o = r.json.obj("itin") else { return (cached(id), nil, nil) }
            let edit = r.json.obj("can").map { $0["edit"] == nil ? true : $0.bool("edit") } ?? true
            let it = TripItin(o, canEdit: edit, waiting: pending(id))
            if pending(id) == 0 { keep(it); return (it, TripProgress.from(r.json.obj("progress")), nil) }
            return (cached(id) ?? it, TripProgress.from(r.json.obj("progress")), nil)
        }
        return (cached(id), nil, r.status == 0 ? nil : tripReason(r.reason))
    }

    func suggest(_ body: JSON) async -> Reply { await api.postTo("/api/itin/suggest", body) }
    func optimise(_ body: JSON) async -> Reply { await api.postTo("/api/itin/optimise", body) }
    func create(_ body: JSON) async -> (TripItin?, String?) {
        let r = await api.postTo("/api/itin", body)
        if r.ok, let o = r.json.obj("itin") { let it = TripItin(o); keep(it); await loadList(); return (it, nil) }
        return (nil, r.status == 0 ? t("No signal. Try again when you're connected.", "Hakuna mtandao. Jaribu tena ukiunganishwa.") : tripReason(r.reason))
    }

    /// A change: `local` (already re-timed on the phone) is kept and shown at once; `body` waits in the queue and is sent
    /// now if there is signal. Returns nil when sent (or waiting for signal), or why it was refused.
    func change(_ local: TripItin, _ body: JSON) async -> String? {
        queue = queue + [["id": local.id, "body": body] as JSON]
        keep(local)
        return await flush()[local.id]
    }

    /// A change that needs the server's answer (share, summary, re-order): no queue.
    func ask(_ id: String, _ body: JSON) async -> Reply {
        var b = body; b["v"] = cached(id)?.v ?? 0
        var r = await api.postTo("/api/itin/" + Api.enc(id), b)
        if r.status == 409, let cur = r.json.obj("current") { keep(TripItin(cur)); b["v"] = cur.int("v"); r = await api.postTo("/api/itin/" + Api.enc(id), b) }
        if r.ok, let o = r.json.obj("itin"), pending(id) == 0 { keep(TripItin(o, canEdit: cached(id)?.canEdit ?? true)) }
        return r
    }

    private var flushing = false
    /// Sends what's waiting, oldest first. Returns why a change was refused, by trip id.
    func flush() async -> [String: String] {
        if flushing { return [:] }
        flushing = true; defer { flushing = false }
        var errs: [String: String] = [:]
        while let q = queue.first {
            let id = q.str("id")
            var body = q.obj("body") ?? [:]
            body["v"] = recs.obj(id)?.obj("raw")?.int("v") ?? 0
            var r = await api.postTo("/api/itin/" + Api.enc(id), body)
            if r.status == 409, let cur = r.json.obj("current") { body["v"] = cur.int("v"); r = await api.postTo("/api/itin/" + Api.enc(id), body) }
            if r.status == 0 || r.status >= 500 || r.status == 429 { break }
            queue = Array(queue.dropFirst())
            if r.ok, let o = r.json.obj("itin") {
                let edit = recs.obj(id).map { $0["edit"] == nil ? true : $0.bool("edit") } ?? true
                if pending(id) == 0 { keep(TripItin(o, canEdit: edit)) }
                else if var rec = recs.obj(id), var raw = rec.obj("raw") { raw["v"] = o.int("v"); rec["raw"] = raw; var all = recs; all[id] = rec; recs = all }
            } else if !r.ok {
                errs[id] = tripReason(r.reason)
                if let cur = r.json.obj("current") { keep(TripItin(cur)) }
            }
        }
        save(); tick += 1
        return errs
    }

    // MARK: vehicles and fuel

    @discardableResult func loadVehicles() async -> Bool {
        let r = await api.get("/api/vehicles")
        guard r.json["vehicles"] != nil else { return false }
        let a = r.json.objs("vehicles"); store["vehicles"] = a; save(); vehicles = a.map { TripVehicle(raw: $0) }; return true
    }
    func logs(_ itin: String) async -> [TripFuelLog] {
        let r = await api.get("/api/fuel?itin=" + Api.enc(itin))
        let key = "logs:" + itin
        var a = store.objs(key)
        if r.json["logs"] != nil { a = r.json.objs("logs"); store[key] = a; save() }
        let waiting = store.objs("logq").filter { $0.str("itinId") == itin }.map { o -> TripFuelLog in var x = o; x["waiting"] = true; return TripFuelLog(raw: x) }
        return a.map { TripFuelLog(raw: $0) } + waiting
    }
    /// A fuel log. With no signal it waits on the phone and is sent with the next one. The receipt photo goes first.
    func addLog(_ body: JSON, receipt: Data?) async -> Reply {
        var b = body
        if let receipt {
            let up = await api.multipart("/api/pin-photo", data: receipt, mime: "image/jpeg", filename: "receipt.jpg", fields: [("kind", "receipt"), ("itin", chatS(body, "itinId"))])
            if up.ok, !up.json.str("id").isEmpty { b["receipt"] = up.json.str("id") }
        }
        let r = await api.postTo("/api/fuel", b)
        if r.status == 0 { store["logq"] = store.objs("logq") + [b]; save() }
        else {
            var rest: [JSON] = []
            for q in store.objs("logq") { let x = await api.postTo("/api/fuel", q); if x.status == 0 { rest.append(q) } }
            store["logq"] = rest; save()
        }
        return r
    }

    // MARK: group trips (admins)

    func groups() async -> [JSON]? {
        let r = await api.get("/api/group-trips")
        if r.ok, r.json["items"] != nil { let a = r.json.objs("items"); store["groups"] = a; save(); return a }
        return store["groups"] == nil ? nil : store.objs("groups")
    }
    func group(_ id: String) async -> (TripGroup?, String?) {
        let r = await api.get("/api/group-trips/" + Api.enc(id))
        if let o = r.json.obj("trip") { store["group:" + id] = o; save(); return (TripGroup(raw: o), nil) }
        return (store.obj("group:" + id).map { TripGroup(raw: $0) }, r.status == 0 ? nil : tripReason(r.reason))
    }
    func groupCreate(_ body: JSON) async -> Reply { await api.postTo("/api/group-trips", body) }
    func groupChange(_ g: TripGroup, _ body: JSON) async -> Reply {
        var b = body; b["v"] = g.v
        var r = await api.postTo("/api/group-trips/" + Api.enc(g.id), b)
        if r.status == 409, let cur = r.json.obj("current") { b["v"] = cur.int("v"); r = await api.postTo("/api/group-trips/" + Api.enc(g.id), b) }
        if let o = r.json.obj("trip") { store["group:" + g.id] = o; save() }
        return r
    }
}

extension AppModel {
    /// Chat (the list, the open chats, the live connection).
    var chat: ChatHub { ChatHub.of(self) }
    /// Field trips, vehicles, fuel and group trips.
    var trips: TripRepo { TripRepo.of(self) }
}
