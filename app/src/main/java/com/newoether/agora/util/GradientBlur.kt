package com.newoether.agora.util

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity

/**
 * Two-pass Gaussian-ish blur whose radius varies along the Y axis.
 *
 * The shader samples the input texture N times with increasing offsets controlled
 * by [blurAtTop] (top blur radius in px) and [blurAtBottom] (bottom blur radius in px).
 * Intermediate rows are linearly interpolated.
 *
 * Requires Android 13+ (API 33). Falls back to no-op on older devices.
 *
 * @param blurAtTop    blur radius in density-independent pixels at the top edge.
 * @param blurAtBottom blur radius in density-independent pixels at the bottom edge.
 */
fun Modifier.gradientBlur(blurAtTopDp: Float, blurAtBottomDp: Float): Modifier = composed {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@composed this

    val density = LocalDensity.current.density
    val maxBlurPx = maxOf(blurAtTopDp, blurAtBottomDp) * density
    val fadeRangePx = 150f * density

    val shader = remember(maxBlurPx, fadeRangePx) {
        RuntimeShader(VARIABLE_BLUR_SHADER).apply {
            setFloatUniform("uParams", maxBlurPx, fadeRangePx)
        }
    }

    val renderEffect = remember(shader) {
        RenderEffect.createRuntimeShaderEffect(shader, "content")
    }.asComposeRenderEffect()

    Modifier.graphicsLayer { this.renderEffect = renderEffect }
}

/**
 * Gradient blur with edge fade at both top and bottom.
 * Blur ramps from 0 at the edge to [maxBlurDp] over [edgeFadeDp] distance.
 */
fun Modifier.gradientBlurEdges(maxBlurDp: Float, edgeFadeDp: Float = 20f, topWeight: Float = 1f, bottomWeight: Float = 1f): Modifier = composed {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@composed this
    if (topWeight <= 0f && bottomWeight <= 0f) return@composed this

    val density = LocalDensity.current.density
    val maxBlurPx = maxBlurDp * density
    val fadePx = edgeFadeDp * density
    var composableHPx by remember { mutableFloatStateOf(2400f) }

    val shader = remember(maxBlurPx, fadePx, topWeight, bottomWeight, composableHPx) {
        RuntimeShader(EDGE_BLUR_SHADER).apply {
            setFloatUniform("uMaxBlur", maxBlurPx)
            setFloatUniform("uFade", fadePx)
            setFloatUniform("uH", composableHPx)
            setFloatUniform("uWeights", topWeight, bottomWeight)
        }
    }

    val renderEffect = remember(shader) {
        RenderEffect.createRuntimeShaderEffect(shader, "content")
    }.asComposeRenderEffect()

    Modifier
        .onSizeChanged { composableHPx = it.height.toFloat() }
        .graphicsLayer { this.renderEffect = renderEffect }
}

/**
 * Sets both top and bottom blur to the same value (uniform blur).
 */
fun Modifier.gradientBlur(radiusDp: Float): Modifier =
    gradientBlur(radiusDp, radiusDp)

/**
 * Gradient blur using a dense sampling grid with bilinear-friendly steps.
 *
 * The key to avoiding speckle artifacts: each sample covers a ~2×2 px area
 * thanks to the GPU's built-in bilinear filtering on texture lookups.
 * By spacing samples at ~1.5 σ apart, we get smooth coverage across the
 * full blur radius without gaps.
 *
 * Grid size adapts to sigma — larger sigma → larger grid → more samples.
 *  - σ <  6 px →  5×5 grid (25 samples)  radius ~ 1.5 σ
 *  - σ < 14 px →  7×7 grid (49 samples)  radius ~ 2.0 σ
 *  - σ ≥ 14 px →  9×9 grid (81 samples)  radius ~ 2.5 σ
 */
private val VARIABLE_BLUR_SHADER = """
    uniform shader content;
    uniform float2 uParams;       // x = maxBlur (px), y = fadeRange (px)

    half4 main(float2 coord) {
        float t = saturate(coord.y / uParams.y);
        float s = uParams.x * (1.0 - t);
        if (s < 0.5) return content.eval(coord);

        // Dense separable Gaussian grid — step = 0.3σ ensures 3+ samples/σ
        // which with bilinear filtering is enough for smooth blur at any radius.
        float step = s * 0.30;
        int N = int(ceil(s * 3.0 / step));
        if (N > 9) N = 9;   // cap at 19×19 = 361 samples max

        half4 accum = half4(0.0);
        float totalW = 0.0;
        float invTwoS2 = -0.5 / (s * s);
        float r2 = float(N * N);

        for (int dx = -9; dx <= 9; dx++) {
            float fx = float(dx);
            if (fx * fx > r2) continue;
            for (int dy = -9; dy <= 9; dy++) {
                float fy = float(dy);
                if (fx * fx + fy * fy > r2) continue;
                float2 offset = float2(fx * step, fy * step);
                float w = exp(dot(offset, offset) * invTwoS2);
                accum += half4(content.eval(coord + offset)) * w;
                totalW += w;
            }
        }

        return accum / half4(totalW);
    }
""".trimIndent()

/**
 * Edge-fade blur shader. Blur is strongest at edges, fading to 0 toward
 * center over [uFade] pixels. Global weights [uWeights] decouple top/bottom.
 */
private val EDGE_BLUR_SHADER = """
    uniform shader content;
    uniform float uMaxBlur;    // blur at edge (4dp → px)
    uniform float uFade;       // fade-in distance (40dp → px)
    uniform float uH;          // composable height in px
    uniform float2 uWeights;   // x = top global multiplier, y = bottom global multiplier

    half4 main(float2 coord) {
        if (uWeights.x <= 0.0 && uWeights.y <= 0.0) return content.eval(coord);
        float t = saturate(1.0 - coord.y / uFade) * uWeights.x;
        float b = saturate(1.0 - (uH - coord.y) / uFade) * uWeights.y;
        float s = uMaxBlur * max(t, b);
        if (s < 0.5) return content.eval(coord);

        float step = s * 0.30;
        int N = int(ceil(s * 3.0 / step));
        if (N > 9) N = 9;

        half4 accum = half4(0.0);
        float totalW = 0.0;
        float invTwoS2 = -0.5 / (s * s);
        float r2 = float(N * N);

        for (int dx = -9; dx <= 9; dx++) {
            float fx = float(dx);
            if (fx * fx > r2) continue;
            for (int dy = -9; dy <= 9; dy++) {
                float fy = float(dy);
                if (fx * fx + fy * fy > r2) continue;
                float2 offset = float2(fx * step, fy * step);
                float w = exp(dot(offset, offset) * invTwoS2);
                accum += half4(content.eval(coord + offset)) * w;
                totalW += w;
            }
        }

        return accum / half4(totalW);
    }
""".trimIndent()
