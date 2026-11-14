package com.js.geode

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Motion system (v0.1.1). JS: "nothing should be instant, use Apple-like
 * fluid animations but optimized." Rules followed here:
 * - Transform + alpha only (scale / slide / fade). No blur, no heavy work.
 * - Spring physics for press + pop, easeOutCubic tweens for entrances.
 * - 250–400ms, staggered 60–80ms, offsets small (8–12dp).
 * - Press feedback never changes layout bounds: graphicsLayer scale
 *   only, around the composable's own centre.
 */

/** Apple-like spring: slightly under-damped so it settles with a soft overshoot. */
fun fluidSpring() = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

fun pressSpring() = spring<Float>(dampingRatio = 0.8f, stiffness = 600f)

/** Entrance tween: fast ease-out, 300ms. */
fun enterTween(delayMillis: Int = 0) = tween<Float>(durationMillis = 300, delayMillis = delayMillis)

/**
 * Bounds transform for the iOS-style card grow (v0.1.4, reworked v0.1.5,
 * reworked v0.1.6): the library card morphs into the full-screen Detail
 * and back. Transform + clip only — no blur, no scale of the text itself.
 * v0.1.5: the shared bounds use ScaleToBounds (set at the call sites),
 * so the shell is measured once at its final size and GPU-scaled instead
 * of being re-measured on every frame (RemeasureToBounds was re-laying
 * out the whole Detail tree per frame — the v0.1.4 lag).
 *
 * v0.1.6: the curve is no longer a FastOutSlowIn tween. MeloX measured
 * Apple Music frame-by-frame and found FastOutSlowIn is too slow at the
 * head (only ~8.5% progress at 1/6 through) so everything rushes at the
 * tail. The curve is now their normalised critically damped spring
 * easing (see [criticallyDampedEasing]): it leaves briskly and spends
 * most of its time settling, slower and slower, into place. The duration
 * is divided by the system animator-duration scale (see [growDurationMs])
 * because Compose multiplies by it — a nominal 430ms was running longer
 * on device. Callers remember the returned BoundsTransform keyed on the
 * duration so a recomposition mid-flight never rebuilds the spec.
 */
const val GROW_BASE_DURATION_MS = 430

/**
 * MeloX's normalised critically damped spring easing: p(f) =
 * [1 - (1 + (k - v0)·f)·e^(-k·f)] / end, k = 6, v0 = 0.9 (α = 0.15 of k,
 * so the start slope is ~0.91x the mean speed instead of 0 — a pure
 * critically damped spring starts flat and "holds back, then starts").
 * `end` is the value at f = 1, so p(1) = 1 exactly and the motion lands
 * on time. Slope is always positive (v0 + k·(k - v0)·f > 0), so the
 * easing is strictly monotonic — no overshoot, no bounce.
 */
val criticallyDampedEasing = Easing { f ->
    val k = 6f
    val v0 = 0.9f
    val end = 1f - (1f + (k - v0)) * exp(-k)
    val value = 1f - (1f + (k - v0) * f) * exp(-k * f)
    (value / end).coerceIn(0f, 1f)
}

/** Nominal grow duration corrected for the system animator-duration
 * scale (Settings.Global.ANIMATOR_DURATION_SCALE, readable with no
 * permission). Scale 0 (animations off) is treated as 1x; the result is
 * clamped so a wild scale can't produce an absurd duration. */
fun growDurationMs(context: Context): Int {
    val scale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    )
    val safe = if (scale <= 0f) 1f else scale
    return (GROW_BASE_DURATION_MS / safe).roundToInt().coerceIn(100, 2000)
}

@OptIn(ExperimentalSharedTransitionApi::class)
fun growBoundsTransform(durationMs: Int): BoundsTransform =
    BoundsTransform { _, _ -> tween(durationMs, easing = criticallyDampedEasing) }

/**
 * Press feedback for every tappable element: scale to 0.97 while pressed,
 * spring back on release, within ~100ms of touch-down. Uses pointer
 * events only (never consumes them), so the underlying click / ripple
 * still fires. When [enabled] is false the scale stays at 1 — a disabled
 * button must still look disabled (its colours say so), not animate.
 */
fun Modifier.pressScale(enabled: Boolean = true): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = pressSpring(),
        label = "pressScale"
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .pointerInput(enabled) {
            // awaitEachGesture / awaitFirstDown never consume the event,
            // so the underlying click + ripple still fire. (Compose 1.10
            // moved awaitPointerEventScope out of ui.input.pointer, so
            // the gesture helpers are used instead.)
            awaitEachGesture {
                awaitFirstDown()
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
}

/** 12dp slide offset in px, from the current density. Compose 1.10's
 *  slideInVertically lambda is (fullHeight: Int) -> Int with no Density
 *  receiver, so the px value has to be computed outside the lambda. */
@Composable
fun enterSlideOffsetPx(): Int = (LocalDensity.current.density * 12).roundToInt()

/**
 * Staggered fade + small slide-in for lists / rows / onboarding bullets.
 * [index] staggers by 70ms (capped by the caller where lists are long).
 * [visible] is driven true once, shortly after first composition.
 */
@Composable
fun StaggeredIn(
    index: Int,
    visible: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    val delay = (index.coerceIn(0, 8)) * 70
    val offsetPx = enterSlideOffsetPx()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300, delayMillis = delay)) +
            slideInVertically(tween(300, delayMillis = delay)) { offsetPx },
        exit = fadeOut(tween(200))
    ) {
        androidx.compose.foundation.layout.Column(content = content)
    }
}

/** Shared text-field colours: text, label and placeholder all come from
 *  theme tokens, never the black default. Used by every text field. */
@Composable
fun geodeTextFieldColors(): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    val n = LocalNeutral.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = scheme.onBackground,
        unfocusedTextColor = scheme.onBackground,
        disabledTextColor = n.secondaryText,
        focusedLabelColor = n.secondaryText,
        unfocusedLabelColor = n.secondaryText,
        focusedPlaceholderColor = n.secondaryText,
        unfocusedPlaceholderColor = n.secondaryText,
        cursorColor = scheme.onBackground,
        focusedBorderColor = scheme.onBackground,
        unfocusedBorderColor = n.border
    )
}
