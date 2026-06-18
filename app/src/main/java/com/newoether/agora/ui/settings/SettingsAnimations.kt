package com.newoether.agora.ui.settings

import androidx.compose.animation.*
import androidx.compose.animation.core.*

// ── Shared animation specs for settings page transitions ──
//     One source of truth — tune here, every sub-page follows.

private val SpringDamping = Spring.DampingRatioLowBouncy
private val SpringStiff = Spring.StiffnessLow
private const val FadeDuration = 300
private const val EnterSlideFraction = 0.75f  // fraction of screen width
private const val ExitSlideFraction = 0.75f
private const val ScaleFrom = 0.94f
private const val ScaleTo = 0.94f

/** Spring used for all enter & exit animations (slide + scale + fade). */
internal fun <T> settingsSpring(): SpringSpec<T> = spring(
    dampingRatio = SpringDamping,
    stiffness = SpringStiff
)

/** Full enter transition: slides from [offsetFraction] of screen width, fades in, scales up. */
internal fun settingsEnterTrans(slideFromRight: Boolean): EnterTransition {
    val offset: (Int) -> Int = { fullWidth ->
        val dist = (fullWidth * EnterSlideFraction).toInt()
        if (slideFromRight) dist else -dist
    }
    return slideInHorizontally(animationSpec = settingsSpring(), initialOffsetX = offset) +
        fadeIn(animationSpec = tween(FadeDuration)) +
        scaleIn(initialScale = ScaleFrom, animationSpec = settingsSpring())
}

/** Full exit transition: slides to [offsetFraction] of screen width, fades out, scales down. */
internal fun settingsExitTrans(slideToRight: Boolean): ExitTransition {
    val offset: (Int) -> Int = { fullWidth ->
        val dist = (fullWidth * ExitSlideFraction).toInt()
        if (slideToRight) dist else -dist
    }
    return slideOutHorizontally(animationSpec = settingsSpring(), targetOffsetX = offset) +
        fadeOut(animationSpec = settingsSpring()) +
        scaleOut(targetScale = ScaleTo, animationSpec = settingsSpring())
}

/**
 * Ready-to-use [ContentTransform] for [AnimatedContent] in settings drill-down.
 * @param forward `true` = drilling in (child slides from right, parent exits left);
 *                `false` = going back (parent slides from left, child exits right).
 */
internal fun settingsContentTransform(forward: Boolean): ContentTransform {
    return if (forward) {
        settingsEnterTrans(slideFromRight = true) togetherWith settingsExitTrans(slideToRight = false)
    } else {
        settingsEnterTrans(slideFromRight = false) togetherWith settingsExitTrans(slideToRight = true)
    }
}
