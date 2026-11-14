package com.js.geode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ library
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LibraryScreen(
    summaries: List<Summary>,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: String,
    onFilterChange: (String) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onSummariseLink: (String) -> Unit,
    // v0.1.15: WhatsApp-style selection, hoisted in GeodeApp so the
    // BackHandler can exit selection first. Long-press enters selection;
    // taps toggle while selecting; the top bar switches to the count +
    // actions (Share, Copy on single, Delete) and the first back press
    // exits selection instead of navigating away.
    selectedIds: Set<Long>,
    onToggleSelect: (Long) -> Unit,
    onLongPressCard: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onShareSelected: () -> Unit,
    onCopySelected: () -> Unit,
    onDeleteSelected: (Set<Long>) -> Unit,
    // Hoisted from GeodeApp (v0.1.4): the list state survives the Detail
    // round-trip so scroll position is kept, and `entered` survives too
    // so the staggered entrance plays once per session, never on return.
    listState: LazyListState,
    entered: Boolean,
    markEntered: () -> Unit,
    sharedScope: SharedTransitionScope,
    animScope: AnimatedVisibilityScope
) {
    val n = LocalNeutral.current
    var linkText by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }
    // v0.1.15: delete confirm — every selection delete goes through this
    // dialog first (same pattern as DetailScreen's delete dialog). No
    // trash bin: the confirm gate is the protection, and a deleted
    // summary is re-created in seconds by sharing the reel again.
    var showDeleteDialog by remember { mutableStateOf(false) }
    if (showDeleteDialog && selectedIds.isNotEmpty()) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    if (count == 1) "Delete 1 summary?" else "Delete $count summaries?",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = { Text("This can't be undone.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteDialog = false; onDeleteSelected(selectedIds) },
                    modifier = Modifier.pressScale()
                ) {
                    Text("Delete", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }, modifier = Modifier.pressScale()) {
                    Text("Cancel", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }
    // Tap-elsewhere unfocus (v0.1.11, bug #4): the shared helper covers
    // both fields on this screen — Search and Paste-a-link.
    val unfocus = rememberTapOutsideUnfocus()

    // Cached per (summaries, filter, query): typing in search or toggling
    // a filter used to re-filter the whole list on every recomposition.
    val filtered = remember(summaries, filter, query) {
        summaries.filter { s ->
            (filter == "All" || s.category.equals(filter, ignoreCase = true)) &&
                (query.isBlank() ||
                    s.title.contains(query, ignoreCase = true) ||
                    s.summary.contains(query, ignoreCase = true) ||
                    s.takeaways.any { it.contains(query, ignoreCase = true) })
        }
    }

    // First appearance only: flip `entered` once, after the first frame,
    // so the stagger runs. On return from Detail `entered` is already
    // true (it lives in GeodeApp), so the cards just appear — no replay.
    if (!entered) {
        LaunchedEffect(Unit) { markEntered() }
    }
    val enterOffsetPx = enterSlideOffsetPx()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .then(unfocus.containerModifier())
    ) {
        // Header — normal, or the selection top bar (v0.1.15).
        if (selectedIds.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClearSelection, modifier = Modifier.size(48.dp).pressScale()) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (selectedIds.size == 1) "1 selected" else "${selectedIds.size} selected",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        "Tap cards to select more",
                        style = MaterialTheme.typography.bodySmall,
                        color = n.secondaryText
                    )
                }
                // Single: Share, Copy, Delete. Multi: Share, Delete —
                // Copy drops out (copying several summaries at once makes
                // no sense). No three-dots menu: not enough options.
                IconButton(onClick = onShareSelected, modifier = Modifier.size(48.dp).pressScale()) {
                    Icon(Icons.Filled.Share, contentDescription = "Share selected")
                }
                if (selectedIds.size == 1) {
                    IconButton(onClick = onCopySelected, modifier = Modifier.size(48.dp).pressScale()) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy selected")
                    }
                }
                IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(48.dp).pressScale()) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Library", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (summaries.isEmpty()) "Your saved summaries live here"
                        else "${summaries.size} ${if (summaries.size == 1) "summary" else "summaries"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = n.secondaryText
                    )
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp).pressScale()) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }
        }

        // Search.
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search summaries…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.isNotBlank()) {
                { IconButton(onClick = { onQueryChange("") }, modifier = Modifier.pressScale()) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } }
            } else null,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .then(unfocus.fieldModifier("search")),
            colors = geodeTextFieldColors()
        )
        Spacer(Modifier.height(12.dp))

        // Filter chips.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Category.ALL.forEach { label ->
                FilterChip(label, selected = filter == label, onClick = { onFilterChange(label) })
            }
        }
        Spacer(Modifier.height(16.dp))

        if (filtered.isEmpty() && query.isBlank() && filter == "All") {
            // Empty state + paste-a-link entry point.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(n.surface)
                        .border(1.dp, n.border, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(32.dp), tint = n.secondaryText)
                }
                Spacer(Modifier.height(16.dp))
                Text("No summaries yet", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Share a reel from Instagram, Facebook, or YouTube Shorts — " +
                        "or paste a link below — and its summary will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = n.secondaryText,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                PasteLinkRow(
                    value = linkText,
                    onValueChange = { linkText = it; linkError = null },
                    error = linkError,
                    onSubmit = {
                        val match = ShareGate.firstMatch(linkText)
                        if (match == null) {
                            linkError = "That doesn't look like a reel or Shorts link. " +
                                "Paste an Instagram, Facebook, or YouTube Shorts link."
                        } else {
                            onSummariseLink(linkText)
                            linkText = ""
                        }
                    },
                    unfocus = unfocus
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 24.dp, end = 24.dp, bottom = 24.dp
                )
            ) {
                // Paste-a-link row at the top when the library is not empty.
                item(key = "paste", contentType = "paste") {
                    PasteLinkRow(
                        value = linkText,
                        onValueChange = { linkText = it; linkError = null },
                        error = linkError,
                        onSubmit = {
                            val match = ShareGate.firstMatch(linkText)
                            if (match == null) {
                                linkError = "That doesn't look like a reel or Shorts link. " +
                                    "Paste an Instagram, Facebook, or YouTube Shorts link."
                            } else {
                                onSummariseLink(linkText)
                                linkText = ""
                            }
                        },
                        unfocus = unfocus
                    )
                }
                if (filtered.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Nothing matches", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Try a different search or filter.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = n.secondaryText
                            )
                        }
                    }
                }
                // itemsIndexed (not indexOf per item — that was O(n^2) on
                // every list composition) with stable keys + contentType,
                // so scrolling/filtering only recomposes the cards that
                // actually changed instead of the whole visible list.
                itemsIndexed(filtered, key = { _, s -> s.id }, contentType = { _, _ -> "card" }) { idx, summary ->
                    AnimatedVisibility(
                        visible = entered,
                        enter = fadeIn(tween(300, delayMillis = (idx.coerceIn(0, 8)) * 60)) +
                            slideInVertically(tween(300, delayMillis = (idx.coerceIn(0, 8)) * 60)) { enterOffsetPx }
                    ) {
                        LibraryCard(
                            summary = summary,
                            // v0.1.15: in selection mode a tap toggles the
                            // card; otherwise it opens Detail as before.
                            onClick = {
                                if (selectedIds.isNotEmpty()) onToggleSelect(summary.id)
                                else onOpenDetail(summary.id)
                            },
                            onLongClick = { onLongPressCard(summary.id) },
                            selected = summary.id in selectedIds,
                            sharedScope = sharedScope,
                            animScope = animScope
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PasteLinkRow(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    onSubmit: () -> Unit,
    unfocus: TapOutsideUnfocus? = null
) {
    val n = LocalNeutral.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("Paste a reel or Shorts link…") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .then(unfocus?.fieldModifier("paste") ?: Modifier),
                colors = geodeTextFieldColors()
            )
            Spacer(Modifier.size(8.dp))
            PrimaryButton("Summarise", onClick = onSubmit)
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            ErrorText(error)
        }
    }
}

// ------------------------------------------------------------------- detail
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DetailScreen(
    summary: Summary,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onHelpful: (Boolean?) -> Unit,
    onRetry: () -> Unit,
    onAddApiKey: () -> Unit,
    sharedScope: SharedTransitionScope,
    animScope: AnimatedVisibilityScope
) {
    val context = LocalContext.current
    val n = LocalNeutral.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this summary?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("This can't be undone.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }, modifier = Modifier.pressScale()) {
                    Text("Delete", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }, modifier = Modifier.pressScale()) {
                    Text("Cancel", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }

    // v0.1.11: ONE expanding surface. The whole Detail — top bar + chip
    // + title + body — is the sharedBounds container, so the card grows
    // out of its own position expanding up AND down like an app opening,
    // instead of the body appearing below a moving title. The body still
    // fades in after the morph starts (v0.1.6: ~80ms hold, ~20% of the
    // morph — hero lands first, then the page follows, per Flow/SwingMX
    // — then ~250ms fade + 12dp rise, staggered ~40ms per section). On
    // back, the body hides first (fast 150ms fade) so the card can morph
    // back cleanly. The title IS a sharedElement again (v0.1.12):
    // identical to the card title (titleLarge, maxLines 3, Ellipsis) and
    // moving with the chip as one unit, so it translates up with the tags
    // instead of fading away and re-emerging.
    var bodyVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(80)
        bodyVisible = true
    }
    // v0.1.6: one bounds transform for the whole Detail (container +
    // chip), remembered on the animator-scale-adjusted duration so a
    // recomposition mid-flight never rebuilds the spec.
    val durationMs = remember(context) { growDurationMs(context) }
    val boundsTransform = remember(durationMs) { growBoundsTransform(durationMs) }
    // v0.1.6 corner morph (JS's table + MeloX): the card's 12dp corners
    // relax to 0dp as it reaches full screen. The flatten happens only
    // in the second half of the morph and is snapped to 0 on settle —
    // never judged by progress >= 1 (with a spring easing that fires
    // early). On cancel the Detail leaves composition and the Library
    // card's fixed 12dp clip takes over instantly, so the return is a
    // snap, never an animated radius.
    // (Dp.VectorConverter doesn't resolve against the cached Compose
    // 1.10 artifacts on this VM, so the converter is spelled out.)
    val cornerRadius = remember {
        Animatable(
            12.dp,
            TwoWayConverter({ AnimationVector1D(it.value) }, { it.value.dp })
        )
    }
    LaunchedEffect(Unit) {
        delay((durationMs / 2).toLong())
        cornerRadius.animateTo(0.dp, tween(durationMs / 2, easing = criticallyDampedEasing))
        cornerRadius.snapTo(0.dp)
    }
    val goBack = { bodyVisible = false; onBack() }
    val bodyOffsetPx = enterSlideOffsetPx()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // --- One expanding surface: the shared card container, now
        // holding the whole Detail (top bar + chip + title + body). ---
        Column(
            modifier = with(sharedScope) {
                Modifier
                    .fillMaxSize()
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "card-${summary.id}"),
                        animatedVisibilityScope = animScope,
                        boundsTransform = boundsTransform,
                        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                        clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(cornerRadius.value))
                    )
            }
                .clip(RoundedCornerShape(cornerRadius.value))
                .background(MaterialTheme.colorScheme.background)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = goBack, modifier = Modifier.size(48.dp).pressScale()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        val text = formatForShare(summary)
                        context.startActivity(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }.let { Intent.createChooser(it, "Share summary") }
                        )
                    },
                    modifier = Modifier.size(48.dp).pressScale()
                ) {
                    Icon(Icons.Filled.Share, contentDescription = "Share summary")
                }
                IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(48.dp).pressScale()) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete summary")
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                CategoryRow(
                    summary.category,
                    summary.source,
                    summary.isSample,
                    modifier = with(sharedScope) {
                        Modifier
                            .skipToLookaheadSize()
                            .sharedElement(
                                sharedContentState = rememberSharedContentState(key = "chip-${summary.id}"),
                                animatedVisibilityScope = animScope,
                                boundsTransform = boundsTransform
                            )
                    }
                )
                Spacer(Modifier.height(12.dp))
                // v0.1.19: title widths now match the card exactly.
                // The card title sits at 40dp insets per side (list
                // 24dp + card 16dp); this title is at 24dp (shell) +
                // 16dp here = 40dp per side. Same font/size AND same
                // width on both sides, so the shared title is a pure
                // translation with zero reflow; it moves up with the
                // chip as one unit. (v0.1.12 only matched the font —
                // the 32dp width gap is what snapped words between
                // lines mid-morph.)
                Text(
                    summary.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = with(sharedScope) {
                        Modifier
                            .padding(horizontal = 16.dp)
                            .skipToLookaheadSize()
                            .sharedElement(
                                sharedContentState = rememberSharedContentState(key = "title-${summary.id}"),
                                animatedVisibilityScope = animScope,
                                boundsTransform = boundsTransform
                            )
                    }
                )
                Spacer(Modifier.height(16.dp))
            }

                // --- Body: inside the shared bounds now (v0.1.11). Fades +
                // rises in after the morph starts, same timing as before. ---
                AnimatedVisibility(
                visible = bodyVisible,
                enter = fadeIn(tween(250)) +
                    slideInVertically(tween(250)) { bodyOffsetPx },
                exit = fadeOut(tween(150)),
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    // Section 1: takeaways (or the error block), no stagger.
                    AnimatedVisibility(
                        visible = bodyVisible,
                        enter = fadeIn(tween(250)) +
                            slideInVertically(tween(250)) { bodyOffsetPx },
                        exit = fadeOut(tween(150))
                    ) {
                        Column {
                            if (summary.isError) {
                                ErrorText(summary.summary)
                                Spacer(Modifier.height(16.dp))
                                PrimaryButton("Retry", onClick = onRetry, modifier = Modifier.fillMaxWidth())
                                if (summary.errorMessage?.contains("API key", ignoreCase = true) == true) {
                                    Spacer(Modifier.height(8.dp))
                                    SecondaryButton("Add API key", onClick = onAddApiKey, modifier = Modifier.fillMaxWidth())
                                }
                            } else {
                                Text("KEY TAKEAWAYS", style = sectionLabelStyle())
                                Spacer(Modifier.height(12.dp))
                                TakeawaysList(summary.takeaways)
                            }
                        }
                    }

                    if (!summary.isError) {
                        Spacer(Modifier.height(20.dp))
                        // Section 2: summary box, +40ms stagger.
                        AnimatedVisibility(
                            visible = bodyVisible,
                            enter = fadeIn(tween(250, delayMillis = 40)) +
                                slideInVertically(tween(250, delayMillis = 40)) { bodyOffsetPx },
                            exit = fadeOut(tween(150))
                        ) {
                            SummaryBox(summary.summary)
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    // Section 3: meta + actions + feedback, +80ms stagger.
                    AnimatedVisibility(
                        visible = bodyVisible,
                        enter = fadeIn(tween(250, delayMillis = 80)) +
                            slideInVertically(tween(250, delayMillis = 80)) { bodyOffsetPx },
                        exit = fadeOut(tween(150))
                    ) {
                        Column {
                            Text(
                                "${summary.source} · ${formatDate(summary.createdAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = n.mutedText
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton(
                                    "Open original",
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(summary.sourceUrl))
                                            )
                                        }
                                    }
                                )
                                SecondaryButton(
                                    "Copy",
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText("Geode summary", formatForShare(summary))
                                        )
                                        Toast.makeText(context, "Summary copied", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }

                            if (!summary.isError) {
                                Spacer(Modifier.height(24.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                        .background(n.surface)
                                        .border(1.dp, n.border, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                        .padding(16.dp)
                                ) {
                                    Text("Was this summary helpful?", style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ThumbButton(
                                            selected = summary.helpful == true,
                                            onClick = { onHelpful(if (summary.helpful == true) null else true) },
                                            icon = Icons.Filled.ThumbUp,
                                            label = "Yes"
                                        )
                                        ThumbButton(
                                            selected = summary.helpful == false,
                                            onClick = { onHelpful(if (summary.helpful == false) null else false) },
                                            icon = Icons.Filled.ThumbDown,
                                            label = "No"
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
                }
        }
    }
}

@Composable
private fun ThumbButton(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    val n = LocalNeutral.current
    Row(
        modifier = Modifier
            .pressScale()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(if (selected) n.primaryButton else androidx.compose.ui.graphics.Color.Transparent)
            .then(if (!selected) Modifier.border(1.dp, n.border, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) n.onPrimaryButton else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) n.onPrimaryButton else MaterialTheme.colorScheme.onBackground
        )
    }
}

fun formatForShare(summary: Summary): String {
    val sb = StringBuilder()
    sb.append(summary.title).append("\n\n")
    if (summary.takeaways.isNotEmpty()) {
        sb.append("Key takeaways:\n")
        summary.takeaways.forEachIndexed { i, t -> sb.append("${i + 1}. $t\n") }
        sb.append("\n")
    }
    sb.append(summary.summary).append("\n\n")
    sb.append("— Saved with Geode")
    return sb.toString()
}

// ----------------------------------------------------------------- settings
@Composable
fun SettingsScreen(
    prefs: Prefs,
    gemini: GeminiClient,
    repository: Repository,
    onBack: () -> Unit,
    onOpenApiKey: Boolean,
    onApiKeyChanged: () -> Unit,
    onThemeChanged: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val n = LocalNeutral.current
    // Tap-elsewhere unfocus (v0.1.11, bug #4): the shared helper —
    // same behaviour as the old hand-written handler here, unified so
    // every field in the app gets it.
    val unfocus = rememberTapOutsideUnfocus()
    var theme by remember { mutableStateOf(prefs.theme) }
    var notificationsOn by remember { mutableStateOf(prefs.notificationsEnabled) }
    var showDeleteAll by remember { mutableStateOf(false) }
    var showClearSamples by remember { mutableStateOf(false) }
    var keySaved by remember { mutableStateOf(prefs.getApiKey() != null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var editingKey by remember { mutableStateOf(onOpenApiKey) }
    var keyText by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    if (showDeleteAll) {
        AlertDialog(
            onDismissRequest = { showDeleteAll = false },
            title = { Text("Delete all data?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("Every summary in your library will be deleted. This can't be undone.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteAll = false
                    scope.launch { repository.deleteAll() }
                    Toast.makeText(context, "All data deleted", Toast.LENGTH_SHORT).show()
                }) { Text("Delete all", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAll = false }, modifier = Modifier.pressScale()) { Text("Cancel", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            }
        )
    }
    if (showClearSamples) {
        AlertDialog(
            onDismissRequest = { showClearSamples = false },
            title = { Text("Clear sample summaries?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("The three sample cards will be removed. Your own summaries stay.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    showClearSamples = false
                    scope.launch { repository.deleteSamples() }
                    Toast.makeText(context, "Samples cleared", Toast.LENGTH_SHORT).show()
                }) { Text("Clear samples", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showClearSamples = false }, modifier = Modifier.pressScale()) { Text("Cancel", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            }
        )
    }

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).pressScale()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 8.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .then(unfocus.containerModifier())
        ) {
            // --- API key
            Text("GEMINI API KEY", style = sectionLabelStyle())
            Spacer(Modifier.height(12.dp))
            if (keySaved) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        prefs.maskedApiKey().orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Filled.Check, contentDescription = null, tint = n.secondaryText, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Saved", style = MaterialTheme.typography.bodyMedium, color = n.secondaryText)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (prefs.keyStoreEncrypted) "Stored encrypted on this phone. Only ever sent to Google."
                    else "Stored on this phone (encrypted storage was unavailable on this device). Only ever sent to Google.",
                    style = MaterialTheme.typography.bodySmall,
                    color = n.mutedText
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Test", onClick = {
                        val key = prefs.getApiKey() ?: return@SecondaryButton
                        testing = true
                        testResult = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { gemini.testKey(key) }
                            result.fold(
                                onSuccess = { testResult = "OK" },
                                onFailure = { testResult = it.message }
                            )
                            testing = false
                        }
                    })
                    SecondaryButton("Change", onClick = { editingKey = true; keyText = "" })
                    SecondaryButton("Remove", onClick = {
                        prefs.clearApiKey()
                        keySaved = false
                        testResult = null
                        onApiKeyChanged()
                    })
                }
                if (testing) {
                    Spacer(Modifier.height(12.dp))
                    LoadingRow("Checking your key with Google…")
                } else if (testResult != null) {
                    Spacer(Modifier.height(12.dp))
                    if (testResult == "OK") {
                        Text("Key works.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        ErrorText(testResult!!)
                    }
                }
            }
            if (!keySaved || editingKey) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text("Paste your API key") },
                    singleLine = true,
                    visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }, modifier = Modifier.pressScale()) {
                            Icon(
                                if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showKey) "Hide key" else "Show key"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(unfocus.fieldModifier("settings-key")),
                    colors = geodeTextFieldColors()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Save and test", onClick = {
                        val key = keyText.trim()
                        if (key.length < 10) {
                            testResult = "That doesn't look like a key yet — paste the full key from AI Studio."
                            return@PrimaryButton
                        }
                        testing = true
                        testResult = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { gemini.testKey(key) }
                            result.fold(
                                onSuccess = {
                                    prefs.setApiKey(key)
                                    keySaved = true
                                    editingKey = false
                                    testResult = "OK"
                                    onApiKeyChanged()
                                },
                                onFailure = { testResult = it.message }
                            )
                            testing = false
                        }
                    })
                    SecondaryButton("Open AI Studio", onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com"))
                        )
                    })
                }
                if (testing) {
                    Spacer(Modifier.height(12.dp))
                    LoadingRow("Checking your key with Google…")
                } else if (testResult != null && testResult != "OK") {
                    Spacer(Modifier.height(12.dp))
                    ErrorText(testResult!!)
                }
            }

            Spacer(Modifier.height(32.dp))
            // --- Notifications
            Text("NOTIFICATIONS", style = sectionLabelStyle())
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Summary ready alerts", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Get told when a summary is done. Off means you have to open the app to check.",
                        style = MaterialTheme.typography.bodySmall,
                        color = n.secondaryText
                    )
                }
                Switch(
                    checked = notificationsOn,
                    onCheckedChange = {
                        notificationsOn = it
                        prefs.notificationsEnabled = it
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = n.onPrimaryButton,
                        checkedTrackColor = n.primaryButton,
                        uncheckedThumbColor = n.mutedText,
                        uncheckedTrackColor = n.border
                    )
                )
            }

            Spacer(Modifier.height(32.dp))
            // --- Appearance
            Text("APPEARANCE", style = sectionLabelStyle())
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                    FilterChip(
                        label,
                        selected = theme == value,
                        onClick = {
                            theme = value
                            prefs.theme = value
                            onThemeChanged(value)
                        }
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
            // --- Library
            Text("LIBRARY", style = sectionLabelStyle())
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Clear samples", onClick = { showClearSamples = true })
                SecondaryButton("Delete all data", onClick = { showDeleteAll = true })
            }

            Spacer(Modifier.height(32.dp))
            // --- About
            Text("ABOUT", style = sectionLabelStyle())
            Spacer(Modifier.height(12.dp))
            Text("Geode 0.1.4", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Summaries are made on your phone with your own Gemini key. " +
                    "No server, no account, no tracking. Summaries are stored only on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = n.secondaryText
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
