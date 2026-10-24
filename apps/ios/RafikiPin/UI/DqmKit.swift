import SwiftUI
import UIKit

// The calm Office look shared by Community, Donors, QR codes, Team map, Plan a week and Clean-up (Android's DqmKit.kt):
// a head with an icon, one line and a few numbers; filter chips with counts; tappable cards that open a sheet with
// every action; set-up and "how it works" folded into rows that open with a tap.

func dqmTone(_ tone: String) -> (Color, Color) {
    switch tone { case "bad": return (C.bad, C.badBg); case "warn": return (C.warn, C.warnBg); case "ok": return (C.ok, C.okBg); case "has": return (C.blue2, C.tint); default: return (C.muted, C.bg) }
}

/// A number tile in a head: with a key it is tappable (it sets the filter).
struct DqmNum { var n: Int; var label: String; var tone = ""; var key: String? = nil }

struct DqmHead<Trailing: View, Actions: View>: View {
    let icon: String; let hue: String; let title: String; let line: String
    var nums: [DqmNum] = []; var picked: String? = nil; var onNum: (String) -> Void = { _ in }
    @ViewBuilder var trailing: () -> Trailing
    @ViewBuilder var actions: () -> Actions
    var body: some View {
        CardV(pad: 16) {
            HStack(spacing: 12) {
                IconTile(icon: icon, tint: C.named(hue).fg, bg: C.named(hue).bg, size: 44)
                VStack(alignment: .leading, spacing: 1) { Text(title).font(.tMedium).foregroundColor(C.ink); Text(line).font(.bSmall).foregroundColor(C.muted) }
                Spacer(minLength: 0)
                trailing()
            }
            if !nums.isEmpty {
                HStack(spacing: 8) {
                    ForEach(Array(nums.enumerated()), id: \.offset) { _, x in
                        let col: Color = x.tone == "bad" ? C.bad : x.tone == "ok" ? C.ok : x.tone == "has" ? C.blue2 : x.tone == "off" ? C.muted : C.ink
                        let on = x.key != nil && x.key == picked
                        let tile = VStack(alignment: .leading, spacing: 1) {
                            Text("\(x.n)").font(.mont(22, .heavy)).foregroundColor(col)
                            Text(x.label).font(.mont(12)).foregroundColor(C.muted).lineLimit(3).multilineTextAlignment(.leading)
                            Spacer(minLength: 0)
                        }.padding(.horizontal, 10).padding(.vertical, 9).frame(maxWidth: .infinity, minHeight: 44, alignment: .topLeading)
                        .background(C.bg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(on ? C.blue : C.line, lineWidth: on ? 2 : 1))
                        if let k = x.key { Button { Haptic.tap(); onNum(k) } label: { tile }.buttonStyle(Press()) } else { tile }
                    }
                }.fixedSize(horizontal: false, vertical: true).padding(.top, 14)
            }
            actions()
        }
    }
}
extension DqmHead where Trailing == EmptyView, Actions == EmptyView {
    init(icon: String, hue: String, title: String, line: String, nums: [DqmNum] = [], picked: String? = nil, onNum: @escaping (String) -> Void = { _ in }) {
        self.init(icon: icon, hue: hue, title: title, line: line, nums: nums, picked: picked, onNum: onNum, trailing: { EmptyView() }, actions: { EmptyView() })
    }
}
extension DqmHead where Trailing == EmptyView {
    init(icon: String, hue: String, title: String, line: String, nums: [DqmNum] = [], picked: String? = nil, onNum: @escaping (String) -> Void = { _ in }, @ViewBuilder actions: @escaping () -> Actions) {
        self.init(icon: icon, hue: hue, title: title, line: line, nums: nums, picked: picked, onNum: onNum, trailing: { EmptyView() }, actions: actions)
    }
}

/// Filter chips with counts, in a row that scrolls sideways.
struct DqmChips: View {
    let items: [(String, String, Int)]; let picked: String; let pick: (String) -> Void
    var body: some View {
        ScrollViewReader { sv in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) { ForEach(items, id: \.0) { k, l, n in FilterPill(label: "\(l) · \(n)", on: picked == k) { Haptic.tap(); pick(k) }.id(k) } }.padding(.vertical, 2)
            }.onChange(of: picked) { k in withAnimation { sv.scrollTo(k, anchor: .center) } }
        }.padding(.top, 14).padding(.bottom, 4)
    }
}

struct DqmPill: View {
    let tone: String; let label: String
    var body: some View {
        let (fg, bg) = dqmTone(tone)
        HStack(spacing: 6) { Circle().fill(fg).frame(width: 7, height: 7); Text(label).font(.mont(12, .heavy)).foregroundColor(fg) }
            .padding(.leading, 8).padding(.trailing, 10).padding(.vertical, 3).background(bg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

struct DqmAvatar: View {
    let name: String; let bg: Color; let fg: Color; var size: CGFloat = 44; var ring = false
    var body: some View {
        Text(initialsOf(name)).font(.mont(size * 0.32, .heavy)).foregroundColor(fg).frame(width: ring ? size - 6 : size, height: ring ? size - 6 : size).background(Circle().fill(bg))
            .padding(ring ? 3 : 0).background(Circle().fill(ring ? Color.white : .clear))
    }
}

/// A calm card: something on the left, one title, a status, at most two lines, a time and a chevron.
struct DqmCard<Lead: View>: View {
    let title: String; var pill: (String, String)? = nil; var lines: [String] = []; var time = ""; var dim = false
    @ViewBuilder var lead: () -> Lead
    let tap: () -> Void
    var body: some View {
        Button { Haptic.tap(); tap() } label: {
            HStack(spacing: 12) {
                lead()
                VStack(alignment: .leading, spacing: 0) {
                    Text(title).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading)
                    if let pill { DqmPill(tone: pill.0, label: pill.1).padding(.top, 4) }
                    ForEach(Array(lines.filter { !$0.isEmpty }.prefix(2).enumerated()), id: \.offset) { _, l in Text(l).font(.bSmall).foregroundColor(C.muted).lineLimit(2).multilineTextAlignment(.leading).padding(.top, 3) }
                }
                Spacer(minLength: 8)
                if !time.isEmpty { Text(time).font(.bSmall).foregroundColor(C.muted).lineLimit(1).fixedSize() }
                Icon("chev", size: 18, color: C.muted)
            }
            .padding(.horizontal, 14).padding(.vertical, 12).opacity(dim ? 0.72 : 1).frame(maxWidth: .infinity, alignment: .leading)
            .background(C.card).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.line, lineWidth: 1))
        }.buttonStyle(Press())
    }
}

/// A row that opens and closes with a tap; the summary shows what's inside without opening it.
struct DqmFold<Content: View>: View {
    let icon: String; let hue: String; let title: String; let summary: String; let tone: String
    var startOpen = false
    @ViewBuilder var content: () -> Content
    @State private var open: Bool?
    var body: some View {
        let isOpen = open ?? startOpen
        let (fg, _) = dqmTone(tone), hu = C.named(hue)
        VStack(alignment: .leading, spacing: 0) {
            Button { Haptic.tap(); withAnimation(Motion.on ? .easeOut(duration: 0.22) : nil) { open = !isOpen } } label: {
                HStack(spacing: 12) {
                    IconTile(icon: icon, tint: hu.fg, bg: hu.bg, size: 38)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading)
                        HStack(alignment: .top, spacing: 6) { Circle().fill(tone == "off" ? C.muted : fg).frame(width: 7, height: 7).padding(.top, 5); Text(summary).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading) }
                    }
                    Spacer(minLength: 6)
                    Icon("chev", size: 18, color: C.muted).rotationEffect(.degrees(isOpen ? 90 : 0))
                }.padding(.horizontal, 14).padding(.vertical, 8).frame(minHeight: 60).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if isOpen { VStack(alignment: .leading, spacing: 0) { content() }.padding(.horizontal, 14).padding(.bottom, 14).transition(.opacity) }
        }
    }
}

/// The top of a sheet: a picture, the title, a line or two, a status, and a close X.
struct DqmSheetHead<Lead: View>: View {
    let title: String; var sub: [String] = []; var pill: (String, String)? = nil; let close: () -> Void
    @ViewBuilder var lead: () -> Lead
    var body: some View {
        ZStack(alignment: .topTrailing) {
            VStack(spacing: 2) {
                lead().rise(0)
                Text(title).font(.tLarge).foregroundColor(C.ink).multilineTextAlignment(.center).padding(.top, 8)
                ForEach(Array(sub.filter { !$0.isEmpty }.enumerated()), id: \.offset) { _, s in Text(s).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center) }
                if let pill { DqmPill(tone: pill.0, label: pill.1).padding(.top, 8) }
            }.frame(maxWidth: .infinity).padding(.horizontal, 44)
            Button(action: close) { Icon("x", size: 20, color: C.ink).frame(width: 44, height: 44).background(Circle().fill(C.bg)) }.buttonStyle(.plain).accessibilityLabel(t("Close", "Funga"))
        }
    }
}

struct DqmFact: View {
    let label: String; let value: String; var sub = ""
    var body: some View { OcvFact(label: label, value: value, sub: sub) }
}

/// A plain bordered button for the less common actions at the bottom of a sheet (red for the ones that remove things).
struct DqmLineBtn: View {
    let text: String; let icon: String; var red = false; var enabled = true; let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) { Icon(icon, size: 18, color: red ? C.bad : C.blue); Text(text).font(.lLarge).foregroundColor(red ? C.bad : C.ink).multilineTextAlignment(.center) }
                .padding(.horizontal, 12).padding(.vertical, 6).frame(maxWidth: .infinity, minHeight: 48)
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line, lineWidth: 1)).contentShape(Rectangle())
        }.buttonStyle(Press()).disabled(!enabled).opacity(enabled ? 1 : 0.5)
    }
}

/// Ask before something that can't be undone: the question, Cancel and the action.
struct DqmAsk: View {
    let question: String; let yes: String; var red = false; var busy = false; var msg: String? = nil; let no: () -> Void; let go: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(question).font(.mont(14, .semibold)).foregroundColor(C.ink)
            HStack(spacing: 8) {
                Btn(t("Cancel", "Ghairi"), light: true, small: true, wide: true, action: no)
                Button(action: go) {
                    Text(yes).font(.mont(14, .bold)).foregroundColor(.white).multilineTextAlignment(.center).padding(.horizontal, 16).frame(maxWidth: .infinity, minHeight: 44)
                        .background(Group { if busy { C.off } else if red { C.bad } else { BlueBrush } }).clipShape(Capsule())
                }.buttonStyle(Press()).disabled(busy)
            }
            Msg(msg, "bad")
        }.padding(14).frame(maxWidth: .infinity, alignment: .leading).background(red ? C.badBg : C.named("slate").bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 12)
    }
}
