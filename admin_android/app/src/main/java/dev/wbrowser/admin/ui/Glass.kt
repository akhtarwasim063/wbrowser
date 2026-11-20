package dev.wbrowser.admin.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Slow light "caustic" sweep (0..1) shared by every glass surface; it rests between sweeps. */
val LocalSweep = compositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }

@Composable
fun WithGlassMotion(content: @Composable () -> Unit) {
    val t = rememberInfiniteTransition(label = "glass")
    val sweep = t.animateFloat(
        0f, 1f,
        infiniteRepeatable(keyframes { durationMillis = 10000; 0f at 0; 1f at 5200 using LinearEasing; 1f at 10000 }),
        label = "sweep",
    )
    CompositionLocalProvider(LocalSweep provides sweep, content = content)
}

private val FACETS = listOf(
    floatArrayOf(0f, 0f, 0.55f, 0f, 0.25f, 0.20f),
    floatArrayOf(0.55f, 0f, 1f, 0f, 1f, 0.17f, 0.72f, 0.28f),
    floatArrayOf(0f, 0.20f, 0.25f, 0.20f, 0.12f, 0.46f, 0f, 0.42f),
    floatArrayOf(1f, 0.52f, 0.74f, 0.60f, 0.86f, 0.88f, 1f, 0.84f),
    floatArrayOf(0f, 0.72f, 0.22f, 0.66f, 0.42f, 1f, 0f, 1f),
)

/** The scene behind everything: gradient, drifting light orbs, faint crystal facets and slow water waves. */
@Composable
fun CrystalBackground(modifier: Modifier = Modifier) {
    val p = palette()
    val t = rememberInfiniteTransition(label = "bg")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(28000, easing = LinearEasing)), label = "orbs")
    val wave by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(10000, easing = LinearEasing)), label = "wave")
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(p.bgTop, p.bgMid, p.bgBottom)))

        fun orb(c: Color, cx: Float, cy: Float, r: Float, a: Float) =
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), center = Offset(cx, cy), radius = r), r, Offset(cx, cy))
        val a = if (p.dark) 0.40f else 0.34f
        orb(p.orbA, w * (0.18f + 0.12f * sin(phase)), h * (0.16f + 0.05f * cos(phase * 0.8f)), w * 0.80f, a)
        orb(p.orbB, w * (0.88f + 0.08f * cos(phase * 1.1f)), h * (0.40f + 0.07f * sin(phase)), w * 0.72f, a * 0.85f)
        orb(p.orbC, w * (0.30f + 0.14f * sin(phase * 0.7f + 2f)), h * (0.74f + 0.05f * cos(phase)), w * 0.85f, a * 0.8f)

        // crystal facets
        val fa = (if (p.dark) 0.05f else 0.30f) * (0.8f + 0.2f * sin(phase * 2f))
        FACETS.forEach { f ->
            val path = Path().apply {
                moveTo(f[0] * w, f[1] * h)
                var i = 2
                while (i < f.size) { lineTo(f[i] * w, f[i + 1] * h); i += 2 }
                close()
            }
            drawPath(path, Brush.linearGradient(listOf(Color.White.copy(alpha = fa), Color.White.copy(alpha = fa * 0.15f)), start = Offset(0f, 0f), end = Offset(w, h)))
            drawPath(path, Color.White.copy(alpha = if (p.dark) 0.07f else 0.45f), style = Stroke(width = 1f))
        }

        // water: two slow waves along the bottom
        fun wavePath(base: Float, amp: Float, freq: Float, ph: Float): Path = Path().apply {
            moveTo(0f, h)
            var x = 0f
            while (x <= w + 14f) { lineTo(x, base + sin(x / w * 2f * PI.toFloat() * freq + ph) * amp); x += 14f }
            lineTo(w, h); close()
        }
        val wa = if (p.dark) 0.20f else 0.26f
        drawPath(wavePath(h * 0.90f, h * 0.010f, 1.4f, wave), Brush.verticalGradient(listOf(p.water.copy(alpha = wa), p.water.copy(alpha = 0.02f)), startY = h * 0.88f, endY = h))
        drawPath(wavePath(h * 0.93f, h * 0.008f, 2.1f, -wave * 1.3f + 1f), Brush.verticalGradient(listOf(p.orbA.copy(alpha = wa * 0.8f), p.orbA.copy(alpha = 0.02f)), startY = h * 0.91f, endY = h))
    }
}

/** Translucent glass: tinted fill, bright rim, top sheen and the occasional light sweep. */
@Composable
fun Modifier.glass(shape: Shape = RoundedCornerShape(18.dp), lift: Dp = 8.dp): Modifier {
    val p = palette()
    val sweep = LocalSweep.current
    return this
        .shadow(if (p.dark) 0.dp else lift, shape, ambientColor = p.glassShadow, spotColor = p.glassShadow)
        .clip(shape)
        .background(p.glassFill)
        .drawWithContent {
            drawRect(Brush.verticalGradient(listOf(p.glassSheen, Color.Transparent), endY = size.height * 0.6f))
            val x = sweep.value * size.width * 2.6f - size.width * 0.8f
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = if (p.dark) 0.07f else 0.45f), Color.Transparent),
                    start = Offset(x, 0f), end = Offset(x + size.width * 0.45f, size.height * 0.8f),
                ),
            )
            drawContent()
        }
        .border(1.dp, Brush.verticalGradient(listOf(p.glassRimTop, p.glassRimBottom)), shape)
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, padding: Dp = 14.dp, shape: Shape = RoundedCornerShape(20.dp), content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().glass(shape)) { Box(Modifier.padding(padding)) { content() } }
}
