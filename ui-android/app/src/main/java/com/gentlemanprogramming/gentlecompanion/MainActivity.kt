package com.gentlemanprogramming.gentlecompanion

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gentlemanprogramming.gentlecompanion.core.SurfaceDto
import com.gentlemanprogramming.gentlecompanion.core.acceptHostKey
import com.gentlemanprogramming.gentlecompanion.core.AgentDto
import com.gentlemanprogramming.gentlecompanion.core.AgentStatusDto
import com.gentlemanprogramming.gentlecompanion.core.CompanionConnection
import com.gentlemanprogramming.gentlecompanion.core.CompanionException
import com.gentlemanprogramming.gentlecompanion.core.ConnectOutcome
import com.gentlemanprogramming.gentlecompanion.core.HostProfileDto
import com.gentlemanprogramming.gentlecompanion.core.PaneReadDto
import com.gentlemanprogramming.gentlecompanion.core.SnapshotDto
import com.gentlemanprogramming.gentlecompanion.core.connect as coreConnect
import com.gentlemanprogramming.gentlecompanion.ui.theme.GentleCompanionTheme
import com.gentlemanprogramming.gentlecompanion.ui.theme.Tokens
import com.gentlemanprogramming.gentlecompanion.ui.terminal.SurfaceStats
import com.gentlemanprogramming.gentlecompanion.ui.terminal.TERMINAL_FONT_RATIO
import com.gentlemanprogramming.gentlecompanion.ui.terminal.TerminalSurface
import com.gentlemanprogramming.gentlecompanion.ui.terminal.surfaceStats
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
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GentleCompanionTheme {
                val keyPath = File(getExternalFilesDir(null), "client_ed25519").absolutePath
                val knownHostsPath = File(filesDir, "known_hosts").absolutePath
                ConnectScreen(keyPath = keyPath, knownHostsPath = knownHostsPath)
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

    var host by remember { mutableStateOf("10.0.2.2") }
    var port by remember { mutableStateOf("2222") }
    var user by remember { mutableStateOf("erickfm") }
    var session by remember { mutableStateOf("default") }
    /** User-chosen terminal font size in px, `0f` = fit the grid to the width. */
    var terminalFontPx by remember { mutableStateOf(0f) }

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
                            "No client key at $keyPath — push it with adb push first.",
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
            // The session screen has its own compact header; the big app
            // title would push the composer off the bottom of the screen.
            Text(
                text = "Gentle Companion",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 22.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
        }

        when (val current = screen.ui) {
            UiState.Form, UiState.Connecting, is UiState.Failed -> {
                Text(
                    text = "Connect over SSH",
                    color = Tokens.muted,
                    fontSize = 11.sp,
                    letterSpacing = 0.8.sp,
                )
                Spacer(Modifier.height(8.dp))
                FormField(label = "Host", value = host, onValueChange = { host = it })
                Spacer(Modifier.height(8.dp))
                FormField(
                    label = "Port",
                    value = port,
                    onValueChange = { port = it },
                    keyboardType = KeyboardType.Number,
                )
                Spacer(Modifier.height(8.dp))
                FormField(label = "User", value = user, onValueChange = { user = it })
                Spacer(Modifier.height(8.dp))
                FormField(label = "Session", value = session, onValueChange = { session = it })
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { connect(acceptChallenge = null) },
                    enabled = current !is UiState.Connecting,
                    modifier = Modifier
                        .fillMaxWidth()
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
                        text = if (current is UiState.Connecting) "Connecting…" else "Connect",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (current is UiState.Connecting) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Probing the host key, then opening the bridge…",
                        color = Tokens.muted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (current is UiState.Failed) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = current.message,
                        color = Tokens.error,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
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
            // Fit the grid to the width at the bundled font's advance ratio
            // (JetBrains Mono: 0.6em per column), then let a pinch shrink it.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val fitPx = with(density) {
                    (maxWidth / (surface.width.toInt().toFloat() * TERMINAL_FONT_RATIO)).toPx()
                }
                val effectivePx = if (terminalFontPx > 0f) minOf(terminalFontPx, fitPx) else fitPx
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
                            .pointerInput(Unit) {
                                detectTransformGestures { _, _, zoom, _ ->
                                    if (zoom != 1f) {
                                        val current = if (terminalFontPx > 0f) terminalFontPx else effectivePx
                                        onFontPxChange(current * zoom)
                                    }
                                }
                            },
                    ) {
                        TerminalSurface(
                            surface = surface,
                            fontSizePx = effectivePx,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
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
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Unspecified,
) {
    Column {
        Text(text = label, color = Tokens.muted, fontSize = 11.sp, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(8.dp),
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Tokens.accent,
                unfocusedBorderColor = Tokens.line,
                focusedContainerColor = Tokens.surface,
                unfocusedContainerColor = Tokens.surface,
                cursorColor = Tokens.accent,
            ),
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
    GentleCompanionTheme {
        ConnectScreen(
            keyPath = "/data/local/tmp/client_ed25519",
            knownHostsPath = "/data/known_hosts",
        )
    }
}
