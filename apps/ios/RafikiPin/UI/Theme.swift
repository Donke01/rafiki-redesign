import CoreText
import SwiftUI
import UIKit

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }
}

/// RAFIKI colours: the same as the web and Android apps.
struct Palette {
    var blue, blue2, deep, ink, tint, bg, card, text, muted, line, edge, ok, okBg, warn, warnBg, bad, badBg, off: Color
    var dark: Bool
}

let LightP = Palette(blue: Color(hex: 0x0A78C2), blue2: Color(hex: 0x076DB1), deep: Color(hex: 0x054F81), ink: Color(hex: 0x04263F), tint: Color(hex: 0xE3F1FC),
                     bg: Color(hex: 0xEEF0F2), card: .white, text: Color(hex: 0x141618), muted: Color(hex: 0x5A6066), line: Color(hex: 0xDCE1E5), edge: Color(hex: 0xC9D1D8),
                     ok: Color(hex: 0x1C7C4A), okBg: Color(hex: 0xE2F4EA), warn: Color(hex: 0x8A5200), warnBg: Color(hex: 0xFDF0DC), bad: Color(hex: 0xB3261E), badBg: Color(hex: 0xFBE7E5), off: Color(hex: 0xAFC4D3), dark: false)
let DarkP = Palette(blue: Color(hex: 0x2F9BE6), blue2: Color(hex: 0x4AAAEE), deep: Color(hex: 0x9FD3F7), ink: Color(hex: 0xE6EEF4), tint: Color(hex: 0x12324A),
                    bg: Color(hex: 0x0D1318), card: Color(hex: 0x161E25), text: Color(hex: 0xE8EDF1), muted: Color(hex: 0x9CA8B2), line: Color(hex: 0x26323C), edge: Color(hex: 0x3A4955),
                    ok: Color(hex: 0x5FD08F), okBg: Color(hex: 0x123222), warn: Color(hex: 0xF2B35B), warnBg: Color(hex: 0x3A2A10), bad: Color(hex: 0xFF8A80), badBg: Color(hex: 0x3D1715), off: Color(hex: 0x33485A), dark: true)
/// Bright sun: black text and strong edges for reading outdoors.
let SunP = Palette(blue: Color(hex: 0x0050A0), blue2: Color(hex: 0x003E7E), deep: Color(hex: 0x002A57), ink: .black, tint: Color(hex: 0xDCEBFF),
                   bg: .white, card: .white, text: .black, muted: Color(hex: 0x1E1E1E), line: .black, edge: .black,
                   ok: Color(hex: 0x00562A), okBg: Color(hex: 0xD8F5E3), warn: Color(hex: 0x6B3A00), warnBg: Color(hex: 0xFFE8C2), bad: Color(hex: 0x8C0000), badBg: Color(hex: 0xFFDCDC), off: Color(hex: 0x8A99A6), dark: false)

/// The colours chosen on the Me page: "" follows the phone, or "light", "dark", "sun".
enum Look {
    static var mode = ""
    static var systemDark = false
    static var p: Palette {
        switch mode { case "light": return LightP; case "dark": return DarkP; case "sun": return SunP; default: return systemDark ? DarkP : LightP }
    }
}
/// The current colours.
var C: Palette { Look.p }

let Amber = Color(hex: 0xE39A1E)
let RedC = Color(hex: 0xD23C32)
let GreenC = Color(hex: 0x1C9C5A)

let DeepBrush = LinearGradient(colors: [Color(hex: 0x0A78C2), Color(hex: 0x054F81), Color(hex: 0x04263F)], startPoint: .topLeading, endPoint: .bottomTrailing)
let BlueBrush = LinearGradient(colors: [Color(hex: 0x1A8BD6), Color(hex: 0x0A78C2), Color(hex: 0x076DB1)], startPoint: .topLeading, endPoint: .bottomTrailing)
let OkBrush = LinearGradient(colors: [Color(hex: 0x25A262), Color(hex: 0x1C7C4A)], startPoint: .topLeading, endPoint: .bottomTrailing)
let LightBlue = Color(hex: 0xCFE8FA)

/// Montserrat, the RAFIKI font, bundled with the app.
enum Fonts {
    static var ready = false
    static func register() {
        guard !ready, let u = Bundle.main.url(forResource: "Montserrat", withExtension: "ttf") else { return }
        CTFontManagerRegisterFontsForURL(u as CFURL, .process, nil)
        ready = true
    }
}

extension Font {
    static func mont(_ size: CGFloat, _ weight: Font.Weight = .medium) -> Font {
        Fonts.ready ? .custom("Montserrat", size: size, relativeTo: .body).weight(weight) : .system(size: size, weight: weight)
    }
    static var hLarge: Font { mont(28, .heavy) }
    static var hMedium: Font { mont(24, .heavy) }
    static var tLarge: Font { mont(20, .heavy) }
    static var tMedium: Font { mont(16, .bold) }
    static var tSmall: Font { mont(14, .bold) }
    static var bLarge: Font { mont(16) }
    static var bMedium: Font { mont(14) }
    static var bSmall: Font { mont(12) }
    static var lLarge: Font { mont(15, .bold) }
    static var lMedium: Font { mont(12, .bold) }
    static var lSmall: Font { mont(11, .bold) }
}
