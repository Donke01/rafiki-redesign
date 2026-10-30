import AVFoundation
import AVKit
import ImageIO
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

// Photos, videos, voice notes and documents in chat: loading them (with the data saver), playing voice notes, recording
// them, the photo viewer, and the pickers.

/// Chat pictures kept in memory so lists don't flicker.
enum ChatPics {
    static let mem: NSCache<NSString, UIImage> = { let c = NSCache<NSString, UIImage>(); c.totalCostLimit = 40 * 1024 * 1024; return c }()
    static func key(_ md: ChatMedia, _ small: Bool) -> String { (small ? "s|" : "b|") + (md.id.isEmpty ? md.local : md.id) }
}

/// A chat photo filling its frame (never growing it). With the data saver on, a small preview first and the full photo on a tap.
struct ChatPic: View {
    @EnvironmentObject var m: AppModel
    let md: ChatMedia; var small = false; var fit = false
    @State private var img: UIImage?
    @State private var failed = false
    var body: some View {
        Color.clear
            .overlay(Group {
                if let img {
                    if fit { Image(uiImage: img).resizable().scaledToFit() } else { Image(uiImage: img).resizable().scaledToFill() }
                } else {
                    ZStack {
                        C.tint
                        if failed { Icon("camera", size: 22, color: C.muted) } else { ProgressView().tint(C.muted) }
                    }
                }
            })
            .clipped()
            .task(id: md.id + md.local + md.thumb) { await load() }
    }
    private func load() async {
        let hub = m.chat
        let video = md.type == "video"
        let hasThumb = !md.thumb.isEmpty || !md.thumbUrl.isEmpty
        // the data saver: a small preview in the chat; the full photo in the viewer
        let useThumb = video || (!fit && (small || hub.settings.saverOn) && hasThumb)
        let px = (useThumb || small) ? 360 : 1400
        let key = ChatPics.key(md, px < 1000) as NSString
        if !video, let c = ChatPics.mem.object(forKey: ChatPics.key(md, false) as NSString) { img = c; return }
        if let c = ChatPics.mem.object(forKey: key) { img = c; return }
        var f: URL? = nil
        if !video { f = hub.cachedFile(md) }
        if f == nil && useThumb && hasThumb { f = await hub.file(md, thumb: true) }
        if f == nil && !video { f = await hub.file(md) }
        guard let file = f, let i = await ChatPic.decode(file, px) else { failed = true; return }
        img = i
        ChatPics.mem.setObject(i, forKey: key)
    }
    /// A picture from a file, upright and at most `px` wide or high.
    static func decode(_ url: URL, _ px: Int) async -> UIImage? {
        await Task.detached(priority: .userInitiated) { () -> UIImage? in
            guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
            let o: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: px]
            guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, o as CFDictionary) else { return nil }
            return UIImage(cgImage: cg)
        }.value
    }
}

/// Up to four photos in a message: one big, or a grid of two to four ("+3" on the last).
struct ChatPhotoGrid: View {
    let media: [ChatMedia]; let width: CGFloat; let open: (Int) -> Void
    var body: some View {
        let n = media.count
        if n <= 1, let md = media.first {
            let ratio: CGFloat = md.w > 0 && md.h > 0 ? min(1.5, max(0.6, CGFloat(md.h) / CGFloat(md.w))) : 0.75
            cell(md, 0).frame(width: width, height: width * ratio)
        } else {
            let half = (width - 3) / 2
            VStack(spacing: 3) {
                HStack(spacing: 3) { cell(media[0], 0).frame(width: half, height: half); cell(media[1], 1).frame(width: half, height: half) }
                if n >= 3 {
                    HStack(spacing: 3) {
                        cell(media[2], 2).frame(width: n == 3 ? width : half, height: half)
                        if n >= 4 {
                            cell(media[3], 3).frame(width: half, height: half)
                                .overlay { if n > 4 { ZStack { Color.black.opacity(0.45); Text("+\(n - 4)").font(.hMedium).foregroundColor(.white) } } }
                        }
                    }
                }
            }
        }
    }
    private func cell(_ md: ChatMedia, _ i: Int) -> some View {
        Button { open(i) } label: {
            ChatPic(md: md)
                .overlay { if md.type == "video" { CIcon("play", size: 24, color: .white).frame(width: 46, height: 46).background(Color.black.opacity(0.55)).clipShape(Circle()) } }
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }.buttonStyle(.plain).accessibilityLabel(md.type == "video" ? t("Video", "Video") : t("Photo", "Picha"))
    }
}

/// The photo and video viewer: swipe between them, pinch to zoom, share.
struct ChatViewer: View {
    @EnvironmentObject var m: AppModel
    let items: [(ChatMsg, ChatMedia)]
    @State var at: Int
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            TabView(selection: $at) {
                ForEach(Array(items.enumerated()), id: \.offset) { i, pair in
                    ChatViewerPage(md: pair.1).tag(i)
                }
            }.tabViewStyle(.page(indexDisplayMode: .never))
            HStack(spacing: 8) {
                Button { dismiss() } label: { Icon("x", size: 22, color: .white).frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel(t("Close", "Funga"))
                if at < items.count {
                    let msg = items[at].0
                    VStack(alignment: .leading, spacing: 0) {
                        Text(chatSame(msg.from, m.chat.me) ? t("You", "Wewe") : msg.from).font(.tSmall).foregroundColor(.white).lineLimit(1)
                        Text(ChatKit.dateTime(msg.at)).font(.bSmall).foregroundColor(.white.opacity(0.75)).lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
                Text("\(min(at + 1, items.count)) / \(items.count)").font(.lMedium).foregroundColor(.white)
                Button { share() } label: { Icon("share", size: 20, color: .white).frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel(t("Share", "Shiriki"))
            }.padding(.horizontal, 8).background(LinearGradient(colors: [Color.black.opacity(0.6), .clear], startPoint: .top, endPoint: .bottom))
        }
        .preferredColorScheme(.dark)
    }
    private func share() {
        guard at < items.count else { return }
        let md = items[at].1
        Task { if let f = await m.chat.file(md) { Share.items([f]) } }
    }
}

private struct ChatViewerPage: View {
    @EnvironmentObject var m: AppModel
    let md: ChatMedia
    @State private var scale: CGFloat = 1
    @State private var player: AVPlayer?
    var body: some View {
        Group {
            if md.type == "video" {
                if let player { VideoPlayer(player: player).onDisappear { player.pause() } }
                else { ProgressView().tint(.white).task { if let f = await m.chat.file(md) { player = AVPlayer(url: f) } } }
            } else {
                ChatPic(md: md, fit: true)
                    .scaleEffect(scale)
                    .gesture(MagnificationGesture().onChanged { scale = min(4, max(1, $0)) }.onEnded { _ in if scale < 1.1 { withAnimation { scale = 1 } } })
                    .onTapGesture(count: 2) { withAnimation { scale = scale > 1 ? 1 : 2.5 } }
            }
        }.frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - voice notes ---------------------------------------------------------------------------------------------------

/// Plays one voice note at a time (1×, 1.5×, 2×).
final class ChatPlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    static let shared = ChatPlayer()
    @Published var playing: String?
    @Published var progress: Double = 0
    @Published var speed: Float = 1
    private var p: AVAudioPlayer?
    private var timer: Timer?

    func toggle(_ key: String, _ url: URL) {
        if playing == key { stop(); return }
        stop()
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try? AVAudioSession.sharedInstance().setActive(true)
        guard let x = try? AVAudioPlayer(contentsOf: url) else { return }
        x.delegate = self; x.enableRate = true; x.rate = speed
        if progress > 0 && progress < 1 { x.currentTime = x.duration * progress }
        p = x; x.play(); playing = key
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            guard let self, let p = self.p, p.duration > 0 else { return }
            self.progress = p.currentTime / p.duration
        }
    }
    func seek(_ f: Double) { if let p { p.currentTime = p.duration * max(0, min(1, f)) }; progress = f }
    func cycleSpeed() { speed = speed == 1 ? 1.5 : speed == 1.5 ? 2 : 1; p?.rate = speed }
    func stop() { timer?.invalidate(); timer = nil; p?.stop(); p = nil; playing = nil; progress = 0 }
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) { DispatchQueue.main.async { self.stop() } }
}

/// The bars of a voice note, coloured as far as it has played. A drag seeks.
struct ChatWave: View {
    let bars: [Int]; let progress: Double; let played: Color; let rest: Color; var seek: ((Double) -> Void)? = nil
    var body: some View {
        GeometryReader { g in
            let list = bars.isEmpty ? Array(repeating: 30, count: 28) : bars
            let n = max(1, min(list.count, Int(g.size.width / 4)))
            let step = Double(list.count) / Double(n)
            HStack(alignment: .center, spacing: 2) {
                ForEach(0..<n, id: \.self) { i in
                    let v = list[min(list.count - 1, Int(Double(i) * step))]
                    Capsule().fill(Double(i) / Double(n) < progress ? played : rest)
                        .frame(width: 2, height: max(3, g.size.height * CGFloat(v) / 100))
                }
            }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { v in seek?(Double(v.location.x / max(1, g.size.width))) })
        }.frame(height: 28)
    }
}

/// A voice note in a bubble: play, the bars, the time and the speed; "Show text" when typing out is switched on.
struct ChatVoiceBubble: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var player = ChatPlayer.shared
    let msg: ChatMsg; let md: ChatMedia; let fg: Color
    @State private var busy = false
    var body: some View {
        let key = msg.key
        let on = player.playing == key
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Button { play() } label: {
                    Group { if busy { ProgressView().tint(fg) } else { CIcon(on ? "pause" : "play", size: 20, color: fg) } }
                        .frame(width: 40, height: 40).background(fg.opacity(0.14)).clipShape(Circle())
                }.buttonStyle(.plain).accessibilityLabel(on ? t("Pause", "Simamisha") : t("Play voice message", "Cheza ujumbe wa sauti"))
                VStack(alignment: .leading, spacing: 2) {
                    ChatWave(bars: md.wave, progress: on ? player.progress : 0, played: ChatLook.read, rest: fg.opacity(0.35)) { f in if on { player.seek(f) } }
                    Text(on ? chatClock(md.dur * player.progress) : chatClock(md.dur)).font(.mont(11, .semibold)).foregroundColor(fg.opacity(0.75))
                }
                if on {
                    Button { player.cycleSpeed() } label: {
                        Text(player.speed == 1 ? "1×" : player.speed == 1.5 ? "1.5×" : "2×").font(.mont(12, .heavy)).foregroundColor(fg)
                            .padding(.horizontal, 8).frame(minHeight: 30).background(fg.opacity(0.14)).clipShape(Capsule())
                    }.buttonStyle(.plain).accessibilityLabel(t("Playback speed", "Kasi ya kucheza"))
                }
            }.frame(minWidth: 200)
            if let typed = m.chat.typedOut[msg.id] {
                Text(typed).font(.bMedium).foregroundColor(fg).fixedSize(horizontal: false, vertical: true)
            } else if m.chat.tools["transcribe"] == true && !msg.queued {
                Button { Task { let (tx, e) = await m.chat.transcribe(msg); if let tx { m.chat.typedOut[msg.id] = tx } else { m.chat.note = e } } } label: {
                    Text(t("Show text", "Onyesha maandishi")).font(.lMedium).foregroundColor(fg).frame(minHeight: 32)
                }.buttonStyle(.plain)
            }
        }
    }
    private func play() {
        if player.playing == msg.key { player.stop(); return }
        busy = true
        Task {
            let f = await m.chat.file(md)
            busy = false
            if let f { player.toggle(msg.key, f) } else { m.chat.note = t("Couldn’t download it. Check the signal.", "Imeshindwa kupakua. Angalia mtandao.") }
        }
    }
}

/// Records a voice note into the chat's outbox folder, with its loudness bars.
final class ChatRec: NSObject, ObservableObject {
    @Published var on = false
    @Published var secs: Double = 0
    @Published var level: [Int] = []
    private var rec: AVAudioRecorder?
    private var timer: Timer?
    private var name = ""

    /// Starts after the microphone is allowed; false (with a reason) otherwise.
    func start(_ done: @escaping (String?) -> Void) {
        AVAudioSession.sharedInstance().requestRecordPermission { ok in
            DispatchQueue.main.async {
                guard ok else { done(t("Allow the microphone to record voice messages.", "Ruhusu kipaza sauti ili kurekodi ujumbe wa sauti.")); return }
                do {
                    ChatPlayer.shared.stop()
                    let s = AVAudioSession.sharedInstance()
                    try s.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
                    try s.setActive(true)
                    self.name = ChatFiles.newName("m4a")
                    let r = try AVAudioRecorder(url: ChatFiles.outDir.appendingPathComponent(self.name), settings: [
                        AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 64000])
                    r.isMeteringEnabled = true
                    r.record(forDuration: 300)
                    self.rec = r; self.on = true; self.secs = 0; self.level = []
                    self.timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
                        guard let self, let r = self.rec else { return }
                        r.updateMeters()
                        let db = r.averagePower(forChannel: 0)
                        self.level.append(Int(max(4, min(100, (db + 55) * 2))))
                        self.secs = r.currentTime
                        if !r.isRecording { self.timer?.invalidate() }
                    }
                    done(nil)
                } catch { done(t("The microphone couldn’t start.", "Kipaza sauti hakikuweza kuanza.")) }
            }
        }
    }
    /// Stops; the note (or nil when under a second).
    func stop() -> ChatMedia? {
        timer?.invalidate(); timer = nil
        guard let r = rec else { on = false; return nil }
        let len = r.currentTime
        r.stop(); rec = nil; on = false
        let path = "chat/out/" + name
        if len < 1 { try? FileManager.default.removeItem(at: Outbox.resolve(path)); return nil }
        let size = ((try? FileManager.default.attributesOfItem(atPath: Outbox.resolve(path).path))?[.size] as? NSNumber)?.intValue ?? 0
        let bars = ChatRec.squash(level, 40)
        return ChatMedia(id: "", type: "voice", dur: len, wave: bars, name: "voice.m4a", size: size, local: path, mime: "audio/mp4")
    }
    func cancel() {
        timer?.invalidate(); timer = nil
        rec?.stop(); rec?.deleteRecording(); rec = nil; on = false
    }
    static func squash(_ l: [Int], _ n: Int) -> [Int] {
        guard !l.isEmpty else { return [] }
        if l.count <= n { return l }
        let step = Double(l.count) / Double(n)
        return (0..<n).map { i in
            let a = Int(Double(i) * step), b = max(a + 1, Int(Double(i + 1) * step))
            return l[a..<min(b, l.count)].max() ?? 0
        }
    }
}

// MARK: - pickers -------------------------------------------------------------------------------------------------------

/// Chooses up to ten photos (and videos up to a minute) from the phone's photos.
struct ChatGalleryPicker: UIViewControllerRepresentable {
    let done: ([ChatMedia], String?) -> Void
    func makeCoordinator() -> Coord { Coord(done) }
    func makeUIViewController(context: Context) -> PHPickerViewController {
        var cfg = PHPickerConfiguration(); cfg.filter = .any(of: [.images, .videos]); cfg.selectionLimit = 10
        let c = PHPickerViewController(configuration: cfg); c.delegate = context.coordinator; return c
    }
    func updateUIViewController(_ vc: PHPickerViewController, context: Context) {}
    final class Coord: NSObject, PHPickerViewControllerDelegate {
        let done: ([ChatMedia], String?) -> Void
        init(_ d: @escaping ([ChatMedia], String?) -> Void) { done = d }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            if results.isEmpty { done([], nil); return }
            let group = DispatchGroup()
            var out: [(Int, ChatMedia)] = []; var why: String?
            let lock = NSLock()
            for (i, r) in results.enumerated() {
                let p = r.itemProvider
                group.enter()
                if p.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                    p.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, _ in
                        defer { group.leave() }
                        guard let url else { lock.lock(); why = t("That video couldn’t be read.", "Video hiyo haikuweza kusomwa."); lock.unlock(); return }
                        let (md, e) = ChatPrep.video(url)
                        lock.lock(); if let md { out.append((i, md)) } else { why = e }; lock.unlock()
                    }
                } else if p.canLoadObject(ofClass: UIImage.self) {
                    p.loadObject(ofClass: UIImage.self) { obj, _ in
                        defer { group.leave() }
                        if let img = obj as? UIImage, let md = ChatFiles.keepPhoto(img) { lock.lock(); out.append((i, md)); lock.unlock() }
                        else { lock.lock(); why = t("That photo couldn’t be read.", "Picha hiyo haikuweza kusomwa."); lock.unlock() }
                    }
                } else { group.leave() }
            }
            group.notify(queue: .main) { self.done(out.sorted { $0.0 < $1.0 }.map { $0.1 }, why) }
        }
    }
}

/// Chooses a document (PDF and others, up to 10 MB).
struct ChatDocPicker: UIViewControllerRepresentable {
    let done: (ChatMedia?, String?) -> Void
    func makeCoordinator() -> Coord { Coord(done) }
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let c = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        c.delegate = context.coordinator; c.allowsMultipleSelection = false; return c
    }
    func updateUIViewController(_ vc: UIDocumentPickerViewController, context: Context) {}
    final class Coord: NSObject, UIDocumentPickerDelegate {
        let done: (ChatMedia?, String?) -> Void
        init(_ d: @escaping (ChatMedia?, String?) -> Void) { done = d }
        func documentPicker(_ c: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let u = urls.first else { done(nil, nil); return }
            let name = u.lastPathComponent
            let local = ChatFiles.newName(u.pathExtension.isEmpty ? "bin" : String(u.pathExtension.prefix(5)))
            let dst = ChatFiles.outDir.appendingPathComponent(local)
            do { try FileManager.default.copyItem(at: u, to: dst) } catch { done(nil, t("That file couldn’t be read.", "Faili hilo halikuweza kusomwa.")); return }
            let size = ((try? FileManager.default.attributesOfItem(atPath: dst.path))?[.size] as? NSNumber)?.intValue ?? 0
            if size > 10 * 1024 * 1024 { try? FileManager.default.removeItem(at: dst); done(nil, t("Documents can be up to 10 MB.", "Hati zinaweza kuwa hadi MB 10.")); return }
            let mime = UTType(filenameExtension: u.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            done(ChatMedia(id: "", type: "doc", name: name, size: size, local: "chat/out/" + local, mime: mime), nil)
        }
        func documentPickerWasCancelled(_ c: UIDocumentPickerViewController) { done(nil, nil) }
    }
}

/// Getting a picked video ready: up to a minute and 16 MB.
enum ChatPrep {
    static func video(_ url: URL) -> (ChatMedia?, String?) {
        let asset = AVURLAsset(url: url)
        let dur = CMTimeGetSeconds(asset.duration)
        if dur > 60.5 { return (nil, t("Videos can be up to 60 seconds. Trim it on your phone first.", "Video zinaweza kuwa hadi sekunde 60. Ifupishe kwenye simu yako kwanza.")) }
        let local = ChatFiles.newName("mp4")
        let dst = ChatFiles.outDir.appendingPathComponent(local)
        do { try FileManager.default.copyItem(at: url, to: dst) } catch { return (nil, t("That video couldn’t be read.", "Video hiyo haikuweza kusomwa.")) }
        let size = ((try? FileManager.default.attributesOfItem(atPath: dst.path))?[.size] as? NSNumber)?.intValue ?? 0
        if size > 16 * 1024 * 1024 { try? FileManager.default.removeItem(at: dst); return (nil, t("That video is too big to send (over 16 MB). Try a shorter one.", "Video hiyo ni kubwa mno kutuma (zaidi ya MB 16). Jaribu fupi zaidi.")) }
        var md = ChatMedia(id: "", type: "video", dur: dur.isFinite ? dur : 0, name: "video.mp4", size: size, local: "chat/out/" + local, mime: "video/mp4")
        // a poster picture from the first second, so the bubble shows something before it plays
        let gen = AVAssetImageGenerator(asset: asset); gen.appliesPreferredTrackTransform = true
        if let cg = try? gen.copyCGImage(at: CMTime(seconds: min(1, max(0, dur / 2)), preferredTimescale: 600), actualTime: nil), let p = ChatFiles.keepPhoto(UIImage(cgImage: cg)) {
            md.w = p.w; md.h = p.h; md.thumb = p.thumb
        }
        return (md, nil)
    }
}
