package com.owlmic.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.owlmic.core.design.Tokens

/** The Lucide icons Owlmic uses (section 29.4); the main screen's come from the prototype in landing/proto-templates. */
enum class Glyph(vararg val paths: String) {
    MIC("M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 19v3"),
    MIC_OFF(
        "M1 1l22 22", "M9 9v3a3 3 0 0 0 5.12 2.12M15 9.34V4a3 3 0 0 0-5.94 -.6",
        "M17 16.95A7 7 0 0 1 5 12v-2m14 0v2a7 7 0 0 1 -.11 1.23", "M12 19v3",
    ),
    CAMERA(
        "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z",
        "M9 13a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
    ),
    CAMERA_OFF(
        "M1 1l22 22", "M21 21H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h3m3-3h6l2.5 3H21a2 2 0 0 1 2 2v7.5",
        "M9 13a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
    ),
    SPEAKER("M11 5L6 9H2v6h4l5 4V5z", "M15.54 8.46a5 5 0 0 1 0 7.07", "M19.07 4.93a10 10 0 0 1 0 14.14"),
    SPEAKER_OFF("M11 5L6 9H2v6h4l5 4V5z", "M23 9l-6 6", "M17 9l6 6"),
    FLIP(
        "M11 19H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h5", "M13 5h7a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-5",
        "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0", "M18 22l-3-3 3-3", "M6 2l3 3-3 3",
    ),
    CLOSE("M18 6L6 18", "M6 6l12 12"),
    BACK("M19 12H5", "M12 19l-7-7 7-7"),
    HELP("M9.09 9.5a3 3 0 0 1 5.82 1c0 2-2.91 3-2.91 3.5", "M12 17.5h.01"),
    OPEN("M7 17L17 7", "M7 7h10v10"),
    NEXT("M5 12h14", "M12 5l7 7-7 7"),
    UNPLUG(
        "M19 5l3-3", "M2 22l3-3", "M6.3 20.3a2.4 2.4 0 0 0 3.4 0L12 18l-6-6-2.3 2.3a2.4 2.4 0 0 0 0 3.4z",
        "M7.5 13.5L10 11", "M10.5 16.5L13 14", "M12 6l6 6 2.3-2.3a2.4 2.4 0 0 0 0-3.4l-2.6-2.6a2.4 2.4 0 0 0-3.4 0z",
    ),

    /** The links: the top-right tile's Wi-Fi, and the connection list's plug (USB debugging), chain (USB tethering), Wi-Fi. */
    WIFI("M5 10.36a10 10 0 0 1 14 0", "M8.5 13.93a5 5 0 0 1 7 0", "M12 17.5h.01"),
    PLUG("M10 13v5a2 2 0 0 0 4 0v-5", "M9 7h6a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z", "M9 7V4", "M15 7V4"),
    CHAIN(
        "M10 13a5 5 0 0 0 7.54 .54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
        "M14 11a5 5 0 0 0-7.54 -.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
    ),
    WIFI_LINK("M5 12.55a11 11 0 0 1 14.08 0", "M8.53 16.11a6 6 0 0 1 6.95 0", "M12 20h.01"),
    BLUETOOTH("M7 7l10 10-5 5V2l5 5L7 17"),
    ;

    private val vectors = HashMap<Float, ImageVector>()

    fun vector(stroke: Float): ImageVector = vectors.getOrPut(stroke) {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    PathParser().parsePathString(it).toNodes(),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
    }
}

@Composable
fun Icon(
    glyph: Glyph,
    color: Color,
    label: String?,
    modifier: Modifier = Modifier,
    size: Dp = Theme.icon,
    stroke: Float = Tokens.Icon.STROKE,
) {
    Image(rememberVectorPainter(glyph.vector(stroke)), label, modifier.size(size), colorFilter = ColorFilter.tint(color))
}
