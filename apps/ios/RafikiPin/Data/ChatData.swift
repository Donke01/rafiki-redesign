import Foundation
import Network
import UIKit

// RAFIKI Pin Chat (Android 2.23 feat-chata, 2.24 chatpa, 2.27 chat face lift): the shapes of the chat contract, read
// leniently so a missing or extra field never breaks a screen, plus the offline outbox and the copy kept on the phone.
// Same server as Android and the web app: /api/chat/* with the app's PIN or monday.com sign-in.

/// A text field: "" for missing, null or "null".
func chatS(_ o: JSON, _ k: String) -> String { let s = o.str(k); return s == "null" ? "" : s }
func chatStrings(_ v: Any?) -> [String] {
    ((v as? [Any]) ?? []).compactMap { x -> String? in
        let s: String = (x as? String) ?? ((x as? NSNumber)?.stringValue ?? "")
        return s.trimmingCharacters(in: .whitespaces).isEmpty || s == "null" ? nil : s
    }
}

/// A moment from the server: ISO text or epoch milliseconds (seconds are read too). 0 when unknown. Milliseconds.
func chatMs(_ v: Any?) -> Double {
    switch v {
    case let n as NSNumber:
        let x = n.doubleValue
        return x >= 1 && x <= 99_999_999_999 ? x * 1000 : x
    case let s as String:
        let t0 = s.trimmingCharacters(in: .whitespaces)
        if let d = Double(t0) { return chatMs(NSNumber(value: d)) }
        if let d = When.parse(t0) { return d.timeIntervalSince1970 * 1000 }
        return 0
    default: return 0
    }
}
func chatIso(_ ms: Double) -> String { When.iso.string(from: Date(timeIntervalSince1970: ms / 1000)) }
func chatNow() -> Double { Date().timeIntervalSince1970 * 1000 }

/// Someone online now, or when they were last seen.
struct ChatPresence: Equatable { var online: Bool; var seen: Double }

/// A chat row's last message, for the preview.
struct ChatLast: Equatable {
    var id: String; var from: String; var kind: String; var text: String; var at: Double; var dur: Double = 0; var sys: String = ""
    func json() -> JSON { ["id": id, "from": from, "kind": kind, "text": text, "at": chatIso(at), "dur": dur, "sys": sys] }
    static func from(_ o: JSON?) -> ChatLast? {
        guard let o else { return nil }
        var sys = ""
        if let so = o["sys"] as? JSON { sys = chatS(so, "type") } else { sys = chatS(o, "sys") }
        let k = chatS(o, "kind")
        return ChatLast(id: chatS(o, "id"), from: chatS(o, "from"), kind: k.isEmpty ? "text" : k, text: chatS(o, "text"), at: chatMs(o["at"]), dur: o.num("dur") ?? 0, sys: sys)
    }
}

/// A pinned message in a chat (the slim bar at the top). The server may send only ids.
struct ChatPin: Equatable {
    var id: String; var from = ""; var kind = "text"; var text = ""; var by = ""; var at: Double = 0; var pinnedAt: Double = 0
    func json() -> JSON { ["id": id, "from": from, "kind": kind, "text": text, "by": by, "at": at, "pinnedAt": pinnedAt] }
    static func list(_ v: Any?) -> [ChatPin] {
        guard let a = v as? [Any] else { return [] }
        let all: [ChatPin] = a.compactMap { x -> ChatPin? in
            if let o = x as? JSON {
                let k = chatS(o, "kind")
                return ChatPin(id: chatS(o, "id"), from: chatS(o, "from"), kind: k.isEmpty ? "text" : k, text: chatS(o, "text"), by: chatS(o, "by"), at: chatMs(o["at"]), pinnedAt: chatMs(o["pinnedAt"]))
            }
            if let s = x as? String, !s.isEmpty, s != "null" { return ChatPin(id: s) }
            if let n = x as? NSNumber { return ChatPin(id: n.stringValue) }
            return nil
        }
        return Array(all.filter { !$0.id.isEmpty }.prefix(3))
    }
}

/// One conversation in the list: team (per country, and All RAFIKI), direct message, group or project thread.
struct ChatRow: Identifiable, Equatable {
    static let always = Double.infinity
    var id: String; var kind: String; var title: String; var photo = ""; var project = ""
    var members: [String] = []; var admins: [String] = []; var last: ChatLast? = nil
    var unread = 0; var mention = false; var pinned = false
    /// 0 = not muted, `always`, otherwise muted until this moment (ms).
    var mutedUntil: Double = 0; var archived = false; var onlyAdmins = false
    var description = ""; var wallpaper = ""
    /// My bubble colour here, the pinned messages, who has read the last message, my oldest unseen @mention, Announcements, country, project photo.
    var bubble = ""; var pins: [ChatPin] = []; var seen: [String] = []; var mentionId = ""
    var announceFlag = false; var countryCode = ""; var pphoto = ""

    func muted(_ now: Double = chatNow()) -> Bool { mutedUntil == ChatRow.always || mutedUntil > now }
    var isGroup: Bool { kind != "dm" }
    var announce: Bool { announceFlag || id == "t:news" }
    /// A team chat's country ("ke"), '' otherwise.
    var country: String {
        if !countryCode.isEmpty { return countryCode }
        guard kind == "team" else { return "" }
        let tail = (id.split(separator: ":").last.map(String.init) ?? "").split(separator: "_").last.map(String.init) ?? ""
        return tail.count == 2 ? tail.lowercased() : ""
    }
    /// The other person in a direct message.
    func other(_ me: String) -> String { members.first { !chatSame($0, me) } ?? title }
    func admin(_ name: String) -> Bool { admins.contains { chatSame($0, name) } }

    func json() -> JSON {
        var o: JSON = ["id": id, "kind": kind, "title": title, "photo": photo, "project": project, "members": members, "admins": admins,
                       "unread": unread, "mention": mention, "pinned": pinned, "archived": archived, "onlyAdmins": onlyAdmins,
                       "description": description, "wallpaper": wallpaper, "bubble": bubble, "pins": pins.map { $0.json() }, "seen": seen,
                       "mentionId": mentionId, "announce": announceFlag, "country": countryCode, "pphoto": pphoto]
        o["last"] = last?.json() ?? NSNull()
        o["muted"] = mutedUntil == ChatRow.always ? "always" as Any : mutedUntil as Any
        return o
    }
    static func mutedFrom(_ v: Any?) -> Double {
        if v == nil || v is NSNull { return 0 }
        if let b = v as? Bool { return b ? always : 0 }
        if let s = v as? String, s == "always" { return always }
        if let s = v as? String, s == "true" { return always }
        return chatMs(v)
    }
    static func from(_ o: JSON) -> ChatRow {
        let k = chatS(o, "kind")
        let mid = chatS(o, "mentionId")
        return ChatRow(id: chatS(o, "id"), kind: k.isEmpty ? "group" : k, title: chatS(o, "title"), photo: chatS(o, "photo"), project: chatS(o, "project"),
                       members: chatStrings(o["members"]), admins: chatStrings(o["admins"]), last: ChatLast.from(o.obj("last")),
                       unread: o.int("unread"), mention: o.bool("mention"), pinned: o.bool("pinned"), mutedUntil: mutedFrom(o["muted"]),
                       archived: o.bool("archived"), onlyAdmins: o.bool("onlyAdmins"), description: chatS(o, "description"), wallpaper: chatS(o, "wallpaper"),
                       bubble: chatS(o, "bubble"), pins: ChatPin.list(o["pins"]), seen: chatStrings(o["seen"]), mentionId: mid == "0" ? "" : mid,
                       announceFlag: o.bool("announce"), countryCode: chatS(o, "country").lowercased(), pphoto: chatS(o, "pphoto"))
    }
}

func chatSame(_ a: String, _ b: String) -> Bool { a.caseInsensitiveCompare(b) == .orderedSame }

/// A photo, video, voice note or document on a message. `local` is the file on this phone (relative to the app's data folder).
struct ChatMedia: Equatable {
    var id: String; var type: String; var w = 0; var h = 0; var dur: Double = 0; var wave: [Int] = []
    var name = ""; var size = 0; var local = ""; var poster = ""; var mime = ""
    /// The server's signed address (relative, works for about a day without headers).
    var url = ""
    /// A small preview for the data saver: a local file before upload, then its media id; and its signed address.
    var thumb = ""; var thumbUrl = ""

    func json(local withLocal: Bool = false) -> JSON {
        var o: JSON = ["id": id, "type": type, "w": w, "h": h, "dur": dur, "wave": wave, "name": name, "size": size]
        if !poster.isEmpty { o["poster"] = poster }
        if !mime.isEmpty { o["mime"] = mime }
        if withLocal && !local.isEmpty { o["local"] = local }
        if !thumb.isEmpty && (withLocal || !thumb.hasPrefix("chat/")) { o["thumb"] = thumb }
        if withLocal && !thumbUrl.isEmpty { o["thumbUrl"] = thumbUrl }
        if withLocal && !url.isEmpty { o["url"] = url }
        return o
    }
    static func waveFrom(_ v: Any?) -> [Int] {
        if let a = v as? [Any] { return a.map { x in min(100, max(0, (x as? NSNumber)?.intValue ?? 0)) } }
        if let s = v as? String { return s.split(separator: ",").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }.map { min(100, max(0, $0)) } }
        return []
    }
    static func from(_ o: JSON) -> ChatMedia {
        let ty = chatS(o, "type")
        return ChatMedia(id: chatS(o, "id"), type: ty.isEmpty ? "photo" : ty, w: o.int("w"), h: o.int("h"), dur: o.num("dur") ?? 0, wave: waveFrom(o["wave"]),
                         name: chatS(o, "name"), size: o.int("size"), local: chatS(o, "local"), poster: chatS(o, "poster"), mime: chatS(o, "mime"),
                         url: chatS(o, "url"), thumb: chatS(o, "thumb"), thumbUrl: chatS(o, "thumbUrl"))
    }
}

struct ChatQuote: Equatable {
    var id: String; var from: String; var kind: String; var text: String
    func json() -> JSON { ["id": id, "from": from, "kind": kind, "text": text] }
    static func from(_ o: JSON?) -> ChatQuote? {
        guard let o else { return nil }
        let k = chatS(o, "kind")
        return ChatQuote(id: chatS(o, "id"), from: chatS(o, "from"), kind: k.isEmpty ? "text" : k, text: chatS(o, "text"))
    }
}

/// A message the app wrote into a project thread: repair reported, assigned, step changed, fixed; an SMS report; group changes.
struct ChatSys: Equatable {
    var type: String; var project = ""; var repair = ""; var step = ""; var by = ""; var who: [String] = []; var to = ""; var state = ""
    func json() -> JSON { ["type": type, "project": project, "repair": repair, "step": step, "by": by, "who": who, "to": to, "state": state] }
    static func from(_ o: JSON?) -> ChatSys? {
        guard let o else { return nil }
        return ChatSys(type: chatS(o, "type"), project: chatS(o, "project"), repair: chatS(o, "repair"), step: chatS(o, "step"), by: chatS(o, "by"),
                       who: chatStrings(o["who"]), to: chatS(o, "to"), state: chatS(o, "state"))
    }
}

struct ChatLive: Equatable {
    var until: Double; var lat: Double?; var lng: Double?; var at: Double
    func active(_ now: Double = chatNow()) -> Bool { until > now }
    func json() -> JSON { ["until": until, "lat": lat.map { $0 as Any } ?? NSNull(), "lng": lng.map { $0 as Any } ?? NSNull(), "at": at > 0 ? at as Any : NSNull()] }
    static func from(_ o: JSON?) -> ChatLive? {
        guard let o else { return nil }
        return ChatLive(until: chatMs(o["until"]), lat: o.num("lat"), lng: o.num("lng"), at: chatMs(o["at"]))
    }
}

/// A trip shared into a chat (kind 'trip'): what /api/itin/<id>?card=1 answers. Someone who may not see the trip gets the title only.
struct ChatTripCard: Equatable {
    var id: String; var title: String; var dates = ""; var from = ""; var to = ""; var next = ""; var done = 0; var total = 0; var limited = false
    var status = ""; var nextDate = ""; var nextAt = ""; var gone = false
    var progress: Double { total > 0 ? min(1, max(0, Double(done) / Double(total))) : 0 }
    func json() -> JSON {
        var o: JSON = ["id": id, "title": title, "from": from, "to": to, "status": status, "done": done, "total": total, "access": !limited]
        o["next"] = next.isEmpty ? NSNull() as Any : (["name": next, "date": nextDate, "at": nextAt.isEmpty ? NSNull() as Any : nextAt as Any] as JSON) as Any
        if !dates.isEmpty { o["dates"] = dates }
        return o
    }
    static func from(_ o: JSON?) -> ChatTripCard? {
        guard let o else { return nil }
        var id = chatS(o, "id"); if id.isEmpty { id = chatS(o, "itin") }
        if id.isEmpty && chatS(o, "title").isEmpty { return nil }
        var from = chatS(o, "from"), to = chatS(o, "to"), dates = ""
        if let d = o["dates"] as? JSON { from = chatS(d, "from"); to = chatS(d, "to") }
        else if let d = o["dates"] as? [Any], d.count >= 2 { from = (d[0] as? String) ?? ""; to = (d[1] as? String) ?? "" }
        else if let d = o["dates"] as? String, d != "null" { dates = d }
        var next = "", nextDate = "", nextAt = ""
        if let n = o["next"] as? JSON { next = chatS(n, "name").isEmpty ? chatS(n, "title") : chatS(n, "name"); nextDate = chatS(n, "date"); nextAt = chatS(n, "at") }
        else if let n = o["next"] as? String, n != "null" { next = n }
        let prog = o.obj("progress")
        let done = o["done"] != nil ? o.int("done") : (prog?.int("done") ?? 0)
        let total = o["total"] != nil ? o.int("total") : (prog?.int("total") ?? 0)
        let limited = (o["access"] != nil && !(o["access"] is NSNull) && !o.bool("access")) || o.bool("limited")
        var title = chatS(o, "title"); if title.isEmpty { title = chatS(o, "name") }
        return ChatTripCard(id: id, title: title, dates: dates, from: from, to: to, next: next, done: done, total: total, limited: limited,
                            status: chatS(o, "status"), nextDate: nextDate, nextAt: nextAt, gone: o.bool("gone"))
    }
}

/// One message. `status` is "sent", "delivered" or "read" from the server, "queued" while it waits on this phone (the clock),
/// "failed" when the server refused it (`failed` says why).
struct ChatMsg: Identifiable, Equatable {
    var id: String; var c: String; var cid = ""; var from: String; var at: Double; var kind = "text"; var text = ""
    var media: [ChatMedia] = []; var reply: ChatQuote? = nil; var fwd = false; var mentions: [String] = []
    var loc: LatLng? = nil; var live: ChatLive? = nil; var contact: String? = nil; var reacts: [String: [String]] = [:]
    var edited: Double = 0; var deleted = false; var sys: ChatSys? = nil; var status = "sent"; var failed: String? = nil; var starred = false
    var trip: ChatTripCard? = nil

    var queued: Bool { status == "queued" }
    /// The key the list uses: the client id while waiting, so the bubble doesn't jump when the server's id arrives.
    var key: String { cid.isEmpty ? id : cid }

    func json() -> JSON {
        var o: JSON = ["id": id, "c": c, "cid": cid, "from": from, "at": chatIso(at), "kind": kind, "text": text, "media": media.map { $0.json(local: true) },
                       "fwd": fwd, "mentions": mentions, "reacts": reacts, "deleted": deleted, "status": status, "starred": starred]
        o["reply"] = reply?.json() ?? NSNull()
        o["loc"] = loc.map { ["lat": $0.lat, "lng": $0.lng] as JSON as Any } ?? NSNull()
        o["live"] = live?.json() ?? NSNull()
        o["contact"] = contact.map { ["name": $0] as JSON as Any } ?? NSNull()
        o["edited"] = edited > 0 ? chatIso(edited) as Any : NSNull()
        o["sys"] = sys?.json() ?? NSNull()
        if let failed { o["failed"] = failed }
        if let trip { o["trip"] = trip.json() }
        return o
    }

    static func from(_ o: JSON, chat: String = "") -> ChatMsg {
        var loc: LatLng? = nil
        if let l = o.obj("loc"), let a = l.num("lat"), let b = l.num("lng") { loc = LatLng(lat: a, lng: b) }
        var reacts: [String: [String]] = [:]
        (o.obj("reacts") ?? [:]).forEach { k, v in let n = chatStrings(v); if !n.isEmpty { reacts[k] = n } }
        let fv = o["fwd"]
        let fwd: Bool = (fv as? Bool) ?? false || ((fv as? String).map { !$0.isEmpty && $0 != "false" && $0 != "null" } ?? false) || fv is JSON
        var contact: String? = nil
        if let co = o.obj("contact") { let n = chatS(co, "name"); contact = n.isEmpty ? nil : n }
        else if let s = o["contact"] as? String, !s.isEmpty, !s.hasPrefix("{"), s != "null" { contact = s }
        let k = chatS(o, "kind"), c0 = chatS(o, "c"), st = chatS(o, "status"), fl = chatS(o, "failed")
        return ChatMsg(id: chatS(o, "id"), c: c0.isEmpty ? chat : c0, cid: chatS(o, "cid"), from: chatS(o, "from"), at: chatMs(o["at"]), kind: k.isEmpty ? "text" : k,
                       text: chatS(o, "text"), media: o.objs("media").map(ChatMedia.from), reply: ChatQuote.from(o.obj("reply")), fwd: fwd,
                       mentions: chatStrings(o["mentions"]), loc: loc, live: ChatLive.from(o.obj("live")), contact: contact, reacts: reacts,
                       edited: chatMs(o["edited"]), deleted: o.bool("deleted"), sys: ChatSys.from(o.obj("sys")), status: st.isEmpty ? "sent" : st,
                       failed: fl.isEmpty ? nil : fl, starred: o.bool("starred"), trip: ChatTripCard.from(o.obj("trip")))
    }

    /// What it is, in a few words, for the chat list and notifications.
    func preview() -> String {
        let txt: String
        switch kind {
        case "contact": txt = contact ?? ""
        case "doc": txt = media.first?.name ?? text
        case "trip": txt = trip?.title ?? text
        default: txt = text
        }
        return chatPreview(kind, txt, dur: media.first?.dur ?? 0, deleted: deleted, count: media.count, mediaType: media.first?.type ?? "")
    }
}

/// "0:12", "1:05".
func chatClock(_ sec: Double) -> String { let s = max(0, Int(sec)); return String(format: "%d:%02d", s / 60, s % 60) }

/// "🎤 Voice message 0:12", "📷 Photo".
func chatPreview(_ kind: String, _ text: String, dur: Double = 0, deleted: Bool = false, count: Int = 1, mediaType: String = "") -> String {
    if deleted { return t("This message was deleted", "Ujumbe huu ulifutwa") }
    switch kind {
    case "voice": return "🎤 " + t("Voice message", "Ujumbe wa sauti") + (dur > 0 ? " " + chatClock(dur) : "")
    case "media":
        let head = mediaType == "video" ? "🎥 " + t("Video", "Video") : count > 1 ? "📷 " + t("\(count) photos", "Picha \(count)") : "📷 " + t("Photo", "Picha")
        return head + (text.isEmpty ? "" : ": " + text)
    case "location": return "📍 " + t("Location", "Mahali")
    case "live": return "📍 " + t("Live location", "Mahali moja kwa moja")
    case "contact": return "👤 " + (text.isEmpty ? t("Contact", "Mawasiliano") : text)
    case "doc": return "📄 " + (text.isEmpty ? t("Document", "Hati") : text)
    case "trip": return "🧭 " + t("Trip", "Safari") + (text.isEmpty ? "" : ": " + text)
    default: return text
    }
}

/// Why a chat call didn't work, in plain words.
func chatReason(_ reason: String?) -> String {
    switch reason {
    case "chat-off": return t("Chat isn’t switched on yet. The office can turn it on.", "Gumzo bado halijawashwa. Ofisi inaweza kuliwasha.")
    case "only-admins": return t("Only admins can send messages in this group.", "Wasimamizi pekee wanaweza kutuma ujumbe kwenye kikundi hiki.")
    case "not-member", "removed": return t("You’re no longer in this chat.", "Hauko tena kwenye gumzo hili.")
    case "edit-window", "too-late": return t("Messages can only be edited for 15 minutes.", "Ujumbe unaweza kuhaririwa kwa dakika 15 tu.")
    case "too-big", "too-large": return t("That file is too big to send.", "Faili hilo ni kubwa mno kutuma.")
    case "donor": return t("Chat is for the RAFIKI team only.", "Gumzo ni kwa timu ya RAFIKI pekee.")
    default: return reasonText(reason)
    }
}

/// System notes in a chat, in words.
func chatSysText(_ s: ChatSys?) -> String {
    guard let s else { return t("Update", "Habari") }
    let by = s.by.split(separator: " ").first.map(String.init) ?? s.by
    let who = by
    let list = s.who.joined(separator: ", ")
    switch s.type {
    case "repair": return who.isEmpty ? t("Repair reported", "Matengenezo yameripotiwa") : t("\(who) reported a repair", "\(who) ameripoti matengenezo")
    case "notworking": return t("A visit found it not working", "Ziara imekuta haifanyi kazi") + (who.isEmpty ? "" : " · \(who)")
    case "assigned":
        let to = (s.to.isEmpty ? s.by : s.to).split(separator: " ").first.map(String.init) ?? ""
        return to.isEmpty ? t("Repair assigned", "Matengenezo yamegawiwa") : t("Repair assigned to \(to)", "Matengenezo amepewa \(to)")
    case "step": return t("Repair step: ", "Hatua ya matengenezo: ") + s.step
    case "fixed": return who.isEmpty ? t("Pump fixed", "Pampu imetengenezwa") : t("\(who) fixed the pump", "\(who) ametengeneza pampu")
    case "sms": return t("Community report", "Ripoti ya jamii")
    case "pinned": return t("\(by) pinned a message", "\(by) amebandika ujumbe")
    case "created": return t("\(by) created the group", "\(by) ameunda kikundi")
    case "added": return t("\(by) added \(list)", "\(by) amemwongeza \(list)")
    case "removed": return t("\(by) removed \(list)", "\(by) amemwondoa \(list)")
    case "joined": let n = list.isEmpty ? by : list; return t("\(n) joined with the invite link", "\(n) amejiunga kwa kiungo cha mwaliko")
    case "left": let n = list.isEmpty ? by : list; return t("\(n) left", "\(n) ameondoka")
    default: return t("Update", "Habari")
    }
}

/// The title a chat shows (Announcements is "Matangazo" in Kiswahili).
func chatTitle(_ r: ChatRow, _ me: String) -> String { r.announce && Lang.sw ? "Matangazo" : (r.title.isEmpty ? r.other(me) : r.title) }

// MARK: - the server ---------------------------------------------------------------------------------------------------

/// The /api/chat/... endpoints (the same sign-in headers as the rest of the app).
extension Api {
    func chatList(since: Double = 0) async -> Reply { await get("/api/chat/list" + (since > 0 ? "?since=" + Api.enc(chatIso(since)) : "")) }
    func chatOpenDm(_ with: String) async -> Reply { await postTo("/api/chat/open", ["kind": "dm", "with": with]) }
    func chatOpenProject(_ pid: String) async -> Reply { await postTo("/api/chat/open", ["kind": "project", "project": pid]) }
    func chatGroup(_ title: String, _ members: [String], _ description: String) async -> Reply { await postTo("/api/chat/group", ["title": title, "members": members, "description": description]) }
    func chatGroupUpdate(_ c: String, _ f: JSON) async -> Reply { var b = f; b["c"] = c; return await postTo("/api/chat/group-update", b) }
    func chatMembers(_ c: String, add: [String] = [], remove: [String] = [], admin: [String] = [], unadmin: [String] = []) async -> Reply {
        var b: JSON = ["c": c]
        if !add.isEmpty { b["add"] = add }; if !remove.isEmpty { b["remove"] = remove }
        if !admin.isEmpty { b["admin"] = admin }; if !unadmin.isEmpty { b["unadmin"] = unadmin }
        return await postTo("/api/chat/members", b)
    }
    func chatLink(_ c: String, reset: Bool = false) async -> Reply { var b: JSON = ["c": c]; if reset { b["reset"] = true }; return await postTo("/api/chat/link", b) }
    func chatJoin(_ token: String) async -> Reply { await postTo("/api/chat/join", ["token": token]) }
    func chatLeave(_ c: String) async -> Reply { await postTo("/api/chat/leave", ["c": c]) }
    func chatMsgs(_ c: String, before: String? = nil, limit: Int = 50) async -> Reply {
        await get("/api/chat/msgs?c=" + Api.enc(c) + (before.map { "&before=" + Api.enc($0) } ?? "") + "&limit=\(limit)")
    }
    func chatSend(_ body: JSON) async -> Reply { await postTo("/api/chat/send", body) }
    func chatEdit(_ c: String, _ id: String, _ text: String) async -> Reply { await postTo("/api/chat/edit", ["c": c, "id": id, "text": text]) }
    func chatDelete(_ c: String, _ id: String, forAll: Bool) async -> Reply { await postTo("/api/chat/delete", ["c": c, "id": id, "forAll": forAll]) }
    func chatDeleteMany(_ c: String, _ ids: [String], forAll: Bool) async -> Reply {
        await postTo("/api/chat/delete-many", ["c": c, "forAll": forAll, "ids": ids.map { Int($0).map { $0 as Any } ?? $0 as Any }])
    }
    func chatReact(_ c: String, _ id: String, _ emoji: String?) async -> Reply { await postTo("/api/chat/react", ["c": c, "id": id, "emoji": emoji.map { $0 as Any } ?? NSNull()]) }
    func chatStar(_ c: String, _ id: String, _ on: Bool) async -> Reply { await postTo("/api/chat/star", ["c": c, "id": id, "on": on]) }
    func chatStarred() async -> Reply { await get("/api/chat/starred") }
    func chatRead(_ c: String, _ upTo: String) async -> Reply { await postTo("/api/chat/read", ["c": c, "upTo": upTo]) }
    func chatDelivered(_ c: String, _ upTo: String) async -> Reply { await postTo("/api/chat/delivered", ["c": c, "upTo": upTo]) }
    func chatInfo(_ c: String, _ id: String) async -> Reply { await get("/api/chat/info?c=" + Api.enc(c) + "&id=" + Api.enc(id)) }
    func chatLiveAt(_ c: String, _ id: String, _ ll: LatLng) async -> Reply { await postTo("/api/chat/live", ["c": c, "id": id, "lat": ll.lat, "lng": ll.lng]) }
    func chatLiveStop(_ c: String, _ id: String) async -> Reply { await postTo("/api/chat/live-stop", ["c": c, "id": id]) }
    func chatPrefs(_ c: String?, _ f: JSON) async -> Reply { var b = f; if let c { b["c"] = c }; return await postTo("/api/chat/prefs", b) }
    func chatSearch(_ q: String) async -> Reply { await get("/api/chat/search?q=" + Api.enc(q)) }
    func chatTicket() async -> Reply { await postTo("/api/chat/ticket", [:]) }
    func chatPin(_ c: String, _ id: String, _ on: Bool) async -> Reply { await postTo("/api/chat/pin", ["c": c, "id": id, "on": on]) }
    func chatTrip(_ id: String) async -> Reply { await get("/api/chat/trip?id=" + Api.enc(id)) }
    func chatTripCard(_ id: String) async -> Reply { await get("/api/itin/" + Api.enc(id) + "?card=1") }
    func chatClear(_ c: String, archive: Bool) async -> Reply { var b: JSON = ["c": c]; if archive { b["archive"] = true }; return await postTo("/api/chat/clear", b) }
    func chatTranscribe(_ c: String, _ id: String) async -> Reply { await postTo("/api/chat/transcribe", ["c": c, "id": Int(id).map { $0 as Any } ?? id as Any]) }
    func chatMedia(_ data: Data, type: String, name: String, mime: String, dur: Double = 0) async -> Reply {
        var fields: [(String, String)] = [("type", type), ("name", name)]
        if dur > 0 { fields.append(("dur", String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), dur))) }
        return await multipart("/api/chat/media", data: data, mime: mime, filename: name, fields: fields)
    }
    static func chatMediaPath(_ id: String) -> String { "/api/chat/media/" + Api.enc(id) }
}

// MARK: - files on this phone ------------------------------------------------------------------------------------------

enum ChatFiles {
    /// Where photos and voice notes wait before they're sent (kept relative to the app's data folder).
    static var outDir: URL {
        let u = Prefs.dataDir.appendingPathComponent("chat/out", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }
    /// Downloaded chat media (can be cleared by iOS).
    static var cacheDir: URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let u = base.appendingPathComponent("chat-media", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }
    static func safe(_ s: String) -> String { String(s.map { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" ? $0 : "_" }) }
    static func newName(_ ext: String) -> String { "c\(Int(Date().timeIntervalSince1970 * 1000))\(Int.random(in: 100...999)).\(ext)" }

    /// A photo, upright and at most 1600 px, and its 320 px preview for the data saver. Returns (photo, thumb) relative paths.
    static func keepPhoto(_ img: UIImage) -> (ChatMedia)? {
        func shrink(_ px: CGFloat, _ q: CGFloat) -> (Data, CGSize)? {
            let k = min(1, px / max(img.size.width, img.size.height))
            let size = CGSize(width: max(1, (img.size.width * k).rounded()), height: max(1, (img.size.height * k).rounded()))
            let f = UIGraphicsImageRendererFormat(); f.scale = 1
            let out = UIGraphicsImageRenderer(size: size, format: f).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
            guard let d = out.jpegData(compressionQuality: q) else { return nil }
            return (d, size)
        }
        guard let big = shrink(1600, 0.82) else { return nil }
        let name = newName("jpg")
        do { try big.0.write(to: outDir.appendingPathComponent(name)) } catch { return nil }
        var md = ChatMedia(id: "", type: "photo", w: Int(big.1.width), h: Int(big.1.height), name: "photo.jpg", size: big.0.count, local: "chat/out/" + name, mime: "image/jpeg")
        if let small = shrink(320, 0.6) {
            let tn = "t" + name
            if (try? small.0.write(to: outDir.appendingPathComponent(tn))) != nil { md.thumb = "chat/out/" + tn }
        }
        return md
    }
    static func mime(_ type: String, _ path: String) -> String {
        switch type {
        case "voice": return "audio/mp4"
        case "photo": return "image/jpeg"
        case "video": return "video/mp4"
        default: return path.lowercased().hasSuffix(".pdf") ? "application/pdf" : "application/octet-stream"
        }
    }
}

// MARK: - the outbox ---------------------------------------------------------------------------------------------------

/// Messages written while offline (or not yet sent): kept in a file, so they wait with a clock and survive the app being
/// closed. Each has a client id; the server returns the same message for the same cid, so sending twice is safe. Files are
/// uploaded first and their server ids saved, so a half-sent message never uploads twice.
final class ChatOutbox {
    struct Item {
        var cid: String; var c: String; var body: JSON; var files: [ChatMedia]; var at: Double; var tries = 0
        func json() -> JSON { ["cid": cid, "c": c, "body": body, "at": at, "tries": tries, "files": files.map { $0.json(local: true) }] }
        static func from(_ o: JSON) -> Item {
            Item(cid: o.str("cid"), c: o.str("c"), body: o.obj("body") ?? [:], files: o.objs("files").map(ChatMedia.from), at: o.num("at") ?? 0, tries: o.int("tries"))
        }
        /// How it shows in the chat while waiting: a bubble with the clock.
        func asMsg(_ me: String) -> ChatMsg {
            var o = body
            o["id"] = cid; o["cid"] = cid; o["c"] = c; o["from"] = me; o["at"] = chatIso(at); o["status"] = "queued"
            o["media"] = files.map { $0.json(local: true) }
            if let r = body["_reply"] as? JSON { o["reply"] = r } else { o["reply"] = nil }
            if let l = body["live"] as? JSON { o["live"] = ["until": l["until"] ?? 0] as JSON }
            return ChatMsg.from(o, chat: c)
        }
    }

    private let file: URL
    private let lock = NSLock()
    init(file: URL) { self.file = file }

    func all() -> [Item] { lock.lock(); defer { lock.unlock() }; return read() }
    private func read() -> [Item] {
        guard let d = try? Data(contentsOf: file), let a = (try? JSONSerialization.jsonObject(with: d)) as? [Any] else { return [] }
        return a.compactMap { ($0 as? JSON).map(Item.from) }
    }
    private func save(_ items: [Item]) {
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let d = try? JSONSerialization.data(withJSONObject: items.map { $0.json() }) { try? d.write(to: file, options: .atomic) }
    }
    func add(_ it: Item) { lock.lock(); defer { lock.unlock() }; save(read().filter { $0.cid != it.cid } + [it]) }
    func put(_ it: Item) { lock.lock(); defer { lock.unlock() }; save(read().map { $0.cid == it.cid ? it : $0 }) }
    func remove(_ cid: String, deleteFiles: Bool = true) {
        lock.lock(); defer { lock.unlock() }
        let items = read()
        if deleteFiles, let it = items.first(where: { $0.cid == cid }) {
            for f in it.files where f.local.hasPrefix("chat/out/") { try? FileManager.default.removeItem(at: Outbox.resolve(f.local)) }
        }
        save(items.filter { $0.cid != cid })
    }
    func count() -> Int { all().count }
    func forChat(_ c: String) -> [Item] { all().filter { $0.c == c } }

    struct Result { var sent: [String: ChatMsg] = [:]; var failed: [String: String] = [:]; var waiting = 0; var off = false; var locked = false; var uploaded = 0 }

    /// Sends everything waiting, oldest first. A message that can't go yet stays (and the ones after it in the same chat).
    func flush(_ api: Api) async -> Result {
        var res = Result()
        var stuck = Set<String>()
        for it0 in all().sorted(by: { $0.at < $1.at }) {
            var it = it0
            if stuck.contains(it.c) || res.off || res.locked { res.waiting += 1; continue }
            var ok = true
            // 1. the files: each uploaded once, its server id kept
            for i in it.files.indices where it.files[i].id.isEmpty {
                let f = it.files[i]
                guard let data = try? Data(contentsOf: Outbox.resolve(f.local)) else {
                    res.failed[it.cid] = t("The file is no longer on this phone.", "Faili halipo tena kwenye simu hii."); ok = false; break
                }
                let name = f.name.isEmpty ? (f.local as NSString).lastPathComponent : f.name
                let r = await api.chatMedia(data, type: f.type, name: name, mime: f.mime.isEmpty ? ChatFiles.mime(f.type, f.local) : f.mime, dur: f.dur)
                if !r.ok {
                    if r.reason == "chat-off" { res.off = true } else if r.status == 401 { res.locked = true } else if !r.retryLater { res.failed[it.cid] = chatReason(r.reason) }
                    ok = false; break
                }
                res.uploaded += data.count
                it.files[i].id = r.json.str("id"); put(it)
            }
            // the photo's small preview (the data saver): best effort
            if ok {
                for i in it.files.indices where it.files[i].thumb.hasPrefix("chat/") {
                    guard let data = try? Data(contentsOf: Outbox.resolve(it.files[i].thumb)) else { it.files[i].thumb = ""; continue }
                    let r = await api.chatMedia(data, type: "photo", name: "thumb.jpg", mime: "image/jpeg")
                    if r.ok { it.files[i].thumb = r.json.str("id"); res.uploaded += data.count }
                    else if !r.retryLater && r.status != 401 { it.files[i].thumb = "" }
                    else { ok = false; break }
                    put(it)
                }
            }
            if res.failed[it.cid] != nil { remove(it.cid); continue }
            if !ok { res.waiting += 1; stuck.insert(it.c); it.tries += 1; put(it); continue }
            // 2. the message, with the same client id every time
            var body = it.body
            body["c"] = it.c; body["cid"] = it.cid
            for k in body.keys where k.hasPrefix("_") { body[k] = nil }
            if !it.files.isEmpty { body["media"] = it.files.map { $0.json() } }
            let r = await api.chatSend(body)
            if r.ok {
                var m = r.json.obj("msg").map { ChatMsg.from($0, chat: it.c) } ?? { var x = it.asMsg(""); x.status = "sent"; return x }()
                let files = it.files
                m.media = m.media.map { md in var x = md; x.local = files.first { $0.id == md.id }?.local ?? ""; return x }
                res.sent[it.cid] = m
                remove(it.cid, deleteFiles: false)
            } else if r.reason == "chat-off" { res.off = true; res.waiting += 1 }
            else if r.status == 401 { res.locked = true; res.waiting += 1 }
            else if r.retryLater { res.waiting += 1; stuck.insert(it.c); it.tries += 1; put(it) }
            else { res.failed[it.cid] = chatReason(r.reason); remove(it.cid) }
        }
        return res
    }
}

// MARK: - the copy kept on the phone -----------------------------------------------------------------------------------

/// The chat list and the latest messages of each chat, kept on the phone so Chat opens offline.
final class ChatCache {
    let dir: URL
    init(dir: URL) { self.dir = dir }
    private func f(_ name: String) -> URL {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent(String(name.map { $0.isLetter || $0.isNumber || "_.-".contains($0) ? $0 : "_" }))
    }
    private func write(_ name: String, _ o: Any) { if let d = try? JSONSerialization.data(withJSONObject: o) { try? d.write(to: f(name), options: .atomic) } }
    private func read(_ name: String) -> Any? { (try? Data(contentsOf: f(name))).flatMap { try? JSONSerialization.jsonObject(with: $0) } }

    func saveList(_ rows: [ChatRow], me: String, presence: [String: ChatPresence]) {
        var p: JSON = [:]; presence.forEach { k, v in p[k] = ["online": v.online, "seen": chatIso(v.seen)] as JSON }
        write("list.json", ["me": me, "chats": rows.map { $0.json() }, "presence": p] as JSON)
    }
    func loadList() -> ([ChatRow], String, [String: ChatPresence])? {
        guard let j = read("list.json") as? JSON else { return nil }
        return (j.objs("chats").map(ChatRow.from), j.str("me"), ChatCache.presence(j.obj("presence")))
    }
    func saveMsgs(_ c: String, _ msgs: [ChatMsg]) { write("c_\(c).json", msgs.filter { !$0.queued }.suffix(200).map { $0.json() }) }
    func loadMsgs(_ c: String) -> [ChatMsg] { ((read("c_\(c).json") as? [Any]) ?? []).compactMap { ($0 as? JSON).map { ChatMsg.from($0, chat: c) } } }
    func clear() {
        let all = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        for u in all where u.lastPathComponent != "outbox.json" && u.lastPathComponent != "out" { try? FileManager.default.removeItem(at: u) }
    }
    static func presence(_ o: JSON?) -> [String: ChatPresence] {
        var out: [String: ChatPresence] = [:]
        (o ?? [:]).forEach { k, v in let j = v as? JSON; out[k] = ChatPresence(online: j?.bool("online") ?? false, seen: chatMs(j?["seen"])) }
        return out
    }
}

// MARK: - my chat settings ---------------------------------------------------------------------------------------------

/// Do-Not-Disturb hours ("21:00" to "06:30"): no sounds and quiet notifications. `mentions`: @mentions still ring.
struct ChatDnd: Equatable {
    var on = false; var from = "21:00"; var to = "06:30"; var mentions = false
    func json() -> JSON { ["on": on, "from": from, "to": to, "mentions": mentions, "tz": TimeZone.current.secondsFromGMT() / 60] }
    static func minutes(_ s: String) -> Int? {
        let p = s.trimmingCharacters(in: .whitespaces).split(separator: ":")
        guard p.count == 2, let h = Int(p[0]), let m = Int(p[1]), (0..<24).contains(h), (0..<60).contains(m) else { return nil }
        return h * 60 + m
    }
    /// Inside the quiet hours now (they may run past midnight).
    func active(_ d: Date = Date()) -> Bool {
        guard on, let a = ChatDnd.minutes(from), let b = ChatDnd.minutes(to), a != b else { return false }
        let c = Calendar.current.dateComponents([.hour, .minute], from: d)
        let now = (c.hour ?? 0) * 60 + (c.minute ?? 0)
        return a < b ? (now >= a && now < b) : (now >= a || now < b)
    }
    static func from(_ v: Any?) -> ChatDnd {
        guard let o = v as? JSON else { return ChatDnd() }
        let f = chatS(o, "from"), t0 = chatS(o, "to")
        return ChatDnd(on: o["on"] != nil ? o.bool("on") : (!f.isEmpty && !t0.isEmpty), from: f.isEmpty ? "21:00" : f, to: t0.isEmpty ? "06:30" : t0, mentions: o.bool("mentions"))
    }
}

let CHAT_QUICK_DEFAULTS: [(String, String)] = [("On my way", "Niko njiani"), ("Arrived at site", "Nimefika eneo la kazi"), ("Pump fixed ✅", "Pampu imetengenezwa ✅"),
                                               ("Need parts", "Nahitaji vipuri"), ("Running late", "Nimechelewa"), ("Call me", "Nipigie simu")]
let CHAT_QUICK_MAX = 8

/// My chat settings. Quick replies, Do-Not-Disturb and the double-tap reaction are synced with the server; the data saver,
/// sounds and the data counter are this phone's own.
final class ChatSettings {
    private let d: UserDefaults
    init(_ d: UserDefaults) { self.d = d }
    var quick: [String] {
        get {
            let l = (d.array(forKey: "chat.quick") as? [String])?.filter { !$0.isEmpty } ?? []
            return l.isEmpty ? CHAT_QUICK_DEFAULTS.map { t($0.0, $0.1) } : l
        }
        set {
            var seen = Set<String>(); var out: [String] = []
            for x in newValue.map({ $0.trimmingCharacters(in: .whitespaces) }) where !x.isEmpty && !seen.contains(x) { seen.insert(x); out.append(x) }
            d.set(Array(out.prefix(CHAT_QUICK_MAX)), forKey: "chat.quick")
        }
    }
    var quickCustom: Bool { !((d.array(forKey: "chat.quick") as? [String]) ?? []).isEmpty }
    func quickReset() { d.removeObject(forKey: "chat.quick") }
    var dnd: ChatDnd {
        get { (d.dictionary(forKey: "chat.dnd")).map { ChatDnd.from($0) } ?? ChatDnd() }
        set { var j = newValue.json(); j["tz"] = nil; d.set(j, forKey: "chat.dnd") }
    }
    var doubleTap: String { get { d.string(forKey: "chat.doubleTap") ?? "❤️" } set { d.set(newValue, forKey: "chat.doubleTap") } }
    /// "off", "mobile" (the default: on mobile data and other expensive networks) or "always".
    var saver: String { get { d.string(forKey: "chat.saver") ?? "mobile" } set { d.set(newValue, forKey: "chat.saver") } }
    var sendSound: Bool { get { d.object(forKey: "chat.sendSound") as? Bool ?? true } set { d.set(newValue, forKey: "chat.sendSound") } }
    var mentionSound: Bool { get { d.object(forKey: "chat.mentionSound") as? Bool ?? true } set { d.set(newValue, forKey: "chat.mentionSound") } }

    func fromServer(_ o: JSON?) {
        guard let o else { return }
        if o["quick"] != nil {
            let l = chatStrings(o["quick"])
            if l.isEmpty { quickReset() } else { quick = l }
        }
        if let x = o["dnd"], !(x is NSNull) { dnd = ChatDnd.from(x) }
        let dt = chatS(o, "doubleTap"); if !dt.isEmpty { doubleTap = dt }
    }
    func serverJson() -> JSON { ["quick": quickCustom ? quick as Any : NSNull(), "dnd": dnd.json(), "doubleTap": doubleTap] }

    /// Chat downloads and uploads this month, on this phone.
    private var monthKey: String { let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM"; return "chat.data." + f.string(from: Date()) }
    func count(_ bytes: Int) { if bytes > 0 { d.set(d.integer(forKey: monthKey) + bytes, forKey: monthKey) } }
    var usedThisMonth: Int { d.integer(forKey: monthKey) }
    /// The data saver is on right now.
    var saverOn: Bool { saver == "always" || (saver == "mobile" && ChatNet.shared.expensive) }
}

/// Is the phone on mobile data (or another expensive network)? For the data saver.
final class ChatNet {
    static let shared = ChatNet()
    private let mon = NWPathMonitor()
    private(set) var expensive = false
    init() {
        mon.pathUpdateHandler = { [weak self] p in self?.expensive = p.isExpensive || p.isConstrained }
        mon.start(queue: DispatchQueue(label: "chat.net"))
    }
}

/// "3.2 MB", "850 KB".
func chatDataText(_ b: Int) -> String {
    if b < 1024 * 1024 { return "\((b + 1023) / 1024) KB" }
    if b < 1024 * 1024 * 1024 { return String(format: "%.1f MB", Double(b) / 1_048_576) }
    return String(format: "%.1f GB", Double(b) / 1_073_741_824)
}

/// 1–3 emoji and nothing else: shown large, without a bubble. 0 otherwise.
func chatEmojiOnly(_ text: String) -> Int {
    let s = text.trimmingCharacters(in: .whitespacesAndNewlines)
    if s.isEmpty || s.count > 12 { return 0 }
    var n = 0
    for ch in s where !ch.isWhitespace {
        let scalars = ch.unicodeScalars
        guard let first = scalars.first else { return 0 }
        let isEmoji = first.properties.isEmojiPresentation || (first.properties.isEmoji && scalars.count > 1) || (first.value >= 0x1F000)
        if !isEmoji { return 0 }
        n += 1; if n > 3 { return 0 }
    }
    return n
}
