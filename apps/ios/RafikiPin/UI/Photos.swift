import AVFoundation
import PhotosUI
import SwiftUI
import UIKit

/// A photo kept on the phone until sent. `label` is its English name in monday.com.
struct Shot: Equatable { var path: String; var label: String }

/// A suggested photo: English label (sent to monday.com), what it says on screen, and a hint.
struct Slot { var label: String; var title: String; var hint: String }

/// Takes a photo with the camera.
struct CameraPicker: UIViewControllerRepresentable {
    let done: (UIImage?) -> Void
    func makeCoordinator() -> Coord { Coord(done) }
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let c = UIImagePickerController()
        c.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera) ? .camera : .photoLibrary
        c.delegate = context.coordinator
        return c
    }
    func updateUIViewController(_ vc: UIImagePickerController, context: Context) {}
    final class Coord: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let done: (UIImage?) -> Void
        init(_ d: @escaping (UIImage?) -> Void) { done = d }
        func imagePickerController(_ p: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) { done(info[.originalImage] as? UIImage) }
        func imagePickerControllerDidCancel(_ p: UIImagePickerController) { done(nil) }
    }
}

/// Chooses a photo from the phone's photos.
struct GalleryPicker: UIViewControllerRepresentable {
    let done: (UIImage?) -> Void
    func makeCoordinator() -> Coord { Coord(done) }
    func makeUIViewController(context: Context) -> PHPickerViewController {
        var cfg = PHPickerConfiguration(); cfg.filter = .images; cfg.selectionLimit = 1
        let c = PHPickerViewController(configuration: cfg); c.delegate = context.coordinator; return c
    }
    func updateUIViewController(_ vc: PHPickerViewController, context: Context) {}
    final class Coord: NSObject, PHPickerViewControllerDelegate {
        let done: (UIImage?) -> Void
        init(_ d: @escaping (UIImage?) -> Void) { done = d }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let p = results.first?.itemProvider, p.canLoadObject(ofClass: UIImage.self) else { done(nil); return }
            p.loadObject(ofClass: UIImage.self) { obj, _ in DispatchQueue.main.async { self.done(obj as? UIImage) } }
        }
    }
}

/// Photo boxes, one per suggested shot. Each box lets you take the photo or choose one from your photos.
/// Photos are shrunk and kept on the phone until they have been sent.
struct PhotoSlots: View {
    @EnvironmentObject var m: AppModel
    let slots: [Slot]
    @Binding var shots: [Shot]
    var max = 7
    @State private var askFor: String?
    @State private var camFor: String?
    @State private var galFor: String?
    @State private var msg: String?

    func add(_ img: UIImage?, _ label: String) {
        guard let img, let path = m.keepPhoto(img) else { return }
        shots = shots.filter { $0.label != label || label == "Other photo" } + [Shot(path: path, label: label)]
    }

    func camera(_ label: String) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .denied, .restricted: msg = t("Allow the camera in Settings to take photos, or choose from your photos.", "Ruhusu kamera kwenye Settings ili kupiga picha, au chagua kutoka kwenye picha zako.")
        default: camFor = label
        }
    }

    var body: some View {
        let extra = shots.filter { s in !slots.contains { $0.label == s.label } }.map { (Slot(label: $0.label, title: t("Other photo", "Picha nyingine"), hint: ""), $0 as Shot?) }
        let all: [(Slot, Shot?)] = slots.map { s in (s, shots.last { $0.label == s.label }) } + extra
        VStack(alignment: .leading, spacing: 10) {
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                ForEach(Array(all.enumerated()), id: \.offset) { _, pair in box(pair.0, pair.1) }
            }
            if shots.count < max { Btn(t("Add a photo", "Ongeza picha"), icon: "camera", light: true, small: true) { askFor = "Other photo" } }
            Msg(msg, "warn")
        }
        .confirmationDialog(t("Add a photo", "Ongeza picha"), isPresented: Binding(get: { askFor != nil }, set: { if !$0 { askFor = nil } }), titleVisibility: .hidden) {
            let l = askFor ?? "Other photo"
            Button(t("Take a photo", "Piga picha")) { askFor = nil; camera(l) }
            Button(t("Choose from your photos", "Chagua kutoka kwenye picha")) { askFor = nil; galFor = l }
            Button(t("Cancel", "Ghairi"), role: .cancel) { askFor = nil }
        }
        .fullScreenCover(isPresented: Binding(get: { camFor != nil }, set: { if !$0 { camFor = nil } })) {
            let l = camFor ?? "Other photo"
            CameraPicker { img in add(img, l); camFor = nil }.ignoresSafeArea()
        }
        .sheet(isPresented: Binding(get: { galFor != nil }, set: { if !$0 { galFor = nil } })) {
            let l = galFor ?? "Other photo"
            GalleryPicker { img in add(img, l); galFor = nil }
        }
    }

    func box(_ slot: Slot, _ shot: Shot?) -> some View {
        Button { askFor = slot.label } label: {
            VStack(spacing: 6) {
                if let shot, let img = UIImage(contentsOfFile: Outbox.resolve(shot.path).path) {
                    Image(uiImage: img).resizable().scaledToFill().frame(height: 90).frame(maxWidth: .infinity).clipped().clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    Text(slot.title).font(.lSmall).foregroundColor(C.ink).lineLimit(1)
                } else {
                    IconTile(icon: "camera", tint: C.blue, bg: C.tint, size: 44)
                    Text(slot.title).font(.lMedium).foregroundColor(C.ink).multilineTextAlignment(.center)
                    if !slot.hint.isEmpty { Text(slot.hint).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.center).lineLimit(2) }
                }
            }
            .padding(8).frame(maxWidth: .infinity).frame(height: 132).background(C.card)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(shot != nil ? C.ok : C.edge, lineWidth: shot != nil ? 2 : 1.5))
            .overlay(alignment: .topTrailing) {
                if let shot {
                    Button { shots.removeAll { $0 == shot } } label: { Icon("x", size: 16, color: .white).frame(width: 32, height: 32).background(C.ink).clipShape(Circle()) }
                        .padding(4).accessibilityLabel(t("Remove photo", "Ondoa picha"))
                }
            }
        }.buttonStyle(.plain)
    }
}

/// A short voice note (up to 2 minutes), kept on the phone until the visit is sent.
final class VoiceRec: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published var recording = false
    @Published var playing = false
    @Published var secs = 0
    private var rec: AVAudioRecorder?
    private var player: AVAudioPlayer?
    private var timer: Timer?
    private var path = ""

    func start(_ done: @escaping (String?) -> Void) {
        AVAudioSession.sharedInstance().requestRecordPermission { ok in
            DispatchQueue.main.async {
                guard ok else { done(nil); return }
                do {
                    let s = AVAudioSession.sharedInstance()
                    try s.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
                    try s.setActive(true)
                    let name = "v\(Int(Date().timeIntervalSince1970 * 1000)).m4a"
                    self.path = "photos/" + name
                    let r = try AVAudioRecorder(url: AppModel.mediaDir.appendingPathComponent(name), settings: [
                        AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 64000])
                    r.record(forDuration: 120)
                    self.rec = r; self.recording = true; self.secs = 0
                    self.timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
                        guard let self else { return }
                        self.secs += 1
                        if self.rec?.isRecording != true { self.stop(done) }
                    }
                } catch { done(nil) }
            }
        }
    }
    func stop(_ done: (String?) -> Void) {
        timer?.invalidate(); timer = nil
        guard let r = rec else { return }
        let len = r.currentTime
        r.stop(); rec = nil; recording = false
        done(secs >= 1 || len >= 1 ? path : nil)
    }
    func play(_ p: String) {
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        player = try? AVAudioPlayer(contentsOf: Outbox.resolve(p)); player?.delegate = self
        playing = player?.play() == true
    }
    func audioPlayerDidFinishPlaying(_ p: AVAudioPlayer, successfully flag: Bool) { DispatchQueue.main.async { self.playing = false } }
}

struct VoiceNote: View {
    @Binding var path: String?
    @StateObject private var v = VoiceRec()
    @State private var msg: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(t("Voice note (optional)", "Ujumbe wa sauti (si lazima)")).font(.tSmall).foregroundColor(C.ink).padding(.top, 16)
            HStack(spacing: 8) {
                if v.recording {
                    Btn(t("Stop", "Simamisha") + String(format: "  %d:%02d", v.secs / 60, v.secs % 60), icon: "x", small: true) { v.stop { p in finish(p) } }
                } else if let p = path {
                    Btn(v.playing ? t("Playing…", "Inacheza…") : t("Play", "Cheza"), icon: "arrow", light: true, small: true) { v.play(p) }
                    Btn(t("Remove", "Ondoa"), icon: "x", light: true, small: true) { try? FileManager.default.removeItem(at: Outbox.resolve(p)); path = nil }
                } else {
                    Btn(t("Record a voice note", "Rekodi ujumbe wa sauti"), icon: "mic", light: true, small: true) {
                        msg = nil
                        v.start { p in if let p { path = p } else { msg = t("Allow the microphone to record a voice note.", "Ruhusu kipaza sauti ili kurekodi ujumbe wa sauti.") } }
                    }
                }
            }
            Msg(msg, "warn")
        }
    }
    func finish(_ p: String?) {
        if let p { path = p } else if !v.recording { msg = msg ?? t("Too short. Hold on a little longer.", "Fupi mno. Endelea kidogo zaidi.") }
    }
}
