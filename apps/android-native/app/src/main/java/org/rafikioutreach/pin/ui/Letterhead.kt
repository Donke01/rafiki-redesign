package org.rafikioutreach.pin.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import org.rafikioutreach.pin.R

/**
 * Letterhead address on reports, chosen by the home country of the person making the report.
 * Kenya team: the Kitale office (as on rafikioutreach.org/contact). Everyone else: the US head office, as on the printed letterhead.
 * To give another country its own address, add one line here (e.g. "tz" to listOf(...)).
 */
val LETTERHEAD: Map<String, List<String>> = mapOf(
    "ke" to listOf("Rafiki Outreach", "Mega Centre, 3rd Floor, Makasembo Road", "Kitale, Kenya", "Phone: +254 791 674 001", "Email: info@rafikioutreach.org"),
    "default" to listOf("Rafiki Outreach", "1817 N. Beck Street", "Salt Lake City, UT 84116, U.S.A.", "Phone: +1 801 547 7847", "Email: Info@rafikioutreach.org"),
)
fun letterheadAddress(home: String): List<String> = LETTERHEAD[home] ?: LETTERHEAD.getValue("default")
const val LETTERHEAD_FOOT = "501(c)(3) Nonprofit Organization  |  Providing clean water and community development in East Africa"

// Page geometry in PDF points, measured from the RAFIKI letterhead (0.6in side margins; logo 2.8in wide, 22pt from the top;
// address 9pt, 12pt apart; navy rule 1.5pt; seal 1.1in square 0.5in from the bottom; thin rule and grey line at the foot).
private const val M = 43.2f
private const val NAVY = 0xFF1A365D.toInt()
/** Where the report text may start on page 1 (first baseline) and where it must stop on every page (above the seal). */
const val LETTERHEAD_BODY_TOP = 146f
const val LETTERHEAD_BODY_BOTTOM = 712f

private fun art(ctx: Context, id: Int): Bitmap? = runCatching { BitmapFactory.decodeResource(ctx.resources, id) }.getOrNull()

/** Logo, address and navy rule across the top of the first page. */
fun drawLetterheadTop(ctx: Context, c: Canvas, home: String, pageW: Float = 595f) {
    val img = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    art(ctx, R.drawable.letterhead_logo)?.let { c.drawBitmap(it, null, RectF(M, 22f, M + 201.6f, 22f + 67.96f), img) }
    val adr = Paint().apply { isAntiAlias = true; textSize = 9f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL); color = 0xFF000000.toInt(); textAlign = Paint.Align.RIGHT }
    letterheadAddress(home).forEachIndexed { i, s -> c.drawText(s, pageW - M, 54f + 12f * i, adr) }
    c.drawLine(M, 104.4f, pageW - M, 104.4f, Paint().apply { isAntiAlias = true; color = NAVY; strokeWidth = 1.5f })
}

/** Seal, thin navy rule and the grey nonprofit line at the foot of every page. */
fun drawLetterheadFoot(ctx: Context, c: Canvas, pageW: Float = 595f, pageH: Float = 842f) {
    val sealR = pageW - 50.4f
    art(ctx, R.drawable.letterhead_seal)?.let { c.drawBitmap(it, null, RectF(sealR - 79.2f, pageH - 36f - 79.2f, sealR, pageH - 36f), Paint().apply { isAntiAlias = true; isFilterBitmap = true }) }
    c.drawLine(M, pageH - 36f, sealR - 79.2f - 21.6f, pageH - 36f, Paint().apply { isAntiAlias = true; color = NAVY; strokeWidth = 0.75f })
    c.drawText(LETTERHEAD_FOOT, M, pageH - 25.2f, Paint().apply { isAntiAlias = true; textSize = 8f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL); color = 0xFF808080.toInt() })
}
