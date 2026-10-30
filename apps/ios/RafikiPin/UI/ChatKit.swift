import AudioToolbox
import SwiftUI
import UIKit

// Pieces every Chat and Trips screen shares: colours (RAFIKI Deep, the 2.27 chat face lift "direction B": a night-blue
// header with a soft glow, warm sand behind the chats, my messages in deep blue; Bright sun keeps its plain look),
// pictures, ticks, times, sheet rows, sounds, and the header's Chat button that does the navigation.

/// The named colours (the same as Theme's, for any name: Theme's `hue` takes a screen key).
enum ChatHues {
    private static let light: [String: Hue] = [
        "blue": Hue(fg: Color(hex: 0x0A78C2), bg: Color(hex: 0xE3F1FC)), "green": Hue(fg: Color(hex: 0x1C8A50), bg: Color(hex: 0xE2F4EA)),
        "purple": Hue(fg: Color(hex: 0x6E46C8), bg: Color(hex: 0xEEE7FB)), "amber": Hue(fg: Color(hex: 0xB86E00), bg: Color(hex: 0xFDF0DC)),
        "teal": Hue(fg: Color(hex: 0x0E7F86), bg: Color(hex: 0xDCF2F3)), "coral": Hue(fg: Color(hex: 0xD2553A), bg: Color(hex: 0xFCE9E4)),
        "slate": Hue(fg: Color(hex: 0x36505F), bg: Color(hex: 0xE8EDF1)),
    ]
    private static let dark: [String: Hue] = [
        "blue": Hue(fg: Color(hex: 0x5BB4F2), bg: Color(hex: 0x12324A)), "green": Hue(fg: Color(hex: 0x5FD08F), bg: Color(hex: 0x123222)),
        "purple": Hue(fg: Color(hex: 0xB49BFF), bg: Color(hex: 0x2A2145)), "amber": Hue(fg: Color(hex: 0xF2B35B), bg: Color(hex: 0x3A2A10)),
        "teal": Hue(fg: Color(hex: 0x5CD3D6), bg: Color(hex: 0x0F3133)), "coral": Hue(fg: Color(hex: 0xFF9A82), bg: Color(hex: 0x3D1F18)),
        "slate": Hue(fg: Color(hex: 0xA9BAC6), bg: Color(hex: 0x22303A)),
    ]
    static func named(_ k: String) -> Hue { (C.dark ? dark : light)[k] ?? (C.dark ? dark : light)["slate"] ?? Hue(fg: C.ink, bg: C.tint) }
    /// A person's colour (their name in a group, their initials).
    static func person(_ name: String) -> Hue {
        let keys = ["blue", "green", "purple", "teal", "coral", "amber"]
        var h: UInt32 = 7
        for u in name.lowercased().unicodeScalars { h = h &* 31 &+ u.value }
        return named(keys[Int(h % UInt32(keys.count))])
    }
    static var bad: Hue { Hue(fg: C.bad, bg: C.badBg) }
    static var warn: Hue { Hue(fg: C.warn, bg: C.warnBg) }
    static var ok: Hue { Hue(fg: C.ok, bg: C.okBg) }
}

/// The chat colours (RAFIKI Deep), in light, dark and Bright sun.
enum ChatLook {
    static var sun: Bool { Look.mode == "sun" }
    /// The face lift is on (every look but Bright sun).
    static var deep: Bool { !sun }
    static let online = Color(hex: 0x22B566)
    static let mint = Color(hex: 0x7FE3B0)
    static let ice = Color(hex: 0x9FD3F7)
    static var night: Color { C.dark ? Color(hex: 0x03182A) : Color(hex: 0x04263F) }
    static var night2: Color { C.dark ? Color(hex: 0x052C48) : Color(hex: 0x06375A) }
    /// Behind the chat list.
    static var sand: Color { sun ? .white : C.dark ? Color(hex: 0x0D1318) : Color(hex: 0xF6F2EC) }
    /// Behind the messages when no wallpaper is chosen.
    static var room: Color { sun ? .white : C.dark ? Color(hex: 0x0F151A) : Color(hex: 0xF3ECE2) }
    static var sandLine: Color { C.dark ? Color.white.opacity(0.05) : Color(hex: 0xE4D8C6) }
    static var acc: Color { C.dark ? Color(hex: 0x2BB98C) : Color(hex: 0x0B8F6A) }
    static var acc2: Color { C.dark ? Color(hex: 0x23A57D) : Color(hex: 0x12A37A) }
    static var go: LinearGradient { LinearGradient(colors: sun ? [Color(hex: 0x00562A), Color(hex: 0x00562A)] : [acc2, acc], startPoint: .topLeading, endPoint: .bottomTrailing) }
    static var dayPill: Color { sun ? .white : C.dark ? Color(hex: 0x1C262E) : Color(hex: 0xE8DCCB) }
    static var dayText: Color { sun ? .black : C.dark ? Color(hex: 0xB9C6D0) : Color(hex: 0x6A5A44) }
    /// Unread: a bold green name, time and count.
    static var un: Color { sun ? Color(hex: 0x00562A) : C.dark ? Color(hex: 0x3CC07A) : Color(hex: 0x1C8A50) }
    static var atBg: Color { sun ? .white : C.dark ? Color(hex: 0x3CC07A, alpha: 0.2) : Color(hex: 0xE3F4EA) }
    static var read: Color { sun ? Color(hex: 0x0050A0) : C.dark ? Color(hex: 0x5BC0FF) : Color(hex: 0x1D8FE0) }
    static var tick: Color { C.dark ? Color(hex: 0x9CB4C6) : Color(hex: 0x6B7C8A) }
    static var time: Color { sun ? Color(hex: 0x1E1E1E) : C.dark ? Color(hex: 0x9AA8B3) : Color(hex: 0x66737D) }
    static var theirs: Color { C.dark ? Color(hex: 0x1E2A36) : .white }
    /// My messages in the default colour: night blue; Bright sun keeps a pale blue.
    static var mine: Color { sun ? Color(hex: 0xDCEBFF) : C.dark ? Color(hex: 0x0E4A75) : Color(hex: 0x06375A) }
    static var link: Color { sun ? Color(hex: 0x0050A0) : C.dark ? Color(hex: 0x5BC0FF) : C.blue }

    static let bubbles = ["", "green", "teal", "blue", "purple", "pink", "orange", "grey"]
    static func bubbleName(_ k: String) -> String {
        switch k {
        case "green": return t("Green", "Kijani"); case "teal": return t("Teal", "Samawati"); case "blue": return t("Blue", "Buluu"); case "purple": return t("Purple", "Zambarau")
        case "pink": return t("Pink", "Waridi"); case "orange": return t("Orange", "Machungwa"); case "grey": return t("Grey", "Kijivu")
        default: return deep ? t("Night blue", "Bluu ya usiku") : t("Usual", "Kawaida")
        }
    }
    /// My bubble in a chosen colour; nil = the default (night blue, white words).
    static func bubble(_ k: String) -> Color? {
        let d = C.dark
        switch k {
        case "green": return d ? Color(hex: 0x125C43) : Color(hex: 0xDCF6CF)
        case "teal": return d ? Color(hex: 0x0B5257) : Color(hex: 0xD2F1EE)
        case "blue": return d ? Color(hex: 0x184F86) : Color(hex: 0xDCE8FF)
        case "purple": return d ? Color(hex: 0x4A2E7A) : Color(hex: 0xEBE0FB)
        case "pink": return d ? Color(hex: 0x6E2A4F) : Color(hex: 0xFBDDEB)
        case "orange": return d ? Color(hex: 0x6B4214) : Color(hex: 0xFFE7CC)
        case "grey": return d ? Color(hex: 0x39444E) : Color(hex: 0xE8ECEF)
        default: return nil
        }
    }
    static let walls = ["", "dots", "waves", "leaves", "drops"]
    static func wallName(_ k: String) -> String {
        switch k {
        case "dots": return t("Dots", "Vitone"); case "waves": return t("Waves", "Mawimbi"); case "leaves": return t("Leaves", "Majani"); case "drops": return t("Drops", "Matone")
        default: return deep ? t("Standard", "Kawaida") : t("None", "Hakuna")
        }
    }
}

/// The night block (the chat list's top, a chat's header): night blue with a soft teal and blue glow, rounded at the bottom.
struct ChatNight<Content: View>: View {
    var round: CGFloat = 26
    @ViewBuilder var content: () -> Content
    var body: some View {
        if ChatLook.deep {
            VStack(spacing: 0) { content() }
                .frame(maxWidth: .infinity)
                .background(
                    GeometryReader { g in
                        ZStack {
                            ChatLook.night
                            RadialGradient(colors: [Color(hex: 0x2EB4AA, alpha: C.dark ? 0.26 : 0.42), .clear], center: UnitPoint(x: 0.88, y: -0.1), startRadius: 0, endRadius: max(g.size.width * 0.7, 200))
                            RadialGradient(colors: [Color(hex: 0x0A78C2, alpha: C.dark ? 0.34 : 0.55), .clear], center: UnitPoint(x: -0.05, y: -0.2), startRadius: 0, endRadius: max(g.size.width * 0.6, 180))
                        }
                    }.ignoresSafeArea(edges: .top)
                )
                .clipShape(ChatBottomRound(r: round))
                .shadow(color: Color(hex: 0x04263F, alpha: 0.18), radius: round > 0 ? 8 : 0, y: 3)
        } else {
            VStack(spacing: 0) { content() }.frame(maxWidth: .infinity).background(C.card)
                .overlay(alignment: .bottom) { Rectangle().fill(C.line).frame(height: ChatLook.sun ? 2 : 1) }
        }
    }
}
/// Words on the night block: white (night) or the app's ink (Bright sun).
enum NightInk {
    static var ink: Color { ChatLook.deep ? .white : C.ink }
    static var muted: Color { ChatLook.deep ? Color(hex: 0xA9C3D6) : C.muted }
    static var chip: Color { ChatLook.deep ? Color.white.opacity(0.1) : C.card }
}
struct ChatBottomRound: Shape {
    let r: CGFloat
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let rr = min(r, rect.height / 2)
        p.move(to: CGPoint(x: rect.minX, y: rect.minY - 400))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.minY - 400))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - rr))
        p.addQuadCurve(to: CGPoint(x: rect.maxX - rr, y: rect.maxY), control: CGPoint(x: rect.maxX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX + rr, y: rect.maxY))
        p.addQuadCurve(to: CGPoint(x: rect.minX, y: rect.maxY - rr), control: CGPoint(x: rect.minX, y: rect.maxY))
        p.closeSubpath()
        return p
    }
}

/// Warm sand with soft contour lines (no wallpaper chosen), or a chosen pattern.
struct ChatWall: View {
    let kind: String
    var body: some View {
        Canvas { ctx, size in
            if kind.isEmpty {
                guard ChatLook.deep else { return }
                let tile: CGFloat = 160
                var y0: CGFloat = 0
                while y0 < size.height + tile {
                    for dy in [40.0, 90.0, 140.0] {
                        let y = y0 + CGFloat(dy) / 160 * tile
                        var p = Path(); p.move(to: CGPoint(x: 0, y: y))
                        var x: CGFloat = 0
                        while x < size.width + tile {
                            p.addCurve(to: CGPoint(x: x + tile, y: y), control1: CGPoint(x: x + tile * 0.25, y: y - tile * 0.125), control2: CGPoint(x: x + tile * 0.5, y: y + tile * 0.125))
                            x += tile
                        }
                        ctx.stroke(p, with: .color(ChatLook.sandLine), lineWidth: 1.2)
                    }
                    y0 += tile
                }
                return
            }
            let ink = C.dark ? Color.white.opacity(0.08) : Color(hex: 0x0A78C2, alpha: 0.07)
            let step: CGFloat = 34
            var y: CGFloat = 0; var row = 0
            while y < size.height + step {
                var x: CGFloat = row % 2 == 0 ? 0 : step / 2
                while x < size.width + step {
                    var p = Path()
                    switch kind {
                    case "dots": p.addEllipse(in: CGRect(x: x - 2.2, y: y - 2.2, width: 4.4, height: 4.4)); ctx.fill(p, with: .color(ink))
                    case "waves":
                        p.move(to: CGPoint(x: x - step / 2, y: y)); p.addQuadCurve(to: CGPoint(x: x, y: y), control: CGPoint(x: x - step / 4, y: y - 6))
                        p.addQuadCurve(to: CGPoint(x: x + step / 2, y: y), control: CGPoint(x: x + step / 4, y: y + 6)); ctx.stroke(p, with: .color(ink), lineWidth: 1.6)
                    case "leaves":
                        p.move(to: CGPoint(x: x, y: y - 7)); p.addQuadCurve(to: CGPoint(x: x, y: y + 7), control: CGPoint(x: x + 7, y: y))
                        p.addQuadCurve(to: CGPoint(x: x, y: y - 7), control: CGPoint(x: x - 7, y: y)); ctx.stroke(p, with: .color(ink), lineWidth: 1.4)
                    default:
                        p.move(to: CGPoint(x: x, y: y - 7)); p.addCurve(to: CGPoint(x: x, y: y + 6), control1: CGPoint(x: x + 6, y: y), control2: CGPoint(x: x + 4, y: y + 6))
                        p.addCurve(to: CGPoint(x: x, y: y - 7), control1: CGPoint(x: x - 4, y: y + 6), control2: CGPoint(x: x - 6, y: y)); ctx.stroke(p, with: .color(ink), lineWidth: 1.4)
                    }
                    x += step
                }
                y += step; row += 1
            }
        }
        .background(kind.isEmpty ? ChatLook.room : (C.dark ? Color(hex: 0x0F171E) : Color(hex: 0xEAF3F9)))
        .allowsHitTesting(false)
    }
}

/// Chat's own icons as iPhone symbols.
enum ChatIc {
    static func name(_ k: String) -> String {
        switch k {
        case "send": return "paperplane.fill"; case "clip": return "paperclip"; case "reply": return "arrowshape.turn.up.left"; case "fwd": return "arrowshape.turn.up.right"
        case "copy": return "doc.on.doc"; case "mute": return "bell.slash"; case "archive": return "archivebox"; case "pushpin": return "pin"; case "newchat": return "square.and.pencil"
        case "trash": return "trash"; case "selectall": return "checkmark.circle"; case "translate": return "character.bubble"; case "photoadd": return "photo.badge.plus"
        case "info": return "info.circle"; case "more": return "ellipsis"; case "megaphone": return "megaphone"; case "stop": return "stop.fill"; case "play": return "play.fill"; case "pause": return "pause.fill"
        case "moon": return "moon"; case "data": return "arrow.up.arrow.down"; case "bubble": return "bubble.left"; case "wall": return "photo"; case "gallery": return "photo.on.rectangle"
        case "doc": return "doc"; case "contact": return "person.crop.circle"; case "live": return "location.circle"; case "group": return "person.3"; case "exit": return "rectangle.portrait.and.arrow.right"
        case "crown": return "crown"; case "bell": return "bell"; case "cog": return "gearshape"; case "route": return "point.topleft.down.curvedto.point.bottomright.up"; case "spark": return "sparkles"
        case "expand": return "arrow.up.left.and.arrow.down.right"; case "video": return "video"; case "keyboard": return "keyboard"; case "house": return "house"; case "leaf": return "leaf"
        case "cloud": return "cloud.rain"; case "coins": return "banknote"; case "car": return "car"
        default: return Ic.name(k)
        }
    }
}
struct CIcon: View {
    let k: String; var size: CGFloat = 20; var color: Color = C.ink
    init(_ k: String, size: CGFloat = 20, color: Color = C.ink) { self.k = k; self.size = size; self.color = color }
    var body: some View { Image(systemName: ChatIc.name(k)).font(.system(size: size * 0.82, weight: .medium)).foregroundColor(color).frame(width: size, height: size) }
}

/// Initials in a coloured circle.
struct ChatPersonAvatar: View {
    let name: String; var size: CGFloat = 40
    var body: some View {
        let hu = ChatHues.person(name)
        let ini = name.split(separator: " ").compactMap { $0.first.map { String($0).uppercased() } }.prefix(2).joined()
        Text(ini.isEmpty ? "·" : ini).font(.mont(size * 0.34, .heavy)).foregroundColor(hu.fg)
            .frame(width: size, height: size).background(hu.bg).clipShape(Circle())
            .accessibilityHidden(true)
    }
}

/// A chat's picture: initials for a person (with the green dot when online), Announcements' megaphone, a country team's
/// flag, a project's drop or photo, a group's initials.
struct ChatAvatar: View {
    @EnvironmentObject var m: AppModel
    let row: ChatRow?; var me: String = ""; var size: CGFloat = 50; var online = false; var name: String? = nil
    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            pic.frame(width: size, height: size)
            if online {
                Circle().fill(ChatLook.online).frame(width: size * 0.26, height: size * 0.26)
                    .overlay(Circle().stroke(C.card, lineWidth: 2)).accessibilityLabel(t("online", "yupo mtandaoni"))
            }
        }.frame(width: size, height: size)
    }
    @ViewBuilder private var pic: some View {
        let kind = row?.kind ?? "dm"
        let title = name ?? (kind == "dm" ? (row?.other(me) ?? "") : (row.map { chatTitle($0, me) } ?? ""))
        if row?.announce == true {
            let hu = ChatHues.named("amber")
            CIcon("megaphone", size: size * 0.46, color: hu.fg).frame(width: size, height: size).background(hu.bg).clipShape(Circle())
        } else if kind == "dm" {
            ChatPersonAvatar(name: title, size: size)
        } else if let r = row, !r.photo.isEmpty {
            ChatPic(md: ChatMedia(id: r.photo, type: "photo"), small: true).frame(width: size, height: size).clipShape(Circle())
        } else if let r = row, !r.country.isEmpty {
            Text(ChatKit.flag(r.country)).font(.system(size: size * 0.56)).frame(width: size, height: size).background(ChatHues.named("teal").bg).clipShape(Circle())
        } else if let r = row, kind == "project", !r.pphoto.isEmpty {
            NetImage(url: r.pphoto.hasPrefix("http") ? r.pphoto : m.api.server + r.pphoto) { ChatHues.named("blue").bg }
                .frame(width: size, height: size).clipShape(Circle())
        } else if kind == "project" {
            let hu = ChatHues.named("blue")
            Icon("drop", size: size * 0.48, color: hu.fg).frame(width: size, height: size).background(hu.bg).clipShape(Circle())
        } else {
            let hu = kind == "team" ? ChatHues.named("teal") : ChatHues.person(title)
            Text(title.split(separator: " ").compactMap { $0.first.map { String($0).uppercased() } }.prefix(2).joined())
                .font(.mont(size * 0.32, .heavy)).foregroundColor(hu.fg).frame(width: size, height: size).background(hu.bg).clipShape(Circle())
        }
    }
}

/// The ticks on my messages: a clock while waiting, one grey tick sent, two grey delivered, two blue read; a red ! if refused.
struct ChatTicks: View {
    let status: String; var tint: Color = ChatLook.tick
    var body: some View {
        Group {
            switch status {
            case "queued": Image(systemName: "clock").font(.system(size: 10, weight: .semibold)).foregroundColor(tint)
            case "failed": Image(systemName: "exclamationmark.circle.fill").font(.system(size: 11, weight: .bold)).foregroundColor(C.bad)
            case "sent": Image(systemName: "checkmark").font(.system(size: 10, weight: .bold)).foregroundColor(tint)
            default:
                ZStack {
                    Image(systemName: "checkmark").font(.system(size: 10, weight: .bold)).offset(x: -3)
                    Image(systemName: "checkmark").font(.system(size: 10, weight: .bold)).offset(x: 2)
                }.foregroundColor(status == "read" ? ChatLook.read : tint)
            }
        }
        .frame(width: 16, height: 12)
        .accessibilityLabel(ChatKit.statusWord(status))
    }
}

enum ChatKit {
    static func flag(_ cc: String) -> String {
        let base: UInt32 = 127397
        return cc.uppercased().unicodeScalars.compactMap { UnicodeScalar(base + $0.value) }.map { String($0) }.joined()
    }
    static func statusWord(_ s: String) -> String {
        switch s { case "read": return t("read", "imesomwa"); case "delivered": return t("delivered", "imefika"); case "queued": return t("waiting to send", "inasubiri kutumwa"); case "failed": return t("not sent", "haijatumwa"); default: return t("sent", "imetumwa") }
    }
    private static func fmt(_ f: String) -> DateFormatter { let d = DateFormatter(); d.locale = Locale(identifier: Lang.sw ? "sw" : "en_GB"); d.dateFormat = f; return d }
    static func time(_ ms: Double) -> String { fmt("HH:mm").string(from: Date(timeIntervalSince1970: ms / 1000)) }
    static func dateTime(_ ms: Double) -> String { fmt("d MMM, HH:mm").string(from: Date(timeIntervalSince1970: ms / 1000)) }
    private static func daysAgo(_ ms: Double) -> Int {
        let cal = Calendar.current
        let a = cal.startOfDay(for: Date(timeIntervalSince1970: ms / 1000)), b = cal.startOfDay(for: Date())
        return cal.dateComponents([.day], from: a, to: b).day ?? 0
    }
    /// The list's time: "10:42" today, "Yesterday", "Mon" this week, then "24 Oct".
    static func when(_ ms: Double) -> String {
        guard ms > 0 else { return "" }
        let n = daysAgo(ms)
        if n == 0 { return time(ms) }
        if n == 1 { return t("Yesterday", "Jana") }
        if n >= 2 && n <= 6 { return fmt("EEE").string(from: Date(timeIntervalSince1970: ms / 1000)) }
        return fmt("d MMM").string(from: Date(timeIntervalSince1970: ms / 1000))
    }
    /// A date pill in a chat: "Today", "Yesterday", "Mon 20 Oct".
    static func day(_ ms: Double) -> String {
        let n = daysAgo(ms)
        if n == 0 { return t("Today", "Leo") }
        if n == 1 { return t("Yesterday", "Jana") }
        return fmt("EEE d MMM").string(from: Date(timeIntervalSince1970: ms / 1000))
    }
    /// "last seen 10:42", "last seen yesterday at 10:42", "last seen 21 Oct, 10:42".
    static func lastSeen(_ ms: Double) -> String {
        guard ms > 0 else { return "" }
        switch daysAgo(ms) {
        case 0: return t("last seen ", "alionekana ") + time(ms)
        case 1: return t("last seen yesterday at ", "alionekana jana saa ") + time(ms)
        default: return t("last seen ", "alionekana ") + dateTime(ms)
        }
    }
    static func dayKey(_ ms: Double) -> String { When.localDay(Date(timeIntervalSince1970: ms / 1000)) }
    static func first(_ name: String) -> String { name.split(separator: " ").first.map(String.init) ?? name }
    /// Links in a message.
    static func links(_ text: String) -> [String] {
        guard let det = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else { return [] }
        return det.matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap { $0.url?.absoluteString }
    }
    /// A project linked in a message: pin.rafikioutreach.org/pin#p=<id>.
    static func projectIn(_ text: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: #"pin\.rafikioutreach\.org/pin#p[/=]([A-Za-z0-9_-]+)"#),
              let m = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)), let r = Range(m.range(at: 1), in: text) else { return nil }
        return String(text[r])
    }
    /// The Google Translate page for a message (when the server's translation isn't switched on).
    static func google(_ text: String) -> String {
        let sw = isSw(text)
        return "https://translate.google.com/?sl=auto&tl=" + (sw ? "en" : "sw") + "&text=" + Api.enc(String(text.prefix(2000))) + "&op=translate"
    }
    static func isSw(_ text: String) -> Bool {
        let words = Set(text.lowercased().split(whereSeparator: { !$0.isLetter }).map(String.init))
        let sw: Set<String> = ["na", "ya", "wa", "kwa", "ni", "za", "la", "katika", "hii", "huu", "sana", "asante", "habari", "nini", "leo", "kesho", "tuko", "niko", "maji", "pampu", "kisima"]
        return words.intersection(sw).count >= 2
    }
    /// An invite token from a pasted link.
    static func joinToken(_ link: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: #"(?:join[=/])([A-Za-z0-9_-]{6,})"#),
              let m = re.firstMatch(in: link, range: NSRange(link.startIndex..., in: link)), let r = Range(m.range(at: 1), in: link) else { return nil }
        return String(link[r])
    }
}

/// The soft sounds: a whoosh when a message goes, a chime when I'm @mentioned (iPhone's own message sounds; quiet when the
/// phone is on silent).
enum ChatSound {
    static func sent() { AudioServicesPlaySystemSound(1004) }
    static func mention() { AudioServicesPlaySystemSound(1007) }
}

/// A plain action row in a sheet: icon and label (and a line under it).
struct ChatSheetRow: View {
    let icon: String; let label: String; var red = false; var value = ""; let action: () -> Void
    init(_ icon: String, _ label: String, red: Bool = false, value: String = "", action: @escaping () -> Void) { self.icon = icon; self.label = label; self.red = red; self.value = value; self.action = action }
    var body: some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 14) {
                CIcon(icon, size: 19, color: red ? C.bad : ChatHues.named("blue").fg).frame(width: 36, height: 36)
                    .background(red ? C.badBg : ChatHues.named("blue").bg).clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.tSmall).foregroundColor(red ? C.bad : C.ink).fixedSize(horizontal: false, vertical: true)
                    if !value.isEmpty { Text(value).font(.bSmall).foregroundColor(C.muted).lineLimit(2).fixedSize(horizontal: false, vertical: true) }
                }
                Spacer(minLength: 0)
            }.frame(minHeight: 52).padding(.horizontal, 4).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

/// A sheet's title with a close cross.
struct ChatSheetHead: View {
    let title: String
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text(title).font(.tLarge).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            Button { dismiss() } label: { Icon("x", size: 18, color: C.muted).frame(width: 44, height: 44).background(C.tint.opacity(0.6)).clipShape(Circle()) }
                .buttonStyle(.plain).accessibilityLabel(t("Close", "Funga"))
        }.padding(.bottom, 6)
    }
}

/// A friendly empty state with an action.
struct ChatEmpty: View {
    let text: String; var action: String? = nil; var go: () -> Void = {}
    var body: some View {
        VStack(spacing: 12) {
            Icon("drop", size: 34, color: ChatHues.named("blue").fg).frame(width: 64, height: 64).background(ChatHues.named("blue").bg).clipShape(Circle())
            Text(text).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            if let action { Btn(action, small: true) { go() } }
        }.frame(maxWidth: .infinity).padding(.vertical, 28).padding(.horizontal, 20)
    }
}

/// "Chat isn't switched on yet", with the technical names in a fold.
struct ChatOffCard: View {
    let need: [String]
    @State private var open = false
    var body: some View {
        CardV {
            HStack(spacing: 12) {
                IconTile(icon: "chat", tint: ChatHues.named("blue").fg, bg: ChatHues.named("blue").bg, size: 44)
                Text(t("Chat isn’t switched on yet. The office can turn it on.", "Gumzo bado halijawashwa. Ofisi inaweza kuliwasha.")).font(.tSmall).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
            }
            Button { open.toggle() } label: {
                Text(open ? t("Hide technical details", "Ficha maelezo ya kiufundi") : t("Technical details", "Maelezo ya kiufundi")).font(.lMedium).foregroundColor(C.blue).frame(minHeight: 44)
            }.buttonStyle(.plain)
            if open {
                Text(t("Every /api/chat/* call answers {ok:false, reason:'chat-off'}: the Pages project has no CHAT binding yet. Deploy the rafiki-chat worker (workers/chat, Durable Objects), add the CHAT service binding and the CHAT_SECRET in both, and the R2 bucket rafiki-chat-media as CHAT_MEDIA.",
                       "Kila mwito wa /api/chat/* unajibu {ok:false, reason:'chat-off'}: mradi wa Pages bado hauna kiungo cha CHAT. Weka worker ya rafiki-chat (workers/chat, Durable Objects), ongeza kiungo cha huduma CHAT na CHAT_SECRET pande zote mbili, na ndoo ya R2 rafiki-chat-media kama CHAT_MEDIA."))
                    .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                if !need.isEmpty { Text(t("The server says it needs: ", "Seva inasema inahitaji: ") + need.joined(separator: "; ")).font(.bSmall).foregroundColor(C.muted).padding(.top, 6) }
            }
        }
    }
}

/// A round 44-point icon button.
struct ChatRoundBtn: View {
    let icon: String; let label: String; var color: Color = C.ink; var bg: Color = .clear; let action: () -> Void
    var body: some View {
        Button { Haptic.tap(); action() } label: { CIcon(icon, size: 22, color: color).frame(width: 44, height: 44).background(bg).clipShape(Circle()) }
            .buttonStyle(.plain).accessibilityLabel(label)
    }
}

/// A short message that slides up from the bottom and goes after a few seconds (the hub's note).
struct ChatToast: View {
    @ObservedObject var hub: ChatHub
    var body: some View {
        VStack {
            Spacer()
            if let n = hub.note {
                Text(n).font(.mont(14, .semibold)).foregroundColor(.white).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 16).padding(.vertical, 12).background(Color.black.opacity(0.82)).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .padding(.horizontal, 24).padding(.bottom, 90)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task(id: n) { try? await Task.sleep(nanoseconds: 2_600_000_000); if hub.note == n { withAnimation { hub.note = nil } } }
                    .onTapGesture { hub.note = nil }
            }
        }.animation(Motion.on ? .spring(response: 0.35, dampingFraction: 0.85) : nil, value: hub.note)
        .allowsHitTesting(hub.note != nil)
    }
}

// MARK: - the header's Chat button and the chat navigation -----------------------------------------------------------------

/// The Chat button in the app's header (Android has Chat as a bottom tab; on iPhone the five tabs stay, so Chat sits at
/// the top right, with the unread count). It also starts Chat when the app opens and opens what other screens ask for.
struct ChatTopButton: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    @ObservedObject var nav = ChatNav.shared
    var body: some View {
        let inChat = m.here.sub?.hasPrefix("chat") == true
        Button { Haptic.tap(); openList() } label: {
            Image(systemName: inChat ? "bubble.left.and.bubble.right.fill" : "bubble.left.and.bubble.right").font(.system(size: 18, weight: .medium)).foregroundColor(C.ink)
                .frame(width: 44, height: 44).background(C.card.opacity(0.85)).clipShape(Circle())
                .overlay(alignment: .topTrailing) {
                    if hub.unread > 0 {
                        Text(hub.unread > 99 ? "99+" : "\(hub.unread)").font(.mont(10, .bold)).foregroundColor(.white).padding(.horizontal, 5).frame(minHeight: 16)
                            .background(ChatLook.online).clipShape(Capsule()).offset(x: 4, y: -2)
                    }
                }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(t("Chat", "Gumzo") + (hub.unread > 0 ? ", \(hub.unread)" : ""))
        .accessibilityIdentifier("nav_chat")
        .onAppear { hub.start(); DemoChat.route(m) }
        .onChange(of: nav.req) { r in if let r { nav.req = nil; Task { await handle(r) } } }
    }
    private func openList() {
        if m.here.sub == "chat" { return }
        if let i = m.stack.firstIndex(where: { $0.sub == "chat" }) { m.stack = Array(m.stack.prefix(i + 1)); return }
        m.go(Place(tab: m.here.tab, sub: "chat"))
    }
    private func open(_ c: String) {
        if m.here.sub == "chat:" + c { return }
        m.go(Place(tab: m.here.tab, sub: "chat:" + c))
    }
    private func handle(_ r: String) async {
        if r.isEmpty { openList(); return }
        if r.hasPrefix("n:") { openList(); open(String(r.dropFirst(2))); return }
        if r.hasPrefix("c:") { open(String(r.dropFirst(2))); return }
        if r.hasPrefix("dm:") { let (id, e) = await hub.openDm(String(r.dropFirst(3))); if let id { open(id) } else { hub.note = e }; return }
        if r.hasPrefix("p:") { let (id, e) = await hub.openProject(String(r.dropFirst(2))); if let id { open(id) } else { hub.note = e }; return }
        if r.hasPrefix("join:") { let (id, e) = await hub.join(String(r.dropFirst(5))); if let id { openList(); open(id) } else { hub.note = e } }
    }
}

/// Which Chat or Trips screen a place's `sub` opens ("chat", "chat:<id>", "chatinfo:<id>", "chatnew", "chatarch",
/// "chatstar", "chatsettings", "trip:<id>", "trip_new", "gtrip:<id>", "gtrip_new", "gview:<id>").
enum ChatRoutes {
    static func handles(_ sub: String?) -> Bool {
        guard let s = sub else { return false }
        return s == "chat" || s.hasPrefix("chat:") || s.hasPrefix("chatinfo:") || s == "chatnew" || s == "chatarch" || s == "chatstar" || s == "chatsettings"
            || s.hasPrefix("trip:") || s == "trip_new" || s.hasPrefix("gtrip:") || s == "gtrip_new" || s.hasPrefix("gview:")
    }
    /// Inside a conversation the composer sits at the bottom, so the bottom bar steps aside.
    static func hidesTabs(_ sub: String?) -> Bool { sub?.hasPrefix("chat:") == true }
}

/// The screen for a Chat or Trips place.
struct ChatScreen: View {
    @EnvironmentObject var m: AppModel
    let sub: String
    var body: some View {
        ZStack {
            screen
            ChatToast(hub: m.chat)
        }
    }
    @ViewBuilder private var screen: some View {
        let hub = m.chat
        if sub == "chat" { ChatListView(hub: hub, archived: false) }
        else if sub == "chatarch" { ChatListView(hub: hub, archived: true) }
        else if sub.hasPrefix("chat:") { ChatRoomView(hub: hub, c: String(sub.dropFirst(5))) }
        else if sub.hasPrefix("chatinfo:") { ChatInfoView(hub: hub, c: String(sub.dropFirst(9))) }
        else if sub == "chatnew" { ChatNewView(hub: hub) }
        else if sub == "chatstar" { ChatStarredView(hub: hub) }
        else if sub == "chatsettings" { ChatSettingsView(hub: hub) }
        else if sub == "trip_new" { TripPlanView(repo: m.trips) }
        else if sub.hasPrefix("trip:") { TripView(repo: m.trips, id: String(sub.dropFirst(5))) }
        else if sub == "gtrip_new" { TripGroupNewView(repo: m.trips) }
        else if sub.hasPrefix("gtrip:") { TripGroupView(repo: m.trips, id: String(sub.dropFirst(6))) }
        else if sub.hasPrefix("gview:") { TripGroupView(repo: m.trips, id: String(sub.dropFirst(6)), preview: true) }
        else { Color.clear }
    }
}

/// A label with the app's switch on the right.
struct ChatSwitchRow: View {
    let label: String; let on: Bool; let set: (Bool) -> Void
    var body: some View {
        HStack(spacing: 10) {
            Text(label).font(.tSmall).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 6)
            Toggle2(on: on, label: label) { Haptic.tap(); set(!on) }
        }.padding(.horizontal, 6).frame(minHeight: 52)
    }
}
