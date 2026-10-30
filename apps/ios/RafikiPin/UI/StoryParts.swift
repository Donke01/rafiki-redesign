import ImageIO
import SwiftUI
import UIKit

// Small pieces the Stories screens share: words that shrink rather than break, photos that need the sign-in,
// thumbnails of photos on this phone, sheet headers, the Before / In progress / After switch, badges.

/// Text that wraps only between words: when the longest word (or the whole text within `lines`) doesn't fit the width,
/// the letters get a little smaller instead of a word being split.
struct FitWords: View {
    let text: String; let size: CGFloat; let weight: Font.Weight; let color: Color; let lines: Int; let align: TextAlignment; let min: CGFloat
    init(_ text: String, size: CGFloat = 12, weight: Font.Weight = .bold, color: Color = C.ink, lines: Int = 2, align: TextAlignment = .leading, min: CGFloat = 0.6) {
        self.text = text; self.size = size; self.weight = weight; self.color = color; self.lines = lines; self.align = align; self.min = min
    }
    private var scales: [CGFloat] { [1, 0.92, 0.84, 0.76, 0.68, 0.6].filter { $0 >= min - 0.001 } }
    private var longest: String {
        text.split(whereSeparator: { $0 == " " || $0 == "\n" }).map(String.init).max(by: { $0.count < $1.count }) ?? text
    }
    var body: some View {
        let sc = scales
        let word = longest
        FitWordsLayout(n: sc.count, center: align == .center) {
            ForEach(0..<sc.count, id: \.self) { i in
                Text(text).font(.mont(size * sc[i], weight)).foregroundColor(color).lineLimit(lines).multilineTextAlignment(align)
            }
            ForEach(0..<sc.count, id: \.self) { i in
                Text(word).font(.mont(size * sc[i], weight)).lineLimit(1).fixedSize().hidden()
            }
            ForEach(0..<sc.count, id: \.self) { i in
                Text(text).font(.mont(size * sc[i], weight)).multilineTextAlignment(align).hidden()
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(text))
    }
}

/// Picks the biggest size at which the longest word fits the width and the text needs no more than its lines.
struct FitWordsLayout: Layout {
    let n: Int; let center: Bool

    private func pick(_ width: CGFloat?, _ subviews: Subviews) -> Int {
        guard let w = width, w.isFinite, subviews.count >= n * 3 else { return 0 }
        for i in 0..<n {
            let word = subviews[n + i].sizeThatFits(.unspecified).width
            if word > w + 0.5 { continue }
            let limited = subviews[i].sizeThatFits(ProposedViewSize(width: w, height: nil)).height
            let full = subviews[2 * n + i].sizeThatFits(ProposedViewSize(width: w, height: nil)).height
            if full <= limited + 0.5 { return i }
        }
        return n - 1
    }
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard !subviews.isEmpty else { return .zero }
        let i = pick(proposal.width, subviews)
        return subviews[i].sizeThatFits(ProposedViewSize(width: proposal.width, height: nil))
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard !subviews.isEmpty else { return }
        let i = pick(bounds.width, subviews)
        for j in subviews.indices {
            if j == i {
                let p = ProposedViewSize(width: bounds.width, height: bounds.height)
                if center { subviews[j].place(at: CGPoint(x: bounds.midX, y: bounds.minY), anchor: .top, proposal: p) }
                else { subviews[j].place(at: bounds.origin, proposal: p) }
            } else {
                // the sizes not used (and the measuring copies) go far off the screen
                subviews[j].place(at: CGPoint(x: bounds.minX - 100_000, y: bounds.minY), proposal: ProposedViewSize(width: bounds.width, height: nil))
            }
        }
    }
}

/// A Binding<Bool> that is true while an optional has a value (sheets and covers that show one thing).
func isSet<T>(_ b: Binding<T?>) -> Binding<Bool> {
    Binding(get: { b.wrappedValue != nil }, set: { if !$0 { b.wrappedValue = nil } })
}

/// The app's colours in a sheet (sheets are separate windows on iPhone).
extension View {
    func storySheet(_ detents: Set<PresentationDetent> = [.large]) -> some View {
        self
            .background(C.card.ignoresSafeArea())
            .preferredColorScheme(Look.mode == "dark" ? .dark : (Look.mode == "light" || Look.mode == "sun") ? .light : nil)
            .presentationDetents(detents)
            .presentationDragIndicator(.visible)
    }
}

// --- photos -------------------------------------------------------------------------------------------------------

/// Story photos from the server (they need the sign-in), kept in memory so lists don't flicker.
final class StoryPics {
    static let shared = StoryPics()
    private let mem = NSCache<NSString, UIImage>()
    init() { mem.totalCostLimit = 32 * 1024 * 1024 }
    func cached(_ path: String) -> UIImage? { mem.object(forKey: path as NSString) }
    func load(_ api: Api, _ path: String) async -> UIImage? {
        if let c = cached(path) { return c }
        let (status, data) = await api.bytes(path)
        guard (200..<300).contains(status), let img = UIImage(data: data) else { return nil }
        mem.setObject(img, forKey: path as NSString, cost: data.count)
        return img
    }
}

/// A story photo from the server, filling its frame (or fitted, for the big view).
struct AuthPic: View {
    @EnvironmentObject var m: AppModel
    let path: String; var fit = false
    @State private var img: UIImage?
    var body: some View {
        ZStack {
            if let img {
                if fit { Image(uiImage: img).resizable().scaledToFit() } else { Image(uiImage: img).resizable().scaledToFill() }
            } else {
                Icon("camera", size: 22, color: C.muted)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: path) {
            img = StoryPics.shared.cached(path)
            if img == nil { img = await StoryPics.shared.load(m.api, path) }
        }
    }
}

/// Small copies of photos on this phone (made with ImageIO, upright), kept in memory.
enum Thumbs {
    private static let mem = NSCache<NSString, UIImage>()
    static func cached(_ path: String, _ px: Int) -> UIImage? { mem.object(forKey: "\(px)|\(path)" as NSString) }
    static func load(_ path: String, _ px: Int) async -> UIImage? {
        if let c = cached(path, px) { return c }
        let img: UIImage? = await Task.detached(priority: .userInitiated) { () -> UIImage? in
            guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil) else { return nil }
            let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                                         kCGImageSourceThumbnailMaxPixelSize: px]
            guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
            return UIImage(cgImage: cg)
        }.value
        if let img { mem.setObject(img, forKey: "\(px)|\(path)" as NSString) }
        return img
    }
}

/// A photo file on this phone, made small for a tile.
struct LocalPic: View {
    let path: String; var px = 360
    @State private var img: UIImage?
    var body: some View {
        ZStack { if let img { Image(uiImage: img).resizable().scaledToFill() } }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .task(id: path) {
                img = Thumbs.cached(path, px)
                if img == nil { img = await Thumbs.load(path, px) }
            }
    }
}

/// A square tile with rounded corners, whatever is drawn in it.
struct SquareTile<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View {
        Color.clear.aspectRatio(1, contentMode: .fit)
            .overlay(content())
            .background(C.tint)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// A photo as big as the screen, on black; ✕ closes.
struct PhotoViewer: View {
    let path: String; let title: String; let close: () -> Void
    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            AuthPic(path: path, fit: true).padding(.vertical, 60)
            HStack(spacing: 8) {
                Text(title).font(.tSmall).foregroundColor(.white).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                Button(action: close) { Icon("x", size: 20, color: .white).frame(width: 44, height: 44).background(Color.white.opacity(0.18)).clipShape(Circle()) }
                    .accessibilityLabel(t("Close", "Funga"))
            }.padding(.horizontal, 16).padding(.top, 8)
        }
        .preferredColorScheme(.dark)
    }
}

// --- sheet parts ----------------------------------------------------------------------------------------------------

struct CloseX: View {
    let action: () -> Void
    var body: some View {
        Button(action: action) { Icon("x", size: 20, color: C.ink).frame(width: 44, height: 44).background(C.bg).clipShape(Circle()) }
            .buttonStyle(.plain).accessibilityLabel(t("Close", "Funga"))
    }
}

/// A sheet's title, a line under it, and ✕.
struct SheetHead: View {
    let title: String; var sub: String? = nil; let close: () -> Void
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.tLarge).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                if let sub, !sub.isEmpty { Text(sub).font(.bMedium).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true) }
            }.frame(maxWidth: .infinity, alignment: .leading)
            CloseX(action: close)
        }
    }
}

/// Before / In progress / After as one segmented control.
struct RowSeg: View {
    let sel: String; let pick: (String) -> Void
    var body: some View {
        HStack(spacing: 4) {
            ForEach(StoryKit.ROWS, id: \.self) { r in
                let on = r == sel
                Button { Haptic.tap(); pick(r) } label: {
                    FitWords(StoryKit.row(r), size: 14, weight: on ? .heavy : .semibold, color: on ? C.blue2 : C.muted, lines: 2, align: .center)
                        .padding(.horizontal, 4).padding(.vertical, 6)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(on ? C.card : Color.clear)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .shadow(color: .black.opacity(on && !C.dark ? 0.12 : 0), radius: 2, y: 1)
                        .contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(4).background(C.bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// A small coloured status label with a dot: "Collecting", "Waiting for review"…
struct StatusPill: View {
    let label: String; let tone: String
    private func colours() -> (Color, Color) {
        switch tone {
        case "bad": return (C.bad, C.badBg)
        case "warn": return (C.warn, C.warnBg)
        case "ok": return (C.ok, C.okBg)
        case "has": return (C.blue2, C.tint)
        default: return (C.muted, C.bg)
        }
    }
    var body: some View {
        let c = colours()
        let fg = c.0, bg = c.1
        let sun = Look.mode == "sun"
        HStack(spacing: 6) {
            Circle().fill(fg).frame(width: 7, height: 7)
            Text(label).font(.mont(12, .heavy)).foregroundColor(fg).lineLimit(1).minimumScaleFactor(0.8)
        }
        .padding(.leading, 8).padding(.trailing, 10).padding(.vertical, 3)
        .background(bg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(sun ? fg : .clear, lineWidth: 1.5))
    }
}

let StoryNight = Color(hex: 0x04263F)
let StoryNight2 = Color(hex: 0x06375A)
let StoryMint = Color(hex: 0x7FE3B0)
let StorySend = Color(hex: 0x2FBF71)
let KidsBrown = Color(hex: 0x8A5200)

/// "In story" on a photo the drafted story shows.
struct InStoryBadge: View {
    var body: some View {
        HStack(spacing: 3) {
            Image(systemName: "sparkle").font(.system(size: 8, weight: .bold)).foregroundColor(StoryMint)
            Text(t("In story", "Hadithini")).font(.mont(10, .bold)).foregroundColor(.white).lineLimit(1).minimumScaleFactor(0.7)
        }
        .padding(.leading, 5).padding(.trailing, 7).padding(.vertical, 3)
        .background(Color(hex: 0x04263F, alpha: 0.85)).clipShape(Capsule())
    }
}

/// "Children" on a photo that shows children.
struct KidsBadge: View {
    var size: CGFloat = 10
    var body: some View {
        Text(t("Children", "Watoto")).font(.mont(size, .bold)).foregroundColor(.white).lineLimit(1).minimumScaleFactor(0.7)
            .padding(.horizontal, 6).padding(.vertical, 2).background(KidsBrown.opacity(0.85)).clipShape(Capsule())
    }
}

/// The story's cover photo: a small round amber star.
struct CoverBadge: View {
    var body: some View {
        Image(systemName: "star.fill").font(.system(size: 12, weight: .bold)).foregroundColor(Color(hex: 0x3A2600))
            .frame(width: 26, height: 26).background(Color(hex: 0xF2B33D)).clipShape(Circle())
            .shadow(color: .black.opacity(0.25), radius: 2, y: 1)
            .accessibilityLabel(t("Cover photo", "Picha ya jalada"))
    }
}

/// A white-ringed green tick: the photo is on the server.
struct SentTick: View {
    var body: some View {
        Icon("check", size: 14, color: .white).frame(width: 20, height: 20).background(GreenC).clipShape(Circle())
            .padding(2).background(Color.white).clipShape(Circle())
            .accessibilityLabel(t("Sent", "Imetumwa"))
    }
}

/// "Use as cover", or for the cover "Cover photo ✓ · Tap to undo" in amber; busy while it saves.
struct StoryCoverBtn: View {
    let on: Bool; let busy: Bool; let action: () -> Void
    var body: some View {
        let saving: String? = busy ? t("Saving…", "Inahifadhi…") : nil
        if !on {
            Btn(t("Use as cover", "Tumia kama jalada"), icon: "star", light: true, small: true, wide: true, busy: saving, action: action)
        } else {
            let fg = C.dark ? Color(hex: 0xF7D27E) : Color(hex: 0x6B4300)
            Button(action: action) {
                HStack(spacing: busy ? 10 : 8) {
                    if busy { ProgressView().progressViewStyle(.circular).tint(fg).frame(width: 18, height: 18) }
                    else { Image(systemName: "star.fill").font(.system(size: 15, weight: .bold)).foregroundColor(Color(hex: 0xF2B33D)) }
                    Text(saving ?? t("Cover photo ✓\u{00A0}· Tap to undo", "Picha ya jalada ✓\u{00A0}· Gusa kutendua"))
                        .font(.mont(14, .bold)).foregroundColor(fg).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, 16).padding(.vertical, 8).frame(maxWidth: .infinity, minHeight: 44)
                .background(C.dark ? Color(hex: 0x3A2C10) : Color(hex: 0xFFF4DC)).clipShape(Capsule())
                .overlay(Capsule().stroke(Color(hex: 0xF2B33D), lineWidth: 1))
            }
            .buttonStyle(Press()).disabled(busy)
            .accessibilityAddTraits(.isSelected)
        }
    }
}
