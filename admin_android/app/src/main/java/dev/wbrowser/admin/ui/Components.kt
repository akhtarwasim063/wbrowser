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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
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

/** Medium date + short time in the phone's locale, e.g. "Nov 20, 2026, 3:05 PM". */
fun dt(ms: Long): String = if (ms <= 0) "—" else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(ms))

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
    Box(modifier.graphicsLayer { alpha = a; translationY = (1f - a) * 40f }) { content() }
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
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
    return this.clip(RoundedCornerShape(20.dp))
        .background(Brush.horizontalGradient(listOf(p.glassFill, p.glassRimTop.copy(alpha = 0.5f), p.glassFill), startX = x * 600f, endX = x * 600f + 600f))
}

// ---------- surfaces ----------
@Composable
fun AdminCard(modifier: Modifier = Modifier, padding: Dp = 14.dp, content: @Composable () -> Unit) = GlassCard(modifier, padding, content = content)

@Composable
fun CardTitle(title: String, hint: String? = null) {
    val p = palette()
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = p.ink)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = p.mut)
    }
}

enum class Tone { Pri, Cyan, Ok, Warn, Violet }

@Composable
private fun toneColor(t: Tone): Color {
    val p = palette()
    return when (t) { Tone.Pri -> p.pri; Tone.Cyan -> p.cyan; Tone.Ok -> p.ok; Tone.Warn -> p.warn; Tone.Violet -> p.violet }
}

/** Compact stat tile: icon on the left, number and label on the right. */
@Composable
fun KpiCard(icon: String, tone: Tone, value: Long, format: (Long) -> String, label: String, modifier: Modifier = Modifier) {
    val p = palette()
    val fg = toneColor(tone)
    val src = remember { MutableInteractionSource() }
    Box(modifier.pressScale(src, 0.97f).height(74.dp).glass(RoundedCornerShape(18.dp), lift = 6.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(listOf(fg.copy(alpha = 0.32f), fg.copy(alpha = 0.10f)))),
                contentAlignment = Alignment.Center,
            ) { Ico(icon, fg, 17.dp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                AnimatedNumber(value, format, MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 22.sp), p.ink)
                Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, lineHeight = 13.sp), color = p.mut, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun Avatar(name: String, size: Dp = 34.dp, square: Boolean = false, text: String = initials(name)) {
    val (a, b) = remember(name) { toneOf(name) }
    Box(
        Modifier.size(size).clip(if (square) RoundedCornerShape(9.dp) else CircleShape).background(Brush.linearGradient(listOf(a, b))),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp) }
}

@Composable
fun Pill(text: String, fg: Color, bg: Color) {
    Box(Modifier.clip(RoundedCornerShape(99.dp)).background(bg).padding(horizontal = 9.dp, vertical = 2.dp)) {
        Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { Text(text, color = palette().mut, style = MaterialTheme.typography.bodyMedium) }
}

/** Glass segmented control with a brand-coloured selected pill. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val p = palette()
    Row(
        modifier.glass(RoundedCornerShape(15.dp), lift = 4.dp).horizontalScroll(rememberScrollState()).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            val a by animateFloatAsState(if (on) 1f else 0f, tween(260), label = "segbg")
            val fg by animateColorAsState(if (on) Color.White else p.mut, tween(220), label = "segfg")
            val src = remember { MutableInteractionSource() }
            Box(
                Modifier.pressScale(src, 0.94f).clip(RoundedCornerShape(12.dp))
                    .background(Brush.horizontalGradient(listOf(p.pri, p.pri2)), alpha = a)
                    .clickable(interactionSource = src, indication = null) { onSelect(o) }.padding(horizontal = 14.dp, vertical = 7.dp),
            ) { Text(label(o), color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
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
    val axis = TextStyle(fontSize = 9.sp, color = p.mut)
    val left = with(density) { 30.dp.toPx() }
    val right = with(density) { 6.dp.toPx() }
    val top = with(density) { 8.dp.toPx() }
    val bottom = with(density) { 26.dp.toPx() }
    val n = values.size
    val nice = (ceil(max(1, values.max()) / 4.0) * 4).toInt().coerceAtLeast(4)

    Canvas(
        modifier.fillMaxWidth().height(124.dp).pointerInput(values) {
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
        for (g in 0..2) {
            val yy = top + h - h * g / 2f
            drawLine(p.line, Offset(left, yy), Offset(size.width - right, yy), strokeWidth = 1f)
            val lbl = measurer.measure(fmt(nice * g / 2), axis)
            drawText(lbl, topLeft = Offset(left - 6f - lbl.size.width, if (g == 0) yy - lbl.size.height - 2f else yy - lbl.size.height / 2f))
        }
        val line = Path().apply {
            moveTo(x(0), y(values[0]))
            for (i in 1 until n) { val cx = (x(i - 1) + x(i)) / 2; cubicTo(cx, y(values[i - 1]), cx, y(values[i]), x(i), y(values[i])) }
        }
        val fill = Path().apply { addPath(line); lineTo(x(n - 1), top + h); lineTo(x(0), top + h); close() }
        clipRect(left = 0f, top = 0f, right = size.width * progress.value, bottom = size.height) {
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.42f), color.copy(alpha = 0.04f)), startY = top, endY = top + h))
            drawPath(line, color.copy(alpha = 0.22f), style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val step = ceil(n / 5.0).toInt().coerceAtLeast(1)
        for (i in 0 until n step step) {
            val lbl = measurer.measure(labels[i].takeLast(5), axis)
            drawText(lbl, topLeft = Offset((x(i) - lbl.size.width / 2f).coerceIn(0f, size.width - lbl.size.width), size.height - bottom + 9f))
        }
        sel?.let { i ->
            drawLine(color.copy(alpha = 0.35f), Offset(x(i), top), Offset(x(i), top + h), strokeWidth = 1.2f)
            drawCircle(Color.White, 6.dp.toPx(), Offset(x(i), y(values[i])))
            drawCircle(color, 3.8.dp.toPx(), Offset(x(i), y(values[i])))
            val tip = measurer.measure("${labels[i]}: ${fmt(values[i])}", TextStyle(fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.SemiBold))
            val tw = tip.size.width + 22f
            val tx = (x(i) - tw / 2).coerceIn(0f, size.width - tw)
            val ty = (y(values[i]) - tip.size.height - 22f).coerceAtLeast(0f)
            drawRoundRect(Color(0xE61A1D2E), Offset(tx, ty), Size(tw, tip.size.height + 12f), CornerRadius(12f))
            drawText(tip, topLeft = Offset(tx + 11f, ty + 6f))
        }
    }
}

@Composable
fun ColumnChart(values: List<Int>, labelOf: (Int) -> String, color: Color, modifier: Modifier = Modifier) {
    val p = palette()
    val measurer = rememberTextMeasurer()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(values) { progress.snapTo(0f); progress.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
    val axis = TextStyle(fontSize = 9.sp, color = p.mut)
    val maxV = max(1, values.maxOrNull() ?: 1)
    Canvas(modifier.fillMaxWidth().height(104.dp)) {
        val left = 26.dp.toPx(); val bottom = 18.dp.toPx(); val top = 4.dp.toPx()
        val w = size.width - left; val h = size.height - bottom - top
        val bw = w / values.size
        drawLine(p.line, Offset(left, top + h), Offset(size.width, top + h), strokeWidth = 1f)
        val top1 = measurer.measure(fmt(maxV), axis)
        drawText(top1, topLeft = Offset(left - 5f - top1.size.width, top))
        values.forEachIndexed { i, v ->
            val k = (progress.value * 1.6f - i.toFloat() / values.size * 0.6f).coerceIn(0f, 1f)
            val bh = v.toFloat() / maxV * h * k
            val bx = left + i * bw + bw * 0.16f
            drawRoundRect(
                Brush.verticalGradient(listOf(color, color.copy(alpha = 0.35f)), startY = top + h - bh, endY = top + h),
                Offset(bx, top + h - bh), Size((bw * 0.68f).coerceAtLeast(2f), bh), CornerRadius(5f),
            )
            if (i % 4 == 0) {
                val l = measurer.measure(labelOf(i), axis)
                drawText(l, topLeft = Offset(left + i * bw + bw / 2 - l.size.width / 2f, size.height - bottom + 4f))
            }
        }
    }
}

@Composable
fun SiteBars(sites: List<SiteStat>) {
    val p = palette()
    if (sites.isEmpty()) { Empty("No websites yet."); return }
    val maxV = max(1, sites.maxOf { it.visits })
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        sites.forEachIndexed { i, s ->
            val frac by animateFloatAsState(s.visits.toFloat() / maxV, tween(900, delayMillis = 120 + i * 60, easing = FastOutSlowInEasing), label = "bar")
            var started by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { started = true }
            val shown = if (started) frac else 0f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(s.domain, 28.dp, square = true, text = s.domain.take(1).uppercase())
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.domain, color = p.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(p.soft)) {
                        Box(Modifier.fillMaxWidth(shown.coerceIn(0.03f, 1f)).fillMaxSize().clip(CircleShape).background(Brush.horizontalGradient(listOf(p.pri, p.cyan))))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("${fmt(s.visits)} visits", color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text("${dur(s.totalMs)} · ${fmt(s.users)}u", color = p.mut, fontSize = 10.sp)
                }
            }
        }
    }
}
