package dev.wbrowser.admin.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wbrowser.admin.data.DateRange
import dev.wbrowser.admin.data.SiteStat
import kotlinx.coroutines.delay
import java.text.NumberFormat
import kotlin.math.ceil
import kotlin.math.max

// ---------- formatting (same rules as the web console) ----------
fun fmt(n: Number): String = NumberFormat.getInstance().format(n)

fun dur(ms: Long): String {
    val t = Math.round(ms / 1000.0)
    return when {
        t < 60 -> "${t}s"
        t < 3600 -> "${t / 60}m ${t % 60}s"
        else -> "${t / 3600}h ${(t % 3600) / 60}m"
    }
}

fun ago(ms: Long): String {
    if (ms <= 0) return "never"
    val d = System.currentTimeMillis() - ms
    return when {
        d < 60_000 -> "just now"
        d < 3_600_000 -> "${d / 60_000} min ago"
        d < 86_400_000 -> "${d / 3_600_000} h ago"
        else -> "${d / 86_400_000} d ago"
    }
}

fun initials(name: String): String =
    name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").ifEmpty { "?" }

private val AVATAR = listOf(
    Color(0xFF6366F1) to Color(0xFF8B5CF6), Color(0xFF0EA5C6) to Color(0xFF2563EB), Color(0xFF10B981) to Color(0xFF059669), Color(0xFFF59E0B) to Color(0xFFEA580C),
    Color(0xFFEC4899) to Color(0xFFBE185D), Color(0xFF14B8A6) to Color(0xFF0891B2), Color(0xFF8B5CF6) to Color(0xFFD946EF), Color(0xFF64748B) to Color(0xFF334155),
)

private fun toneOf(s: String): Pair<Color, Color> {
    var x = 0L
    for (c in s) x = (x * 31 + c.code) and 0xFFFFFFFFL
    return AVATAR[(x % 8).toInt()]
}

// ---------- motion ----------
/** Fades and rises into place; as a LazyColumn item it replays whenever it scrolls back into view. */
@Composable
fun Reveal(index: Int = 0, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(index.coerceAtMost(8) * 55L); shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(480, easing = FastOutSlowInEasing), label = "reveal")
    Box(modifier.graphicsLayer { alpha = a; translationY = (1f - a) * 48f }) { content() }
}

/** Springy press feedback for anything tappable. */
fun Modifier.pressScale(source: MutableInteractionSource, to: Float = 0.96f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) to else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

@Composable
fun AnimatedNumber(target: Long, format: (Long) -> String, style: TextStyle, color: Color) {
    val v by animateFloatAsState(target.toFloat(), tween(900, easing = FastOutSlowInEasing), label = "count")
    Text(format(v.toLong()), style = style, color = color, maxLines = 1)
}

@Composable
fun Modifier.shimmer(): Modifier {
    val p = palette()
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "x")
    return this.background(Brush.horizontalGradient(listOf(p.soft, p.line, p.soft), startX = x * 600f, endX = x * 600f + 600f), RoundedCornerShape(18.dp))
}

// ---------- surfaces ----------
@Composable
fun AdminCard(modifier: Modifier = Modifier, padding: Dp = 18.dp, content: @Composable () -> Unit) {
    val p = palette()
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = p.card, border = BorderStroke(1.dp, p.line), tonalElevation = 0.dp, shadowElevation = if (p.dark) 0.dp else 1.dp) {
        Box(Modifier.padding(padding)) { content() }
    }
}

@Composable
fun CardTitle(title: String, hint: String? = null) {
    val p = palette()
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = p.ink)
        if (hint != null) Text(hint, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = p.mut)
    }
}

enum class Tone { Pri, Cyan, Ok, Warn, Violet }

@Composable
private fun toneColors(t: Tone): Pair<Color, Color> {
    val p = palette()
    return when (t) {
        Tone.Pri -> p.pri to p.priSoft
        Tone.Cyan -> p.cyan to p.cyan.copy(alpha = 0.16f)
        Tone.Ok -> p.ok to p.okSoft
        Tone.Warn -> p.warn to p.warnSoft
        Tone.Violet -> p.violet to p.violet.copy(alpha = 0.16f)
    }
}

@Composable
fun KpiCard(icon: String, tone: Tone, value: Long, format: (Long) -> String, label: String, modifier: Modifier = Modifier) {
    val p = palette()
    val (fg, bg) = toneColors(tone)
    val src = remember { MutableInteractionSource() }
    Surface(
        modifier.pressScale(src, 0.97f).height(118.dp),
        shape = RoundedCornerShape(20.dp), color = p.card, border = BorderStroke(1.dp, p.line), shadowElevation = if (p.dark) 0.dp else 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(bg), contentAlignment = Alignment.Center) { Ico(icon, fg, 19.dp) }
            Column {
                AnimatedNumber(value, format, androidx.compose.material3.MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp), p.ink)
                Text(label, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = p.mut, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun Avatar(name: String, size: Dp = 38.dp, square: Boolean = false, text: String = initials(name)) {
    val (a, b) = remember(name) { toneOf(name) }
    Box(
        Modifier.size(size).clip(if (square) RoundedCornerShape(10.dp) else CircleShape).background(Brush.linearGradient(listOf(a, b))),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.34f).sp) }
}

@Composable
fun Pill(text: String, fg: Color, bg: Color) {
    Box(Modifier.clip(RoundedCornerShape(99.dp)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { Text(text, color = palette().mut, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium) }
}

@Composable
fun RangeChips(selected: DateRange, onSelect: (DateRange) -> Unit) {
    val p = palette()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).clip(RoundedCornerShape(16.dp)).background(p.soft).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DateRange.entries.forEach { r ->
            val on = r == selected
            val bg by animateColorAsState(if (on) p.card else Color.Transparent, tween(220), label = "chipbg")
            val fg by animateColorAsState(if (on) p.ink else p.mut, tween(220), label = "chipfg")
            val src = remember { MutableInteractionSource() }
            Box(
                Modifier.pressScale(src, 0.94f).clip(RoundedCornerShape(12.dp)).background(bg)
                    .clickable(interactionSource = src, indication = null) { onSelect(r) }.padding(horizontal = 16.dp, vertical = 9.dp),
            ) { Text(r.label, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        }
    }
}

// ---------- charts ----------
@Composable
fun AreaChart(labels: List<String>, values: List<Int>, color: Color, modifier: Modifier = Modifier) {
    if (values.isEmpty()) { Empty("No data in this range."); return }
    val p = palette()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(values) { progress.snapTo(0f); progress.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    var sel by remember(values) { mutableStateOf<Int?>(null) }
    val axis = TextStyle(fontSize = 10.sp, color = p.mut)
    val left = with(density) { 38.dp.toPx() }
    val right = with(density) { 12.dp.toPx() }
    val top = with(density) { 14.dp.toPx() }
    val bottom = with(density) { 26.dp.toPx() }
    val n = values.size
    val nice = (ceil(max(1, values.max()) / 4.0) * 4).toInt().coerceAtLeast(4)

    Canvas(
        modifier.fillMaxWidth().height(190.dp).pointerInput(values) {
            detectTapGestures { o ->
                val w = size.width - left - right
                sel = if (n == 1) 0 else (((o.x - left) / w) * (n - 1)).let { Math.round(it).toInt().coerceIn(0, n - 1) }
            }
        },
    ) {
        val w = size.width - left - right
        val h = size.height - top - bottom
        fun x(i: Int) = left + if (n == 1) w / 2 else i * w / (n - 1)
        fun y(v: Int) = top + (1f - v.toFloat() / nice) * h
        for (g in 0..4) {
            val yy = top + h - h * g / 4f
            drawLine(p.line, Offset(left, yy), Offset(size.width - right, yy), strokeWidth = 1f)
            val lbl = measurer.measure(fmt(nice * g / 4), axis)
            drawText(lbl, topLeft = Offset(left - 8f - lbl.size.width, yy - lbl.size.height / 2f))
        }
        val line = Path().apply {
            moveTo(x(0), y(values[0]))
            for (i in 1 until n) { val cx = (x(i - 1) + x(i)) / 2; cubicTo(cx, y(values[i - 1]), cx, y(values[i]), x(i), y(values[i])) }
        }
        val fill = Path().apply { addPath(line); lineTo(x(n - 1), top + h); lineTo(x(0), top + h); close() }
        clipRect(left = 0f, top = 0f, right = size.width * progress.value, bottom = size.height) {
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent), startY = top, endY = top + h))
            drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val step = ceil(n / 6.0).toInt().coerceAtLeast(1)
        for (i in 0 until n step step) {
            val lbl = measurer.measure(labels[i].takeLast(5), axis)
            drawText(lbl, topLeft = Offset(x(i) - lbl.size.width / 2f, size.height - bottom + 8f))
        }
        sel?.let { i ->
            drawLine(color.copy(alpha = 0.35f), Offset(x(i), top), Offset(x(i), top + h), strokeWidth = 1.5f)
            drawCircle(p.card, 7.dp.toPx(), Offset(x(i), y(values[i])))
            drawCircle(color, 4.5.dp.toPx(), Offset(x(i), y(values[i])))
            val tip = measurer.measure("${labels[i]}: ${fmt(values[i])}", TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold))
            val tw = tip.size.width + 24f
            val tx = (x(i) - tw / 2).coerceIn(0f, size.width - tw)
            val ty = (y(values[i]) - tip.size.height - 26f).coerceAtLeast(0f)
            drawRoundRect(Color(0xE61A1D2E), Offset(tx, ty), Size(tw, tip.size.height + 14f), CornerRadius(14f))
            drawText(tip, topLeft = Offset(tx + 12f, ty + 7f))
        }
    }
}

@Composable
fun ColumnChart(values: List<Int>, labelOf: (Int) -> String, color: Color, modifier: Modifier = Modifier) {
    val p = palette()
    val measurer = rememberTextMeasurer()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(values) { progress.snapTo(0f); progress.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
    val axis = TextStyle(fontSize = 10.sp, color = p.mut)
    val maxV = max(1, values.maxOrNull() ?: 1)
    Canvas(modifier.fillMaxWidth().height(160.dp)) {
        val left = 30.dp.toPx(); val bottom = 22.dp.toPx(); val top = 6.dp.toPx()
        val w = size.width - left; val h = size.height - bottom - top
        val bw = w / values.size
        drawLine(p.line, Offset(left, top + h), Offset(size.width, top + h), strokeWidth = 1f)
        val top1 = measurer.measure(fmt(maxV), axis)
        drawText(top1, topLeft = Offset(left - 6f - top1.size.width, top))
        values.forEachIndexed { i, v ->
            val k = (progress.value * 1.6f - i.toFloat() / values.size * 0.6f).coerceIn(0f, 1f)
            val bh = v.toFloat() / maxV * h * k
            drawRoundRect(color.copy(alpha = 0.9f), Offset(left + i * bw + 2f, top + h - bh), Size((bw - 4f).coerceAtLeast(2f), bh), CornerRadius(6f))
            if (i % 3 == 0) {
                val l = measurer.measure(labelOf(i), axis)
                drawText(l, topLeft = Offset(left + i * bw + bw / 2 - l.size.width / 2f, size.height - bottom + 5f))
            }
        }
    }
}

@Composable
fun SiteBars(sites: List<SiteStat>) {
    val p = palette()
    if (sites.isEmpty()) { Empty("No websites yet."); return }
    val maxV = max(1, sites.maxOf { it.visits })
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        sites.forEachIndexed { i, s ->
            val frac by animateFloatAsState(s.visits.toFloat() / maxV, tween(900, delayMillis = 120 + i * 60, easing = FastOutSlowInEasing), label = "bar")
            var started by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { started = true }
            val shown = if (started) frac else 0f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(s.domain, 32.dp, square = true, text = s.domain.take(1).uppercase())
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.domain, color = p.pri, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(p.soft)) {
                        Box(Modifier.fillMaxWidth(shown.coerceIn(0.03f, 1f)).fillMaxSize().clip(CircleShape).background(Brush.horizontalGradient(listOf(p.pri, p.violet))))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("${fmt(s.visits)} visits", color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("${dur(s.totalMs)} · ${fmt(s.users)} user${if (s.users == 1) "" else "s"}", color = p.mut, fontSize = 11.sp)
                }
            }
        }
    }
}
