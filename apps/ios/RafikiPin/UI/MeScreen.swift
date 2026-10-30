import SwiftUI

/// Me & team (Android 2.14–2.28): the title in the middle and Me | Team under it (the bell is in the app header, top
/// right). Me: the profile card, an update when there is one, what's waiting to send, my work, my month, my stamps,
/// Settings and App updates. Team: who's working today, the team's totals and the feed.
struct MeScreen: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var up = MeUpdates.shared
    @ObservedObject private var contact = Contact.shared
    @State private var pane = "me"
    var body: some View {
        ScrollViewReader { sv in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text(t("Me & team", "Mimi na timu")).font(.hLarge).foregroundColor(C.ink).multilineTextAlignment(.center).frame(maxWidth: .infinity).id("top")
                    SmallSeg(items: [("me", t("Me", "Mimi")), ("team", t("Team", "Timu"))], sel: pane) { p in
                        pane = p; m.prefs.mePane = p; sv.scrollTo("top", anchor: .top)
                    }.frame(maxWidth: .infinity).padding(.top, 10)
                    if pane == "team" {
                        TeamToday(mates: m.team, act: m.activity, snap: m.snap)
                        TeamMonth(act: m.activity)
                        TeamFeed(act: m.activity, snap: m.snap)
                    } else {
                        MeHero(act: m.activity)
                        MeUpdateCard()
                        WaitingList(snap: m.snap)
                        if let snap = m.snap { MyWork(snap: snap) }
                        MyTrips()
                        MyMonth(act: m.activity)
                        BadgeStamps(act: m.activity)
                        SettingsCard()
                        MeUpdateRow()
                    }
                }
                .padding(16).padding(.bottom, 24)
                .meCentred()
            }
            .onChange(of: up.focus) { f in if f { showUpdate(sv) } }
            .onAppear { if up.focus { showUpdate(sv) } }
        }
        .onAppear { pane = up.focus ? "me" : m.prefs.mePane }
        .task { await m.loadActivity(); await m.loadPeople() }
        .onChange(of: contact.changed) { _ in Task { await m.loadPeople() } }
        .refreshable { await m.refreshNow(); await m.loadActivity(); await m.loadPeople() }
    }
    private func showUpdate(_ sv: ScrollViewProxy) {
        pane = "me"; m.prefs.mePane = "me"
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 250_000_000)
            withAnimation(Motion.on ? Animation.easeInOut(duration: 0.35) : nil) { sv.scrollTo("app_update", anchor: .center) }
        }
    }
}
