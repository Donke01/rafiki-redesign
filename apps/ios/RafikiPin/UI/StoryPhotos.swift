import SwiftUI
import UIKit

// Story photos (as Android 2.27–2.28): the story's steps, what is still needed as cards, the Photos card (rows, a
// 3-column grid, "In story" badges, dashed Add photos tiles), the add flow (gallery with several at once, or take
// photos one after another), the review sheet (children?), tiles that fill as they send, a banner over the bottom
// bar, a details sheet per photo (caption, row, children, cover, delete) and the month of each column.

/// The four steps: Collecting → Draft ready → Approved → On the website, as dots on a line with their names.
struct StoryStepper: View {
    let st: Story
    private var steps: [(String, String)] {
        [("collecting", t("Collecting", "Inakusanywa")), ("draft", t("Draft ready", "Rasimu iko tayari")),
         ("approved", t("Approved", "Imeidhinishwa")), ("published", t("On the website", "Kwenye tovuti"))]
    }
    private var cur: Int { st.live ? 3 : st.status == "approved" ? 2 : st.status == "draft" ? 1 : 0 }

    var body: some View {
        let s = steps
        let c = cur
        CardV(pad: 12) {
            HStack(spacing: 8) {
                Text(t("The story", "Hadithi")).font(.tLarge).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading)
                Text(st.status == "not-started" && !st.live ? t("Not started", "Haijaanza") : s[c].1)
                    .font(.lMedium).foregroundColor(st.live ? C.ok : C.blue2).lineLimit(1).minimumScaleFactor(0.75)
                    .padding(.horizontal, 10).padding(.vertical, 5).background(st.live ? C.okBg : C.tint).clipShape(Capsule())
            }
            ZStack(alignment: .top) {
                lines(c).frame(height: 28)
                HStack(alignment: .top, spacing: 0) {
                    ForEach(0..<4, id: \.self) { i in dot(i, s[i].1, c).frame(maxWidth: .infinity) }
                }
            }.padding(.top, 14)
        }
    }

    private func lines(_ c: Int) -> some View {
        GeometryReader { g in
            let col = g.size.width / 4
            let y = g.size.height / 2
            ZStack {
                ForEach(0..<3, id: \.self) { i in
                    Path { p in
                        p.move(to: CGPoint(x: col * CGFloat(i) + col / 2 + 16, y: y))
                        p.addLine(to: CGPoint(x: col * CGFloat(i + 1) + col / 2 - 16, y: y))
                    }
                    .stroke(i < c || st.live ? C.ok : C.line, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                }
            }
        }
    }

    private func dot(_ i: Int, _ label: String, _ c: Int) -> some View {
        let done = i < c || (st.live && i == 3)
        let now = i == c && !st.live && st.status != "not-started"
        return VStack(spacing: 6) {
            ZStack {
                Circle().fill(done ? C.ok : now ? C.card : C.line).frame(width: 28, height: 28)
                if now { Circle().stroke(C.blue2, lineWidth: 3).frame(width: 25, height: 25); Circle().fill(C.blue2).frame(width: 12, height: 12) }
                if done { Icon("check", size: 16, color: .white) }
            }
            FitWords(label, size: 11, weight: now ? .heavy : .semibold, color: done || now ? C.ink : C.muted, lines: 2, align: .center, min: 0.64)
                .padding(.horizontal, 2)
        }
        .accessibilityElement(children: .combine)
        .accessibilityValue(done ? t("done", "imekamilika") : now ? t("now", "sasa") : t("to come", "inakuja"))
    }
}

/// What is still needed, as cards that each go and do it.
struct StoryNeedCards: View {
    let st: Story
    let addTo: (String) -> Void; let toConsent: () -> Void; let toQuestions: () -> Void; let toDraft: () -> Void; let toDates: () -> Void

    var body: some View {
        let miss = st.live || ["approved", "published"].contains(st.status) ? [] : st.missing
        if !miss.isEmpty {
            VStack(spacing: 10) {
                ForEach(miss, id: \.self) { k in card(k) }
            }.padding(.top, 12)
        }
    }

    @ViewBuilder private func card(_ k: String) -> some View {
        if k.hasSuffix("-photos") {
            let r0 = String(k.dropLast(7))
            let row = StoryKit.ROWS.contains(r0) ? r0 : "after"
            NeedCard(icon: "camera",
                     title: row == "before" ? t("Add before photos", "Ongeza picha za kabla") : row == "progress" ? t("Add in-progress photos", "Ongeza picha za wakati wa kazi") : t("Add after photos", "Ongeza picha za baada"),
                     sub: row == "after" ? t("The story ends with what changed. 2–3 photos of the finished project.", "Hadithi inaishia na kilichobadilika. Picha 2–3 za mradi uliokamilika.")
                        : row == "before" ? t("How it was before the work began.", "Ilivyokuwa kabla kazi haijaanza.") : t("The work as it happened.", "Kazi ilivyokuwa ikifanyika."),
                     action: t("Add", "Ongeza"), actionIcon: "camera") { addTo(row) }
        } else if k.hasPrefix("answers:") {
            let parts = k.dropFirst(8).split(separator: "/").map(String.init)
            let a = parts.first ?? "0", b = parts.count > 1 ? parts[1] : "6"
            NeedCard(icon: "text", title: t("Answer the questions", "Jibu maswali"), sub: t("\(a) of \(b) answers so far.", "Majibu \(a) kati ya \(b) hadi sasa."),
                     action: t("Answer", "Jibu"), actionIcon: "next", go: toQuestions)
        } else if k == "consent" {
            NeedCard(icon: "shield", title: t("Photo consent", "Idhini ya picha"),
                     sub: t("Not received yet. It is needed before photos of children go on the website.", "Bado haijapokelewa. Inahitajika kabla picha za watoto hazijaenda kwenye tovuti."),
                     action: t("Open", "Fungua"), actionIcon: "next", go: toConsent)
        } else if k == "dates", !storyDatesMissing(st).isEmpty {
            let n = storyDatesMissing(st).map { StoryKit.row($0) }
            let names = n.count == 1 ? n[0] : n.dropLast().joined(separator: ", ") + t(" and ", " na ") + (n.last ?? "")
            NeedCard(icon: "cal", title: t("Add the dates for \(names)", "Ongeza tarehe za \(names)"),
                     sub: t("The month of each part, so the story tells things in the right order.", "Mwezi wa kila sehemu, ili hadithi ieleze mambo kwa mpangilio sahihi."),
                     action: t("Add", "Ongeza"), actionIcon: "cal", go: toDates)
        } else if k == "text" {
            NeedCard(icon: "spark", title: t("Draft the story", "Andika rasimu ya hadithi"), sub: t("When the answers and photos are in.", "Majibu na picha zikiwa tayari."),
                     action: t("Open", "Fungua"), actionIcon: "next", go: toDraft)
        }
    }
}

/// An amber card: what is needed, a line, and a button that goes and does it (under the words on a narrow phone).
struct NeedCard: View {
    let icon: String; let title: String; let sub: String; let action: String; let actionIcon: String; let go: () -> Void

    private var btn: some View {
        let bg = C.dark ? Color(hex: 0xF2B35B) : Color(hex: 0x7A4A00)
        let fg = C.dark ? Color(hex: 0x071521) : Color.white
        return Button { Haptic.tap(); go() } label: {
            HStack(spacing: 6) {
                Icon(actionIcon, size: 16, color: fg)
                Text(action).font(.mont(14, .bold)).foregroundColor(fg).lineLimit(1).fixedSize()
            }
            .padding(.horizontal, 14).frame(minHeight: 44).background(bg).clipShape(Capsule())
        }.buttonStyle(Press())
    }
    private var words: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.tSmall).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
            Text(sub).font(.bSmall).foregroundColor(C.text).fixedSize(horizontal: false, vertical: true)
        }
    }

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                Icon(icon, size: 22, color: C.warn)
                words.frame(minWidth: 170, idealWidth: 170, maxWidth: .infinity, alignment: .leading)
                btn
            }
            HStack(alignment: .top, spacing: 12) {
                Icon(icon, size: 22, color: C.warn)
                VStack(alignment: .leading, spacing: 10) { words; btn }.frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(C.warnBg)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.warn.opacity(0.35), lineWidth: 1))
    }
}

/// "2 new photos since this draft. Draft again to use them." (or, on the website, where admins add them).
struct StoryNewPhotos: View {
    let st: Story; let admin: Bool; let draftAgain: () -> Void
    var body: some View {
        let n = st.newPhotos
        if n > 0, st.doc != nil, !(st.live && !admin) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top, spacing: 10) {
                    Icon("spark", size: 20, color: C.blue2)
                    Text(line(n)).font(.mont(14, .semibold)).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if !st.live { Btn(t("Draft it again", "Andika rasimu upya"), icon: "spark", light: true, small: true, wide: true, action: draftAgain).padding(.top, 10) }
            }
            .padding(14).frame(maxWidth: .infinity, alignment: .leading).background(C.tint)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.blue.opacity(0.3), lineWidth: 1))
            .padding(.top, 12)
        }
    }
    private func line(_ n: Int) -> String {
        if st.live {
            return n == 1 ? t("1 new photo since this story went on the website. Add it in the story editor below.", "Picha 1 mpya tangu hadithi hii iwekwe kwenye tovuti. Iongeze kwenye kihariri cha hadithi hapa chini.")
                : t("\(n) new photos since this story went on the website. Add them in the story editor below.", "Picha \(n) mpya tangu hadithi hii iwekwe kwenye tovuti. Ziongeze kwenye kihariri cha hadithi hapa chini.")
        }
        return n == 1 ? t("1 new photo since this draft. Draft again to use it.", "Picha 1 mpya tangu rasimu hii. Andika rasimu upya ili kuitumia.")
            : t("\(n) new photos since this draft. Draft again to use them.", "Picha \(n) mpya tangu rasimu hii. Andika rasimu upya ili kuzitumia.")
    }
}

// --- the Photos card --------------------------------------------------------------------------------------------------

/// The Photos card: Before, In progress and After, each a 3-column grid that ends with a dashed Add photos tile.
struct StoryPhotosCard: View {
    @ObservedObject var up: StoryUploads
    let pid: String; let st: Story
    let add: (String) -> Void; let dates: (String) -> Void; let open: (StoryPhoto) -> Void

    var body: some View {
        let inStory = st.inStory
        let shown = st.allPhotos.filter { inStory.contains($0.id) }.count
        CardV(pad: 16) {
            HStack(spacing: 8) {
                Text(t("Photos", "Picha")).font(.tLarge).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading)
                if shown > 0 {
                    HStack(spacing: 4) {
                        Image(systemName: "sparkle").font(.system(size: 11, weight: .bold)).foregroundColor(C.ok)
                        Text(t("\(shown) in the story", "\(shown) kwenye hadithi")).font(.lMedium).foregroundColor(C.ok).lineLimit(1)
                    }
                }
            }
            ForEach(StoryKit.ROWS, id: \.self) { r in rowView(r, inStory) }
            Text(t("Tap a photo to add a caption, move it to another row, or mark that it shows children.", "Gusa picha kuongeza maelezo, kuihamisha safu nyingine, au kuonyesha kwamba ina watoto."))
                .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 12)
        }
    }

    private func rowView(_ r: String, _ inStory: Set<String>) -> some View {
        let server = st.photos[r] ?? []
        let ids = Set(server.map { $0.id })
        let mine = up.items.filter { $0.row == r && $0.project == pid }
        let ticked = Set(mine.filter { $0.state == "done" && ids.contains($0.asset) }.map { $0.asset })
        let local = mine.filter { !($0.state == "done" && ids.contains($0.asset)) }
        let count = server.count + local.filter { $0.state != "done" }.count
        return VStack(alignment: .leading, spacing: 0) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { rowTitle(r, count); dateChip(r, server.isEmpty); Spacer(minLength: 0) }
                VStack(alignment: .leading, spacing: 4) { rowTitle(r, count); dateChip(r, server.isEmpty) }
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                ForEach(Array(server.enumerated()), id: \.element.id) { i, ph in
                    ServerTile(pid: pid, row: r, i: i, ph: ph, inStory: inStory.contains(ph.id), sent: ticked.contains(ph.id), cover: ph.id == st.cover) { open(ph) }
                }
                ForEach(local) { u in LocalTile(up: up, u: u) }
                AddTile { add(r) }
            }.padding(.top, 8)
        }.padding(.top, 14)
    }

    private func rowTitle(_ r: String, _ n: Int) -> some View {
        HStack(spacing: 8) {
            Text(StoryKit.row(r)).font(.tSmall).foregroundColor(C.ink).lineLimit(1).fixedSize()
            Text("\(n)").font(.lMedium).foregroundColor(C.muted).padding(.horizontal, 8).padding(.vertical, 2).background(C.bg).clipShape(Capsule()).fixedSize()
        }
    }

    /// The column's month ("Nov 2024", "Jan – Mar 2025"), or a small Add date; tap to set it.
    private func dateChip(_ r: String, _ empty: Bool) -> some View {
        let when = colDateText(st.dates, r)
        let need = when == nil && st.missing.contains("dates") && !empty
        let fg = need ? C.warn : C.blue2
        return Button { Haptic.tap(); dates(r) } label: {
            HStack(spacing: 5) {
                Icon("cal", size: 15, color: fg)
                Text(when ?? t("Add date", "Ongeza tarehe")).font(.mont(12, .bold)).foregroundColor(fg).lineLimit(1).fixedSize()
            }
            .padding(.horizontal, 10).frame(minHeight: 36)
            .background(when != nil ? Color.clear : need ? C.warnBg : C.tint).clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(StoryKit.row(r) + " · " + (when ?? t("Add date", "Ongeza tarehe")))
    }
}

/// A photo on the server: "In story", "Children", the cover star and a tick when it just arrived.
struct ServerTile: View {
    let pid: String; let row: String; let i: Int; let ph: StoryPhoto; let inStory: Bool; let sent: Bool; let cover: Bool; let tap: () -> Void
    var body: some View {
        Button(action: tap) {
            SquareTile {
                ZStack {
                    AuthPic(path: StoryKit.photoPath(pid, ph.id))
                    if inStory { InStoryBadge().padding(5).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading) }
                    if ph.children {
                        KidsBadge().padding(.horizontal, 5).padding(.top, cover ? 35 : 5)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    }
                    if cover || sent {
                        HStack(spacing: 4) { if cover { CoverBadge() }; if sent { SentTick() } }
                            .padding(5).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    }
                }
            }
        }
        .buttonStyle(Press())
        .accessibilityLabel(label)
    }
    private var label: String {
        var s = StoryKit.row(row) + " · " + t("photo \(i + 1)", "picha \(i + 1)")
        if cover { s += " · " + t("cover photo", "picha ya jalada") }
        if inStory { s += " · " + t("in the story", "kwenye hadithi") }
        if !ph.cap.isEmpty { s += " · " + ph.cap }
        return s
    }
}

/// A photo that is on its way: the photo from the phone, with a ring and %, waiting for signal, or Tap to retry.
struct LocalTile: View {
    @ObservedObject var up: StoryUploads
    let u: StoryUp
    var body: some View {
        SquareTile {
            ZStack {
                LocalPic(path: up.url(u).path)
                stateLayer
            }
        }
        .onTapGesture { if u.state == "failed" { Haptic.tap(); up.retry(u.key) } }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(u.state == "failed" ? .isButton : [])
    }

    @ViewBuilder private var stateLayer: some View {
        switch u.state {
        case "done":
            SentTick().padding(5).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
        case "waiting":
            VStack(spacing: 4) {
                Icon("offline", size: 22, color: .white)
                FitWords(t("Waiting for signal", "Inasubiri mtandao"), size: 11, color: .white, lines: 2, align: .center)
            }
            .padding(4).frame(maxWidth: .infinity, maxHeight: .infinity).background(StoryNight.opacity(0.55))
        case "failed":
            VStack(spacing: 4) {
                Icon("refresh", size: 18, color: Color(hex: 0xB3261E)).frame(width: 32, height: 32).background(Color.white).clipShape(Circle())
                FitWords(t("Tap to retry", "Gusa ujaribu tena"), size: 11, color: .white, lines: 2, align: .center)
            }
            .padding(4).frame(maxWidth: .infinity, maxHeight: .infinity).background(StoryNight.opacity(0.55))
        default:
            ZStack {
                StoryNight.opacity(0.4)
                SendRing(progress: u.progress, queued: u.state == "queued")
                Text("\(Int(u.progress * 100))%").font(.lMedium).foregroundColor(.white)
            }
        }
    }
}

/// The ring on a sending tile: fills as it sends; a short turning arc while it waits its turn.
struct SendRing: View {
    let progress: Double; let queued: Bool
    @State private var turn = false
    var body: some View {
        ZStack {
            Circle().stroke(Color.white.opacity(0.33), lineWidth: 4)
            if queued {
                if Motion.on {
                    Circle().trim(from: 0, to: 0.2).stroke(Color.white, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                        .rotationEffect(.degrees(turn ? 360 : 0))
                        .animation(.linear(duration: 1.1).repeatForever(autoreverses: false), value: turn)
                        .onAppear { turn = true }
                }
            } else {
                Circle().trim(from: 0, to: max(0.001, min(1, progress))).stroke(Color.white, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(Motion.on ? .easeOut(duration: 0.25) : nil, value: progress)
            }
        }.frame(width: 50, height: 50)
    }
}

/// The last tile of a row: a dashed "+ Add photos".
struct AddTile: View {
    let tap: () -> Void
    var body: some View {
        Button { Haptic.tap(); tap() } label: {
            Color.clear.aspectRatio(1, contentMode: .fit)
                .overlay(
                    VStack(spacing: 4) {
                        Icon("plus", size: 26, color: C.blue2)
                        FitWords(t("Add photos", "Ongeza picha"), size: 12, color: C.blue2, lines: 2, align: .center)
                    }.padding(6)
                )
                .background(C.tint.opacity(0.55))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(C.blue.opacity(0.55), style: StrokeStyle(lineWidth: 1.5, dash: [6, 4])))
        }.buttonStyle(Press())
    }
}

// --- adding photos ----------------------------------------------------------------------------------------------------

/// Add photos: which part of the story, then Choose from gallery (several at once) or Take photos.
struct StoryAddSheet: View {
    let title: String; let row: String; let pickRow: (String) -> Void; let close: () -> Void; let gallery: () -> Void; let camera: () -> Void
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SheetHead(title: t("Add photos", "Ongeza picha"), sub: title, close: close)
                Text(t("Which part of the story?", "Sehemu gani ya hadithi?")).font(.lLarge).foregroundColor(C.ink).padding(.top, 16).padding(.bottom, 8)
                RowSeg(sel: row, pick: pickRow)
                AddOption(icon: "grid", title: t("Choose from gallery", "Chagua kutoka kwenye picha"),
                          sub: t("Pick several at once — tap each photo you want.", "Chagua kadhaa kwa mara moja — gusa kila picha unayotaka."), main: true, go: gallery)
                AddOption(icon: "camera", title: t("Take photos", "Piga picha"),
                          sub: t("Keep shooting, then add them all together.", "Endelea kupiga, kisha uziongeze zote pamoja."), main: false, go: camera)
                HStack(alignment: .top, spacing: 8) {
                    Icon("down", size: 16, color: C.ok)
                    Text(t("Photos are made smaller before sending to save data. No signal? They wait and send by themselves.", "Picha zinapunguzwa ukubwa kabla ya kutumwa ili kuokoa data. Hakuna mtandao? Zinasubiri na kujituma zenyewe."))
                        .font(.bSmall).foregroundColor(C.ok).fixedSize(horizontal: false, vertical: true)
                }.padding(.top, 14)
            }.padding(20)
        }
    }
}

private struct AddOption: View {
    let icon: String; let title: String; let sub: String; let main: Bool; let go: () -> Void
    var body: some View {
        let fg: Color = main ? .white : C.ink
        let fg2: Color = main ? Color.white.opacity(0.9) : C.muted
        Button { Haptic.tap(); go() } label: {
            HStack(spacing: 14) {
                Icon(icon, size: 24, color: main ? .white : C.blue2).frame(width: 48, height: 48)
                    .background(main ? Color.white.opacity(0.2) : C.card).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.tMedium).foregroundColor(fg).fixedSize(horizontal: false, vertical: true)
                    Text(sub).font(.bSmall).foregroundColor(fg2).fixedSize(horizontal: false, vertical: true)
                }.frame(maxWidth: .infinity, alignment: .leading)
                Icon("chev", size: 18, color: main ? .white : C.blue2)
            }
            .padding(14).frame(maxWidth: .infinity, minHeight: 76, alignment: .leading)
            .background(Group { if main { LinearGradient(colors: [Color(hex: 0x0A78C2), Color(hex: 0x076DB1)], startPoint: .topLeading, endPoint: .bottomTrailing) } else { C.tint } })
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .buttonStyle(Press()).padding(.top, 12)
    }
}

/// Taking photos one after another: "2 taken · Take another / Add 2 photos".
struct StoryShotsTray: View {
    let shots: [String]; let close: () -> Void; let another: () -> Void; let done: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                Text(shots.count == 1 ? t("1 taken", "1 imepigwa") : t("\(shots.count) taken", "\(shots.count) zimepigwa")).font(.tLarge).foregroundColor(C.ink)
                    .frame(maxWidth: .infinity, alignment: .leading)
                CloseX(action: close)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(shots, id: \.self) { f in
                        LocalPic(path: f, px: 240).frame(width: 72, height: 72).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                }
            }.padding(.top, 12)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { moreBtn; addBtn }
                VStack(spacing: 8) { moreBtn; addBtn }
            }.padding(.top, 14)
            Spacer(minLength: 0)
        }.padding(20)
    }
    private var moreBtn: some View { Btn(t("Take another", "Piga nyingine"), icon: "camera", light: true, small: true, wide: true, action: another) }
    private var addBtn: some View {
        Btn(shots.count == 1 ? t("Add 1 photo", "Ongeza picha 1") : t("Add \(shots.count) photos", "Ongeza picha \(shots.count)"), icon: "check", small: true, wide: true, action: done)
    }
}

/// Check the photos: remove any, say which show children, then "Add N photos to After".
struct StoryReviewSheet: View {
    let row: String; let files: [String]; let kids: Set<String>; let ask: String
    let remove: (String) -> Void; let setAsk: (String) -> Void; let toggle: (String) -> Void; let close: () -> Void; let add: () -> Void

    var body: some View {
        let n = files.count
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SheetHead(title: t("Check the photos", "Angalia picha"), sub: t("For “\(StoryKit.row(row))”", "Kwa “\(StoryKit.row(row))”"), close: close)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                    ForEach(Array(files.enumerated()), id: \.element) { i, f in tile(i, f) }
                }.padding(.top, 12)
                Text(t("Do any of these show children?", "Je, yoyote kati ya hizi inaonyesha watoto?")).font(.tSmall).foregroundColor(C.ink)
                    .fixedSize(horizontal: false, vertical: true).padding(.top, 16)
                HStack(spacing: 8) {
                    Pill(t("No", "Hapana"), on: ask == "no") { setAsk("no") }
                    Pill(t("Yes", "Ndiyo"), on: ask == "yes") { setAsk("yes") }
                }.padding(.top, 8)
                if ask == "yes" {
                    Text(kids.isEmpty ? t("Tap the ones that do.", "Gusa zile zinazoonyesha.") : t("\(kids.count) marked. Consent is needed before they go on the website.", "\(kids.count) zimewekwa alama. Idhini inahitajika kabla hazijaenda kwenye tovuti."))
                        .font(.bSmall).foregroundColor(C.warn).fixedSize(horizontal: false, vertical: true).padding(.top, 8)
                }
                Btn(n == 1 ? t("Add 1 photo to \(StoryKit.row(row))", "Ongeza picha 1 kwenye \(StoryKit.row(row))") : t("Add \(n) photos to \(StoryKit.row(row))", "Ongeza picha \(n) kwenye \(StoryKit.row(row))"),
                    icon: "check", enabled: n > 0 && (ask == "no" || (ask == "yes" && !kids.isEmpty)), wide: true, action: add).padding(.top, 16)
            }.padding(20)
        }
    }

    private func tile(_ i: Int, _ f: String) -> some View {
        let kid = kids.contains(f)
        return SquareTile {
            ZStack {
                LocalPic(path: f)
                if kid { KidsBadge().padding(5).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading) }
                Button { remove(f) } label: {
                    Icon("x", size: 14, color: .white).frame(width: 28, height: 28).background(StoryNight.opacity(0.7)).clipShape(Circle())
                        .frame(width: 44, height: 44).contentShape(Rectangle())
                }
                .buttonStyle(.plain).accessibilityLabel(t("Remove", "Ondoa"))
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            }
        }
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(kid ? Amber : .clear, lineWidth: 3))
        .contentShape(Rectangle())
        .onTapGesture { if ask == "yes" { Haptic.tap(); toggle(f) } }
        .accessibilityLabel(t("Photo \(i + 1)", "Picha \(i + 1)"))
        .accessibilityAddTraits(kid ? .isSelected : [])
    }
}

/// Over the bottom bar while a project's photos send: "Adding 3 photos to After · 1 of 3 sent", then "3 photos added ✓".
struct StoryUploadBanner: View {
    @ObservedObject var up: StoryUploads
    let pid: String

    var body: some View {
        let mine = up.items.filter { $0.project == pid && !$0.seen }
        let active = mine.filter { $0.active }.count
        let sent = mine.filter { $0.state == "done" }.count
        let failed = mine.filter { $0.state == "failed" }
        let waiting = mine.filter { $0.state == "waiting" }.count
        let rows = Array(Set(mine.map { $0.row }))
        let whereTo = rows.count == 1 ? StoryKit.row(rows[0]) : ""
        if !mine.isEmpty {
            Group {
                if active > 0 { sending(mine, sent, waiting, whereTo) } else { finished(sent, failed) }
            }
            .padding(.horizontal, 12).padding(.top, 6)
            .task(id: "\(active)-\(failed.count)-\(sent)") {
                // all sent: says so, then goes by itself
                if active == 0 && failed.isEmpty && sent > 0 {
                    try? await Task.sleep(nanoseconds: 4_000_000_000)
                    up.hide(pid)
                }
            }
        }
    }

    private func sending(_ mine: [StoryUp], _ sent: Int, _ waiting: Int, _ whereTo: String) -> some View {
        let total = mine.count
        let now = mine.first { $0.state == "sending" }
        let overall = (Double(sent) + (now?.progress ?? 0)) / Double(max(1, total))
        let title = !whereTo.isEmpty ? (total == 1 ? t("Adding 1 photo to \(whereTo)", "Inaongeza picha 1 kwenye \(whereTo)") : t("Adding \(total) photos to \(whereTo)", "Inaongeza picha \(total) kwenye \(whereTo)"))
            : t("Adding \(total) photos", "Inaongeza picha \(total)")
        let sub = t("\(sent) of \(total) sent", "\(sent) kati ya \(total) zimetumwa") + (now.map { " · \(Int($0.progress * 100))%" } ?? "")
        return VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                Icon("upload", size: 20, color: StoryMint).frame(width: 40, height: 40).background(Color.white.opacity(0.15)).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.tSmall).foregroundColor(.white).fixedSize(horizontal: false, vertical: true)
                    Text(sub).font(.bSmall).foregroundColor(Color.white.opacity(0.8))
                }.frame(maxWidth: .infinity, alignment: .leading)
                Button { up.hide(pid) } label: {
                    Text(t("Hide", "Ficha")).font(.lLarge).foregroundColor(StoryMint).padding(.horizontal, 10).frame(minHeight: 44)
                }.buttonStyle(.plain)
            }
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.2))
                    Capsule().fill(StorySend).frame(width: g.size.width * min(1, max(0, overall)))
                        .animation(Motion.on ? .easeOut(duration: 0.25) : nil, value: overall)
                }
            }.frame(height: 6).padding(.top, 10)
            if waiting > 0 {
                HStack(alignment: .top, spacing: 6) {
                    Icon("offline", size: 14, color: Color(hex: 0xF2B35B))
                    Text(waiting == 1 ? t("1 is waiting for signal — it sends by itself.", "1 inasubiri mtandao — itajituma yenyewe.") : t("\(waiting) are waiting for signal — they send by themselves.", "\(waiting) zinasubiri mtandao — zitajituma zenyewe."))
                        .font(.bSmall).foregroundColor(Color(hex: 0xF2B35B)).fixedSize(horizontal: false, vertical: true)
                }.padding(.top, 8)
            }
        }
        .padding(14)
        .background(LinearGradient(colors: [StoryNight2, StoryNight], startPoint: .topLeading, endPoint: .bottomTrailing))
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .shadow(color: .black.opacity(0.18), radius: 12, y: 4)
        .accessibilityElement(children: .combine)
    }

    private func finished(_ sent: Int, _ failed: [StoryUp]) -> some View {
        HStack(spacing: 12) {
            Icon(sent > 0 ? "check" : "alert", size: 20, color: .white).frame(width: 40, height: 40).background(sent > 0 ? GreenC : C.bad).clipShape(Circle())
            VStack(alignment: .leading, spacing: 2) {
                if sent > 0 { Text(sent == 1 ? t("1 photo added ✓", "Picha 1 imeongezwa ✓") : t("\(sent) photos added ✓", "Picha \(sent) zimeongezwa ✓")).font(.tSmall).foregroundColor(C.ink) }
                if let f = failed.first {
                    Text((failed.count == 1 ? t("1 couldn’t send", "1 haikutumwa") : t("\(failed.count) couldn’t send", "\(failed.count) hazikutumwa")) + ": " + upWhy(f.reason) + " " + t("Tap Retry.", "Gusa Jaribu tena."))
                        .font(.bSmall).foregroundColor(C.bad).fixedSize(horizontal: false, vertical: true)
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
            if !failed.isEmpty {
                Button { Haptic.tap(); up.retry() } label: {
                    HStack(spacing: 6) { Icon("refresh", size: 16, color: C.bad); Text(t("Retry", "Jaribu tena")).font(.lLarge).foregroundColor(C.bad).lineLimit(1).fixedSize() }
                        .padding(.horizontal, 12).frame(minHeight: 44).background(C.badBg).clipShape(Capsule())
                }.buttonStyle(.plain)
            } else {
                Button { up.hide(pid) } label: { Icon("x", size: 18, color: C.muted).frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel(t("Close", "Funga"))
            }
        }
        .padding(14).background(C.card)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.line, lineWidth: 1))
        .shadow(color: .black.opacity(0.12), radius: 12, y: 4)
    }
}

/// On the Stories tab: "Sending 2 photos…" while any story photo is on its way.
struct StorySendingPill: View {
    @ObservedObject var up: StoryUploads
    var body: some View {
        let n = up.sending()
        let wait = up.items.filter { $0.active }.allSatisfy { $0.state == "waiting" }
        if n > 0 {
            HStack(spacing: 6) {
                Icon(wait ? "offline" : "upload", size: 14, color: StoryMint)
                Text(wait ? (n == 1 ? t("1 photo waiting for signal", "Picha 1 inasubiri mtandao") : t("\(n) photos waiting for signal", "Picha \(n) zinasubiri mtandao"))
                     : n == 1 ? t("Sending 1 photo…", "Inatuma picha 1…") : t("Sending \(n) photos…", "Inatuma picha \(n)…"))
                    .font(.lMedium).foregroundColor(.white).fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 12).padding(.vertical, 6).background(StoryNight2).clipShape(Capsule())
            .accessibilityElement(children: .combine)
        }
    }
}

// --- one photo ------------------------------------------------------------------------------------------------------

/// One photo: a big look, its caption, which row, "shows children", the cover, and Delete.
struct StoryPhotoSheet: View {
    @EnvironmentObject var m: AppModel
    let pid: String; let row: String; let ph: StoryPhoto; let inStory: Bool
    let cover: Bool; let coverBusy: Bool; let coverErr: String?
    let saveCap: (String) -> Void; let move: (String) -> Void; let kids: (Bool) -> Void; let delete: () -> Void; let setCover: () -> Void; let close: () -> Void
    @State private var cap = ""
    @State private var started = false
    @State private var ask = false
    @State private var big = false
    @State private var gone = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SheetHead(title: t("Photo", "Picha"), sub: added, close: close)
                Button { big = true } label: {
                    ZStack(alignment: .topLeading) {
                        AuthPic(path: StoryKit.photoPath(pid, ph.id, w: 960))
                        if inStory { InStoryBadge().padding(10) }
                    }
                    .frame(maxWidth: .infinity).frame(height: 220).background(C.tint)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                }.buttonStyle(.plain).accessibilityLabel(t("Open the photo", "Fungua picha")).padding(.top, 12)
                captionField
                Text(t("Move to", "Hamishia")).font(.lLarge).foregroundColor(C.ink).padding(.top, 14).padding(.bottom, 6)
                RowSeg(sel: row) { r in if r != row { saveNow(); move(r) } }
                kidsRow.padding(.top, 14)
                StoryCoverBtn(on: cover, busy: coverBusy, action: setCover).padding(.top, 12)
                Msg(coverErr, "bad")
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { delBtn; doneBtn }
                    VStack(spacing: 8) { doneBtn; delBtn }
                }.padding(.top, 16)
            }.padding(20)
        }
        .onAppear { if !started { cap = ph.cap; started = true } }
        .onDisappear { saveNow() }
        .alert(t("Delete this photo?", "Futa picha hii?"), isPresented: $ask) {
            Button(t("Cancel", "Ghairi"), role: .cancel) {}
            Button(t("Delete", "Futa"), role: .destructive) { gone = true; delete() }
        } message: { Text(t("It is taken out of the story’s photos.", "Itaondolewa kwenye picha za hadithi.")) }
        .fullScreenCover(isPresented: $big) {
            PhotoViewer(path: StoryKit.photoPath(pid, ph.id, w: 1600), title: ph.cap.isEmpty ? StoryKit.row(row) : ph.cap) { big = false }.environmentObject(m)
        }
    }

    private var captionField: some View {
        VStack(alignment: .leading, spacing: 0) {
                Text(t("Caption", "Maelezo ya picha")).font(.lLarge).foregroundColor(C.ink).padding(.top, 14).padding(.bottom, 6)
                TextField("", text: Binding(get: { cap }, set: { cap = String($0.replacingOccurrences(of: "\n", with: " ").prefix(120)) }),
                          prompt: Text(t("e.g. Drilling reached water", "mf. Uchimbaji ulifika majini")).foregroundColor(C.muted), axis: .vertical)
                    .lineLimit(1...3).font(.bLarge).foregroundColor(C.text)
                    .padding(.horizontal, 14).padding(.vertical, 14).frame(minHeight: 52)
                    .background(C.card).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.edge, lineWidth: 1))
                Text(t("The story uses captions to describe photos.", "Hadithi inatumia maelezo kueleza picha.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 4)
        }
    }

    private var added: String {
        var parts: [String] = []
        if let d = photoDay(ph.at) { parts.append(t("Added \(d)", "Iliongezwa \(d)")) }
        if !ph.by.isEmpty { parts.append(t("by \(ph.by)", "na \(ph.by)")) }
        return parts.joined(separator: " ")
    }
    private func saveNow() {
        let c = cap.trimmingCharacters(in: .whitespacesAndNewlines)
        if started && !gone && c != ph.cap { saveCap(c) }
    }
    private var kidsRow: some View {
        HStack(spacing: 10) {
            Icon("person", size: 22, color: Color(hex: 0xB86E00))
            VStack(alignment: .leading, spacing: 2) {
                Text(t("Shows children", "Inaonyesha watoto")).font(.tSmall).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                Text(t("Only used on the website once photo consent is received.", "Inatumika kwenye tovuti tu baada ya idhini ya picha kupokelewa.")).font(.bSmall).foregroundColor(C.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }.frame(maxWidth: .infinity, alignment: .leading)
            Toggle("", isOn: Binding(get: { ph.children }, set: { kids($0) })).labelsHidden().tint(C.blue2)
        }
        .padding(12).background(C.bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
    private var delBtn: some View { Btn(t("Delete", "Futa"), icon: "trash", light: true, small: true, wide: true) { ask = true } }
    private var doneBtn: some View { Btn(t("Done", "Imekamilika"), icon: "check", small: true, wide: true, action: close) }
}

// --- the month of each column -------------------------------------------------------------------------------------------

/// A month and year: the chosen one (or "Not set"), and when open a year stepper, the twelve months and a Clear.
struct MonthField: View {
    let value: String; let open: Bool; let setOpen: (Bool) -> Void; let none: String; let pick: (String) -> Void
    @State private var year = Calendar.current.component(.year, from: Date())

    var body: some View {
        let nowY = Calendar.current.component(.year, from: Date())
        let nowM = Calendar.current.component(.month, from: Date())
        VStack(spacing: 0) {
            Button { Haptic.tap(); setOpen(!open) } label: {
                HStack(spacing: 10) {
                    Icon("cal", size: 20, color: C.blue2)
                    Text(monthText(value) ?? t("Not set", "Haijawekwa")).font(.bLarge).foregroundColor(value.isEmpty ? C.muted : C.ink)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Icon(open ? "up" : "down", size: 18, color: C.muted)
                }.padding(.horizontal, 14).frame(minHeight: 52).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if open {
                VStack(spacing: 6) {
                    HStack {
                        Button { if year > 2000 { year -= 1 } } label: { Icon("back", size: 18, color: year > 2000 ? C.ink : C.off).frame(width: 44, height: 44) }
                            .buttonStyle(.plain).accessibilityLabel(t("Year before", "Mwaka uliopita"))
                        Text(verbatim: "\(year)").font(.tMedium).foregroundColor(C.ink).frame(maxWidth: .infinity)
                        Button { if year < nowY { year += 1 } } label: { Icon("next", size: 18, color: year < nowY ? C.ink : C.off).frame(width: 44, height: 44) }
                            .buttonStyle(.plain).accessibilityLabel(t("Next year", "Mwaka ujao"))
                    }
                    ForEach(0..<3, id: \.self) { line in
                        HStack(spacing: 6) {
                            ForEach(0..<4, id: \.self) { k in month(line * 4 + k, nowY, nowM) }
                        }
                    }
                    Btn(none, light: true, small: true, wide: true) { pick("") }.padding(.top, 2)
                }.padding(.horizontal, 10).padding(.bottom, 10)
            }
        }
        .background(C.card).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.edge, lineWidth: 1))
        .padding(.top, 6)
        .onAppear { if let p = ymParts(value) { year = p.0 } }
        .onChange(of: value) { v in if let p = ymParts(v) { year = p.0 } }
    }

    private func month(_ i: Int, _ nowY: Int, _ nowM: Int) -> some View {
        let ym = String(format: "%04d-%02d", year, i + 1)
        let future = year > nowY || (year == nowY && i + 1 > nowM)
        let on = String(value.prefix(7)) == ym
        return Button { Haptic.tap(); pick(ym) } label: {
            Text(shortMonths()[i]).font(.lLarge).foregroundColor(on ? .white : future ? C.off : C.ink).lineLimit(1).minimumScaleFactor(0.7)
                .frame(maxWidth: .infinity, minHeight: 44).background(on ? C.blue2 : C.bg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain).disabled(future)
        .accessibilityLabel(longMonths()[i] + " \(year)").accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// The month of one column (In progress: From and an optional To): a month-and-year picker, Save, and Clear.
struct StoryDatesSheet: View {
    let row: String; let d: StoryDates; let hints: [String: (String, String)]; let busy: Bool; let bad: String?
    let close: () -> Void; let save: ([String: String]) -> Void
    @State private var vals: [String: String] = [:]
    @State private var open: [String: Bool] = [:]
    @State private var started = false

    private var keys: [String] { row == "progress" ? ["progress", "progressEnd"] : [row] }

    var body: some View {
        let had = keys.contains { !d.value($0).isEmpty }
        let changed = keys.contains { (vals[$0] ?? "") != d.value($0) }
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SheetHead(title: t("When was it?", "Ilikuwa lini?"), sub: sub, close: close)
                ForEach(keys, id: \.self) { k in field(k) }
                Text(t("One month for this whole part of the story, not for each photo. The story and the website’s timeline use it.", "Mwezi mmoja kwa sehemu hii yote ya hadithi, si kwa kila picha. Hadithi na mpangilio wa wakati wa tovuti zinautumia."))
                    .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 14)
                Msg(bad, "bad")
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { if had { clearBtn }; saveBtn(changed, had) }
                    VStack(spacing: 8) { saveBtn(changed, had); if had { clearBtn } }
                }.padding(.top, 16)
            }.padding(20)
        }
        .onAppear {
            guard !started else { return }
            started = true
            var v: [String: String] = [:], o: [String: Bool] = [:]
            for k in keys {
                let now = d.value(k)
                v[k] = now.isEmpty ? (hints[k]?.0 ?? "") : now
                o[k] = now.isEmpty && hints[k] == nil && k != "progressEnd"
            }
            vals = v; open = o
        }
    }

    private var sub: String {
        switch row {
        case "before": return t("Before: how it was before the work began", "Kabla: ilivyokuwa kabla kazi haijaanza")
        case "progress": return t("In progress: when the work was done", "Wakati wa kazi: kazi ilipofanyika")
        default: return t("After: the finished project", "Baada: mradi uliokamilika")
        }
    }

    private func field(_ k: String) -> some View {
        let label = k == "progress" ? t("From", "Kuanzia") : k == "progressEnd" ? t("To (optional)", "Hadi (si lazima)") : t("Month", "Mwezi")
        let v = vals[k] ?? ""
        let h = hints[k]
        return VStack(alignment: .leading, spacing: 0) {
            Text(label).font(.lLarge).foregroundColor(C.ink).padding(.top, 16)
            MonthField(value: v, open: open[k] == true, setOpen: { open[k] = $0 }, none: t("Clear", "Ondoa")) { x in vals[k] = x; open[k] = false }
            if let h, d.value(k).isEmpty, v == h.0 {
                Text(h.1).font(.bSmall).foregroundColor(C.ok).fixedSize(horizontal: false, vertical: true).padding(.top, 4)
            }
        }
    }

    private var clearBtn: some View {
        Btn(t("Clear", "Ondoa"), icon: "x", enabled: !busy, light: true, small: true, wide: true) {
            var out: [String: String] = [:]; keys.forEach { out[$0] = "" }; save(out)
        }
    }
    private func saveBtn(_ changed: Bool, _ had: Bool) -> some View {
        Btn(t("Save", "Hifadhi"), icon: "check", enabled: !busy && changed, small: had, wide: true, busy: busy ? t("Saving…", "Inahifadhi…") : nil) {
            var out: [String: String] = [:]; keys.forEach { out[$0] = vals[$0] ?? "" }; save(out)
        }
    }
}
