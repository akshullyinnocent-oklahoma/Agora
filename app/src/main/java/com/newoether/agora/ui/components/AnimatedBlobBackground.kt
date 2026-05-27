package com.newoether.agora.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class BlobSpec(
    val centerXFrac: Float,
    val centerYFrac: Float,
    val radiusDp: Float,
    val xAmp: Float,
    val yAmp: Float,
    val xPeriodSec: Float,
    val yPeriodSec: Float,
)

@Composable
fun AnimatedBlobBackground(
    modifier: Modifier = Modifier,
    blurRadius: Float = 60f,
    overallAlpha: Float = 0.55f,
) {
    val density = LocalDensity.current

    val blobColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.primary,
    )

    val blobs = remember {
        listOf(
            BlobSpec(0.25f, 0.30f, 220f, 0.06f, 0.05f, 25f, 32f),
            BlobSpec(0.70f, 0.55f, 280f, 0.05f, 0.07f, 30f, 22f),
            BlobSpec(0.45f, 0.78f, 180f, 0.07f, 0.06f, 35f, 28f),
            BlobSpec(0.80f, 0.15f, 310f, 0.04f, 0.08f, 28f, 36f),
        )
    }

    var timeSec by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        val startNanos = System.nanoTime()
        while (true) {
            timeSec = (System.nanoTime() - startNanos) / 1_000_000_000.0
            delay(16L)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .blur(radius = blurRadius.dp)
            .alpha(overallAlpha)
    ) {
        val w = size.width
        val h = size.height
        val t = timeSec

        blobs.forEachIndexed { i, blob ->
            val phase = i.toDouble() * 1.3
            val xFrac = blob.centerXFrac + blob.xAmp * sin(t / blob.xPeriodSec * 2.0 * PI + phase).toFloat()
            val yFrac = blob.centerYFrac + blob.yAmp * cos(t / blob.yPeriodSec * 2.0 * PI + phase).toFloat()
            val cx = w * xFrac
            val cy = h * yFrac
            val r = blob.radiusDp * density.density

            val color = blobColors[i]
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to color.copy(alpha = 0.12f),
                    0.25f to color.copy(alpha = 0.06f),
                    1.0f to Color.Transparent,
                    center = Offset(cx, cy),
                    radius = r
                ),
                radius = r,
                center = Offset(cx, cy)
            )
        }
    }
}
