import AVFoundation
import SwiftUI
import UIKit

/// The message box: quick replies above it when it's empty, the reply or edit bar, @names in groups, Attach (photos and
/// videos, camera, document, location, live location, contact), the camera, and Send — or the microphone when the box is
/// empty (tap to record a voice note, then listen, delete or send). The half-written message is kept per chat.
struct ChatComposer: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    @ObservedObject var player = ChatPlayer.shared
    let c: String; let row: ChatRow?
    @Binding var reply: ChatMsg?
    @Binding var editing: ChatMsg?

    @State private var text = ""
    @StateObject private var rec = ChatRec()
    @State private var note: ChatMedia?
    @State private var msg: String?
    @State private var sheet: String?
    @State private var picked: [ChatMedia]?
    @FocusState private var focus: Bool

    var body: some View {
        VStack(spacing: 0) {
            if let msg { Text(msg).font(.mont(13, .semibold)).foregroundColor(C.warn).padding(.horizontal, 14).padding(.vertical, 4).frame(maxWidth: .infinity, alignment: .leading).background(C.card) }
            mentionList
            if text.isEmpty && editing == nil && !rec.on && note == nil { quick }
            if editing != nil || reply != nil { replyBar }
            HStack(alignment: .bottom, spacing: 6) {
                if rec.on { recBar } else if let n = note { noteBar(n) } else { field }
                sendButton
            }
            .padding(.horizontal, 8).padding(.vertical, 8)
        }
        .background(ChatLook.deep ? ChatLook.room : C.card)
        .overlay(alignment: .top) { Rectangle().fill(C.line).frame(height: ChatLook.sun ? 2 : 1) }
        .onAppear {
            text = hub.drafts[c] ?? ""
            if m.demo { switch Demo.arg("-sheet") ?? "" { case "attach", "live", "contact": sheet = Demo.arg("-sheet"); case "rec": rec.on = true; rec.secs = 7; default: break } }
        }
        .onChange(of: text) { v in
            hub.drafts[c] = v
            if !v.isEmpty { hub.sendTyping(c, "typing") }
        }
        .onChange(of: editing?.id) { _ in if let e = editing { text = e.text; focus = true } }
        .onChange(of: reply?.id) { _ in if reply != nil { focus = true } }
        .onDisappear { hub.sendTyping(c, nil); if rec.on { rec.cancel() } }
        .sheet(isPresented: isSet($sheet)) { sheetView }
        .sheet(isPresented: isSet($picked)) { if let p = picked { ChatPreviewSheet(list: p) { list, caption in sendMedia(list, caption) }.environmentObject(m).storySheet([.large]) } }
    }

    // MARK: parts

    private var field: some View {
        HStack(alignment: .bottom, spacing: 2) {
            TextField("", text: $text, prompt: Text(t("Message", "Ujumbe")).foregroundColor(C.muted), axis: .vertical)
                .lineLimit(1...6).font(.bLarge).foregroundColor(C.text).focused($focus)
                .padding(.leading, 14).padding(.vertical, 11)
            Button { Haptic.tap(); sheet = "attach" } label: { CIcon("clip", size: 22, color: C.muted).frame(width: 40, height: 44) }.buttonStyle(.plain).accessibilityLabel(t("Attach", "Ambatisha"))
            if text.isEmpty { Button { Haptic.tap(); sheet = "camera" } label: { CIcon("camera", size: 22, color: C.muted).frame(width: 40, height: 44) }.buttonStyle(.plain).accessibilityLabel(t("Camera", "Kamera")) }
        }
        .background(C.card).clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(ChatLook.sun ? Color.black : C.line, lineWidth: ChatLook.sun ? 2 : 1))
    }
    private var canSend: Bool { !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || note != nil }
    private var sendButton: some View {
        Button {
            if rec.on { if let n = rec.stop() { note = n; hub.sendTyping(c, nil) } else { msg = t("Too short. Record a little longer.", "Fupi mno. Rekodi kidogo zaidi.") }; return }
            if note != nil { sendNote(); return }
            if canSend { sendText() } else { startRec() }
        } label: {
            Image(systemName: rec.on ? "stop.fill" : (canSend ? "paperplane.fill" : "mic.fill")).font(.system(size: 20, weight: .semibold)).foregroundColor(.white)
                .frame(width: 48, height: 48).background(ChatLook.go).clipShape(Circle())
        }
        .buttonStyle(Press())
        .accessibilityLabel(rec.on ? t("Stop and listen", "Simamisha usikilize") : canSend ? t("Send", "Tuma") : t("Record a voice message", "Rekodi ujumbe wa sauti"))
    }
    private var recBar: some View {
        HStack(spacing: 8) {
            Button { rec.cancel(); hub.sendTyping(c, nil); Haptic.tap() } label: { CIcon("trash", size: 20, color: C.bad).frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel(t("Delete recording", "Futa rekodi"))
            Circle().fill(C.bad).frame(width: 10, height: 10)
            Text(chatClock(rec.secs)).font(.mont(15, .bold)).foregroundColor(C.ink).monospacedDigit()
            ChatWave(bars: Array(rec.level.suffix(40)), progress: 1, played: C.bad.opacity(0.7), rest: C.muted)
        }.padding(.horizontal, 6).frame(minHeight: 48).background(C.card).clipShape(Capsule())
    }
    private func noteBar(_ n: ChatMedia) -> some View {
        let on = player.playing == "note"
        return HStack(spacing: 8) {
            Button { player.stop(); try? FileManager.default.removeItem(at: Outbox.resolve(n.local)); note = nil } label: { CIcon("trash", size: 20, color: C.bad).frame(width: 44, height: 44) }
                .buttonStyle(.plain).accessibilityLabel(t("Delete voice message", "Futa ujumbe wa sauti"))
            Button { player.toggle("note", Outbox.resolve(n.local)) } label: { CIcon(on ? "pause" : "play", size: 20, color: C.blue).frame(width: 40, height: 40).background(C.tint).clipShape(Circle()) }
                .buttonStyle(.plain).accessibilityLabel(on ? t("Pause", "Simamisha") : t("Listen", "Sikiliza"))
            ChatWave(bars: n.wave, progress: on ? player.progress : 0, played: C.blue, rest: C.muted)
            Text(chatClock(n.dur)).font(.mont(13, .bold)).foregroundColor(C.muted).monospacedDigit()
        }.padding(.horizontal, 6).frame(minHeight: 48).background(C.card).clipShape(Capsule())
    }
    private var replyBar: some View {
        HStack(spacing: 6) {
            Group {
                if let e = editing { ChatQuoteBox(q: ChatQuote(id: e.id, from: t("Edit message", "Hariri ujumbe"), kind: e.kind, text: e.text), me: "") }
                else if let r = reply { ChatQuoteBox(q: ChatQuote(id: r.id, from: r.from, kind: r.kind, text: r.preview()), me: hub.me) }
            }
            Button { if editing != nil { editing = nil; text = "" } else { reply = nil } } label: { Icon("x", size: 16, color: C.muted).frame(width: 36, height: 36) }
                .buttonStyle(.plain).accessibilityLabel(t("Cancel", "Ghairi"))
        }.padding(.horizontal, 10).padding(.top, 8)
    }
    private var quick: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(hub.settings.quick, id: \.self) { q in
                    Button { sendText(q) } label: {
                        Text(q).font(.mont(13, .semibold)).foregroundColor(C.ink).lineLimit(1).fixedSize().padding(.horizontal, 12).frame(minHeight: 34)
                            .background(C.card).clipShape(Capsule()).overlay(Capsule().stroke(ChatLook.sun ? Color.black : C.line, lineWidth: 1))
                    }.buttonStyle(.plain)
                }
            }.padding(.horizontal, 10).padding(.top, 8)
        }
    }
    /// @names in a group: typing "@" offers the members.
    @ViewBuilder private var mentionList: some View {
        let members = (row?.members ?? []).filter { !chatSame($0, hub.me) }
        if row?.isGroup == true, let at = text.range(of: "@", options: .backwards), !text[at.upperBound...].contains(" ") {
            let part = String(text[at.upperBound...]).lowercased()
            let list = members.filter { part.isEmpty || $0.lowercased().contains(part) }.prefix(5)
            if !list.isEmpty {
                VStack(spacing: 0) {
                    ForEach(Array(list), id: \.self) { n in
                        Button { text = String(text[..<at.lowerBound]) + "@" + n + " " } label: {
                            HStack(spacing: 10) { ChatPersonAvatar(name: n, size: 30); Text(n).font(.tSmall).foregroundColor(C.ink); Spacer() }
                                .padding(.horizontal, 14).frame(minHeight: 44).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                }.background(C.card).overlay(alignment: .top) { Rectangle().fill(C.line).frame(height: 1) }
            }
        }
    }

    // MARK: sending

    private func after() {
        Haptic.tap()
        if !hub.quiet(c) && hub.settings.sendSound && !hub.settings.dnd.active() { ChatSound.sent() }
        hub.sendTyping(c, nil)
    }
    private func sendText(_ t0: String? = nil) {
        let txt = (t0 ?? text).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !txt.isEmpty else { return }
        if let e = editing {
            Task { if let err = await hub.editMsg(e, txt) { msg = err } }
            editing = nil
        } else {
            let mentions = (row?.members ?? []).filter { txt.range(of: "@" + $0, options: .caseInsensitive) != nil }
            hub.send(c, kind: "text", text: txt, reply: reply, mentions: mentions)
            reply = nil
        }
        if t0 == nil { text = "" }
        msg = nil
        after()
    }
    private func startRec() {
        msg = nil
        rec.start { e in if let e { msg = e } else { hub.sendTyping(c, "recording"); Haptic.tap() } }
    }
    private func sendNote() {
        guard let n = note else { return }
        player.stop()
        hub.send(c, kind: "voice", files: [n], reply: reply)
        note = nil; reply = nil; after()
    }
    private func sendMedia(_ list: [ChatMedia], _ caption: String) {
        guard !list.isEmpty else { return }
        hub.send(c, kind: "media", text: caption, files: list, reply: reply)
        reply = nil; picked = nil; after()
    }
    private func here() -> LatLng? {
        if let f = Locator.shared.fix { return f.ll }
        Locator.shared.start()
        msg = Locator.shared.denied ? t("Allow location for RAFIKI Pin in Settings to share where you are.", "Ruhusu mahali kwa RAFIKI Pin kwenye Settings ili kushiriki ulipo.") : t("Finding where you are… try again in a moment.", "Inatafuta ulipo… jaribu tena baada ya muda mfupi.")
        return nil
    }

    @ViewBuilder private var sheetView: some View {
        switch sheet ?? "" {
        case "attach": ChatAttachSheet { k in DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { pick(k) } }.environmentObject(m).storySheet([.medium])
        case "gallery": ChatGalleryPicker { list, why in sheet = nil; msg = why; if !list.isEmpty { DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { picked = list } } }.ignoresSafeArea()
        case "camera": CameraPicker { img in sheet = nil; if let img, let md = ChatFiles.keepPhoto(img) { DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { picked = [md] } } }.ignoresSafeArea()
        case "doc": ChatDocPicker { md, why in sheet = nil; if let md { hub.send(c, kind: "doc", files: [md], reply: reply); reply = nil; after() } else { msg = why } }.ignoresSafeArea()
        case "live": ChatLiveSheet { mins in if let ll = here() { hub.send(c, kind: "live", loc: ll, liveMinutes: mins); after() } }.environmentObject(m).storySheet([.medium])
        case "contact": ChatMatePicker(hub: hub, title: t("Share a contact", "Shiriki mawasiliano"), multi: false, exclude: []) { names in if let n = names.first { hub.send(c, kind: "contact", contact: n); after() } }.environmentObject(m).storySheet([.large])
        default: EmptyView()
        }
    }
    private func pick(_ k: String) {
        switch k {
        case "loc": if let ll = here() { hub.send(c, kind: "location", reply: reply, loc: ll); reply = nil; after() }
        case "camera":
            if AVCaptureDevice.authorizationStatus(for: .video) == .denied { msg = t("Allow the camera in Settings to take photos, or choose from your photos.", "Ruhusu kamera kwenye Settings ili kupiga picha, au chagua kutoka kwenye picha zako.") }
            else { sheet = "camera" }
        default: sheet = k
        }
    }
}

/// Send: Photos & videos, Camera, Document, Location, Live location, Contact.
struct ChatAttachSheet: View {
    let pick: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        let items: [(String, String, String, String)] = [("gallery", t("Photos & videos", "Picha na video"), "gallery", "blue"), ("camera", t("Camera", "Kamera"), "camera", "coral"),
                                                         ("doc", t("Document", "Hati"), "doc", "amber"), ("loc", t("Location", "Mahali"), "pin", "green"),
                                                         ("live", t("Live location", "Moja kwa moja"), "live", "teal"), ("contact", t("Contact", "Mawasiliano"), "contact", "slate")]
        VStack(alignment: .leading, spacing: 12) {
            ChatSheetHead(title: t("Send", "Tuma"))
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 12) {
                ForEach(items, id: \.0) { k, label, icon, hue in
                    let hu = ChatHues.named(hue)
                    Button { Haptic.tap(); dismiss(); pick(k) } label: {
                        VStack(spacing: 6) {
                            CIcon(icon, size: 26, color: hu.fg).frame(width: 56, height: 56).background(hu.bg).clipShape(Circle())
                            FitWords(label, size: 12, weight: .bold, color: C.ink, lines: 2, align: .center)
                        }.frame(maxWidth: .infinity).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
            }
            Spacer(minLength: 0)
        }.padding(20)
    }
}

/// Share live location for 15 minutes, an hour or 8 hours.
struct ChatLiveSheet: View {
    let pick: (Int) -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ChatSheetHead(title: t("Share live location", "Shiriki mahali moja kwa moja"))
            Text(t("Your position updates while RAFIKI Pin is open. Stop any time.", "Mahali ulipo panasasishwa RAFIKI Pin ikiwa wazi. Acha wakati wowote.")).font(.bSmall).foregroundColor(C.muted).padding(.bottom, 8)
            ChatSheetRow("clock", t("15 minutes", "Dakika 15")) { dismiss(); pick(15) }
            ChatSheetRow("clock", t("1 hour", "Saa 1")) { dismiss(); pick(60) }
            ChatSheetRow("clock", t("8 hours", "Saa 8")) { dismiss(); pick(480) }
            Spacer(minLength: 0)
        }.padding(20)
    }
}

/// The photos and videos chosen, with a caption, before they go.
struct ChatPreviewSheet: View {
    @State var list: [ChatMedia]
    let send: ([ChatMedia], String) -> Void
    @State private var caption = ""
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                ChatSheetHead(title: list.count == 1 ? t("Send 1 item", "Tuma kimoja") : t("Send \(list.count) items", "Tuma vitu \(list.count)"))
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                    ForEach(Array(list.enumerated()), id: \.offset) { i, md in
                        ChatPic(md: md, small: true).aspectRatio(1, contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .overlay(alignment: .topTrailing) {
                                Button { list.remove(at: i); if list.isEmpty { dismiss() } } label: { Icon("x", size: 14, color: .white).frame(width: 30, height: 30).background(Color.black.opacity(0.6)).clipShape(Circle()) }
                                    .buttonStyle(.plain).padding(4).accessibilityLabel(t("Remove", "Ondoa"))
                            }
                            .overlay { if md.type == "video" { CIcon("play", size: 20, color: .white).frame(width: 36, height: 36).background(Color.black.opacity(0.5)).clipShape(Circle()) } }
                    }
                }
                Field(t("Caption (optional)", "Maelezo (si lazima)"), $caption)
                Btn(t("Send", "Tuma"), icon: "next", enabled: !list.isEmpty, wide: true) { dismiss(); send(list, caption.trimmingCharacters(in: .whitespacesAndNewlines)) }.padding(.top, 8)
            }.padding(20)
        }
    }
}

/// Choose teammates (a contact to share, people to add).
struct ChatMatePicker: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    let title: String; let multi: Bool; let exclude: [String]; let done: ([String]) -> Void
    @State private var q = ""
    @State private var chosen: [String] = []
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        let w = q.trimmingCharacters(in: .whitespaces).lowercased()
        let people = (m.team ?? []).map(\.name).filter { !chatSame($0, hub.me) && !exclude.contains(where: { x in chatSame(x, $0) }) && (w.isEmpty || $0.lowercased().contains(w)) }
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                ChatSheetHead(title: title)
                ChatSearchBox(q: $q, hint: t("Search the team", "Tafuta kwenye timu"))
                if m.team == nil { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted).padding(8) }
                else if people.isEmpty { Text(t("Nobody to add.", "Hakuna wa kuongeza.")).font(.bMedium).foregroundColor(C.muted).padding(8) }
                ForEach(people, id: \.self) { n in
                    let on = chosen.contains(n)
                    Button {
                        if multi { if on { chosen.removeAll { $0 == n } } else { chosen.append(n) } } else { dismiss(); done([n]) }
                    } label: {
                        HStack(spacing: 12) {
                            ChatPersonAvatar(name: n, size: 40)
                            Text(n).font(.tSmall).foregroundColor(C.ink).lineLimit(1)
                            Spacer(minLength: 4)
                            if multi {
                                Circle().fill(on ? ChatLook.online : .clear).frame(width: 24, height: 24).overlay(Circle().stroke(on ? ChatLook.online : C.edge, lineWidth: 2))
                                    .overlay { if on { Icon("check", size: 14, color: .white) } }
                            }
                        }.frame(minHeight: 52).contentShape(Rectangle())
                    }.buttonStyle(.plain).accessibilityAddTraits(on ? .isSelected : [])
                }
                if multi {
                    Btn(chosen.isEmpty ? t("Choose people", "Chagua watu") : t("Add \(chosen.count)", "Ongeza \(chosen.count)"), icon: "check", enabled: !chosen.isEmpty, wide: true) { dismiss(); done(chosen) }.padding(.top, 10)
                }
            }.padding(20)
        }
        .task { if m.team == nil { await m.loadTeam() } }
    }
}
