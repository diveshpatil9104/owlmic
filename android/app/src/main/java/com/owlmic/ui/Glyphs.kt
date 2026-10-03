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
import androidx.compose.ui.unit.dp
import com.owlmic.core.design.Tokens

/** The Lucide icons Owlmic uses (section 29.4), drawn from their path data at a 1.5 stroke. */
enum class Glyph(vararg val paths: String) {
    MIC("M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 19v3"),
    CAMERA(
        "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z",
        "M9 13a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
    ),
    SPEAKER("M11 5L6 9H2v6h4l5 4V5z", "M15.54 8.46a5 5 0 0 1 0 7.07", "M19.07 4.93a10 10 0 0 1 0 14.14"),
    SETTINGS(
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
        "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
    ),
    FLIP(
        "M11 19H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h5", "M13 5h7a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-5",
        "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0", "M18 22l-3-3 3-3", "M6 2l3 3-3 3",
    ),
    CLOSE("M18 6L6 18", "M6 6l12 12"),
    UNPLUG(
        "M19 5l3-3", "M2 22l3-3", "M6.3 20.3a2.4 2.4 0 0 0 3.4 0L12 18l-6-6-2.3 2.3a2.4 2.4 0 0 0 0 3.4z",
        "M7.5 13.5L10 11", "M10.5 16.5L13 14", "M12 6l6 6 2.3-2.3a2.4 2.4 0 0 0 0-3.4l-2.6-2.6a2.4 2.4 0 0 0-3.4 0z",
    ),
    USB(
        "M9 7a1 1 0 1 0 2 0a1 1 0 1 0-2 0", "M3 20a1 1 0 1 0 2 0a1 1 0 1 0-2 0", "M4.7 19.3L19 5", "M21 3l-3 1 2 2z",
        "M9.26 7.68L5 12l2 5", "M10 14l5 2 3.5-3.5", "M18 12l1-1 1 1-1 1z",
    ),
    CABLE(
        "M17 21v-2a1 1 0 0 1-1-1v-1a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v1a1 1 0 0 1-1 1", "M19 15V6.5a1 1 0 0 0-7 0v11a1 1 0 0 1-7 0V9",
        "M21 21v-2h-4", "M3 5h4V3", "M7 5a1 1 0 0 1 1 1v1a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a1 1 0 0 1 1-1V3",
    ),
    WIFI("M12 20h.01", "M2 8.82a15 15 0 0 1 20 0", "M5 12.859a10 10 0 0 1 14 0", "M8.5 16.429a5 5 0 0 1 7 0"),
    BLUETOOTH("M7 7l10 10-5 5V2l5 5L7 17"),
    ;

    val vector: ImageVector by lazy {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    PathParser().parsePathString(it).toNodes(),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = Tokens.Icon.STROKE,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
    }
}

@Composable
fun Icon(glyph: Glyph, color: Color, label: String?, modifier: Modifier = Modifier) {
    Image(rememberVectorPainter(glyph.vector), label, modifier.size(Theme.icon), colorFilter = ColorFilter.tint(color))
}
