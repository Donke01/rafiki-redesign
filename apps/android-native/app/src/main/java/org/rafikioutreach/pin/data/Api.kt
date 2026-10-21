package org.rafikioutreach.pin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What a call to the RAFIKI server came back with. status 0 = no connection. */
data class Reply(val ok: Boolean, val status: Int, val json: JSONObject, val reason: String?) {
    val retryLater get() = status == 0 || status >= 500 || status == 429
}

/** Talks to the same endpoints as the web app: /api/pin, /api/pin-photo, /api/place. */
class Api(val server: String, private val pin: () -> String, private val session: () -> String = { "" }) {

    /** Lets tests swap the network for a fake one. */
    var transport: (method: String, url: String, headers: Map<String, String>, body: ByteArray?) -> Pair<Int, String> = ::http

    suspend fun list(): Reply = call("GET", "/api/pin", null)
    suspend fun history(id: String): Reply = call("GET", "/api/pin?history=" + enc(id), null)
    suspend fun last(id: String): Reply = call("GET", "/api/pin?id=" + enc(id), null)
    suspend fun post(body: JSONObject): Reply = call("POST", "/api/pin", body)
    suspend fun place(q: String, near: LatLng?, ck: String): Reply =
        call("GET", "/api/place?q=" + enc(q) + (if (ck.isNotBlank()) "&cc=$ck" else "") + (near?.let { "&near=${it.lat},${it.lng}" } ?: ""), null)

    suspend fun activity(): Reply = call("GET", "/api/pin?activity=1", null)
    suspend fun team(): Reply = call("GET", "/api/pin?team=1", null)
    suspend fun adminGet(view: String): Reply = call("GET", "/api/admin?view=" + enc(view), null)
    suspend fun admin(body: JSONObject): Reply = call("POST", "/api/admin", body)
    suspend fun authMe(): Reply = call("GET", "/api/pin-auth/me", null)
    suspend fun authExchange(code: String, verifier: String): Reply = call("POST", "/api/pin-auth/exchange", JSONObject().put("code", code).put("verifier", verifier))
    suspend fun authToken(token: String): Reply = call("POST", "/api/pin-auth/token", JSONObject().put("token", token))
    suspend fun logout(): Reply = call("POST", "/api/pin-auth/logout", JSONObject())
    /** The newest Android app on the website: {versionCode, versionName, apk}. */
    suspend fun appVersion(): Reply = call("GET", "/app/version.json", null)

    fun photoUrl(photo: String, w: Int = 480) = "$server/api/photo/${enc(photo)}?w=$w"

    /** Who is asking: a PIN, or the monday.com session (sent the way a browser sends its cookie). */
    private fun auth(h: MutableMap<String, String>) {
        pin().takeIf { it.isNotBlank() }?.let { h["X-Pin"] = it }
        session().takeIf { it.isNotBlank() }?.let { h["Cookie"] = "rfpin=$it"; h["Origin"] = server }
    }

    private suspend fun call(method: String, path: String, body: JSONObject?): Reply = withContext(Dispatchers.IO) {
        val h = mutableMapOf("Accept" to "application/json")
        auth(h)
        if (body != null) h["Content-Type"] = "application/json"
        if (method == "POST") h["Origin"] = server
        val (status, text) = transport(method, server + path, h, body?.toString()?.toByteArray())
        toReply(status, text)
    }

    /** Adds one photo or voice note to a monday.com note (after the change itself was saved). */
    suspend fun upload(updateId: String, file: File, kind: String, label: String): Reply = withContext(Dispatchers.IO) {
        val boundary = "----rafiki" + System.nanoTime()
        val out = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("update", updateId)
        if (label.isNotBlank()) field("label", label)
        val ext = if (kind == "voice") "m4a" else "jpg"
        val mime = if (kind == "voice") "audio/mp4" else "image/jpeg"
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${if (kind == "voice") "voice" else "photo"}.$ext\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
        out.write(file.readBytes())
        out.write("\r\n--$boundary--\r\n".toByteArray())
        val h = mutableMapOf("Accept" to "application/json", "Content-Type" to "multipart/form-data; boundary=$boundary")
        auth(h)
        val (status, text) = transport("POST", "$server/api/pin-photo", h, out.toByteArray())
        toReply(status, text)
    }

    private fun toReply(status: Int, text: String): Reply {
        if (status == 0) return Reply(false, 0, JSONObject(), "offline")
        val j = try { JSONObject(text) } catch (e: Exception) { JSONObject().put("ok", false).put("reason", "down") }
        return Reply(j.optBoolean("ok"), status, j, j.optString("reason").takeIf { it.isNotBlank() })
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        fun http(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Pair<Int, String> = try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 45000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            code to text
        } catch (e: Exception) { 0 to "" }
    }
}
