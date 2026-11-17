package com.gentlemanprogramming.gentlecompanion

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gentlemanprogramming.gentlecompanion.core.acceptHostKey
import com.gentlemanprogramming.gentlecompanion.core.AgentDto
import com.gentlemanprogramming.gentlecompanion.core.AgentStatusDto
import com.gentlemanprogramming.gentlecompanion.core.CompanionConnection
import com.gentlemanprogramming.gentlecompanion.core.CompanionException
import com.gentlemanprogramming.gentlecompanion.core.ConnectOutcome
import com.gentlemanprogramming.gentlecompanion.core.HostProfileDto
import com.gentlemanprogramming.gentlecompanion.core.SnapshotDto
import com.gentlemanprogramming.gentlecompanion.core.connect as coreConnect
import com.gentlemanprogramming.gentlecompanion.ui.theme.GentleCompanionTheme
import com.gentlemanprogramming.gentlecompanion.ui.theme.Tokens
import java.io.File
import java.util.concurrent.Executors

/**
 * The real lane-A read path: one screen, plus the host-key challenge state.
 * No fake rows — everything shown comes from a snapshot of a live host over
 * SSH, through the Rust core. An empty snapshot says so instead of inventing
 * hosts.
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

// One background worker thread: connect, accept, and snapshot polling all run
// off the UI thread; results come back through the main handler.
private val executor = Executors.newSingleThreadExecutor()
private val mainHandler = Handler(Looper.getMainLooper())

private sealed interface UiState {
    data object Form : UiState
    data object Connecting : UiState
    data class Challenge(val fingerprint: String, val pinned: String?, val keyBlob: String) : UiState
    data class Connected(val connection: CompanionConnection, val snapshot: SnapshotDto?) : UiState
    data class Failed(val message: String) : UiState
}

/** Screen state + connect generation, so poll callbacks can stop cleanly. */
private class ScreenState {
    var ui by mutableStateOf<UiState>(UiState.Form)
    var generation: Int = 0
}

@Composable
fun ConnectScreen(keyPath: String, knownHostsPath: String) {
    val screen = remember { ScreenState() }

    var host by remember { mutableStateOf("10.0.2.2") }
    var port by remember { mutableStateOf("2222") }
    var user by remember { mutableStateOf("erickfm") }
    var session by remember { mutableStateOf("default") }

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
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "Gentle Companion",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))

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
                            AgentRow(agent)
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
    else -> error.message ?: error.javaClass.simpleName
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
private fun AgentRow(agent: AgentDto) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
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
