package com.newoether.agora.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
internal fun ZoomableImageItem(
    url: String,
    onTap: () -> Unit,
    onScaleChanged: (Float) -> Unit = {},
    consumeConditionally: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current

    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offsetX by remember(url) { mutableFloatStateOf(0f) }
    var offsetY by remember(url) { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(Size.Zero) }
    var imageSize by remember(url) { mutableStateOf(Size.Zero) }
    var animationJob by remember(url) { mutableStateOf<Job?>(null) }
    var lastCentroid by remember(url) { mutableStateOf(Offset.Unspecified) }

    fun getMaxOffsets(currentScale: Float): Pair<Float, Float> {
        if (imageSize == Size.Zero || containerSize == Size.Zero) return 0f to 0f
        val imageAspectRatio = imageSize.width / imageSize.height
        val containerAspectRatio = containerSize.width / containerSize.height
        val contentWidth = if (imageAspectRatio > containerAspectRatio) containerSize.width else containerSize.height * imageAspectRatio
        val contentHeight = if (imageAspectRatio > containerAspectRatio) containerSize.width / imageAspectRatio else containerSize.height
        val maxX = (contentWidth * currentScale - containerSize.width).coerceAtLeast(0f) / 2f
        val maxY = (contentHeight * currentScale - containerSize.height).coerceAtLeast(0f) / 2f
        return maxX to maxY
    }

    fun rubberBandValue(fullDelta: Float, dimension: Float): Float {
        if (dimension <= 0f) return 0f
        val c = 0.45f
        return (fullDelta * c * dimension) / (dimension + c * fullDelta)
    }

    LaunchedEffect(Unit) {
        snapshotFlow { scale }.collect { onScaleChanged(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(url) {
                detectTapGestures(
                    onTap = { if (scale <= 1.05f) onTap() },
                    onDoubleTap = { tapOffset ->
                        animationJob?.cancel()
                        animationJob = scope.launch {
                            val startScale = scale
                            val startOffsetX = offsetX
                            val startOffsetY = offsetY
                            if (startScale > 1.05f) {
                                val targetScale = 1f
                                val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                AnimationState(startScale).animateTo(targetScale, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, 0.001f)) {
                                    scale = value
                                    val r = if (startScale != 0f) value / startScale else 1f
                                    val unconstrainedX = startOffsetX * r + (tapOffset.x - center.x) * (1f - r)
                                    val unconstrainedY = startOffsetY * r + (tapOffset.y - center.y) * (1f - r)
                                    val (maxX, maxY) = getMaxOffsets(value)
                                    offsetX = unconstrainedX.coerceIn(-maxX, maxX)
                                    offsetY = unconstrainedY.coerceIn(-maxY, maxY)
                                }
                            } else {
                                val targetScale = 3f
                                val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                AnimationState(startScale).animateTo(targetScale, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, 0.001f)) {
                                    scale = value
                                    val r = if (startScale != 0f) value / startScale else 1f
                                    val unconstrainedX = startOffsetX * r + (tapOffset.x - center.x) * (1f - r)
                                    val unconstrainedY = startOffsetY * r + (tapOffset.y - center.y) * (1f - r)
                                    val (maxX, maxY) = getMaxOffsets(value)
                                    offsetX = unconstrainedX.coerceIn(-maxX, maxX)
                                    offsetY = unconstrainedY.coerceIn(-maxY, maxY)
                                }
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        coil.compose.AsyncImage(
            model = url,
            contentDescription = null,
            onSuccess = { state -> imageSize = state.painter.intrinsicSize },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(url) {
                    val velocityTracker = VelocityTracker()
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        animationJob?.cancel()
                        var pastTouchSlop = false
                        val touchSlop = viewConfiguration.touchSlop
                        lastCentroid = Offset.Unspecified
                        var logicalScale = scale
                        var logicalOffsetX = offsetX
                        var logicalOffsetY = offsetY
                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (!pastTouchSlop) {
                                val panAmount = panChange.getDistance()
                                if (zoomChange != 1f || panAmount > touchSlop) pastTouchSlop = true
                            }
                            if (pastTouchSlop) {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                if (zoomChange != 1f && centroid != Offset.Unspecified) lastCentroid = centroid
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    val oldVisualScale = scale
                                    logicalScale = (logicalScale * zoomChange).coerceIn(0.1f, 30f)
                                    val newVisualScale = if (logicalScale < 1f) 1f - rubberBandValue(1f - logicalScale, 1f)
                                    else if (logicalScale > 10f) 10f + rubberBandValue(logicalScale - 10f, 5f)
                                    else logicalScale
                                    val r = if (oldVisualScale != 0f) newVisualScale / oldVisualScale else 1f
                                    val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                    logicalOffsetX = logicalOffsetX * r + (centroid.x - center.x) * (1f - r) + panChange.x
                                    logicalOffsetY = logicalOffsetY * r + (centroid.y - center.y) * (1f - r) + panChange.y
                                    val (maxX, maxY) = getMaxOffsets(newVisualScale)
                                    scale = newVisualScale
                                    offsetX = if (logicalOffsetX > maxX) maxX + rubberBandValue(logicalOffsetX - maxX, containerSize.width)
                                    else if (logicalOffsetX < -maxX) -maxX - rubberBandValue(-maxX - logicalOffsetX, containerSize.width)
                                    else logicalOffsetX
                                    offsetY = if (logicalOffsetY > maxY) maxY + rubberBandValue(logicalOffsetY - maxY, containerSize.height)
                                    else if (logicalOffsetY < -maxY) -maxY - rubberBandValue(-maxY - logicalOffsetY, containerSize.height)
                                    else logicalOffsetY
                                    if (consumeConditionally) {
                                        if (newVisualScale > 1.05f || zoomChange != 1f || abs(panChange.y) > abs(panChange.x)) {
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                    } else {
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                }
                            }
                            if (event.changes.size == 1) {
                                velocityTracker.addPosition(event.changes.first().uptimeMillis, event.changes.first().position)
                            } else {
                                velocityTracker.resetTracking()
                            }
                        } while (event.changes.any { it.pressed })
                        val rawVelocity = velocityTracker.calculateVelocity()
                        val maxV = with(density) { 2500.dp.toPx() }
                        val velocity = Velocity(
                            x = if (rawVelocity.x.isNaN()) 0f else rawVelocity.x.coerceIn(-maxV, maxV),
                            y = if (rawVelocity.y.isNaN()) 0f else rawVelocity.y.coerceIn(-maxV, maxV)
                        )
                        animationJob = scope.launch {
                            val rbCoeff = 0.45f
                            if (scale < 0.95f || scale > 10.05f) {
                                val sS = scale; val sX = offsetX; val sY = offsetY
                                val targetS = scale.coerceIn(1f, 10f)
                                val (targetMaxX, targetMaxY) = getMaxOffsets(targetS)
                                val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                val pivot = if (lastCentroid != Offset.Unspecified) lastCentroid else center
                                val targetR = if (sS != 0f) targetS / sS else 1f
                                val finalPivotX = sX * targetR + (pivot.x - center.x) * (1f - targetR)
                                val finalPivotY = sY * targetR + (pivot.y - center.y) * (1f - targetR)
                                val targetX = finalPivotX.coerceIn(-targetMaxX, targetMaxX)
                                val targetY = finalPivotY.coerceIn(-targetMaxY, targetMaxY)
                                AnimationState(0f).animateTo(1f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessLow)) {
                                    val currentS = sS + (targetS - sS) * value
                                    scale = currentS
                                    val r = if (sS != 0f) currentS / sS else 1f
                                    val pivotOX = sX * r + (pivot.x - center.x) * (1f - r)
                                    val pivotOY = sY * r + (pivot.y - center.y) * (1f - r)
                                    offsetX = pivotOX + (targetX - finalPivotX) * value
                                    offsetY = pivotOY + (targetY - finalPivotY) * value
                                }
                            } else {
                                launch {
                                    val (maxX, _) = getMaxOffsets(scale)
                                    if (offsetX > maxX || offsetX < -maxX) {
                                        val targetX = offsetX.coerceIn(-maxX, maxX)
                                        AnimationState(offsetX, velocity.x * rbCoeff).animateTo(targetX, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessLow)) { offsetX = value }
                                    } else if (velocity.x != 0f) {
                                        val decay = splineBasedDecay<Float>(density)
                                        var hitX = false; var velX = 0f; var posX = 0f
                                        AnimationState(offsetX, velocity.x).animateDecay(decay) {
                                            val (curMaxX, _) = getMaxOffsets(scale)
                                            if (value > curMaxX || value < -curMaxX) { velX = this.velocity; posX = value; hitX = true; cancelAnimation() }
                                            else offsetX = value
                                        }
                                        if (hitX) {
                                            val (curMaxX, _) = getMaxOffsets(scale)
                                            AnimationState(posX, velX * rbCoeff).animateTo(posX.coerceIn(-curMaxX, curMaxX), spring(Spring.DampingRatioNoBouncy, Spring.StiffnessLow)) { offsetX = value }
                                        }
                                    }
                                }
                                launch {
                                    val (_, maxY) = getMaxOffsets(scale)
                                    if (offsetY > maxY || offsetY < -maxY) {
                                        val targetY = offsetY.coerceIn(-maxY, maxY)
                                        AnimationState(offsetY, velocity.y * rbCoeff).animateTo(targetY, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessLow)) { offsetY = value }
                                    } else if (velocity.y != 0f) {
                                        val decay = splineBasedDecay<Float>(density)
                                        var hitY = false; var velY = 0f; var posY = 0f
                                        AnimationState(offsetY, velocity.y).animateDecay(decay) {
                                            val (_, curMaxY) = getMaxOffsets(scale)
                                            if (value > curMaxY || value < -curMaxY) { velY = this.velocity; posY = value; hitY = true; cancelAnimation() }
                                            else offsetY = value
                                        }
                                        if (hitY) {
                                            val (_, curMaxY) = getMaxOffsets(scale)
                                            AnimationState(posY, velY * rbCoeff).animateTo(posY.coerceIn(-curMaxY, curMaxY), spring(Spring.DampingRatioNoBouncy, Spring.StiffnessLow)) { offsetY = value }
                                        }
                                    }
                                }
                            }
                        }
                        velocityTracker.resetTracking()
                    }
                }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                ),
            contentScale = ContentScale.Fit
        )
        if (imageSize == Size.Zero) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White, strokeWidth = 2.dp)
        }
    }
}
