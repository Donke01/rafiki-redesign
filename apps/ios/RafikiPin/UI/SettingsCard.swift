import SwiftUI
import UIKit

// Me › Settings (Android 2.14–2.15): account, this phone (security and alerts, display), help and about, each row
// opening its own page (SettingsPages.swift). Sign out is last, on its own.

/// RAFIKI Pin's privacy policy (the same page for every app).
let PRIVACY_URL = "https://pin.rafikioutreach.org/pin-privacy"

struct SettingsCard: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var nc = NotifCenter.shared
    var body: some View {
        let me = m.me
        CardV {
            Text(t("Settings", "Mipangilio")).font(.tLarge).foregroundColor(C.ink).frame(maxWidth: .infinity)
            // most used first: who you are, then keeping the app safe and telling you things, then how it looks, then help
            MeGroupLabel(t("Account", "Akaunti"))
            SettingsMondayRow()
            SetRow(icon: "person", hue: "blue", title: t("Personal details", "Taarifa zangu"),
                   sub: [me.nick.isEmpty ? m.prefs.name : me.nick, me.phone].filter { !$0.isEmpty }.joined(separator: " · ")) { m.openSettings("profile") }
            SetRow(icon: "lock", hue: "purple", title: me.kind == "monday" ? t("Sign-in", "Kuingia") : t("Change my PIN", "Badilisha PIN yangu"), sub: kindText(me.kind)) { m.openSettings("pin") }
            MeGroupLabel(t("Security and alerts", "Usalama na arifa"))
            SettingsBioRow()
            SetRow(icon: "bell", hue: "amber", title: t("Notifications", "Arifa"),
                   sub: m.prefs.phoneAlerts && nc.allowed ? t("On this phone too", "Pia kwenye simu hii") : t("In the app only", "Ndani ya programu tu")) { m.openSettings("notify") }
            MeGroupLabel(t("Display", "Mwonekano"))
            SetRow(icon: "sun", hue: "amber", title: t("Appearance", "Mwonekano"), sub: lookText()) { m.openSettings("look") }
            SetRow(icon: "globe", hue: "blue", title: t("Language", "Lugha"), sub: Lang.sw ? "Kiswahili" : "English") { m.openSettings("lang") }
            SetRow(icon: "nav", hue: "green", title: t("Distances", "Umbali"), sub: m.prefs.miles ? t("Miles", "Maili") : t("Kilometres (km)", "Kilomita (km)")) { m.openSettings("units") }
            MeGroupLabel(t("Help and support", "Msaada"))
            SetRow(icon: "search", hue: "teal", title: t("Help and FAQs", "Msaada na maswali"), sub: t("Pinning, visits, no signal, updates", "Kuweka mahali, ziara, bila mtandao, masasisho")) { m.openSettings("help") }
            SetRow(icon: "chat", hue: "green", title: t("Contact the office", "Wasiliana na ofisi"), sub: MeMail.office) { MeMail.open("RAFIKI Pin: " + m.prefs.name) }
            SetRow(icon: "alert", hue: "coral", title: t("Report a problem with the app", "Ripoti tatizo la programu"), sub: t("Sends the app version with your message", "Inatuma toleo la programu pamoja na ujumbe wako")) {
                let d = UIDevice.current
                MeMail.open("RAFIKI Pin problem", "\n\nWhat happened:\n\n\n---\n\(m.prefs.name) · iPhone app \(AppVer.name) (\(AppVer.build)) · \(d.model) · iOS \(d.systemVersion)")
            }
            MeGroupLabel(t("About", "Kuhusu"))
            SetRow(icon: "drop", hue: "blue", title: t("About RAFIKI Pin", "Kuhusu RAFIKI Pin"), sub: t("Version ", "Toleo ") + AppVer.name) { m.openSettings("about") }
            SetRow(icon: "lock", hue: "slate", title: t("Privacy policy", "Sera ya faragha"), sub: "") { Share.open(PRIVACY_URL) }
            SetRow(icon: "clip", hue: "slate", title: t("Terms of use", "Masharti ya matumizi"), sub: "") { MeMail.web("/terms-of-use") }
            Rectangle().fill(C.line).frame(height: 1).padding(.top, 12).padding(.bottom, 4)
            SetRow(icon: "undo", hue: "coral", title: t("Sign out of this phone", "Toka kwenye simu hii"), sub: t("You’ll need your PIN to come back in", "Utahitaji PIN yako kurudi")) { m.openSettings("signout") }
        }
        .padding(.top, 14)
    }

    private func kindText(_ k: String) -> String {
        switch k {
        case "monday": return t("Signed in with monday.com", "Umeingia kwa monday.com")
        case "app": return t("Your own PIN", "PIN yako mwenyewe")
        case "cloudflare": return t("Set by the office", "Imewekwa na ofisi")
        default: return t("Shared team PIN", "PIN ya timu")
        }
    }
}

/// "Light · same as phone" under Appearance.
func lookText() -> String {
    switch Look.mode {
    case "": return t("Same as phone", "Kama simu")
    case "dark": return t("Dark", "Giza")
    case "sun": return t("Sun", "Jua")
    default: return t("Light", "Mwanga")
    }
}

/// One row of Settings: a coloured icon, a name, a line under it, and a chevron.
struct SetRow: View {
    let icon: String; let hue: String; let title: String; let sub: String; let action: () -> Void
    var body: some View {
        let hu = meHue(hue)
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 12) {
                MeTile(icon: icon, hue: hu)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.tSmall).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                    if !sub.isEmpty { Text(sub).font(.bSmall).foregroundColor(C.muted).lineLimit(1).truncationMode(.tail) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                Icon("chev", size: 18, color: C.muted)
            }.padding(.vertical, 10).padding(.horizontal, 2).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

/// Settings › Account › Your name on monday.com: one row; the steps are on their own page (not when signed in with monday.com).
private struct SettingsMondayRow: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        if let snap = m.snap, m.prefs.session.isEmpty {
            let linked = !snap.linked.isEmpty
            Button { Haptic.tap(); m.openSettings("monday") } label: {
                HStack(spacing: 12) {
                    NetImage(url: "https://www.google.com/s2/favicons?domain=monday.com&sz=64") { Icon("link", size: 22, color: Color(hex: 0x6161FF)) }
                        .frame(width: 24, height: 24).clipShape(RoundedRectangle(cornerRadius: 6))
                        .frame(width: 40, height: 40).background(Color(hex: 0x6161FF).opacity(0.10)).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    VStack(alignment: .leading, spacing: 1) {
                        Text(t("Your name on monday.com", "Jina lako kwenye monday.com")).font(.tSmall).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                        Text(linked ? t("Connected as \(snap.linked)", "Imeunganishwa kama \(snap.linked)") : snap.who.isEmpty ? t("Changes go in under the shared account", "Mabadiliko yanaingia kwa akaunti ya pamoja") : t("Not connected · takes 20 seconds", "Haijaunganishwa · sekunde 20"))
                            .font(.bSmall).foregroundColor(linked ? C.ok : C.muted).lineLimit(1)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    Icon("chev", size: 18, color: C.muted)
                }.padding(.vertical, 10).padding(.horizontal, 2).contentShape(Rectangle())
            }.buttonStyle(.plain)
        }
    }
}

/// Settings › Security › Unlock with Face ID: a switch right in the row.
private struct SettingsBioRow: View {
    @EnvironmentObject var m: AppModel
    @State private var bio = false
    @State private var msg: String?
    var body: some View {
        if Bio.available || bio {
            HStack(spacing: 12) {
                IconTile(icon: Bio.face ? "face" : "finger", tint: C.hue("due").fg, bg: C.hue("due").bg, size: 40)
                VStack(alignment: .leading, spacing: 1) {
                    Text(Bio.face ? t("Unlock with Face ID", "Fungua kwa Face ID") : t("Unlock with Touch ID", "Fungua kwa Touch ID")).font(.tSmall).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                    Text(msg ?? (bio ? t("On · asked when the app opens and after a minute away", "Imewashwa · inaulizwa programu ikifunguliwa na baada ya dakika moja") : t("Off · keeps the app private if someone picks up your phone", "Imezimwa · inalinda programu mtu akishika simu yako")))
                        .font(.bSmall).foregroundColor(msg != nil ? C.bad : C.muted).fixedSize(horizontal: false, vertical: true)
                }.frame(maxWidth: .infinity, alignment: .leading)
                Toggle2(on: bio, label: bio ? t("Turn off", "Zima") : t("Turn on", "Washa")) {
                    if bio { bio = false; m.prefs.bio = false; msg = nil; return }
                    Task { if await Bio.ask(t("Turn on Face ID unlock", "Washa kufungua kwa Face ID")) { bio = true; m.prefs.bio = true; msg = nil; Haptic.ok() } else { msg = t("Not turned on.", "Haijawashwa.") } }
                }
            }
            .padding(.vertical, 10).padding(.horizontal, 2)
            .onAppear { bio = m.prefs.bio }
        }
    }
}

/// An icon tile: one of the app's icons, or else a system symbol by name ("bell").
struct MeTile: View {
    let icon: String; let hue: Hue; var size: CGFloat = 40
    var body: some View {
        if Ic.map[icon] != nil { IconTile(icon: icon, tint: hue.fg, bg: hue.bg, size: size) }
        else { SFIcon(icon, size: size * 0.55, color: hue.fg).frame(width: size, height: size).background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(hue.bg)) }
    }
}
