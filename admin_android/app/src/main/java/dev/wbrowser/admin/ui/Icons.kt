package dev.wbrowser.admin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The same 24px stroke icons as the web console (firebase/public/admin/app.js), drawn on a Canvas. */
private val ICONS = mapOf(
    "grid" to "M3 3h7v9H3z|M14 3h7v5h-7z|M14 12h7v9h-7z|M3 16h7v5H3z",
    "globe" to "M12 2a10 10 0 100 20 10 10 0 000-20z|M2 12h20|M12 2c3 3 3 17 0 20|M12 2c-3 3-3 17 0 20",
    "clock" to "M12 2a10 10 0 100 20 10 10 0 000-20z|M12 6v6l4 2",
    "users" to "M17 21v-2a4 4 0 00-4-4H7a4 4 0 00-4 4v2|M10 11a4 4 0 100-8 4 4 0 000 8z|M21 21v-2a4 4 0 00-3-3.9|M16 3.1a4 4 0 010 7.8",
    "user" to "M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2|M12 11a4 4 0 100-8 4 4 0 000 8z",
    "userplus" to "M16 21v-2a4 4 0 00-4-4H6a4 4 0 00-4 4v2|M9 11a4 4 0 100-8 4 4 0 000 8z|M19 8v6|M22 11h-6",
    "activity" to "M22 12h-4l-3 9L9 3l-3 9H2",
    "shield" to "M12 2l8 3v6c0 5-3.5 9-8 11-4.5-2-8-6-8-11V5z|M9 12l2 2 4-4",
    "list" to "M8 6h13|M8 12h13|M8 18h13|M3 6h.01|M3 12h.01|M3 18h.01",
    "layers" to "M12 2l10 5-10 5L2 7z|M2 17l10 5 10-5|M2 12l10 5 10-5",
    "out" to "M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4|M16 17l5-5-5-5|M21 12H9",
    "refresh" to "M21 12a9 9 0 11-3-6.7|M21 3v6h-6",
    "lock" to "M5 11h14v10H5z|M8 11V7a4 4 0 018 0v4",
    "check" to "M20 6L9 17l-5-5",
    "mail" to "M4 4h16a2 2 0 012 2v12a2 2 0 01-2 2H4a2 2 0 01-2-2V6a2 2 0 012-2z|M22 6l-10 7L2 6",
    "eye" to "M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z|M12 15a3 3 0 100-6 3 3 0 000 6z",
    "back" to "M19 12H5|M12 19l-7-7 7-7",
    "chev" to "M9 18l6-6-6-6",
    "trash" to "M3 6h18|M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2|M19 6l-1 14a2 2 0 01-2 2H8a2 2 0 01-2-2L5 6",
    "pause" to "M6 4h4v16H6z|M14 4h4v16h-4z",
    "download" to "M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4|M7 10l5 5 5-5|M12 15V3",
    "search" to "M11 19a8 8 0 100-16 8 8 0 000 16z|M21 21l-4.3-4.3",
    "db" to "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z|M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6|M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6",
    "gear" to "M12 15a3 3 0 100-6 3 3 0 000 6z|M19.4 15a1.7 1.7 0 00.3 1.8l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.8-.3 1.7 1.7 0 00-1 1.5V21a2 2 0 11-4 0v-.1a1.7 1.7 0 00-1.1-1.5 1.7 1.7 0 00-1.8.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.7 1.7 0 00.3-1.8 1.7 1.7 0 00-1.5-1H3a2 2 0 110-4h.1a1.7 1.7 0 001.5-1.1 1.7 1.7 0 00-.3-1.8l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.8.3H9a1.7 1.7 0 001-1.5V3a2 2 0 114 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.8-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.8V9a1.7 1.7 0 001.5 1H21a2 2 0 110 4h-.1a1.7 1.7 0 00-1.5 1z",
)

/**
 * Re-writes an SVG path so every number is space separated. SVG allows packed arc flags ("a10 10 0 100 20"),
 * which Compose's PathParser reads wrongly, so arcs are tokenised by hand here.
 */
private fun spaced(d: String): String {
    val out = StringBuilder()
    var i = 0
    var cmd = ' '
    var argIdx = 0
    fun number(): String {
        val st = i
        if (d[i] == '-' || d[i] == '+') i++
        var dot = false
        while (i < d.length && (d[i].isDigit() || (d[i] == '.' && !dot))) { if (d[i] == '.') dot = true; i++ }
        return d.substring(st, i)
    }
    while (i < d.length) {
        val c = d[i]
        when {
            c.isLetter() -> { cmd = c; argIdx = 0; out.append(c).append(' '); i++ }
            c == ' ' || c == ',' -> i++
            else -> {
                val arc = cmd == 'a' || cmd == 'A'
                if (arc && (argIdx % 7 == 3 || argIdx % 7 == 4)) { out.append(d[i]).append(' '); i++ } else out.append(number()).append(' ')
                argIdx++
            }
        }
    }
    return out.toString()
}

@Composable
fun Ico(name: String, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier, strokeWidth: Float = 1.9f) {
    val paths = remember(name) { (ICONS[name] ?: "").split('|').filter { it.isNotEmpty() }.map { PathParser().parsePathString(spaced(it)).toPath() } }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(scale = s, pivot = Offset.Zero) {
            paths.forEach { drawPath(it, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}
