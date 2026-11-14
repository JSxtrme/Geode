package com.js.geode

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class KeyTestState { IDLE, TESTING, OK, ERROR }

/**
 * First-run onboarding, 5 steps: Welcome -> API key -> Notifications ->
 * How to use -> Done. The API key step is the heart of it: most people
 * have never opened AI Studio, so it explains WHY first, then walks
 * through the exact taps.
 */
@Composable
fun OnboardingScreen(
    prefs: Prefs,
    gemini: GeminiClient,
    requestNotificationPermission: () -> Unit,
    onFinish: () -> Unit
) {
    var page by remember { mutableIntStateOf(0) }
    var keyOk by remember { mutableStateOf(prefs.getApiKey() != null) }
    // v0.1.16: "Skip for now" must not skip silently — it opens a caution
    // dialog first, because Geode does nothing without a key.
    var showSkipDialog by remember { mutableStateOf(false) }
    val n = LocalNeutral.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            // No imePadding() here on purpose (v0.1.3 keyboard fix): the
            // outer pager + bottom CTA row must NOT re-lay out on every
            // keyboard frame. IME handling lives only on the ApiKeyStep's
            // scrollable column below, which is the one place a text
            // field needs to clear the keyboard. The CTA row stays put at
            // the bottom of the (adjustResize-shrunk) window, above the
            // gesture bar, and the pager / staggered entrances never
            // replay — their state (page / entered) doesn't change when
            // the keyboard opens or closes.
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        // Progress dots.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(5) { i ->
                Box(
                    modifier = Modifier
                        .size(width = if (i == page) 24.dp else 8.dp, height = 8.dp)
                        .clip(CircleShape)
                        .background(if (i <= page) n.primaryButton else n.border)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Box(modifier = Modifier.weight(1f)) {
            // Fluid pager: slide + fade, 350ms, direction follows travel.
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val forward = targetState > initialState
                    val slide = if (forward) 48 else -48
                    (slideInHorizontally(tween(350)) { slide } + fadeIn(tween(350))) togetherWith
                        (slideOutHorizontally(tween(350)) { -slide } + fadeOut(tween(250)))
                },
                label = "onboardingPager"
            ) { p ->
                when (p) {
                    0 -> WelcomeStep()
                    1 -> ApiKeyStep(prefs, gemini, onKeyVerified = { keyOk = true })
                    2 -> NotificationsStep()
                    3 -> HowToStep()
                    else -> DoneStep(prefs)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        // v0.1.16 skip-confirm dialog (same AlertDialog style as the
        // delete confirms): skipping means the app cannot summarise
        // anything, so the user confirms they mean it. "Add API key"
        // just closes the dialog and stays on the key screen.
        if (showSkipDialog) {
            AlertDialog(
                onDismissRequest = { showSkipDialog = false },
                icon = {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = { Text("Skip adding your key?", style = MaterialTheme.typography.titleLarge) },
                text = {
                    Text(
                        "Geode won't work without an API key. Are you sure you want to skip? It only takes 2 minutes.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showSkipDialog = false; page = 2 }, modifier = Modifier.pressScale()) {
                        Text("Skip", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSkipDialog = false }, modifier = Modifier.pressScale()) {
                        Text("Add API key", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
        when (page) {
            1 -> {
                // API key step: Continue is only enabled once the key works,
                // but Skip is always available — behind the confirm dialog.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showSkipDialog = true }, modifier = Modifier.pressScale()) {
                        Text("Skip for now", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    PrimaryButton("Continue", onClick = { page = 2 }, enabled = keyOk)
                }
            }
            2 -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { page = 3 }, modifier = Modifier.pressScale()) {
                        Text("Skip", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    PrimaryButton("Allow notifications", onClick = {
                        requestNotificationPermission()
                        page = 3
                    })
                }
            }
            4 -> {
                PrimaryButton(
                    "Open my library",
                    onClick = {
                        prefs.onboardingDone = true
                        onFinish()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            else -> {
                PrimaryButton(
                    "Continue",
                    onClick = { page++ },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WelcomeStep() {
    val n = LocalNeutral.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        // Geode logo — the locked G (v0.1.10, geode_logo.xml: dark tile +
        // white arcs + purple crossbar, exactly as designed). No tint: the
        // tile makes it read identically in light and dark mode.
        AnimatedVisibility(
            visible = entered,
            enter = scaleIn(
                spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                initialScale = 0.8f
            ) + fadeIn(tween(250))
        ) {
            Image(
                painter = painterResource(R.drawable.geode_logo),
                contentDescription = null,
                modifier = Modifier.size(96.dp)
            )
        }
        Spacer(Modifier.height(24.dp))
        StaggeredIn(0, entered) {
            Text("Geode", style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Share a reel. Get notes back.",
                style = MaterialTheme.typography.titleLarge,
                color = n.secondaryText
            )
        }
        Spacer(Modifier.height(32.dp))
        val bullets = listOf(
            Triple(Icons.Filled.Share, "Share", "Send a reel from Instagram, Facebook, or YouTube Shorts to Geode."),
            Triple(Icons.Filled.PlayArrow, "Summarise", "Gemini turns it into key takeaways and a short summary — in English, even for Hindi reels."),
            Triple(Icons.Filled.Search, "Find it later", "Everything is saved in a searchable library on your phone. No account, no server.")
        )
        bullets.forEachIndexed { i, (icon, title, body) ->
            StaggeredIn(i + 1, entered) {
                OnboardingBullet(icon, title, body)
            }
            if (i < bullets.lastIndex) Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun OnboardingBullet(icon: ImageVector, title: String, body: String) {
    val n = LocalNeutral.current
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(n.surface)
                .border(1.dp, n.border, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = n.secondaryText)
        }
    }
}

@Composable
private fun ApiKeyStep(prefs: Prefs, gemini: GeminiClient, onKeyVerified: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var keyText by remember { mutableStateOf(prefs.getApiKey().orEmpty()) }
    var showKey by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(KeyTestState.IDLE) }
    var error by remember { mutableStateOf<String?>(null) }
    // The last complete key that was auto-tested, so the same key is never
    // auto-tested twice (recomposition, returning to this step, or retyping
    // the same key after a successful test). Editing to a different key
    // clears the way for that key to auto-test once.
    var lastAutoTestedKey by remember { mutableStateOf<String?>(null) }
    // True once the step has mounted once with its initial (possibly
    // pre-filled) key, so a saved key sitting in the field is never
    // auto-tested just for being there — only a fresh paste/type counts.
    var keySeen by remember { mutableStateOf(false) }
    val n = LocalNeutral.current

    // Hoisted scroll state + bring-into-view plumbing (v0.1.8): the field
    // must scroll smoothly into view above the keyboard when tapped, and
    // taps elsewhere must unfocus it. The scroll state used to be created
    // inline in the Column modifier — it has to live here so the scroll
    // helpers below can read/animate it.
    val scrollState = rememberScrollState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val fieldInteractionSource = remember { MutableInteractionSource() }
    var columnHeightPx by remember { mutableStateOf(0) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    // Tap-elsewhere unfocus (v0.1.11, bug #4): the shared helper —
    // same behaviour as the old hand-written handler here.
    val unfocus = rememberTapOutsideUnfocus()

    // Smoothly scroll the key field to mid-screen above the keyboard
    // (the 3rd-screenshot height: field fully visible, Test key below
    // it). bringIntoView alone only scrolls the bare minimum, leaving
    // the field hugging the keyboard edge, so after it settles we lift
    // the page a further ~18% of the viewport. Runs after a short delay
    // so the IME inset has settled; animated, never a jump.
    fun scrollFieldIntoView() {
        scrollJob?.cancel()
        scrollJob = scope.launch {
            delay(250)
            bringIntoViewRequester.bringIntoView()
            delay(80)
            val viewport = columnHeightPx.toFloat()
            if (viewport > 0f) {
                val extra = (viewport * 0.18f).toInt()
                val target = (scrollState.value + extra).coerceIn(0, scrollState.maxValue)
                scrollState.animateScrollTo(target, tween(350, easing = FastOutSlowInEasing))
            }
        }
    }

    // Re-taps while the field is already focused don't refire
    // onFocusChanged, so listen for presses on the field itself too.
    LaunchedEffect(fieldInteractionSource) {
        fieldInteractionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) scrollFieldIntoView()
        }
    }

    // Gemini keys come in two shapes, not one: old keys start with AIza
    // and are exactly 39 characters, new (2026) keys start with AQ. and
    // are 53 characters. A key is complete when its prefix matches and it
    // has at least that many characters (longer is fine — never truncate,
    // only trim whitespace). Any other prefix never counts, however long.
    fun isCompleteKey(trimmed: String): Boolean =
        (trimmed.startsWith("AIza") && trimmed.length >= 39) ||
            (trimmed.startsWith("AQ.") && trimmed.length >= 53)

    // The one Test-key flow, shared by the manual button and the auto-test:
    // same loading state, same success/error UI, same Continue gating.
    fun runTest(key: String) {
        if (key.length < 10) {
            error = "That doesn't look like a key yet — paste the full key from AI Studio."
            state = KeyTestState.ERROR
            return
        }
        state = KeyTestState.TESTING
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { gemini.testKey(key) }
            result.fold(
                onSuccess = {
                    prefs.setApiKey(key)
                    onKeyVerified()
                    state = KeyTestState.OK
                },
                onFailure = { e ->
                    error = e.message ?: "Something went wrong. Please try again."
                    state = KeyTestState.ERROR
                }
            )
        }
    }

    // Auto-hide + auto-test: the moment the field holds a complete key
    // (pasted or typed to the last character), drop the keyboard, unfocus
    // the field and run the test — once per distinct complete key.
    LaunchedEffect(keyText) {
        if (!keySeen) {
            keySeen = true
            return@LaunchedEffect
        }
        val trimmed = keyText.trim()
        if (isCompleteKey(trimmed) && trimmed != lastAutoTestedKey &&
            state != KeyTestState.TESTING
        ) {
            lastAutoTestedKey = trimmed
            keyboardController?.hide()
            focusManager.clearFocus()
            runTest(trimmed)
        }
    }

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    // IME handling is scoped here and nowhere else in onboarding: only
    // this scrollable column gets imePadding(), so when the keyboard
    // slides up only the key field / Test key / error row move and scroll
    // — the pager, progress dots and bottom CTA row above are untouched.
    Column(
        modifier = Modifier
            .verticalScroll(scrollState)
            .imePadding()
            .onGloballyPositioned { coords ->
                columnHeightPx = coords.size.height
            }
            .then(unfocus.containerModifier())
    ) {
        StaggeredIn(0, entered) {
            Text("Your free Gemini key", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Geode has no server, so nobody else pays for your summaries. " +
                    "You use your own free Google key. It stays on this phone and is " +
                    "only ever sent to Google.",
                style = MaterialTheme.typography.bodyMedium,
                color = n.secondaryText
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("HOW TO GET IT", style = sectionLabelStyle())
        Spacer(Modifier.height(12.dp))
        // v0.1.17: step 2 fixed — the deep link opens /api-keys
        // directly, so the user NEVER sees the landing page and its
        // "Get started" pill. Logged-out users go straight to Google's
        // account chooser; logged-in users land on the keys page with
        // no sign-in at all. Hence the conditional wording below.
        val steps = listOf(
            "Tap Open AI Studio below — it opens in your browser.",
            "If it asks you to sign in, choose your Google account (or Use another account).",
            "Accept the terms if asked — Google makes a free project and key for you.",
            "On the API Keys page, find the key called Default Gemini API Key and tap the copy icon next to it.",
            "Come back here and paste it below."
        )
        steps.forEachIndexed { i, step ->
            StaggeredIn(i + 1, entered) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(n.primaryButton),
                        contentAlignment = Alignment.Center
                    ) {
                        Text((i + 1).toString(), style = MaterialTheme.typography.labelMedium, color = n.onPrimaryButton)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            if (i < steps.lastIndex) Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton(
            "Open AI Studio",
            onClick = {
                // v0.1.16: straight to the API Keys page, so after sign-in
                // the user lands on the keys instead of the dashboard.
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/api-keys"))
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "No card or billing needed — the free tier is enough for reels.",
            style = MaterialTheme.typography.bodySmall,
            color = n.mutedText
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = keyText,
            // Trim leading/trailing whitespace/newlines: a paste from AI
            // Studio often carries a trailing newline, and a key never
            // contains spaces — the stored key stays clean.
            onValueChange = { raw -> keyText = raw.trim(); state = KeyTestState.IDLE; error = null },
            label = { Text("Paste your API key") },
            placeholder = { Text("AQ.…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
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
                .bringIntoViewRequester(bringIntoViewRequester)
                .then(unfocus.fieldModifier("api-key"))
                .onFocusChanged { if (it.isFocused) scrollFieldIntoView() },
            interactionSource = fieldInteractionSource,
            colors = geodeTextFieldColors()
        )
        Spacer(Modifier.height(12.dp))
        // v0.1.16: the button says what it is doing, not just going grey.
        // While testing it shows "Testing key…" with a small loader and
        // stays disabled; once a key is verified it becomes tappable again
        // as "Test key again" (editing the key resets state to IDLE, so
        // the label goes back to "Test key"). The LoadingRow below stays.
        if (state == KeyTestState.TESTING) {
            PrimaryButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth()
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = n.disabledButtonText
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Testing key…",
                    style = MaterialTheme.typography.labelLarge,
                    color = n.disabledButtonText
                )
            }
        } else {
            PrimaryButton(
                if (state == KeyTestState.OK) "Test key again" else "Test key",
                onClick = { runTest(keyText.trim()) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(16.dp))
        when (state) {
            KeyTestState.TESTING -> LoadingRow("Checking your key with Google…")
            KeyTestState.OK -> AnimatedVisibility(
                visible = true,
                enter = scaleIn(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f) + fadeIn(tween(250))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(n.primaryButton),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = n.onPrimaryButton, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Key verified — you're good to go.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            KeyTestState.ERROR -> error?.let { ErrorText(it) }
            KeyTestState.IDLE -> {
                if (prefs.getApiKey() != null) {
                    Text(
                        "A key is already saved (${prefs.maskedApiKey()}). You can change it in Settings later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = n.mutedText
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationsStep() {
    val n = LocalNeutral.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        AnimatedVisibility(
            visible = entered,
            enter = scaleIn(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.8f) + fadeIn(tween(250))
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(n.surface)
                    .border(1.dp, n.border, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        StaggeredIn(0, entered) {
            Text("Get told when it's ready", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Summaries take 10–45 seconds. With notifications on, Geode " +
                    "tells you the moment one is ready — you don't have to open the " +
                    "app to check.",
                style = MaterialTheme.typography.bodyMedium,
                color = n.secondaryText
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "You can turn this off anytime in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = n.mutedText
            )
        }
    }
}

@Composable
private fun HowToStep() {
    val n = LocalNeutral.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        StaggeredIn(0, entered) {
            Text("How to use it", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Three taps, then wait for the notification.",
                style = MaterialTheme.typography.bodyMedium,
                color = n.secondaryText
            )
        }
        Spacer(Modifier.height(24.dp))
        val steps = listOf(
            "Share" to "In Instagram, Facebook, or YouTube, tap Share on a reel or Short.",
            "Pick Geode" to "Tap Geode in the share sheet. You'll see “Reel sent to Geode”.",
            "Open the card" to "When the “Summary ready in Geode” notification arrives, tap it to read the takeaways and summary."
        )
        steps.forEachIndexed { i, (title, body) ->
            StaggeredIn(i + 1, entered) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(n.primaryButton),
                        contentAlignment = Alignment.Center
                    ) {
                        Text((i + 1).toString(), style = MaterialTheme.typography.labelMedium, color = n.onPrimaryButton)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(body, style = MaterialTheme.typography.bodyMedium, color = n.secondaryText)
                    }
                }
            }
            if (i < steps.lastIndex) Spacer(Modifier.height(20.dp))
        }
        // Tip: a little head room above it (10dp) so it doesn't hug the
        // "Open the card" text — v0.1.4, JS's screenshot.
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = n.mutedText, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                "Tip: you can also paste a link from inside the app.",
                style = MaterialTheme.typography.bodySmall,
                color = n.mutedText
            )
        }
    }
}

@Composable
private fun DoneStep(prefs: Prefs) {
    val n = LocalNeutral.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        AnimatedVisibility(
            visible = entered,
            enter = scaleIn(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f) + fadeIn(tween(250))
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(n.primaryButton),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = n.onPrimaryButton, modifier = Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        StaggeredIn(0, entered) {
            Text("You're all set", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                if (prefs.getApiKey() != null) {
                    "Your Gemini key is saved and verified. Share your first reel and " +
                        "your summary will appear here."
                } else {
                    "You skipped the API key step. You can add your free key anytime " +
                        "in Settings — sharing a reel before that will remind you to add it."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = n.secondaryText
            )
        }
    }
}
