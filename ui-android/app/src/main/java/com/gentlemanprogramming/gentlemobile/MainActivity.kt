package com.gentlemanprogramming.gentlemobile

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import android.provider.OpenableColumns
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.gentlemanprogramming.gentlemobile.notifications.AgentAlertTracker
import com.gentlemanprogramming.gentlemobile.notifications.ConnectionService
import com.gentlemanprogramming.gentlemobile.notifications.ForegroundHub
import com.gentlemanprogramming.gentlemobile.notifications.ForegroundServiceGate
import com.gentlemanprogramming.gentlemobile.notifications.TrackedAgent
import com.gentlemanprogramming.gentlemobile.notifications.ensureChannels
import com.gentlemanprogramming.gentlemobile.notifications.postAgentAlert
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.gentlemanprogramming.gentlemobile.supervision.PollTracker
import com.gentlemanprogramming.gentlemobile.supervision.SupervisionMachine
import com.gentlemanprogramming.gentlemobile.supervision.SupervisionState
import com.gentlemanprogramming.gentlemobile.supervision.livenessLabel
import com.gentlemanprogramming.gentlemobile.core.SurfaceDto
import com.gentlemanprogramming.gentlemobile.core.acceptHostKey
import com.gentlemanprogramming.gentlemobile.core.AgentDto
import com.gentlemanprogramming.gentlemobile.core.AgentStatusDto
import com.gentlemanprogramming.gentlemobile.core.CompanionConnection
import com.gentlemanprogramming.gentlemobile.core.CompanionException
import com.gentlemanprogramming.gentlemobile.core.ConnectOutcome
import com.gentlemanprogramming.gentlemobile.core.KnownHostDto
import com.gentlemanprogramming.gentlemobile.core.HostProfileDto
import com.gentlemanprogramming.gentlemobile.core.PaneReadDto
import com.gentlemanprogramming.gentlemobile.core.SnapshotDto
import com.gentlemanprogramming.gentlemobile.core.UploadProgressListener
import com.gentlemanprogramming.gentlemobile.core.composePrompt as coreComposePrompt
import com.gentlemanprogramming.gentlemobile.core.planUpload as corePlanUpload
import com.gentlemanprogramming.gentlemobile.core.connect as coreConnect
import com.gentlemanprogramming.gentlemobile.core.defaultSessionsDir
import com.gentlemanprogramming.gentlemobile.core.forgetHost
import com.gentlemanprogramming.gentlemobile.core.listKnownHosts
import com.gentlemanprogramming.gentlemobile.core.refreshLease
import com.gentlemanprogramming.gentlemobile.core.releaseLease
import com.gentlemanprogramming.gentlemobile.core.resumeSession as coreResumeSession
import com.gentlemanprogramming.gentlemobile.ui.sessions.HeldLease
import com.gentlemanprogramming.gentlemobile.ui.AgentRowModel
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.ui.locale.AppLanguage
import com.gentlemanprogramming.gentlemobile.ui.locale.AppLocale
import com.gentlemanprogramming.gentlemobile.ui.locale.LocaleStore
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.gentlemanprogramming.gentlemobile.ui.sessions.ResumedSessionScreen
import com.gentlemanprogramming.gentlemobile.ui.sessions.SessionsConfig
import com.gentlemanprogramming.gentlemobile.ui.sessions.SessionsModel
import com.gentlemanprogramming.gentlemobile.ui.sessions.SessionsScreen
import com.gentlemanprogramming.gentlemobile.identity.DeviceIdentity
import com.gentlemanprogramming.gentlemobile.pair.PairClient
import com.gentlemanprogramming.gentlemobile.pair.PairCommand
import com.gentlemanprogramming.gentlemobile.pair.PairLink
import com.gentlemanprogramming.gentlemobile.pair.PairLinkResult
import com.gentlemanprogramming.gentlemobile.pair.PairParams
import com.gentlemanprogramming.gentlemobile.pair.QrScannerScreen
import com.gentlemanprogramming.gentlemobile.security.BiometricDecision
import com.gentlemanprogramming.gentlemobile.security.BiometricUnlockStore
import com.gentlemanprogramming.gentlemobile.security.biometricDecision
import com.gentlemanprogramming.gentlemobile.security.parseHostSpec
import com.gentlemanprogramming.gentlemobile.security.requireBiometricUnlock
import com.gentlemanprogramming.gentlemobile.ui.settings.SettingsScreen
import com.gentlemanprogramming.gentlemobile.ui.session.AddResult
import com.gentlemanprogramming.gentlemobile.ui.session.formatBytes
import com.gentlemanprogramming.gentlemobile.ui.session.AndroidDictationEngine
import com.gentlemanprogramming.gentlemobile.ui.session.Attachment
import com.gentlemanprogramming.gentlemobile.ui.session.AttachmentQueue
import com.gentlemanprogramming.gentlemobile.ui.session.AttachmentState
import com.gentlemanprogramming.gentlemobile.ui.session.DictationController
import com.gentlemanprogramming.gentlemobile.ui.session.DictationState
import com.gentlemanprogramming.gentlemobile.ui.session.SessionComposer
import com.gentlemanprogramming.gentlemobile.ui.session.rememberDictationSession
import com.gentlemanprogramming.gentlemobile.ui.settings.ThemePickerScreen
import com.gentlemanprogramming.gentlemobile.ui.splash.SplashScreen
import com.gentlemanprogramming.gentlemobile.ui.theme.AppTheme
import com.gentlemanprogramming.gentlemobile.ui.theme.GentleMobileTheme
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette
import com.gentlemanprogramming.gentlemobile.ui.theme.SharedPreferencesStore
import com.gentlemanprogramming.gentlemobile.ui.theme.ThemeStore
import com.gentlemanprogramming.gentlemobile.ui.terminal.SurfaceStats
import com.gentlemanprogramming.gentlemobile.ui.terminal.TerminalFontMaxSp
import com.gentlemanprogramming.gentlemobile.ui.terminal.TerminalFontMinSp
import com.gentlemanprogramming.gentlemobile.ui.terminal.TerminalSurface
import com.gentlemanprogramming.gentlemobile.ui.terminal.rememberTerminalCellMetrics
import com.gentlemanprogramming.gentlemobile.ui.terminal.surfaceStats
import java.io.File
import java.util.concurrent.Executors

/**
 * The real lane-A read path: one screen, plus the host-key challenge state.
 * No fake rows — everything shown comes from a snapshot of a live host over
 * SSH, through the Rust core. An empty snapshot says so instead of inventing
 * hosts. Tapping an agent opens its session screen: the agent's pane drawn
 * as a real cell grid from the surface stream (latest-wins in the core,
 * polled at ~12 fps — never a queue of stale frames), falling back to the
 * pane's visible text over `pane.read` until the first surface arrives, and
 * a composer that submits the daemon's own `agent.prompt` — never raw
 * keystrokes.
 *
 * Pairing (T9): the identity key is generated on this phone at first launch
 * and stays in app-private storage; the screen shows the public line to run
 * `--add-key` with, a paste field for the host's `gc://` link, and a camera
 * scanner behind the design's Pair screen.
 *
 * Settings/security (T18): the biometric gate — a BiometricPrompt (weak or
 * device credential) standing between the user and every session open and
 * every explicit connect — the key regeneration, per-host known-hosts
 * management through the core, and the local revoke, each behind its own
 * confirmation. The gate never touches the key file: it wraps the actions
 * that use it.
 */
class MainActivity : FragmentActivity() {

    /** S24-1: the stored language wraps the base context, so the choice
     *  survives process death and every resource access follows it from
     *  the very first frame. */
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(AppLocale.wrapStored(base))
    }

    /** T15 (S11): the agent pane a notification tap asked to open. Set from
     *  the launch intent here and from onNewIntent when the task is already
     *  running (singleTop); consumed by the connect screen once the live
     *  snapshot actually contains the agent. */
    private val openAgentPane = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The launch theme carries the splash background; this is the real
        // app theme from the first composed frame on.
        setTheme(R.style.Theme_GentleMobile)
        super.onCreate(savedInstanceState)
        // Notification channels exist before anything can post (T15): the
        // persistent connection channel is quiet, the agent-alert channel
        // is the one that must be seen.
        ensureChannels(this)
        openAgentPane.value = intent?.getStringExtra(ConnectionService.EXTRA_OPEN_AGENT_PANE)
        // Edge-to-edge with real insets: the session composer must stay above
        // the keyboard and the navigation bar, which only works when the app
        // receives IME/nav-bar insets instead of the decor consuming them.
        // The app is always dark, so force light (white) status-bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // Development shortcut: `--ez fixture true` draws a deterministic 80x24
        // surface so terminal geometry can be iterated without an SSH host, a
        // host-key challenge or a live agent.
        val showFixture = intent?.getBooleanExtra("fixture", false) == true
        // Debug-only, per-launch override used by the tests; absent in release.
        val devSessionsDirOverride =
            if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                intent?.getStringExtra("sessions_dir")
            } else {
                null
            }
        setContent {
            val themeStore = remember {
                ThemeStore(SharedPreferencesStore(this@MainActivity))
            }
            var appTheme by remember { mutableStateOf(themeStore.selected()) }
            // S24-1: the language lives in the app, persists, and applies
            // immediately — the composition re-provides the context in the
            // chosen locale, so every stringResource call follows without a
            // restart. The choice is re-read in attachBaseContext after a
            // process death.
            val localeStore = remember {
                LocaleStore(SharedPreferencesStore(this@MainActivity))
            }
            var language by remember { mutableStateOf(localeStore.selected()) }
            val localizedContext = remember(language) {
                AppLocale.wrap(this@MainActivity, language)
            }
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides localizedContext,
                // S24-1: the wrapped context is not the ComponentActivity,
                // so the owners activity-compose derives by casting
                // LocalContext must be provided explicitly.
                LocalActivityResultRegistryOwner provides this@MainActivity,
                LocalLifecycleOwner provides this@MainActivity,
                LocalOnBackPressedDispatcherOwner provides this@MainActivity,
            ) {
                GentleMobileTheme(appTheme) {
                if (showFixture) {
                    com.gentlemanprogramming.gentlemobile.ui.terminal.FixtureTerminal()
                } else {
                    // The identity key lives in app-private storage now. The
                    // legacy external copy (the old adb-push path) is migrated
                    // once by DeviceIdentity.ensure, which also generates the
                    // keypair on first launch — no files are pushed any more.
                    val keyPath = File(filesDir, DeviceIdentity.KEY_FILE).absolutePath
                    val knownHostsPath = File(filesDir, DeviceIdentity.KNOWN_HOSTS_FILE).absolutePath
                    ConnectScreen(
                        keyPath = keyPath,
                        knownHostsPath = knownHostsPath,
                        appTheme = appTheme,
                        language = language,
                        onSetLanguage = { chosen ->
                            // Applied immediately (the composition provides
                            // a context in the new locale), persisted for the
                            // next launch.
                            localeStore.select(chosen)
                            language = chosen
                        },
                        openAgentPane = openAgentPane,
                        onSelectTheme = { theme ->
                            // Applied immediately, persisted for the next launch.
                            appTheme = theme
                            themeStore.select(theme)
                        },
                    )
                }
            }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A notification tap while the task already exists (singleTop):
        // record the agent to open, and keep the intent current so a later
        // recreation does not replay a stale one.
        openAgentPane.value = intent.getStringExtra(ConnectionService.EXTRA_OPEN_AGENT_PANE)
        setIntent(intent)
    }
}

// Background workers: read polling and user actions run off the UI thread
// on separate threads, so a stalled read never blocks a send. Internal:
// the Sessions screen (T17a) shares the same workers for its SFTP list and
// lease calls.
internal val executor = Executors.newFixedThreadPool(2)
internal val mainHandler = Handler(Looper.getMainLooper())

/** Log tag for supervision evidence: poller registration and stop lines. */
private const val TAG = "Supervision"

private sealed interface UiState {
    data object Form : UiState
    data object Connecting : UiState
    data object Scanning : UiState
    data class Challenge(val fingerprint: String, val pinned: String?, val keyBlob: String) : UiState
    data class Connected(
        val connection: CompanionConnection,
        val snapshot: SnapshotDto?,
    ) : UiState
    data class Session(val connection: CompanionConnection, val agent: AgentDto) : UiState
    data class Failed(val message: String) : UiState
}

/** Screen state + connect generation, so poll callbacks can stop cleanly. */
private class ScreenState {
    var ui by mutableStateOf<UiState>(UiState.Form)
    var generation: Int = 0
 /** One session state per agent pane, so drafts survive going back. */
    val sessions = mutableMapOf<String, SessionUi>()

    /** Connected splits into the Nodes screen and the agent Inbox; this
     * flag is the entry point from the host row into the agent list. */
    var inboxOpen by mutableStateOf(false)

    /** T17a (S15/S18): the profile and device lease id of the live
     * connection — the Sessions screen opens its own pinned SSH session
     * per call, and the advisory lease carries the device id. */
    var profile by mutableStateOf<HostProfileDto?>(null)
    var deviceLeaseId by mutableStateOf<String?>(null)

    /** The advisory lease this device holds on a host session, and the
     * generation counter that stops its heartbeat loop. */
    var heldLease by mutableStateOf<HeldLease?>(null)
    var leaseHeartbeatGeneration: Int = 0

    /** T17b: the RPC lane's live resumed session, plus the words the
     *  handshake reported. Closed screens keep the reason visible. */
    var resumedSession by mutableStateOf<com.gentlemanprogramming.gentlemobile.core.RpcResumedSession?>(null)
    var resumedTranscript by mutableStateOf<List<com.gentlemanprogramming.gentlemobile.core.TranscriptEntryDto>>(emptyList())
    var resumedMessageCount by mutableStateOf(0UL)
    var resumedReclaimed by mutableStateOf(false)
    var resumedDraft by mutableStateOf("")
    var resumedSending by mutableStateOf(false)
    var resumedSendError by mutableStateOf<String?>(null)
    var resumedCloseError by mutableStateOf<String?>(null)

    /** T8c supervision: the four words (connecting, connected, interrupted,
     * failed) and the liveness rule, in one tested place. Screens read this
     * machine instead of a separate interrupted flag that can drift from
     * what the connection is really doing. */
    val machine = SupervisionMachine()

    /** Registered pollers, logged at every change so teardown — cancelling
     *  a session, going back to the host list, replacing the connection — is
     *  provable with a counter that returns to zero. */
    val pollers = PollTracker()

    /** S24-1: the application context, captured once so messages composed
     *  on the worker threads (errors, drop reasons) can resolve their
     *  strings in the stored locale at creation time. */
    lateinit var appContext: Context

    /** T15 (S11): the pure alert brain, fed by its own slow poller over the
     *  connection the app already holds — no extra network traffic, no
     *  relay. Reset whenever a new connection goes live. */
    val alertTracker = AgentAlertTracker()

    /** The foreground service runs exactly while the machine says
     *  connected; the unit-tested gate owns the start/stop pairing. */
    val serviceGate = ForegroundServiceGate()

    /** Whether the app is (probably) visible: the notification suppression
     *  rule — never notify about the agent whose session is on screen. */
    var appInForeground by mutableStateOf(true)
}

/** The mutable state of one open agent session. */
private class SessionUi {
    var pane by mutableStateOf<PaneReadDto?>(null)
    var readError by mutableStateOf<String?>(null)
    /** The latest grid from the surface stream; `null` until the host rendered one. */
    var surface by mutableStateOf<SurfaceDto?>(null)
    var surfaceStats by mutableStateOf<SurfaceStats?>(null)
    var draft by mutableStateOf("")
    var sending by mutableStateOf(false)
    var sendError by mutableStateOf<String?>(null)
    var sent by mutableStateOf(false)
    var refreshNonce by mutableStateOf(0)

    /** Attachments (T13): the queue owns the honest state machine; the
     * snapshot list is Compose state so the rows redraw on every transition.
     * Thumbnails are decoded once per attachment, keyed by id; the source
     * URIs are kept so a Retry can re-read the bytes (the upload transfer
     * is one-shot). */
    val attachmentQueue = AttachmentQueue()
    var attachments by mutableStateOf<List<Attachment>>(emptyList())
    val thumbnails = mutableMapOf<Long, ImageBitmap>()
    val attachmentUris = mutableMapOf<Long, Uri>()
    /** A pick-time refusal (oversize, unusable name) shown once, near the
     * composer — the queue refuses before anything is half-added. */
    var attachmentError by mutableStateOf<String?>(null)

    /** Dictation (T14, S8): the pure state machine owns every transition;
     * [dictation] is the Compose mirror so the listening line and the mic
     * circle redraw on every change. The recognizer lives in the session
     * composable; its callbacks drive the machine, the machine decides
     * what lands in the field. */
    val dictationController = DictationController()
    var dictation by mutableStateOf<DictationState>(DictationState.Idle)

    /** Re-publish the queue snapshot; call after every queue mutation. */
    fun syncAttachments() {
        attachments = attachmentQueue.snapshot()
        if (attachmentQueue.snapshot().none {
                it.state is AttachmentState.Ready ||
                    it.state is AttachmentState.Uploading ||
                    it.state is AttachmentState.Failed
            }
        ) {
            // Nothing in flight: a stale refusal would only confuse.
            attachmentError = null
        }
    }

    /** Extra-key row state (board 04). Ctrl and Alt are sticky one-shot
     * modifiers: tapping toggles, the next key chip composes `ctrl+<key>`
     * / `alt+<key>` and consumes the sticky state. `keySending` debounces
     * a chip while its request is in flight, and `keyError` carries the
     * host's typed refusal — a chip never claims a press that did not land. */
    var ctrlSticky by mutableStateOf(false)
    var altSticky by mutableStateOf(false)
    var keySending by mutableStateOf(false)
    var keyError by mutableStateOf<String?>(null)

    /** Full re-baseline: a new connection owns a fresh core store, so the
     * drawn grid and the fallback read start empty again. Drafts survive.
     * Attachments do not: their upload session died with the connection. */
    fun resetBaseline() {
        pane = null
        readError = null
        surface = null
        surfaceStats = null
        // A dead connection cannot keep a recognizer: any dictation in
        // flight is cancelled honestly (its partial is never committed).
        if (dictationController.state is DictationState.Listening ||
            dictationController.state is DictationState.RequestingPermission
        ) {
            val update = dictationController.cancel()
            dictation = update.state
            draft = update.draft
        }
        attachmentQueue.snapshot().filter {
            it.state !is AttachmentState.Uploaded
        }.forEach { attachmentQueue.remove(it.id) }
        thumbnails.clear()
        syncAttachments()
    }

    /** A sent prompt has handed every uploaded file to the agent: the rows
     * leave the composer with the draft, so the next message starts clean. */
    fun clearSentAttachments() {
        attachmentQueue.snapshot()
            .filter { it.state is AttachmentState.Uploaded }
            .forEach { attachmentQueue.remove(it.id) }
        thumbnails.clear()
        attachmentUris.clear()
        syncAttachments()
    }
}

/** The screens reachable from the Pair flow; the design shows back buttons. */
private enum class Route { Main, Settings, Themes, Sessions, Resumed }

/** The words a resume or a prompt failure shows: typed refusals keep their
 *  exact message (the core's Display carries the reason), anything else
 *  falls back to the exception's own words. Never a generic "failed". */
private fun describeResumeFailure(error: Exception): String =
    error.message ?: error.toString()

@Composable
fun ConnectScreen(
    sessionsDirOverride: String? = null,
    keyPath: String,
    knownHostsPath: String,
    appTheme: AppTheme,
    language: AppLanguage,
    onSetLanguage: (AppLanguage) -> Unit,
    onSelectTheme: (AppTheme) -> Unit,
    openAgentPane: androidx.compose.runtime.MutableState<String?>,
) {
    val screen = remember { ScreenState() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // S24-1: the worker threads resolve their strings (errors, drop
    // reasons) through the application context, in the stored locale.
    screen.appContext = context.applicationContext

    var route by remember { mutableStateOf(Route.Main) }

    var host by remember { mutableStateOf("10.0.2.2") }
    var port by remember { mutableStateOf("2222") }
    var user by remember { mutableStateOf("erickfm") }
    var session by remember { mutableStateOf("default") }
    /** User-chosen terminal font size in px, `0f` = fit the grid to the width. */
    var terminalFontPx by remember { mutableStateOf(0f) }

    // Device identity: generated on first launch, migrated from the old
    // external path once, shown so the user never copies files around.
    var identity by remember { mutableStateOf<DeviceIdentity.Identity?>(null) }
    var identityError by remember { mutableStateOf<String?>(null) }
    // Settings/security state (T18): the persisted biometric switch, the
    // device's real capability, and the pinned hosts the core holds.
    val biometricStore = remember { BiometricUnlockStore(SharedPreferencesStore(context)) }
    var biometricDecision by remember { mutableStateOf<BiometricDecision?>(null) }
    var biometricEnabled by remember { mutableStateOf(false) }
    var knownHosts by remember { mutableStateOf<List<KnownHostDto>>(emptyList()) }
    var regenerated by remember { mutableStateOf(false) }

    fun loadIdentity() {
        executor.execute {
            try {
                val ensured = DeviceIdentity.ensure(context)
                mainHandler.post { identity = ensured }
            } catch (error: Exception) {
                mainHandler.post { identityError = describe(context, error) }
            }
        }
    }
    LaunchedEffect(Unit) { loadIdentity() }

    /** The hosts the core has pinned right now, for the Settings list. */
    fun refreshHosts() {
        val generation = screen.generation
        executor.execute {
            val hosts = try {
                listKnownHosts(knownHostsPath)
            } catch (_: Exception) {
                emptyList()
            }
            mainHandler.post { if (screen.generation == generation) knownHosts = hosts }
        }
    }

    /** The switch's persisted state plus what this device can enforce. */
    fun refreshBiometrics() {
        biometricEnabled = biometricStore.enabled()
        executor.execute {
            val decision = biometricDecision(context)
            mainHandler.post { biometricDecision = decision }
        }
    }

    // The biometric gate (T18): when the switch is on and the device can
    // enforce it, a BiometricPrompt stands between the user and the gated
    // action — opening a session, or an explicit connect, which is the
    // device key being used. It never touches the key file itself.
    fun gated(action: () -> Unit) {
        // The composition context may be locale-wrapped (S24-1): the gate
        // needs the real FragmentActivity behind it.
        val activity = AppLocale.unwrapToLifecycle(context) as? FragmentActivity
        if (activity != null) {
            requireBiometricUnlock(activity, biometricStore, biometricDecision, action)
        } else {
            action()
        }
    }

    // Settings reads live state on entry: the pinned hosts and the biometric
    // capability are re-asked every time the screen opens (T18).
    LaunchedEffect(route) {
        if (route == Route.Settings) {
            refreshHosts()
            refreshBiometrics()
        }
    }

    // T15 (S11): POST_NOTIFICATIONS on Android 13+, requested once, with an
    // explanation on screen while the system dialog is up. A denial keeps
    // the app fully working — the Settings row then says notifications are
    // off and why. Below Android 13 there is nothing to ask (null).
    var notificationsGranted by remember {
        mutableStateOf(notificationPermissionGranted(context))
    }
    var notificationsAsked by remember { mutableStateOf(false) }
    val notificationsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsGranted = granted
        notificationsAsked = true
    }
    fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (notificationsGranted == true || notificationsAsked) return
        notificationsAsked = true
        notificationsPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    // Splash (S21, board 01): the mark, the wordmark and a thin progress line
    // for exactly as long as the cold-start key work runs — never a fake
    // delay. It hides as soon as the first screen is ready, including when
    // the key work failed (the error is shown instead of a spinner).
    if (identity == null && identityError == null) {
        SplashScreen()
        return
    }

    /** Release the advisory lease this device holds (S18): clear the local
     *  state, stop the heartbeat, and ask the host to remove the file — only
     *  the core knows whether it was still ours. */
    fun releaseHeldLease(state: ScreenState) {
        val held = state.heldLease ?: return
        state.heldLease = null
        state.leaseHeartbeatGeneration += 1
        val profile = state.profile ?: return
        val deviceId = state.deviceLeaseId ?: return
        executor.execute {
            try {
                releaseLease(profile, held.sessionId, deviceId, held.token)
            } catch (_: Exception) {
            }
        }
    }

    /** The heartbeat loop (S18): while the app holds a lease, refresh it
     *  every heartbeat interval so it stays live on the host's clock. A
     *  failed refresh — lease lost, replaced, or the link gone — clears the
     *  hold honestly instead of claiming a session we no longer own. */
    fun scheduleLeaseHeartbeat(state: ScreenState) {
        state.leaseHeartbeatGeneration += 1
        val generation = state.leaseHeartbeatGeneration
        mainHandler.postDelayed({
            if (generation != state.leaseHeartbeatGeneration) return@postDelayed
            val held = state.heldLease ?: return@postDelayed
            val profile = state.profile ?: return@postDelayed
            val deviceId = state.deviceLeaseId ?: return@postDelayed
            executor.execute {
                val alive = try {
                    refreshLease(profile, held.sessionId, deviceId, held.token)
                } catch (_: Exception) {
                    false
                }
                mainHandler.post {
                    if (generation != state.leaseHeartbeatGeneration) return@post
                    if (!alive) {
                        // The lease is gone; the screen reloads its list on
                        // its own actions, and the row honestly returns to
                        // whatever the host says.
                        state.heldLease = null
                    }
                    scheduleLeaseHeartbeat(state)
                }
            }
        }, LEASE_HEARTBEAT_MS)
    }

    fun disconnectLive() {
        // T15 (S11): the notification's Disconnect action can land while a
        // session screen is up; the user is disconnecting the connection,
        // whatever screen shows it.
        val connection = when (val current = screen.ui) {
            is UiState.Connected -> current.connection
            is UiState.Session -> current.connection
            else -> null
        }
        if (connection != null) {
            screen.generation += 1
            executor.execute {
                try {
                    connection.disconnect()
                } catch (_: Exception) {
                }
            }
            screen.ui = UiState.Form
            screen.machine.reset()
        }
        // S18: disconnecting releases any advisory lease this device holds —
        // a dead link must not keep a session claimed.
        releaseHeldLease(screen)
    }

    // S18: app teardown releases the advisory lease — the composition is
    // the app's lifetime for the connect flow, so disposing it is the
    // last chance to hand the claim back.
    DisposableEffect(Unit) {
        onDispose { releaseHeldLease(screen) }
    }

    if (route == Route.Settings) {
        SettingsScreen(
            identity = identity,
            identityError = identityError,
            knownHosts = knownHosts,
            biometricDecision = biometricDecision,
            biometricEnabled = biometricEnabled,
            theme = appTheme,
            language = language,
            onSetLanguage = onSetLanguage,
            connected = screen.ui is UiState.Connected,
            regenerated = regenerated,
            notificationsGranted = notificationsGranted,
            onCopyKey = {
                identity?.let { clipboard.setText(AnnotatedString(it.publicKeyLine)) }
            },
            onOpenThemes = { route = Route.Themes },
            onToggleBiometric = { enabled ->
                biometricEnabled = enabled
                biometricStore.setEnabled(enabled)
            },
            onRegenerateKey = {
                val generation = screen.generation
                executor.execute {
                    try {
                        val fresh = DeviceIdentity.regenerate(context)
                        mainHandler.post {
                            if (screen.generation == generation) {
                                identity = fresh
                                regenerated = true
                            }
                        }
                        // A new key means new trust: the pins are gone.
                        mainHandler.post { refreshHosts() }
                    } catch (error: Exception) {
                        mainHandler.post { identityError = describe(context, error) }
                    }
                }
            },
            onForgetHost = { host ->
                val target = parseHostSpec(host.spec)
                if (target != null) {
                    val (forgottenHost, port) = target
                    executor.execute {
                        try {
                            forgetHost(knownHostsPath, forgottenHost, port.toUShort())
                        } catch (_: Exception) {
                        }
                        mainHandler.post { refreshHosts() }
                    }
                }
            },
            onRevoke = {
                // Wipe what this device holds and come back to the Pair
                // screen with a fresh key; the host side keeps trusting the
                // old key until gentle-mobile-pair --revoke runs there.
                disconnectLive()
                executor.execute {
                    DeviceIdentity.wipeAt(context.filesDir)
                    mainHandler.post {
                        regenerated = false
                        knownHosts = emptyList<KnownHostDto>()
                        identity = null
                        identityError = null
                        screen.ui = UiState.Form
                        screen.machine.reset()
                        route = Route.Main
                        loadIdentity()
                    }
                }
            },
            onDisconnect = {
                disconnectLive()
                route = Route.Main
            },
            onOpenNotificationSettings = {
                // Permission honesty (T15): a denied row opens the system's
                // own notification settings for this app, one tap away.
                try {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: Exception) {
                }
            },
            onBack = { route = Route.Main },
        )
        return
    }
    if (route == Route.Themes) {
        ThemePickerScreen(
            current = appTheme,
            onSelect = onSelectTheme,
            // Board 06: the topbar's back returns to settings.
            onBack = { route = Route.Settings },
        )
        return
    }

    // T17a (S15/S18): the host's Gentle Shell sessions, listed over SFTP
    // and guarded by the advisory lease. Reached from the Nodes screen.
    if (route == Route.Sessions) {
        val sessionsProfile = screen.profile
        val sessionsDeviceId = screen.deviceLeaseId
        if (sessionsProfile != null && sessionsDeviceId != null) {
            SessionsScreen(
                profile = sessionsProfile,
                deviceLeaseId = sessionsDeviceId,
                // The sessions root is the host's own default. A dev override
                // exists only in a debug build and only for this launch
                // (`--es sessions_dir <path>`), never a silent persisted value.
                sessionsDir = sessionsDirOverride ?: defaultSessionsDir(sessionsProfile.user),
                heldLease = screen.heldLease,
                onLeaseTaken = { sessionId, token ->
                    screen.heldLease = HeldLease(sessionId, token)
                    scheduleLeaseHeartbeat(screen)
                },
                onLeaseReleased = {
                    screen.heldLease = null
                    screen.leaseHeartbeatGeneration += 1
                },
                onResume = { session, sessionsDir ->
                    val profile = sessionsProfile
                    val deviceId = sessionsDeviceId
                    executor.execute {
                        try {
                            val token = java.util.UUID.randomUUID().toString()
                            val outcome = coreResumeSession(
                                profile,
                                "${sessionsDir.trimEnd('/')}/${session.fileName}",
                                session.sessionId,
                                deviceId,
                                token,
                            )
                            mainHandler.post {
                                screen.heldLease = HeldLease(session.sessionId, token)
                                scheduleLeaseHeartbeat(screen)
                                screen.resumedSession = outcome.session
                                screen.resumedTranscript = outcome.transcript
                                screen.resumedMessageCount = outcome.session.messageCount()
                                screen.resumedReclaimed = outcome.reclaimed
                                screen.resumedDraft = ""
                                screen.resumedSending = false
                                screen.resumedSendError = null
                                screen.resumedCloseError = null
                                route = Route.Resumed
                            }
                        } catch (e: Exception) {
                            mainHandler.post {
                                // Typed refusals stay honest, word for word.
                                screen.resumedSendError = describeResumeFailure(e)
                            }
                        }
                    }
                },
                onBack = {
                    releaseHeldLease(screen)
                    route = Route.Main
                },
            )
            return
        }
        // No live profile behind the route (the connection was reset): fall
        // back to the Nodes screen instead of rendering a dead screen.
        route = Route.Main
    }

    // T17b (S15/S17): the resumed session — transcript read-only, prompts
    // through the RPC lane, dialogs answered, close releases the lease.
    if (route == Route.Resumed) {
        val resumed = screen.resumedSession
        if (resumed != null) {
            // Poll the pump's visible state without extra network traffic:
            // the driver updates its shared store on every record.
            LaunchedEffect(resumed) {
                while (true) {
                    screen.resumedTranscript = resumed.transcript()
                    kotlinx.coroutines.delay(500)
                }
            }
            ResumedSessionScreen(
                sessionId = resumed.sessionId(),
                messageCount = screen.resumedMessageCount,
                transcript = screen.resumedTranscript,
                reclaimed = screen.resumedReclaimed,
                pendingDialog = resumed.pendingDialog(),
                draft = screen.resumedDraft,
                onDraftChange = { screen.resumedDraft = it },
                sending = screen.resumedSending,
                sendError = screen.resumedSendError,
                closed = resumed.isClosed(),
                closeError = screen.resumedCloseError ?: resumed.lastError(),
                onSend = {
                    val text = screen.resumedDraft
                    if (text.isNotBlank() && !screen.resumedSending) {
                        screen.resumedSending = true
                        screen.resumedSendError = null
                        executor.execute {
                            try {
                                val transcript = resumed.sendPrompt(text)
                                mainHandler.post {
                                    screen.resumedTranscript = transcript
                                    screen.resumedMessageCount = resumed.messageCount()
                                    screen.resumedDraft = ""
                                    screen.resumedSending = false
                                }
                            } catch (e: Exception) {
                                mainHandler.post {
                                    screen.resumedSendError = describeResumeFailure(e)
                                    screen.resumedSending = false
                                }
                            }
                        }
                    }
                },
                onAnswerDialog = { dialogId, cancelled, value ->
                    executor.execute {
                        try {
                            resumed.answerDialog(dialogId, cancelled, value, null)
                        } catch (_: Exception) {
                        }
                    }
                },
                onClose = {
                    executor.execute {
                        var closeError: String? = null
                        try {
                            resumed.closeSession()
                        } catch (e: Exception) {
                            closeError = e.message ?: e.toString()
                        }
                        mainHandler.post {
                            screen.resumedCloseError = closeError
                            releaseHeldLease(screen)
                            route = Route.Sessions
                        }
                    }
                },
                onBack = {
                    // Back without closing keeps the pump and the lease —
                    // the session continues while the app holds it (S18);
                    // the row still says held by this device.
                    route = Route.Sessions
                },
            )
            return
        }
        // The session is gone (teardown): back to the list.
        route = Route.Sessions
    }

    // Pairing link paste + QR scan.
    var pairLink by remember { mutableStateOf("") }
    var pairError by remember { mutableStateOf<String?>(null) }
    var cameraMessage by remember { mutableStateOf<String?>(null) }
    var copiedKey by remember { mutableStateOf(false) }
    var copiedCommand by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf(false) }
    // One-scan pairing (S22): when the applied link carries a pairing
    // endpoint + token, Connect first POSTs this phone's key to the
    // listener, then opens SSH. The paste field runs the same flow.
    var pairParams by remember { mutableStateOf<PairParams?>(null) }
    var pairStatus by remember { mutableStateOf<String?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            cameraMessage = null
            screen.ui = UiState.Scanning
        } else {
            cameraMessage = context.getString(R.string.pair_camera_denied)
        }
    }

    // S24-1: the pairing refusals, built in the stored locale.
    val pairInvalid = { reason: PairLink.InvalidReason ->
        val id = when (reason) {
            PairLink.InvalidReason.EMPTY -> R.string.pair_invalid_empty
            PairLink.InvalidReason.NOT_GC -> R.string.pair_invalid_not_gc
            PairLink.InvalidReason.MISSING_QUERY -> R.string.pair_invalid_missing_query
            PairLink.InvalidReason.TOKEN_WITHOUT_PAIR -> R.string.pair_invalid_token_without_pair
            PairLink.InvalidReason.PAIR_WITHOUT_TOKEN -> R.string.pair_invalid_pair_without_token
            PairLink.InvalidReason.BAD_TOKEN -> R.string.pair_invalid_bad_token
            PairLink.InvalidReason.BAD_PAIR_HOSTPORT -> R.string.pair_invalid_bad_pair_hostport
            PairLink.InvalidReason.BAD_AUTHORITY -> R.string.pair_invalid_bad_authority
            PairLink.InvalidReason.BAD_HOST_NAME -> R.string.pair_invalid_bad_host
            PairLink.InvalidReason.BAD_PORT -> R.string.pair_invalid_bad_port
            PairLink.InvalidReason.MISSING_USER -> R.string.pair_invalid_missing_query
            PairLink.InvalidReason.BAD_USER_URL -> R.string.pair_invalid_bad_user_url
            PairLink.InvalidReason.BAD_USER_NAME -> R.string.pair_invalid_bad_user_name
        }
        context.getString(id)
    }
    val pairRefusal = { status: Int, detail: String? ->
        val suffix = detail?.let { context.getString(R.string.pair_err_detail_suffix, it) }.orEmpty()
        when (status) {
            401 -> context.getString(R.string.pair_err_token) + suffix
            403 -> context.getString(R.string.pair_err_expired) + suffix
            409 -> context.getString(R.string.pair_err_used) + suffix
            400 -> detail?.let { context.getString(R.string.pair_err_key_detail, it) }
                ?: context.getString(R.string.pair_err_key_malformed)
            else -> context.getString(R.string.pair_err_other, status) + suffix
        }
    }
    val pairUnreachable = { pairHost: String, pairPort: String, reason: String ->
        context.getString(R.string.pair_err_unreachable, "$pairHost:$pairPort", reason)
    }

    fun applyPairLink(raw: String): Boolean {
        val parsed = PairLink.parse(raw, pairInvalid)
        pasted = parsed is PairLinkResult.Parsed
        return when (parsed) {
            is PairLinkResult.Parsed -> {
                host = parsed.host
                port = parsed.port
                user = parsed.user
                pairParams = parsed.pair
                pairLink = ""
                pairError = null
                true
            }
            is PairLinkResult.Invalid -> {
                pairError = parsed.reason
                false
            }
        }
    }

    fun connect(acceptChallenge: UiState.Challenge?, pair: PairParams?) {
        screen.generation += 1
        val generation = screen.generation
        // Supervision (T8c): a new attempt begins — first connect, manual
        // retry, or the one automatic foreground attempt. The challenge accept
        // continues the attempt already in flight, so it does not re-begin.
        if (acceptChallenge == null && screen.machine.state !is SupervisionState.Connecting) {
            screen.machine.begin()
        }
        screen.ui = UiState.Connecting
        pairStatus = pair?.let {
            context.getString(R.string.pair_status_pairing, "${it.host}:${it.port}")
        }
        val profile = HostProfileDto(
            host = host,
            port = port.toUShortOrNull() ?: 0u,
            user = user,
            identityPath = keyPath,
            knownHostsPath = knownHostsPath,
        )
        // T17a: the Sessions screen rides this profile and this device id.
        screen.profile = profile
        screen.deviceLeaseId = identity?.publicKeyLine?.let { SessionsModel.deviceLeaseId(it) }
        val challenge = acceptChallenge
        val keyLine = identity?.publicKeyLine
        executor.execute {
            try {
                if (pair != null) {
                    if (keyLine == null) {
                        mainHandler.post {
                            screen.ui = UiState.Failed(
                                context.getString(R.string.pair_no_key_yet),
                            )
                        }
                        return@execute
                    }
                    when (
                        val reply = PairClient.sendKey(
                            pair.host,
                            pair.port,
                            pair.token,
                            keyLine,
                            pairUnreachable,
                            pairRefusal,
                        )
                    ) {
                        is PairClient.Result.Refused -> {
                            mainHandler.post {
                                if (screen.generation == generation) {
                                    screen.machine.onRefused(reply.message)
                                    screen.ui = UiState.Failed(reply.message)
                                }
                            }
                            // Never fall through to SSH: the key was not
                            // trusted, the connect would fail with a second
                            // error that hides the pairing reason.
                            return@execute
                        }
                        is PairClient.Result.Accepted -> mainHandler.post {
                            if (screen.generation == generation) {
                                // The token burned on use: the key is trusted
                                // now, later connects go straight to SSH.
                                pairParams = null
                                pairStatus = context.getString(R.string.pair_status_accepted)
                            }
                        }
                    }
                }
                if (challenge != null) {
                    acceptHostKey(knownHostsPath, profile.host, profile.port, challenge.keyBlob)
                }
                if (!File(keyPath).exists()) {
                    mainHandler.post {
                        screen.ui = UiState.Failed(
                            context.getString(R.string.pair_no_key_file, keyPath),
                        )
                    }
                    return@execute
                }
                when (val outcome = coreConnect(profile, session)) {
                    is ConnectOutcome.Challenge -> mainHandler.post {
                        if (screen.generation == generation) {
                            // Still the same attempt, paused for the human.
                            screen.ui = UiState.Challenge(
                                fingerprint = outcome.v1.fingerprint,
                                pinned = outcome.v1.pinnedFingerprint,
                                keyBlob = outcome.v1.keyBlob,
                            )
                        }
                    }
                    is ConnectOutcome.Connected -> {
                        val connection = outcome.v1
                        mainHandler.post {
                            if (screen.generation == generation) {
                                // A new connection is a fresh baseline: every
                                // session's grid and read start empty again,
                                // and the host row — not the inbox — leads.
                                // The alert brain re-primes too: the first
                                // snapshot of THIS connection is the baseline
                                // (an agent already in needs you never alerts).
                                screen.sessions.values.forEach { it.resetBaseline() }
                                screen.alertTracker.reset()
                                screen.inboxOpen = false
                                screen.machine.onLive()
                                screen.ui = UiState.Connected(connection, snapshot = null)
                                // T15 (S11): the foreground service keeps the
                                // SSH link alive in the background, and the
                                // slow alert poller watches for transitions.
                                if (screen.serviceGate.onConnected(host)) {
                                    ContextCompat.startForegroundService(
                                        context,
                                        ConnectionService.connectionIntent(context, host),
                                    )
                                }
                                pollAlerts(context, screen, connection)
                            }
                        }
                        poll(screen, connection, generation)
                    }
                }
            } catch (error: Exception) {
                val message = describe(context, error)
                mainHandler.post {
                    // A refused attempt fails with its reason and never
                    // retries on its own (T8c).
                    if (screen.generation == generation) {
                        screen.machine.onRefused(message)
                    }
                    screen.ui = UiState.Failed(message)
                }
            }
        }
    }

    /** Open one agent's session screen — the exact path the inbox row and
     *  a notification tap both take. The biometric gate wraps it like any
     *  other session open. */
    fun openAgentSession(current: UiState.Connected, agent: AgentDto) {
        gated {
            val connection = current.connection
            screen.generation += 1
            val generation = screen.generation
            val sessionUi = screen.sessions.getOrPut(agent.paneId) { SessionUi() }
            screen.ui = UiState.Session(connection, agent)
            pollPane(screen, sessionUi, connection, agent.paneId, generation)
            pollSurface(screen, sessionUi, connection, agent.paneId, generation)
        }
    }

    // Foreground return (T8c): never assume the socket survived a sleep. A
    // live link gets a cheap liveness probe — an unanswered probe ends the
    // connection and the pollers surface the interruption; an interrupted
    // link gets exactly one automatic attempt, never a silent loop.
    fun onAppForeground() {
        when (val supervision = screen.machine.state) {
            is SupervisionState.Interrupted -> {
                if (screen.machine.onForeground()) {
                    connect(acceptChallenge = null, pair = null)
                }
            }
            is SupervisionState.Connected -> {
                val current = screen.ui
                if (current is UiState.Connected) {
                    val connection = current.connection
                    val generation = screen.generation
                    executor.execute {
                        val dead = try {
                            connection.isDisconnected()
                        } catch (_: Exception) {
                            true
                        }
                        if (dead) {
                            mainHandler.post {
                                if (screen.generation == generation) {
                                    screen.machine.onDropped(
                                        AppLocale.wrapStored(screen.appContext)
                                            .getString(R.string.session_reason_link_down),
                                    )
                                }
                            }
                        } else {
                            // Cheap probe: ping now; a host that stays silent
                            // ends the connection within a few seconds, and
                            // the pollers report the interruption.
                            try {
                                connection.probeLiveness()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
            else -> {}
        }
    }

    // ON_RESUME is the supervision trigger: verify liveness or retry once,
    // instead of trusting a socket that may have died unnoticed. ON_PAUSE
    // flips the visibility flag the notification suppression reads (T15):
    // the agent on screen never alerts, the background always may.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    screen.appInForeground = true
                    notificationsGranted = notificationPermissionGranted(context)
                    onAppForeground()
                }
                Lifecycle.Event.ON_PAUSE -> screen.appInForeground = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // T15 (S11): the foreground service runs exactly while the supervision
    // machine says connected — one start per connection, one stop when the
    // link drops, fails, or the user disconnects. The unit-tested gate
    // (ForegroundServiceGate) owns the pairing, so a drop during a retry
    // never leaves an orphaned notification behind.
    LaunchedEffect(Unit) {
        snapshotFlow { screen.machine.state }.collect { state ->
            if (state is SupervisionState.Connected) {
                if (screen.serviceGate.onConnected(host)) {
                    ContextCompat.startForegroundService(
                        context,
                        ConnectionService.connectionIntent(context, host),
                    )
                }
            } else if (screen.serviceGate.onConnectionEnded()) {
                context.stopService(Intent(context, ConnectionService::class.java))
            }
        }
    }

    // T15 (S11): the notification's Disconnect action reaches the screen
    // that owns the live connection. Unregistered when this screen goes
    // away, so a stale teardown can never fire.
    DisposableEffect(Unit) {
        ForegroundHub.userDisconnect = {
            disconnectLive()
            route = Route.Main
        }
        onDispose { ForegroundHub.userDisconnect = null }
    }

    // T15 (S11): a notification tap opens that agent's session. The pane id
    // may arrive before the agent is in a snapshot (or before any
    // connection exists), so it stays pending until the connected snapshot
    // actually contains it.
    LaunchedEffect(openAgentPane.value, screen.ui) {
        val paneId = openAgentPane.value ?: return@LaunchedEffect
        val current = screen.ui
        if (current is UiState.Connected) {
            val agent = current.snapshot?.agents?.firstOrNull { it.paneId == paneId }
            if (agent != null) {
                openAgentPane.value = null
                openAgentSession(current, agent)
            }
        }
    }

    Column(
        modifier = (if (screen.ui is UiState.Session) {
            // The session screen must be sized by the window itself: inside
            // the scrollable connect column its weighted pane block gets
            // unbounded constraints and collapses to zero height.
            Modifier
        } else {
            Modifier
        })
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Real window insets: status bar at the top, IME + navigation bar
            // at the bottom, so the composer always sits above both.
            .safeDrawingPadding()
            .then(
                if (screen.ui is UiState.Session) {
                    Modifier
                } else {
                    Modifier.verticalScroll(rememberScrollState())
                }
            )
            .padding(16.dp),
    ) {
        if (screen.ui !is UiState.Session) {
            // The session screen has its own compact header; the topbar stays
            // quiet. S24-7: no product name in the chrome — the one word the
            // screen needed removed — and the single way into Settings is a
            // gear icon, labelled for screen readers, instead of the repeated
            // `Settings` word.
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable { route = Route.Settings },
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = stringResource(R.string.cd_settings),
                        tint = LocalPalette.current.accentEdge,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        when (val current = screen.ui) {
            is UiState.Scanning -> {
                QrScannerScreen(
                    onQr = { link ->
                        screen.ui = UiState.Form
                        val applied = applyPairLink(link)
                        // One scan runs the whole flow: post the key, then
                        // connect over SSH — the host-key challenge still
                        // shows after.
                        if (applied && pairParams != null) {
                            connect(acceptChallenge = null, pair = pairParams)
                        }
                    },
                    onCancel = { screen.ui = UiState.Form },
                )
            }
            UiState.Form, UiState.Connecting, is UiState.Failed -> {
                // --- 01-pair.html: one surface, hairline-separated blocks, ---
                // --- no nested cards, one accent action.                  ---
                Text(
                    text = stringResource(R.string.pair_title),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 22.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.pair_intro),
                    color = LocalPalette.current.muted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                Hairline()

                // Block 1: this phone's own public key, with the identity
                // state shown honestly — "creating the key…", the error, or
                // the key itself. Never a spinner that hides a failure.
                BlockLabel(stringResource(R.string.pair_key_label))
                val identity = identity
                val identityFailure = identityError
                when {
                    identityFailure != null -> Text(
                        text = identityFailure,
                        color = LocalPalette.current.error,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    identity == null -> Text(
                        text = stringResource(R.string.pair_creating_key),
                        color = LocalPalette.current.muted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    else -> {
                        SelectionContainer {
                            Text(
                                text = identity.publicKeyLine,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(LocalPalette.current.surfaceRaised, RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            GhostButton(
                                label = stringResource(R.string.pair_copy),
                                onClick = {
                                    clipboard.setText(AnnotatedString(identity.publicKeyLine))
                                    copiedKey = true
                                    copiedCommand = false
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (copiedKey) stringResource(R.string.pair_copied) else
                                    stringResource(R.string.pair_key_trust_hint),
                                color = if (copiedKey) LocalPalette.current.accent else LocalPalette.current.muted,
                                fontSize = 11.sp,
                                maxLines = 2,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Hairline()

                // Block 2: the host command, ready to copy.
                BlockLabel(stringResource(R.string.pair_host_label))
                val identityForCommand = identity
                if (identityForCommand == null) {
                    Text(
                        text = stringResource(R.string.pair_ready_when_key),
                        color = LocalPalette.current.muted,
                        fontSize = 12.sp,
                    )
                } else {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = LocalPalette.current.accent)) { append("\u27AF ") }
                                append(PairCommand.addKey(identityForCommand.publicKeyLine))
                            },
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 11.sp,
                            lineHeight = 18.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        GhostButton(
                            label = stringResource(R.string.pair_copy),
                            onClick = {
                                clipboard.setText(
                                    AnnotatedString(
                                        PairCommand.addKey(identityForCommand.publicKeyLine),
                                    ),
                                )
                                copiedCommand = true
                                copiedKey = false
                            },
                        )
                    }
                    if (copiedCommand) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.pair_copied_command),
                            color = LocalPalette.current.accent,
                            fontSize = 11.sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Hairline()

                // Block 3: the connection link, pasted from the host output.
                BlockLabel(stringResource(R.string.pair_link_label))
                OutlinedTextField(
                    value = pairLink,
                    onValueChange = {
                        pairLink = it
                        pairError = null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "gc://host:port?user=\u2026",
                            color = LocalPalette.current.muted,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    },
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LocalPalette.current.accent,
                        unfocusedBorderColor = LocalPalette.current.line,
                        focusedContainerColor = LocalPalette.current.surface,
                        unfocusedContainerColor = LocalPalette.current.surface,
                        cursorColor = LocalPalette.current.accent,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    GhostButton(
                        label = stringResource(R.string.pair_paste),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val text = clipboard.getText()?.text.orEmpty()
                            if (text.isBlank()) {
                                pairError =
                                    context.getString(R.string.pair_clipboard_empty)
                            } else {
                                applyPairLink(text)
                            }
                        },
                    )
                }
                pairError?.let { error ->
                    Notice(error, LocalPalette.current.error)
                }
                if (pasted) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = pairParams?.let {
                            stringResource(
                                R.string.pair_will_pair,
                                "${it.host}:${it.port}", host, user,
                            )
                        } ?: stringResource(R.string.pair_will_connect, host, port, user),
                        color = LocalPalette.current.muted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        // The notification permission rides the connect
                        // action: one explicit moment, with the explanation
                        // below while the dialog is up.
                        askForNotifications()
                        // A typed link is applied first; a pasted or scanned
                        // one was already applied, so Connect reuses it. With
                        // the biometric switch on, connecting is the device
                        // key being used: the prompt stands before it.
                        gated {
                            if (pairLink.isNotBlank()) applyPairLink(pairLink)
                            if (pasted) connect(acceptChallenge = null, pair = pairParams)
                        }
                    },
                    enabled = (pairLink.isNotBlank() || pasted) && current !is UiState.Connecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalPalette.current.accent,
                        contentColor = LocalPalette.current.bg,
                        // A disabled action must read as disabled, not as a
                        // broken dark button: keep it legible and obviously off.
                        disabledContainerColor = LocalPalette.current.accentPressed,
                        disabledContentColor = LocalPalette.current.bg,
                    ),
                ) {
                    Text(
                        text = when {
                            current is UiState.Connecting && pairParams != null ->
                                stringResource(R.string.pair_button_pairing)
                            current is UiState.Connecting ->
                                stringResource(R.string.pair_button_connecting)
                            else -> stringResource(R.string.pair_button)
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (current is UiState.Connecting) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = pairStatus
                            ?: stringResource(R.string.pair_status_probing),
                        color = LocalPalette.current.muted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (current is UiState.Failed) {
                    Notice(current.message, LocalPalette.current.error)
                }
                if (notificationsAsked && notificationsGranted == null) {
                    // The request is up: the explanation the honest flow
                    // needs, never a bare dialog.
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.pair_notifications_explaining),
                        color = LocalPalette.current.muted,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Hairline()

                // Block 4: the scan affordance, per 01-pair.html — the button
                // stands alone (no QR label above it). A missing or denied
                // camera never blocks pairing — the paste field above is the
                // fallback.
                Row {
                    GhostButton(
                        label = stringResource(R.string.pair_scan_qr),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            cameraMessage = null
                            if (context.packageManager.hasSystemFeature(
                                    android.content.pm.PackageManager.FEATURE_CAMERA_ANY,
                                )
                            ) {
                                cameraPermission.launch(android.Manifest.permission.CAMERA)
                            } else {
                                cameraMessage = context.getString(R.string.pair_no_camera)
                            }
                        },
                    )
                }
                cameraMessage?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Text(text = message, color = LocalPalette.current.muted, fontSize = 12.sp)
                }
            }

            is UiState.Challenge -> {
                Text(
                    text = stringResource(R.string.chal_verify),
                    color = LocalPalette.current.muted,
                    fontSize = 11.sp,
                    letterSpacing = 0.8.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.chal_presents),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = current.fingerprint,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LocalPalette.current.surfaceRaised, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                )
                if (current.pinned != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.chal_pinned_differs, current.pinned),
                        color = LocalPalette.current.error,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { connect(acceptChallenge = current, pair = pairParams) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalPalette.current.accent,
                        contentColor = LocalPalette.current.bg,
                        // A disabled action must read as disabled, not as a
                        // broken dark button: keep it legible and obviously off.
                        disabledContainerColor = LocalPalette.current.line,
                        disabledContentColor = LocalPalette.current.muted,
                    ),
                ) {
                    Text(text = stringResource(R.string.chal_accept), fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { screen.ui = UiState.Form },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = LocalPalette.current.muted,
                    ),
                ) {
                    Text(text = stringResource(R.string.chal_cancel))
                }
            }

            is UiState.Connected -> {
                // Supervision (T8c): the interruption lives in the machine,
                // not in a flag that can drift. The last snapshot stays on
                // screen behind the board's interrupted notice, and Retry
                // now re-runs the connect path.
                val interrupted = screen.machine.state as? SupervisionState.Interrupted
                if (interrupted != null) {
                    InterruptedNotice(
                        reason = interrupted.reason,
                        onRetry = { gated { connect(acceptChallenge = null, pair = pairParams) } },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (screen.inboxOpen) {
                    // --- 03-inbox.html: one surface, groups in the fixed ---
                    // --- order needs you → done → working → idle; the    ---
                    // --- group label carries the word and the count, and ---
                    // --- no row repeats it with a status chip.           ---
                    // S24-7: the chevron alone explains the way back — no
                    // repeated `Hosts` word. The row keeps a screen-reader
                    // label because the chevron glyph reads as nothing.
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        val backToHosts = stringResource(R.string.cd_back_to_hosts)
                        Text(
                            text = "‹",
                            color = LocalPalette.current.accent,
                            fontSize = 20.sp,
                            modifier = Modifier
                                .clickable { screen.inboxOpen = false }
                                .semantics { contentDescription = backToHosts }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.nodes_disconnect),
                            color = LocalPalette.current.muted,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clickable { disconnectLive() }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                    val snapshot = current.snapshot
                    when {
                        snapshot == null -> Text(
                            text = stringResource(R.string.nodes_waiting),
                            color = LocalPalette.current.muted,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        snapshot.agents.isEmpty() -> Text(
                            text = stringResource(R.string.inbox_no_agents),
                            color = LocalPalette.current.muted,
                            fontSize = 13.sp,
                        )
                        else -> {
                            // `unknown` is not on the board; it keeps its own
                            // honest group last rather than lying as idle.
                            // S24-6: human words in sentence case, and the
                            // count only when it is not one. S24-8: rows
                            // lead with the project name, not the path.
                            listOf(
                                Triple(
                                    stringResource(R.string.inbox_group_needs_you),
                                    snapshot.agents.filter { it.status == AgentStatusDto.BLOCKED },
                                    true,
                                ),
                                Triple(
                                    stringResource(R.string.inbox_group_done),
                                    snapshot.agents.filter { it.status == AgentStatusDto.DONE },
                                    false,
                                ),
                                Triple(
                                    stringResource(R.string.inbox_group_working),
                                    snapshot.agents.filter { it.status == AgentStatusDto.WORKING },
                                    false,
                                ),
                                Triple(
                                    stringResource(R.string.inbox_group_idle),
                                    snapshot.agents.filter { it.status == AgentStatusDto.IDLE },
                                    false,
                                ),
                                Triple(
                                    stringResource(R.string.inbox_group_unknown),
                                    snapshot.agents.filter { it.status == AgentStatusDto.UNKNOWN },
                                    false,
                                ),
                            ).forEach { (label, agents, needsYou) ->
                                if (agents.isNotEmpty()) {
                                    GroupLabel(groupLabelWithCount(label, agents.size), uppercase = false)
                                    agents.forEach { agent ->
                                        InboxRow(agent, needsYou = needsYou, onOpen = {
                                            openAgentSession(current, agent)
                                        })
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // --- 02-nodes.html: hosts as hairline rows on one ---
                    // --- surface, state as a plain word, agents count ---
                    // --- for context, the row is the entry point into ---
                    // --- the agent list.                              ---
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // The badge must never claim a live link the machine
                        // does not have: the word comes from the supervision
                        // state, not from the fact that this branch renders.
                        val stateWord = when (screen.machine.state) {
                            is SupervisionState.Interrupted ->
                                stringResource(R.string.nodes_state_interrupted)
                            is SupervisionState.Failed ->
                                stringResource(R.string.nodes_state_failed)
                            is SupervisionState.Connecting ->
                                stringResource(R.string.nodes_state_connecting)
                            else -> stringResource(R.string.nodes_state_connected)
                        }
                        Text(
                            text = "$stateWord · $session",
                            color = if (screen.machine.state is SupervisionState.Connected) {
                                LocalPalette.current.accent
                            } else {
                                LocalPalette.current.error
                            },
                            fontSize = 11.sp,
                            letterSpacing = 0.8.sp,
                        )
                        Text(
                            text = stringResource(R.string.nodes_disconnect),
                            color = LocalPalette.current.muted,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { disconnectLive() },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val snapshot = current.snapshot
                    if (snapshot == null) {
                        Text(
                            text = stringResource(R.string.nodes_waiting),
                            color = LocalPalette.current.muted,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.nodes_your_hosts),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 22.sp,
                            lineHeight = 26.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        GroupLabel("$user@$host")
                        NodeRow(
                            name = host,
                            // State as a word, never a stale snapshot rendered
                            // as if it were live (T8c).
                            state = if (interrupted != null) {
                                stringResource(R.string.nodes_state_row_interrupted)
                            } else {
                                stringResource(R.string.nodes_state_row_connected)
                            },
                            meta = stringResource(
                                R.string.nodes_meta,
                                "$user@$host",
                                port,
                                pluralStringResource(
                                    R.plurals.agents_count,
                                    snapshot.agents.size,
                                    snapshot.agents.size,
                                ),
                            ),
                            onOpen = { screen.inboxOpen = true },
                        )
                        // T17a (S15): the host's Gentle Shell sessions, listed
                        // over SFTP and lease-guarded — the entry point the
                        // continuity flow starts from. S24-3: plain language —
                        // the row itself says what it does, instead of the
                        // jargon group label `gentle shell sessions` and the
                        // `list · lease-guarded` state word.
                        NodeRow(
                            name = stringResource(R.string.nodes_session_history),
                            state = null,
                            meta = stringResource(R.string.nodes_session_history_sub),
                            onOpen = { route = Route.Sessions },
                        )
                        Spacer(Modifier.height(24.dp))
                        // Honest empty space, not a dead accent button: a
                        // second host cannot be added in this build.
                        Text(
                            text = stringResource(R.string.nodes_one_host),
                            color = LocalPalette.current.muted,
                            fontSize = 13.sp,
                        )
                        // Search stays out on purpose: with one host there is
                        // nothing real for it to filter, so the field would be
                        // dead chrome (design/system.md redundancy rule).
                    }
                }
            }

            is UiState.Session -> {
                val sessionUi = screen.sessions.getOrPut(current.agent.paneId) { SessionUi() }
                SessionScreen(
                    connection = current.connection,
                    agent = current.agent,
                    sessionUi = sessionUi,
                    supervision = screen.machine.state,
                    terminalFontPx = terminalFontPx,
                    onFontPxChange = { terminalFontPx = it },
                    onBack = {
                        screen.generation += 1
                        screen.ui = UiState.Connected(current.connection, snapshot = null)
                        // Going back restarts snapshot polling: a session open
                        // had stopped it, and an empty snapshot must refresh,
                        // not freeze on "Waiting for the first snapshot…".
                        // The pane poller stops itself: its generation check
                        // no longer matches a Connected screen.
                        poll(screen, current.connection, screen.generation)
                    },
                    onRetry = { gated { connect(acceptChallenge = null, pair = pairParams) } },
                    onRefresh = {
                        val generation = screen.generation
                        requestPaneRead(screen, sessionUi, current.connection, current.agent.paneId, generation)
                    },
                    onSend = { prompt ->
                        val connection = current.connection
                        val paneId = current.agent.paneId
                        val generation = screen.generation
                        sessionUi.sending = true
                        sessionUi.sendError = null
                        sessionUi.sent = false
                        executor.execute {
                            try {
                                connection.promptAgent(paneId, prompt)
                                // "Sent" only on the daemon's agent_prompted reply.
                                mainHandler.post {
                                    if (sessionStillCurrent(screen, sessionUi, paneId, generation)) {
                                        sessionUi.sending = false
                                        sessionUi.sent = true
                                        sessionUi.draft = ""
                                        // The uploads the prompt referenced have been
                                        // handed over: the rows leave with the draft.
                                        sessionUi.clearSentAttachments()
                                    }
                                }
                            } catch (error: Exception) {
                                // The draft stays: the prompt was not sent.
                                mainHandler.post {
                                    if (sessionStillCurrent(screen, sessionUi, paneId, generation)) {
                                        sessionUi.sending = false
                                        sessionUi.sent = false
                                        sessionUi.sendError = describe(context, error)
                                    }
                                }
                            }
                        }
                    },
                    onSendKeys = { keys ->
                        val connection = current.connection
                        val paneId = current.agent.paneId
                        val generation = screen.generation
                        sessionUi.keySending = true
                        sessionUi.keyError = null
                        executor.execute {
                            try {
                                connection.sendKeys(paneId, keys)
                                // "Sent" only on the daemon's `ok` reply.
                                mainHandler.post {
                                    if (sessionStillCurrent(screen, sessionUi, paneId, generation)) {
                                        sessionUi.keySending = false
                                    }
                                }
                            } catch (error: Exception) {
                                mainHandler.post {
                                    if (sessionStillCurrent(screen, sessionUi, paneId, generation)) {
                                        sessionUi.keySending = false
                                        sessionUi.keyError = describe(context, error)
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Snapshot polling: ask the core for the latest full snapshot every 500 ms
 * while the connection is live. One poller slot is registered for the
 * poller's whole life — rescheduling keeps it, stopping closes it, and the
 * log line at every change is the teardown proof (T8c). A dead connection
 * is reported to the supervision machine: the screen keeps the last
 * snapshot, rendered as not live (board 03), and Retry now re-runs the
 * connect path.
 */
private const val POLL_INTERVAL_MS = 500L

/** The advisory lease heartbeat cadence: matches the core's
 * LEASE_HEARTBEAT_INTERVAL_SECS, so a refresh always lands well before the
 * 90 s staleness threshold (S18). */
private const val LEASE_HEARTBEAT_MS = 30_000L

private fun poll(screen: ScreenState, connection: CompanionConnection, generation: Int) {
    val slot = screen.pollers.register()
    Log.i(TAG, "snapshot poller registered, active=${screen.pollers.count}")
    fun runOnce() {
        executor.execute {
            var reschedule = false
            try {
                val snapshot = try {
                    connection.snapshot()
                } catch (error: Exception) {
                    mainHandler.post {
                        if (screen.generation == generation) {
                            screen.machine.onDropped(
                                describe(AppLocale.wrapStored(screen.appContext), error),
                            )
                        }
                    }
                    return@execute
                }
                val ended = try {
                    connection.isDisconnected()
                } catch (_: Exception) {
                    true
                }
                mainHandler.post {
                    val current = screen.ui
                    if (screen.generation == generation &&
                        current is UiState.Connected &&
                        current.connection === connection
                    ) {
                        if (ended) {
                            screen.machine.onDropped(
                                AppLocale.wrapStored(screen.appContext)
                                    .getString(R.string.session_reason_host_closed),
                            )
                        } else if (snapshot != null) {
                            screen.ui = current.copy(snapshot = snapshot)
                        }
                    }
                }
                reschedule = !ended
            } finally {
                if (!reschedule) {
                    slot.close()
                    Log.i(TAG, "snapshot poller stopped, active=${screen.pollers.count}")
                }
            }
            if (reschedule) {
                mainHandler.postDelayed({
                    val current = screen.ui
                    if (screen.generation == generation &&
                        current is UiState.Connected &&
                        current.connection === connection
                    ) {
                        runOnce()
                    } else {
                        slot.close()
                        Log.i(TAG, "snapshot poller stopped, active=${screen.pollers.count}")
                    }
                }, POLL_INTERVAL_MS)
            }
        }
    }
    runOnce()
}

/**
 * Alert polling (T15, S11): a slow, separate poller that exists only for
 * notifications. It rides the same connection and the same core snapshot
 * the UI already uses — no extra network traffic, no relay — and it does
 * not stop while a session screen is up: the phone must still tell you
 * when an agent needs you even after you opened someone else's session.
 * Deliberately slower than the UI's 500 ms snapshot poll (2 s): the shade
 * does not need render cadence, and the battery rule stands — no new
 * wakeup pattern beyond what supervision already costs. The pure rules
 * (baseline, transition-only, suppression, debounce) live in
 * AgentAlertTracker and are unit-tested.
 */
private const val ALERT_POLL_MS = 2000L

private fun pollAlerts(
    context: Context,
    screen: ScreenState,
    connection: CompanionConnection,
) {
    val slot = screen.pollers.register()
    Log.i(TAG, "alert poller registered, active=${screen.pollers.count}")
    fun runOnce() {
        executor.execute {
            var reschedule = false
            try {
                val ended = try {
                    connection.isDisconnected()
                } catch (_: Exception) {
                    true
                }
                if (!ended) {
                    val snapshot = try {
                        connection.snapshot()
                    } catch (_: Exception) {
                        null
                    }
                    if (snapshot != null) {
                        mainHandler.post {
                            // The suppression rule reads UI state, so the
                            // transition decision runs on the main thread.
                            val showingPaneId = if (screen.appInForeground) {
                                (screen.ui as? UiState.Session)?.agent?.paneId
                            } else {
                                null
                            }
                            val alerts = screen.alertTracker.onSnapshot(
                                snapshot.agents.map {
                                    TrackedAgent(it.paneId, it.name, it.cwd, it.status)
                                },
                                showingPaneId,
                            )
                            alerts.forEach { postAgentAlert(context, it) }
                        }
                    }
                }
                reschedule = !ended
            } finally {
                if (!reschedule) {
                    slot.close()
                    Log.i(TAG, "alert poller stopped, active=${screen.pollers.count}")
                }
            }
            if (reschedule) {
                mainHandler.postDelayed({
                    val ended = try {
                        connection.isDisconnected()
                    } catch (_: Exception) {
                        true
                    }
                    if (!ended) {
                        runOnce()
                    } else {
                        slot.close()
                        Log.i(TAG, "alert poller stopped, active=${screen.pollers.count}")
                    }
                }, ALERT_POLL_MS)
            }
        }
    }
    runOnce()
}

/** The notification permission as this device currently has it: `null`
 *  below Android 13, where there is no runtime permission to deny. */
private fun notificationPermissionGranted(context: Context): Boolean? =
    if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        null
    }

private fun describe(context: Context, error: Exception): String = when (error) {
    is CompanionException.HostKeyMismatch ->
        context.getString(R.string.err_host_key_mismatch, error.pinned, error.presented)
    is CompanionException.HostKeyUnknown ->
        context.getString(R.string.err_host_key_unknown, error.fingerprint)
    is CompanionException.RequestRefused ->
        context.getString(R.string.err_request_refused, error.code.toString(), error.detail)
    is CompanionException.UnsupportedMethod ->
        context.getString(R.string.err_unsupported_method, error.method)
    is CompanionException.Transport ->
        context.getString(R.string.err_transport, error.detail)
    is CompanionException.Timeout ->
        context.getString(R.string.err_timeout, error.detail)
    else -> error.message ?: error.javaClass.simpleName
}

/** True while this pane's session screen is still the one on display. */
private fun sessionStillCurrent(
    screen: ScreenState,
    sessionUi: SessionUi,
    paneId: String,
    generation: Int,
): Boolean {
    val current = screen.ui
    return screen.generation == generation &&
        current is UiState.Session &&
        current.agent.paneId == paneId &&
        screen.sessions[paneId] === sessionUi
}

/**
 * Surface polling: about twelve times a second, read the core's latest-wins
 * slot. The throttle lives in the core (one slot, overwritten in place), so
 * a burst of frames between two polls has already collapsed into the newest
 * one — this loop can never queue a stale frame, it can only read the
 * current grid. Stops when the screen changes or the connection ended.
 */
private const val SURFACE_POLL_MS = 80L

private fun pollSurface(
    screen: ScreenState,
    sessionUi: SessionUi,
    connection: CompanionConnection,
    paneId: String,
    generation: Int,
) {
    // One slot for the poller's whole life; the stop log is the teardown
    // proof (T8c), and a dead connection is reported to the machine so the
    // session header says not live instead of a frozen grid passing as
    // current.
    val slot = screen.pollers.register()
    Log.i(TAG, "surface poller registered, active=${screen.pollers.count}")
    fun runOnce() {
        executor.execute {
            var reschedule = false
            try {
                val surface = try {
                    connection.latestSurface()
                } catch (_: Exception) {
                    null
                }
                mainHandler.post {
                    val previous = sessionUi.surface
                    if (sessionStillCurrent(screen, sessionUi, paneId, generation) &&
                        surface != null &&
                        (previous == null || surface.revision != previous.revision)
                    ) {
                        sessionUi.surface = surface
                        sessionUi.surfaceStats = surfaceStats(surface)
                    }
                }
                val ended = try {
                    connection.isDisconnected()
                } catch (_: Exception) {
                    true
                }
                if (ended) {
                    mainHandler.post {
                        if (screen.generation == generation) {
                            screen.machine.onDropped(
                                AppLocale.wrapStored(screen.appContext)
                                    .getString(R.string.session_reason_host_closed),
                            )
                        }
                    }
                }
                reschedule = !ended
            } finally {
                if (!reschedule) {
                    slot.close()
                    Log.i(TAG, "surface poller stopped, active=${screen.pollers.count}")
                }
            }
            if (reschedule) {
                mainHandler.postDelayed({
                    if (sessionStillCurrent(screen, sessionUi, paneId, generation)) {
                        runOnce()
                    } else {
                        slot.close()
                        Log.i(TAG, "surface poller stopped, active=${screen.pollers.count}")
                    }
                }, SURFACE_POLL_MS)
            }
        }
    }
    runOnce()
}

/** One immediate read, off the poll cadence — the manual refresh button. */
private fun requestPaneRead(
    screen: ScreenState,
    sessionUi: SessionUi,
    connection: CompanionConnection,
    paneId: String,
    generation: Int,
) {
    executor.execute {
        try {
            val read = connection.readPane(paneId)
            mainHandler.post {
                if (sessionStillCurrent(screen, sessionUi, paneId, generation)) {
                    sessionUi.pane = read
                    sessionUi.readError = null
                }
            }
        } catch (error: Exception) {
            mainHandler.post {
                if (sessionStillCurrent(screen, sessionUi, paneId, generation)) {
                    sessionUi.readError =
                        describe(AppLocale.wrapStored(screen.appContext), error)
                }
            }
        }
    }
}

/**
 * Pane polling: about twice per second, read the pane's visible text over
 * the request channel — the fallback while the surface stream has not
 * produced its first frame. Once a grid is flowing this stops: the surface
 * is the live view, and polling text beside it would be dead weight.
 */
private fun pollPane(
    screen: ScreenState,
    sessionUi: SessionUi,
    connection: CompanionConnection,
    paneId: String,
    generation: Int,
) {
    // Same slot discipline as the other pollers (T8c teardown proof).
    val slot = screen.pollers.register()
    Log.i(TAG, "pane poller registered, active=${screen.pollers.count}")
    fun runOnce() {
        executor.execute {
            var reschedule = false
            try {
                try {
                    val read = connection.readPane(paneId)
                    mainHandler.post {
                        if (sessionStillCurrent(screen, sessionUi, paneId, generation)) {
                            sessionUi.pane = read
                            sessionUi.readError = null
                        }
                    }
                } catch (error: Exception) {
                    mainHandler.post {
                        if (sessionStillCurrent(screen, sessionUi, paneId, generation)) {
                            sessionUi.readError =
                                describe(AppLocale.wrapStored(screen.appContext), error)
                        }
                    }
                }
                val ended = try {
                    connection.isDisconnected()
                } catch (_: Exception) {
                    true
                }
                if (ended) {
                    mainHandler.post {
                        if (screen.generation == generation) {
                            screen.machine.onDropped(
                                AppLocale.wrapStored(screen.appContext)
                                    .getString(R.string.session_reason_host_closed),
                            )
                        }
                    }
                }
                reschedule = !ended && sessionUi.surface == null
            } finally {
                if (!reschedule) {
                    slot.close()
                    Log.i(TAG, "pane poller stopped, active=${screen.pollers.count}")
                }
            }
            if (reschedule) {
                mainHandler.postDelayed({
                    if (sessionStillCurrent(screen, sessionUi, paneId, generation)) {
                        runOnce()
                    } else {
                        slot.close()
                        Log.i(TAG, "pane poller stopped, active=${screen.pollers.count}")
                    }
                }, 500)
            }
        }
    }
    runOnce()
}

/** The agent session screen: header, terminal grid (or text fallback), composer.
 * Supervision (T8c) rides in as `supervision`: while the connection is not
 * live, the header says so — a frozen grid never passes as current — and the
 * board's interrupted notice with Retry now sits above the terminal. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionScreen(
    connection: CompanionConnection,
    agent: AgentDto,
    sessionUi: SessionUi,
    supervision: SupervisionState,
    terminalFontPx: Float,
    onFontPxChange: (Float) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onSend: (String) -> Unit,
    onSendKeys: (List<String>) -> Unit,
) {
    val interrupted = supervision as? SupervisionState.Interrupted
    // T13: the two pickers behind the composer's camera and clip icons.
    // The photo picker needs no permission; the camera intent writes into a
    // provider-owned cache file, so no storage permission either.
    val context = LocalContext.current
    val cameraUri = remember { mutableStateOf<Uri?>(null) }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            startAttachmentUpload(context, connection, sessionUi, uri)
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = cameraUri.value
        cameraUri.value = null
        if (taken && uri != null) {
            startAttachmentUpload(context, connection, sessionUi, uri)
        }
    }
    fun openCamera() {
        val dir = java.io.File(context.cacheDir, "attachments").apply { mkdirs() }
        val file = java.io.File(dir, "camera-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
        cameraUri.value = uri
        try {
            cameraLauncher.launch(uri)
        } catch (error: Exception) {
            sessionUi.attachmentError = context.getString(R.string.session_no_camera_app)
        }
    }
    fun openPicker() {
        try {
            pickLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        } catch (error: Exception) {
            sessionUi.attachmentError = context.getString(R.string.session_no_gallery_app)
        }
    }

    // T14 (S8): the shared dictation session — the real recognizer behind
    // the mic, with the permission gate routed through the composer's
    // explanation line. The machine decides the state; this only mirrors it.
    val dictationSession = rememberDictationSession(
        controller = sessionUi.dictationController,
        onApply = { update ->
            sessionUi.draft = update.draft
            sessionUi.dictation = update.state
        },
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Edge-to-edge: without the insets the header sits under the status
            // bar and the composer under the navigation bar, and the keyboard
            // would cover the field the user is typing into.
            .safeDrawingPadding()
            .imePadding()
            .padding(16.dp),
    ) {
        // Header: back to the agents list, agent name and its cwd.
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.inbox_back),
                color = LocalPalette.current.accent,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(vertical = 12.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = agent.name,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        agent.cwd,
                        statusWord(agent.status),
                    ).joinToString(" · "),
                    color = LocalPalette.current.muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.session_refresh),
                color = LocalPalette.current.accent,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable(onClick = onRefresh)
                    .padding(vertical = 12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            // The liveness word is the machine's, never assumed: only a
            // connected connection may claim live (T8c).
            text = if (interrupted == null) {
                stringResource(
                    R.string.session_live_line,
                    stringResource(R.string.status_live),
                )
            } else {
                stringResource(
                    R.string.session_not_live_line,
                    stringResource(R.string.status_not_live),
                )
            },
            color = if (interrupted == null) LocalPalette.current.muted else LocalPalette.current.error,
            fontSize = 11.sp,
            letterSpacing = 0.8.sp,
        )
        if (interrupted != null) {
            Spacer(Modifier.height(8.dp))
            InterruptedNotice(reason = interrupted.reason, onRetry = onRetry)
        }

        // Terminal area: the cell grid from the surface stream. Before the
        // first surface arrives (or if the host never sends one) the pane's
        // visible text shows here instead — never a silently empty view.
        Spacer(Modifier.height(8.dp))
        val surface = sessionUi.surface
        val surfaceStats = sessionUi.surfaceStats
        val readError = sessionUi.readError
        if (surface != null) {
            val metrics = rememberTerminalCellMetrics()
            // The terminal area owns the leftover height (weight(1f)); the
            // composer below it is measured first at its own intrinsic
            // height, so the grid can never run beneath the composer and
            // the composer can never be pushed off the screen.
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val density = LocalDensity.current
                val cols = surface.width.toInt().coerceAtLeast(1)
                val rows = surface.height.toInt().coerceAtLeast(1)
                // Fit rule: shrink the font until ALL columns fit the
                // measured box, using the advance ratio measured from the
                // bundled font (the same measurement the renderer slots
                // cells with). Below the readability floor the font stops
                // shrinking and the grid scrolls horizontally instead —
                // the right edge is never silently clipped.
                val fitPx = constraints.maxWidth / (cols * metrics.advanceRatio)
                val minPx = with(density) { TerminalFontMinSp.toPx() }
                val maxPx = with(density) { TerminalFontMaxSp.toPx() }
                val effectivePx = (if (terminalFontPx > 0f) terminalFontPx else fitPx)
                    .coerceIn(minPx, maxPx)
                val gridWidthPx = cols * metrics.advanceRatio * effectivePx
                val gridHeightPx = rows * metrics.lineHeightRatio * effectivePx

                var viewSize by remember { mutableStateOf(IntSize.Zero) }
                val hScroll = rememberScrollState()
                val vScroll = rememberScrollState()
                val cellHalfPx = (metrics.lineHeightRatio * effectivePx / 2f).toInt().coerceAtLeast(1)
                // Pinned to the bottom while the user is there: new frames
                // follow only when pinned, so reading history is not yanked.
                val pinnedToBottom = remember { mutableStateOf(true) }
                LaunchedEffect(vScroll, cellHalfPx) {
                    snapshotFlow { vScroll.value }.collect {
                        pinnedToBottom.value = vScroll.maxValue == 0 ||
                            vScroll.value >= vScroll.maxValue - cellHalfPx
                    }
                }
                LaunchedEffect(surface.revision, effectivePx, gridHeightPx, viewSize) {
                    if (pinnedToBottom.value) vScroll.scrollTo(vScroll.maxValue)
                }
                val atBottom = vScroll.maxValue == 0 ||
                    vScroll.value >= vScroll.maxValue - cellHalfPx

                // Pinch only changes the font size; one-finger drags belong
                // to the scroll (canPan = false keeps the transform detector
                // out of the drag gesture's way).
                val transformState = rememberTransformableState { zoomChange, _, _ ->
                    val current = if (terminalFontPx > 0f) terminalFontPx else effectivePx
                    onFontPxChange((current * zoomChange).coerceIn(minPx, maxPx))
                }
                val statsLine = surfaceStats?.let {
                    stringResource(
                        R.string.session_stats_line,
                        it.cols,
                        it.rows,
                        it.drawnCells,
                        it.distinctColours,
                    ) +
                        (it.wideSymbol?.let { w -> stringResource(R.string.session_stats_wide, w) } ?: "") +
                        (if (it.cursorVisible) stringResource(R.string.session_stats_cursor) else "")
                } ?: ""
                Column(Modifier.fillMaxSize()) {
                    if (statsLine.isNotEmpty()) {
                        Text(
                            text = statsLine,
                            color = LocalPalette.current.muted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .onSizeChanged { viewSize = it }
                            .verticalScroll(vScroll)
                            .horizontalScroll(hScroll)
                            .transformable(transformState, canPan = { false }),
                    ) {
                        // The canvas covers the whole viewport when the grid
                        // fits (background everywhere) and the grid's own size
                        // when it overflows: scrolling then reveals the rest —
                        // nothing is clipped.
                        val canvasWidthPx = maxOf(gridWidthPx, viewSize.width.toFloat())
                        val canvasHeightPx = maxOf(gridHeightPx, viewSize.height.toFloat())
                        TerminalSurface(
                            surface = surface,
                            fontSizePx = effectivePx,
                            modifier = Modifier.size(
                                with(density) { canvasWidthPx.toDp() },
                                with(density) { canvasHeightPx.toDp() },
                            ),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // Footer: the fit is checkable at a glance — grid size,
                    // live font size, and which scroll paths are active.
                    val fontSp = with(density) { effectivePx.toSp() }.value
                    Text(
                        text = buildString {
                            append(cols)
                            append("×")
                            append(rows)
                            append(" · ")
                            append("%.1f".format(fontSp))
                            append(stringResource(R.string.session_stats_sp))
                            if (gridWidthPx > viewSize.width + 0.5f) {
                                append(stringResource(R.string.session_stats_scroll))
                            }
                            if (gridHeightPx > viewSize.height + 0.5f) {
                                append(
                                    if (atBottom) {
                                        stringResource(R.string.session_stats_at_bottom)
                                    } else {
                                        stringResource(R.string.session_stats_scrolled)
                                    },
                                )
                            }
                        },
                        color = LocalPalette.current.muted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else when {
            readError != null -> Text(
                text = readError,
                color = LocalPalette.current.error,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            else -> {
                val pane = sessionUi.pane
                Text(
                    text = when {
                        pane == null -> stringResource(R.string.session_reading_pane)
                        pane.text.isEmpty() -> stringResource(R.string.session_pane_empty)
                        else -> pane.text
                    },
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .background(LocalPalette.current.surface, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                )
                if (pane?.truncated == true) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.session_pane_truncated),
                        color = LocalPalette.current.muted,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        // Extra keys (board 04): the designed strip between the terminal
        // and the composer. Every chip maps to a key name the daemon
        // accepts (measured: esc, tab, enter, up, down, and ctrl+/alt+
        // compositions), so all are enabled; Ctrl and Alt are sticky.
        Spacer(Modifier.height(8.dp))
        ExtraKeyRow(sessionUi, onSendKeys)

        // Composer: a prompt, sent through the daemon's own agent.prompt.
        // One row — the field with the send action beside it — so the
        // composer stays inside the one-thumb zone on a phone: a full-width
        // button row pushes it off the bottom.
        Spacer(Modifier.height(8.dp))
        // Composer: a prompt, sent through the daemon's own agent.prompt.
        // One designed row per 04-session.html — see ui/session/SessionComposer.
        // The send prompt is the draft plus the confirmed uploads' suffix
        // lines, composed by the core so the contract lives in one place.
        SessionComposer(
            draft = sessionUi.draft,
            onDraftChange = { sessionUi.draft = it },
            attachments = sessionUi.attachments,
            thumbnails = sessionUi.thumbnails,
            sending = sessionUi.sending,
            sendError = sessionUi.sendError,
            sent = sessionUi.sent,
            dictation = sessionUi.dictation,
            onSend = {
                val prompt = coreComposePrompt(
                    sessionUi.draft,
                    sessionUi.attachmentQueue.promptSuffixes(),
                )
                onSend(prompt)
            },
            onCamera = { openCamera() },
            onPickImage = { openPicker() },
            onStartDictation = { dictationSession.start(sessionUi.draft) },
            onStopDictation = { dictationSession.stop() },
            onCancelDictation = { dictationSession.cancel() },
            onRetryUpload = { id ->
                val uri = sessionUi.attachmentUris[id]
                if (uri != null) {
                    sessionUi.attachmentQueue.retry(id)
                    sessionUi.syncAttachments()
                    uploadAttachment(context, connection, sessionUi, id, uri)
                }
            },
            onRemoveAttachment = { id ->
                sessionUi.attachmentQueue.remove(id)
                sessionUi.thumbnails.remove(id)
                sessionUi.attachmentUris.remove(id)
                sessionUi.syncAttachments()
            },
        )
    }
}

/**
 * T13 pick → plan → add → upload. Runs on the main thread until the
 * transfer, which goes to the executor. Every refusal is honest and
 * pick-time: an oversize or unreadable image never becomes a half-added
 * attachment row.
 */
private fun startAttachmentUpload(
    context: android.content.Context,
    connection: CompanionConnection,
    sessionUi: SessionUi,
    uri: Uri,
) {
    try {
        val resolver = context.contentResolver
        var displayName: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    displayName = cursor.getString(0)
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null) {
            sessionUi.attachmentError = context.getString(R.string.att_unreadable)
            return
        }
        if (size < 0) size = bytes.size.toLong()
        val name = displayName?.takeIf { it.isNotBlank() } ?: "image.png"
        val plan = try {
            corePlanUpload(name, size.toULong())
        } catch (error: CompanionException) {
            sessionUi.attachmentError = describe(context, error)
            return
        }
        val thumbnail = decodeThumbnail(context, uri)
        val added = sessionUi.attachmentQueue.add(name, plan.storedName, size) { bytes, max ->
            context.getString(R.string.att_too_large, formatBytes(bytes), formatBytes(max))
        }
        val id = when (added) {
            is AddResult.Refused -> {
                sessionUi.attachmentError = added.reason
                return
            }
            is AddResult.Accepted -> added.id
        }
        if (thumbnail != null) sessionUi.thumbnails[id] = thumbnail
        sessionUi.attachmentUris[id] = uri
        sessionUi.syncAttachments()
        uploadAttachment(context, connection, sessionUi, id, uri)
    } catch (error: Exception) {
        sessionUi.attachmentError = describe(context, error)
    }
}

/**
 * The transfer itself: the bytes are re-read from the stored URI (so a
 * retry is always possible), handed to the core's SFTP lane, and every
 * outcome lands back on the queue on the main thread.
 */
private fun uploadAttachment(
    context: android.content.Context,
    connection: CompanionConnection,
    sessionUi: SessionUi,
    id: Long,
    uri: Uri,
) {
    val storedName = sessionUi.attachmentQueue.snapshot()
        .firstOrNull { it.id == id }?.storedName ?: return
    executor.execute {
        val outcome = try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw java.io.IOException(context.getString(R.string.att_unreadable_anymore))
            connection.uploadFile(
                storedName,
                bytes,
                object : UploadProgressListener {
                    override fun onProgress(sentBytes: ULong, totalBytes: ULong) {
                        mainHandler.post {
                            sessionUi.attachmentQueue.onProgress(id, sentBytes.toLong())
                            sessionUi.syncAttachments()
                        }
                    }
                },
            )
        } catch (error: Exception) {
            mainHandler.post {
                sessionUi.attachmentQueue.onFailed(id, describe(context, error))
                sessionUi.syncAttachments()
            }
            return@execute
        }
        mainHandler.post {
            sessionUi.attachmentQueue.onUploaded(id, outcome.remotePath, outcome.promptSuffix)
            sessionUi.syncAttachments()
        }
    }
}

/** A downsampled preview bitmap for the attachment row; `null` means the
 * plate stands in, never a broken image. */
private fun decodeThumbnail(context: android.content.Context, uri: Uri): ImageBitmap? = try {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (
        (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) &&
        sample < 64
    ) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?.asImageBitmap()
} catch (_: Exception) {
    null
}

@Composable
private fun BlockLabel(text: String) {
    // design/system.md: uppercase meta labels, one step smaller and muted.
    Text(
        text = text.uppercase(),
        color = LocalPalette.current.muted,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Hairline() {
    // 01-pair.html: blocks separated by a 1px line on the same surface —
    // never a nested card.
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalPalette.current.line),
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onBackground,
        ),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Notice(message: String, color: androidx.compose.ui.graphics.Color) {
    // 01-pair.html `.notice`: a left rule and quiet text, never a filled
    // banner. The rejection text is readable, not a spinner that hides it.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(IntrinsicSize.Min),
    ) {
        Spacer(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(color),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = message,
            color = color,
            fontSize = 13.sp,
        )
    }
}

/** One agent row in the grouped inbox: name, working dir, kind — the
 * status lives in the group label, so the row carries no chip (board 03).
 * Elapsed time is not in the snapshot's AgentDto, so it is omitted rather
 * than invented. */
@Composable
private fun InboxRow(agent: AgentDto, needsYou: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onOpen)
            .then(if (needsYou) Modifier.background(LocalPalette.current.selection) else Modifier),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        // The needs-you left rule is the only colour on the list (board 03).
        Spacer(
            Modifier
                .width(2.dp)
                .heightIn(min = 56.dp)
                .background(if (needsYou) LocalPalette.current.accentEdge else Color.Transparent),
        )
        Spacer(Modifier.width(if (needsYou) 10.dp else 12.dp))
        Column(Modifier.weight(1f)) {
            // S24-8: the project name leads, never the full path.
            Text(
                text = AgentRowModel.projectTitle(agent.cwd, agent.name),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // S24-8: the full cwd stays here (board 03's meta), but the
            // default kind `pi` is never repeated beside the name.
            Text(
                text = listOfNotNull(agent.cwd, AgentRowModel.secondaryKind(agent.kind))
                    .joinToString(" · "),
                color = LocalPalette.current.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(text = "›", color = LocalPalette.current.muted, fontSize = 15.sp)
    }
}

/** One host row on the Nodes screen (board 02): state is a plain word in
 * secondary text — no chip, no row fill — and the row opens the agent list.
 * A row that explains itself (S24-3) passes `state = null` and shows none. */
@Composable
private fun NodeRow(name: String, state: String?, meta: String, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onOpen),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    text = name,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!state.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = state,
                        color = LocalPalette.current.muted,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                    )
                }
            }
            Text(
                text = meta,
                color = LocalPalette.current.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(text = "›", color = LocalPalette.current.muted, fontSize = 15.sp)
    }
}

/** The group header: the word and the count, quiet (board 03). S24-6: the
 *  inbox's human words arrive already sentence-cased (`uppercase = false`);
 *  meta labels keep the design's uppercase step. */
@Composable
private fun GroupLabel(text: String, uppercase: Boolean = true) {
    Text(
        text = if (uppercase) text.uppercase() else text,
        color = LocalPalette.current.muted,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/**
 * The interrupted-connection notice (board 03): a 2px error left rule with
 * quiet text — never a card and never a modal — and Retry now always visible
 * as accent text.
 */
@Composable
private fun InterruptedNotice(reason: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(LocalPalette.current.error),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.inbox_interrupted_title),
                color = LocalPalette.current.error,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                // The honest reason, then the rule: what is on screen is the
                // last data received, never live data (board 03, T8c).
                text = stringResource(R.string.inbox_interrupted_suffix, reason),
                color = LocalPalette.current.muted,
                fontSize = 13.sp,
            )
        }
        Text(
            text = stringResource(R.string.inbox_retry_now),
            color = LocalPalette.current.accentEdge,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clickable(onClick = onRetry)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}

/**
 * The extra-key row (board 04): Esc, Tab, Ctrl, Alt, arrows, Enter, sitting
 * above the composer. Every chip sends the daemon's own `pane.send_keys`
 * through the core — one request per press, `Ok(())` only on the daemon's
 * `ok` reply. Ctrl and Alt are sticky: tapping toggles the pressed state
 * (design `aria-pressed`), and the next key chip composes `ctrl+<key>`,
 * `alt+<key>` or `ctrl+alt+<key>` and consumes the sticky state — exactly
 * one press per modifier, so a sticky flag can never surprise a second key.
 * Every key here is representable on the wire (measured), so no chip needs
 * a disabled state; a failed send shows the host's typed refusal instead of
 * pretending the key landed.
 */
@Composable
private fun ExtraKeyRow(sessionUi: SessionUi, onSendKeys: (List<String>) -> Unit) {
    fun compose(key: String): List<String> {
        val modifiers = listOfNotNull(
            "ctrl".takeIf { sessionUi.ctrlSticky },
            "alt".takeIf { sessionUi.altSticky },
        )
        return listOf(if (modifiers.isEmpty()) key else modifiers.joinToString("+") + "+" + key)
    }

    fun press(key: String) {
        if (sessionUi.keySending) return
        onSendKeys(compose(key))
        // Sticky modifiers are one-shot: consumed by the key they composed.
        sessionUi.ctrlSticky = false
        sessionUi.altSticky = false
    }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // Sticky toggles first, in the board's order.
            ExtraKeyChip("Esc", enabled = true, sending = sessionUi.keySending) { press("esc") }
            ExtraKeyChip("Tab", enabled = true, sending = sessionUi.keySending) { press("tab") }
            ExtraKeyChip(
                "Ctrl",
                enabled = true,
                pressed = sessionUi.ctrlSticky,
                sending = sessionUi.keySending,
            ) { sessionUi.ctrlSticky = !sessionUi.ctrlSticky }
            ExtraKeyChip(
                "Alt",
                enabled = true,
                pressed = sessionUi.altSticky,
                sending = sessionUi.keySending,
            ) { sessionUi.altSticky = !sessionUi.altSticky }
            ExtraKeyChip("↑", enabled = true, sending = sessionUi.keySending) { press("up") }
            ExtraKeyChip("↓", enabled = true, sending = sessionUi.keySending) { press("down") }
            ExtraKeyChip("Enter", enabled = true, sending = sessionUi.keySending) { press("enter") }
        }
        sessionUi.keyError?.let { error ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = error,
                color = LocalPalette.current.error,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * One key chip: a ~30px visual inside a 44dp touch target (board 04).
 * `pressed` is the sticky-modifier state (design `aria-pressed="true"`):
 * accent edge and selection fill. A disabled chip keeps the muted text and
 * no click — the honest unavailable state, never a fake press.
 */
@Composable
private fun ExtraKeyChip(
    label: String,
    enabled: Boolean,
    pressed: Boolean = false,
    sending: Boolean = false,
    onPress: () -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .padding(horizontal = 3.dp, vertical = 8.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        val clickModifier = if (enabled && !sending) {
            Modifier.clickable(onClick = onPress)
        } else {
            Modifier
        }
        Box(
            modifier = Modifier
                .widthIn(min = 30.dp)
                .height(30.dp)
                .then(clickModifier)
                .border(
                    1.dp,
                    if (pressed) palette.accentEdge else palette.line,
                    RoundedCornerShape(8.dp),
                )
                .background(
                    if (pressed) palette.selection else palette.surfaceRaised,
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 4.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Text(
                text = label,
                color = when {
                    pressed -> palette.accentEdge
                    enabled -> MaterialTheme.colorScheme.onBackground
                    else -> palette.textDisabled
                },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun statusWord(status: AgentStatusDto): String = when (status) {
    AgentStatusDto.IDLE -> stringResource(R.string.status_idle)
    AgentStatusDto.WORKING -> stringResource(R.string.status_working)
    AgentStatusDto.BLOCKED -> stringResource(R.string.status_needs_you)
    AgentStatusDto.DONE -> stringResource(R.string.status_done)
    AgentStatusDto.UNKNOWN -> stringResource(R.string.status_unknown)
}

/** S24-1: the localized inbox group label — the human word, the count only
 *  when it is not one (the rule AgentRowModel.groupLabel encodes). */
@Composable
private fun groupLabelWithCount(word: String, count: Int): String =
    if (count == 1) word
    else stringResource(R.string.inbox_group_count, word, count)

private fun String.toUShortOrNull(): UShort? =
    trim().toIntOrNull()?.let { if (it in 1..65535) it.toUShort() else null }

@Preview(showBackground = true)
@Composable
private fun ConnectScreenPreview() {
    GentleMobileTheme {
        ConnectScreen(
            keyPath = "/data/local/tmp/client_ed25519",
            knownHostsPath = "/data/known_hosts",
            appTheme = AppTheme.CUTE,
            language = AppLanguage.ENGLISH,
            onSetLanguage = {},
            openAgentPane = androidx.compose.runtime.mutableStateOf<String?>(null),
            onSelectTheme = {},
        )
    }
}
