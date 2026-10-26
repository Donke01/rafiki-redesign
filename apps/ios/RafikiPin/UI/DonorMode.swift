import SwiftUI
import UIKit

// RAFIKI Pin in donor mode (Android's DonorMode.kt): a calm, donor-only app. None of the staff tabs or the team's data
// are here: only the donor's own projects from /api/donor (see Data/Donor.swift).

let DONOR_CONTACT = "info@rafikioutreach.org"
let DONOR_GIVE = "https://www.rafikioutreach.org/donate"

/// Words for a project's state, as a donor reads them.
func donorStateName(_ k: String) -> String {
    switch k {
    case "working": return t("Working well", "Inafanya kazi vizuri"); case "repairing": return t("Being repaired", "Inarekebishwa")
    case "notworking": return t("Waiting for a repair", "Inasubiri matengenezo"); case "building": return t("Being built", "Inajengwa")
    case "planned": return t("Planned", "Imepangwa"); default: return t("Completed", "Imekamilika")
    }
}
func donorStateSay(_ p: DProject) -> String {
    let at = p.healthAt.isEmpty ? "" : Dates.weekday(p.healthAt)
    switch p.state {
    case "working": return t("Water is flowing and people are using it. Last checked \(at).", "Maji yanatoka na watu wanayatumia. Imekaguliwa mwisho \(at).")
    case "repairing": return t("Our team found a problem and is fixing it now.", "Timu yetu iligundua tatizo na inalirekebisha sasa.")
    case "notworking": return t("It stopped working at the last check (\(at)). Our team knows and is arranging the repair.", "Iliacha kufanya kazi katika ukaguzi wa mwisho (\(at)). Timu yetu inajua na inapanga matengenezo.")
    case "building":
        if let st = p.visits.first(where: { !$0.stage.isEmpty })?.stage { return t("Building is under way: ", "Ujenzi unaendelea: ") + donorStage(st).lowercased() + "." }
        return t("Building is under way.", "Ujenzi unaendelea.")
    case "planned": return t("This project is planned. We’ll share news as soon as work starts.", "Mradi huu umepangwa. Tutakujulisha kazi ikianza.")
    default:
        if !p.done.isEmpty { return t("Finished \(Dates.day(p.done)).", "Ilikamilika \(Dates.day(p.done)).") }
        return p.year > 0 ? t("Finished in \(p.year).", "Ilikamilika \(p.year).") : t("Finished.", "Imekamilika.")
    }
}
private func stateTone(_ k: String) -> (Color, Color) {
    switch k {
    case "working": return (C.ok, C.okBg); case "repairing": return (C.warn, C.warnBg); case "notworking": return (C.bad, C.badBg)
    case "building": return (C.named("purple").fg, C.named("purple").bg); default: return (C.blue2, C.tint)
    }
}
private func kindOf(_ p: DProject) -> String {
    switch p.type {
    case "Borehole well": return t("Borehole well", "Kisima kirefu"); case "Water collection": return t("Rainwater tank", "Tangi la maji ya mvua")
    case "School": return t("School", "Shule"); case "Health": return t("Health centre", "Kituo cha afya")
    case "Orphanage": return t("Children’s home", "Makao ya watoto"); case "Farming": return t("Farming", "Kilimo"); default: return p.type
    }
}
private func iconOf(_ p: DProject) -> String { p.water ? "drop" : p.cat == "school" ? "school" : p.cat == "children" ? "house" : p.cat == "health" ? "cross" : "spark" }
private func cover(_ d: DonorState, _ p: DProject) -> String? { p.photos.first.map { d.board($0) } ?? p.gallery.first.map { d.photo($0.id) } }
private func donorMail(_ subject: String) { Links.mail(subject, to: DONOR_CONTACT) }

// ---- getting in ----------------------------------------------------------------------------------------------
/// "I'm a donor": paste the private link or type the short code.
struct DonorSignIn: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var d = DonorState.shared
    @State private var typed = ""
    @State private var msg: (String, String)?
    @State private var busy = false
    @State private var askStaff = false

    func go() {
        if busy { return }
        if m.signedIn { askStaff = true; return }
        busy = true; msg = ("", t("Checking…", "Inakagua…"))
        Task { let err = await d.signIn(typed); busy = false; msg = err.map { ("bad", $0) } }
    }
    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                Drop(size: 84, color: .blue, mood: .smile)
                OnbWordmark(height: 26).padding(.top, 14)
                Text(t("I’m a donor", "Mimi ni mfadhili")).font(.hMedium).foregroundColor(C.ink).multilineTextAlignment(.center).padding(.top, 14)
                Text(t("See the projects you funded: photos, visits and thank-you messages from the communities.", "Ona miradi uliyofadhili: picha, ziara na ujumbe wa shukrani kutoka kwa jamii."))
                    .font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).padding(.top, 4)
                Field(t("Your donor code or private link", "Msimbo wako wa mfadhili au kiungo cha siri"), $typed, placeholder: "K7QM4-XR9TB").padding(.top, 10).accessibilityIdentifier("donor_code")
                    .onChange(of: typed) { _ in if !busy { msg = nil } }
                Text(t("It’s in the message RAFIKI sent you. Paste the whole link, or type the 10-letter code.", "Iko kwenye ujumbe RAFIKI ilikutumia. Bandika kiungo kizima, au andika msimbo wa herufi 10."))
                    .font(.bSmall).foregroundColor(C.muted).wide().padding(.top, 6)
                if case let (tone, text)? = msg { Msg(text, tone) }
                Btn(busy ? t("Checking…", "Inakagua…") : t("Continue", "Endelea"), enabled: !typed.trimmingCharacters(in: .whitespaces).isEmpty && !busy, wide: true) { go() }
                    .padding(.top, 16).accessibilityIdentifier("donor_go")
                Button { donorMail("My RAFIKI donor code") } label: {
                    HStack(spacing: 6) { Icon("mail", size: 18, color: C.blue2); Text(t("No code? Write to us", "Huna msimbo? Tuandikie")).font(.lLarge).foregroundColor(C.blue2) }.frame(minHeight: 44)
                }.buttonStyle(.plain).padding(.top, 8)
                Button { d.entry = false } label: { Text(t("I’m RAFIKI staff", "Mimi ni mfanyakazi wa RAFIKI")).font(.lLarge).foregroundColor(C.blue2).frame(minHeight: 44) }
                    .buttonStyle(.plain).accessibilityIdentifier("donor_staff")
                LangSwitch().padding(.top, 12)
            }
            .frame(maxWidth: 420).padding(.horizontal, 24).padding(.vertical, 28).frame(maxWidth: .infinity)
        }
        .background(C.bg.ignoresSafeArea())
        .confirmationDialog(t("This phone is signed in as RAFIKI staff", "Simu hii imeingia kama mfanyakazi wa RAFIKI"), isPresented: $askStaff, titleVisibility: .visible) {
            Button(t("Sign out and continue", "Toka na uendelee"), role: .destructive) { m.signOut(); DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { go() } }
            Button(t("Keep the staff sign-in", "Baki kama mfanyakazi"), role: .cancel) {}
        } message: {
            Text(t("A phone can be used either by staff or by a donor. Sign out of the staff app to continue as a donor?", "Simu inaweza kutumiwa na mfanyakazi au na mfadhili. Toka kwenye programu ya wafanyakazi ili uendelee kama mfadhili?"))
        }
        .onAppear { if !d.prefill.isEmpty { typed = d.prefill; d.prefill = ""; go() } }
    }
}

/// Switched off by RAFIKI (revoked or replaced): signed out, with a kind word.
struct DonorGone: View {
    @ObservedObject var d = DonorState.shared
    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                IconTile(icon: "heart", tint: C.named("coral").fg, bg: C.named("coral").bg, size: 84)
                Text(t("Your donor access has been switched off", "Ufikiaji wako wa mfadhili umezimwa")).font(.hMedium).foregroundColor(C.ink).multilineTextAlignment(.center).padding(.top, 20)
                Text(t("Thank you for everything you’ve given. This link or code isn’t active any more; it may have been replaced with a new one.", "Asante kwa yote uliyotoa. Kiungo au msimbo huu haufanyi kazi tena; huenda umebadilishwa na mpya."))
                    .font(.bLarge).foregroundColor(C.text).multilineTextAlignment(.center).padding(.top, 10)
                Text(t("Write to us and we’ll gladly send you a fresh one.", "Tuandikie na tutafurahi kukutumia mpya.")).font(.bLarge).foregroundColor(C.text).multilineTextAlignment(.center).padding(.top, 8)
                Btn(t("Write to RAFIKI", "Iandikie RAFIKI"), icon: "mail", wide: true) { donorMail("My RAFIKI donor access") }.padding(.top, 22)
                Btn(t("Sign in again", "Ingia tena"), light: true, wide: true) { d.dismissGone(); d.entry = true }.padding(.top, 10)
                Btn(t("I’m RAFIKI staff", "Mimi ni mfanyakazi wa RAFIKI"), light: true, small: true) { d.dismissGone() }.padding(.top, 10)
            }.frame(maxWidth: 420).padding(.horizontal, 24).padding(.vertical, 48).frame(maxWidth: .infinity)
        }.background(C.bg.ignoresSafeArea())
    }
}

// ---- the donor app -------------------------------------------------------------------------------------------
/// Home, Updates and Settings, with a project on top.
struct DonorApp: View {
    @ObservedObject var d = DonorState.shared
    @State private var tab = "home"
    @State private var pid: String?
    var body: some View {
        let v = d.view
        let news = v.map { vv in vv.updates.filter { !d.seen.isEmpty && $0.at > d.seen }.count } ?? 0
        VStack(spacing: 0) {
            DonorTop()
            ZStack {
                if let v {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            if let id = pid, let p = v.project(id) { DonorProjectView(p: p) { withAnimation(.easeOut(duration: 0.2)) { pid = nil } } }
                            else if tab == "updates" { DonorUpdates(v: v) { pid = $0 } }
                            else if tab == "settings" { DonorSettings(v: v) }
                            else { DonorHome(v: v) { pid = $0 } }
                        }.padding(.horizontal, 16).padding(.top, 6).padding(.bottom, 24)
                    }
                    .id(pid ?? tab)
                    .refreshable { await d.refresh() }
                } else {
                    Text(t("Loading…", "Inapakia…")).foregroundColor(C.muted).frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }.frame(maxHeight: .infinity)
            DonorTabs(on: pid != nil ? "" : tab, news: news) { k in tab = k; pid = nil }
        }
        .background(AppBackground().ignoresSafeArea())
        .task {
            await d.refresh()
            while !Task.isCancelled { try? await Task.sleep(nanoseconds: 5 * 60 * 1_000_000_000); await d.refresh(bg: true) }
        }
        .onChange(of: d.goProject) { g in if let g { pid = g; d.goProject = nil } }
        .onChange(of: tab) { k in if k == "updates" { DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { d.markSeen() } } }
    }
}

private struct DonorTop: View {
    @EnvironmentObject var m: AppModel
    @State private var said: String?
    var body: some View {
        HStack(spacing: 8) {
            Image(C.dark ? "RafikiLogoLight" : "RafikiLogoDark").resizable().scaledToFit().frame(height: 22).accessibilityLabel("RAFIKI")
            let co = C.named("coral")
            HStack(spacing: 4) { Icon("heart", size: 13, color: co.fg); Text(t("DONOR", "MFADHILI")).font(.mont(11, .heavy)).tracking(0.6).foregroundColor(co.fg).lineLimit(1) }
                .padding(.horizontal, 9).padding(.vertical, 4).background(co.bg).clipShape(Capsule())
            Spacer()
            // text size in one tap: Normal → Large → Larger
            Button {
                Haptic.tap()
                let next = m.prefs.textSize == "" ? "l" : m.prefs.textSize == "l" ? "xl" : ""
                m.prefs.textSize = next; m.look += 1
                m.say(next == "l" ? t("Large text", "Maandishi makubwa") : next == "xl" ? t("Larger text", "Maandishi makubwa zaidi") : t("Normal text", "Maandishi ya kawaida"))
            } label: {
                Text("Aa").font(.mont(17, .heavy)).foregroundColor(C.ink).frame(width: 46, height: 46).background(C.card)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line, lineWidth: 1))
            }.buttonStyle(Press()).accessibilityLabel(t("Text size", "Ukubwa wa maandishi")).accessibilityIdentifier("donor_aa")
            Button { Share.open(DONOR_GIVE) } label: {
                HStack(spacing: 6) { Icon("heart", size: 16, color: .white); Text(t("Give again", "Changia tena")).font(.mont(13, .bold)).foregroundColor(.white).lineLimit(1) }
                    .padding(.horizontal, 14).frame(minHeight: 40).background(BlueBrush).clipShape(Capsule())
            }.buttonStyle(Press()).accessibilityIdentifier("donor_give")
        }.padding(.horizontal, 14).frame(minHeight: 56)
    }
}

private struct DonorTabs: View {
    let on: String; let news: Int; let pick: (String) -> Void
    var body: some View {
        HStack(spacing: 0) {
            ForEach([("home", t("Home", "Nyumbani"), "house"), ("updates", t("Updates", "Habari"), "bell"), ("settings", t("Settings", "Mipangilio"), "cog")], id: \.0) { k, label, icon in
                let sel = on == k
                Button { Haptic.tap(); pick(k) } label: {
                    VStack(spacing: 3) {
                        Icon(icon, size: 23, color: sel ? C.blue2 : C.muted).frame(width: 56, height: 30).background(sel ? C.tint : Color.clear).clipShape(Capsule())
                            .overlay(alignment: .topTrailing) {
                                if k == "updates" && news > 0 { Text("\(news)").font(.mont(10, .bold)).foregroundColor(.white).padding(.horizontal, 5).background(C.named("coral").fg).clipShape(Capsule()) }
                            }
                        Text(label).font(.lSmall).foregroundColor(sel ? C.blue2 : C.muted).lineLimit(1)
                    }.frame(maxWidth: .infinity).padding(.vertical, 6).contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityAddTraits(sel ? .isSelected : []).accessibilityIdentifier("dtab_\(k)")
            }
        }
        .frame(height: 68).background(C.card).clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).stroke(C.line, lineWidth: 1))
        .shadow(color: Color(hex: 0x144678).opacity(0.2), radius: 16, y: 4)
        .padding(.horizontal, 12).padding(.top, 6).padding(.bottom, 10)
    }
}

private struct SectionHead: View {
    let text: String; var extra = ""
    var body: some View {
        VStack(spacing: 2) {
            Text(text).font(.mont(22, .heavy)).foregroundColor(C.ink).multilineTextAlignment(.center)
            if !extra.isEmpty { Text(extra).font(.lMedium).foregroundColor(C.muted).multilineTextAlignment(.center) }
        }.frame(maxWidth: .infinity).padding(.top, 24).padding(.bottom, 12).padding(.horizontal, 2)
    }
}

private struct DonorHome: View {
    @ObservedObject var d = DonorState.shared
    let v: DonorView; let open: (String) -> Void
    var body: some View {
        let org = [v.group || v.viewer != v.name ? v.name : "", !v.org.isEmpty && v.org != v.name ? v.org : ""].filter { !$0.isEmpty }.joined(separator: " · ")
        let allWater = v.nWater > 0 && v.nWater == v.nProjects
        let hp = v.projects.first { cover(d, $0) != nil }
        VStack(spacing: 0) {
            HStack(spacing: 6) { Icon("lock", size: 13, color: .white); Text(t("JUST FOR YOU", "KWA AJILI YAKO TU")).font(.mont(11, .heavy)).tracking(0.8).foregroundColor(.white).lineLimit(1) }
                .padding(.horizontal, 12).padding(.vertical, 5).background(Color.white.opacity(0.16)).clipShape(Capsule())
            Text(t("Hello, ", "Habari, ") + (v.viewer.trimmingCharacters(in: .whitespaces).split(separator: " ").first.map(String.init) ?? "")).font(.mont(32, .heavy)).foregroundColor(.white)
                .multilineTextAlignment(.center).shadow(color: .black.opacity(0.25), radius: 9, y: 2).padding(.top, 14)
            Text(org.isEmpty ? t("Thank you for giving with RAFIKI", "Asante kwa kuchangia na RAFIKI") : org).font(.mont(14, .semibold)).foregroundColor(.white.opacity(0.85)).multilineTextAlignment(.center).padding(.top, 2)
            if v.people > 0 {
                Text((v.nProjects == 1 ? t("Your project serves about ", "Mradi wako unahudumia takriban ") : t("Your \(v.nProjects)\u{00A0}projects serve about ", "Miradi yako \(v.nProjects) inahudumia takriban ")) + (donorNum(v.people) + t(" people.", " watu.")).nb)
                    .font(.mont(19, .bold)).foregroundColor(.white).multilineTextAlignment(.center).frame(maxWidth: 420).padding(.top, 14)
            }
            HStack(spacing: 8) {
                HeroTile(value: v.nProjects, label: allWater ? (v.nWater == 1 ? t("well", "kisima") : t("wells", "visima")) : (v.nProjects == 1 ? t("project", "mradi") : t("projects", "miradi")))
                HeroTile(value: v.people, label: t("people served", "wanufaika"))
                HeroTile(value: v.visitsYear, label: t("visits this year", "ziara mwaka huu"))
            }.fixedSize(horizontal: false, vertical: true).frame(maxWidth: 560).padding(.top, 18)
        }
        .padding(.horizontal, 18).padding(.vertical, 26).frame(maxWidth: .infinity)
        .background {
            ZStack(alignment: .bottom) {
                DeepBrush
                if let hp {
                    NetImage(url: cover(d, hp)) { Color.clear }.modifier(Drift()).clipped()
                    LinearGradient(colors: [Color(hex: 0x04263F).opacity(0.55), Color(hex: 0x054F81).opacity(0.72), Color(hex: 0x04263F).opacity(0.94)], startPoint: .top, endPoint: .bottom)
                }
                Waves(back: Color.white.opacity(0.08), front: Color(hex: 0x6BBDF2).opacity(0.12)).frame(height: 70)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .shadow(color: Color(hex: 0x04263F).opacity(0.2), radius: 16, y: 10)
        .accessibilityIdentifier("donor_hero")
        if v.projects.contains(where: { $0.gps != nil }) {
            SectionHead(text: t("Your projects on the map", "Miradi yako kwenye ramani"))
            DonorMap(projects: v.projects, withCard: true, open: open)
        }
        SectionHead(text: v.projects.count == 1 ? t("Your project", "Mradi wako") : t("Your projects", "Miradi yako"), extra: "\(v.projects.count)")
        if v.projects.isEmpty { EmptyDrop(text: t("Your projects will appear here soon.", "Miradi yako itaonekana hapa hivi karibuni.")) }
        ForEach(Array(v.projects.enumerated()), id: \.element.id) { i, p in
            DonorCard(p: p, index: i) { open(p.id) }.padding(.bottom, 18).rise(min(i, 5))
        }
    }
}

private struct HeroTile: View {
    let value: Int; let label: String
    var body: some View {
        VStack(spacing: 2) {
            DonorCount(value: value, font: .mont(24, .heavy), color: .white)
            Text(label).font(.mont(12, .semibold)).foregroundColor(.white.opacity(0.9)).multilineTextAlignment(.center).lineLimit(2).minimumScaleFactor(0.8)
        }
        .padding(.horizontal, 8).padding(.vertical, 12).frame(maxWidth: .infinity, minHeight: 82)
        .background(Color.white.opacity(0.12)).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Color.white.opacity(0.22), lineWidth: 1))
    }
}

/// The status on the photo: frosted white, with a dot that breathes while it's working or being fixed.
private struct StateBadge: View {
    let p: DProject
    var body: some View {
        let (fg, _) = stateTone(p.state)
        HStack(spacing: 7) { BreathDot(color: fg, live: p.state == "working" || p.state == "repairing"); Text(donorStateName(p.state)).font(.mont(13, .bold)).foregroundColor(fg).lineLimit(1) }
            .padding(.leading, 11).padding(.trailing, 13).padding(.vertical, 6).background(Color.white.opacity(0.93)).clipShape(Capsule()).shadow(color: .black.opacity(0.12), radius: 4, y: 2)
    }
}

private struct Fact: View {
    let value: String; let label: String
    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(value).font(.mont(15, .bold)).foregroundColor(C.ink).lineLimit(1).minimumScaleFactor(0.6)
            Text(label).font(.mont(11, .semibold)).foregroundColor(C.muted).lineLimit(2)
        }
        .padding(.horizontal, 10).padding(.vertical, 8).frame(maxWidth: .infinity, alignment: .leading)
        .background(C.card).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line, lineWidth: 1))
    }
}

/// No photo yet: a deep blue scene with the project's icon floating, rings spreading, bubbles and waves.
private struct NoPhotoScene: View {
    let p: DProject
    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(hex: 0x3EA6EC), Color(hex: 0x0A78C2), Color(hex: 0x04263F)], startPoint: .topLeading, endPoint: .bottomTrailing)
            Bubbles()
            VStack { Spacer(); Waves(back: Color.white.opacity(0.09), front: Color(hex: 0x6BBDF2).opacity(0.14)).frame(height: 90) }
            VStack {
                ZStack { Ripples(); Icon(iconOf(p), size: 60, color: Color.white.opacity(0.4)).modifier(Bob()) }.frame(width: 84, height: 84).padding(.top, 46)
                Spacer()
            }
        }
    }
}

private struct PStat { let n: Int; let suffix: String; let count: Bool; let label: String }

/// A project, the same card as the web donor page (designed in Figma): the photo with the name on it, three numbers,
/// the last check as a living line, and one round arrow to open it. Everything centred.
private struct DonorCard: View {
    @ObservedObject var d = DonorState.shared
    let p: DProject; var index = 0; let open: () -> Void
    var stats: [PStat] {
        var a: [PStat] = []
        if p.people > 0 { a.append(PStat(n: p.people, suffix: "", count: true, label: t("people served", "wanaohudumiwa"))) }
        if p.pupils > 0 { a.append(PStat(n: p.pupils, suffix: "", count: true, label: t("pupils", "wanafunzi"))) }
        if p.patients > 0 { a.append(PStat(n: p.patients, suffix: "", count: true, label: t("patients a week", "wagonjwa kwa wiki"))) }
        if p.children > 0 { a.append(PStat(n: p.children, suffix: "", count: true, label: t("children", "watoto"))) }
        if p.litresPerDay > 0 { a.append(PStat(n: p.litresPerDay, suffix: "\u{00A0}L", count: true, label: t("water a day", "maji kwa siku"))) }
        if p.year > 0 { a.append(PStat(n: p.year, suffix: "", count: false, label: p.status == "Completed" ? t("completed", "ilikamilika") : t("year", "mwaka"))) }
        return Array(a.prefix(3))
    }
    var body: some View {
        let (fg, bg) = stateTone(p.state)
        let nPics = p.photos.count + p.gallery.count
        let st = stats
        Button(action: open) {
            VStack(spacing: 0) {
                Color.clear.aspectRatio(358.0 / 250.0, contentMode: .fit)
                    .overlay(NetImage(url: cover(d, p)) { NoPhotoScene(p: p) }.modifier(Drift()))
                    .overlay(LinearGradient(stops: [.init(color: Color(hex: 0x04263F).opacity(0), location: 0.35), .init(color: Color(hex: 0x021727).opacity(0.88), location: 1)], startPoint: .top, endPoint: .bottom))
                    .modifier(Sheen(delay: 0.5 + Double(min(index, 5)) * 0.09))
                    .clipped()
                    .overlay(alignment: .topLeading) { StateBadge(p: p).padding(16) }
                    .overlay(alignment: .topTrailing) {
                        if nPics > 1 {
                            HStack(spacing: 6) { Icon("camera", size: 15, color: .white); Text("\(nPics)").font(.mont(13, .bold)).foregroundColor(.white) }
                                .padding(.leading, 10).padding(.trailing, 12).padding(.vertical, 6).background(Color(hex: 0x021727).opacity(0.55)).clipShape(Capsule()).padding(16)
                                .accessibilityElement(children: .ignore).accessibilityLabel(t("\(nPics) photos", "Picha \(nPics)"))
                        }
                    }
                    .overlay(alignment: .bottom) {
                        VStack(spacing: 4) {
                            Text(kindOf(p).uppercased()).font(.mont(12, .bold)).tracking(1).foregroundColor(.white.opacity(0.85)).multilineTextAlignment(.center)
                            Text(p.title).font(.mont(25, .heavy)).foregroundColor(.white).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true).shadow(color: .black.opacity(0.25), radius: 6)
                            if !p.place.isEmpty {
                                HStack(spacing: 5) { Icon("pin", size: 15, color: .white.opacity(0.9)); Text(p.place.nb).font(.mont(14, .semibold)).foregroundColor(.white.opacity(0.9)).lineLimit(1).minimumScaleFactor(0.75) }
                            }
                        }.padding(.horizontal, 20).padding(.bottom, 18)
                    }
                VStack(spacing: 16) {
                    if !st.isEmpty {
                        HStack(spacing: 0) {
                            ForEach(Array(st.enumerated()), id: \.offset) { k, s in
                                if k > 0 { Rectangle().fill(C.line).frame(width: 1) }
                                VStack(spacing: 2) {
                                    DonorCount(value: s.n, suffix: s.suffix, count: s.count)
                                    Text(s.label).font(.mont(13, .medium)).foregroundColor(C.muted).multilineTextAlignment(.center).lineLimit(2).minimumScaleFactor(0.85)
                                }.frame(maxWidth: .infinity).padding(.horizontal, 4)
                            }
                        }.fixedSize(horizontal: false, vertical: true)
                    }
                    if !p.health.isEmpty && !p.healthAt.isEmpty {
                        let live = p.state == "working" && p.water
                        let what = live ? t("Water flowing", "Maji yanatoka") : donorStateName(p.state)
                        HStack(spacing: 10) {
                            StateMark(k: p.state, color: donorStateColor(p.state))
                            Text(what.nb + " · " + (t("checked ", "imekaguliwa ") + Dates.day(p.healthAt)).nb).font(.mont(14, .semibold)).foregroundColor(fg).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.horizontal, 12).padding(.vertical, 10).frame(maxWidth: .infinity)
                        .background(ZStack { bg; if live { Waves(back: C.ok.opacity(0.10), front: C.blue.opacity(0.08)) } })
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                }.padding(.horizontal, 20).padding(.top, 18).padding(.bottom, 16)
                Rectangle().fill(C.line).frame(height: 1)
                HStack(spacing: 12) {
                    Text(t("Photos, visits and repairs", "Picha, ziara na matengenezo")).font(.mont(16, .bold)).foregroundColor(C.blue).multilineTextAlignment(.center)
                    Image(systemName: "arrow.right").font(.system(size: 17, weight: .bold)).foregroundColor(.white).modifier(Nudge())
                        .frame(width: 44, height: 44).background(Color(hex: 0x0A78C2)).clipShape(Circle()).shadow(color: Color(hex: 0x0A78C2).opacity(0.3), radius: 7, y: 5)
                }.frame(maxWidth: .infinity).padding(.vertical, 12).padding(.horizontal, 16)
            }
            .background(C.card).clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).stroke(C.ink.opacity(0.06), lineWidth: 1))
            .shadow(color: Color(hex: 0x04263F).opacity(0.10), radius: 16, y: 10)
        }.buttonStyle(Press()).accessibilityIdentifier("dcard_\(p.id)")
    }
}

// ---- a project ---------------------------------------------------------------------------------------------------
private struct DonorProjectView: View {
    @ObservedObject var d = DonorState.shared
    let p: DProject; let back: () -> Void
    @State private var viewer: ViewerPhotos?
    var pics: [(String, String)] { p.gallery.map { (d.photo($0.id, w: 1600), Dates.weekday($0.at) + ($0.label.isEmpty ? "" : " · " + $0.label)) } }
    var body: some View {
        Btn(t("Back", "Rudi"), icon: "back", light: true) { back() }.padding(.bottom, 6).accessibilityIdentifier("dproject_back")
            .fullScreenCover(item: $viewer) { PhotoViewer(v: $0) }
        Color.clear.aspectRatio(16 / 10, contentMode: .fit)
            .overlay(NetImage(url: cover(d, p)) { Icon(iconOf(p), size: 56, color: C.blue) }.scaledToFill())
            .background(C.tint).clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        Text(p.title).font(.mont(28, .heavy)).foregroundColor(C.ink).multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(.top, 14).accessibilityIdentifier("dproject_title")
        Text([kindOf(p).nb, p.place.nb].filter { !$0.isEmpty }.joined(separator: " · ")).font(.mont(14, .semibold)).foregroundColor(C.muted).multilineTextAlignment(.center).frame(maxWidth: .infinity)
        let (fg, bg) = stateTone(p.state)
        CardV {
            HStack(alignment: .top, spacing: 12) {
                IconTile(icon: p.state == "repairing" || p.state == "notworking" ? "wrench" : iconOf(p), tint: fg, bg: bg, size: 44)
                VStack(alignment: .leading, spacing: 2) { Text(donorStateName(p.state)).font(.tMedium).foregroundColor(C.ink); Text(donorStateSay(p) + (donorPeopleSay(p).isEmpty ? "" : " " + donorPeopleSay(p))).font(.bLarge).foregroundColor(C.text) }
                Spacer(minLength: 0)
            }
            SpeakBtn(key: "p_" + p.id, text: p.title + ". " + donorStateName(p.state) + ". " + donorStateSay(p) + " " + donorPeopleSay(p)).padding(.top, 12)
        }.padding(.top, 14)
        let facts = facts()
        if !facts.isEmpty {
            HStack(spacing: 8) { ForEach(Array(facts.enumerated()), id: \.offset) { _, f in Fact(value: f.0, label: f.1) } }.fixedSize(horizontal: false, vertical: true).padding(.top, 12)
        }
        if !p.gallery.isEmpty {
            SectionHead(text: t("Photos", "Picha"), extra: "\(p.gallery.count)")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 10) { ForEach(Array(p.gallery.enumerated()), id: \.offset) { k, g in MediaTile(url: d.photo(g.id), caption: Dates.weekday(g.at) + (g.label.isEmpty ? "" : " · " + g.label), video: false, zoom: true) { viewer = ViewerPhotos(list: pics, at: k, play: false) } } }
            }
            if p.gallery.count > 1 {
                Btn(t("Watch the \(p.gallery.count) photos as a slideshow", "Tazama picha \(p.gallery.count) kama onyesho"), icon: "play", wide: true) { viewer = ViewerPhotos(list: pics, at: 0, play: true) }.padding(.top, 10)
            }
        }
        if !p.thanks.isEmpty || !p.stories.isEmpty {
            SectionHead(text: t("Thank you from the community", "Asante kutoka kwa jamii"))
            if !p.thanks.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 10) {
                        ForEach(Array(p.thanks.enumerated()), id: \.offset) { _, x in
                            MediaTile(url: d.photo(x.id), caption: Dates.weekday(x.at) + (x.label.isEmpty ? "" : " · “" + x.label + "”"), video: x.video, zoom: true) {
                                if x.video { Share.open(d.photo(x.id)) } else { viewer = ViewerPhotos(list: [(d.photo(x.id, w: 1600), x.label.isEmpty ? "" : "“" + x.label + "”")], at: 0, play: false) }
                            }
                        }
                    }
                }
            }
            ForEach(Array(p.stories.enumerated()), id: \.offset) { _, s in
                VStack(alignment: .leading, spacing: 6) {
                    Text("“" + s.quote + "”").font(.bLarge).foregroundColor(C.ink)
                    if !s.who.isEmpty { Text("— " + s.who).font(.lMedium).foregroundColor(C.muted) }
                }.padding(14).frame(maxWidth: .infinity, alignment: .leading).background(C.card).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 10)
            }
        }
        progress
        visitsAndRepairs
        if let ll = p.gps {
            SectionHead(text: t("Where it is", "Mahali ilipo"))
            DonorMap(projects: [p], withCard: false) { _ in }
            Btn(t("Open in Google Maps", "Fungua kwenye Google Maps"), icon: "b:gmaps", light: true, small: true) { Share.open("https://www.google.com/maps/search/?api=1&query=\(ll.lat),\(ll.lng)") }.padding(.top, 10)
        }
        SectionHead(text: t("Questions about this project?", "Una swali kuhusu mradi huu?"))
        DonorTalk(p: p)
    }

    func facts() -> [(String, String)] {
        var f: [(String, String)] = []
        if p.people > 0 { f.append((donorNum(p.people), t("people served", "watu wanaohudumiwa"))) }
        if p.litresPerDay > 0 { f.append((donorNum(p.litresPerDay), t("litres a day", "lita kwa siku"))) }
        if p.years > 0 { f.append((p.years.truncatingRemainder(dividingBy: 1) == 0 ? donorNum(p.years) : String(format: "%.1f", p.years), p.years == 1 ? t("year of water", "mwaka wa maji") : t("years of water", "miaka ya maji"))) }
        if p.pupils > 0 { f.append((donorNum(p.pupils), t("pupils", "wanafunzi"))) }
        if p.patients > 0 { f.append((donorNum(p.patients), t("patients a week", "wagonjwa kwa wiki"))) }
        if p.children > 0 { f.append((donorNum(p.children), t("children", "watoto"))) }
        return Array(f.prefix(3))
    }

    @ViewBuilder var progress: some View {
        let spent = p.repairs.reduce(0) { $0 + $1.cost }
        let rows: [(String, String)] = [
            p.year > 0 ? (p.status == "Completed" ? t("Completed", "Ilikamilika") : t("Started", "Ilianza"), !p.done.isEmpty ? Dates.day(p.done) : "\(p.year)") : nil,
            p.depth > 0 ? (t("Depth", "Kina"), "\(p.depth) m") : nil,
            !p.pump.isEmpty ? (t("Pump", "Pampu"), p.pump) : nil,
            spent > 0 ? (t("Spent on repairs", "Gharama za matengenezo"), "KES " + donorNum(spent)) : nil,
        ].compactMap { $0 }
        let stage = p.visits.first { !$0.stage.isEmpty }?.stage
        let building = p.state == "building" && stage != nil
        if !rows.isEmpty || building {
            SectionHead(text: t("Progress", "Maendeleo"))
            CardV {
                if building, let stage {
                    let steps = ["Foundation", "Walls going up", "Roofing", "Finishing", "Finished"]
                    let pct = Double(max(1, (steps.firstIndex(of: stage) ?? 0) + 1)) / Double(steps.count)
                    Text(donorStage(stage)).font(.tSmall).foregroundColor(C.ink)
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Capsule().fill(C.line)
                            Capsule().fill(LinearGradient(colors: [Color(hex: 0x47B4F5), Color(hex: 0x0A78C2)], startPoint: .leading, endPoint: .trailing)).frame(width: g.size.width * pct)
                        }
                    }.frame(height: 10).padding(.vertical, 6)
                }
                ForEach(Array(rows.enumerated()), id: \.offset) { i, r in
                    if i > 0 || building { Rectangle().fill(C.line).frame(height: 1) }
                    HStack { Text(r.0).font(.mont(14, .semibold)).foregroundColor(C.muted); Spacer(); Text(r.1).font(.mont(14, .bold)).foregroundColor(C.ink).multilineTextAlignment(.trailing) }.padding(.vertical, 9)
                }
            }.accessibilityIdentifier("dproject_progress")
        }
    }

    @ViewBuilder var visitsAndRepairs: some View {
        let lines: [(String, String, String, String, String)] = (p.visits.prefix(8).map { v in
            (v.at, "cal", v.state == "repair" ? "repairing" : v.state == "notworking" ? "notworking" : "done", t("Site visit", "Ziara") + " · " + Dates.weekday(v.at), donorVisitSay(v))
        } + p.repairs.prefix(4).map { r in
            let at = r.fixedAt.isEmpty ? r.at : r.fixedAt
            return (at, "wrench", r.open ? "repairing" : "working", (r.open ? t("Repair under way", "Matengenezo yanaendelea") : t("Repaired", "Imerekebishwa")) + " · " + Dates.weekday(at),
                    (r.problems.isEmpty ? t("A problem was found", "Tatizo liligunduliwa") : r.problems.joined(separator: ", ")) + (r.cost > 0 ? " · KES " + donorNum(r.cost) : ""))
        }).sorted { $0.0 > $1.0 }
        if !lines.isEmpty {
            SectionHead(text: t("Visits and repairs", "Ziara na matengenezo"))
            CardV {
                ForEach(Array(lines.enumerated()), id: \.offset) { i, l in
                    if i > 0 { Rectangle().fill(C.line).frame(height: 1).padding(.vertical, 10) }
                    let (f, b) = stateTone(l.2)
                    HStack(alignment: .top, spacing: 12) {
                        IconTile(icon: l.1, tint: f, bg: b, size: 40)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(l.3).font(.tSmall).foregroundColor(C.ink)
                            Text(t("RAFIKI field team", "Timu ya RAFIKI uwanjani")).font(.mont(11, .semibold)).foregroundColor(C.muted)
                            if !l.4.isEmpty { Text(l.4).font(.bMedium).foregroundColor(C.text).padding(.top, 3) }
                        }
                        Spacer(minLength: 0)
                    }
                }
            }.accessibilityIdentifier("dproject_visits")
        }
    }
}

private struct MediaTile: View {
    let url: String; let caption: String; let video: Bool; var zoom = false; var tap: () -> Void = {}
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Button { if video || zoom { Haptic.tap(); tap() } } label: {
                Color.clear.aspectRatio(4 / 3, contentMode: .fit)
                    .overlay(NetImage(url: url) { Icon("camera", size: 32, color: C.blue) }.scaledToFill())
                    .background(C.tint).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay { if video { Image(systemName: "play.fill").font(.system(size: 20)).foregroundColor(.white).frame(width: 52, height: 52).background(Color.black.opacity(0.7)).clipShape(Circle()) } }
            }.buttonStyle(.plain).disabled(!video && !zoom).accessibilityLabel(video ? t("Play the video", "Cheza video") : caption)
            Text(caption).font(.mont(12, .semibold)).foregroundColor(C.muted).lineLimit(3)
        }.frame(width: 240)
    }
}

// ---- updates -------------------------------------------------------------------------------------------------------
private let MONTH_EN = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"]
private let MONTH_SW = ["Januari", "Februari", "Machi", "Aprili", "Mei", "Juni", "Julai", "Agosti", "Septemba", "Oktoba", "Novemba", "Desemba"]
func donorMonth(_ key: String) -> String {
    guard key.count >= 7, let mo = Int(key.dropFirst(5).prefix(2)), (1...12).contains(mo) else { return key }
    return (Lang.sw ? MONTH_SW : MONTH_EN)[mo - 1] + " " + key.prefix(4)
}
/// "3 visits · 2 new photos · 1 thank-you" for one month.
func donorMonthSum(_ us: [DUpdate]) -> String {
    func c(_ k: String) -> Int { us.filter { $0.type == k }.count }
    let photos = us.filter { $0.type == "photos" }.reduce(0) { $0 + $1.n }
    var a: [String] = []
    if c("visit") > 0 { a.append(c("visit") == 1 ? t("1 visit", "ziara 1") : t("\(c("visit")) visits", "ziara \(c("visit"))")) }
    if photos > 0 { a.append(photos == 1 ? t("1 new photo", "picha 1 mpya") : t("\(photos) new photos", "picha \(photos) mpya")) }
    if c("thanks") > 0 { a.append(c("thanks") == 1 ? t("1 thank-you", "shukrani 1") : t("\(c("thanks")) thank-yous", "shukrani \(c("thanks"))")) }
    if c("repair") > 0 { a.append(c("repair") == 1 ? t("1 repair", "matengenezo 1") : t("\(c("repair")) repairs", "matengenezo \(c("repair"))")) }
    if c("story") > 0 { a.append(c("story") == 1 ? t("1 story", "hadithi 1") : t("\(c("story")) stories", "hadithi \(c("story"))")) }
    return a.joined(separator: " · ")
}

private struct DonorUpdates: View {
    @ObservedObject var d = DonorState.shared
    let v: DonorView; let open: (String) -> Void
    var body: some View {
        SectionHead(text: t("Updates", "Habari"), extra: t("From the RAFIKI field team, newest first", "Kutoka kwa timu ya RAFIKI, mpya kwanza"))
        if v.updates.isEmpty {
            EmptyDrop(text: t("News from your projects will appear here: visits, photos and thank-you messages.", "Habari za miradi yako zitaonekana hapa: ziara, picha na ujumbe wa shukrani."))
        } else {
            let keys = v.updates.map { String($0.at.prefix(7)) }.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }.prefix(12)
            ForEach(Array(keys), id: \.self) { k in
                let us = v.updates.filter { $0.at.hasPrefix(k) }
                VStack(spacing: 2) {
                    Text(donorMonth(k).nb).font(.mont(18, .heavy)).foregroundColor(C.ink)
                    Text(donorMonthSum(us)).font(.mont(12, .semibold)).foregroundColor(C.muted).multilineTextAlignment(.center)
                }.frame(maxWidth: .infinity).padding(.top, 8).padding(.bottom, 10).accessibilityIdentifier("dmonth_\(k)")
                let shown = Array(us.prefix(12)).filter { v.project($0.pid) != nil && updateLook($0) != nil }
                ForEach(Array(shown.enumerated()), id: \.offset) { j, u in
                    UpdateRow(u: u, p: v.project(u.pid)!, fresh: !d.seen.isEmpty && u.at > d.seen) { open(u.pid) }.padding(.bottom, 14).rise(min(j, 6))
                }
            }
        }
    }
}

private func updateLook(_ u: DUpdate) -> (String, String, String, String)? {
    switch u.type {
    case "visit": return ("cal", u.visit?.state == "notworking" ? "notworking" : u.visit?.state == "repair" ? "repairing" : "done", t("Site visit", "Ziara"), u.visit.map(donorVisitSay) ?? "")
    case "photos": return ("camera", "planned", u.n == 1 ? t("1 new photo", "Picha 1 mpya") : t("\(u.n) new photos", "Picha \(u.n) mpya"), "")
    case "thanks": return ("heart", "repairing", u.media == "video" ? t("A thank-you video", "Video ya shukrani") : t("A thank-you photo", "Picha ya shukrani"), u.text.isEmpty ? "" : "“" + u.text + "”")
    case "repair": return ("wrench", u.fixed ? "working" : "repairing", u.fixed ? t("Repaired", "Imerekebishwa") : t("A problem was found", "Tatizo liligunduliwa"), u.problems.joined(separator: ", "))
    case "story": return ("chat", "building", t("A story from the community", "Hadithi kutoka kwa jamii"), "“" + u.text + "”" + (u.who.isEmpty ? "" : " — " + u.who))
    case "anniversary": return ("spark", "working", u.years == 1 ? t("One year of clean water!", "Mwaka mmoja wa maji safi!") : t("\(u.years) years of clean water!", "Miaka \(u.years) ya maji safi!"), "")
    default: return nil
    }
}

/// The update's icon: it pops in, then lives a little (a heart that beats, a spanner at work, a camera that flashes).
private struct UpdateIcon: View {
    let icon: String; let f: Color; let b: Color
    var body: some View {
        let tile = IconTile(icon: icon, tint: f, bg: b, size: 46)
        Group {
            switch icon {
            case "heart": tile.modifier(Beat())
            case "wrench": tile.modifier(AtWork(period: 2.6))
            case "camera": tile.modifier(Flash()).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            case "spark": tile.modifier(Bob(px: 4, period: 2.4))
            default: tile
            }
        }.pop()
    }
}

/// An update as its own card, centred: the icon, the project, the date, a headline and the story; Listen and
/// See the project along the bottom.
private struct UpdateRow: View {
    @ObservedObject var d = DonorState.shared
    let u: DUpdate; let p: DProject; let fresh: Bool; let open: () -> Void
    @State private var viewer: ViewerPhotos?
    var body: some View {
        if let (icon, tone, title, text0) = updateLook(u) {
            let text = donorStory(u).isEmpty ? text0 : donorStory(u)
            let (f, b) = stateTone(tone)
            let anniv = u.type == "anniversary"
            VStack(spacing: 0) {
                VStack(spacing: 0) {
                    UpdateIcon(icon: icon, f: f, b: b)
                    Button { open() } label: { Text(p.title).font(.mont(14, .bold)).foregroundColor(C.blue2).multilineTextAlignment(.center).padding(.horizontal, 6).padding(.vertical, 2) }
                        .buttonStyle(.plain).padding(.top, 10)
                    HStack(spacing: 8) {
                        Text(Dates.weekday(u.at).nb).font(.mont(13, .medium)).foregroundColor(C.muted)
                        if fresh { Tag(t("NEW", "MPYA"), bg: C.named("coral").bg, fg: C.named("coral").fg) }
                    }
                    Text(title).font(.mont(20, .heavy)).foregroundColor(C.ink).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true).padding(.top, 12)
                    if !text.isEmpty { Text(text).font(.bLarge).foregroundColor(C.text).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true).padding(.top, 6) }
                    let thumbs = u.type == "photos" ? Array(u.photos.prefix(3)) : (u.type == "thanks" && u.media != "video" && !u.id.isEmpty) ? [u.id] : []
                    if !thumbs.isEmpty {
                        HStack(spacing: 8) {
                            ForEach(Array(thumbs.enumerated()), id: \.offset) { k, id in
                                Button { viewer = ViewerPhotos(list: thumbs.map { (d.photo($0, w: 1600), p.title + " · " + Dates.weekday(u.at)) }, at: k, play: false) } label: {
                                    Color.clear.aspectRatio(100.0 / 84.0, contentMode: .fit).frame(maxWidth: 120)
                                        .overlay(NetImage(url: d.photo(id, w: 480)) { Color.clear }.scaledToFill())
                                        .background(C.tint).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                                }.buttonStyle(Press()).accessibilityLabel(t("Open photo", "Fungua picha")).pop()
                            }
                        }.frame(maxWidth: .infinity).padding(.top, 14)
                    }
                }.padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 16).frame(maxWidth: .infinity)
                Rectangle().fill(anniv ? Color(hex: 0x8A5200).opacity(0.15) : C.line).frame(height: 1)
                HStack(spacing: 10) {
                    SpeakBtn(key: "u_" + u.type + u.pid + u.at, text: p.title + ". " + title + ". " + Dates.weekday(u.at) + ". " + text, label: t("Listen", "Sikiliza"), small: true, wide: false)
                    Btn(t("See the project", "Ona mradi"), icon: "chev", light: true, small: true) { open() }
                }.frame(maxWidth: .infinity).padding(.horizontal, 16).padding(.vertical, 10)
            }
            .background(anniv ? AnyShapeStyle(LinearGradient(colors: [Color(hex: 0xFFF6E0), Color(hex: 0xFDE7C2)], startPoint: .topLeading, endPoint: .bottomTrailing)) : AnyShapeStyle(C.card))
            .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).stroke(C.ink.opacity(0.06), lineWidth: 1))
            .shadow(color: Color(hex: 0x04263F).opacity(0.08), radius: 14, y: 8)
            .accessibilityIdentifier("dupd_\(u.type)_\(u.pid)")
            .fullScreenCover(item: $viewer) { PhotoViewer(v: $0) }
        }
    }
}

// ---- settings ------------------------------------------------------------------------------------------------------
private struct DonorSettings: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var d = DonorState.shared
    let v: DonorView
    @State private var msg: (String, String)?
    @State private var busy = false
    @State private var askOut = false
    @State private var alerts = false
    var body: some View {
        SectionHead(text: t("Settings", "Mipangilio"))
        CardV(pad: 0) {
            block(t("Language", "Lugha")) { Seg(opts: [("en", "English"), ("sw", "Kiswahili")], cur: Lang.sw ? "sw" : "en") { m.setLang($0 == "sw") } }
            line
            block(t("Text size", "Ukubwa wa maandishi")) { Seg(opts: [("s", t("Small", "Ndogo")), ("", t("Normal", "Kawaida")), ("l", t("Large", "Kubwa")), ("xl", t("Larger", "Kubwa zaidi"))], cur: m.prefs.textSize) { m.prefs.textSize = $0; m.look += 1 } }
            line
            block(t("Colours", "Rangi")) { Seg(opts: [("", t("Automatic", "Kiotomatiki")), ("light", t("Light", "Mwanga")), ("dark", t("Dark", "Giza"))], cur: Look.mode) { m.setTheme($0) } }
        }
        CardV(pad: 0) {
            let sub = !v.email ? t("We don’t have your e-mail yet. Write to us to get them.", "Bado hatuna barua pepe yako. Tuandikie uzipate.")
                : !v.digest ? t("Paused by RAFIKI for now.", "Zimesimamishwa na RAFIKI kwa sasa.")
                : v.mailOn ? t("A short summary on the 1st of each month.", "Muhtasari mfupi tarehe 1 kila mwezi.") : t("Off. You won’t get the monthly e-mail.", "Zimezimwa. Hutapata barua pepe ya mwezi.")
            row("mail", "teal", t("E-mail updates", "Habari kwa barua pepe"), sub) {
                Toggle("", isOn: Binding(get: { v.mailOn }, set: { on in
                    busy = true
                    Task { let e = await d.setMail(on); busy = false; msg = e.map { ("bad", $0) } ?? ("ok", on ? t("E-mail updates on ✓", "Habari kwa barua pepe zimewashwa ✓") : t("E-mail updates off", "Habari kwa barua pepe zimezimwa")) }
                })).labelsHidden().tint(C.ok).disabled(!v.email || !v.digest || busy).accessibilityIdentifier("dset_mail")
            }
            line
            row("bell", "amber", t("New photos & updates", "Picha mpya na habari"), t("A notification on this phone when there’s news from your projects.", "Arifa kwenye simu hii kukiwa na habari za miradi yako.")) {
                Toggle("", isOn: Binding(get: { alerts }, set: { on in alerts = on; m.prefs.phoneAlerts = on })).labelsHidden().tint(C.ok).accessibilityIdentifier("dset_alerts")
            }
        }.padding(.top, 12)
        if case let (tone, text)? = msg { Msg(text, tone) }
        CardV(pad: 0) {
            Button { Share.open(DONOR_GIVE) } label: { row("heart", "green", t("Give again", "Changia tena"), "rafikioutreach.org/donate") { Icon("chev", size: 18, color: C.muted) } }.buttonStyle(.plain)
            line
            Button { Share.open("mailto:\(DONOR_CONTACT)") } label: { row("mail", "blue", t("Contact RAFIKI", "Wasiliana na RAFIKI"), DONOR_CONTACT) { Icon("chev", size: 18, color: C.muted) } }.buttonStyle(.plain)
            line
            Button { askOut = true } label: { row("back", "coral", t("Sign out", "Toka"), t("You can sign in again with your code or link.", "Unaweza kuingia tena kwa msimbo au kiungo chako.")) { EmptyView() } }
                .buttonStyle(.plain).accessibilityIdentifier("dset_out")
        }.padding(.top, 12)
        SectionHead(text: t("Talk to RAFIKI", "Ongea na RAFIKI"))
        DonorTalk(p: nil)
        Text(t("Signed in as ", "Umeingia kama ") + v.viewer).font(.bSmall).foregroundColor(C.muted).wide(.center).padding(.top, 16)
            .onAppear { alerts = m.prefs.phoneAlerts }
            .confirmationDialog(t("Sign out?", "Utoke?"), isPresented: $askOut, titleVisibility: .visible) {
                Button(t("Sign out", "Toka"), role: .destructive) { d.signOut() }
                Button(t("Cancel", "Ghairi"), role: .cancel) {}
            } message: {
                Text(t("Your projects stay safe. Sign in again any time with your donor code or private link.", "Miradi yako iko salama. Ingia tena wakati wowote kwa msimbo wako wa mfadhili au kiungo cha siri."))
            }
    }
    var line: some View { Rectangle().fill(C.line).frame(height: 1) }
    func block<V: View>(_ title: String, @ViewBuilder _ content: () -> V) -> some View {
        VStack(alignment: .leading, spacing: 8) { Text(title).font(.tSmall).foregroundColor(C.ink); content() }.padding(.horizontal, 16).padding(.vertical, 12)
    }
    func row<E: View>(_ icon: String, _ hue: String, _ title: String, _ sub: String, @ViewBuilder end: () -> E) -> some View {
        let h = C.named(hue)
        return HStack(spacing: 12) {
            IconTile(icon: icon, tint: h.fg, bg: h.bg, size: 38)
            VStack(alignment: .leading, spacing: 1) { Text(title).font(.tSmall).foregroundColor(C.ink); Text(sub).font(.mont(12, .semibold)).foregroundColor(C.muted) }
            Spacer(minLength: 8)
            end()
        }.padding(.horizontal, 16).padding(.vertical, 12).frame(minHeight: 64).contentShape(Rectangle())
    }
}

private struct Seg: View {
    let opts: [(String, String)]; let cur: String; let pick: (String) -> Void
    var body: some View {
        HStack(spacing: 4) {
            ForEach(opts, id: \.0) { k, label in
                let on = k == cur
                Button { Haptic.tap(); pick(k) } label: {
                    Text(label).font(.mont(13, .bold)).foregroundColor(on ? .white : C.ink).multilineTextAlignment(.center).lineLimit(2).minimumScaleFactor(0.8)
                        .padding(.horizontal, 4).frame(maxWidth: .infinity, minHeight: 44).background(on ? AnyShapeStyle(BlueBrush) : AnyShapeStyle(Color.clear))
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }.buttonStyle(.plain).accessibilityAddTraits(on ? .isSelected : [])
            }
        }.padding(4).background(C.card).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1))
    }
}
