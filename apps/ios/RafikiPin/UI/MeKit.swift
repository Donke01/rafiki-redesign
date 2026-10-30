import AVFoundation
import SwiftUI
import UIKit

// Pieces the Me area shares (Android MeKit.kt, 2.25): profile photos with initials as the fallback, the photo sheet
// (take or choose, fit it in the circle), centred content on wide screens, and a few small helpers.

/// A system symbol at a size, for icons the app's icon list doesn't have (bell, envelope, key…).
struct SFIcon: View {
    let name: String; var size: CGFloat = 20; var color: Color = C.ink
    init(_ name: String, size: CGFloat = 20, color: Color = C.ink) { self.name = name; self.size = size; self.color = color }
    var body: some View { Image(systemName: name).font(.system(size: size * 0.82, weight: .semibold)).foregroundColor(color).frame(width: size, height: size) }
}

/// "GW" for Grace Wanjiru.
func initialsOf(_ name: String) -> String {
    let s = name.split(whereSeparator: { $0 == " " || $0 == "\n" }).compactMap { $0.first.map { String($0).uppercased() } }.prefix(2).joined()
    return s.isEmpty ? "·" : s
}

/// A colour for a person, worked out from their name (the same as Android and the web).
func nameHue(_ name: String) -> String {
    let hues = ["blue", "green", "purple", "amber", "teal", "coral"]
    var h: UInt64 = 0
    for c in name.trimmingCharacters(in: .whitespaces).lowercased().unicodeScalars { h = (h &* 31 &+ UInt64(c.value)) & 0xFFFF_FFFF }
    return hues[Int(h % UInt64(hues.count))]
}

/// [name]'s profile photo in a circle when they have one (and it loads), else [initials].
struct PhotoOr<Initials: View>: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var ph = TeamPhotos.shared
    let name: String; let size: CGFloat
    @ViewBuilder var initials: () -> Initials
    @State private var img: UIImage?
    var body: some View {
        let url = ph.url(name)
        let shown: UIImage? = url == nil ? nil : (img ?? ph.cached(url))
        Group {
            if let shown {
                Color.clear.frame(width: size, height: size)
                    .overlay(Image(uiImage: shown).resizable().scaledToFill())
                    .clipShape(Circle())
            } else { initials() }
        }
        .task(id: url ?? "") {
            guard let url else { img = nil; return }
            img = ph.cached(url)
            if img == nil { img = await ph.load(m.api, name: name, url: url) }
        }
    }
}

/// A person's round picture: their photo, or initials in their colour.
struct PersonAvatar: View {
    let name: String; var size: CGFloat = 44
    var body: some View {
        let hu = meHue(nameHue(name))
        PhotoOr(name: name, size: size) {
            Text(initialsOf(name)).font(.mont(size * 0.36, .heavy)).foregroundColor(hu.fg).lineLimit(1).minimumScaleFactor(0.6)
                .frame(width: size, height: size).background(Circle().fill(hu.bg))
        }
    }
}

/// The blue initials circle, or the photo (the app's own avatar look).
struct MeFace: View {
    let name: String; var size: CGFloat = 34
    var body: some View {
        PhotoOr(name: name, size: size) { Avatar(name: name, size: size) }
    }
}

/// A big centred title, as every page has (Android 2.14 "centred titles").
struct MeTitle: View {
    let text: String; var sub: String? = nil
    init(_ text: String, sub: String? = nil) { self.text = text; self.sub = sub }
    var body: some View {
        VStack(spacing: 2) {
            Text(text).font(.hLarge).foregroundColor(C.ink).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            if let sub, !sub.isEmpty { Text(sub).font(.mont(13, .semibold)).foregroundColor(C.muted).multilineTextAlignment(.center) }
        }.frame(maxWidth: .infinity)
    }
}

/// A card's heading, centred, with something small beside it.
struct MeH2<Extra: View>: View {
    let text: String
    let extra: () -> Extra
    init(_ text: String, @ViewBuilder extra: @escaping () -> Extra) { self.text = text; self.extra = extra }
    var body: some View {
        HStack(spacing: 10) {
            Text(text).font(.tLarge).foregroundColor(C.ink).multilineTextAlignment(.center)
            extra()
        }.frame(maxWidth: .infinity).padding(.bottom, 4)
    }
}
extension MeH2 where Extra == EmptyView {
    init(_ text: String) { self.text = text; self.extra = { EmptyView() } }
}

extension View {
    /// A page's content in one centred column no wider than 720 points (phones: the full width, as before).
    func meCentred() -> some View { frame(maxWidth: 720).frame(maxWidth: .infinity) }
}

/// A small label over a group of rows ("ACCOUNT", "TELL ME ABOUT").
struct MeGroupLabel: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text.uppercased()).font(.mont(11, .bold)).tracking(1.4).foregroundColor(C.muted)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 2).padding(.top, 16).padding(.bottom, 2)
    }
}

// MARK: - the photo sheet

/// Add, change or remove a profile photo: [name]'s ([me]: it's mine). Take a photo or choose one, fit it in the circle;
/// it's made small on the phone (about 512 px, 200 KB or less) and saved. [saved] says what happened.
struct MePhotoSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var ph = TeamPhotos.shared
    let name: String; let me: Bool; let saved: (String) -> Void
    @State private var picked: UIImage?
    @State private var busy = false
    @State private var msg: String?
    @State private var askRemove = false
    @State private var cam = false
    @State private var gal = false

    func use(_ img: UIImage?) {
        guard let img else { return }
        // upright, and no bigger than needed for a 512 px circle
        let up = AvatarImage.upright(img)
        let k = min(1, 2048 / max(up.size.width, up.size.height))
        if k < 1 {
            let sz = CGSize(width: (up.size.width * k).rounded(), height: (up.size.height * k).rounded())
            let f = UIGraphicsImageRendererFormat(); f.scale = 1
            picked = UIGraphicsImageRenderer(size: sz, format: f).image { _ in up.draw(in: CGRect(origin: .zero, size: sz)) }
        } else { picked = up }
        msg = nil
    }
    func camera() {
        if m.demo { use(UIImage(data: Demo.photo("me", size: CGSize(width: 800, height: 600)))); return }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .denied, .restricted: msg = t("Allow the camera in Settings to take photos, or choose from your photos.", "Ruhusu kamera kwenye Settings ili kupiga picha, au chagua kutoka kwenye picha zako.")
        default: cam = true
        }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                HStack { Spacer(); CloseX { dismiss() } }
                if let img = picked { MeCropper(img: img, name: name, me: me, busy: $busy, msg: $msg, again: { picked = nil; msg = nil }) { _ in saved(t("Photo saved ✓", "Picha imehifadhiwa ✓")); dismiss() } }
                else { choose }
                if let msg { Msg(msg, "bad") }
            }.padding(.horizontal, 20).padding(.bottom, 28).padding(.top, 8)
        }
        .storySheet()
        .fullScreenCover(isPresented: $cam) { CameraPicker { img in cam = false; use(img) }.ignoresSafeArea() }
        .sheet(isPresented: $gal) { GalleryPicker { img in gal = false; use(img) } }
    }

    private var choose: some View {
        VStack(spacing: 0) {
            PersonAvatar(name: name, size: 96).padding(4).background(Circle().fill(C.card)).overlay(Circle().stroke(C.line, lineWidth: 1))
            Text(me ? t("Your photo", "Picha yako") : t("\(name)’s photo", "Picha ya \(name)")).font(.tLarge).foregroundColor(C.ink).multilineTextAlignment(.center).padding(.top, 10)
            Text(me ? t("Your team sees it next to your name.", "Timu yako inaiona karibu na jina lako.") : t("Shown next to their name everywhere in the app.", "Inaonyeshwa karibu na jina lake kila mahali kwenye programu."))
                .font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).padding(.top, 2).padding(.horizontal, 16)
            VStack(spacing: 10) {
                Btn(t("Take a photo", "Piga picha"), icon: "camera", enabled: !busy, wide: true) { camera() }
                Btn(t("Choose from gallery", "Chagua kutoka kwenye picha"), icon: "upload", enabled: !busy, light: true, wide: true) {
                    if m.demo { use(UIImage(data: Demo.photo("gallery", size: CGSize(width: 600, height: 800)))) } else { gal = true }
                }
                if ph.url(name) != nil && !askRemove { Btn(t("Remove photo", "Ondoa picha"), icon: "trash", enabled: !busy, light: true, wide: true) { askRemove = true } }
                if askRemove { removeAsk }
            }.padding(.top, 16)
        }
    }

    private var removeAsk: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(me ? t("Remove your photo? Your initials show instead.", "Ondoa picha yako? Herufi za jina lako zitaonyeshwa badala yake.") : t("Remove \(name)’s photo? Their initials show instead.", "Ondoa picha ya \(name)? Herufi za jina lake zitaonyeshwa badala yake."))
                .font(.bMedium).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                Btn(t("Cancel", "Ghairi"), light: true, small: true, wide: true) { askRemove = false }
                Btn(t("Remove", "Ondoa"), enabled: !busy, small: true, wide: true, busy: busy ? t("Removing…", "Inaondoa…") : nil) {
                    busy = true
                    Task {
                        let e = await ph.remove(m.api, forName: me ? nil : name, me: m.prefs.name)
                        busy = false; askRemove = false
                        if e == nil { saved(t("Photo removed", "Picha imeondolewa")); dismiss() } else { msg = e }
                    }
                }
            }
        }.padding(12).frame(maxWidth: .infinity, alignment: .leading).background(C.badBg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    /// Fit it in the circle: drag to move, pinch (or the slider) to zoom; then Use photo.
    private struct MeCropper: View {
        @EnvironmentObject var m: AppModel
        let img: UIImage; let name: String; let me: Bool
        @Binding var busy: Bool
        @Binding var msg: String?
        let again: () -> Void
        let done: (String) -> Void
        @ObservedObject private var ph = TeamPhotos.shared
        @State private var zoom: CGFloat = 1
        @State private var zoomStart: CGFloat = 1
        @State private var off: CGSize = .zero
        @State private var offStart: CGSize = .zero
        @State private var view: CGFloat = 300

        private func clamp() {
            let mx = AvatarImage.maxShift(img.size.width, img.size.height, view: view, zoom: zoom)
            off = CGSize(width: min(max(off.width, -mx.width), mx.width), height: min(max(off.height, -mx.height), mx.height))
        }

        var body: some View {
            VStack(spacing: 0) {
                Text(t("Fit it in the circle", "Iweke ndani ya duara")).font(.tLarge).foregroundColor(C.ink).multilineTextAlignment(.center)
                Text(t("Drag to move it, pinch to zoom.", "Buruta kuisogeza, bana kukuza.")).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).padding(.top, 2)
                GeometryReader { g in
                    let v = g.size.width
                    let s = v / max(1, min(img.size.width, img.size.height)) * zoom
                    ZStack {
                        Color.black
                        Image(uiImage: img).resizable().frame(width: img.size.width * s, height: img.size.height * s).offset(off)
                        Canvas { ctx, size in
                            var p = Path(CGRect(origin: .zero, size: size))
                            p.addEllipse(in: CGRect(x: 4, y: 4, width: size.width - 8, height: size.height - 8))
                            ctx.fill(p, with: .color(Color.black.opacity(0.6)), style: FillStyle(eoFill: true))
                            ctx.stroke(Path(ellipseIn: CGRect(x: 4, y: 4, width: size.width - 8, height: size.height - 8)), with: .color(.white), lineWidth: 2)
                        }.allowsHitTesting(false)
                    }
                    .frame(width: v, height: v).clipped()
                    .contentShape(Rectangle())
                    .gesture(DragGesture().onChanged { d in off = CGSize(width: offStart.width + d.translation.width, height: offStart.height + d.translation.height); clamp() }
                                .onEnded { _ in offStart = off })
                    .simultaneousGesture(MagnificationGesture().onChanged { k in zoom = min(4, max(1, zoomStart * k)); clamp() }.onEnded { _ in zoomStart = zoom; offStart = off })
                    .onAppear { view = v }
                    .onChange(of: v) { nv in view = nv; clamp() }
                }
                .aspectRatio(1, contentMode: .fit).frame(maxWidth: 360)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                .padding(.top, 12)
                .accessibilityLabel(t("The photo, inside a round frame", "Picha, ndani ya fremu ya duara"))
                HStack(spacing: 8) {
                    Text("−").font(.tLarge).foregroundColor(C.muted).accessibilityHidden(true)
                    Slider(value: Binding(get: { Double(zoom) }, set: { zoom = CGFloat($0); zoomStart = zoom; clamp(); offStart = off }), in: 1...4)
                        .accessibilityLabel(t("Zoom", "Kuza"))
                    Text("+").font(.tLarge).foregroundColor(C.muted).accessibilityHidden(true)
                }.padding(.top, 10)
                Btn(t("Use photo", "Tumia picha"), icon: "check", enabled: !busy, wide: true, busy: busy ? t("Saving…", "Inahifadhi…") : nil) { save() }.padding(.top, 8)
                Btn(t("Choose another", "Chagua nyingine"), enabled: !busy, light: true, wide: true) { again() }.padding(.top, 10)
            }
        }

        func save() {
            let r = AvatarImage.cropRect(img.size.width, img.size.height, view: max(1, view), zoom: zoom, dx: off.width, dy: off.height)
            guard let data = AvatarImage.jpeg(img, r) else { msg = t("That picture couldn’t be opened. Try another one.", "Picha hiyo haikufunguka. Jaribu nyingine."); return }
            busy = true; msg = nil
            Task {
                let (url, e) = await ph.upload(m.api, forName: me ? nil : name, me: m.prefs.name, jpeg: data)
                busy = false
                if let url { done(url) } else { msg = e }
            }
        }
    }
}
