package com.newoether.agora.ui.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun ZoomableImage(
    model: Any?,
    modifier: Modifier = Modifier,
    onSingleTap: (() -> Unit)? = null,
    contentScale: ContentScale = ContentScale.Fit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current

    var scale by remember(model) { mutableFloatStateOf(1f) }
    var offsetX by remember(model) { mutableFloatStateOf(0f) }
    var offsetY by remember(model) { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(Size.Zero) }
    var imageSize by remember { mutableStateOf(Size.Zero) }
    var animationJob by remember { mutableStateOf<Job?>(null) }
    var lastCentroid by remember { mutableStateOf(Offset.Unspecified) }

    fun getMaxOffsets(currentScale: Float): Pair<Float, Float> {
        if (imageSize == Size.Zero || containerSize == Size.Zero) return 0f to 0f
        val iar = imageSize.width / imageSize.height
        val car = containerSize.width / containerSize.height
        val cw = if (iar > car) containerSize.width else containerSize.height * iar
        val ch = if (iar > car) containerSize.width / iar else containerSize.height
        return ((cw * currentScale - containerSize.width).coerceAtLeast(0f) / 2f) to
               ((ch * currentScale - containerSize.height).coerceAtLeast(0f) / 2f)
    }

    fun rubberBand(v: Float, dim: Float): Float {
        if (dim <= 0f) return 0f; val c = 0.45f; return (v * c * dim) / (dim + c * v)
    }

    coil.compose.AsyncImage(
        model = model,
        contentDescription = null,
        onSuccess = { imageSize = it.painter.intrinsicSize },
        modifier = modifier
            .onSizeChanged { containerSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(model) {
                detectTapGestures(
                    onTap = { onSingleTap?.invoke() },
                    onDoubleTap = { tapOffset ->
                        animationJob?.cancel()
                        animationJob = scope.launch {
                            val sS = scale; val sX = offsetX; val sY = offsetY
                            val targetS = if (sS > 1.05f) 1f else 3f
                            val ctr = Offset(containerSize.width / 2f, containerSize.height / 2f)
                            AnimationState(sS).animateTo(targetS, spring(Spring.StiffnessMediumLow, Spring.DampingRatioNoBouncy, 0.001f)) {
                                scale = value
                                val r = if (sS != 0f) value / sS else 1f
                                val (mX, mY) = getMaxOffsets(value)
                                offsetX = (sX * r + (tapOffset.x - ctr.x) * (1f - r)).coerceIn(-mX, mX)
                                offsetY = (sY * r + (tapOffset.y - ctr.y) * (1f - r)).coerceIn(-mY, mY)
                            }
                        }
                    }
                )
            }
            .pointerInput(model) {
                val vt = VelocityTracker()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    animationJob?.cancel()
                    var pastSlop = false; val slop = viewConfiguration.touchSlop
                    lastCentroid = Offset.Unspecified
                    var logS = scale; var logX = offsetX; var logY = offsetY
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom(); val pan = event.calculatePan()
                        if (!pastSlop && (zoom != 1f || pan.getDistance() > slop)) pastSlop = true
                        if (pastSlop) {
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if (zoom != 1f && centroid != Offset.Unspecified) lastCentroid = centroid
                            if (zoom != 1f || pan != Offset.Zero) {
                                val oldS = scale
                                logS = (logS * zoom).coerceIn(0.1f, 30f)
                                val newS = when { logS < 1f -> 1f - rubberBand(1f - logS, 1f); logS > 10f -> 10f + rubberBand(logS - 10f, 5f); else -> logS }
                                val r = if (oldS != 0f) newS / oldS else 1f
                                val ctr = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                logX = logX * r + (centroid.x - ctr.x) * (1f - r) + pan.x
                                logY = logY * r + (centroid.y - ctr.y) * (1f - r) + pan.y
                                val (mX, mY) = getMaxOffsets(newS)
                                scale = newS
                                offsetX = when { logX > mX -> mX + rubberBand(logX - mX, containerSize.width); logX < -mX -> -mX - rubberBand(-mX - logX, containerSize.width); else -> logX }
                                offsetY = when { logY > mY -> mY + rubberBand(logY - mY, containerSize.height); logY < -mY -> -mY - rubberBand(-mY - logY, containerSize.height); else -> logY }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            if (event.changes.size == 1) vt.addPosition(event.changes.first().uptimeMillis, event.changes.first().position)
                            else vt.resetTracking()
                        }
                    } while (event.changes.any { it.pressed })
                    val rawV = vt.calculateVelocity()
                    val maxV = with(density) { 2500.dp.toPx() }
                    val vel = Velocity(if (rawV.x.isNaN()) 0f else rawV.x.coerceIn(-maxV, maxV), if (rawV.y.isNaN()) 0f else rawV.y.coerceIn(-maxV, maxV))
                    animationJob = scope.launch {
                        if (scale < 0.95f || scale > 10.05f) {
                            val sS = scale; val sX = offsetX; val sY = offsetY; val tS = scale.coerceIn(1f, 10f)
                            val (tMX, tMY) = getMaxOffsets(tS); val ctr = Offset(containerSize.width / 2f, containerSize.height / 2f)
                            val r = if (sS != 0f) tS / sS else 1f
                            val tX = (sX * r + (lastCentroid.x - ctr.x) * (1f - r)).coerceIn(-tMX, tMX)
                            val tY = (sY * r + (lastCentroid.y - ctr.y) * (1f - r)).coerceIn(-tMY, tMY)
                            AnimationState(0f).animateTo(1f, spring(Spring.StiffnessLow)) { scale = sS + (tS - sS) * value; offsetX = sX + (tX - sX) * value; offsetY = sY + (tY - sY) * value }
                        } else {
                            val (mX, mY) = getMaxOffsets(scale); val eX = if (mX > 0f) offsetX + vel.x * 0.15f else 0f; val eY = if (mY > 0f) offsetY + vel.y * 0.15f else 0f
                            val tX = eX.coerceIn(-mX, mX); val tY = eY.coerceIn(-mY, mY); val sX = offsetX; val sY = offsetY
                            AnimationState(0f).animateTo(1f, spring(Spring.StiffnessLow)) { offsetX = sX + (tX - sX) * value; offsetY = sY + (tY - sY) * value }
                        }
                    }
                    vt.resetTracking()
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
        contentScale = contentScale
    )
}
