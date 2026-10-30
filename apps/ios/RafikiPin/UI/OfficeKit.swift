import SwiftUI
import UIKit

// The calm look of the Office (Android 2.17 – 2.26, OfficeOverview.kt and DqmKit.kt): a head with an icon, one line and a
// few numbers; filter chips with counts; tappable cards that open a sheet holding every action; settings folded into
// rows that open with a tap. Also used by the map, projects and donor screens.

/// More line icons, as iPhone system symbols (everything else comes from Ic).
enum OIc {
    static let extra: [String: String] = [
        "coins": "dollarsign.circle", "box": "shippingbox", "qr": "qrcode", "scan": "qrcode.viewfinder", "mail": "envelope", "route": "point.topleft.down.curvedto.point.bottomright.up",
        "car": "car", "build": "hammer", "brick": "building.2", "school": "graduationcap", "cross": "cross.case", "house": "house", "leaf": "leaf", "info": "info.circle",
        "more": "ellipsis", "filter": "line.3.horizontal.decrease", "cog": "gearshape", "crown": "crown", "key": "key", "cloud": "icloud", "speak": "speaker.wave.2",
        "play": "play.fill", "pause": "pause.fill", "fit": "arrow.up.left.and.arrow.down.right", "hat": "graduationcap", "bell": "bell", "copy": "doc.on.doc",
        "hourglass": "hourglass", "trend": "chart.line.uptrend.xyaxis", "heart": "heart", "flask": "flask", "photos": "photo.on.rectangle", "left": "chevron.left",
        "fuel": "fuelpump", "flag": "flag", "eye": "eye", "gift": "gift", "sparkles": "sparkles", "download": "arrow.down.circle", "stack": "square.stack.3d.up",
        "sms": "message", "whatsapp": "phone.bubble", "pdf": "doc.richtext", "print": "printer", "light": "flashlight.on.fill", "minus": "minus", "ruler": "ruler",
        "hand": "hand.tap", "user": "person.crop.circle", "wifi": "wifi",
    ]
    static func name(_ k: String) -> String { extra[k] ?? Ic.name(k) }
}

struct OIcon: View {
    let k: String; var size: CGFloat = 22; var color: Color = C.ink
    init(_ k: String, size: CGFloat = 22, color: Color = C.ink) { self.k = k; self.size = size; self.color = color }
    var body: some View { Image(systemName: OIc.name(k)).font(.system(size: size * 0.82, weight: .medium)).foregroundColor(color).frame(width: size, height: size) }
}

private let OHUE_LIGHT: [String: Hue] = [
    "blue": Hue(fg: Color(hex: 0x0A78C2), bg: Color(hex: 0xE3F1FC)), "green": Hue(fg: Color(hex: 0x1C8A50), bg: Color(hex: 0xE2F4EA)),
    "purple": Hue(fg: Color(hex: 0x6E46C8), bg: Color(hex: 0xEEE7FB)), "amber": Hue(fg: Color(hex: 0xB86E00), bg: Color(hex: 0xFDF0DC)),
    "teal": Hue(fg: Color(hex: 0x0E7F86), bg: Color(hex: 0xDCF2F3)), "coral": Hue(fg: Color(hex: 0xD2553A), bg: Color(hex: 0xFCE9E4)),
    "slate": Hue(fg: Color(hex: 0x36505F), bg: Color(hex: 0xE8EDF1)),
]
private let OHUE_DARK: [String: Hue] = [
    "blue": Hue(fg: Color(hex: 0x5BB4F2), bg: Color(hex: 0x12324A)), "green": Hue(fg: Color(hex: 0x5FD08F), bg: Color(hex: 0x123222)),
    "purple": Hue(fg: Color(hex: 0xB49BFF), bg: Color(hex: 0x2A2145)), "amber": Hue(fg: Color(hex: 0xF2B35B), bg: Color(hex: 0x3A2A10)),
    "teal": Hue(fg: Color(hex: 0x5CD3D6), bg: Color(hex: 0x0F3133)), "coral": Hue(fg: Color(hex: 0xFF9A82), bg: Color(hex: 0x3D1F18)),
    "slate": Hue(fg: Color(hex: 0xA9BAC6), bg: Color(hex: 0x22303A)),
]
/// The colour for a key, as Android's C.hue: action keys ("pin", "visit", "due"…) or a colour name ("blue", "teal"…).
func ohue(_ key: String) -> Hue {
    let name: String
    switch key {
    case "pin", "missing", "list": name = "blue"
    case "visit", "near", "map", "offline": name = "green"
    case "new": name = "purple"
    case "repairs", "office": name = "amber"
    case "due", "scan", "qr": name = "teal"
    case "me": name = "coral"
    default: name = OHUE_LIGHT[key] != nil ? key : "slate"
    }
    let table = C.dark ? OHUE_DARK : OHUE_LIGHT
    return table[name] ?? Hue(fg: C.ink, bg: C.bg)
}

/// Bright sun: black text and strong edges.
var oSun: Bool { Look.p.ink == .black }
var oEdge: CGFloat { oSun ? 2 : 1 }

/// (text colour, background) for a tone: "bad", "warn", "ok", "has" or "off".
func otone(_ tone: String) -> (Color, Color) {
    switch tone { case "bad": return (C.bad, C.badBg); case "warn": return (C.warn, C.warnBg); case "ok": return (C.ok, C.okBg); case "has": return (C.blue2, C.tint); default: return (C.muted, C.bg) }
}

struct OTileIcon: View {
    let icon: String; let tint: Color; let bg: Color; var size: CGFloat = 42
    var body: some View {
        OIcon(icon, size: size * 0.55, color: tint).frame(width: size, height: size).background(bg)
            .clipShape(RoundedRectangle(cornerRadius: size > 50 ? 20 : 14, style: .continuous))
    }
}

/// The head of a tab: icon, title, one short line; then whatever the tab adds (numbers, a button).
struct OHead<Extra: View>: View {
    let icon: String; let hue: String; let title: String; let line: String
    @ViewBuilder var extra: () -> Extra
    var body: some View {
        let hu = ohue(hue)
        CardV(pad: 16) {
            HStack(spacing: 12) {
                OTileIcon(icon: icon, tint: hu.fg, bg: hu.bg, size: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.mont(17, .heavy)).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                    Text(line).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
            extra()
        }
    }
}
extension OHead where Extra == EmptyView {
    init(icon: String, hue: String, title: String, line: String) { self.init(icon: icon, hue: hue, title: title, line: line, extra: { EmptyView() }) }
}

/// A small number in a head. Tappable ones set the filter.
struct ONum: View {
    let value: String; let label: String; var color: Color = C.ink; var on = false; var prefix = ""; var sub = ""; var action: (() -> Void)? = nil
    var body: some View {
        let inner = VStack(alignment: .leading, spacing: 1) {
            HStack(alignment: .lastTextBaseline, spacing: 4) {
                if !prefix.isEmpty { Text(prefix).font(.mont(10, .heavy)).foregroundColor(C.muted) }
                Text(value).font(.mont(prefix.isEmpty ? 22 : 19, .heavy)).foregroundColor(color).lineLimit(1).minimumScaleFactor(0.6)
            }
            FitWords(label, size: 12, weight: .medium, color: C.muted, lines: 3)
            if !sub.isEmpty { Text(sub).font(.bSmall).foregroundColor(C.muted).lineLimit(2) }
        }
        .padding(.horizontal, 10).padding(.vertical, 8)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).frame(minHeight: 56)
        .background(on ? C.tint : C.bg)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(on ? C.blue : C.line, lineWidth: on ? 1.5 : oEdge))
        if let action { Button { Haptic.tap(); action() } label: { inner }.buttonStyle(Press()) } else { inner }
    }
}

/// Numbers side by side, all as tall as the tallest.
struct ONums: View {
    let items: [ONum]
    var body: some View {
        HStack(alignment: .top, spacing: 8) { ForEach(items.indices, id: \.self) { i in items[i] } }
            .fixedSize(horizontal: false, vertical: true).padding(.top, 12)
    }
}

/// A status: a dot and a short word, in its tone.
struct OPill: View {
    let label: String; let tone: String
    init(_ label: String, _ tone: String) { self.label = label; self.tone = tone }
    var body: some View {
        let (fg, bg) = otone(tone)
        HStack(spacing: 6) {
            Circle().fill(fg).frame(width: 7, height: 7)
            Text(label).font(.mont(12, .heavy)).foregroundColor(fg).fixedSize(horizontal: false, vertical: true)
        }
        .padding(.leading, 8).padding(.trailing, 10).padding(.vertical, 3).background(bg)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(oSun ? fg : .clear, lineWidth: 1.5))
    }
}

/// One filter chip ("Label · 3"); blue when chosen.
struct OChip: View {
    let label: String; var icon: String? = nil; let on: Bool; var hue = "blue"; var badge = 0; let action: () -> Void
    var body: some View {
        let hu = ohue(hue)
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 6) {
                if let icon { OIcon(icon, size: 17, color: on ? .white : hu.fg) }
                Text(label).font(.mont(14, .bold)).foregroundColor(on ? .white : C.ink).lineLimit(1).fixedSize()
                if badge > 0 { Text("\(badge)").font(.mont(11, .bold)).foregroundColor(.white).padding(.horizontal, 6).padding(.vertical, 1).background(RedC).clipShape(Capsule()) }
            }
            .padding(.horizontal, 14).frame(minHeight: 40)
            .background(on ? hu.fg : C.card).clipShape(Capsule())
            .overlay(Capsule().stroke(on ? .clear : C.line, lineWidth: oEdge))
            .frame(minHeight: 44)
        }.buttonStyle(Press())
    }
}

/// Filter chips with counts, in a row that scrolls sideways; the chosen one is brought into view.
struct OChips: View {
    let items: [(String, String, Int)]; let picked: String; var hue = "blue"; let pick: (String) -> Void
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(items, id: \.0) { k, label, n in OChip(label: "\(label) · \(n)", on: picked == k, hue: hue) { pick(k) }.id(k) }
                }.padding(.vertical, 2)
            }
            .onChange(of: picked) { k in withAnimation(Motion.on ? .easeInOut(duration: 0.3) : nil) { proxy.scrollTo(k, anchor: .center) } }
        }.padding(.top, 12)
    }
}

/// A list card: something on the left, a title with the time (or amount) on the right, a status, up to two lines.
struct OListCard<Lead: View>: View {
    let title: String; var right = ""; var rightStrong = false; var pill: (String, String)? = nil; var side = ""; var lines: [String] = []; var italicLast = false; var index = 0
    @ViewBuilder let lead: () -> Lead
    let action: () -> Void
    var body: some View {
        let ls = lines.filter { !$0.isEmpty }
        Button { Haptic.tap(); action() } label: {
            HStack(alignment: .top, spacing: 12) {
                lead()
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .top, spacing: 6) {
                        Text(title).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading).frame(maxWidth: .infinity, alignment: .leading)
                        if !right.isEmpty { Text(right).font(rightStrong ? .tSmall : .mont(12, .semibold)).foregroundColor(rightStrong ? C.ink : C.muted).lineLimit(1).fixedSize() }
                        Icon("chev", size: 16, color: C.muted)
                    }
                    if pill != nil || !side.isEmpty {
                        HStack(spacing: 8) {
                            if let pill { OPill(pill.0, pill.1) }
                            Spacer(minLength: 0)
                            if !side.isEmpty { Text(side).font(.bSmall).foregroundColor(C.muted).lineLimit(1) }
                        }.padding(.top, 5)
                    }
                    ForEach(Array(ls.enumerated()), id: \.offset) { k, l in
                        Text(l).font(.bSmall).italic(italicLast && k == ls.count - 1 && k > 0).foregroundColor(C.muted).lineLimit(1).padding(.top, k == 0 ? 5 : 1)
                    }
                }
            }
            .padding(14).frame(maxWidth: .infinity, alignment: .leading).background(C.card)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.line, lineWidth: oEdge))
        }.buttonStyle(Press()).padding(.top, 8).rise(min(index, 6), step: 0.035)
    }
}

/// A calm card: something on the left, one title, a status, at most two lines, a time and a chevron.
struct OCalmCard<Lead: View>: View {
    let title: String; var pill: (String, String)? = nil; var lines: [String] = []; var time = ""; var dim = false
    @ViewBuilder let lead: () -> Lead
    let action: () -> Void
    var body: some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 12) {
                lead()
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading)
                    if let pill { OPill(pill.0, pill.1).padding(.top, 1) }
                    ForEach(Array(lines.filter { !$0.isEmpty }.prefix(2).enumerated()), id: \.offset) { _, l in Text(l).font(.bSmall).foregroundColor(C.muted).lineLimit(2).multilineTextAlignment(.leading) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                if !time.isEmpty { Text(time).font(.bSmall).foregroundColor(C.muted).lineLimit(1).fixedSize() }
                Icon("chev", size: 18, color: C.muted)
            }
            .padding(.horizontal, 14).padding(.vertical, 12).background(C.card)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.line, lineWidth: oEdge))
            .opacity(dim ? 0.72 : 1)
        }.buttonStyle(Press())
    }
}

/// A row that opens and closes with a tap; the summary under the title says what's inside.
struct OFold<Content: View>: View {
    let icon: String; let hue: String; let title: String; let summary: String; var tone = ""
    @State var open = false
    @ViewBuilder let content: () -> Content
    var body: some View {
        let hu = ohue(hue)
        VStack(alignment: .leading, spacing: 0) {
            Button { Haptic.tap(); withAnimation(Motion.on ? .easeInOut(duration: 0.22) : nil) { open.toggle() } } label: {
                HStack(spacing: 12) {
                    OTileIcon(icon: icon, tint: hu.fg, bg: hu.bg, size: 38)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading)
                        HStack(alignment: .top, spacing: 6) {
                            if !tone.isEmpty { Circle().fill(tone == "off" ? C.muted : otone(tone).0).frame(width: 7, height: 7).padding(.top, 5) }
                            Text(summary).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    Icon("chev", size: 18, color: C.muted).rotationEffect(.degrees(open ? 90 : 0))
                }.padding(.horizontal, 14).padding(.vertical, 8).frame(minHeight: 60).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if open {
                VStack(alignment: .leading, spacing: 0) { content() }
                    .padding(.horizontal, 14).padding(.bottom, 14)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }
}

/// A sheet's content: scrolls, with room at the sides.
struct OSheet<Content: View>: View {
    @ViewBuilder let content: () -> Content
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 0) { content() }.padding(.horizontal, 20).padding(.top, 22).padding(.bottom, 28) }
            .storySheet()
    }
}

/// The round close X at the top of a sheet.
struct OCloseX: View {
    let action: () -> Void
    var body: some View {
        Button { Haptic.tap(); action() } label: { Icon("x", size: 20, color: C.ink).frame(width: 44, height: 44).background(C.bg).clipShape(Circle()) }
            .buttonStyle(.plain).accessibilityLabel(t("Close", "Funga"))
    }
}

/// The top of a sheet: a picture, a small label, the title and a close X.
struct OSheetHead<Lead: View>: View {
    var label = ""; var labelColor: Color = C.muted; let title: String
    @ViewBuilder let lead: () -> Lead
    let close: () -> Void
    var body: some View {
        HStack(spacing: 14) {
            lead()
            VStack(alignment: .leading, spacing: 1) {
                if !label.isEmpty { Text(label).font(.mont(14, .heavy)).foregroundColor(labelColor).fixedSize(horizontal: false, vertical: true) }
                Text(title).font(.tLarge).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
            }.frame(maxWidth: .infinity, alignment: .leading)
            OCloseX(action: close)
        }
    }
}

/// The top of a sheet, centred: a picture, the title, a line or two, a status, and a close X.
struct OSheetHeadC<Lead: View>: View {
    let title: String; var sub: [String] = []; var pill: (String, String)? = nil
    @ViewBuilder let lead: () -> Lead
    let close: () -> Void
    var body: some View {
        ZStack(alignment: .topTrailing) {
            VStack(spacing: 2) {
                lead().rise(0)
                Text(title).font(.tLarge).foregroundColor(C.ink).multilineTextAlignment(.center).padding(.top, 8)
                ForEach(sub.filter { !$0.isEmpty }, id: \.self) { Text($0).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center) }
                if let pill { OPill(pill.0, pill.1).padding(.top, 8) }
            }.frame(maxWidth: .infinity).padding(.horizontal, 44)
            OCloseX(action: close)
        }
    }
}

/// A label and its value on one line (the value can have a small line under it).
struct OFact: View {
    let label: String; let value: String; var color: Color = C.ink; var sub = ""
    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top, spacing: 12) {
                Text(label).font(.bMedium).foregroundColor(C.muted).frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .trailing, spacing: 1) {
                    Text(value).font(.mont(14, .bold)).foregroundColor(color).multilineTextAlignment(.trailing)
                    if !sub.isEmpty { Text(sub).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.trailing) }
                }.frame(maxWidth: 210, alignment: .trailing)
            }.padding(.vertical, 10)
            Rectangle().fill(C.line).frame(height: 1)
        }
    }
}

/// A plain bordered button for the less common actions (red for the ones that remove things).
struct OLineBtn: View {
    let text: String; let icon: String; var red = false; var enabled = true; let action: () -> Void
    var body: some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 8) {
                OIcon(icon, size: 18, color: red ? C.bad : C.blue)
                Text(text).font(.lLarge).foregroundColor(red ? C.bad : C.ink).multilineTextAlignment(.center)
            }
            .padding(.horizontal, 12).padding(.vertical, 6).frame(maxWidth: .infinity, minHeight: 48)
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line, lineWidth: oEdge))
            .contentShape(Rectangle())
        }.buttonStyle(Press()).disabled(!enabled).opacity(enabled ? 1 : 0.5)
    }
}

/// Ask before something that can't be undone: the question, Cancel and the action.
struct OAsk: View {
    let question: String; let yes: String; var no = t("Cancel", "Ghairi"); var red = true; var busy = false; var msg: String? = nil
    let onNo: () -> Void; let onYes: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(question).font(.mont(14, .semibold)).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                Btn(no, light: true, small: true, wide: true, action: onNo)
                Button(action: onYes) {
                    Text(yes).font(.mont(14, .bold)).foregroundColor(.white).multilineTextAlignment(.center).padding(.horizontal, 12)
                        .frame(maxWidth: .infinity, minHeight: 44).background(red ? C.bad : C.blue).clipShape(Capsule())
                }.buttonStyle(Press()).disabled(busy).opacity(busy ? 0.6 : 1)
            }.padding(.top, 10)
            Msg(msg, "bad")
        }
        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
        .background(red ? C.badBg : ohue("slate").bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.top, 12).rise(0)
    }
}

/// A small uppercase label over a group.
struct OLabel: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text.uppercased(with: Locale(identifier: Lang.sw ? "sw" : "en_US"))).font(.mont(11, .heavy)).tracking(0.8).foregroundColor(C.muted)
            .padding(.top, 18).padding(.bottom, 6).accessibilityAddTraits(.isHeader)
    }
}

/// A bar that fills from empty.
struct OBar: View {
    let f: Double; var color: Color = C.blue; var height: CGFloat = 6
    @State private var shown = 0.0
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(C.line)
                if shown > 0.001 { Capsule().fill(color).frame(width: g.size.width * min(1, max(0, shown))) }
            }
        }
        .frame(height: height)
        .onAppear { if Motion.on { withAnimation(.easeOut(duration: 1.1)) { shown = f } } else { shown = f } }
        .onChange(of: f) { v in withAnimation(Motion.on ? .easeOut(duration: 0.5) : nil) { shown = v } }
    }
}

/// The share of something, as a ring that fills.
struct ORing: View {
    let pct: Int; var size: CGFloat = 48; var color: Color = C.blue
    @State private var shown = 0.0
    var body: some View {
        ZStack {
            Circle().stroke(C.line, lineWidth: 5)
            Circle().trim(from: 0, to: shown).stroke(color, style: StrokeStyle(lineWidth: 5, lineCap: .round)).rotationEffect(.degrees(-90))
            Text("\(pct)%").font(.mont(12, .heavy)).foregroundColor(C.ink).lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(width: size, height: size)
        .onAppear { let f = Double(pct) / 100; if Motion.on { withAnimation(.easeOut(duration: 1.3)) { shown = f } } else { shown = f } }
    }
}

/// Initials in a coloured circle (the colour follows the name).
struct OAvatar: View {
    let name: String; var size: CGFloat = 40
    static func hueName(_ name: String) -> String {
        var hh: UInt64 = 7
        for c in name.lowercased().unicodeScalars { hh = (hh &* 31 &+ UInt64(c.value)) % 4_294_967_296 }
        return ["blue", "green", "purple", "teal", "coral", "slate"][Int(hh % 6)]
    }
    var body: some View {
        let hu = ohue(OAvatar.hueName(name))
        let ini = name.split(whereSeparator: { $0 == " " }).prefix(2).compactMap { $0.first.map { String($0).uppercased() } }.joined()
        Text(ini.isEmpty ? "·" : ini).font(.mont(size * 0.34, .heavy)).foregroundColor(hu.fg)
            .frame(width: size, height: size).background(hu.bg).clipShape(Circle())
            .overlay(Circle().stroke(oSun ? Color.black : .clear, lineWidth: 2))
    }
}

/// A grey box shown while a number loads.
struct OSkel: View {
    var w: CGFloat = 52; var h: CGFloat = 26
    @State private var dim = false
    var body: some View {
        RoundedRectangle(cornerRadius: 8, style: .continuous).fill(C.line).frame(width: w, height: h).opacity(dim ? 0.45 : 0.9)
            .onAppear { if Motion.on { withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { dim = true } } }
    }
}

/// "Nothing here" with the RAFIKI drop.
struct OEmpty: View {
    let text: String
    init(_ text: String) { self.text = text }
    @State private var bob = false
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "drop.fill").font(.system(size: 34)).foregroundStyle(LinearGradient(colors: [Color(hex: 0x47B4F5), Color(hex: 0x0A78C2)], startPoint: .top, endPoint: .bottom))
                .offset(y: bob ? -3 : 3)
                .onAppear { if Motion.on { withAnimation(.easeInOut(duration: 1.6).repeatForever(autoreverses: true)) { bob = true } } }
            Text(text).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
        }.frame(maxWidth: .infinity).padding(.vertical, 24).padding(.horizontal, 12)
    }
}

/// "Nothing … right now." with a green check.
struct ONothing: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        HStack(spacing: 10) { Icon("check", size: 22, color: C.ok); Text(text).font(.mont(14, .bold)).foregroundColor(C.ok).fixedSize(horizontal: false, vertical: true) }
            .padding(.top, 10).padding(.bottom, 2)
    }
}

/// "Show 12 more".
struct OMore: View {
    let n: Int; let action: () -> Void
    var body: some View { Btn(t("Show \(n) more", "Onyesha \(n) zaidi"), light: true, small: true, wide: true, action: action).padding(.top, 10) }
}

/// A row in a set-up or to-do list: an icon in its colour, a title and a line, a chevron.
struct ORow: View {
    let icon: String; let fg: Color; let bg: Color; let title: String; let line: String; var dim = false; let action: () -> Void
    var body: some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 12) {
                OIcon(icon, size: 20, color: fg).frame(width: 36, height: 36).background(bg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.mont(14, .bold)).foregroundColor(dim ? C.muted : C.ink).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                    Text(line).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                }.frame(maxWidth: .infinity, alignment: .leading)
                Icon("chev", size: 18, color: C.muted)
            }.padding(.vertical, 8).padding(.horizontal, 2).frame(minHeight: 58).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

/// A thin line between rows.
struct ODivider: View { var body: some View { Rectangle().fill(C.line).frame(height: 1) } }

/// A Binding to an optional JSON item, for sheets (JSON isn't Identifiable).
struct OItem: Identifiable { let id: String; let o: JSON }

/// Dates as the Office shows them.
enum ODates {
    static func loc() -> Locale { Locale(identifier: Lang.sw ? "sw" : "en_GB") }
    static func parseDay(_ s: String) -> Date? { DateField.parse(String(s.prefix(10))) }
    /// Days from `b` to `a` (yyyy-MM-dd).
    static func days(_ a: String, _ b: String) -> Int {
        guard let x = parseDay(a), let y = parseDay(b) else { return 0 }
        return Int((x.timeIntervalSince(y) / 86400).rounded())
    }
    static func fmt(_ d: Date, _ pattern: String) -> String { let f = DateFormatter(); f.locale = loc(); f.dateFormat = pattern; return f.string(from: d) }
    /// "19 Oct", "Today", "Tomorrow".
    static func dayText(_ d: String, _ today: String) -> String {
        switch days(d, today) {
        case 0: return t("Today", "Leo"); case 1: return t("Tomorrow", "Kesho"); case -1: return t("Yesterday", "Jana")
        default:
            guard let x = parseDay(d) else { return d }
            let same = Calendar.current.component(.year, from: x) == Calendar.current.component(.year, from: Date())
            return fmt(x, same ? "d MMM" : "d MMM yyyy")
        }
    }
    /// "3 days late", "In 4 days".
    static func relText(_ date: String, _ today: String) -> String {
        let n = days(date, today)
        if n < -1 { return t("\(-n) days late", "Imechelewa siku \(-n)") }
        if n == -1 { return t("1 day late", "Imechelewa siku 1") }
        if n == 0 { return t("Today", "Leo") }
        if n == 1 { return t("Tomorrow", "Kesho") }
        return t("In \(n) days", "Baada ya siku \(n)")
    }
    static func lateShort(_ date: String, _ today: String) -> String { let n = -days(date, today); return n == 1 ? t("1 day", "siku 1") : t("\(n) days", "siku \(n)") }
    /// "now", "5 min", "3 h", "Yesterday", then the day.
    static func ago(_ iso: String) -> String {
        guard let d = When.parse(iso) else { return iso.isEmpty ? "" : String(iso.prefix(10)) }
        let m = Int(Date().timeIntervalSince(d) / 60)
        if m < 1 { return t("now", "sasa") }
        if m < 60 { return "\(m) min" }
        if m < 24 * 60 { return "\(m / 60) h" }
        if m < 48 * 60 { return t("Yesterday", "Jana") }
        return day(iso)
    }
    /// "12 Sep" (with the year when it isn't this year).
    static func day(_ iso: String) -> String {
        guard let d = When.parse(iso) ?? parseDay(iso) else { return String(iso.prefix(10)) }
        let same = Calendar.current.component(.year, from: d) == Calendar.current.component(.year, from: Date())
        return fmt(d, same ? "d MMM" : "d MMM yyyy")
    }
    static func today() -> String { When.localDay() }
    static func addDays(_ n: Int, to d: Date = Date()) -> String { When.localDay(Calendar.current.date(byAdding: .day, value: n, to: d) ?? d) }
}

/// Where the Office keeps small choices on this phone (separate in demo mode).
extension AppModel {
    var od: UserDefaults { demo ? (UserDefaults(suiteName: "rafiki-demo") ?? .standard) : .standard }
}

/// Sends a person's choice of tab to the Projects list (filter, country) and opens it.
@MainActor func officeToList(_ m: AppModel, _ f: String, ck: String? = nil) {
    m.prefs.filter = f
    if let ck { m.prefs.country = ck }
    m.tab("list")
}
