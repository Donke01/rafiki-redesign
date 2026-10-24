import SwiftUI

/// Me › Settings › Share my location with the office.
/// Privacy first: off unless the person turns it on; only in the work hours they choose (Mon–Sat 07:00–18:00 to start
/// with); at most every 10 minutes; only while the app is open. The server keeps only the latest point, for 12 hours,
/// and only admins see it on the Team map. The settings are kept on this phone.
enum ShareHere {
    /// [days]: 1 = Monday … 7 = Sunday. [from] / [to]: minutes after midnight.
    struct Hours: Equatable { var on = false; var days: Set<Int> = [1, 2, 3, 4, 5, 6]; var from = 7 * 60; var to = 18 * 60 }
    static let GAP: TimeInterval = 10 * 60
    private static let d = UserDefaults.standard

    static func load() -> Hours {
        let days = Set((d.string(forKey: "share.days") ?? "1,2,3,4,5,6").split(separator: ",").compactMap { Int($0) }.filter { (1...7).contains($0) })
        return Hours(on: d.bool(forKey: "share.on"), days: days,
                     from: d.object(forKey: "share.from") == nil ? 420 : max(0, min(1439, d.integer(forKey: "share.from"))),
                     to: d.object(forKey: "share.to") == nil ? 1080 : max(0, min(1439, d.integer(forKey: "share.to"))))
    }
    static func save(_ h: Hours) {
        d.set(h.on, forKey: "share.on"); d.set(h.days.sorted().map(String.init).joined(separator: ","), forKey: "share.days")
        d.set(h.from, forKey: "share.from"); d.set(h.to, forKey: "share.to")
        if !h.on { lastSent = .distantPast }
    }
    static var lastSent: Date {
        get { d.object(forKey: "share.sent") as? Date ?? .distantPast }
        set { d.set(newValue, forKey: "share.sent") }
    }
    static func inHours(_ h: Hours, now: Date = Date()) -> Bool {
        let c = Calendar.current.dateComponents([.weekday, .hour, .minute], from: now)
        let m = (c.hour ?? 0) * 60 + (c.minute ?? 0)
        let wd = ((c.weekday ?? 1) + 5) % 7 + 1 // Monday = 1 … Sunday = 7
        if !h.days.contains(wd) { return false }
        return h.from <= h.to ? (m >= h.from && m < h.to) : (m >= h.from || m < h.to)
    }
    static func hhmm(_ min: Int) -> String { String(format: "%02d:%02d", min / 60, min % 60) }
    private static let DAYS = [("Mon", "Jtt"), ("Tue", "Jnn"), ("Wed", "Jtn"), ("Thu", "Alh"), ("Fri", "Ijm"), ("Sat", "Jms"), ("Sun", "Jpl")]
    static func dayName(_ i: Int) -> String { let x = DAYS[i - 1]; return t(x.0, x.1) }
    /// "Mon–Sat · 07:00–18:00"
    static func hoursText(_ h: Hours) -> String {
        let ds = h.days.sorted()
        let run = ds.count > 2 && zip(ds, ds.dropFirst()).allSatisfy { $1 == $0 + 1 }
        let dd = ds.isEmpty ? t("No days", "Hakuna siku") : run ? dayName(ds.first!) + "–" + dayName(ds.last!) : ds.map(dayName).joined(separator: ", ")
        return dd + " · " + hhmm(h.from) + "–" + hhmm(h.to)
    }
    /// Sharing right now: turned on, signed in with their own name, and in work hours.
    static func active(_ h: Hours, own: Bool, now: Date = Date()) -> Bool { h.on && own && inHours(h, now: now) }
    /// Sends where the phone is, if sharing and due. Returns true when a point went out.
    @discardableResult static func maybeSend(_ api: Api, own: Bool, fix: Fix?) async -> Bool {
        guard let f = fix, active(load(), own: own), Date().timeIntervalSince(lastSent) >= GAP else { return false }
        lastSent = Date()
        return await api.post(["action": "here", "lat": f.ll.lat, "lng": f.ll.lng, "acc": f.acc]).ok
    }
    /// Turned off: the server forgets the point straight away.
    static func stop(_ api: Api) async {
        var h = load(); h.on = false; save(h)
        _ = await api.post(["action": "here", "off": true])
    }
}

/// "Sharing location with the office · Stop", under the header on every screen while sharing.
struct SharingBar: View {
    @EnvironmentObject var m: AppModel
    @State private var tick = 0
    @State private var ping = false
    var body: some View {
        let own = !(m.snap?.who ?? "").isEmpty
        let _ = tick
        let on = ShareHere.active(ShareHere.load(), own: own)
        Group {
            if on {
                HStack(spacing: 8) {
                    ZStack {
                        if Motion.on { Circle().stroke(C.ok, lineWidth: 2).frame(width: 20, height: 20).scaleEffect(ping ? 1.45 : 0.45).opacity(ping ? 0 : 0.85) }
                        Circle().fill(C.ok).frame(width: 9, height: 9)
                    }.frame(width: 20, height: 20)
                    Text(t("Sharing location with the office", "Unashiriki mahali na ofisi")).font(.mont(13, .heavy)).foregroundColor(C.ok).lineLimit(1)
                    Button { Task { await ShareHere.stop(m.api); tick += 1 } } label: {
                        Text(t("Stop", "Acha")).font(.mont(13, .heavy)).foregroundColor(C.ink).padding(.horizontal, 14).frame(minHeight: 32).background(C.card).clipShape(Capsule())
                    }.buttonStyle(.plain)
                }
                .padding(.leading, 12).padding(.trailing, 4).padding(.vertical, 4)
                .background(C.okBg).clipShape(Capsule()).overlay(Capsule().stroke(C.line, lineWidth: 1))
                .padding(.horizontal, 12).padding(.vertical, 2)
                .transition(.move(edge: .top).combined(with: .opacity))
                .onAppear { if Motion.on { withAnimation(.easeOut(duration: 2.2).repeatForever(autoreverses: false)) { ping = true } } }
            }
        }
        .animation(.easeOut(duration: 0.25), value: on)
        .task(id: "\(on)-\(m.fix?.at.timeIntervalSince1970 ?? 0 / 60)") { if on { await ShareHere.maybeSend(m.api, own: own, fix: m.fix) } }
        .task { while !Task.isCancelled { try? await Task.sleep(nanoseconds: 60_000_000_000); tick += 1 } }
    }
}

/// Me › Settings › Security and alerts: the row.
struct ShareRow: View {
    let action: () -> Void
    var body: some View {
        let h = ShareHere.load()
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 12) {
                IconTile(icon: "pin", tint: C.named("green").fg, bg: C.named("green").bg, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(t("Share my location with the office", "Shiriki mahali nilipo na ofisi")).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading)
                    Text(h.on ? t("On · ", "Imewashwa · ") + ShareHere.hoursText(h) : t("Off", "Imezimwa")).font(.bSmall).foregroundColor(h.on ? C.ok : C.muted).lineLimit(1)
                }
                Spacer(minLength: 6); Icon("chev", size: 18, color: C.muted)
            }.padding(.vertical, 10).padding(.horizontal, 2).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

/// Settings › Share my location: on/off, work days and hours.
struct ShareSettingsPage: View {
    @EnvironmentObject var m: AppModel
    @State private var h = ShareHere.load()
    @State private var note: (String, String)?
    @State private var tick = 0
    var body: some View {
        let own = !(m.snap?.who ?? "").isEmpty
        ScrollView {
            VStack(spacing: 0) {
                Text(t("Share my location", "Shiriki mahali nilipo")).font(.hLarge).foregroundColor(C.ink).wide(.center).padding(.top, 4).padding(.bottom, 8)
                CardV {
                    Text(t("While you work, the office can see where you are on a map, to plan visits and keep the team safe. Only admins see it. Only your latest position is kept, for 12 hours, never a history. It is off unless you turn it on, and the app never tracks you in the background.",
                           "Unapofanya kazi, ofisi inaweza kuona ulipo kwenye ramani, ili kupanga ziara na kuilinda timu. Wasimamizi pekee wanaona. Mahali pako pa mwisho tu panahifadhiwa, kwa saa 12, si historia. Imezimwa usipoiwasha, na programu haikufuatilii chinichini."))
                        .font(.bMedium).foregroundColor(C.muted)
                    if !own {
                        Msg(t("You need your own PIN (or monday.com sign-in) to share your location, so the office knows it’s you.", "Unahitaji PIN yako mwenyewe (au kuingia kwa monday.com) ili kushiriki mahali, ili ofisi ijue ni wewe."), "warn")
                    } else {
                        HStack(spacing: 12) {
                            IconTile(icon: "pin", tint: C.named("green").fg, bg: C.named("green").bg, size: 40)
                            Text(t("Share my location with the office during work hours", "Shiriki mahali nilipo na ofisi wakati wa saa za kazi")).font(.tSmall).foregroundColor(C.ink)
                            Spacer(minLength: 8)
                            Toggle2(on: h.on, label: h.on ? t("Turn off", "Zima") : t("Turn on", "Washa")) {
                                if h.on { h.on = false; Task { await ShareHere.stop(m.api) } }
                                else if m.locator.allowed { h.on = true; ShareHere.save(h); m.locator.start(); note = nil }
                                else { m.locator.start(); note = ("bad", t("Allow location for RAFIKI Pin first, then turn it on again.", "Ruhusu mahali kwa RAFIKI Pin kwanza, kisha uiwashe tena.")) }
                            }
                        }.padding(.top, 14)
                        Text(t("Work days", "Siku za kazi")).font(.mont(13, .bold)).foregroundColor(C.ink).padding(.top, 16).padding(.bottom, 6).wide()
                        HStack(spacing: 4) {
                            ForEach(1...7, id: \.self) { dd in
                                let sel = h.days.contains(dd)
                                Button { if sel { h.days.remove(dd) } else { h.days.insert(dd) }; ShareHere.save(h) } label: {
                                    Text(ShareHere.dayName(dd)).font(.mont(12, .bold)).foregroundColor(sel ? .white : C.ink).frame(maxWidth: .infinity, minHeight: 40)
                                        .background(sel ? C.selected : C.card).clipShape(Capsule()).overlay(Capsule().stroke(sel ? C.selected : C.edge, lineWidth: 1))
                                }.buttonStyle(.plain)
                            }
                        }
                        let times = (10...44).map { ShareHere.hhmm($0 * 30) }.map { ($0, $0) }
                        HStack(spacing: 10) {
                            Picker1(label: t("From", "Kuanzia"), value: ShareHere.hhmm(h.from), options: times) { v in h.from = (Int(v.prefix(2)) ?? 7) * 60 + (Int(v.suffix(2)) ?? 0); ShareHere.save(h) }
                            Picker1(label: t("Until", "Hadi"), value: ShareHere.hhmm(h.to), options: times) { v in h.to = (Int(v.prefix(2)) ?? 18) * 60 + (Int(v.suffix(2)) ?? 0); ShareHere.save(h) }
                        }
                        let _ = tick
                        let status: (String, String) = {
                            let sent = ShareHere.lastSent
                            if !h.on { return ("", t("Off. The office can’t see where you are.", "Imezimwa. Ofisi haioni ulipo.")) }
                            if h.days.isEmpty { return ("warn", t("Choose at least one work day.", "Chagua angalau siku moja ya kazi.")) }
                            if ShareHere.inHours(h) { return ("ok", t("Sharing now", "Inashiriki sasa") + (sent > .distantPast ? " · " + t("last sent ", "ilitumwa ") + agoText(When.iso.string(from: sent)) : "")) }
                            return ("", t("Outside your work hours: not sharing now.", "Nje ya saa zako za kazi: haishiriki sasa."))
                        }()
                        Msg((note ?? status).1, (note ?? status).0)
                    }
                }.padding(.top, 8)
            }.padding(16).padding(.bottom, 100)
        }
        .task { while !Task.isCancelled { try? await Task.sleep(nanoseconds: 30_000_000_000); tick += 1 } }
    }
}
