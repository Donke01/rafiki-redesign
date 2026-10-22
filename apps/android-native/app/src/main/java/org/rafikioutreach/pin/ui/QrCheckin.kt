package org.rafikioutreach.pin.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.R
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.t
import java.io.File

/*
 * Check in by QR code. Every project can have a printed sticker (Office › QR codes) holding
 * https://pin.rafikioutreach.org/pin#p=<id>: the app's Scan QR reads it, and so does any phone camera
 * (it opens this app when installed, otherwise the web app, which understands the same address).
 * Scanning opens the project's Site visit and the visit report then says "Checked in by scanning the
 * site's QR code" on monday.com (the visit is sent with qr: true).
 */
object QrCheckin {
    const val BASE = "https://pin.rafikioutreach.org/pin"
    fun url(id: String) = "$BASE#p=$id"

    /** The project id in a scanned code: our address (#p=123, or the app's own #p/123), or just the number. */
    fun parse(text: String?): String? {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return null
        Regex("#p[=/](\\d{1,15})").find(s)?.let { return it.groupValues[1] }
        if (Regex("^\\d{1,15}$").matches(s)) return s
        return null
    }

    /** A link opened from outside (a phone camera): https://pin.rafikioutreach.org/pin#p=123. */
    fun fromLink(u: Uri?): Boolean {
        if (u == null || u.scheme != "https" || u.path?.trimEnd('/') != "/pin") return false
        val id = parse("#" + (u.fragment ?: "")) ?: return false
        openReq = id; return true
    }

    private val marks = mutableStateMapOf<String, Long>()
    /** Scanned at the site: this project's next visit report says so (for the next 12 hours). */
    fun mark(id: String, at: Long = System.currentTimeMillis()) { marks[id] = at }
    fun isFor(id: String): Boolean = (marks[id] ?: 0L) > System.currentTimeMillis() - 12 * 3_600_000L
    fun at(id: String): Long? = marks[id]
    fun clear(id: String) { marks.remove(id) }

    /** A project to open on its Site visit (set by a scanned link; the app shell opens it). */
    var openReq by mutableStateOf<String?>(null)

    /** Lets tests swap Google's camera scanner: calls back with the text read, null when cancelled, or an error. */
    var scanner: (Context, (String?) -> Unit, (String) -> Unit) -> Unit = { ctx, ok, fail ->
        runCatching {
            val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(ctx, opts).startScan()
                .addOnSuccessListener { ok(it.rawValue) }.addOnCanceledListener { ok(null) }.addOnFailureListener { fail(it.message ?: "") }
        }.onFailure { fail(it.message ?: "") }
    }
}

/** QR codes: made with ZXing, read from a photo with ZXing. */
object QrCode {
    fun matrix(text: String): BitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8"))

    /** Black on white with a quiet zone of [quiet] modules, [scale] pixels per module. */
    fun bitmap(text: String, scale: Int = 8, quiet: Int = 2): Bitmap {
        val m = matrix(text); val n = m.width + quiet * 2
        val px = IntArray(n * scale * n * scale) { -1 }
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) {
            for (dy in 0 until scale) { val row = ((y + quiet) * scale + dy) * n * scale; for (dx in 0 until scale) px[row + (x + quiet) * scale + dx] = 0xFF000000.toInt() }
        }
        return Bitmap.createBitmap(px, n * scale, n * scale, Bitmap.Config.ARGB_8888)
    }

    /** The text in a QR code in a photo, or null. */
    fun decode(src: Bitmap): String? {
        val b = if (maxOf(src.width, src.height) > 1600) { val k = 1600f / maxOf(src.width, src.height); Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) } else src
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val lum = RGBLuminanceSource(b.width, b.height, px)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        for (bin in listOf(HybridBinarizer(lum), GlobalHistogramBinarizer(lum))) {
            runCatching { return QRCodeReader().decode(BinaryBitmap(bin), hints).text }
        }
        return null
    }

    /** Draws the code as squares (sharp at any print size). */
    fun draw(c: Canvas, text: String, left: Float, top: Float, size: Float) {
        val m = matrix(text); val k = size / m.width
        val ink = Paint().apply { color = 0xFF000000.toInt(); isAntiAlias = false }
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) c.drawRect(left + x * k, top + y * k, left + (x + 1) * k + 0.2f, top + (y + 1) * k + 0.2f, ink)
    }
}

/** The printable sheet: A4, 3 × 4 stickers a page, each with the RAFIKI logo, the code, the project name and id. */
object QrStickers {
    const val COLS = 3; const val ROWS = 4
    var lastPdf: File? = null

    fun pdf(ctx: Context, list: List<Project>, out: File): File {
        val doc = PdfDocument()
        val pages = list.chunked(COLS * ROWS)
        pages.forEachIndexed { pi, chunk ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pi + 1).create())
            drawPage(ctx, page.canvas, chunk, pi + 1, pages.size)
            doc.finishPage(page)
        }
        out.parentFile?.mkdirs(); out.outputStream().use { doc.writeTo(it) }; doc.close()
        lastPdf = out
        return out
    }

    /** One A4 page (595 × 842 points) of up to 12 stickers. */
    fun drawPage(ctx: Context, c: Canvas, chunk: List<Project>, pageNo: Int, pages: Int) {
        val W = 595f; val H = 842f; val mx = 28f; val top = 34f; val bottom = 40f
        val cw = (W - 2 * mx) / COLS; val ch = (H - top - bottom) / ROWS
        val logo = runCatching { BitmapFactory.decodeResource(ctx.resources, R.drawable.letterhead_logo) }.getOrNull()
        val cut = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 0.6f; color = 0xFFB8C4CE.toInt(); pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f) }
        val name = TextPaint().apply { isAntiAlias = true; textSize = 10.5f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); color = 0xFF04263F.toInt() }
        val small = Paint().apply { isAntiAlias = true; textSize = 7.5f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL); color = 0xFF5A6066.toInt(); textAlign = Paint.Align.CENTER }
        val blue = Paint(small).apply { color = 0xFF0A78C2.toInt(); typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        val foot = Paint(small).apply { textSize = 7f; color = 0xFF808080.toInt() }
        c.drawColor(0xFFFFFFFF.toInt())
        chunk.forEachIndexed { i, p ->
            val x = mx + (i % COLS) * cw; val y = top + (i / COLS) * ch
            c.drawRoundRect(RectF(x + 4, y + 4, x + cw - 4, y + ch - 4), 10f, 10f, cut)
            val cx = x + cw / 2
            logo?.let { val lw = 80f; val lh = lw * it.height / it.width; c.drawBitmap(it, null, RectF(cx - lw / 2, y + 10, cx + lw / 2, y + 10 + lh), Paint().apply { isFilterBitmap = true; isAntiAlias = true }) }
            val q = 90f; val qy = y + 45
            QrCode.draw(c, QrCheckin.url(p.id), cx - q / 2, qy, q)
            val lay = StaticLayout.Builder.obtain(p.title, 0, p.title.length, name, (cw - 24).toInt()).setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            c.save(); c.translate(x + 12, qy + q + 5); lay.draw(c); c.restore()
            val ty = qy + q + 5 + lay.height + 9
            c.drawText("ID " + p.id + (if (p.country.isNotBlank()) " · " + p.country else ""), cx, ty, small)
            c.drawText("Scan to check in · Changanua uingie", cx, ty + 11, blue)
        }
        c.drawText("RAFIKI Pin · QR check-in stickers · page $pageNo of $pages · cut along the dotted lines", W / 2, H - 20, foot)
    }

    fun share(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, "org.rafikioutreach.pin.files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** One sticker as it will print, for the preview. */
@Composable
fun QrStickerCard(p: Project, modifier: Modifier = Modifier) {
    val bmp = remember(p.id) { QrCode.bitmap(QrCheckin.url(p.id), 6, 1).asImageBitmap() }
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, Color(0xFFB8C4CE), RoundedCornerShape(16.dp)).padding(14.dp).testTag("qr_sticker"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.letterhead_logo), "RAFIKI Outreach", Modifier.width(110.dp), contentScale = ContentScale.FillWidth)
        Image(bmp, t("QR code for ${p.title}", "Msimbo wa QR wa ${p.title}"), Modifier.padding(top = 8.dp).size(132.dp), filterQuality = FilterQuality.None)
        Text(p.title, color = Color(0xFF04263F), fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text("ID ${p.id}" + if (p.country.isNotBlank()) " · ${p.country}" else "", color = Color(0xFF5A6066), fontSize = 11.sp)
        Text("Scan to check in · Changanua uingie", color = Color(0xFF0A78C2), fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Office › QR codes: pick projects (by country, all or none), see a sticker, share the A4 sheet as a PDF to print. How it works is folded away. */
@Composable
fun QrStickerSection(m: AppModel, snap: Snapshot) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ck by remember { mutableStateOf(m.prefs.country) }
    val list = snap.projects.filter { ck.isBlank() || it.ck == ck }.sortedBy { it.title.lowercase() }
    val picked = remember { mutableStateMapOf<String, Boolean>() }
    val chosen = list.filter { picked[it.id] != false }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val hu = C.hue("qr")
    val pages = (chosen.size + QrStickers.COLS * QrStickers.ROWS - 1) / (QrStickers.COLS * QrStickers.ROWS)
    DqmHead("qr_head", "qr", "teal", t("QR check-in stickers", "Vibandiko vya QR vya kuingia"), t("Print one for each project and fix it at the site.", "Chapisha kimoja kwa kila mradi na ukibandike eneo la mradi."),
        listOf(DqmNum(chosen.size, t("chosen", "vimechaguliwa"), if (chosen.isEmpty()) "off" else ""), DqmNum(pages, if (pages == 1) t("A4 page", "ukurasa A4") else t("A4 pages", "kurasa A4"), if (pages == 0) "off" else "")))
    val counts = snap.projects.groupingBy { it.ck }.eachCount()
    Row(Modifier.padding(top = 14.dp, bottom = 4.dp).fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (listOf("" to t("All countries", "Nchi zote")) + COUNTRIES.map { it.key to it.value }).filter { (k, _) -> k.isBlank() || (counts[k] ?: 0) > 0 || k == ck }.forEach { (k, l) ->
            Chip("$l · ${if (k.isBlank()) snap.projects.size else counts[k] ?: 0}", if (k.isBlank()) "globe" else null, ck == k, { ck = k }, tag = "qr_ck_$k", hue = "qr")
        }
    }
    Card(Modifier.padding(top = 10.dp), pad = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("${chosen.size} of ${list.size} chosen", "${chosen.size} kati ya ${list.size} zimechaguliwa"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f).padding(start = 4.dp).testTag("qr_count"))
            Chip(t("All", "Zote"), null, false, { list.forEach { picked[it.id] = true } }, tag = "qr_all", hue = "qr")
            Spacer(Modifier.width(6.dp))
            Chip(t("None", "Hakuna"), null, false, { list.forEach { picked[it.id] = false } }, tag = "qr_none", hue = "qr")
        }
        HorizontalDivider(color = C.line, modifier = Modifier.padding(top = 10.dp))
        if (list.isEmpty()) Text(t("No projects in this country yet.", "Bado hakuna miradi katika nchi hii."), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(8.dp))
        list.take(200).forEachIndexed { i, p ->
            val on = picked[p.id] != false
            if (i > 0) HorizontalDivider(color = C.line)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Checkbox) { picked[p.id] = !on }.heightIn(min = 56.dp).padding(vertical = 4.dp).testTag("qr_p_${p.id}"), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, { picked[p.id] = it }, colors = CheckboxDefaults.colors(checkedColor = hu.fg))
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.ink)
                    Text("ID ${p.id}" + if (p.area.isNotBlank()) " · ${p.area}" else "", style = MaterialTheme.typography.bodySmall, color = C.muted)
                }
            }
        }
        if (list.size > 200) Text(t("Showing the first 200. Choose a country to see the rest.", "Inaonyesha 200 za kwanza. Chagua nchi kuona zilizobaki."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(8.dp))
    }
    Card(Modifier.padding(top = 12.dp).testTag("qr_preview"), pad = 16.dp) {
        val p = chosen.firstOrNull()
        if (p != null) {
            Text(t("How a sticker looks", "Jinsi kibandiko kinavyoonekana"), style = MaterialTheme.typography.titleSmall, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrStickerCard(p, Modifier.width(220.dp)) }
        } else Text(t("Choose at least one project to see a sticker.", "Chagua angalau mradi mmoja kuona kibandiko."), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(t("${chosen.size} " + (if (chosen.size == 1) "sticker" else "stickers") + " on $pages A4 " + (if (pages == 1) "page" else "pages"), "Vibandiko ${chosen.size} kwenye kurasa $pages za A4"), style = MaterialTheme.typography.titleSmall, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        Btn(if (busy) t("Making the PDF…", "Inatengeneza PDF…") else t("Share the PDF to print", "Shiriki PDF ya kuchapisha"), {
            busy = true; msg = null
            scope.launch {
                val f = runCatching { withContext(Dispatchers.IO) { QrStickers.pdf(ctx, chosen, File(ctx.cacheDir, "exports/RAFIKI-QR-stickers.pdf")) } }.getOrNull()
                busy = false
                if (f == null) msg = t("Couldn’t make the PDF. Please try again.", "Imeshindwa kutengeneza PDF. Tafadhali jaribu tena.")
                else runCatching { QrStickers.share(ctx, f) }.onFailure { msg = t("No app to share the PDF with.", "Hakuna programu ya kushiriki PDF.") }
            }
        }, Modifier.fillMaxWidth().padding(top = 12.dp).testTag("qr_pdf"), icon = "share", enabled = chosen.isNotEmpty() && !busy)
        msg?.let { Msg(it, "bad") }
    }
    Card(Modifier.padding(top = 12.dp), pad = 4.dp) {
        DqmFold("qr_how", "info", "blue", t("How it works", "Jinsi inavyofanya kazi"), t("Scan it to open the Site visit", "Kichanganue kufungua Ziara"), "has") {
            Text(t("Print one for each project and fix it at the site (pump house, sign or office door). Scanning it opens the project’s Site visit, and monday.com notes the visit was checked in by QR.",
                "Chapisha kimoja kwa kila mradi na ukibandike eneo la mradi (nyumba ya pampu, bango au mlango wa ofisi). Kukichanganua kunafungua Ziara ya mradi, na monday.com inaandika kuwa ziara iliingizwa kwa QR."), style = MaterialTheme.typography.bodyMedium, color = C.text)
            Text(t("Share the PDF to a printer or a computer, print it on A4 at 100% and cut along the dotted lines.", "Shiriki PDF kwa printa au kompyuta, ichapishe kwenye A4 kwa ukubwa 100% na ukate kwenye mistari ya nukta."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Scan QR: Google's camera scanner, a photo of the sticker, or the project number typed in. */
@Composable
fun ScanScreen(m: AppModel, opened: (String) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val snap by m.snap.collectAsState()
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var typed by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun handle(text: String?, byHand: Boolean = false) {
        if (text == null) return
        val id = QrCheckin.parse(text)
        if (id == null) { msg = "bad" to t("That QR code isn’t a RAFIKI project sticker.", "Msimbo huo wa QR si kibandiko cha mradi wa RAFIKI."); return }
        if (snap?.projects?.none { it.id == id } != false) { msg = "warn" to t("Project $id isn’t in your list. Pull down on Projects to refresh, then scan again.", "Mradi $id haupo kwenye orodha yako. Vuta chini kwenye Miradi kuonyesha upya, kisha changanua tena."); return }
        if (!byHand) QrCheckin.mark(id) // a typed number isn't a scan
        msg = null; opened(id)
    }
    fun fromImage(load: () -> Bitmap?) {
        scope.launch {
            val txt = withContext(Dispatchers.Default) { runCatching { load()?.let { QrCode.decode(it) } }.getOrNull() }
            if (txt == null) msg = "bad" to t("No QR code found in that photo. Hold the phone closer, keep the code flat and in good light, then try again.", "Hakuna msimbo wa QR kwenye picha hiyo. Shika simu karibu zaidi, msimbo uwe tambarare na kwenye mwanga mzuri, kisha jaribu tena.")
            else handle(txt)
        }
    }
    val shot = remember { File(File(ctx.cacheDir, "cam").apply { mkdirs() }, "qr.jpg") }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) fromImage { BitmapFactory.decodeFile(shot.path) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) fromImage { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } } }
    ShowInBar(m, t("Scan QR", "Changanua QR"), true)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp).testTag("scan_screen")) {
        Text(t("Scan the RAFIKI sticker at the site. The project’s Site visit opens, checked in.", "Changanua kibandiko cha RAFIKI kilicho eneo la mradi. Ziara ya mradi inafunguka, ukiwa umeingia."), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp))
        val hu = C.hue("scan")
        Card {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(150.dp).clip(RoundedCornerShape(32.dp)).background(hu.bg).ambient(), contentAlignment = Alignment.Center) {
                    Icon(Ic.get("scan"), null, tint = hu.fg, modifier = Modifier.size(96.dp))
                    Icon(Ic.get("qr"), null, tint = hu.fg, modifier = Modifier.size(46.dp))
                }
            }
            Btn(t("Scan with the camera", "Changanua kwa kamera"), {
                msg = null
                QrCheckin.scanner(ctx, { handle(it) }) { msg = "warn" to t("The camera scanner isn’t ready on this phone (it needs Google Play services). Take a photo of the code instead.", "Kichanganuzi cha kamera hakipo tayari kwenye simu hii (kinahitaji Google Play services). Piga picha ya msimbo badala yake.") }
            }, Modifier.fillMaxWidth().padding(top = 16.dp).testTag("scan_cam"), icon = "scan")
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(t("Photo of the code", "Picha ya msimbo"), {
                    msg = null
                    runCatching { cam.launch(FileProvider.getUriForFile(ctx, "org.rafikioutreach.pin.files", shot)) }.onFailure { msg = "bad" to t("Couldn’t open the camera.", "Imeshindwa kufungua kamera.") }
                }, Modifier.weight(1f).testTag("scan_photo"), icon = "camera", light = true, small = true)
                Btn(t("Choose a photo", "Chagua picha"), { msg = null; runCatching { pick.launch("image/*") } }, Modifier.weight(1f).testTag("scan_pick"), icon = "upload", light = true, small = true)
            }
            msg?.let { (tone, text) -> Msg(text, tone) }
        }
        Card(Modifier.padding(top = 12.dp)) {
            Text(t("No sticker, or it won’t scan?", "Hakuna kibandiko, au hakichanganuliki?"), style = MaterialTheme.typography.titleSmall, color = C.ink)
            Field(typed, { typed = it.filter { c -> c.isDigit() } }, t("Project number (on the sticker)", "Namba ya mradi (kwenye kibandiko)"), Modifier.testTag("scan_id"), placeholder = t("e.g. 1234567890", "mf. 1234567890"), number = true)
            Btn(t("Open", "Fungua"), { handle(typed, byHand = true) }, Modifier.fillMaxWidth().padding(top = 12.dp).testTag("scan_open"), light = true, enabled = typed.isNotBlank())
            Text(t("Typing the number opens the visit too, but it isn’t marked as checked in by QR.", "Kuandika namba kunafungua ziara pia, lakini haiandikwi kuwa umeingia kwa QR."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** In the Site visit: "Checked in by QR code" when the sticker was scanned. */
@Composable
fun QrCheckedIn(id: String) {
    if (!QrCheckin.isFor(id)) return
    val hu = C.hue("qr")
    Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(hu.bg).padding(horizontal = 12.dp, vertical = 10.dp).testTag("qr_checked"), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.get("qr"), null, tint = hu.fg, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
        Text(t("Checked in by scanning the site’s QR code", "Umeingia kwa kuchanganua msimbo wa QR wa eneo"), color = hu.fg, style = MaterialTheme.typography.labelLarge)
    }
}
