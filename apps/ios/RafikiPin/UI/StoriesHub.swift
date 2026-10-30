import SwiftUI
import UIKit

/// The Stories tab in the bottom bar (as Android 2.28): every project's story in one place. Write a story (find the
/// project), see where each one is, and open the project's story writer (its Stories tab).
struct StoriesView: View {
    @EnvironmentObject var m: AppModel
    @State private var f = ""
    @State private var picking = false
    @State private var choose: String?
    @State private var reading: String?
    @State private var demoDone = false
    private let limit = 60

    var body: some View {
        if let s = m.snap {
            let c = s.shCount()
            let group = f.isEmpty ? (s.admin && (c["draft"] ?? 0) > 0 ? "draft" : "need") : f
            let list = s.shSorted(s.projects.filter { s.shGroup($0) == group })
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text(t("Stories", "Hadithi")).font(.hLarge).foregroundColor(C.ink).frame(maxWidth: .infinity).multilineTextAlignment(.center)
                        .accessibilityAddTraits(.isHeader)
                    // story photos still on their way (they keep sending while you use the app)
                    StorySendingPill(up: m.storyUp).frame(maxWidth: .infinity).padding(.top, 4)
                    hero.padding(.top, 8).rise(0)
                    counts(s, c, group).padding(.top, 12).rise(1)
                    listCard(s, list, group).padding(.top, 12).rise(2)
                    StoriesHow().padding(.top, 12).rise(3)
                    Spacer().frame(height: 24)
                }.padding(16)
            }
            .sheet(isPresented: $picking) {
                StoryProjectPicker(s: s, close: { picking = false }) { id in picking = false; open(id) }.storySheet()
            }
            .sheet(isPresented: isSet($choose)) {
                let id = choose ?? ""
                StoryChooseSheet(title: s.projects.first { $0.id == id }?.title ?? t("Story", "Hadithi"), close: { choose = nil },
                                 view: { choose = nil; DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { reading = id } },
                                 edit: { choose = nil; DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { open(id) } })
                    .storySheet([.height(250), .medium])
            }
            .fullScreenCover(isPresented: isSet($reading)) {
                StoryReaderView(pid: reading ?? "") { reading = nil }.environmentObject(m)
            }
            .onAppear { demo() }
        }
    }

    /// A project's page on its Stories tab, at the writer; back comes here.
    private func open(_ id: String) { m.go(Place(tab: "stories", project: id, projectTab: "story")) }

    /// The hero: what this is, and the one big action.
    private var hero: some View {
        let hu = C.hue("coral")
        return CardV(pad: 16) {
            HStack(spacing: 12) {
                IconTile(icon: "spark", tint: hu.fg, bg: hu.bg, size: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(t("Every project has a story", "Kila mradi una hadithi")).font(.tMedium).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                    Text(t("How a project changed lives, in the words of the people it serves. Everything is saved to monday.com.", "Jinsi mradi ulivyobadilisha maisha, kwa maneno ya watu unaowahudumia. Kila kitu kinahifadhiwa monday.com."))
                        .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
            Btn(t("Write a story", "Andika hadithi"), icon: "plus", wide: true) { Haptic.tap(); picking = true }.padding(.top, 14)
        }
    }

    /// The four counts: tap one to see those projects; two by two on a phone.
    private func counts(_ s: Snapshot, _ c: [String: Int], _ group: String) -> some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
            ForEach(SH_GROUPS, id: \.self) { g in
                let n = c[g] ?? 0
                StoriesCount(n: n, label: shGroupName(g), on: group == g, hot: g == "draft" && s.admin && n > 0) { Haptic.tap(); f = g }
            }
        }
    }

    private func listCard(_ s: Snapshot, _ list: [Project], _ group: String) -> some View {
        CardV(pad: 16) {
            Text(shGroupName(group)).font(.tSmall).foregroundColor(C.ink).accessibilityAddTraits(.isHeader)
            if group == "draft" {
                Text(s.admin ? t("Open one to read it, edit the words, then Approve.", "Fungua moja uisome, hariri maneno, kisha Idhinisha.")
                     : t("An admin reads these and approves them for the website.", "Msimamizi anazisoma na kuziidhinisha kwa ajili ya tovuti."))
                    .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 4)
            }
            if list.isEmpty {
                Text(t("Nothing here right now.", "Hakuna kitu hapa sasa hivi.")).font(.bMedium).foregroundColor(C.muted).padding(.top, 10)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(list.prefix(limit).enumerated()), id: \.element.id) { i, p in
                        if i > 0 { Divider().overlay(C.line) }
                        StoryProjectRow(s: s, p: p) {
                            Haptic.tap()
                            if ["draft", "approved", "published"].contains(s.storyStatus(p)) { choose = p.id } else { open(p.id) }
                        }
                    }
                }.padding(.top, 4)
                if list.count > limit {
                    Text(t("Showing \(limit) of \(list.count). Use “Write a story” to find any project.", "Inaonyesha \(limit) kati ya \(list.count). Tumia “Andika hadithi” kupata mradi wowote."))
                        .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 8)
                }
            }
        }
    }

    /// Demo screenshots: -screen story-read opens the reader; -sheet pick / choose opens those sheets.
    private func demo() {
        guard m.demo, !demoDone else { return }
        demoDone = true
        if Demo.arg("-screen") == "story-read" { reading = "101" }
        switch Demo.arg("-sheet") ?? "" {
        case "pick": picking = true
        case "choose": choose = "101"
        default: break
        }
    }
}

/// A count: the number, and what it counts (wraps between words, never inside one).
private struct StoriesCount: View {
    let n: Int; let label: String; let on: Bool; let hot: Bool; let tap: () -> Void
    var body: some View {
        Button(action: tap) {
            VStack(alignment: .leading, spacing: 2) {
                Text("\(n)").font(.mont(22, .heavy)).foregroundColor(C.ink).lineLimit(1)
                FitWords(label, size: 12, weight: .medium, color: C.muted, lines: 2)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12).padding(.vertical, 10)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .topLeading)
            .background(on ? C.tint : C.bg)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(on ? C.blue : hot ? C.warn : C.line, lineWidth: on || hot ? 1.5 : 1))
        }
        .buttonStyle(Press())
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// A project in the list or the picker: its name and place, where its story is, and what it has so far.
struct StoryProjectRow: View {
    let s: Snapshot; let p: Project; let tap: () -> Void
    private func pill(_ st: String) -> (String, String) {
        switch st {
        case "collecting": return ("has", t("Collecting", "Inakusanywa"))
        case "draft": return ("warn", t("Waiting for review", "Inasubiri ukaguzi"))
        case "approved": return ("ok", t("Approved", "Imeidhinishwa"))
        case "published": return ("ok", t("On the website", "Kwenye tovuti"))
        default: return ("off", t("Not started", "Haijaanza"))
        }
    }
    var body: some View {
        let hu = C.hue("coral")
        let place = [p.area, p.country].filter { !$0.isEmpty }.joined(separator: " · ")
        let pl = pill(s.storyStatus(p))
        let line = s.shLine(p)
        Button(action: tap) {
            HStack(spacing: 12) {
                Icon("spark", size: 20, color: hu.fg).frame(width: 36, height: 36).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 0) {
                    Text(p.title).font(.tSmall).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                    if !place.isEmpty { Text(place).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true) }
                    StatusPill(label: pl.1, tone: pl.0).padding(.top, 5).padding(.bottom, 2)
                    if !line.isEmpty { Text(line).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 2) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                Icon("chev", size: 18, color: C.muted)
            }
            .padding(.vertical, 10).padding(.horizontal, 2).frame(minHeight: 58).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

/// "How a story gets to the website": the four steps, folded away.
private struct StoriesHow: View {
    @State private var open = false
    var body: some View {
        let steps: [(String, String)] = [
            (t("Collect", "Kusanya"), t("Answer the 6 questions (typing or a voice note), add before, during and after photos, a quote, and photo consent.", "Jibu maswali 6 (kwa kuandika au sauti), ongeza picha za kabla, wakati na baada, nukuu, na ruhusa ya picha.")),
            (t("Send for review", "Tuma kwa ukaguzi"), t("“Draft the story” writes it from your answers and sends it to an admin.", "“Andika rasimu ya hadithi” inaiandika kutoka kwa majibu yako na kuituma kwa msimamizi.")),
            (t("Approve", "Idhinisha"), t("An admin reads it, fixes the words and approves it.", "Msimamizi anaisoma, anarekebisha maneno na kuiidhinisha.")),
            (t("Publish", "Chapisha"), t("Approved stories show on the website. Nothing goes public before that.", "Hadithi zilizoidhinishwa zinaonekana kwenye tovuti. Hakuna kinachotoka kabla ya hapo.")),
        ]
        CardV(pad: 4) {
            Button { Haptic.tap(); withAnimation(Motion.on ? .easeInOut(duration: 0.22) : nil) { open.toggle() } } label: {
                HStack(spacing: 8) {
                    Text(t("How a story gets to the website", "Jinsi hadithi inavyofika kwenye tovuti")).font(.tSmall).foregroundColor(C.ink)
                        .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading)
                    Icon("chev", size: 18, color: C.muted).rotationEffect(.degrees(open ? 90 : 0))
                }.padding(.horizontal, 14).padding(.vertical, 8).frame(minHeight: 56).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if open {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(Array(steps.enumerated()), id: \.offset) { i, st in
                        HStack(alignment: .top, spacing: 10) {
                            Text("\(i + 1)").font(.mont(12, .heavy)).foregroundColor(C.blue2).frame(width: 24, height: 24).background(C.tint).clipShape(Circle())
                            (Text(st.0).font(.mont(14, .heavy)).foregroundColor(C.ink) + Text(" " + st.1).font(.bMedium).foregroundColor(C.text))
                                .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading).padding(.top, 1)
                        }
                    }
                }
                .padding(.horizontal, 14).padding(.bottom, 14)
                .transition(.opacity)
            }
        }
    }
}

/// "Which project is the story about?": search by project, village or country.
struct StoryProjectPicker: View {
    let s: Snapshot; let close: () -> Void; let pick: (String) -> Void
    @State private var q = ""
    var body: some View {
        let qq = q.trimmingCharacters(in: .whitespaces).lowercased()
        let list = Array(s.shSorted(s.projects).filter { p in qq.isEmpty || "\(p.title) \(p.area) \(p.country)".lowercased().contains(qq) }.prefix(40))
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SheetHead(title: t("Which project is the story about?", "Hadithi inahusu mradi gani?"), close: close)
                HStack(spacing: 10) {
                    Icon("search", size: 20, color: C.muted)
                    TextField("", text: $q, prompt: Text(t("Search a project or village", "Tafuta mradi au kijiji")).foregroundColor(C.muted))
                        .font(.bLarge).foregroundColor(C.text).textInputAutocapitalization(.never).autocorrectionDisabled()
                }
                .padding(.horizontal, 14).frame(minHeight: 52).background(C.card)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.edge, lineWidth: 1))
                .padding(.top, 12).padding(.bottom, 6)
                if list.isEmpty {
                    Text(t("No project matches.", "Hakuna mradi unaolingana.")).font(.bMedium).foregroundColor(C.muted).padding(.vertical, 12)
                }
                ForEach(Array(list.enumerated()), id: \.element.id) { i, p in
                    if i > 0 { Divider().overlay(C.line) }
                    StoryProjectRow(s: s, p: p) { Haptic.tap(); pick(p.id) }
                }
            }.padding(20)
        }
    }
}
