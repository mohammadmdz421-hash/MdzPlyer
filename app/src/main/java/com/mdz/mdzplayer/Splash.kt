package com.mdz.mdzplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private fun fibonacciSphere(n: Int): List<FloatArray> {
    val golden = Math.PI * (3.0 - sqrt(5.0))
    return List(n) { i ->
        val y = 1.0 - (i / (n - 1).toDouble()) * 2.0
        val rad = sqrt(1.0 - y * y)
        val th = golden * i
        floatArrayOf((cos(th) * rad).toFloat(), y.toFloat(), (sin(th) * rad).toFloat())
    }
}

@Composable
fun SplashScreen(onDone: () -> Unit) {
    var t by remember { mutableFloatStateOf(0f) }
    val points = remember { fibonacciSphere(1300) }

    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (t < 3.4f) {
            val now = withFrameNanos { it }
            t = (now - start) / 1_000_000_000f
        }
        onDone()
    }

    val th = LocalTheme.current
    val appear = (t / 1.2f).coerceIn(0f, 1f)
    val eased = 1f - (1f - appear) * (1f - appear)
    val textAlpha = ((t - 0.8f) / 0.9f).coerceIn(0f, 1f)
    val fadeOut = (1f - ((t - 2.9f) / 0.5f)).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .background(th.bgBrush())
            .alpha(fadeOut)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height * 0.40f
            val r = min(size.width, size.height) * 0.30f * (0.35f + 0.65f * eased)
            val ang = t * 0.9f
            val cosA = cos(ang)
            val sinA = sin(ang)
            val dens = size.minDimension / 700f
            for (p in points) {
                val x = p[0] * cosA + p[2] * sinA
                val z = -p[0] * sinA + p[2] * cosA
                val y = p[1]
                val depth = (z + 1f) / 2f
                val persp = 2.5f / (2.5f - z)
                val a = (0.18f + 0.82f * depth) * eased
                drawCircle(
                    color = th.primary.copy(alpha = a.coerceIn(0f, 1f)),
                    radius = (1.2f + 2.4f * depth) * dens * 1.6f,
                    center = Offset(cx + x * r * persp, cy + y * r * persp)
                )
            }
        }
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.74f))
            Text(
                "MdzPlyer",
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                modifier = Modifier.alpha(textAlpha)
            )
            Spacer(Modifier.weight(0.26f))
        }
    }
}
