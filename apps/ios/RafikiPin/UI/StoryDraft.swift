import SwiftUI
import UIKit

// The drafted story as sections with photos (as Android 2.27+). Admins edit the title, the line under it, each
// heading and its words, and choose each section's photo from story.pool (action doc); field users read it as an
// article. "Approve and publish" asks first and puts it on the website; then "On the website ✓" links to the story
// page. Old drafts without sections keep the single text view.

/// One section while an admin edits it.
struct EdSec: Equatable { var h: String; var body: String; var photo: DocPhoto? }

struct StoryDraftCard: View {
    @EnvironmentObject var m: AppModel
    let p: Project; let st: Story; let admin: Bool
    let post: (JSON, String?, ((Reply) -> Void)?) -> Void
    let draftAgain: () -> Void

    @State private var drafting = false
    /// What is being sent now: save, text, approve, back.
    @State private var work: String?
    @State private var askApprove = false
    @State private var askOff = false
    @State private var pick: Int?
    @State private var view: DocPhoto?
    @State private var title = ""
    @State private var dek = ""
    @State private var secs: [EdSec] = []
    @State private var text = ""
    @State private var started = false

    private var doc: StoryDoc? { st.doc }
    private var live: Bool { st.live }
    private func edited() -> StoryDoc {
        StoryDoc(title: title, dek: dek, sections: secs.map { DocSection(h: $0.h, body: $0.body, photo: $0.photo) },
                 pullQuote: doc?.pullQuote ?? "", pullWho: doc?.pullWho ?? "", at: doc?.at ?? "", by: doc?.by ?? "", model: doc?.model ?? "")
    }
    private var dirty: Bool {
        guard let d = doc else { return false }
        return title != d.title || dek != d.dek || secs.count != d.sections.count
            || zip(secs, d.sections).contains(where: { pair in pair.0.h != pair.1.h || pair.0.body != pair.1.body || pair.0.photo?.id != pair.1.photo?.id })
    }

    var body: some View {
        CardV {
            head
            if live { StoryPubLink(url: st.pub?.url ?? "") }
            content
            actions
        }
        .onAppear { if !started { started = true; reset() } }
        .onChange(of: st.doc) { _ in reset() }
        .onChange(of: st.text) { v in text = v }
        .alert(t("Approve and publish this story?", "Idhinisha na uchapishe hadithi hii?"), isPresented: $askApprove) {
            Button(t("Cancel", "Ghairi"), role: .cancel) {}
            Button(t("Approve and publish", "Idhinisha na uchapishe")) { approve() }
        } message: { Text(t("It goes on the website’s Stories page straight away, and a copy goes to monday.com.", "Inaenda kwenye ukurasa wa Hadithi wa tovuti mara moja, na nakala inaenda monday.com.")) }
        .alert(t("Take it off the website?", "Iondoe kwenye tovuti?"), isPresented: $askOff) {
            Button(t("Cancel", "Ghairi"), role: .cancel) {}
            Button(t("Take it off", "Iondoe"), role: .destructive) {
                work = "back"; post(["action": "collecting"], t("Taken off the website", "Imeondolewa kwenye tovuti")) { _ in work = nil }
            }
        } message: { Text(t("The story goes back to Collecting and leaves the website’s Stories page.", "Hadithi inarudi kukusanya na kuondoka kwenye ukurasa wa Hadithi wa tovuti.")) }
        .sheet(isPresented: isSet($pick)) {
            if let i = pick, i < secs.count {
                StoryPoolSheet(pid: p.id, pool: st.pool, head: secs[i].h, i: i, used: secs.map { $0.photo?.id }, close: { pick = nil }) { ph in
                    // a photo shows once in the story
                    if let ph { for j in secs.indices where j != i && secs[j].photo?.id == ph.id { secs[j].photo = nil } }
                    secs[i].photo = ph
                    pick = nil
                }
                .environmentObject(m)
                .storySheet()
            }
        }
        .fullScreenCover(isPresented: isSet($view)) {
            if let ph = view { PhotoViewer(path: StoryKit.photoPath(p.id, ph.id, w: 1600), title: ph.cap.isEmpty ? t("Photo", "Picha") : ph.cap) { view = nil }.environmentObject(m) }
        }
    }

    private func reset() {
        title = doc?.title ?? ""; dek = doc?.dek ?? ""
        secs = (doc?.sections ?? []).map { EdSec(h: $0.h, body: $0.body, photo: $0.photo) }
        text = st.text
    }

    private var head: some View {
        let nWords = doc != nil ? (admin ? edited().words : (doc?.words ?? 0)) : wordCount(text)
        return VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(t("The story", "Hadithi yenyewe")).font(.tMedium).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityAddTraits(.isHeader)
                if !st.text.isEmpty || doc != nil {
                    Text(t("\(nWords) words", "Maneno \(nWords)")).font(.lSmall).foregroundColor(doc != nil || (150...300).contains(nWords) ? C.muted : C.warn).lineLimit(1).fixedSize()
                }
            }
            if let d = doc {
                let how = d.model.hasPrefix("openai:") ? t("written with AI from the facts", "imeandikwa na AI kutoka kwa ukweli")
                    : d.model == "text" ? t("from the earlier story", "kutoka kwa hadithi ya awali") : t("written from the answers", "imeandikwa kutoka kwa majibu")
                Text(how + (d.by.isEmpty ? "" : " · " + d.by)).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    @ViewBuilder private var content: some View {
        if st.text.isEmpty && doc == nil {
            Text(t("Answer the questions, then draft it here: 150–300 words from the answers, the quote and the project’s facts.", "Jibu maswali, kisha andika rasimu hapa: maneno 150–300 kutoka kwa majibu, nukuu na taarifa za mradi."))
                .font(.bMedium).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 6)
        } else if let d = doc, admin {
            editor(d)
        } else if let d = doc {
            StoryArticle(pid: p.id, d: d, big: false, pullAfter: -1) { view = $0 }.padding(.top, 10)
        } else if admin && !live {
            Field(t("Story text", "Maandishi ya hadithi"), $text, lines: 8)
        } else {
            Text(st.text).font(.bMedium).foregroundColor(C.text).fixedSize(horizontal: false, vertical: true).padding(.top, 6)
        }
    }

    private func editor(_ d: StoryDoc) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Field(t("Title", "Kichwa"), Binding(get: { title }, set: { title = String($0.replacingOccurrences(of: "\n", with: " ").prefix(90)) }))
            Field(t("The line under the title", "Mstari chini ya kichwa"), Binding(get: { dek }, set: { dek = String($0.replacingOccurrences(of: "\n", with: " ").prefix(240)) }), lines: 2)
            ForEach(secs.indices, id: \.self) { i in secEditor(i) }
            PullQuote(d: d, admin: true)
        }
    }

    private func secEditor(_ i: Int) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                Text("\(i + 1)").font(.lMedium).foregroundColor(C.blue2).frame(width: 26, height: 26).background(C.tint).clipShape(Circle())
                Text(t("Section \(i + 1)", "Sehemu \(i + 1)")).font(.lLarge).foregroundColor(C.ink)
            }
            Field(t("Heading", "Kichwa kidogo"), Binding(get: { i < secs.count ? secs[i].h : "" },
                                                         set: { v in if i < secs.count { secs[i].h = String(v.replacingOccurrences(of: "\n", with: " ").prefix(70)) } }))
            SecPhoto(pid: p.id, ph: i < secs.count ? secs[i].photo : nil, admin: true,
                     change: { pick = i }, remove: { if i < secs.count { secs[i].photo = nil } }, open: { view = i < secs.count ? secs[i].photo : nil })
            Field(t("Words", "Maneno"), Binding(get: { i < secs.count ? secs[i].body : "" }, set: { v in if i < secs.count { secs[i].body = v } }), lines: 4)
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(C.bg)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).padding(.top, 16)
    }

    // --- what can be done ----------------------------------------------------------------------------------------------

    @ViewBuilder private var actions: some View {
        let hasWords = !st.text.isEmpty || doc != nil
        if !live {
            Btn(hasWords ? t("Draft it again", "Andika rasimu upya") : t("Draft the story", "Andika rasimu ya hadithi"), icon: "spark", enabled: work == nil, light: hasWords, wide: true,
                busy: drafting ? t("Drafting…", "Inaandika…") : nil) {
                if hasWords { draftAgain() } else { drafting = true; post(["action": "draft"], t("Draft ready ✓", "Rasimu iko tayari ✓")) { _ in drafting = false } }
            }.padding(.top, 14)
        }
        // the completion date the story will use (monday.com)
        if !live, ["not-started", "collecting", "draft"].contains(st.status), let d = monthText(p.done) {
            Text(t("Finished: \(d) (from monday.com)", "Ilikamilika: \(d) (kutoka monday.com)")).font(.bSmall).foregroundColor(C.muted).padding(.top, 6)
        }
        if admin && doc != nil {
            Btn(live ? t("Save and update the website", "Hifadhi na usasishe tovuti") : t("Save changes", "Hifadhi mabadiliko"), icon: "check", enabled: dirty && work == nil,
                light: !dirty, small: true, wide: true,
                busy: work == "save" ? (live ? t("Updating the website…", "Inasasisha tovuti…") : t("Saving…", "Inahifadhi…")) : nil) {
                work = "save"
                post(["action": "doc", "doc": edited().out()], live ? t("Saved and updated on the website ✓", "Imehifadhiwa na kusasishwa kwenye tovuti ✓") : t("Saved ✓", "Imehifadhiwa ✓")) { _ in work = nil }
            }.padding(.top, 8)
        } else if admin && doc == nil && !st.text.isEmpty && text != st.text && !live {
            Btn(t("Save text", "Hifadhi maandishi"), icon: "check", enabled: work == nil, small: true, wide: true, busy: work == "text" ? t("Saving…", "Inahifadhi…") : nil) {
                work = "text"; post(["action": "text", "text": text], t("Saved ✓", "Imehifadhiwa ✓")) { _ in work = nil }
            }.padding(.top, 8)
        }
        if admin && !live && ["draft", "collecting", "approved"].contains(st.status) {
            Btn(t("Approve and publish", "Idhinisha na uchapishe"), icon: "check", enabled: hasWords && work == nil, wide: true,
                busy: work == "approve" ? t("Publishing…", "Inachapisha…") : nil) { askApprove = true }.padding(.top, 8)
            Text(t("“Approve and publish” puts it on the website’s Stories page straight away, with a copy in monday.com.", "“Idhinisha na uchapishe” inaiweka kwenye ukurasa wa Hadithi wa tovuti mara moja, na nakala kwenye monday.com."))
                .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 6)
        }
        // field users: drafting sends it for review
        if !admin && ["not-started", "collecting"].contains(st.status) {
            Text(t("“Draft the story” sends it to an admin for review. Nothing goes on the website before it is approved.", "“Andika rasimu ya hadithi” inaituma kwa msimamizi akague. Hakuna kinachoenda kwenye tovuti kabla ya kuidhinishwa."))
                .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 8)
        }
        if st.status == "draft" || (admin && (["approved", "published"].contains(st.status) || live)) {
            Btn(t("Back to Collecting", "Rudi kukusanya"), icon: "undo", enabled: work == nil, light: true, small: true, wide: true,
                busy: work == "back" ? (live ? t("Taking it off the website…", "Inaiondoa kwenye tovuti…") : t("Moving back…", "Inarudisha…")) : nil) {
                if live { askOff = true } else { work = "back"; post(["action": "collecting"], nil) { _ in work = nil } }
            }.padding(.top, 8)
        }
    }

    private func approve() {
        var b: JSON = ["action": "approve"]
        if doc != nil { b["doc"] = edited().out() } else { b["text"] = text }
        work = "approve"
        post(b, t("On the website ✓", "Iko kwenye tovuti ✓")) { _ in work = nil }
    }
}

/// "On the website ✓ · Open the story page": opens the story on the website.
struct StoryPubLink: View {
    let url: String
    var body: some View {
        Button { if !url.isEmpty { Share.open(url) } } label: {
            HStack(spacing: 12) {
                Icon("check", size: 18, color: .white).frame(width: 32, height: 32).background(C.ok).clipShape(Circle())
                VStack(alignment: .leading, spacing: 2) {
                    Text(t("On the website ✓", "Iko kwenye tovuti ✓")).font(.tSmall).foregroundColor(C.ink)
                    if !url.isEmpty { Text(t("Open the story page", "Fungua ukurasa wa hadithi")).font(.bSmall).foregroundColor(C.ok) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                if !url.isEmpty { Icon("next", size: 18, color: C.ok) }
            }
            .padding(.horizontal, 14).padding(.vertical, 10).frame(minHeight: 56)
            .background(C.okBg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.ok.opacity(0.35), lineWidth: 1))
        }
        .buttonStyle(Press()).disabled(url.isEmpty).padding(.top, 10)
    }
}

/// A section's photo: big, with its caption; for admins Change and Remove, or Add a photo.
struct SecPhoto: View {
    let pid: String; let ph: DocPhoto?; let admin: Bool; let change: () -> Void; let remove: () -> Void; let open: () -> Void
    var body: some View {
        if let ph {
            VStack(alignment: .leading, spacing: 0) {
                Button { if admin { change() } else { open() } } label: {
                    Color.clear.aspectRatio(16 / 10, contentMode: .fit)
                        .overlay(
                            ZStack(alignment: .topLeading) {
                                AuthPic(path: StoryKit.photoPath(pid, ph.id, w: 960))
                                if ph.children { KidsBadge(size: 11).padding(8) }
                            }
                        )
                        .background(C.tint).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(.plain).accessibilityLabel(ph.cap.isEmpty ? t("Photo", "Picha") : ph.cap)
                if !ph.cap.isEmpty { Text(ph.cap).font(.bSmall).italic().foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 4) }
                if admin {
                    // side by side, or one under the other on a narrow phone (no word split)
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 8) { changeBtn; removeBtn }
                        VStack(spacing: 8) { changeBtn; removeBtn }
                    }.padding(.top, 6)
                }
            }.padding(.top, 10)
        } else if admin {
            Btn(t("Add a photo", "Ongeza picha"), icon: "camera", light: true, small: true, wide: true, action: change).padding(.top, 10)
        }
    }
    private var changeBtn: some View { Btn(t("Change", "Badilisha"), icon: "refresh", light: true, small: true, wide: true, action: change) }
    private var removeBtn: some View { Btn(t("Remove", "Ondoa"), icon: "x", light: true, small: true, wide: true, action: remove) }
}

/// The story's quote, word for word.
struct PullQuote: View {
    let d: StoryDoc; let admin: Bool
    var body: some View {
        if d.hasPull {
            VStack(alignment: .leading, spacing: 4) {
                Text("“" + d.pullQuote + "”").font(.tMedium).italic().foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                if !d.pullWho.isEmpty { Text("— " + d.pullWho).font(.mont(12, .semibold)).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true) }
            }
            .padding(.leading, 16).padding(.trailing, 14).padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading).background(C.tint)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 16)
            if admin {
                Text(t("The quote is shown word for word. Change it under “Quote” above.", "Nukuu inaonyeshwa neno kwa neno. Ibadilishe chini ya “Nukuu” hapo juu."))
                    .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 6)
            }
        }
    }
}

/// The drafted story as an article: title, the line under it, sections with their photos, the quote.
/// `pullAfter`: the quote after that section (the reader puts it after the second), or at the end (-1).
struct StoryArticle: View {
    let pid: String; let d: StoryDoc; let big: Bool; let pullAfter: Int; let open: (DocPhoto) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(d.title).font(.hMedium).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true).accessibilityAddTraits(.isHeader)
            if !d.dek.isEmpty { Text(d.dek).font(.bLarge).italic().foregroundColor(big ? C.muted : C.text).fixedSize(horizontal: false, vertical: true).padding(.top, 6) }
            ForEach(Array(d.sections.enumerated()), id: \.offset) { i, x in
                section(x)
                if i == pullAfter { PullQuote(d: d, admin: false) }
            }
            if pullAfter < 0 || d.sections.count <= pullAfter { PullQuote(d: d, admin: false) }
        }
    }
    private func section(_ x: DocSection) -> some View {
        let paras = x.body.components(separatedBy: "\n\n").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        return VStack(alignment: .leading, spacing: 0) {
            if !x.h.isEmpty { Text(x.h).font(.tMedium).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true).accessibilityAddTraits(.isHeader) }
            if !big { SecPhoto(pid: pid, ph: x.photo, admin: false, change: {}, remove: {}, open: { if let ph = x.photo { open(ph) } }) }
            ForEach(Array(paras.enumerated()), id: \.offset) { _, para in
                Text(para).font(big ? .bLarge : .bMedium).foregroundColor(C.text).fixedSize(horizontal: false, vertical: true).padding(.top, 8)
            }
            if big { SecPhoto(pid: pid, ph: x.photo, admin: false, change: {}, remove: {}, open: { if let ph = x.photo { open(ph) } }) }
        }.padding(.top, big ? 20 : 16)
    }
}

/// A photo for a section, from the project's photos (story rows and building stages); or none.
struct StoryPoolSheet: View {
    let pid: String; let pool: [DocPhoto]; let head: String; let i: Int; let used: [String?]; let close: () -> Void; let choose: (DocPhoto?) -> Void
    var body: some View {
        let name = head.isEmpty ? "\(i + 1)" : head
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SheetHead(title: t("Photo for “\(name)”", "Picha ya “\(name)”"), close: close)
                if pool.isEmpty {
                    Text(t("No photos yet. Add them under Photos above, or with the building stages.", "Bado hakuna picha. Ziongeze chini ya Picha hapo juu, au pamoja na hatua za ujenzi."))
                        .font(.bMedium).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 12)
                }
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], alignment: .leading, spacing: 10) {
                    ForEach(Array(pool.enumerated()), id: \.offset) { _, ph in cell(ph) }
                }.padding(.top, 10)
                Btn(t("No photo for this section", "Hakuna picha kwa sehemu hii"), light: true, small: true, wide: true) { choose(nil) }.padding(.top, 14)
            }.padding(20)
        }
    }
    private func cell(_ ph: DocPhoto) -> some View {
        let on = i < used.count && used[i] == ph.id
        let other = used.firstIndex { $0 == ph.id }.flatMap { $0 != i ? $0 : nil }
        let rowName = StoryKit.ROWS.contains(ph.row) ? StoryKit.row(ph.row) : ph.row.hasPrefix("stage:") ? t("Building", "Ujenzi") : ph.row
        return Button { Haptic.tap(); choose(ph) } label: {
            VStack(alignment: .leading, spacing: 2) {
                Color.clear.aspectRatio(4 / 3, contentMode: .fit)
                    .overlay(ZStack(alignment: .topLeading) { AuthPic(path: StoryKit.photoPath(pid, ph.id, w: 320)); if ph.children { KidsBadge().padding(5) } })
                    .background(C.tint).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                Text(rowName).font(.lMedium).foregroundColor(C.ink).padding(.top, 2)
                if !ph.cap.isEmpty { Text(ph.cap).font(.bSmall).foregroundColor(C.muted).lineLimit(3).fixedSize(horizontal: false, vertical: true) }
                if let o = other { Text(t("In section \(o + 1)", "Kwenye sehemu \(o + 1)")).font(.lSmall).foregroundColor(C.blue2) }
            }
            .padding(6).frame(maxWidth: .infinity, alignment: .topLeading)
            .background(on ? C.tint : C.bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(on ? C.blue2 : .clear, lineWidth: 3))
        }
        .buttonStyle(.plain).accessibilityAddTraits(on ? .isSelected : [])
    }
}

// --- reading a story -------------------------------------------------------------------------------------------------

/// Stories tab: a story that already has words — View story (full page) or Edit story (the writer).
struct StoryChooseSheet: View {
    let title: String; let close: () -> Void; let view: () -> Void; let edit: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SheetHead(title: title, close: close)
            Btn(t("View story", "Tazama hadithi"), icon: "book", wide: true, action: view).padding(.top, 14)
            Btn(t("Edit story", "Hariri hadithi"), icon: "edit", light: true, wide: true, action: edit).padding(.top, 10)
            Spacer(minLength: 0)
        }.padding(20)
    }
}

/// The story as a full page: title, the line under it, sections with their photos, the quote; Open on the website at the top.
struct StoryReaderView: View {
    @EnvironmentObject var m: AppModel
    let pid: String; let close: () -> Void
    @State private var st: Story?
    @State private var err: String?
    @State private var view: DocPhoto?

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Button(action: close) { Icon("back", size: 24, color: C.ink).frame(width: 48, height: 48).contentShape(Circle()) }
                    .buttonStyle(.plain).accessibilityLabel(t("Back", "Rudi"))
                Spacer(minLength: 8)
                if let url = st?.pub?.url, !url.isEmpty {
                    Btn(t("Open on the website", "Fungua kwenye tovuti"), icon: "link", small: true) { Share.open(url) }
                }
            }.padding(.horizontal, 8).padding(.vertical, 6)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    page
                }.padding(.horizontal, 20).padding(.bottom, 28)
            }
        }
        .background(C.card.ignoresSafeArea())
        .preferredColorScheme(Look.mode == "dark" ? .dark : (Look.mode == "light" || Look.mode == "sun") ? .light : nil)
        .gesture(DragGesture(minimumDistance: 30).onEnded { g in if g.startLocation.x < 30 && g.translation.width > 80 { close() } })
        .task(id: pid) {
            let r = await m.api.get("/api/story?id=" + Api.enc(pid))
            if r.ok, let x = Story.from(r.json.obj("story")) { st = x }
            else { err = t("The story could not be opened. Check the signal and try again.", "Hadithi haikuweza kufunguliwa. Angalia mtandao kisha ujaribu tena.") }
        }
        .fullScreenCover(isPresented: isSet($view)) {
            if let ph = view { PhotoViewer(path: StoryKit.photoPath(pid, ph.id, w: 1600), title: ph.cap.isEmpty ? t("Photo", "Picha") : ph.cap) { view = nil }.environmentObject(m) }
        }
    }

    @ViewBuilder private var page: some View {
        if let err {
            Text(err).font(.bMedium).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 20)
        } else if let s = st {
            if let d = s.doc {
                StoryArticle(pid: pid, d: d, big: true, pullAfter: 1) { view = $0 }.padding(.top, 6)
            } else {
                let paras = s.text.components(separatedBy: "\n\n").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
                ForEach(Array(paras.enumerated()), id: \.offset) { _, para in
                    Text(para).font(.bLarge).foregroundColor(C.text).fixedSize(horizontal: false, vertical: true).padding(.top, 10)
                }
            }
            if s.pub == nil {
                Text(s.status == "approved" ? t("Approved, not on the website yet.", "Imeidhinishwa, bado haiko kwenye tovuti.") : t("A draft: not on the website yet.", "Rasimu: bado haiko kwenye tovuti."))
                    .font(.bSmall).foregroundColor(C.muted).padding(.top, 18)
            }
        } else {
            ForEach(0..<3, id: \.self) { _ in
                RoundedRectangle(cornerRadius: 16, style: .continuous).fill(C.bg).frame(height: 90).padding(.top, 16)
            }
        }
    }
}
