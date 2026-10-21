package org.rafikioutreach.pin.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The app's line icons: the same drawings as the web app, 24×24, drawn as strokes. */
object Ic {
    private val cache = HashMap<String, ImageVector>()

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"
    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r},$y h${w - 2 * r} a$r,$r 0 0 1 $r,$r v${h - 2 * r} a$r,$r 0 0 1 -$r,$r h${-(w - 2 * r)} a$r,$r 0 0 1 -$r,-$r v${-(h - 2 * r)} a$r,$r 0 0 1 $r,-$r z"

    private val D: Map<String, List<String>> = mapOf(
        "pin" to listOf("M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21z", circle(12f, 9.5f, 2.6f)),
        "pinq" to listOf("M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21z", "M10.3 8.4a1.9 1.9 0 1 1 2.6 1.8c-.6.3-.9.7-.9 1.3M12 13.6v.1"),
        "drop" to listOf("M12 3.5s6 6.6 6 11a6 6 0 0 1-12 0c0-4.4 6-11 6-11z"),
        "wrench" to listOf("M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.6 2.6-2.4-.6-.6-2.4z"),
        "cal" to listOf(rect(3.5f, 5f, 17f, 15.5f, 2.5f), "M3.5 10h17M8 3v4M16 3v4", "M12 13.2v2.6l1.8 1.1"),
        "clip" to listOf(rect(5f, 4.5f, 14f, 16.5f, 2.5f), "M9 4.5V3.5h6v1M8.5 12l2.2 2.2L15.5 9.5"),
        "people" to listOf(circle(9f, 8.5f, 3.2f), "M3.5 19.5c.6-3.2 2.8-5 5.5-5s4.9 1.8 5.5 5", circle(17f, 9.5f, 2.4f), "M15.8 14.6c2.4.2 4 1.8 4.6 4.4"),
        "person" to listOf(circle(12f, 8f, 3.6f), "M5 20c.8-3.8 3.6-6 7-6s6.2 2.2 7 6"),
        "map" to listOf("M3.5 6.5l5.5-2.5 6 2.5 5.5-2.5v13.5L15 20l-6-2.5-5.5 2.5z", "M9 4v13.5M15 6.5V20"),
        "mic" to listOf(rect(9f, 3.5f, 6f, 11f, 3f), "M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v3"),
        "camera" to listOf("M4 8.5h3l1.6-2.5h6.8L17 8.5h3v10.5H4z", circle(12f, 13.5f, 3.3f)),
        "compass" to listOf(circle(12f, 12f, 8.5f), "M15.5 8.5l-2 5-5 2 2-5z"),
        "search" to listOf(circle(11f, 11f, 6.5f), "M16 16l4 4"),
        "list" to listOf("M9 6.5h11M9 12h11M9 17.5h11", circle(4.8f, 6.5f, 1.2f), circle(4.8f, 12f, 1.2f), circle(4.8f, 17.5f, 1.2f)),
        "plus" to listOf("M12 5v14M5 12h14"),
        "undo" to listOf("M9 7L4.5 11.5 9 16", "M4.5 11.5H15a4.5 4.5 0 0 1 0 9h-2"),
        "check" to listOf("M5 12.5l4.5 4.5L19 7.5"),
        "okc" to listOf(circle(12f, 12f, 8.5f), "M8 12.3l2.8 2.8L16.2 9.6"),
        "xc" to listOf(circle(12f, 12f, 8.5f), "M9 9l6 6M15 9l-6 6"),
        "x" to listOf("M6 6l12 12M18 6L6 18"),
        "alert" to listOf("M12 4l9 16H3z", "M12 10v4.5M12 17.6v.1"),
        "back" to listOf("M19 12H5M11 6l-6 6 6 6"),
        "arrow" to listOf("M5 12h14M13 6l6 6-6 6"),
        "lock" to listOf(rect(5f, 10.5f, 14f, 10f, 2.5f), "M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5"),
        "refresh" to listOf("M20 12a8 8 0 1 1-2.3-5.7", "M20 4v4.5h-4.5"),
        "target" to listOf(circle(12f, 12f, 7f), circle(12f, 12f, 1.5f), "M12 2v3M12 19v3M2 12h3M19 12h3"),
        "layers" to listOf("M12 4l9 5-9 5-9-5z", "M3 14l9 5 9-5"),
        "del" to listOf("M9 5h11v14H9l-6-7z", "M12.5 9.5l5 5M17.5 9.5l-5 5"),
        "clock" to listOf(circle(12f, 12f, 8.5f), "M12 7.5V12l3 2"),
        "upload" to listOf("M12 20V9M7 13.5l5-5 5 5M5 4h14"),
        "inbox" to listOf("M4 13l2.5-7.5h11L20 13v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z", "M4 13h4.5l1.5 2.5h4l1.5-2.5H20"),
        "spark" to listOf("M12 3.5l1.9 5.6 5.6 1.9-5.6 1.9L12 18.5l-1.9-5.6L4.5 11l5.6-1.9z"),
        "chev" to listOf("M9 6l6 6-6 6"),
        "finger" to listOf("M7.5 5.5A8 8 0 0 1 20 12v1.5", "M4 9.5A8 8 0 0 1 5.5 7", "M4 12.5v.5a8 8 0 0 0 1.5 4.7", "M8 12a4 4 0 0 1 8 0v1.5a10 10 0 0 1-1.6 5.4", "M12 12v1.5a14 14 0 0 1-2.7 8", "M8 15.5a11 11 0 0 1-.9 3.6", "M16.6 21a14 14 0 0 0 1-3.5"),
        "phone" to listOf("M6.6 3.5h2.6l1.4 4-2 1.4a11 11 0 0 0 6.5 6.5l1.4-2 4 1.4v2.6a2 2 0 0 1-2.2 2A16.5 16.5 0 0 1 4.6 5.7a2 2 0 0 1 2-2.2z"),
        "chat" to listOf("M4.5 19.5l1.2-3.6A7.8 7.8 0 1 1 8.6 18.6z", "M9 10.5h6M9 13.5h4"),
        "down" to listOf("M6 9l6 6 6-6"),
        "share" to listOf(circle(18f, 5.5f, 2.5f), circle(6f, 12f, 2.5f), circle(18f, 18.5f, 2.5f), "M8.2 10.8l7.6-4.1M8.2 13.2l7.6 4.1"),
        "sun" to listOf(circle(12f, 12f, 4f), "M12 2.5v2M12 19.5v2M4.6 4.6l1.4 1.4M18 18l1.4 1.4M2.5 12h2M19.5 12h2M4.6 19.4 6 18M18 6l1.4-1.4"),
        "globe" to listOf(circle(12f, 12f, 9f), "M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"),
        "nav" to listOf("M3 11l18-8-8 18-2-8z"),
        "file" to listOf("M14 2.5H6.5a2 2 0 0 0-2 2v15a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V8z", "M14 2.5V8h5.5M15.5 13h-7M15.5 17h-7"),
        "link" to listOf("M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7", "M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7"),
        "flame" to listOf("M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.4-.5-2-1-3-1.1-2.1-.2-4 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.2.4-2.3 1-3a2.5 2.5 0 0 0 2.5 2.5z"),
        "text" to listOf("M4 18 9 6l5 12M5.6 14h6.8M15 18l3-7 3 7M15.9 16h4.2"),
    )

    fun get(name: String): ImageVector = cache.getOrPut(name) {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            (D[name] ?: D.getValue("drop")).forEach { d ->
                addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()
    }

    /** A filled water drop, for the logo mark. */
    val dropFill: ImageVector by lazy {
        ImageVector.Builder("dropFill", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(addPathNodes(D.getValue("drop")[0]), fill = SolidColor(Color.White))
        }.build()
    }
}
