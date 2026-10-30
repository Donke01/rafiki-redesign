import SwiftUI
import UIKit

/// The app's line icons, as iPhone system symbols.
enum Ic {
    static let map: [String: String] = [
        "pin": "mappin.and.ellipse", "pinq": "mappin.slash", "drop": "drop", "wrench": "wrench.adjustable", "cal": "calendar", "clip": "list.clipboard",
        "people": "person.2", "person": "person", "map": "map", "mic": "mic", "camera": "camera", "compass": "location.north.circle", "search": "magnifyingglass",
        "list": "list.bullet", "plus": "plus", "undo": "arrow.uturn.backward", "check": "checkmark", "okc": "checkmark.circle", "xc": "xmark.circle", "x": "xmark",
        "alert": "exclamationmark.triangle", "back": "arrow.left", "arrow": "play.fill", "lock": "lock", "refresh": "arrow.clockwise", "target": "scope",
        "layers": "square.3.layers.3d", "del": "delete.left", "clock": "clock", "upload": "square.and.arrow.up", "inbox": "tray", "spark": "sparkle",
        "chev": "chevron.right", "finger": "touchid", "face": "faceid", "phone": "phone", "chat": "message", "down": "chevron.down", "share": "square.and.arrow.up", "satellite": "globe.europe.africa",
        "sun": "sun.max", "globe": "globe", "nav": "location", "file": "doc.text", "link": "link", "flame": "flame", "text": "textformat.size",
        "book": "book", "star": "star", "starfill": "star.fill", "trash": "trash", "grid": "square.grid.2x2", "shield": "checkmark.shield", "offline": "wifi.slash",
        "up": "chevron.up", "next": "arrow.right", "edit": "square.and.pencil",
    ]
    static func name(_ k: String) -> String { map[k] ?? "drop" }
}

struct Icon: View {
    let k: String; var size: CGFloat = 22; var color: Color = C.ink
    init(_ k: String, size: CGFloat = 22, color: Color = C.ink) { self.k = k; self.size = size; self.color = color }
    var body: some View { Image(systemName: Ic.name(k)).font(.system(size: size * 0.82, weight: .medium)).foregroundColor(color).frame(width: size, height: size) }
}

/// The main button: blue, light (outlined) or green. `busy`: a turning circle and what is happening ("Saving…"),
/// in the button's own colours, and the button can't be pressed again meanwhile.
struct Btn: View {
    let text: String; let action: () -> Void
    var icon: String? = nil; var enabled = true; var light = false; var ok = false; var small = false; var wide = false; var busy: String? = nil
    init(_ text: String, icon: String? = nil, enabled: Bool = true, light: Bool = false, ok: Bool = false, small: Bool = false, wide: Bool = false, busy: String? = nil, action: @escaping () -> Void) {
        self.text = text; self.action = action; self.icon = icon; self.enabled = enabled; self.light = light; self.ok = ok; self.small = small; self.wide = wide; self.busy = busy
    }
    var body: some View {
        let fg: Color = light ? C.ink : .white
        let working = busy != nil
        Button(action: action) {
            HStack(spacing: working ? 10 : 8) {
                if working { ProgressView().progressViewStyle(.circular).tint(fg).frame(width: small ? 18 : 20, height: small ? 18 : 20) }
                else if let icon { Icon(icon, size: small ? 18 : 20, color: fg) }
                Text(busy ?? text).font(small ? .mont(14, .bold) : .lLarge).foregroundColor(fg).multilineTextAlignment(.center)
            }
            .padding(.horizontal, small ? 16 : 22)
            .frame(minHeight: small ? 44 : 52)
            .frame(maxWidth: wide ? .infinity : nil)
            .background(
                Group {
                    if !enabled && !working { C.off } else if light { C.card } else if ok { OkBrush } else { BlueBrush }
                }
            )
            .clipShape(Capsule())
            .overlay(Capsule().stroke(light ? C.edge : .clear, lineWidth: 1))
        }
        .buttonStyle(Press())
        .disabled(!enabled || working)
        .accessibilityValue(busy ?? "")
    }
}

struct Press: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed ? 0.97 : 1).animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// A rounded white panel.
struct CardV<Content: View>: View {
    var pad: CGFloat = 18; var deep = false; var fill: Color? = nil; var border: Color? = nil
    @ViewBuilder var content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content() }
            .padding(pad)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Group { if deep { DeepBrush } else { fill ?? C.card } })
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(deep || fill != nil ? Color.clear : (border ?? C.line), lineWidth: 1))
    }
}

/// A square-ish button with an icon over a short label (filters, sections).
struct Tile: View {
    let label: String; let icon: String; let selected: Bool; var badge = 0; let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Icon(icon, size: 22, color: selected ? .white : C.blue)
                Text(label).font(.lSmall).foregroundColor(selected ? .white : C.ink).multilineTextAlignment(.center).lineLimit(2).minimumScaleFactor(0.8)
            }
            .padding(.horizontal, 2).padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: 66)
            .background(Group { if selected { BlueBrush } else { C.card } })
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(selected ? .clear : C.line, lineWidth: 1))
            .overlay(alignment: .top) {
                if badge > 0 {
                    Text("\(badge)").font(.mont(10, .bold)).foregroundColor(selected ? RedC : .white)
                        .padding(.horizontal, 6).padding(.vertical, 1).background(selected ? Color.white : RedC).clipShape(Capsule()).offset(x: 16, y: 5)
                }
            }
        }.buttonStyle(Press())
    }
}

struct Tag: View {
    let text: String; let bg: Color; let fg: Color
    init(_ text: String, bg: Color, fg: Color) { self.text = text; self.bg = bg; self.fg = fg }
    var body: some View { Text(text).font(.lMedium).foregroundColor(fg).padding(.horizontal, 10).padding(.vertical, 5).background(bg).clipShape(Capsule()).lineLimit(1) }
}

struct IconTile: View {
    let icon: String; let tint: Color; let bg: Color; var size: CGFloat = 42
    var body: some View {
        Icon(icon, size: size * 0.55, color: tint).frame(width: size, height: size).background(bg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

/// A labelled text box.
struct Field: View {
    let label: String; @Binding var value: String; var placeholder = ""; var number = false; var lines = 1
    init(_ label: String, _ value: Binding<String>, placeholder: String = "", number: Bool = false, lines: Int = 1) {
        self.label = label; self._value = value; self.placeholder = placeholder; self.number = number; self.lines = lines
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.mont(13, .bold)).foregroundColor(C.ink).padding(.top, 12)
            Group {
                if lines > 1 {
                    if #available(iOS 16.0, *) {
                        TextField("", text: $value, prompt: Text(placeholder).foregroundColor(C.muted), axis: .vertical).lineLimit(lines...8)
                    }
                } else {
                    TextField("", text: $value, prompt: Text(placeholder).foregroundColor(C.muted))
                }
            }
            .font(.bLarge).foregroundColor(C.text)
            .keyboardType(number ? .decimalPad : .default)
            .padding(.horizontal, 14).padding(.vertical, 14)
            .frame(minHeight: 52)
            .background(C.card)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.edge, lineWidth: 1))
        }
    }
}

struct Msg: View {
    let text: String?; var tone = ""
    init(_ text: String?, _ tone: String = "") { self.text = text; self.tone = tone }
    var body: some View {
        if let text, !text.isEmpty {
            Text(text).font(.mont(14, .semibold))
                .foregroundColor(tone == "bad" ? C.bad : tone == "ok" ? C.ok : tone == "warn" ? C.warn : C.muted)
                .padding(.top, 10).fixedSize(horizontal: false, vertical: true)
        }
    }
}

struct H2: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View { Text(text).font(.tLarge).foregroundColor(C.ink).padding(.bottom, 4) }
}

/// A two-state choice button (OK / Problem, Light / Dark…).
struct Pill: View {
    let label: String; let on: Bool; var color: Color? = nil; let action: () -> Void
    init(_ label: String, on: Bool, color: Color? = nil, action: @escaping () -> Void) { self.label = label; self.on = on; self.color = color; self.action = action }
    var body: some View {
        let c = color ?? C.blue
        Button(action: action) {
            Text(label).font(.lMedium).foregroundColor(on ? .white : C.ink).multilineTextAlignment(.center)
                .padding(.horizontal, 10).frame(maxWidth: .infinity, minHeight: 44)
                .background(on ? c : C.card)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(on ? c : C.edge, lineWidth: 1))
        }.buttonStyle(Press())
    }
}

/// A big radio choice with an icon (the condition of a water point).
struct Choice: View {
    let label: String; let icon: String; let tint: Color; let bg: Color; let on: Bool; let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                IconTile(icon: icon, tint: tint, bg: on ? .white : bg)
                Text(label).font(.tMedium).foregroundColor(on ? .white : C.ink)
                Spacer()
            }
            .padding(.horizontal, 14).frame(minHeight: 64)
            .background(Group { if on { BlueBrush } else { C.card } })
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(on ? .clear : C.edge, lineWidth: 1))
        }.buttonStyle(Press()).padding(.top, 10)
    }
}

/// A list to pick one from (a person, a country, a month).
struct Picker1: View {
    let label: String; let value: String; let options: [(String, String)]; let onChange: (String) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !label.isEmpty { Text(label).font(.mont(13, .bold)).foregroundColor(C.ink).padding(.top, 12) }
            Menu {
                ForEach(options, id: \.0) { o in Button(o.1) { onChange(o.0) } }
            } label: {
                HStack {
                    Text(options.first { $0.0 == value }?.1 ?? value).font(.bLarge).foregroundColor(C.text).lineLimit(2).multilineTextAlignment(.leading)
                    Spacer(minLength: 4)
                    Icon("down", size: 18, color: C.muted)
                }
                .padding(.horizontal, 14).padding(.vertical, 6).frame(minHeight: 52)
                .background(C.card)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.edge, lineWidth: 1))
            }
        }
    }
}

/// A date chosen with a calendar. Empty = none. Stored as yyyy-MM-dd.
struct DateField: View {
    let label: String; let value: String; let onChange: (String) -> Void
    @State private var open = false
    @State private var pick = Date()
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.mont(13, .bold)).foregroundColor(C.ink).padding(.top, 12)
            Button { pick = DateField.parse(value) ?? Date(); open = true } label: {
                HStack {
                    Text(value.isEmpty ? t("Choose a date", "Chagua tarehe") : value).font(.bLarge).foregroundColor(value.isEmpty ? C.muted : C.text).lineLimit(2)
                    Spacer(minLength: 4); Icon("cal", size: 20, color: C.blue)
                }
                .padding(.horizontal, 14).frame(minHeight: 52)
                .background(C.card)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.edge, lineWidth: 1))
            }
        }
        .sheet(isPresented: $open) {
            VStack(spacing: 12) {
                DatePicker("", selection: $pick, displayedComponents: .date).datePickerStyle(.graphical).labelsHidden()
                    .environment(\.locale, Locale(identifier: Lang.sw ? "sw" : "en_GB"))
                HStack(spacing: 10) {
                    Btn(t("Clear", "Futa"), light: true, small: true) { onChange(""); open = false }
                    Btn(t("OK", "Sawa"), small: true) { onChange(DateField.text(pick)); open = false }
                }
            }.padding(20).presentationDetents([.medium, .large])
        }
    }
    static func parse(_ s: String) -> Date? { let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd"; return f.date(from: s) }
    static func text(_ d: Date) -> String { let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd"; return f.string(from: d) }
}

/// A tick box with a label.
struct Check: View {
    let label: String; let on: Bool; let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(alignment: .center, spacing: 10) {
                Image(systemName: on ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(on ? C.blue : C.muted)
                Text(label).font(.bMedium).foregroundColor(C.text).multilineTextAlignment(.leading)
                Spacer(minLength: 0)
            }.frame(minHeight: 44)
        }.buttonStyle(.plain)
    }
}

/// A progress bar.
struct Meter: View {
    let f: Double; var light = false
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(light ? Color.white.opacity(0.18) : C.line)
                Capsule().fill(light ? AnyShapeStyle(LinearGradient(colors: [Color(hex: 0x5DB8F2), Color(hex: 0xBFE3FB)], startPoint: .leading, endPoint: .trailing)) : AnyShapeStyle(OkBrush))
                    .frame(width: g.size.width * min(1, max(0, f)))
            }
        }.frame(height: light ? 8 : 10)
    }
}

/// English / Kiswahili switch. Changes every screen at once and is remembered.
struct LangSwitch: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        HStack(spacing: 4) {
            ForEach([(false, "English"), (true, "Kiswahili")], id: \.0) { sw, label in
                let on = Lang.sw == sw
                Button { m.setLang(sw) } label: {
                    Text(label).font(.lMedium).foregroundColor(on ? .white : C.ink).padding(.horizontal, 18).frame(minHeight: 44)
                        .background(Group { if on { BlueBrush } else { Color.clear } }).clipShape(Capsule())
                }.buttonStyle(.plain)
            }
        }.padding(4).background(C.card).clipShape(Capsule()).overlay(Capsule().stroke(C.line, lineWidth: 1))
    }
}

/// "← Back" at the top of a screen.
struct BackLink: View {
    let label: String; let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) { Icon("back", size: 20, color: C.blue2); Text(label).font(.tSmall).foregroundColor(C.blue2) }.frame(minHeight: 44)
        }.buttonStyle(.plain)
    }
}

/// Pictures from the server, kept in memory so lists don't flicker.
final class Pics {
    static let shared = Pics()
    private let mem = NSCache<NSString, UIImage>()
    init() { mem.totalCostLimit = 24 * 1024 * 1024 }
    func cached(_ url: String) -> UIImage? { mem.object(forKey: url as NSString) }
    func load(_ url: String) async -> UIImage? {
        if let c = cached(url) { return c }
        guard let u = URL(string: url), let (d, _) = try? await Api.net.data(from: u), let img = UIImage(data: d) else { return nil }
        mem.setObject(img, forKey: url as NSString, cost: d.count); return img
    }
}

struct NetImage<P: View>: View {
    let url: String?
    @ViewBuilder var placeholder: () -> P
    @State private var img: UIImage?
    var body: some View {
        ZStack {
            if let img { Image(uiImage: img).resizable().scaledToFill() } else { placeholder() }
        }
        .task(id: url) { if let url { img = Pics.shared.cached(url); if img == nil { img = await Pics.shared.load(url) } } }
    }
}

/// Opens the share sheet (WhatsApp, e-mail, Files, Drive…).
enum Share {
    static func items(_ items: [Any]) {
        let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)
        guard let root = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first?.rootViewController else { return }
        var top = root; while let p = top.presentedViewController { top = p }
        vc.popoverPresentationController?.sourceView = top.view
        vc.popoverPresentationController?.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.maxY - 80, width: 1, height: 1)
        top.present(vc, animated: true)
    }
    static func open(_ url: String) { if let u = URL(string: url) { UIApplication.shared.open(u) } }
}
