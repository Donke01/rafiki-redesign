import AVFoundation
import PhotosUI
import SwiftUI
import UIKit

/// The sheets of the story writer (one at a time).
enum StorySheet: Identifiable, Equatable {
    case add, tray, review, detail(String), dates(String)
    var id: String {
        switch self {
        case .add: return "add"
        case .tray: return "tray"
        case .review: return "review"
        case .detail(let a): return "detail-" + a
        case .dates(let r): return "dates-" + r
        }
    }
}

/// A project's Stories tab (the story writer): where the story is and what's still needed, the photos, the six
/// questions, the quote and photo consent, and the drafted story. `scroll` brings a part of the page into view.
struct StoryTab: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var up: StoryUploads
    let p: Project
    let scroll: (String) -> Void

    @State private var s: Story?
    @State private var err: String?
    @State private var saidTone = ""
    @State private var saidText: String?
    @State private var sheet: StorySheet?
    @State private var pickRow = "after"
    @State private var shots: [String] = []
    @State private var review: [String] = []
    @State private var reviewCam = false
    @State private var kids: Set<String> = []
    @State private var kidsAsk = ""
    @State private var camOpen = false
    @State private var galleryOpen = false
    @State private var picked: [PhotosPickerItem] = []
    @State private var askDraft = false
    @State private var coverBusy = false
    @State private var coverErr: String?
    @State private var datesBusy = false
    @State private var datesBad: String?
    @State private var demoDone = false

    private var admin: Bool { m.snap?.admin == true }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let st = s { loaded(st) }
            else { Text(err ?? t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(err != nil ? C.bad : C.muted).fixedSize(horizontal: false, vertical: true) }
        }
        .task(id: p.id) { await load() }
        .onChange(of: up.freshAt[p.id] ?? 0) { _ in
            if let j = up.fresh[p.id], let x = Story.from(j) { s = x }
        }
        .sheet(item: $sheet) { sh in sheetView(sh).environmentObject(m) }
        .fullScreenCover(isPresented: $camOpen) {
            CameraPicker { img in camDone(img) }.ignoresSafeArea()
        }
        .photosPicker(isPresented: $galleryOpen, selection: $picked, maxSelectionCount: 20, matching: .images)
        .onChange(of: picked) { items in gotPicked(items) }
        .alert(t("Write the draft again?", "Andika rasimu upya?"), isPresented: $askDraft) {
            Button(t("Cancel", "Ghairi"), role: .cancel) {}
            Button(t("Draft it again", "Andika rasimu upya")) { post(["action": "draft"], ok: t("Draft ready ✓", "Rasimu iko tayari ✓")) }
        } message: { Text(t("A new draft replaces this one, with any changes made to it.", "Rasimu mpya inachukua nafasi ya hii, pamoja na mabadiliko yaliyofanywa.")) }
    }

    // --- the page ----------------------------------------------------------------------------------------------------

    @ViewBuilder private func loaded(_ st: Story) -> some View {
        StoryStepper(st: st)
        StoryNeedCards(st: st, addTo: { r in openAdd(r) }, toConsent: { scroll("story_consent") }, toQuestions: { scroll("story_questions") },
                       toDraft: { scroll("story_draft") }, toDates: { sheet = .dates(storyDatesMissing(st).first ?? "before") })
        StoryNewPhotos(st: st, admin: admin) { askDraft = true }
        if let said = saidText { Msg(said, saidTone).id("story_msg") }
        StoryPhotosCard(up: up, pid: p.id, st: st, add: { r in openAdd(r) }, dates: { r in sheet = .dates(r) }, open: { ph in sheet = .detail(ph.id) })
            .padding(.top, 12).id("story_photos")
        StoryQuestions(p: p, st: st) { body, ok in post(body, ok: ok) }.id("story_questions")
        StoryQuoteCard(st: st, myName: m.prefs.name) { body, ok in post(body, ok: ok) }.padding(.top, 12)
        StoryDraftCard(p: p, st: st, admin: admin, post: { body, ok, after in post(body, ok: ok, after: after) }, draftAgain: { askDraft = true })
            .padding(.top, 12).id("story_draft")
    }

    // --- the sheets ----------------------------------------------------------------------------------------------------

    @ViewBuilder private func sheetView(_ sh: StorySheet) -> some View {
        switch sh {
        case .add:
            StoryAddSheet(title: p.title, row: pickRow, pickRow: { pickRow = $0 }, close: { sheet = nil },
                          gallery: { then { galleryOpen = true } }, camera: { shots = []; then { camera() } })
                .storySheet([.medium, .large])
        case .tray:
            StoryShotsTray(shots: shots, close: { sheet = nil; shots = [] }, another: { then { camera() } }) {
                let f = shots; shots = []
                toReview(f, cam: true)
            }
            .storySheet([.height(260), .medium])
        case .review:
            StoryReviewSheet(row: pickRow, files: review, kids: kids, ask: kidsAsk,
                             remove: { f in review.removeAll { $0 == f }; kids.remove(f); if review.isEmpty { sheet = nil } },
                             setAsk: { a in kidsAsk = a; if a == "no" { kids = [] } },
                             toggle: { f in if kids.contains(f) { kids.remove(f) } else { kids.insert(f) } },
                             close: { sheet = nil }) {
                let go = review.map { ($0, kids.contains($0)) }
                let row = pickRow, cam = reviewCam
                sheet = nil; review = []
                Task { await up.add(project: p.id, row: row, files: go, camera: cam) }
            }
            .storySheet()
        case .detail(let asset):
            detailSheet(asset)
        case .dates(let r):
            if let st = s {
                StoryDatesSheet(row: r, d: st.dates, hints: storyDateHint(st, r, done: p.done), busy: datesBusy, bad: datesBad,
                                close: { sheet = nil }) { vals in saveDates(vals) }
                    .storySheet()
            }
        }
    }

    @ViewBuilder private func detailSheet(_ asset: String) -> some View {
        if let st = s, let r = st.rowOf(asset), let ph = (st.photos[r] ?? []).first(where: { $0.id == asset }) {
            let isCover = st.cover == asset
            StoryPhotoSheet(pid: p.id, row: r, ph: ph, inStory: st.inStory.contains(asset), cover: isCover, coverBusy: coverBusy, coverErr: coverErr,
                            saveCap: { c in post(["action": "photo-edit", "row": r, "asset": asset, "cap": c], ok: t("Caption saved ✓", "Maelezo yamehifadhiwa ✓")) },
                            move: { to in post(["action": "photo-edit", "row": r, "asset": asset, "to": to], ok: t("Moved to \(StoryKit.row(to)) ✓", "Imehamishiwa \(StoryKit.row(to)) ✓")) },
                            kids: { k in post(["action": "photo-edit", "row": r, "asset": asset, "children": k]) },
                            delete: { sheet = nil; post(["action": "photo-remove", "row": r, "asset": asset], ok: t("Photo deleted", "Picha imefutwa")) },
                            setCover: { setCover(asset, isCover) },
                            close: { sheet = nil })
                .storySheet()
        } else {
            Color.clear.onAppear { sheet = nil }
        }
    }

    // --- talking to the server -----------------------------------------------------------------------------------------

    private func load() async {
        up.resume()
        let r = await m.api.get("/api/story?id=" + Api.enc(p.id))
        if r.ok, let x = Story.from(r.json.obj("story")) { s = x; demoSetUp() } else { err = reasonText(r.reason) }
    }

    /// Every POST answers with the fresh story.
    private func post(_ body: JSON, ok: String? = nil, after: ((Reply) -> Void)? = nil) {
        var b = body
        b["id"] = p.id
        Task {
            let r = await m.api.postTo("/api/story", b)
            if r.ok {
                if let x = Story.from(r.json.obj("story")) { s = x }
                if let ok { saidTone = "ok"; saidText = ok }
                m.refresh()
            } else {
                saidTone = "bad"; saidText = StoryKit.why(r)
            }
            after?(r)
        }
    }

    /// The story's cover: this photo, or "" for the automatic choice.
    private func setCover(_ asset: String, _ isCover: Bool) {
        guard !coverBusy else { return }
        coverBusy = true; coverErr = nil
        Task {
            let res = await m.api.postTo("/api/story", ["action": "cover", "id": p.id, "asset": isCover ? "" : asset])
            coverBusy = false
            if res.ok {
                if let x = Story.from(res.json.obj("story")) { s = x }
                saidTone = "ok"
                saidText = isCover ? t("Cover back to automatic", "Jalada limerudi kuchaguliwa lenyewe") : t("Cover photo set ✓", "Picha ya jalada imewekwa ✓")
            } else { coverErr = StoryKit.why(res) }
        }
    }

    private func saveDates(_ vals: [String: String]) {
        datesBusy = true; datesBad = nil
        Task {
            var body: JSON = ["action": "dates", "id": p.id]
            vals.forEach { body[$0.key] = $0.value }
            let res = await m.api.postTo("/api/story", body)
            datesBusy = false
            if res.ok {
                if let x = Story.from(res.json.obj("story")) { s = x }
                sheet = nil
                saidTone = "ok"; saidText = t("Dates saved ✓", "Tarehe zimehifadhiwa ✓")
            } else { datesBad = storyDateWhy(res.status == 0 ? "offline" : res.reason, res.json.str("field")) }
        }
    }

    // --- adding photos --------------------------------------------------------------------------------------------------

    private func openAdd(_ r: String) { pickRow = r; sheet = .add }

    /// Closes the sheet, then does the next thing once it has gone (two sheets can't be up at once).
    private func then(_ f: @escaping () -> Void) {
        sheet = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { f() }
    }

    private func camera() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .denied, .restricted:
            saidTone = "warn"
            saidText = t("Allow the camera in Settings to take photos, or choose from your photos.", "Ruhusu kamera kwenye Settings ili kupiga picha, au chagua kutoka kwenye picha zako.")
        default: camOpen = true
        }
    }

    private static var pickDir: URL {
        let u = FileManager.default.temporaryDirectory.appendingPathComponent("story-pick", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    /// A camera shot: kept in the temporary folder, then the tray (Take another / Add them).
    private func camDone(_ img: UIImage?) {
        camOpen = false
        if let img, let data = img.jpegData(compressionQuality: 0.92) {
            let f = StoryTab.pickDir.appendingPathComponent("c\(Int(Date().timeIntervalSince1970 * 1000)).jpg")
            if (try? data.write(to: f)) != nil { shots.append(f.path) }
        }
        if !shots.isEmpty { DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { sheet = .tray } }
    }

    /// Photos chosen in the gallery: copied as they are (with the day they were taken), then the review sheet.
    private func gotPicked(_ items: [PhotosPickerItem]) {
        guard !items.isEmpty else { return }
        picked = []
        Task {
            var files: [String] = []
            let stamp = Int(Date().timeIntervalSince1970 * 1000)
            for (i, it) in items.enumerated() {
                if let d = try? await it.loadTransferable(type: Data.self) {
                    let f = StoryTab.pickDir.appendingPathComponent("g\(stamp)_\(i).img")
                    if (try? d.write(to: f)) != nil { files.append(f.path) }
                }
            }
            if !files.isEmpty { toReview(files, cam: false) }
        }
    }

    private func toReview(_ files: [String], cam: Bool) {
        review = files; reviewCam = cam; kids = []; kidsAsk = ""
        if sheet != nil { then { sheet = .review } } else { sheet = .review }
    }

    // --- demo screenshots ---------------------------------------------------------------------------------------------

    private func demoSetUp() {
        guard m.demo, !demoDone else { return }
        demoDone = true
        let screen = Demo.arg("-screen") ?? ""
        if screen == "story-photos" {
            up.demoShow(Demo.uploads(p.id, up.dir))
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { scroll("story_photos") }
        }
        switch Demo.arg("-sheet") ?? "" {
        case "add": openAdd("after")
        case "detail": if let a = s?.photos["after"]?.first?.id { sheet = .detail(a) }
        case "dates": sheet = .dates("progress")
        case "review": pickRow = "before"; review = Demo.pickFiles(); kidsAsk = "yes"; kids = Set(review.prefix(1)); sheet = .review
        case "tray": shots = Demo.pickFiles(); sheet = .tray
        default: break
        }
    }
}

/// The six questions as a guided flow, one card at a time: type an answer, or record it (written out when the server can).
struct StoryQuestions: View {
    @EnvironmentObject var m: AppModel
    let p: Project; let st: Story; let save: (JSON, String) -> Void
    @State private var at = -1
    @State private var answer = ""
    @State private var voice: String?
    @State private var vmsg: String?

    private var cur: Int { at >= 0 ? at : max(0, StoryKit.QS.firstIndex { (st.answers[$0] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } ?? 0) }

    var body: some View {
        let i = cur
        let q = StoryKit.QS[i]
        CardV {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(t("Questions", "Maswali")).font(.tMedium).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading)
                Text(t("\(st.filled) of 6 answered", "\(st.filled) kati ya 6 yamejibiwa")).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.trailing)
            }
            dots(i).padding(.top, 10)
            Text(t("Question \(i + 1) of 6", "Swali \(i + 1) kati ya 6")).font(.lMedium).foregroundColor(C.muted).padding(.top, 12)
            Text(StoryKit.prompt(st, q)).font(.tMedium).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true).padding(.top, 2)
            Field(t("Answer", "Jibu"), $answer, lines: 4)
            VoiceNote(path: $voice)
            Msg(vmsg)
            Btn(i < 5 ? t("Save and next", "Hifadhi na uendelee") : t("Save", "Hifadhi"), icon: "check", small: true, wide: true) {
                save(["action": "answers", q: answer.trimmingCharacters(in: .whitespacesAndNewlines)], t("Saved ✓", "Imehifadhiwa ✓"))
                if i < 5 { at = i + 1 }
            }.padding(.top, 12)
            if i > 0 { Btn(t("Previous question", "Swali lililopita"), light: true, small: true, wide: true) { at = i - 1 }.padding(.top, 8) }
        }
        .padding(.top, 12)
        .onAppear { if at < 0 { at = cur; answer = st.answers[StoryKit.QS[at]] ?? "" } }
        .onChange(of: at) { n in
            if n >= 0 { answer = st.answers[StoryKit.QS[n]] ?? "" }
            voice = nil; vmsg = nil
        }
        .onChange(of: st.answers) { a in if at >= 0, answer.isEmpty { answer = a[StoryKit.QS[at]] ?? "" } }
        .onChange(of: voice) { v in if let v { sendVoice(v, q) } }
    }

    /// Jump to any question; answered ones are ticked.
    private func dots(_ i: Int) -> some View {
        HStack(spacing: 6) {
            ForEach(0..<6, id: \.self) { k in
                let has = !(st.answers[StoryKit.QS[k]] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                Button { Haptic.tap(); at = k } label: {
                    ZStack {
                        Circle().fill(k == i ? C.blue2 : has ? C.okBg : C.tint)
                        if has && k != i { Icon("check", size: 16, color: C.ok) }
                        else { Text("\(k + 1)").font(.lLarge).foregroundColor(k == i ? .white : C.ink) }
                    }.frame(width: 40, height: 40).frame(minWidth: 40, minHeight: 44).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(t("Question \(k + 1)", "Swali \(k + 1)"))
                .accessibilityAddTraits(k == i ? .isSelected : [])
            }
            Spacer(minLength: 0)
        }
    }

    /// A voice answer: sent to the server, which writes it out when speech-to-text is on (else keeps it on monday.com).
    private func sendVoice(_ path: String, _ q: String) {
        guard let data = try? Data(contentsOf: Outbox.resolve(path)) else { return }
        vmsg = t("Sending the recording…", "Inatuma sauti…")
        Task {
            let r = await m.api.multipart("/api/story", data: data, mime: "audio/mp4", filename: "answer.m4a", fields: [("action", "voice"), ("id", p.id), ("q", q)])
            if !r.ok { vmsg = StoryKit.why(r) }
            else if r.json.bool("typed") {
                answer = r.json.str("text")
                vmsg = t("Written out from your recording ✓ Check it, then save.", "Imeandikwa kutoka kwa sauti yako ✓ Iangalie, kisha uhifadhi.")
            } else { vmsg = t("Kept as a voice note on monday.com.", "Imehifadhiwa kama ujumbe wa sauti kwenye monday.com.") }
        }
    }
}

/// The quote (what they said, who said it, the photo credit) and photo consent.
struct StoryQuoteCard: View {
    let st: Story; let myName: String; let post: (JSON, String?) -> Void
    @State private var quote = ""
    @State private var by = ""
    @State private var credit = ""

    var body: some View {
        CardV {
            Text(t("Quote", "Nukuu")).font(.tMedium).foregroundColor(C.ink)
            Field(t("What they said", "Walichosema"), $quote, placeholder: t("e.g. “Now my girls get to school on time.”", "mf. “Sasa wasichana wangu wanafika shuleni kwa wakati.”"), lines: 3)
            Field(t("Who said it", "Aliyesema"), $by, placeholder: t("e.g. Mary, mother of three", "mf. Mary, mama wa watoto watatu"))
            Field(t("Photo credit", "Mpiga picha"), $credit, placeholder: myName)
            Btn(t("Save quote", "Hifadhi nukuu"), icon: "check", light: true, small: true, wide: true) {
                post(["action": "answers", "quote": quote.trimmingCharacters(in: .whitespacesAndNewlines), "quoteBy": by.trimmingCharacters(in: .whitespacesAndNewlines),
                      "credit": credit.trimmingCharacters(in: .whitespacesAndNewlines)], t("Saved ✓", "Imehifadhiwa ✓"))
            }.padding(.top, 12)
            Text(t("Photo consent", "Idhini ya picha")).font(.lLarge).foregroundColor(C.ink).padding(.top, 16).padding(.bottom, 6).id("story_consent")
            HStack(spacing: 6) {
                ForEach([("received", t("Received", "Imepokelewa")), ("not-yet", t("Not yet", "Bado"))], id: \.0) { k, l in
                    Pill(l, on: st.consent == k) { if st.consent != k { Haptic.tap(); post(["action": "consent", "value": k], nil) } }
                }
            }
            if st.allPhotos.contains(where: { $0.children }) {
                Text(t("Children are in a photo, so consent is needed before it’s approved.", "Kuna watoto kwenye picha, kwa hiyo idhini inahitajika kabla ya kuidhinishwa."))
                    .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 6)
            }
        }
        .onAppear { quote = st.quote; by = st.quoteBy; credit = st.credit.isEmpty ? myName : st.credit }
        .onChange(of: st.quote) { quote = $0 }
        .onChange(of: st.quoteBy) { by = $0 }
        .onChange(of: st.credit) { v in credit = v.isEmpty ? myName : v }
    }
}
