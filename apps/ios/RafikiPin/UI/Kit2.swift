import SwiftUI
import UIKit

// Pieces shared by the newer screens (same as Android's Dates.kt, Mascot.kt, Motion.kt and Settings.kt helpers).

/// Dates on screen, the same everywhere and the same as the web app, in the app's language (not the phone's):
/// "Thu 24 Oct", "24 Oct", "24 Oct, 18:34"; the year only when it isn't this year; due dates "Today", "In 2 days", "3 days late".
enum Dates {
    private static let MON_EN = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
    private static let MON_SW = ["Jan", "Feb", "Mac", "Apr", "Mei", "Jun", "Jul", "Ago", "Sep", "Okt", "Nov", "Des"]
    private static let DAY_EN = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    private static let DAY_SW = ["Jtt", "Jnn", "Jtn", "Alh", "Iju", "Jmo", "Jpl"]
    static var cal: Calendar { Calendar.current }

    static func month(_ m: Int) -> String { (Lang.sw ? MON_SW : MON_EN)[max(0, min(11, m - 1))] }
    /// "24 Oct", or "24 Oct 2025" when it isn't this year.
    static func day(_ d: Date, year: Bool? = nil) -> String {
        let c = cal.dateComponents([.year, .month, .day], from: d)
        let thisYear = cal.component(.year, from: Date())
        return "\(c.day ?? 1) \(month(c.month ?? 1))" + ((year ?? (c.year != thisYear)) ? " \(c.year ?? thisYear)" : "")
    }
    /// "Thu 24 Oct" this year, "24 Oct 2025" another year.
    static func weekday(_ d: Date) -> String {
        if cal.component(.year, from: d) != cal.component(.year, from: Date()) { return day(d) }
        let wd = (cal.component(.weekday, from: d) + 5) % 7 // Monday = 0
        return (Lang.sw ? DAY_SW : DAY_EN)[wd] + " " + day(d)
    }
    static func time(_ d: Date) -> String { let c = cal.dateComponents([.hour, .minute], from: d); return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0) }
    static func dateTime(_ d: Date) -> String { day(d) + ", " + time(d) }
    /// Just the time today ("10:42"), otherwise the date and time.
    static func timeOrDateTime(_ d: Date) -> String { cal.isDateInToday(d) ? time(d) : dateTime(d) }
    /// "Today", "Tomorrow", "In 2 days" (up to a week ahead, then "Thu 24 Oct"), "1 day late", "3 days late".
    static func due(_ d: Date) -> String {
        let n = cal.dateComponents([.day], from: cal.startOfDay(for: Date()), to: cal.startOfDay(for: d)).day ?? 0
        switch n {
        case 0: return Lang.sw ? "Leo" : "Today"
        case 1: return Lang.sw ? "Kesho" : "Tomorrow"
        case 2...6: return Lang.sw ? "Baada ya siku \(n)" : "In \(n) days"
        case 7...: return weekday(d)
        default: return Lang.sw ? "Imechelewa siku \(-n)" : "\(-n) \(n == -1 ? "day" : "days") late"
        }
    }
    /// A date ("2026-10-20") or a moment ("2026-10-20T08:00:00Z").
    static func parse(_ iso: String) -> Date? {
        let s = iso.trimmingCharacters(in: .whitespaces)
        if s.count == 10 { return DateField.parse(s) }
        return When.parse(s)
    }
    static func day(_ iso: String, year: Bool? = nil) -> String { parse(iso).map { day($0, year: year) } ?? iso }
    static func weekday(_ iso: String) -> String { parse(iso).map(weekday) ?? iso }
    static func dateTime(_ iso: String) -> String { iso.count == 10 ? day(iso) : (parse(iso).map(dateTime) ?? iso) }
    static func due(_ iso: String) -> String { parse(iso).map(due) ?? iso }
}

/// "just now", "5 min ago", "2 h ago", "yesterday", "3 days ago", then the date.
func agoText(_ iso: String, now: Date = Date()) -> String {
    guard let at = When.parse(iso) else { return "" }
    let m = max(0, Int(now.timeIntervalSince(at) / 60)), h = m / 60, d = h / 24
    if m < 1 { return t("just now", "sasa hivi") }
    if m < 60 { return t("\(m) min ago", "dakika \(m) zilizopita") }
    if h < 24 { return t("\(h) h ago", "saa \(h) zilizopita") }
    if d < 7 { return d == 1 ? t("yesterday", "jana") : t("\(d) days ago", "siku \(d) zilizopita") }
    return Dates.day(at)
}

private let NAME_HUES = ["blue", "green", "purple", "amber", "teal", "coral"]
/// A colour for a person, worked out from their name (the same as the web app).
func nameHue(_ name: String) -> String {
    var h: UInt64 = 0
    for c in name.trimmingCharacters(in: .whitespaces).lowercased().unicodeScalars { h = (h &* 31 &+ UInt64(c.value)) & 0xFFFFFFFF }
    return NAME_HUES[Int(h % UInt64(NAME_HUES.count))]
}
func initialsOf(_ name: String) -> String {
    let s = name.split(whereSeparator: { $0 == " " || $0 == "\t" }).compactMap { $0.first.map { String($0).uppercased() } }.prefix(2).joined()
    return s.isEmpty ? "·" : s
}

extension Palette {
    /// A selected choice (pill, tab).
    var selected: Color { self.ink == .black ? Color(hex: 0x0050A0) : dark ? Color(hex: 0x075E9C) : blue }
    /// Bright sun colours.
    var sun: Bool { ink == .black }
    /// A hue by its colour name (blue, green, purple, amber, teal, coral, slate) or by an action key.
    func named(_ k: String) -> Hue {
        switch k { case "blue": return hue("pin"); case "green": return hue("visit"); case "purple": return hue("new"); case "amber": return hue("repairs")
        case "teal": return hue("due"); case "coral": return hue("me"); case "slate": return hue("x"); default: return hue(k) }
    }
}

/// Pops in a little bigger, then settles (counts, badges).
private struct Pop: ViewModifier {
    @State private var shown = false
    func body(content: Content) -> some View {
        content.scaleEffect(shown ? 1 : 0.4).opacity(shown ? 1 : 0)
            .onAppear { if Motion.on { withAnimation(.spring(response: 0.35, dampingFraction: 0.55)) { shown = true } } else { shown = true } }
    }
}
extension View {
    func pop() -> some View { modifier(Pop()) }
    /// Fills the width, left-aligned.
    func wide(_ a: Alignment = .leading) -> some View { frame(maxWidth: .infinity, alignment: a) }
}

/// RAFIKI Pin wordmark for the header.
struct Wordmark: View {
    var size: CGFloat = 16
    var body: some View {
        HStack(spacing: 0) { Text("RAFIKI").font(.mont(size, .bold)).tracking(2.5).foregroundColor(C.ink); Text(" Pin").font(.mont(size, .bold)).foregroundColor(C.blue) }
    }
}

/// The RAFIKI drop: a glassy water drop with eyes, in a few colours and moods.
enum DropC {
    case blue, green, coral, purple, amber, grey
    var stops: (Color, Color, Color) {
        switch self {
        case .blue: return (Color(hex: 0xBFE8FF), Color(hex: 0x3EA7EE), Color(hex: 0x0757A0))
        case .green: return (Color(hex: 0xC8F5DA), Color(hex: 0x2FB872), Color(hex: 0x0B6B3D))
        case .coral: return (Color(hex: 0xFFD6C9), Color(hex: 0xF26A4F), Color(hex: 0xA3261A))
        case .purple: return (Color(hex: 0xE6DCFF), Color(hex: 0x8C6CF2), Color(hex: 0x4527B0))
        case .amber: return (Color(hex: 0xFFEDB8), Color(hex: 0xF2B13A), Color(hex: 0xA86200))
        case .grey: return (Color(hex: 0xF1F3F5), Color(hex: 0xC9CFD4), Color(hex: 0x8C969E))
        }
    }
}
enum Mood { case plain, smile, wink, o }

/// The drop outline in a 24×27 box.
struct DropShape: Shape {
    func path(in r: CGRect) -> Path {
        let k = r.width / 24
        var p = Path()
        p.move(to: CGPoint(x: 12 * k, y: 2.5 * k))
        p.addCurve(to: CGPoint(x: 19 * k, y: 15.1 * k), control1: CGPoint(x: 12 * k, y: 2.5 * k), control2: CGPoint(x: 19 * k, y: 9.9 * k))
        p.addArc(center: CGPoint(x: 12 * k, y: 15.1 * k), radius: 7 * k, startAngle: .degrees(0), endAngle: .degrees(180), clockwise: false)
        p.addCurve(to: CGPoint(x: 12 * k, y: 2.5 * k), control1: CGPoint(x: 5 * k, y: 9.9 * k), control2: CGPoint(x: 12 * k, y: 2.5 * k))
        p.closeSubpath()
        return p.offsetBy(dx: r.minX, dy: r.minY)
    }
}

struct Drop: View {
    var size: CGFloat = 64; var color: DropC = .blue; var mood: Mood = .plain; var still = false
    @State private var up = false
    @State private var blink = false
    var body: some View {
        let (hi, mid, lo) = color.stops
        let k = size / 24
        let moving = Motion.on && !still
        ZStack(alignment: .topLeading) {
            Ellipse().fill(Color(hex: 0x0B3A5E).opacity(up ? 0.14 : 0.22)).frame(width: 10.4 * k * (up ? 0.82 : 1), height: 2 * k)
                .position(x: 12 * k, y: 25.4 * k)
            ZStack(alignment: .topLeading) {
                DropShape().fill(RadialGradient(colors: [hi, mid, lo], center: UnitPoint(x: 9.1 / 24, y: 8.6 / 27), startRadius: 0, endRadius: 15 * k))
                DropShape().stroke(Color.white.opacity(0.55), lineWidth: 0.5 * k)
                Path { p in p.move(to: CGPoint(x: 8.2 * k, y: 11.4 * k)); p.addCurve(to: CGPoint(x: 10.9 * k, y: 6.6 * k), control1: CGPoint(x: 8.7 * k, y: 9.7 * k), control2: CGPoint(x: 9.8 * k, y: 8 * k)) }
                    .stroke(Color.white.opacity(0.9), style: StrokeStyle(lineWidth: 1.3 * k, lineCap: .round))
                eyes(k)
                mouth(k)
            }
            .frame(width: size, height: size * 27 / 24)
            .offset(y: up ? -2.2 * k : 0)
        }
        .frame(width: size, height: size * 27 / 24)
        .onAppear {
            guard moving else { return }
            withAnimation(.easeInOut(duration: 1.8).repeatForever(autoreverses: true)) { up = true }
        }
        .task {
            guard moving else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 4_800_000_000)
                withAnimation(.easeInOut(duration: 0.1)) { blink = true }
                try? await Task.sleep(nanoseconds: 120_000_000)
                withAnimation(.easeInOut(duration: 0.1)) { blink = false }
            }
        }
    }
    private func eyes(_ k: CGFloat) -> some View {
        let ink = Color(hex: 0x04263F)
        let h: CGFloat = blink ? 0.3 : 3
        return ZStack(alignment: .topLeading) {
            if mood == .wink {
                Path { p in p.move(to: CGPoint(x: 9 * k, y: 15.4 * k)); p.addQuadCurve(to: CGPoint(x: 11 * k, y: 15.4 * k), control: CGPoint(x: 10 * k, y: 14.6 * k)) }
                    .stroke(ink, style: StrokeStyle(lineWidth: 0.7 * k, lineCap: .round))
            } else {
                Ellipse().fill(.white).frame(width: 2.3 * k, height: h * k).position(x: 10 * k, y: 15.2 * k)
                Circle().fill(ink).frame(width: 1.24 * k, height: 1.24 * k * (blink ? 0.3 : 1)).position(x: 10.25 * k, y: 15.5 * k)
            }
            Ellipse().fill(.white).frame(width: 2.3 * k, height: h * k).position(x: 14 * k, y: 15.2 * k)
            Circle().fill(ink).frame(width: 1.24 * k, height: 1.24 * k * (blink ? 0.3 : 1)).position(x: 14.25 * k, y: 15.5 * k)
        }
    }
    @ViewBuilder private func mouth(_ k: CGFloat) -> some View {
        let ink = Color(hex: 0x04263F)
        switch mood {
        case .smile, .wink:
            Path { p in p.move(to: CGPoint(x: 10.4 * k, y: 18.1 * k)); p.addQuadCurve(to: CGPoint(x: 13.6 * k, y: 18.1 * k), control: CGPoint(x: 12 * k, y: 19.3 * k)) }
                .stroke(ink, style: StrokeStyle(lineWidth: 0.7 * k, lineCap: .round))
        case .o: Ellipse().fill(ink).frame(width: 1.4 * k, height: 1.7 * k).position(x: 12 * k, y: 18.4 * k)
        case .plain: EmptyView()
        }
    }
}

/// The drop with a short line under it, for screens with nothing to show yet.
struct EmptyDrop: View {
    let text: String
    var body: some View {
        VStack(spacing: 8) { Drop(size: 64, mood: .smile); Text(text).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center) }
            .frame(maxWidth: .infinity).padding(.top, 20).padding(.bottom, 6)
    }
}

/// A round filter pill with an optional red count (Notifications, Office lists).
struct FilterPill: View {
    let label: String; let on: Bool; var count = 0; let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 7) {
                Text(label).font(.mont(14, .bold)).foregroundColor(on ? .white : C.ink).lineLimit(1)
                if count > 0 {
                    Text(count > 9 ? "9+" : "\(count)").font(.mont(11, .bold)).foregroundColor(C.dark ? Color(hex: 0x2A0B08) : .white)
                        .padding(.horizontal, 5).frame(minWidth: 20, minHeight: 20).background(C.bad).clipShape(Capsule()).id(count).pop()
                }
            }
            .padding(.horizontal, 15).frame(minHeight: 40)
            .background(on ? C.selected : C.card).clipShape(Capsule())
            .overlay(Capsule().stroke(on ? Color.clear : C.line, lineWidth: C.sun ? 2 : 1))
            .animation(.easeOut(duration: 0.2), value: on)
        }.buttonStyle(Press()).accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// UPPERCASE small label over a group of rows.
struct GroupLabel: View {
    let text: String; var top: CGFloat = 16
    init(_ text: String, top: CGFloat = 16) { self.text = text; self.top = top }
    var body: some View { Text(text.uppercased()).font(.mont(11, .heavy)).tracking(1.4).foregroundColor(C.muted).wide().padding(.leading, 2).padding(.top, top).padding(.bottom, 2) }
}

/// A Settings row: coloured icon, title, line under it, chevron.
struct SetRow: View {
    let icon: String; let hue: String; let title: String; var sub = ""; let action: () -> Void
    var body: some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 12) {
                IconTile(icon: icon, tint: C.named(hue).fg, bg: C.named(hue).bg, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading)
                    if !sub.isEmpty { Text(sub).font(.bSmall).foregroundColor(C.muted).lineLimit(1) }
                }
                Spacer(minLength: 6)
                Icon("chev", size: 18, color: C.muted)
            }.padding(.vertical, 10).padding(.horizontal, 2).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

/// A choice on a Settings page: a name, a line under it, and a tick when it's the one in use.
struct SetChoice<Lead: View>: View {
    let title: String; var sub = ""; let on: Bool; let action: () -> Void
    @ViewBuilder var lead: () -> Lead
    var body: some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 12) {
                lead()
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.tSmall).foregroundColor(C.ink)
                    if !sub.isEmpty { Text(sub).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading) }
                }
                Spacer(minLength: 6)
                ZStack {
                    Circle().fill(on ? C.blue : Color.clear).frame(width: 24, height: 24)
                    if on { Icon("check", size: 15, color: .white) } else { Circle().stroke(C.edge, lineWidth: 1.5).frame(width: 24, height: 24) }
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .background(on ? C.tint : Color.clear)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(on ? C.blue : C.line, lineWidth: on ? 2 : 1))
            .contentShape(Rectangle())
        }.buttonStyle(.plain).padding(.top, 8).accessibilityAddTraits(on ? .isSelected : [])
    }
}
extension SetChoice where Lead == EmptyView {
    init(title: String, sub: String = "", on: Bool, action: @escaping () -> Void) { self.title = title; self.sub = sub; self.on = on; self.action = action; self.lead = { EmptyView() } }
}

/// "…" in a round button.
struct MoreDots: View {
    let label: String; let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 3.5) { ForEach(0..<3) { _ in Circle().fill(C.ink).frame(width: 4.5, height: 4.5) } }
                .frame(width: 44, height: 44).background(C.card).clipShape(Circle()).overlay(Circle().stroke(C.line, lineWidth: C.sun ? 2 : 1))
        }.buttonStyle(.plain).accessibilityLabel(label)
    }
}

/// A short message at the bottom of the screen, for a moment.
struct ToastView: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        VStack {
            Spacer()
            if let s = m.toast {
                Text(s).font(.mont(14, .semibold)).foregroundColor(.white).multilineTextAlignment(.center)
                    .padding(.horizontal, 18).padding(.vertical, 12).background(Capsule().fill(Color.black.opacity(0.82)))
                    .padding(.bottom, 96).padding(.horizontal, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .id(s)
            }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.85), value: m.toast)
        .allowsHitTesting(false)
    }
}

/// Opens an e-mail to the office, or a page on the website.
enum Links {
    static func mail(_ subject: String, _ body: String = "", to: String = "info@rafikioutreach.org") {
        var allowed = CharacterSet.urlQueryAllowed; allowed.remove(charactersIn: "&=+?")
        let q = "subject=" + (subject.addingPercentEncoding(withAllowedCharacters: allowed) ?? "") + (body.isEmpty ? "" : "&body=" + (body.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""))
        Share.open("mailto:\(to)?\(q)")
    }
    static func web(_ path: String) { Share.open("https://www.rafikioutreach.org" + path) }
    static func call(_ phone: String) { Share.open("tel:" + phone.filter { $0.isNumber || $0 == "+" }) }
    static func whatsapp(_ phone: String, _ home: String, text: String = "") {
        var allowed = CharacterSet.urlQueryAllowed; allowed.remove(charactersIn: "&=+?")
        Share.open("https://wa.me/" + waNumber(phone, home) + (text.isEmpty ? "" : "?text=" + (text.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")))
    }
}

/// The app's version, e.g. "2.21.0 (46)".
enum AppInfo {
    static var version: String { (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "" }
    static var build: String { (Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String) ?? "" }
}
