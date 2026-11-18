package com.gentlemanprogramming.gentlemobile

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.gentlemanprogramming.gentlemobile.core.SurfaceDto
import com.gentlemanprogramming.gentlemobile.core.acceptHostKey
import com.gentlemanprogramming.gentlemobile.core.AgentDto
import com.gentlemanprogramming.gentlemobile.core.AgentStatusDto
import com.gentlemanprogramming.gentlemobile.core.CompanionConnection
import com.gentlemanprogramming.gentlemobile.core.CompanionException
import com.gentlemanprogramming.gentlemobile.core.ConnectOutcome
import com.gentlemanprogramming.gentlemobile.core.HostProfileDto
import com.gentlemanprogramming.gentlemobile.core.PaneReadDto
import com.gentlemanprogramming.gentlemobile.core.SnapshotDto
import com.gentlemanprogramming.gentlemobile.core.connect as coreConnect
import com.gentlemanprogramming.gentlemobile.identity.DeviceIdentity
import com.gentlemanprogramming.gentlemobile.pair.PairCommand
import com.gentlemanprogramming.gentlemobile.pair.PairLink
import com.gentlemanprogramming.gentlemobile.pair.PairLinkResult
import com.gentlemanprogramming.gentlemobile.pair.QrScannerScreen
import com.gentlemanprogramming.gentlemobile.ui.theme.GentleMobileTheme
import com.gentlemanprogramming.gentlemobile.ui.theme.Tokens
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
 */
class MainActivity : ComponentActivity() {
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
            GentleMobileTheme {
                if (showFixture) {
                    com.gentlemanprogramming.gentlemobile.ui.terminal.FixtureTerminal()
                } else {
                    // The identity key lives in app-private storage now. The
                    // legacy external copy (the old adb-push path) is migrated
                    // once by DeviceIdentity.ensure, which also generates the
                    // keypair on first launch — no files are pushed any more.
                    val keyPath = File(filesDir, DeviceIdentity.KEY_FILE).absolutePath
                    val knownHostsPath = File(filesDir, "known_hosts").absolutePath
                    ConnectScreen(keyPath = keyPath, knownHostsPath = knownHostsPath)
                }
            }
        }
    }
}

// Background workers: read polling and user actions run off the UI thread
// on separate threads, so a stalled read never blocks a send.
private val executor = Executors.newFixedThreadPool(2)
private val mainHandler = Handler(Looper.getMainLooper())

private sealed interface UiState {
    data object Form : UiState
    data object Connecting : UiState
    data object Scanning : UiState
    data class Challenge(val fingerprint: String, val pinned: String?, val keyBlob: String) : UiState
    data class Connected(val connection: CompanionConnection, val snapshot: SnapshotDto?) : UiState
    data class Session(val connection: CompanionConnection, val agent: AgentDto) : UiState
    data class Failed(val message: String) : UiState
}

/** Screen state + connect generation, so poll callbacks can stop cleanly. */
private class ScreenState {
    var ui by mutableStateOf<UiState>(UiState.Form)
    var generation: Int = 0
    /** One session state per agent pane, so drafts survive going back. */
    val sessions = mutableMapOf<String, SessionUi>()
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

    /** Full re-baseline: a new connection owns a fresh core store, so the
     * drawn grid and the fallback read start empty again. Drafts survive. */
    fun resetBaseline() {
        pane = null
        readError = null
        surface = null
        surfaceStats = null
    }
}

@Composable
fun ConnectScreen(keyPath: String, knownHostsPath: String) {
    val screen = remember { ScreenState() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

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
    LaunchedEffect(Unit) {
        try {
            identity = withContext(Dispatchers.IO) { DeviceIdentity.ensure(context) }
        } catch (error: Exception) {
            identityError = describe(error)
        }
    }

    // Pairing link paste + QR scan.
    var pairLink by remember { mutableStateOf("") }
    var pairError by remember { mutableStateOf<String?>(null) }
    var cameraMessage by remember { mutableStateOf<String?>(null) }
    var copiedKey by remember { mutableStateOf(false) }
    var copiedCommand by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf(false) }
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

    fun connect(acceptChallenge: UiState.Challenge?) {
        screen.generation += 1
        val generation = screen.generation
        screen.ui = UiState.Connecting
        val profile = HostProfileDto(
            host = host,
            port = port.toUShortOrNull() ?: 0u,
            user = user,
            identityPath = keyPath,
            knownHostsPath = knownHostsPath,
        )
        val challenge = acceptChallenge
        executor.execute {
            try {
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
                                // session's grid and read start empty again.
                                screen.sessions.values.forEach { it.resetBaseline() }
                                screen.ui = UiState.Connected(connection, snapshot = null)
                            }
                        }
                        poll(screen, connection, generation)
                    }
                }
            } catch (error: Exception) {
                val message = describe(error)
                mainHandler.post { screen.ui = UiState.Failed(message) }
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
            // quiet so the Pair screen's own headline leads the surface.
            Text(
                text = "Gentle Mobile",
                color = Tokens.muted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
        }

        when (val current = screen.ui) {
            is UiState.Scanning -> {
                QrScannerScreen(
                    onQr = { link ->
                        screen.ui = UiState.Form
                        // The QR encodes the same gc:// link the host prints:
                        // one parser and one error path for paste and scan.
                        applyPairLink(link)
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
                    text = "Run gentle-companion-pair on the host, then paste its link — or scan the QR. Works over Tailscale or plain SSH.",
                    color = Tokens.muted,
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
                        color = Tokens.error,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    identity == null -> Text(
                        text = "Creating the key…",
                        color = Tokens.muted,
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
                                    .background(Tokens.surfaceRaised, RoundedCornerShape(8.dp))
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
                                color = if (copiedKey) Tokens.accent else Tokens.muted,
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
                        color = Tokens.muted,
                        fontSize = 12.sp,
                    )
                } else {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = Tokens.accent)) { append("\u27AF ") }
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
                            color = Tokens.accent,
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
                            color = Tokens.muted,
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
                        focusedBorderColor = Tokens.accent,
                        unfocusedBorderColor = Tokens.line,
                        focusedContainerColor = Tokens.surface,
                        unfocusedContainerColor = Tokens.surface,
                        cursorColor = Tokens.accent,
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
                    Notice(error, Tokens.error)
                }
                if (pasted) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Will connect to $host \u00B7 $port \u00B7 $user",
                        color = Tokens.muted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        // Typed links work too: the field is applied first, so
                        // paste and typing share one Connect action.
                        if (applyPairLink(pairLink)) connect(acceptChallenge = null)
                    },
                    enabled = pairLink.isNotBlank() && current !is UiState.Connecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Tokens.accent,
                        contentColor = Tokens.bg,
                        // A disabled action must read as disabled, not as a
                        // broken dark button: keep it legible and obviously off.
                        disabledContainerColor = Tokens.accentPressed,
                        disabledContentColor = Tokens.bg,
                    ),
                ) {
                    Text(
                        text = if (current is UiState.Connecting) "Connecting\u2026" else "Connect",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (current is UiState.Connecting) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Probing the host key, then opening the bridge\u2026",
                        color = Tokens.muted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (current is UiState.Failed) {
                    Notice(current.message, Tokens.error)
                }
                Spacer(Modifier.height(12.dp))
                Hairline()

                // Block 4: the QR affordance. A missing or denied camera never
                // blocks pairing — the paste field above is the fallback.
                BlockLabel("QR")
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
                    Text(text = message, color = Tokens.muted, fontSize = 12.sp)
                }
            }

            is UiState.Challenge -> {
                Text(
                    text = "Verify this host",
                    color = Tokens.muted,
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
                        .background(Tokens.surfaceRaised, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                )
                if (current.pinned != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "The pinned key is different: ${current.pinned}. " +
                            "Accepting retires the old pin.",
                        color = Tokens.error,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { connect(acceptChallenge = current) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Tokens.accent,
                        contentColor = Tokens.bg,
                        // A disabled action must read as disabled, not as a
                        // broken dark button: keep it legible and obviously off.
                        disabledContainerColor = Tokens.line,
                        disabledContentColor = Tokens.muted,
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
                        contentColor = Tokens.muted,
                    ),
                ) {
                    Text(text = "Cancel")
                }
            }

            is UiState.Connected -> {
                val snapshot = current.snapshot
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Connected · $session",
                        color = Tokens.accent,
                        fontSize = 11.sp,
                        letterSpacing = 0.8.sp,
                    )
                    Text(
                        text = "Disconnect",
                        color = Tokens.muted,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable {
                            screen.generation += 1
                            val connection = current.connection
                            executor.execute {
                                try {
                                    connection.disconnect()
                                } catch (_: Exception) {
                                }
                            }
                            screen.ui = UiState.Form
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (snapshot == null) {
                    Text(
                        text = "Waiting for the first snapshot…",
                        color = Tokens.muted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                } else {
                    Text(
                        text = "${snapshot.workspaces.size} workspaces · " +
                            "${snapshot.tabs.size} tabs · ${snapshot.panes.size} panes · " +
                            "${snapshot.agents.size} agents",
                        color = Tokens.muted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Agents",
                        color = Tokens.muted,
                        fontSize = 11.sp,
                        letterSpacing = 0.8.sp,
                    )
                    if (snapshot.agents.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "No agents in this snapshot.",
                            color = Tokens.muted,
                            fontSize = 13.sp,
                        )
                    } else {
                        snapshot.agents.forEachIndexed { index, agent ->
                            AgentRow(agent, onOpen = {
                                val connection = current.connection
                                screen.generation += 1
                                val generation = screen.generation
                                val sessionUi = screen.sessions.getOrPut(agent.paneId) { SessionUi() }
                                screen.ui = UiState.Session(connection, agent)
                                pollPane(screen, sessionUi, connection, agent.paneId, generation)
                                pollSurface(screen, sessionUi, connection, agent.paneId, generation)
                            })
                            if (index != snapshot.agents.lastIndex) {
                                Spacer(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(Tokens.line),
                                )
                            }
                        }
                    }
                }
            }

            is UiState.Session -> {
                val sessionUi = screen.sessions.getOrPut(current.agent.paneId) { SessionUi() }
                SessionScreen(
                    connection = current.connection,
                    agent = current.agent,
                    sessionUi = sessionUi,
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
                )
            }
        }
    }
}

/**
 * Snapshot polling: ask the core for the latest full snapshot every 500 ms
 * while the connection is live. Stops when the generation changes (a new
 * connect or disconnect) or the connection reports it ended.
 */
private fun poll(screen: ScreenState, connection: CompanionConnection, generation: Int) {
    executor.execute {
        val snapshot = try {
            connection.snapshot()
        } catch (error: Exception) {
            mainHandler.post { screen.ui = UiState.Failed(describe(error)) }
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
                screen.ui = if (ended) {
                    UiState.Failed("The connection ended.")
                } else {
                    current.copy(snapshot = snapshot ?: current.snapshot)
                }
            }
        }
        if (!ended) {
            mainHandler.postDelayed({
                val current = screen.ui
                if (screen.generation == generation &&
                    current is UiState.Connected &&
                    current.connection === connection
                ) {
                    poll(screen, connection, generation)
                }
            }, 500)
        }
    }
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
    executor.execute {
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
        if (!ended) {
            mainHandler.postDelayed({
                if (sessionStillCurrent(screen, sessionUi, paneId, generation)) {
                    pollSurface(screen, sessionUi, connection, paneId, generation)
                }
            }, SURFACE_POLL_MS)
        }
    }
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
        val ended = try {
            connection.isDisconnected()
        } catch (_: Exception) {
            true
        }
        if (!ended && sessionUi.surface == null) {
            mainHandler.postDelayed({
                if (sessionStillCurrent(screen, sessionUi, paneId, generation)) {
                    pollPane(screen, sessionUi, connection, paneId, generation)
                }
            }, 500)
        }
    }
}

/** The agent session screen: header, terminal grid (or text fallback), composer. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionScreen(
    connection: CompanionConnection,
    agent: AgentDto,
    sessionUi: SessionUi,
    terminalFontPx: Float,
    onFontPxChange: (Float) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSend: (String) -> Unit,
) {
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
                color = Tokens.accent,
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
                    color = Tokens.muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "Refresh",
                color = Tokens.accent,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable(onClick = onRefresh)
                    .padding(vertical = 12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The agent's pane, drawn live from the surface stream",
            color = Tokens.muted,
            fontSize = 11.sp,
            letterSpacing = 0.8.sp,
        )

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
                            color = Tokens.muted,
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
                        color = Tokens.muted,
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
                color = Tokens.error,
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
                        .background(Tokens.surface, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                )
                if (pane?.truncated == true) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "The host left out older rows.",
                        color = Tokens.muted,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        // Composer: a prompt, sent through the daemon's own agent.prompt.
        // One row — the field with the send action beside it — so the
        // composer stays inside the one-thumb zone on a phone: a full-width
        // button row pushes it off the bottom.
        Spacer(Modifier.height(8.dp))
        sessionUi.sendError?.let { error ->
            Text(
                text = error,
                color = Tokens.error,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(4.dp))
        }
        if (sessionUi.sent) {
            Text(
                text = "Sent — the host confirmed the submission.",
                color = Tokens.accent,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
        }
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = sessionUi.draft,
                onValueChange = { sessionUi.draft = it },
                modifier = Modifier.weight(1f),
                singleLine = false,
                maxLines = 3,
                placeholder = {
                    Text("Prompt ${agent.name}…", color = Tokens.muted, fontSize = 13.sp)
                },
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Tokens.accent,
                    unfocusedBorderColor = Tokens.line,
                    focusedContainerColor = Tokens.surface,
                    unfocusedContainerColor = Tokens.surface,
                    cursorColor = Tokens.accent,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onSend(sessionUi.draft) },
                enabled = !sessionUi.sending && sessionUi.draft.isNotBlank(),
                modifier = Modifier
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Tokens.accent,
                    contentColor = Tokens.bg,
                    disabledContainerColor = Tokens.accentPressed,
                    disabledContentColor = Tokens.bg,
                ),
            ) {
                Text(
                    text = if (sessionUi.sending) "…" else "Send",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun BlockLabel(text: String) {
    // design/system.md: uppercase meta labels, one step smaller and muted.
    Text(
        text = text.uppercase(),
        color = Tokens.muted,
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
            .background(Tokens.line),
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

@Composable
private fun AgentRow(agent: AgentDto, onOpen: () -> Unit) {
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
                    text = agent.name,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusWord(agent.status),
                    color = when (agent.status) {
                        AgentStatusDto.WORKING -> Tokens.accent
                        AgentStatusDto.BLOCKED -> Tokens.error
                        else -> Tokens.muted
                    },
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                )
            }
            Text(
                text = listOfNotNull(agent.kind, agent.cwd).joinToString(" · "),
                color = Tokens.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(text = "›", color = Tokens.muted, fontSize = 15.sp)
    }
}

private fun statusWord(status: AgentStatusDto): String = when (status) {
    AgentStatusDto.IDLE -> "idle"
    AgentStatusDto.WORKING -> "working"
    AgentStatusDto.BLOCKED -> "blocked"
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
        )
    }
}
