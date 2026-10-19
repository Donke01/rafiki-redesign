import SwiftUI
import UIKit

/// Where something is: the chosen point and how it was chosen.
final class Spot: ObservableObject {
    @Published var ll: LatLng?
    @Published var how = "map"
    @Published var acc: Int?
    @Published var found = ""
}

/// Move the map under the cross, use the phone's GPS, paste a point or a Google Maps link,
/// or search for a place by name. Shared by "Pin location" and "New site".
struct SpotPicker: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var loc = Locator.shared
    @ObservedObject var spot: Spot
    let start: LatLng?
    let ck: String
    var others: [Dot] = []
    @State private var jump: LatLng?
    @State private var paste = ""
    @State private var q = ""
    @State private var places: [(String, String, LatLng)] = []
    @State private var msg: String?
    @State private var waitGps = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(t("Move the map so the cross is on the exact spot, or use your location.", "Sogeza ramani hadi msalaba uwe mahali kamili, au tumia mahali ulipo.")).font(.bMedium).foregroundColor(C.muted)
            PickMapBox(start: start ?? loc.fix?.ll, jumpTo: jump, others: others) { ll in
                spot.ll = ll
                if spot.how == "gps", spot.acc != nil, let j = jump, Geo.metres(ll, j) > 3 { spot.how = "map"; spot.acc = nil }
            }.frame(height: 320).padding(.top, 10)
            if let ll = spot.ll { Text(ll.text() + (spot.acc.map { " · GPS ±\($0) m" } ?? "")).font(.lMedium).foregroundColor(C.ink).padding(.top, 8) }
            Btn(waitGps ? t("Finding you…", "Inakutafuta…") : t("Use my location", "Tumia mahali nilipo"), icon: "pin", small: true, wide: true) {
                if let f = loc.fix {
                    spot.how = "gps"; spot.acc = f.acc; jump = f.ll
                    msg = f.acc > 25 ? t("GPS is ±\(f.acc) m. It gets better in the open; tap again in a moment.", "GPS ni ±\(f.acc) m. Inakuwa bora mahali wazi; gusa tena baada ya muda.") : nil
                } else {
                    waitGps = true; loc.start()
                    msg = loc.denied ? t("Allow Location for RAFIKI Pin in your iPhone’s Settings.", "Ruhusu Location kwa RAFIKI Pin kwenye Settings za iPhone.") : t("Waiting for GPS. Stand in the open for a moment.", "Inasubiri GPS. Simama mahali wazi kwa muda.")
                }
            }.padding(.top, 10)
            Msg(msg, "warn")

            Field(t("Coordinates or a Google Maps link", "Coordinates au kiungo cha Google Maps"), $paste, placeholder: "0.561368, 35.065480")
            if !paste.trimmingCharacters(in: .whitespaces).isEmpty {
                Btn(t("Use this point", "Tumia hapa"), light: true, small: true) {
                    if let ll = Geo.parse(paste) { spot.how = "paste"; spot.acc = nil; jump = ll; msg = nil }
                    else { msg = t("Couldn’t read that. Try 0.561368, 35.065480 or a Google Maps link with the point in it.", "Haikusomeka. Jaribu 0.561368, 35.065480 au kiungo cha Google Maps chenye mahali.") }
                }.padding(.top, 8)
            }
            Field(t("Find a place by name", "Tafuta mahali kwa jina"), $q, placeholder: t("e.g. Kiptoo Primary School, Nandi", "mf. Shule ya Msingi Kiptoo, Nandi"))
            if !q.trimmingCharacters(in: .whitespaces).isEmpty {
                Btn(t("Search", "Tafuta"), icon: "search", light: true, small: true) {
                    Task {
                        msg = t("Searching…", "Inatafuta…")
                        let r = await m.api.place(q, near: loc.fix?.ll ?? start, ck: ck)
                        places = r.json.objs("places").compactMap { o in
                            guard let la = o.num("lat"), let ln = o.num("lng") else { return nil }
                            return (o.str("name"), o.str("where"), LatLng(lat: la, lng: ln))
                        }
                        msg = !r.ok ? reasonText(r.reason) : places.isEmpty ? t("Nothing found. Try a nearby town or school.", "Hakuna kilichopatikana. Jaribu mji au shule iliyo karibu.") : nil
                    }
                }.padding(.top, 8)
            }
            ForEach(Array(places.prefix(6).enumerated()), id: \.offset) { _, pl in
                Button {
                    spot.how = "search"; spot.found = "\(pl.0), \(pl.1)"; spot.acc = nil; jump = pl.2; places = []; q = pl.0
                } label: {
                    VStack(alignment: .leading, spacing: 2) { Text(pl.0).font(.tSmall).foregroundColor(C.ink); Text(pl.1).font(.bSmall).foregroundColor(C.muted) }
                        .frame(maxWidth: .infinity, alignment: .leading).padding(12).background(C.card)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line, lineWidth: 1))
                }.buttonStyle(.plain).padding(.top, 8)
            }
        }
        .onChange(of: loc.fix) { f in
            guard waitGps, let f else { return }
            if f.acc <= 25 || Date().timeIntervalSince(f.at) < 3 { waitGps = false; spot.how = "gps"; spot.acc = f.acc; jump = f.ll; msg = nil }
        }
    }
}

struct PinTab: View {
    @EnvironmentObject var m: AppModel
    let p: Project
    @StateObject private var spot = Spot()
    @State private var shots: [Shot] = []
    @State private var msg: (String, String)?
    @State private var busy = false
    @State private var saved = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SpotPicker(spot: spot, start: p.gps, ck: p.ck,
                       others: (m.snap?.projects ?? []).filter { $0.id != p.id && $0.gps != nil }.map { Dot(id: $0.id, ll: $0.gps!, color: UIColor(red: 0x1C / 255, green: 0x7C / 255, blue: 0x4A / 255, alpha: 1), big: false, title: $0.title) })
            Text(t("Photos (optional)", "Picha (si lazima)")).font(.tSmall).foregroundColor(C.ink).padding(.top, 14).padding(.bottom, 8)
            PhotoSlots(slots: [], shots: $shots, max: 5)
            Btn(t("Save this location", "Hifadhi mahali hapa"), icon: "check", enabled: spot.ll != nil && !busy, wide: true) { save() }.padding(.top, 16)
            if case let (tone, text)? = msg { Msg(text, tone) }
            if saved {
                Btn(t("Undo", "Tendua"), icon: "undo", light: true, small: true) {
                    Task {
                        let o = await m.submit(["action": "undo", "id": p.id], [])
                        if case .sent = o { m.refresh(); saved = false; msg = ("ok", t("Undone. The location is back to what it was.", "Imetenduliwa. Mahali pamerudi kama awali.")) }
                        else if case .failed(let s) = o { msg = ("bad", s) }
                    }
                }.padding(.top, 8)
            }
        }
    }

    func save() {
        guard let ll = spot.ll else { return }
        busy = true
        Task {
            var body: JSON = ["action": "pin", "id": p.id, "lat": ll.lat, "lng": ll.lng, "how": spot.how, "found": spot.found]
            if let a = spot.acc { body["acc"] = a }
            switch await m.submit(body, shots.map { OutFile(path: $0.path, kind: "photo", label: "Location photo") }) {
            case .sent(let reply, let note):
                if reply.bool("pending") { msg = ("warn", t("This location was confirmed by the office, so your change was sent to them to approve.", "Mahali hapa pamethibitishwa na ofisi, kwa hiyo badiliko lako limetumwa kwao kuidhinisha.")) }
                else { m.localPin(p, ll); saved = true; msg = ("ok", t("Location saved in monday.com ✓", "Mahali pamehifadhiwa kwenye monday.com ✓") + (note.map { " \($0)" } ?? "")) }
                shots = []
            case .queued: m.localPin(p, ll); saved = true; shots = []; msg = ("warn", t("Saved on this phone. It will be sent when you have signal.", "Imehifadhiwa kwenye simu. Itatumwa ukipata mtandao."))
            case .failed(let s): msg = ("bad", s)
            case .locked: break
            }
            busy = false
        }
    }
}
