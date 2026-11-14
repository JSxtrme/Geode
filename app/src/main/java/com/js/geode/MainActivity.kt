package com.js.geode

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_SUMMARY_ID = "open_summary_id"
        const val EXTRA_OPEN_API_KEY = "open_api_key"
    }

    private lateinit var prefs: Prefs
    private lateinit var repository: Repository
    private lateinit var gemini: GeminiClient

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort; the app works without it */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge: content draws behind the system bars; every screen
        // then reserves its own space with statusBarsPadding() /
        // navigationBarsPadding() instead of stretching under them.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repository = RepositoryProvider.get(this)
        gemini = GeminiClient()
        Notifications.ensureChannel(this)

        val openSummaryId = intent?.getLongExtra(EXTRA_OPEN_SUMMARY_ID, 0L) ?: 0L
        val openApiKey = intent?.getBooleanExtra(EXTRA_OPEN_API_KEY, false) ?: false

        setContent {
            GeodeApp(
                prefs = prefs,
                repository = repository,
                gemini = gemini,
                initialSummaryId = openSummaryId,
                initialOpenApiKey = openApiKey,
                requestNotificationPermission = ::requestNotificationPermission
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Notification taps carry a new summary id; recreate so the new
        // extras are picked up. Cheap and correct for v0.1.
        recreate()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private sealed interface Screen {
    data object Library : Screen
    data class Detail(val id: Long) : Screen
    data object Settings : Screen
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun GeodeApp(
    prefs: Prefs,
    repository: Repository,
    gemini: GeminiClient,
    initialSummaryId: Long,
    initialOpenApiKey: Boolean,
    requestNotificationPermission: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var themePref by remember { mutableStateOf(prefs.theme) }
    var screen by remember {
        mutableStateOf<Screen>(
            if (initialSummaryId != 0L) Screen.Detail(initialSummaryId) else Screen.Library
        )
    }
    var showOnboarding by remember { mutableStateOf(!prefs.onboardingDone) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var openApiKey by remember { mutableStateOf(initialOpenApiKey) }
    var loading by remember { mutableStateOf(true) }

    // v0.1.6: serialise Library <-> Detail transitions (MeloX). A return-
    // then-immediate-retap used to freeze a card mid-morph in the overlay;
    // FORWARD taps while a transition is in flight are ignored, not
    // queued. The flag clears once the transition has settled + a 60ms
    // margin, and it always clears on a timer, so it can never stick.
    // v0.1.15: the BACK path bypasses the lock — a back press/gesture
    // mid-grow reverses the morph immediately instead of being swallowed
    // until it finishes. The clear timer is re-armed on every navigation
    // (previous timer cancelled), so the latest direction always gets
    // its full settle window and the flag can never stick after a
    // mid-flight cancel.
    var transitionBusy by remember { mutableStateOf(false) }
    var clearJob by remember { mutableStateOf<Job?>(null) }
    val growMs = remember(context) { growDurationMs(context) }
    fun armBusyTimer() {
        clearJob?.cancel()
        clearJob = scope.launch { delay(growMs + 60L); transitionBusy = false }
    }
    fun goDetail(id: Long) {
        if (transitionBusy) return
        transitionBusy = true
        screen = Screen.Detail(id)
        armBusyTimer()
    }
    fun goBackFromDetail() {
        if (screen !is Screen.Detail) return
        transitionBusy = true
        screen = Screen.Library
        armBusyTimer()
    }

    // v0.1.15: WhatsApp-style selection, hoisted here so the BackHandler
    // can exit selection first (first back press clears the selection;
    // only a second back press navigates away).
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    // Hoisted library state (v0.1.4): both live in GeodeApp so they survive
    // the Detail round-trip. The LazyListState keeps the scroll position
    // when coming back from a card; `libraryEntered` makes the staggered
    // entrance play once per session instead of replaying on every return.
    val libraryListState = rememberLazyListState()
    var libraryEntered by remember { mutableStateOf(false) }

    val summaries by repository.summaries.collectAsState()

    // First run: seed the three sample cards once, then load.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (!prefs.samplesSeeded) {
                Samples.all(System.currentTimeMillis()).forEach { repository.insert(it) }
                prefs.samplesSeeded = true
            }
            // v0.1.14: failed shares are never saved to the Library, so
            // clear any error rows left over from earlier versions.
            repository.deleteErrors()
            repository.refresh()
        }
        loading = false
    }

    // Status/navigation-bar icons must stay legible: dark icons on light
    // theme, light icons on dark theme. Updated whenever the theme changes.
    val view = androidx.compose.ui.platform.LocalView.current
    val darkTheme = when (themePref) {
        "light" -> false
        "dark" -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    androidx.compose.runtime.SideEffect {
        val window = (context as? android.app.Activity)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    GeodeTheme(themePref = themePref) {
        if (showOnboarding) {
            OnboardingScreen(
                prefs = prefs,
                gemini = gemini,
                requestNotificationPermission = requestNotificationPermission,
                onFinish = {
                    showOnboarding = false
                    screen = Screen.Library
                }
            )
            return@GeodeTheme
        }
        if (loading) {
            Box(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground)
            }
            return@GeodeTheme
        }

        // v0.1.15: back exits selection first (clears it, stays on the
        // Library); only with nothing selected does it navigate. The
        // Detail back path bypasses transitionBusy, so a back press or
        // gesture mid-grow reverses the morph immediately.
        BackHandler(enabled = selectedIds.isNotEmpty() || screen != Screen.Library) {
            when {
                selectedIds.isNotEmpty() -> selectedIds = emptySet()
                screen is Screen.Detail -> goBackFromDetail()
                screen is Screen.Settings -> screen = Screen.Library
            }
        }

        // SharedTransitionLayout is the ancestor of Library + Detail, so a
        // card can grow into the full-screen Detail and shrink back.
        SharedTransitionLayout {
            val sharedScope = this
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                // Library <-> Detail: staggered fade, ~400ms total.
                // The shared card grow is the motion here; a slide on
                // top of it would fight the morph. v0.1.12: the exit is
                // fast (200ms) and the enter is delayed (~150ms) so the
                // two screens never sit at half alpha together — the
                // mid-transition dim that made the title look like it
                // faded away and re-emerged. The shared elements (card
                // bounds, chip, title) carry the continuity through the
                // morph instead. Other switches keep slide + fade.
                val isDetailTransition =
                    initialState is Screen.Detail || targetState is Screen.Detail
                if (isDetailTransition) {
                    fadeIn(tween(250, delayMillis = 150)) togetherWith fadeOut(tween(200))
                } else {
                    (slideInHorizontally(tween(300)) { 32 } + fadeIn(tween(300))) togetherWith
                        (slideOutHorizontally(tween(300)) { -32 } + fadeOut(tween(250)))
                }
            },
            label = "screenTransition"
        ) { target ->
        val animScope = this
        when (val s = target) {
            is Screen.Library -> LibraryScreen(
                summaries = summaries,
                query = query,
                onQueryChange = { query = it },
                filter = filter,
                onFilterChange = { filter = it },
                onOpenDetail = { id -> goDetail(id) },
                onOpenSettings = { screen = Screen.Settings },
                onSummariseLink = { link ->
                    val match = ShareGate.firstMatch(link) ?: return@LibraryScreen
                    PendingQueue.append(context, match.url, match.source)
                    SummaryService.start(context, match.url, match.source)
                    Toast.makeText(context, "Reel sent to Geode", Toast.LENGTH_SHORT).show()
                },
                // v0.1.15: selection actions. Share sends the selected
                // summaries as text (same formatForShare text as Detail);
                // Copy is single-card only; Delete runs after the confirm
                // dialog in LibraryScreen and clears the selection.
                selectedIds = selectedIds,
                onToggleSelect = { id ->
                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                },
                onLongPressCard = { id -> selectedIds = selectedIds + id },
                onClearSelection = { selectedIds = emptySet() },
                onShareSelected = {
                    val selected = summaries.filter { it.id in selectedIds }
                    if (selected.isNotEmpty()) {
                        val text = selected.joinToString("\n\n— — —\n\n") { formatForShare(it) }
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                },
                                "Share summaries"
                            )
                        )
                    }
                },
                onCopySelected = {
                    val selected = summaries.firstOrNull { it.id in selectedIds }
                    if (selected != null) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("Geode summary", formatForShare(selected))
                        )
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                },
                onDeleteSelected = { ids ->
                    scope.launch {
                        ids.forEach { repository.delete(it) }
                    }
                    selectedIds = emptySet()
                    Toast.makeText(
                        context,
                        if (ids.size == 1) "Summary deleted" else "${ids.size} summaries deleted",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                listState = libraryListState,
                entered = libraryEntered,
                markEntered = { libraryEntered = true },
                sharedScope = sharedScope,
                animScope = animScope
            )
            is Screen.Detail -> {
                val summary = summaries.firstOrNull { it.id == s.id }
                if (summary == null) {
                    // Deleted elsewhere; back to the library.
                    LaunchedEffect(Unit) { screen = Screen.Library }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground)
                    }
                } else {
                    DetailScreen(
                        summary = summary,
                        onBack = { goBackFromDetail() },
                        onDelete = {
                            scope.launch { repository.delete(summary.id) }
                            goBackFromDetail()
                        },
                        onHelpful = { helpful ->
                            scope.launch { repository.setHelpful(summary.id, helpful) }
                        },
                        onRetry = {
                            SummaryService.start(
                                context = context,
                                url = summary.sourceUrl,
                                source = runCatching {
                                    Source.valueOf(summary.source.uppercase())
                                }.getOrDefault(Source.INSTAGRAM),
                                deleteErrorId = summary.id
                            )
                            goBackFromDetail()
                        },
                        onAddApiKey = {
                            openApiKey = true
                            screen = Screen.Settings
                        },
                        sharedScope = sharedScope,
                        animScope = animScope
                    )
                }
            }
            is Screen.Settings -> SettingsScreen(
                prefs = prefs,
                gemini = gemini,
                repository = repository,
                onBack = { screen = Screen.Library },
                onOpenApiKey = openApiKey,
                onApiKeyChanged = { openApiKey = false },
                onThemeChanged = { themePref = it }
            )
        }
        }
        }
    }
}
