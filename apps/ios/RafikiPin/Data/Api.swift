import Foundation

/// What a call to the RAFIKI server came back with. status 0 = no connection.
struct Reply {
    var ok: Bool; var status: Int; var json: JSON; var reason: String?
    var retryLater: Bool { status == 0 || status >= 500 || status == 429 }
}

typealias Transport = (_ method: String, _ url: String, _ headers: [String: String], _ body: Data?) async -> (Int, Data)

/// Talks to the same endpoints as the web and Android apps: /api/pin, /api/pin-photo, /api/place, /api/admin.
final class Api {
    let server: String
    private let pin: () -> String
    private let session: () -> String
    /// Lets the demo mode swap the network for a fake one.
    var transport: Transport = Api.http
    /// Told when the server ends this person's access: 401 with reason "blocked" (an admin blocked them) or
    /// "signed-out" (an admin signed them out of all phones). The app then signs out with a kind message.
    var onAuthEnd: ((String) -> Void)?
    /// The PIN session's number (X-Pin-Gen): after "Sign out of all phones" the server refuses the old one.
    var gen: () -> String = { "" }
    /// True while signing in with a PIN typed on the lock screen (X-Pin-New: 1 starts a new session).
    var fresh = false
    /// 401 reasons that mean "you're no longer signed in here".
    static let AUTH_END: Set<String> = ["blocked", "signed-out"]

    init(server: String, pin: @escaping () -> String, session: @escaping () -> String = { "" }) {
        self.server = server; self.pin = pin; self.session = session
    }

    func list() async -> Reply { await call("GET", "/api/pin", nil) }
    func history(_ id: String) async -> Reply { await call("GET", "/api/pin?history=" + Api.enc(id), nil) }
    func last(_ id: String) async -> Reply { await call("GET", "/api/pin?id=" + Api.enc(id), nil) }
    func post(_ body: JSON) async -> Reply { await call("POST", "/api/pin", body) }
    func place(_ q: String, near: LatLng?, ck: String) async -> Reply {
        await call("GET", "/api/place?free=1&q=" + Api.enc(q) + (ck.isEmpty ? "" : "&cc=\(ck)") + (near.map { "&near=\($0.lat),\($0.lng)" } ?? ""), nil)
    }
    /// An admin view with more to ask, e.g. adminGet("plan", ["ck": "ke", "days": "5"]).
    func adminGet(_ view: String, _ q: [String: String]) async -> Reply {
        await call("GET", "/api/admin?view=" + Api.enc(view) + q.sorted { $0.key < $1.key }.map { "&" + Api.enc($0.key) + "=" + Api.enc($0.value) }.joined(), nil)
    }
    /// Office › Send an e-mail: who can be reached, the limits and what was sent (/api/mail).
    func mailGet() async -> Reply { await call("GET", "/api/mail?view=overview", nil) }
    /// Office › Send an e-mail: preview, test, send or a photo ({action, audience, picks, subject, body, …}).
    func mail(_ body: JSON) async -> Reply { await call("POST", "/api/mail", body) }
    /// Office › Community: SMS / WhatsApp reports, set-up status and committee phones.
    func smsAdmin() async -> Reply { await call("GET", "/api/sms-admin", nil) }
    func smsAdminPost(_ body: JSON) async -> Reply { await call("POST", "/api/sms-admin", body) }
    /// Any GET on the server (trip advice, road reports…).
    func get(_ path: String) async -> Reply { await call("GET", path, nil) }
    func postTo(_ path: String, _ body: JSON) async -> Reply { await call("POST", path, body) }
    func activity() async -> Reply { await call("GET", "/api/pin?activity=1", nil) }
    func adminGet(_ view: String) async -> Reply { await call("GET", "/api/admin?view=" + Api.enc(view), nil) }
    func admin(_ body: JSON) async -> Reply { await call("POST", "/api/admin", body) }
    func authMe() async -> Reply { await call("GET", "/api/pin-auth/me", nil) }
    func authExchange(code: String, verifier: String) async -> Reply { await call("POST", "/api/pin-auth/exchange", ["code": code, "verifier": verifier]) }
    func team() async -> Reply { await call("GET", "/api/pin?team=1", nil) }
    func authToken(_ token: String) async -> Reply { await call("POST", "/api/pin-auth/token", ["token": token]) }
    func logout() async -> Reply { await call("POST", "/api/pin-auth/logout", [:]) }

    func photoUrl(_ photo: String, w: Int = 480) -> String { "\(server)/api/photo/\(Api.enc(photo))?w=\(w)" }

    /// Who is asking: a PIN, or the monday.com session (sent the way a browser sends its cookie).
    private func auth(_ h: inout [String: String]) {
        let p = pin()
        if !p.isEmpty {
            h["X-Pin"] = p
            if fresh { h["X-Pin-New"] = "1" } else { let g = gen(); if !g.isEmpty { h["X-Pin-Gen"] = g } }
        }
        let s = session(); if !s.isEmpty { h["Cookie"] = "rfpin=\(s)"; h["Origin"] = server }
    }

    private func call(_ method: String, _ path: String, _ body: JSON?) async -> Reply {
        var h = ["Accept": "application/json"]
        auth(&h)
        var data: Data? = nil
        if let body { h["Content-Type"] = "application/json"; data = try? JSONSerialization.data(withJSONObject: body) }
        if method == "POST" { h["Origin"] = server }
        let (status, out) = await transport(method, server + path, h, data)
        return seen(Api.toReply(status, out))
    }

    /// Adds one photo or voice note to a monday.com note (after the change itself was saved).
    func upload(updateId: String, file: URL, kind: String, label: String) async -> Reply {
        let boundary = "----rafiki\(UInt64(Date().timeIntervalSince1970 * 1000))"
        var out = Data()
        func field(_ name: String, _ value: String) { out.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n".data(using: .utf8)!) }
        field("update", updateId)
        if !label.isEmpty { field("label", label) }
        let voice = kind == "voice"
        out.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"\(voice ? "voice.m4a" : "photo.jpg")\"\r\nContent-Type: \(voice ? "audio/mp4" : "image/jpeg")\r\n\r\n".data(using: .utf8)!)
        out.append((try? Data(contentsOf: file)) ?? Data())
        out.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        var h = ["Accept": "application/json", "Content-Type": "multipart/form-data; boundary=\(boundary)"]
        auth(&h)
        let (status, text) = await transport("POST", "\(server)/api/pin-photo", h, out)
        return seen(Api.toReply(status, text))
    }

    /// A thank-you photo or short video (≤ 60 s) from the community, for the project's donors (/api/pin-thanks).
    func thanks(id: String, data: Data, mime: String, caption: String, seconds: Int) async -> Reply {
        let boundary = "----rafiki\(UInt64(Date().timeIntervalSince1970 * 1000))"
        var out = Data()
        func field(_ name: String, _ value: String) { out.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n".data(using: .utf8)!) }
        field("id", id); field("caption", caption); field("seconds", "\(seconds)")
        let ext = ["video/mp4": "mp4", "video/quicktime": "mov", "image/png": "png", "image/webp": "webp"][mime] ?? "jpg"
        out.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"thanks.\(ext)\"\r\nContent-Type: \(mime)\r\n\r\n".data(using: .utf8)!)
        out.append(data)
        out.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        var h = ["Accept": "application/json", "Content-Type": "multipart/form-data; boundary=\(boundary)", "Origin": server]
        auth(&h)
        let (status, text) = await transport("POST", "\(server)/api/pin-thanks", h, out)
        return seen(Api.toReply(status, text))
    }

    /// A 401 that ends this sign-in is passed on, so the app signs out kindly.
    private func seen(_ r: Reply) -> Reply {
        if r.status == 401, let why = r.reason, Api.AUTH_END.contains(why) { let cb = onAuthEnd; DispatchQueue.main.async { cb?(why) } }
        return r
    }

    static func toReply(_ status: Int, _ data: Data) -> Reply {
        if status == 0 { return Reply(ok: false, status: 0, json: [:], reason: "offline") }
        let j = (try? JSONSerialization.jsonObject(with: data)) as? JSON ?? ["ok": false, "reason": "down"]
        let r = j.str("reason")
        return Reply(ok: j.bool("ok"), status: status, json: j, reason: r.isEmpty ? nil : r)
    }

    static func enc(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }

    static let net: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 30; c.timeoutIntervalForResource = 90
        c.httpShouldSetCookies = false; c.httpCookieAcceptPolicy = .never
        return URLSession(configuration: c)
    }()

    static func http(_ method: String, _ url: String, _ headers: [String: String], _ body: Data?) async -> (Int, Data) {
        guard let u = URL(string: url) else { return (0, Data()) }
        var r = URLRequest(url: u); r.httpMethod = method; r.httpBody = body
        headers.forEach { r.setValue($0.value, forHTTPHeaderField: $0.key) }
        do {
            let (d, resp) = try await net.data(for: r)
            return ((resp as? HTTPURLResponse)?.statusCode ?? 0, d)
        } catch { return (0, Data()) }
    }
}
