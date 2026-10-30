import SwiftUI

// Me & team, to the Figma design "RAFIKI Pin — Me & team": a Me | Team switch and the profile card (the phone's own
// settings are in Me › Settings, SettingsCard.swift).

private let WORKS: Set<String> = ["pin", "visit", "fixed", "new"]
private func dayOf(_ iso: String) -> Date? { When.parse(iso).map { Calendar.current.startOfDay(for: $0) } }

/// A small switch: grey track, the chosen one white (Me | Team, Light | Dark | Sun…).
struct SmallSeg: View {
    let items: [(String, String)]; let sel: String; var fill = false; let pick: (String) -> Void
    var body: some View {
        HStack(spacing: 0) {
            ForEach(items, id: \.0) { k, l in
                let on = k == sel
                Button { Haptic.tap(); pick(k) } label: {
                    Text(l).font(.mont(12.5, .bold)).foregroundColor(on ? C.ink : C.muted).lineLimit(1).minimumScaleFactor(0.8)
                        .padding(.horizontal, 12).padding(.vertical, 9).frame(maxWidth: fill ? .infinity : nil)
                        .background(Capsule().fill(on ? C.card : Color.clear).shadow(color: .black.opacity(on ? 0.12 : 0), radius: 2, y: 1))
                }.buttonStyle(.plain).accessibilityAddTraits(on ? .isSelected : [])
            }
        }.padding(4).background(Capsule().fill(C.dark ? Color(hex: 0x22303A) : Color(hex: 0xE8EDF1)))
    }
}

/// The deep blue profile card: who you are, this month, days in a row, Lock the app and Monthly report.
struct MeHero: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var ph = TeamPhotos.shared
    let act: [JSON]?
    @State private var photo = false
    var body: some View {
        let mine = act?.filter { $0.str("by").trimmingCharacters(in: .whitespaces).lowercased() == m.prefs.name.trimmingCharacters(in: .whitespaces).lowercased() && WORKS.contains($0.str("a")) }
        let cal = Calendar.current, today = cal.startOfDay(for: Date()), start = cal.date(from: cal.dateComponents([.year, .month], from: today))!
        let month = mine?.filter { (dayOf($0.str("at")) ?? .distantPast) >= start }
        let streak: Int? = mine.map { l in
            let days = Set(l.compactMap { dayOf($0.str("at")) })
            var d = days.contains(today) ? today : cal.date(byAdding: .day, value: -1, to: today)!; var n = 0
            while days.contains(d) { n += 1; d = cal.date(byAdding: .day, value: -1, to: d)! }
            return n
        }
        let ctry = countryName(m.prefs.country)
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 14) {
                // a tap on my picture: add or change my profile photo
                Button { Haptic.tap(); photo = true } label: {
                    PhotoOr(name: m.prefs.name, size: 56) {
                        Text(initialsOf(m.prefs.name)).font(.mont(19, .heavy)).foregroundColor(.white).frame(width: 56, height: 56)
                            .background(Circle().fill(Color.white.opacity(0.16)))
                    }
                    .overlay(Circle().stroke(Color.white.opacity(0.35), lineWidth: 1.5))
                    .overlay(alignment: .bottomTrailing) {
                        SFIcon("camera.fill", size: 14, color: Color(hex: 0x054F81)).frame(width: 24, height: 24).background(Circle().fill(Color.white)).offset(x: 4, y: 4)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(ph.url(m.prefs.name) != nil ? t("Change your photo", "Badilisha picha yako") : t("Add your photo", "Weka picha yako"))
                .accessibilityIdentifier("me_photo")
                VStack(alignment: .leading, spacing: 3) {
                    Text(m.prefs.name).font(.mont(19, .heavy)).foregroundColor(.white).lineLimit(1).minimumScaleFactor(0.8)
                    Text((m.snap?.admin == true ? t("Admin", "Msimamizi") : t("Field team", "Timu ya uwanjani")) + (ctry.map { " · " + $0 } ?? "")).font(.mont(13, .semibold)).foregroundColor(Color(hex: 0xCFE8FA))
                }
                Spacer(minLength: 0)
            }
            Text(t("THIS MONTH", "MWEZI HUU")).font(.mont(11, .bold)).tracking(1.4).foregroundColor(Color(hex: 0x9FD3F7)).padding(.top, 18).padding(.bottom, 8)
            HStack(spacing: 8) {
                ForEach([("pin", t("Pins", "Mahali")), ("visit", t("Visits", "Ziara")), ("fixed", t("Fixed", "Zimerekebishwa"))], id: \.0) { k, l in
                    VStack(spacing: 2) {
                        if let month { CountUp(value: month.filter { $0.str("a") == k || (k == "pin" && $0.str("a") == "new") }.count, font: .mont(26, .heavy), color: .white) }
                        else { Text("–").font(.mont(26, .heavy)).foregroundColor(.white) }
                        Text(l).font(.mont(12, .bold)).foregroundColor(Color(hex: 0xCFE8FA))
                    }.frame(maxWidth: .infinity).padding(.vertical, 12).background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white.opacity(0.12)))
                }
            }
            if let streak {
                HStack(spacing: 10) {
                    Icon("flame", size: 18, color: Color(hex: 0xFFB27A))
                    Text(streak == 0 ? t("Pin or visit today to start a streak.", "Weka mahali au tembelea leo kuanza mfululizo.") : streak == 1 ? t("1 day in a row. Keep it going!", "Siku 1 mfululizo. Endelea hivyo!") : t("\(streak) days in a row. Keep it going!", "Siku \(streak) mfululizo. Endelea hivyo!"))
                        .font(.mont(13, .bold)).foregroundColor(.white)
                    Spacer(minLength: 0)
                }.padding(.horizontal, 14).padding(.vertical, 10).background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.white.opacity(0.10))).padding(.top, 12)
            }
            HStack(spacing: 8) {
                HeroBtn(text: t("Lock the app", "Funga kwa PIN"), icon: "lock", solid: true) { m.lockOrSignOut() }
                HeroBtn(text: t("Monthly report", "Ripoti ya mwezi"), icon: "file", solid: false) { m.go(Place(tab: "me", sub: "report")) }
            }.padding(.top, 16)
        }
        .padding(20)
        .background(LinearGradient(colors: [Color(hex: 0x0A78C2), Color(hex: 0x054F81), Color(hex: 0x04263F)], startPoint: .topLeading, endPoint: .bottomTrailing))
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .shadow(color: Color(hex: 0x054F81).opacity(C.dark ? 0 : 0.28), radius: 12, y: 10)
        .padding(.top, 12)
        .rise(0)
        .sheet(isPresented: $photo) { MePhotoSheet(name: m.prefs.name, me: true) { said in MeToast.say(said) }.environmentObject(m) }
    }
}

private struct HeroBtn: View {
    let text: String; let icon: String; let solid: Bool; let action: () -> Void
    var body: some View {
        let fg = solid ? Color(hex: 0x04263F) : Color.white
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 8) { Icon(icon, size: 16, color: fg); Text(text).font(.mont(13, .bold)).foregroundColor(fg).lineLimit(1).minimumScaleFactor(0.85) }
                .frame(maxWidth: .infinity, minHeight: 46)
                .background(Capsule().fill(solid ? Color.white : Color.clear))
                .overlay(Capsule().stroke(solid ? Color.clear : Color.white.opacity(0.5), lineWidth: 1.5))
        }.buttonStyle(Press())
    }
}

/// An on/off switch drawn like the design: green when on.
struct Toggle2: View {
    let on: Bool; let label: String; let action: () -> Void
    var body: some View {
        Button(action: action) {
            ZStack(alignment: on ? .trailing : .leading) {
                Capsule().fill(on ? GreenC : Color(hex: 0xC9D1D8)).frame(width: 50, height: 30)
                Circle().fill(Color.white).frame(width: 24, height: 24).shadow(color: .black.opacity(0.2), radius: 1.5, y: 1).padding(3)
            }.animation(Motion.on ? .spring(response: 0.3, dampingFraction: 0.8) : nil, value: on)
        }.buttonStyle(.plain).accessibilityLabel(label).accessibilityValue(on ? "on" : "off")
    }
}
