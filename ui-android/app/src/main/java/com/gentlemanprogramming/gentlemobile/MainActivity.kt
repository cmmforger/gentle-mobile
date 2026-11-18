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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import android.provider.OpenableColumns
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import com.gentlemanprogramming.gentlemobile.core.forgetHost
import com.gentlemanprogramming.gentlemobile.core.listKnownHosts
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
    override fun onCreate(savedInstanceState: Bundle?) {
        // The launch theme carries the splash background; this is the real
        // app theme from the first composed frame on.
        setTheme(R.style.Theme_GentleMobile)
        super.onCreate(savedInstanceState)
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
        setContent {
            val themeStore = remember {
                ThemeStore(SharedPreferencesStore(this@MainActivity))
            }
            var appTheme by remember { mutableStateOf(themeStore.selected()) }
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

// Background workers: read polling and user actions run off the UI thread
// on separate threads, so a stalled read never blocks a send.
private val executor = Executors.newFixedThreadPool(2)
private val mainHandler = Handler(Looper.getMainLooper())

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

    /** T8c supervision: the four words (connecting, connected, interrupted,
     * failed) and the liveness rule, in one tested place. Screens read this
     * machine instead of a separate interrupted flag that can drift from
     * what the connection is really doing. */
    val machine = SupervisionMachine()

    /** Registered pollers, logged at every change so teardown — cancelling
     * a session, going back to the host list, replacing the connection — is
     * provable with a counter that returns to zero. */
    val pollers = PollTracker()
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
private enum class Route { Main, Settings, Themes }

@Composable
fun ConnectScreen(
    keyPath: String,
    knownHostsPath: String,
    appTheme: AppTheme,
    onSelectTheme: (AppTheme) -> Unit,
) {
    val screen = remember { ScreenState() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

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
                mainHandler.post { identityError = describe(error) }
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
        val activity = context as? FragmentActivity
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

    // Splash (S21, board 01): the mark, the wordmark and a thin progress line
    // for exactly as long as the cold-start key work runs — never a fake
    // delay. It hides as soon as the first screen is ready, including when
    // the key work failed (the error is shown instead of a spinner).
    if (identity == null && identityError == null) {
        SplashScreen()
        return
    }

    fun disconnectLive() {
        val current = screen.ui
        if (current is UiState.Connected) {
            screen.generation += 1
            val connection = current.connection
            executor.execute {
                try {
                    connection.disconnect()
                } catch (_: Exception) {
                }
            }
            screen.ui = UiState.Form
            screen.machine.reset()
        }
    }

    if (route == Route.Settings) {
        SettingsScreen(
            identity = identity,
            identityError = identityError,
            knownHosts = knownHosts,
            biometricDecision = biometricDecision,
            biometricEnabled = biometricEnabled,
            theme = appTheme,
            connected = screen.ui is UiState.Connected,
            regenerated = regenerated,
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
                        mainHandler.post { identityError = describe(error) }
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
            cameraMessage = "Camera permission denied — paste the link instead."
        }
    }

    fun applyPairLink(raw: String): Boolean {
        val parsed = PairLink.parse(raw)
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
            "Pairing — sending this phone's key to ${it.host}:${it.port}…"
        }
        val profile = HostProfileDto(
            host = host,
            port = port.toUShortOrNull() ?: 0u,
            user = user,
            identityPath = keyPath,
            knownHostsPath = knownHostsPath,
        )
        val challenge = acceptChallenge
        val keyLine = identity?.publicKeyLine
        executor.execute {
            try {
                if (pair != null) {
                    if (keyLine == null) {
                        mainHandler.post {
                            screen.ui = UiState.Failed(
                                "No client key on this phone yet — the identity must exist before pairing.",
                            )
                        }
                        return@execute
                    }
                    when (val reply = PairClient.sendKey(pair.host, pair.port, pair.token, keyLine)) {
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
                                pairStatus = "Key accepted — connecting…"
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
                            "No client key at $keyPath — the identity could not be prepared on this phone.",
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
                                screen.sessions.values.forEach { it.resetBaseline() }
                                screen.inboxOpen = false
                                screen.machine.onLive()
                                screen.ui = UiState.Connected(connection, snapshot = null)
                            }
                        }
                        poll(screen, connection, generation)
                    }
                }
            } catch (error: Exception) {
                val message = describe(error)
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
                                    screen.machine.onDropped("the link was down while the app was away")
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
    // instead of trusting a socket that may have died unnoticed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onAppForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            // quiet so the Pair screen's own headline leads the surface. The
            // Settings affordance lives here: the one screen the boards route
            // everything else from.
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = "Gentle Mobile",
                    color = LocalPalette.current.muted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Settings",
                    color = LocalPalette.current.accentEdge,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clickable { route = Route.Settings }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                )
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
                    text = "Connect to your computer",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 22.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Run gentle-mobile-pair on the host, scan its QR — or paste the link — and the key is trusted in one step. The host key is confirmed once after.",
                    color = LocalPalette.current.muted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                Hairline()

                // Block 1: this phone's own public key, with the identity
                // state shown honestly — "creating the key…", the error, or
                // the key itself. Never a spinner that hides a failure.
                BlockLabel("This phone's key")
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
                        text = "Creating the key…",
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
                                label = "Copy",
                                onClick = {
                                    clipboard.setText(AnnotatedString(identity.publicKeyLine))
                                    copiedKey = true
                                    copiedCommand = false
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (copiedKey) "Copied" else
                                    "The host must trust this key before it can connect.",
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
                BlockLabel("On the host")
                val identityForCommand = identity
                if (identityForCommand == null) {
                    Text(
                        text = "Ready when this phone's key exists.",
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
                            label = "Copy",
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
                            text = "Copied — run it on the host.",
                            color = LocalPalette.current.accent,
                            fontSize = 11.sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Hairline()

                // Block 3: the connection link, pasted from the host output.
                BlockLabel("Connection link")
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
                        label = "Paste",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val text = clipboard.getText()?.text.orEmpty()
                            if (text.isBlank()) {
                                pairError =
                                    "The clipboard is empty — copy the link the host printed."
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
                            "Will pair with ${it.host}:${it.port} \u00B7 ssh $host:$port \u00B7 $user"
                        } ?: "Will connect to $host \u00B7 $port \u00B7 $user",
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
                            current is UiState.Connecting && pairParams != null -> "Pairing\u2026"
                            current is UiState.Connecting -> "Connecting\u2026"
                            else -> "Connect"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (current is UiState.Connecting) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = pairStatus ?: "Probing the host key, then opening the bridge\u2026",
                        color = LocalPalette.current.muted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (current is UiState.Failed) {
                    Notice(current.message, LocalPalette.current.error)
                }
                Spacer(Modifier.height(12.dp))
                Hairline()

                // Block 4: the scan affordance, per 01-pair.html — the button
                // stands alone (no QR label above it). A missing or denied
                // camera never blocks pairing — the paste field above is the
                // fallback.
                Row {
                    GhostButton(
                        label = "Scan QR",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            cameraMessage = null
                            if (context.packageManager.hasSystemFeature(
                                    android.content.pm.PackageManager.FEATURE_CAMERA_ANY,
                                )
                            ) {
                                cameraPermission.launch(android.Manifest.permission.CAMERA)
                            } else {
                                cameraMessage = "This device has no camera — paste the link instead."
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
                    text = "Verify this host",
                    color = LocalPalette.current.muted,
                    fontSize = 11.sp,
                    letterSpacing = 0.8.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "The host presented this key fingerprint:",
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
                        text = "The pinned key is different: ${current.pinned}. " +
                            "Accepting retires the old pin.",
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
                    Text(text = "Accept & connect", fontWeight = FontWeight.SemiBold)
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
                    Text(text = "Cancel")
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
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            text = "‹ Hosts",
                            color = LocalPalette.current.accent,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable { screen.inboxOpen = false }
                                .padding(vertical = 12.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "Disconnect",
                            color = LocalPalette.current.muted,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { disconnectLive() },
                        )
                    }
                    val snapshot = current.snapshot
                    when {
                        snapshot == null -> Text(
                            text = "Waiting for the first snapshot…",
                            color = LocalPalette.current.muted,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        snapshot.agents.isEmpty() -> Text(
                            text = "No agents in this snapshot.",
                            color = LocalPalette.current.muted,
                            fontSize = 13.sp,
                        )
                        else -> {
                            // `unknown` is not on the board; it keeps its own
                            // honest group last rather than lying as idle.
                            listOf(
                                Triple("needs you", snapshot.agents.filter { it.status == AgentStatusDto.BLOCKED }, true),
                                Triple("done", snapshot.agents.filter { it.status == AgentStatusDto.DONE }, false),
                                Triple("working", snapshot.agents.filter { it.status == AgentStatusDto.WORKING }, false),
                                Triple("idle", snapshot.agents.filter { it.status == AgentStatusDto.IDLE }, false),
                                Triple("unknown", snapshot.agents.filter { it.status == AgentStatusDto.UNKNOWN }, false),
                            ).forEach { (label, agents, needsYou) ->
                                if (agents.isNotEmpty()) {
                                    GroupLabel("$label · ${agents.size}")
                                    agents.forEach { agent ->
                                        InboxRow(agent, needsYou = needsYou, onOpen = {
                                            gated {
                                                val connection = current.connection
                                                screen.generation += 1
                                                val generation = screen.generation
                                                val sessionUi = screen.sessions.getOrPut(agent.paneId) { SessionUi() }
                                                screen.ui = UiState.Session(connection, agent)
                                                pollPane(screen, sessionUi, connection, agent.paneId, generation)
                                                pollSurface(screen, sessionUi, connection, agent.paneId, generation)
                                            }
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
                            is SupervisionState.Interrupted -> "Interrupted"
                            is SupervisionState.Failed -> "Failed"
                            is SupervisionState.Connecting -> "Connecting"
                            else -> "Connected"
                        }
                        Text(
                            text = "$stateWord · $session",
                            color = if (stateWord == "Connected") {
                                LocalPalette.current.accent
                            } else {
                                LocalPalette.current.error
                            },
                            fontSize = 11.sp,
                            letterSpacing = 0.8.sp,
                        )
                        Text(
                            text = "Disconnect",
                            color = LocalPalette.current.muted,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { disconnectLive() },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val snapshot = current.snapshot
                    if (snapshot == null) {
                        Text(
                            text = "Waiting for the first snapshot…",
                            color = LocalPalette.current.muted,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    } else {
                        Text(
                            text = "Your hosts",
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
                            state = if (interrupted != null) "interrupted" else "connected",
                            meta = "$user@$host · port $port · protocol 22 · ${snapshot.agents.size} agents",
                            onOpen = { screen.inboxOpen = true },
                        )
                        Spacer(Modifier.height(24.dp))
                        // Honest empty space, not a dead accent button: a
                        // second host cannot be added in this build.
                        Text(
                            text = "One host per install for now — adding a second host needs a later build.",
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
                                        sessionUi.sendError = describe(error)
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
                                        sessionUi.keyError = describe(error)
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
                            screen.machine.onDropped(describe(error))
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
                            screen.machine.onDropped("the host closed the connection")
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

private fun describe(error: Exception): String = when (error) {
    is CompanionException.HostKeyMismatch ->
        "Host key mismatch: pinned ${error.pinned}, presented ${error.presented}."
    is CompanionException.HostKeyUnknown ->
        "Unknown host key: ${error.fingerprint}."
    is CompanionException.RequestRefused ->
        "The host refused it (${error.code}): ${error.detail}"
    is CompanionException.UnsupportedMethod ->
        "This host does not offer ${error.method}."
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
                            screen.machine.onDropped("the host closed the connection")
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
                    sessionUi.readError = describe(error)
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
                            sessionUi.readError = describe(error)
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
                            screen.machine.onDropped("the host closed the connection")
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
            sessionUi.attachmentError = "no camera app is available on this device"
        }
    }
    fun openPicker() {
        try {
            pickLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        } catch (error: Exception) {
            sessionUi.attachmentError = "no gallery app is available on this device"
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
                text = "‹ Agents",
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
                    text = listOfNotNull(agent.cwd, statusWord(agent.status)).joinToString(" · "),
                    color = LocalPalette.current.muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "Refresh",
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
                "live · ${livenessLabel(supervision)} · the agent's pane from the surface stream"
            } else {
                "not live · this is the last frame before the interruption"
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
                    "${it.cols}×${it.rows} · ${it.drawnCells} non-blank · " +
                        "${it.distinctColours} colours" +
                        (it.wideSymbol?.let { w -> " · wide \"$w\"" } ?: "") +
                        (if (it.cursorVisible) " · cursor" else "")
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
                            append("sp")
                            if (gridWidthPx > viewSize.width + 0.5f) append(" · scroll ↔")
                            if (gridHeightPx > viewSize.height + 0.5f) {
                                append(if (atBottom) " · at bottom" else " · scrolled")
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
                        pane == null -> "Reading the pane…"
                        pane.text.isEmpty() -> "(the pane shows no text right now)"
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
                        text = "The host left out older rows.",
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
            sessionUi.attachmentError = "the image could not be read"
            return
        }
        if (size < 0) size = bytes.size.toLong()
        val name = displayName?.takeIf { it.isNotBlank() } ?: "image.png"
        val plan = try {
            corePlanUpload(name, size.toULong())
        } catch (error: CompanionException) {
            sessionUi.attachmentError = describe(error)
            return
        }
        val thumbnail = decodeThumbnail(context, uri)
        val added = sessionUi.attachmentQueue.add(name, plan.storedName, size)
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
        sessionUi.attachmentError = describe(error)
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
                ?: throw java.io.IOException("the image could not be read anymore")
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
                sessionUi.attachmentQueue.onFailed(id, describe(error))
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
            Text(
                text = agent.name,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(agent.cwd, agent.kind).joinToString(" · "),
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
 * secondary text — no chip, no row fill — and the row opens the agent list. */
@Composable
private fun NodeRow(name: String, state: String, meta: String, onOpen: () -> Unit) {
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
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state,
                    color = LocalPalette.current.muted,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp,
                )
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

/** The group header: the word and the count, uppercase, quiet (board 03). */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text.uppercase(),
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
                text = "Connection interrupted.",
                color = LocalPalette.current.error,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                // The honest reason, then the rule: what is on screen is the
                // last snapshot, never live data (board 03, T8c).
                text = "$reason — what is on screen is the last snapshot.",
                color = LocalPalette.current.muted,
                fontSize = 13.sp,
            )
        }
        Text(
            text = "Retry now",
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

private fun statusWord(status: AgentStatusDto): String = when (status) {
    AgentStatusDto.IDLE -> "idle"
    AgentStatusDto.WORKING -> "working"
    AgentStatusDto.BLOCKED -> "needs you"
    AgentStatusDto.DONE -> "done"
    AgentStatusDto.UNKNOWN -> "unknown"
}

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
            onSelectTheme = {},
        )
    }
}
