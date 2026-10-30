import MapKit
import SwiftUI
import UIKit

/// One message: a system line in the middle, or a bubble (left: theirs, right: mine). Swipe right to reply; double-tap to
/// react; hold for reactions and actions. `act` tells the room: "reply", "hold", "toggle", "double", "reacts", "info",
/// "retry", "media:<id>", "jump:<id>".
struct ChatBubbleLine: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    let msg: ChatMsg; let row: ChatRow?; let me: String; let group: Bool; let first: Bool; let last: Bool; let maxW: CGFloat; let flash: Bool
    let seen: [String]; let selecting: Bool; let selected: Bool; let act: (String) -> Void
    @State private var dx: CGFloat = 0
    @State private var buzzed = false

    var body: some View {
        if msg.sys != nil || msg.kind == "sys" { ChatSysLine(msg: msg) }
        else { line }
    }
    private var mine: Bool { chatSame(msg.from, me) }
    private var line: some View {
        let mine = self.mine
        let big = msg.kind == "text" && !msg.deleted && msg.reply == nil && !msg.fwd ? chatEmojiOnly(msg.text) : 0
        return ZStack(alignment: mine ? .trailing : .leading) {
            if dx > 4 {
                let k = min(1.2, dx / 64)
                CIcon("reply", size: 18, color: k >= 1 ? .white : C.blue).frame(width: 32, height: 32).background(k >= 1 ? C.blue : C.card).clipShape(Circle())
                    .scaleEffect(0.4 + 0.6 * min(1, k)).opacity(Double(min(1, k))).frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 6)
            }
            if dx < -4 {
                Text(ChatKit.dateTime(msg.at)).font(.mont(11, .semibold)).foregroundColor(C.muted).opacity(Double(min(1, -dx / 64))).frame(maxWidth: .infinity, alignment: .trailing).padding(.trailing, 6)
            }
            VStack(alignment: mine ? .trailing : .leading, spacing: 2) {
                if big > 0 { emoji(big) } else { bubble }
                if !msg.reacts.isEmpty { reacts }
                if !seen.isEmpty { ChatSeenBy(names: seen) }
            }
            .frame(maxWidth: .infinity, alignment: mine ? .trailing : .leading)
            .offset(x: dx)
        }
        .padding(.top, first ? 6 : 2)
        .background(C.blue.opacity(flash ? 0.18 : 0).animation(.easeInOut(duration: 0.3), value: flash))
        .contentShape(Rectangle())
        .simultaneousGesture(DragGesture(minimumDistance: 24).onChanged { v in
            guard !selecting, abs(v.translation.width) > abs(v.translation.height) * 1.5 else { return }
            dx = max(-84, min(90, v.translation.width))
            if dx > 64 && !buzzed { buzzed = true; Haptic.tap() }
        }.onEnded { _ in
            if dx > 64 && !msg.deleted && !msg.queued { act("reply") }
            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { dx = 0 }; buzzed = false
        })
    }

    private func emoji(_ n: Int) -> some View {
        VStack(alignment: mine ? .trailing : .leading, spacing: 2) {
            if group && !mine && first { Text(msg.from).font(.mont(12, .heavy)).foregroundColor(ChatHues.person(msg.from).fg).lineLimit(1) }
            Text(msg.text.trimmingCharacters(in: .whitespaces)).font(.system(size: n == 1 ? 48 : n == 2 ? 40 : 34))
            HStack(spacing: 3) {
                Text(ChatKit.time(msg.at)).font(.mont(10, .medium)).foregroundColor(C.muted)
                if mine { ChatTicks(status: msg.status) }
            }.padding(.horizontal, 6).padding(.vertical, 1).background(C.dark ? Color.black.opacity(0.4) : Color.white.opacity(0.8)).clipShape(Capsule())
        }
        .padding(.horizontal, 6)
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { act("double") }
        .onLongPressGesture(minimumDuration: 0.35) { act("hold") }
        .accessibilityElement(children: .combine)
    }

    /// Night blue (my default) has white words; a chosen colour or theirs has the app's words.
    private var nightBubble: Bool { mine && (row?.bubble ?? "").isEmpty && ChatLook.deep }
    private var fg: Color { nightBubble ? .white : C.text }
    private var sub: Color { nightBubble ? Color(hex: 0xA9C3D6) : ChatLook.time }

    private var bubble: some View {
        let bg: Color = mine ? (ChatLook.bubble(row?.bubble ?? "") ?? ChatLook.mine) : ChatLook.theirs
        let shape = ChatBubbleShape(mine: mine, first: first, last: last)
        return ChatBubbleStack(spacing: 3) {
            if group && !mine && first { Text(msg.from).font(.mont(12, .heavy)).foregroundColor(ChatHues.person(msg.from).fg).lineLimit(1) }
            if msg.fwd && !msg.deleted {
                HStack(spacing: 4) { CIcon("fwd", size: 13, color: sub); Text(t("Forwarded", "Imesambazwa")).font(.mont(11, .medium)).italic().foregroundColor(sub) }
            }
            if msg.deleted {
                Text("🚫 " + (mine ? t("You deleted this message", "Ulifuta ujumbe huu") : t("This message was deleted", "Ujumbe huu ulifutwa"))).font(.bMedium).italic().foregroundColor(sub)
            } else {
                if let q = msg.reply { ChatQuoteBox(q: q, me: me, onNight: nightBubble).onTapGesture { act("jump:" + q.id) } }
                ChatMsgBody(hub: hub, msg: msg, mine: mine, row: row, maxW: maxW - 20, fg: fg, sub: sub, act: act)
                ChatTranslateLine(hub: hub, msg: msg, mine: mine, fg: fg)
            }
            HStack(spacing: 3) {
                Spacer(minLength: 0)
                if row?.pins.contains(where: { $0.id == msg.id }) == true { Icon("pin", size: 11, color: sub).accessibilityLabel(t("Pinned", "Imebandikwa")) }
                if msg.starred { Icon("starfill", size: 11, color: sub).accessibilityLabel(t("Starred", "Ina nyota")) }
                if msg.edited > 0 && !msg.deleted { Text(t("edited", "imehaririwa")).font(.mont(11, .medium)).italic().foregroundColor(sub) }
                Text(ChatKit.time(msg.at)).font(.mont(11, .medium)).foregroundColor(sub)
                if mine && !msg.deleted { ChatTicks(status: msg.status, tint: nightBubble ? Color(hex: 0xA9C3D6) : ChatLook.tick) }
            }
            .contentShape(Rectangle())
            .onTapGesture { if mine && !msg.deleted && !msg.queued && !selecting { act("info") } }
            if let f = msg.failed { Text(f).font(.mont(11, .medium)).foregroundColor(C.bad).fixedSize(horizontal: false, vertical: true) }
            if msg.status == "failed" { Text(t("Tap to try again", "Gusa kujaribu tena")).font(.mont(11, .bold)).foregroundColor(C.bad) }
        }
        .padding(.horizontal, 10).padding(.top, 6).padding(.bottom, 5)
        .background(bg)
        .clipShape(shape)
        .overlay(shape.stroke(selected ? ChatLook.un : (ChatLook.sun ? Color.black : Color.clear), lineWidth: selected ? 3 : 1.5))
        .shadow(color: .black.opacity(C.dark ? 0 : 0.06), radius: 0.5, y: 0.5)
        .padding(mine ? .trailing : .leading, 4)
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { if !selecting { act("double") } }
        .onTapGesture { if selecting { act("toggle") } else if msg.status == "failed" { act("retry") } }
        .onLongPressGesture(minimumDuration: 0.35) { act("hold") }
        .accessibilityElement(children: .contain)
        .accessibilityHint(says)
        .accessibilityAction(named: t("Actions", "Vitendo")) { act("hold") }
        .accessibilityAction(named: t("Reply", "Jibu")) { act("reply") }
        .accessibilityAddTraits(selected ? .isSelected : [])
        .frame(maxWidth: maxW, alignment: mine ? .trailing : .leading)
    }
    private var says: String {
        let who = mine ? t("You", "Wewe") : msg.from
        let what = msg.deleted ? t("This message was deleted", "Ujumbe huu ulifutwa") : msg.preview()
        return [who, ChatKit.time(msg.at), what, mine && !msg.deleted ? ChatKit.statusWord(msg.status) : ""].filter { !$0.isEmpty }.joined(separator: ", ")
    }
    private var reacts: some View {
        let sortedR = msg.reacts.sorted { $0.value.count > $1.value.count }
        let total = msg.reacts.values.reduce(0) { $0 + $1.count }
        return Button { act("reacts") } label: {
            HStack(spacing: 1) {
                ForEach(Array(sortedR.prefix(4)), id: \.key) { e in Text(e.key).font(.system(size: 13)) }
                if total > 1 { Text(" \(total)").font(.mont(11, .bold)).foregroundColor(C.muted) }
            }.padding(.horizontal, 7).padding(.vertical, 2).background(C.card).clipShape(Capsule()).overlay(Capsule().stroke(C.line, lineWidth: 1))
        }.buttonStyle(.plain).padding(.horizontal, 10).offset(y: -4)
        .accessibilityLabel(t("Reactions", "Miitikio"))
    }
}

/// Rounded bubbles: a squarer corner where a run starts, smaller corners where they join.
struct ChatBubbleShape: Shape {
    let mine: Bool; let first: Bool; let last: Bool
    func path(in r: CGRect) -> Path {
        let big: CGFloat = 18, small: CGFloat = 6
        let tl = mine ? big : (first ? 4 : small), tr = mine ? (first ? 4 : small) : big
        let bl = mine ? big : (last ? big : small), br = mine ? (last ? big : small) : big
        var p = Path()
        p.move(to: CGPoint(x: r.minX + tl, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX - tr, y: r.minY)); p.addQuadCurve(to: CGPoint(x: r.maxX, y: r.minY + tr), control: CGPoint(x: r.maxX, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY - br)); p.addQuadCurve(to: CGPoint(x: r.maxX - br, y: r.maxY), control: CGPoint(x: r.maxX, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX + bl, y: r.maxY)); p.addQuadCurve(to: CGPoint(x: r.minX, y: r.maxY - bl), control: CGPoint(x: r.minX, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX, y: r.minY + tl)); p.addQuadCurve(to: CGPoint(x: r.minX + tl, y: r.minY), control: CGPoint(x: r.minX, y: r.minY))
        p.closeSubpath()
        return p
    }
}

/// A line the app wrote: an icon per kind and a short sentence with the time ("Meril fixed the pump · 10:42").
struct ChatSysLine: View {
    @EnvironmentObject var m: AppModel
    let msg: ChatMsg
    var body: some View {
        let s = msg.sys
        let (icon, hue): (String, String) = {
            switch s?.type ?? "" {
            case "repair": return ("wrench", "amber"); case "notworking": return ("alert", "coral"); case "fixed": return ("okc", "green"); case "assigned": return ("cal", "blue")
            case "sms": return ("phone", "teal"); case "step": return ("clip", "slate"); case "pinned": return ("pin", "blue"); case "created", "added", "joined": return ("people", "green")
            case "removed", "left": return ("people", "slate"); default: return ("info", "slate")
            }
        }()
        let own: Set<String> = ["fixed", "repair", "pinned", "notworking", "sms", "assigned"]
        let text = Lang.sw || msg.text.isEmpty || own.contains(s?.type ?? "") ? chatSysText(s) : msg.text
        let hu = ChatHues.named(hue)
        Button { if let p = s?.project, !p.isEmpty { m.go(Place(tab: m.here.tab, project: p, projectTab: "pin")) } } label: {
            HStack(spacing: 8) {
                CIcon(icon, size: 13, color: hu.fg).frame(width: 22, height: 22).background(hu.bg).clipShape(Circle())
                Text(text + " · " + ChatKit.time(msg.at)).font(.mont(12, .semibold)).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
            }.padding(.horizontal, 10).padding(.vertical, 6).background(C.dark ? Color(hex: 0x1E2A36) : Color.white.opacity(0.96))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(hu.bg, lineWidth: 1))
            .frame(maxWidth: 330)
        }.buttonStyle(.plain).frame(maxWidth: .infinity).padding(.vertical, 6)
    }
}

/// A quoted message (in a reply, and above the composer).
struct ChatQuoteBox: View {
    let q: ChatQuote; let me: String; var onNight = false
    var body: some View {
        let col = ChatHues.person(q.from).fg
        HStack(spacing: 8) {
            Rectangle().fill(onNight ? ChatLook.mint : col).frame(width: 3)
            VStack(alignment: .leading, spacing: 1) {
                Text(chatSame(q.from, me) ? t("You", "Wewe") : q.from).font(.mont(12, .heavy)).foregroundColor(onNight ? ChatLook.mint : col).lineLimit(1)
                Text(chatPreview(q.kind, q.text)).font(.bSmall).foregroundColor(onNight ? Color.white.opacity(0.85) : C.muted).lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 5).padding(.trailing, 8)
        .background(onNight ? Color.white.opacity(0.1) : C.tint.opacity(0.7))
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

/// What a message holds: words (links and @names marked), photos, a voice note, a place, a contact, a document, a trip.
struct ChatMsgBody: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    let msg: ChatMsg; let mine: Bool; let row: ChatRow?; let maxW: CGFloat; let fg: Color; let sub: Color; let act: (String) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            switch msg.kind {
            case "voice":
                if let md = msg.media.first { ChatVoiceBubble(msg: msg, md: md, fg: fg) }
            case "media":
                ChatPhotoGrid(media: msg.media, width: min(maxW, 280)) { i in act("media:" + (msg.media[i].id.isEmpty ? msg.media[i].local : msg.media[i].id)) }
                if !msg.text.isEmpty { words }
            case "location", "live": ChatLocationCard(hub: hub, msg: msg, mine: mine)
            case "contact": ChatContactCard(name: msg.contact ?? msg.text)
            case "doc": if let md = msg.media.first { ChatDocCard(hub: hub, md: md, fg: fg) }
            case "trip": if let card = msg.trip { ChatTripCardView(card: card) }
            default: words
            }
            if msg.kind == "text", let pid = ChatKit.projectIn(msg.text) { ChatProjectCard(pid: pid) }
        }
    }
    private var words: some View {
        Text(attributed).font(.bLarge).foregroundColor(fg).fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
            .environment(\.openURL, OpenURLAction { url in
                if let pid = ChatKit.projectIn(url.absoluteString) { m.go(Place(tab: m.here.tab, project: pid, projectTab: "pin")); return .handled }
                return .systemAction
            })
    }
    /// Links tappable, @names of the chat in bold.
    private var attributed: AttributedString {
        var a = AttributedString(msg.text)
        if let det = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) {
            for mt in det.matches(in: msg.text, range: NSRange(msg.text.startIndex..., in: msg.text)) {
                guard let r = Range(mt.range, in: msg.text), let ar = Range(r, in: a), let u = mt.url else { continue }
                a[ar].link = u
                let col: Color = mine && fg == .white ? ChatLook.ice : ChatLook.link
                a[ar].foregroundColor = col
                a[ar].underlineStyle = Text.LineStyle.single
            }
        }
        for n in (row?.members ?? []) + msg.mentions {
            let tag = "@" + n
            var from = msg.text.startIndex
            while let r = msg.text.range(of: tag, options: .caseInsensitive, range: from..<msg.text.endIndex) {
                if let ar = Range(r, in: a) { let f: Font = .mont(16, .heavy); a[ar].font = f }
                from = r.upperBound
            }
        }
        return a
    }
}

/// A project linked in a message: its photo, name, place and how it's doing.
struct ChatProjectCard: View {
    @EnvironmentObject var m: AppModel
    let pid: String
    var body: some View {
        let p = m.snap?.projects.first { $0.id == pid }
        let rep = m.snap?.repairs[pid]
        Button { m.go(Place(tab: m.here.tab, project: pid, projectTab: "pin")) } label: {
            VStack(alignment: .leading, spacing: 0) {
                if let ph = p?.photo { NetImage(url: m.api.photoUrl(ph, w: 480)) { ChatHues.named("blue").bg }.frame(height: 120).frame(maxWidth: .infinity).clipped() }
                VStack(alignment: .leading, spacing: 3) {
                    Text(p?.title ?? t("Project \(pid)", "Mradi \(pid)")).font(.tSmall).foregroundColor(C.ink).lineLimit(2)
                    let place = p.map { [$0.area, $0.country].filter { !$0.isEmpty }.joined(separator: ", ") } ?? ""
                    Text(place.isEmpty ? t("Open the project", "Fungua mradi") : place).font(.bSmall).foregroundColor(C.muted).lineLimit(1)
                    if p != nil {
                        let st: (String, String) = rep?.state == "notworking" ? (t("Not working", "Haifanyi kazi"), "coral") : rep != nil ? (t("Needs repair", "Inahitaji matengenezo"), "amber") : (t("Working", "Inafanya kazi"), "green")
                        Tag(st.0, bg: ChatHues.named(st.1).bg, fg: ChatHues.named(st.1).fg).padding(.top, 3)
                    }
                }.padding(10)
            }.frame(maxWidth: 260).background(C.card).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(C.line, lineWidth: 1))
        }.buttonStyle(.plain)
    }
}

/// A place, or a live location (with how far it is and "Stop sharing" on mine).
struct ChatLocationCard: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    @ObservedObject var loc = Locator.shared
    let msg: ChatMsg; let mine: Bool
    var body: some View {
        let live = msg.kind == "live"
        let active = live && (msg.live?.active() ?? false)
        let lat = msg.live?.lat ?? msg.loc?.lat, lng = msg.live?.lng ?? msg.loc?.lng
        VStack(alignment: .leading, spacing: 6) {
            Button { if let lat, let lng { Share.open("http://maps.apple.com/?ll=\(lat),\(lng)&q=" + Api.enc(msg.from)) } } label: {
                ZStack {
                    if let lat, let lng {
                        ChatMapSnap(ll: LatLng(lat: lat, lng: lng))
                    } else { C.tint }
                    Icon("pin", size: 34, color: live ? ChatLook.online : C.bad).offset(y: -8)
                }.frame(width: 240, height: 130).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }.buttonStyle(.plain).accessibilityLabel(t("Open in Maps", "Fungua kwenye Ramani"))
            Text(live ? (active ? t("Live location", "Mahali moja kwa moja") : t("Live location ended", "Mahali moja kwa moja pameisha")) : t("Location", "Mahali")).font(.tSmall).foregroundColor(C.ink)
            if active, let l = msg.live {
                Text(t("Until ", "Hadi ") + ChatKit.time(l.until) + (l.at > 0 ? " · " + t("updated ", "imesasishwa ") + agoText(l.at) : "")).font(.bSmall).foregroundColor(C.muted)
            }
            if let here = loc.fix?.ll, let lat, let lng, !mine {
                Text(Geo.dist(Geo.metres(here, LatLng(lat: lat, lng: lng)), miles: m.miles) + t(" away", " kutoka hapa")).font(.bSmall).foregroundColor(C.muted)
            }
            if active && mine {
                Button { Task { if let e = await hub.stopLive(msg) { hub.note = e } } } label: {
                    Text(t("Stop sharing", "Acha kushiriki")).font(.lMedium).foregroundColor(C.bad).frame(minHeight: 36)
                }.buttonStyle(.plain)
            }
        }
    }
    private func agoText(_ at: Double) -> String { let mn = Int((chatNow() - at) / 60_000); return mn < 1 ? t("just now", "sasa hivi") : t("\(mn) min ago", "dakika \(mn) zilizopita") }
}

/// A small map picture of a place (MapKit's snapshot, kept in memory).
struct ChatMapSnap: View {
    let ll: LatLng
    @State private var img: UIImage?
    private static let mem = NSCache<NSString, UIImage>()
    var body: some View {
        Color.clear.overlay(Group { if let img { Image(uiImage: img).resizable().scaledToFill() } else { C.tint } }).clipped()
            .task(id: ll) {
                let key = "\(ll.lat),\(ll.lng),\(C.dark)" as NSString
                if let c = ChatMapSnap.mem.object(forKey: key) { img = c; return }
                let o = MKMapSnapshotter.Options()
                o.region = MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: ll.lat, longitude: ll.lng), latitudinalMeters: 1200, longitudinalMeters: 1200)
                o.size = CGSize(width: 240, height: 130)
                o.traitCollection = UITraitCollection(userInterfaceStyle: C.dark ? .dark : .light)
                if let s = try? await MKMapSnapshotter(options: o).start() { img = s.image; ChatMapSnap.mem.setObject(s.image, forKey: key) }
            }
    }
}

/// A teammate shared as a contact: Message them.
struct ChatContactCard: View {
    @EnvironmentObject var m: AppModel
    let name: String
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                ChatPersonAvatar(name: name, size: 40)
                VStack(alignment: .leading, spacing: 1) {
                    Text(name).font(.tSmall).foregroundColor(C.ink).lineLimit(2)
                    Text(t("RAFIKI team", "Timu ya RAFIKI")).font(.bSmall).foregroundColor(C.muted)
                }
            }
            HStack(spacing: 8) {
                Button { ChatNav.shared.dm(name) } label: {
                    Text(t("Message", "Tuma ujumbe")).font(.lMedium).foregroundColor(C.blue).frame(maxWidth: .infinity, minHeight: 38)
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(C.line, lineWidth: 1))
                }.buttonStyle(.plain)
                if let ph = m.team?.first(where: { chatSame($0.name, name) })?.phone, !ph.isEmpty {
                    Button { Share.open("tel:" + ph.filter { $0.isNumber || $0 == "+" }) } label: {
                        Text(t("Call", "Piga simu")).font(.lMedium).foregroundColor(C.blue).frame(maxWidth: .infinity, minHeight: 38)
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(C.line, lineWidth: 1))
                    }.buttonStyle(.plain)
                }
            }
        }.frame(minWidth: 200, maxWidth: 260)
    }
}

/// A document: its name and size; a tap downloads it and opens it.
struct ChatDocCard: View {
    @ObservedObject var hub: ChatHub
    let md: ChatMedia; let fg: Color
    @State private var busy = false
    var body: some View {
        Button {
            busy = true
            Task { let f = await hub.file(md); busy = false; if let f { Share.items([f]) } else { hub.note = t("Couldn’t download it. Check the signal.", "Imeshindwa kupakua. Angalia mtandao.") } }
        } label: {
            HStack(spacing: 10) {
                Group { if busy { ProgressView() } else { CIcon("doc", size: 22, color: ChatHues.named("coral").fg) } }
                    .frame(width: 40, height: 40).background(ChatHues.named("coral").bg).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 1) {
                    Text(md.name.isEmpty ? t("Document", "Hati") : md.name).font(.tSmall).foregroundColor(fg).lineLimit(2).multilineTextAlignment(.leading)
                    if md.size > 0 { Text(chatDataText(md.size)).font(.bSmall).foregroundColor(fg.opacity(0.7)) }
                }
            }.frame(minWidth: 200, alignment: .leading)
        }.buttonStyle(.plain)
    }
}

/// A trip shared into a chat: its title, dates, the next stop, progress, and "Open trip".
struct ChatTripCardView: View {
    @EnvironmentObject var m: AppModel
    let card: ChatTripCard
    var body: some View {
        let hu = ChatHues.named("teal")
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 10) {
                CIcon("route", size: 19, color: hu.fg).frame(width: 34, height: 34).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 1) {
                    Text(t("TRIP", "SAFARI")).font(.mont(10, .heavy)).tracking(0.8).foregroundColor(hu.fg)
                    Text(card.title.isEmpty ? t("A trip", "Safari") : card.title).font(.tSmall).foregroundColor(C.ink).lineLimit(2)
                }
            }
            if card.gone { Text(t("This trip was deleted.", "Safari hii ilifutwa.")).font(.bSmall).foregroundColor(C.muted) }
            else if card.limited { Text(t("Only people on this trip can open it", "Ni watu wa safari hii tu wanaoweza kuifungua")).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true) }
            else {
                let dates = card.dates.isEmpty ? TripDates.range(card.from, card.to) : card.dates
                let st = statusWord(card.status)
                Text([dates, st].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted)
                if !card.next.isEmpty { Text(t("Next: ", "Ijayo: ") + card.next).font(.mont(12, .semibold)).foregroundColor(C.ink).lineLimit(2) }
                if card.total > 0 {
                    Meter(f: card.progress).padding(.top, 4)
                    Text(t("\(card.done) of \(card.total) stops", "Vituo \(card.done) kati ya \(card.total)")).font(.mont(11, .medium)).foregroundColor(C.muted)
                }
                Button { m.go(Place(tab: m.here.tab, sub: "trip:" + card.id)) } label: {
                    Text(t("Open trip", "Fungua safari")).font(.lMedium).foregroundColor(C.blue).frame(maxWidth: .infinity, minHeight: 38)
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(C.line, lineWidth: 1))
                }.buttonStyle(.plain).padding(.top, 4)
            }
        }.padding(10).frame(width: 250, alignment: .leading).background(C.card).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
    private func statusWord(_ s: String) -> String {
        switch s { case "active": return t("on the road", "safarini"); case "done": return t("done", "imekamilika"); case "planned": return t("planned", "imepangwa"); case "cancelled": return t("cancelled", "imeghairiwa"); default: return "" }
    }
}

/// "Translate · Tafsiri" under someone else's words (when they look like the other language), and the translation.
struct ChatTranslateLine: View {
    @ObservedObject var hub: ChatHub
    let msg: ChatMsg; let mine: Bool; let fg: Color
    @State private var busy = false
    var body: some View {
        if let tx = hub.translated[msg.id] {
            VStack(alignment: .leading, spacing: 2) {
                Rectangle().fill(fg.opacity(0.2)).frame(height: 1)
                Text(tx).font(.bMedium).foregroundColor(fg).fixedSize(horizontal: false, vertical: true)
            }
        } else if !mine && !msg.text.isEmpty && (msg.kind == "text" || msg.kind == "media") && ChatKit.isSw(msg.text) != Lang.sw {
            Button { translate() } label: {
                HStack(spacing: 4) {
                    if busy { ProgressView().scaleEffect(0.6) } else { CIcon("translate", size: 13, color: ChatLook.link) }
                    Text("Translate · Tafsiri").font(.mont(12, .bold)).foregroundColor(ChatLook.link)
                }.frame(minHeight: 28)
            }.buttonStyle(.plain)
        }
    }
    private func translate() {
        if hub.tools["translate"] != true { Share.open(ChatKit.google(msg.text)); return }
        busy = true
        Task {
            let (tx, e, g) = await hub.translate(msg.text)
            busy = false
            if let tx { hub.translated[msg.id] = tx } else if let g { Share.open(g) } else { hub.note = e }
        }
    }
}

/// "Seen by": the faces of who has read the newest message in a group.
struct ChatSeenBy: View {
    let names: [String]
    var body: some View {
        HStack(spacing: -6) {
            ForEach(Array(names.prefix(5)), id: \.self) { n in ChatPersonAvatar(name: n, size: 18).overlay(Circle().stroke(C.card, lineWidth: 1.5)) }
            if names.count > 5 { Text("+\(names.count - 5)").font(.mont(10, .bold)).foregroundColor(C.muted).padding(.leading, 8) }
        }
        .padding(.horizontal, 8)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(t("Seen by ", "Imeonwa na ") + names.joined(separator: ", "))
    }
}

/// A bubble's insides: as wide as its widest part (at most the width offered), each part laid out at that width, so a
/// short message stays small and the time row reaches the right edge.
struct ChatBubbleStack: Layout {
    var spacing: CGFloat = 3
    private func width(_ proposal: ProposedViewSize, _ subviews: Subviews) -> CGFloat {
        let cap = proposal.width ?? .infinity
        let ideal = subviews.map { $0.sizeThatFits(.unspecified).width }.max() ?? 0
        return max(0, min(ideal, cap))
    }
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = width(proposal, subviews)
        let hs = subviews.map { $0.sizeThatFits(ProposedViewSize(width: w, height: nil)).height }
        let gaps = spacing * CGFloat(max(0, hs.filter { $0 > 0 }.count - 1))
        return CGSize(width: w, height: hs.reduce(0, +) + gaps)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for s in subviews {
            let h = s.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil)).height
            s.place(at: CGPoint(x: bounds.minX, y: y), proposal: ProposedViewSize(width: bounds.width, height: h))
            if h > 0 { y += h + spacing }
        }
    }
}
