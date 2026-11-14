package com.js.geode

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val CardShape = RoundedCornerShape(12.dp)
val ButtonShape = RoundedCornerShape(8.dp)
val ChipShape = RoundedCornerShape(8.dp)

/**
 * Tap-elsewhere unfocus, one shared helper for every text field in the
 * app (v0.1.11, bug #4 — JS's standing rule: EVERY field, now and any
 * added in future, unfocuses on tap elsewhere).
 *
 * Use: `val unfocus = rememberTapOutsideUnfocus()` in the screen, then
 * `unfocus.containerModifier()` on the screen's scrollable container and
 * `unfocus.fieldModifier(key)` on each text field. A tap anywhere
 * outside every registered field hides the keyboard and clears focus;
 * taps inside a field are left alone (typing / cursor), drags to scroll
 * cancel the tap gesture, and child buttons still receive their own
 * clicks — this only hides the keyboard under them. Replaces the two
 * per-screen copies in onboarding (ApiKeyStep) and Settings, which had
 * the same logic hand-written twice and which the Library fields never
 * got.
 */
class TapOutsideUnfocus(private val dismiss: () -> Unit) {
    private var containerCoords by mutableStateOf<LayoutCoordinates?>(null)
    private val fieldRects = mutableStateMapOf<Any, Rect>()

    /** Apply to the screen's scrollable container. */
    fun containerModifier(): Modifier = Modifier
        .onGloballyPositioned { containerCoords = it }
        .pointerInput(Unit) {
            detectTapGestures(onTap = { offset: Offset ->
                if (fieldRects.values.none { it.contains(offset) }) dismiss()
            })
        }

    /** Apply to a text field that must stay focused when tapped. */
    fun fieldModifier(key: Any = Unit): Modifier = Modifier.onGloballyPositioned { coords ->
        containerCoords?.localBoundingBoxOf(coords)?.let { fieldRects[key] = it }
    }
}

@Composable
fun rememberTapOutsideUnfocus(): TapOutsideUnfocus {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    return remember(keyboardController, focusManager) {
        TapOutsideUnfocus {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }
}

/** Primary action: inverted button (black on light, white on dark). */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val n = LocalNeutral.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).pressScale(enabled),
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = n.primaryButton,
            contentColor = n.onPrimaryButton,
            // Disabled must stay readable, not a blank rectangle:
            // disabledButtonText on the border fill is 6.00:1 dark,
            // 6.31:1 light (v0.1.1 used secondaryText: 3.90:1 light).
            disabledContainerColor = n.border,
            disabledContentColor = n.disabledButtonText
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    ) {
        // Explicit colour: a Typography style colour would override the
        // button's content colour (v0.1.1: white label on white button).
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) n.onPrimaryButton else n.disabledButtonText
        )
    }
}

/** Primary action with custom content (v0.1.16: the Test-key button shows
 * a loader + "Testing key…" while a test runs). Same styling as the text
 * version above; the caller supplies the label/colours. */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val n = LocalNeutral.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).pressScale(enabled),
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = n.primaryButton,
            contentColor = n.onPrimaryButton,
            disabledContainerColor = n.border,
            disabledContentColor = n.disabledButtonText
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        content = content
    )
}

/** Secondary action: bordered button, no fill. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val n = LocalNeutral.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).pressScale(enabled),
        shape = ButtonShape,
        border = BorderStroke(1.dp, n.border),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onBackground,
            disabledContentColor = n.secondaryText,
            disabledContainerColor = Color.Transparent
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.onBackground else n.secondaryText
        )
    }
}

/** Filter chip. Selected = inverted fill. */
@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val n = LocalNeutral.current
    val bg = if (selected) n.primaryButton else Color.Transparent
    val fg = if (selected) n.onPrimaryButton else MaterialTheme.colorScheme.onBackground
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .pressScale()
            .clip(ChipShape)
            .background(bg)
            .then(if (!selected) Modifier.border(1.dp, n.border, ChipShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/** Small category + source row used on every card. */
@Composable
fun CategoryRow(
    category: String,
    source: String,
    isSample: Boolean = false,
    modifier: Modifier = Modifier
) {
    val n = LocalNeutral.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(ChipShape)
                .background(n.primaryButton)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                category,
                style = MaterialTheme.typography.labelSmall,
                color = n.onPrimaryButton
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            source,
            style = MaterialTheme.typography.labelMedium,
            color = n.secondaryText
        )
        if (isSample) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(ChipShape)
                    .border(1.dp, n.border, ChipShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    "Sample",
                    style = MaterialTheme.typography.labelSmall,
                    color = n.secondaryText
                )
            }
        }
    }
}

/** Numbered takeaways, Clera-style: numbered disc + text. */
@Composable
fun TakeawaysList(takeaways: List<String>) {
    val n = LocalNeutral.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        takeaways.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.Top) {
                // v0.1.11 (bug #2): the disc is 20dp — the same height as
                // one bodyMedium line (14sp / 20sp) — so its centre sits
                // on the FIRST line's centre. The old 28dp disc centred
                // ~4dp below the first line, landing in the gap between
                // lines 1 and 2 on multi-line items.
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(n.primaryButton),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        (index + 1).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = n.onPrimaryButton
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    item,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** The shaded summary box at the bottom of a card. */
@Composable
fun SummaryBox(summary: String) {
    val n = LocalNeutral.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(n.surface)
            .border(1.dp, n.border, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(
            "Summary",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            color = n.secondaryText
        )
    }
}

/** Full summary card (library list + detail share the same structure). */
@Composable
fun SummaryCard(
    summary: Summary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val n = LocalNeutral.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .pressScale()
            .clip(CardShape)
            .background(n.card)
            .border(1.dp, n.border, CardShape)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        CategoryRow(summary.category, summary.source, summary.isSample)
        Spacer(Modifier.height(12.dp))
        Text(
            summary.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "KEY TAKEAWAYS",
            style = sectionLabelStyle()
        )
        Spacer(Modifier.height(12.dp))
        if (summary.isError) {
            Text(
                summary.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = n.secondaryText
            )
        } else {
            TakeawaysList(summary.takeaways)
        }
        Spacer(Modifier.height(16.dp))
        if (!summary.isError) SummaryBox(summary.summary)
        Spacer(Modifier.height(12.dp))
        Text(
            formatDate(summary.createdAt),
            style = MaterialTheme.typography.bodySmall,
            color = n.mutedText
        )
    }
}

/**
 * Collapsed library card (v0.1.4, Clera-style): chips + title + date only.
 * No takeaways, no summary box, no "KEY TAKEAWAYS" label — the full
 * content lives on DetailScreen, reached by tapping the card. Collapsing
 * is also the biggest single scroll win: a full card laid out ~20
 * composables per item (5 takeaway rows + summary box); a collapsed card
 * lays out 6, so the list does roughly a third of the layout work per
 * item while scrolling.
 *
 * The card container is a sharedBounds element keyed "card-<id>", and
 * the chip row + title are sharedElements with the same keys Detail
 * uses, so tapping grows the card into the full-screen Detail and
 * backing out shrinks it into the same card again. The title IS a
 * sharedElement again (v0.1.12): v0.1.11 deleted it and the title just
 * crossfaded while the chip moved — JS's frame-by-frame check showed
 * the title staying put and fading away, then the Detail title
 * emerging from nowhere. The reflow that killed the shared title in
 * the first place was the FONT SIZE difference (titleLarge on the card
 * vs headlineMedium on Detail); both sides are now identical
 * (titleLarge, maxLines 3, Ellipsis), so the shared title is a pure
 * translation + GPU scale with zero reflow.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LibraryCard(
    summary: Summary,
    onClick: () -> Unit,
    sharedScope: SharedTransitionScope,
    animScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
    // v0.1.15: WhatsApp-style selection. Long-press enters selection mode;
    // a selected card gets a highlighted border + a check mark. Tap still
    // opens Detail when nothing is selected (handled by the caller).
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    val n = LocalNeutral.current
    // v0.1.5: the shared modifiers attach only once this card is actually
    // visible (onVisibilityChanged), so offscreen prefetched cards don't
    // pay the shared-transition bookkeeping cost during fast flings.
    // The SharedContentState objects are remembered unconditionally (a
    // remember call must never sit inside a conditional branch); only
    // the modifiers that consume them are gated on `sharedVisible`.
    var sharedVisible by remember { mutableStateOf(false) }
    // v0.1.6: one bounds transform for the whole card (container + chip +
    // title), remembered on the animator-scale-adjusted duration so a
    // recomposition mid-flight never rebuilds the spec.
    val context = LocalContext.current
    val durationMs = remember(context) { growDurationMs(context) }
    val boundsTransform = remember(durationMs) { growBoundsTransform(durationMs) }
    with(sharedScope) {
        val cardState = rememberSharedContentState(key = "card-${summary.id}")
        val chipState = rememberSharedContentState(key = "chip-${summary.id}")
        val titleState = rememberSharedContentState(key = "title-${summary.id}")
        Column(
            modifier = modifier
                .fillMaxWidth()
                .onVisibilityChanged(minFractionVisible = 0.001f) { sharedVisible = it }
                .pressScale()
                .then(
                    if (sharedVisible) {
                        Modifier.sharedBounds(
                            sharedContentState = cardState,
                            animatedVisibilityScope = animScope,
                            boundsTransform = boundsTransform,
                            // v0.1.5: ScaleToBounds, not RemeasureToBounds.
                            // The card is measured once at its lookahead
                            // size and GPU-scaled to the animated bounds,
                            // instead of being re-measured on every frame.
                            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                            clipInOverlayDuringTransition = OverlayClip(CardShape)
                        )
                    } else Modifier
                )
                .clip(CardShape)
                .background(if (selected) n.surface else n.card)
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else n.border,
                    CardShape
                )
                // v0.1.15: combinedClickable for long-press selection.
                // pressScale never consumes events, so both fire correctly.
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryRow(
                    summary.category,
                    summary.source,
                    summary.isSample,
                    modifier = Modifier
                        .weight(1f)
                        .skipToLookaheadSize()
                        .then(
                            if (sharedVisible) {
                                Modifier.sharedElement(
                                    sharedContentState = chipState,
                                    animatedVisibilityScope = animScope,
                                    boundsTransform = boundsTransform
                                )
                            } else Modifier
                        )
                )
                // v0.1.15: selection check mark, top-right of the card.
                if (selected) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                summary.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    // v0.1.12: title is a sharedElement again, same style
                    // on both sides, so it translates + scales with the
                    // chip as one unit — no reflow, no fade-out/in.
                    .skipToLookaheadSize()
                    .then(
                        if (sharedVisible) {
                            Modifier.sharedElement(
                                sharedContentState = titleState,
                                animatedVisibilityScope = animScope,
                                boundsTransform = boundsTransform
                            )
                        } else Modifier
                    )
            )
            Spacer(Modifier.height(12.dp))
            Text(
                formatDate(summary.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = n.mutedText
            )
        }
    }
}

fun formatDate(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000} min ago"
        diff < 86_400_000 -> "${diff / 3_600_000} hr ago"
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))
    }
}

/** Error text with a neutral warning treatment (no red — monochrome). */
@Composable
fun ErrorText(message: String) {
    val n = LocalNeutral.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(n.surface)
            .border(1.dp, n.border, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            "!",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = n.secondaryText,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Loading / progress line used while the key is being tested. */
@Composable
fun LoadingRow(text: String) {
    val n = LocalNeutral.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = n.secondaryText)
    }
}
