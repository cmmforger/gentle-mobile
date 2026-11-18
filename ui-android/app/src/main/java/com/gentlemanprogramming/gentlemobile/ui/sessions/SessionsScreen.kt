package com.gentlemanprogramming.gentlemobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gentlemanprogramming.gentlemobile.core.HostProfileDto
import com.gentlemanprogramming.gentlemobile.core.LeaseTakeOutcomeDto
import com.gentlemanprogramming.gentlemobile.core.LeaseViewDto
import com.gentlemanprogramming.gentlemobile.core.SessionDto
import com.gentlemanprogramming.gentlemobile.core.acquireLease
import com.gentlemanprogramming.gentlemobile.core.listSessions
import com.gentlemanprogramming.gentlemobile.core.releaseLease
import com.gentlemanprogramming.gentlemobile.executor
import com.gentlemanprogramming.gentlemobile.mainHandler
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette
import java.util.UUID

/**
 * The Sessions screen (T17a, S15): the host's Gentle Shell sessions listed
 * over the SFTP lane — one surface, hairline rows, state as a word, cwd and
 * a relative last-used time as the mono meta — plus the advisory lease the
 * core owns (S18: Pi provides no lock, so the guard is ours).
 *
 * Honesty rules on this screen:
 * * a live lease held elsewhere shows `in use elsewhere` and the row never
 *   offers to open; the take action is refused typed by the core;
 * * a stale lease is reclaimed only through the visible Take action, and
 *   the note says so — never silently;
 * * the Resume action runs only when this device holds the lease (S18: the
 *   lease is taken before any RPC child exists); the core repeats the check
 *   and refuses typed if the world changed underneath the UI.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
/** The advisory lease this device currently holds (S18). MainActivity owns
 *  the state so release/heartbeat/teardown work from anywhere. */
data class HeldLease(val sessionId: String, val token: String)

@Composable
fun SessionsScreen(
    profile: HostProfileDto,
    deviceLeaseId: String,
    sessionsDir: String,
    heldLease: HeldLease?,
    onLeaseTaken: (sessionId: String, token: String) -> Unit,
    onLeaseReleased: () -> Unit,
    onResume: (session: SessionDto, sessionsDirOverride: String) -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    var sessions by remember { mutableStateOf<List<SessionDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // S24-3: the listing can be genuinely slow (the host's session files are
    // read one by one over SSH), so the state carries a ticking clock: after
    // two seconds it says it is still working, never frozen.
    var listingStartedAtMs by remember { mutableStateOf(0L) }
    var nowMs by remember { mutableStateOf(0L) }
    var sheetFor by remember { mutableStateOf<SessionDto?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var noteIsError by remember { mutableStateOf(false) }
    // Sessions whose lease this device claimed by replacing a stale one:
    // the row keeps the visible `stale lease reclaimed` word.
    var reclaimedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var reloadNonce by remember { mutableIntStateOf(0) }

    fun describe(error: Exception): String = error.message ?: error.toString()

    fun load() {
        executor.execute {
            try {
                val listed = listSessions(profile, sessionsDir, deviceLeaseId)
                mainHandler.post {
                    sessions = SessionsModel.ordered(listed)
                    error = null
                }
            } catch (e: Exception) {
                mainHandler.post { error = describe(e) }
            }
        }
    }
    LaunchedEffect(reloadNonce) {
        listingStartedAtMs = System.currentTimeMillis()
        load()
    }
    // While the list is loading, tick once a second so the honest elapsed
    // time is visible — a slow host never looks frozen (S24-3).
    val listing = sessions == null && error == null
    if (listing) {
        LaunchedEffect(reloadNonce) {
            while (true) {
                nowMs = System.currentTimeMillis()
                kotlinx.coroutines.delay(1_000)
            }
        }
    }

    fun showNote(text: String, isError: Boolean) {
        note = text
        noteIsError = isError
    }

    fun takeLease(session: SessionDto) {
        val token = UUID.randomUUID().toString()
        executor.execute {
            try {
                val outcome = acquireLease(profile, session.sessionId, deviceLeaseId, token)
                mainHandler.post {
                    when (outcome) {
                        LeaseTakeOutcomeDto.TAKEN -> {
                            onLeaseTaken(session.sessionId, token)
                            showNote("Lease taken — this device now holds it.", isError = false)
                        }
                        LeaseTakeOutcomeDto.RECLAIMED -> {
                            onLeaseTaken(session.sessionId, token)
                            reclaimedIds = reclaimedIds + session.sessionId
                            showNote(SessionsModel.RECLAIM_NOTE, isError = false)
                        }
                    }
                    reloadNonce += 1
                }
            } catch (e: Exception) {
                // The core refuses a live lease held elsewhere, typed.
                mainHandler.post {
                    showNote(SessionsModel.LEASE_LIVE_ELSEWHERE_NOTE, isError = true)
                    reloadNonce += 1
                }
            }
        }
    }

    fun releaseHeld() {
        val held = heldLease ?: return
        executor.execute {
            try {
                releaseLease(profile, held.sessionId, deviceLeaseId, held.token)
            } catch (_: Exception) {
            }
            mainHandler.post {
                onLeaseReleased()
                showNote("Lease released — the session is open again.", isError = false)
                reloadNonce += 1
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "‹ Nodes",
                color = p.accent,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable {
                        // Closing the screen releases the lease we hold (S18).
                        onBack()
                    }
                    .padding(vertical = 12.dp),
            )
            Text(
                text = "lease-guarded",
                color = p.muted,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Session history",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        GroupLabel("${profile.user}@${profile.host} · gentle shell")
        Text(
            text = sessionsDir,
            color = p.muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))

        when {
            sessions == null && error == null -> Text(
                text = SessionsModel.listingStatus(nowMs - listingStartedAtMs),
                color = p.muted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            error != null -> Text(
                text = "The sessions could not be listed: ${describeOr(error!!)}",
                color = p.error,
                fontSize = 13.sp,
            )
            sessions!!.isEmpty() -> Text(
                text = "No sessions in this directory.",
                color = p.muted,
                fontSize = 13.sp,
            )
            else -> sessions!!.forEach { session ->
                val isHeldHere = heldLease?.sessionId == session.sessionId
                val view = if (isHeldHere) LeaseViewDto.HELD_HERE else session.lease
                val reclaimed = session.sessionId in reclaimedIds && isHeldHere
                SessionRow(
                    session = session,
                    state = SessionsModel.stateWord(view, reclaimed),
                    isBlocked = view == LeaseViewDto.IN_USE_ELSEWHERE,
                    onClick = { sheetFor = session },
                )
            }
        }

        note?.let { text ->
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
                Spacer(
                    Modifier
                        .width(2.dp)
                        .heightIn(min = 40.dp)
                        .background(if (noteIsError) p.error else p.accentEdge),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = text,
                    color = if (noteIsError) p.error else p.muted,
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    sheetFor?.let { session ->
        SessionSheet(
            session = session,
            isHeldHere = heldLease?.sessionId == session.sessionId,
            onTake = { takeLease(session) },
            onRelease = { releaseHeld() },
            onResume = { sheetFor = null; onResume(session, sessionsDir) },
            onDismiss = { sheetFor = null },
        )
    }
}

@Composable
private fun SessionRow(
    session: SessionDto,
    state: String,
    isBlocked: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    text = session.cwd?.substringAfterLast('/')?.ifBlank { "session" } ?: "session",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state,
                    color = if (isBlocked) p.error else p.muted,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp,
                )
            }
            Text(
                text = SessionsModel.rowMeta(
                    session,
                    SessionsModel.relativeTime(session.lastModifiedEpochMs.toLong(), System.currentTimeMillis()),
                ),
                color = p.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(text = "›", color = p.muted, fontSize = 15.sp)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SessionSheet(
    session: SessionDto,
    isHeldHere: Boolean,
    onTake: () -> Unit,
    onRelease: () -> Unit,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val view = if (isHeldHere) LeaseViewDto.HELD_HERE else session.lease
    val leaseReason = SessionsModel.resumeReason(view, isHeldHere)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = session.cwd?.substringAfterLast('/')?.ifBlank { "session" } ?: "session",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            SheetField("Session id", session.sessionId)
            SheetField("File", session.fileName)
            SheetField("Working directory", session.cwd ?: "not recorded")
            SheetField(
                "Last write",
                SessionsModel.relativeTime(session.lastModifiedEpochMs.toLong(), System.currentTimeMillis()),
            )
            SheetField(
                "Messages",
                session.messageCount?.let { it.toString() } ?: "too large to count cheaply",
            )
            SheetField(
                "Lease",
                when (view) {
                    LeaseViewDto.OPEN -> "open — no device holds a claim"
                    LeaseViewDto.HELD_HERE -> "held by this device (heartbeat every 30 s)"
                    LeaseViewDto.IN_USE_ELSEWHERE -> "live claim by another device"
                    LeaseViewDto.STALE -> "stale — heartbeat older than 90 s, reclaimable"
                },
            )
            Spacer(Modifier.height(16.dp))
            // The resume action (T17b, S15/S18): enabled only while this
            // device holds the lease. The core re-checks the lease before
            // spawning, so a stale screen can never open a held session.
            Button(
                onClick = onResume,
                enabled = isHeldHere,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = p.accent,
                    contentColor = p.bg,
                    disabledContainerColor = p.surfaceRaised,
                    disabledContentColor = p.textDisabled,
                ),
            ) {
                Text(text = "Resume session", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = leaseReason ?: "Resuming opens the session over the RPC lane — this device holds the lease.",
                color = p.muted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = if (isHeldHere) onRelease else onTake,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (isHeldHere) "Release lease" else "Take lease",
                    color = p.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (!isHeldHere && view == LeaseViewDto.STALE) {
                Text(
                    text = SessionsModel.RECLAIM_NOTE,
                    color = p.muted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun SheetField(label: String, value: String) {    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label.uppercase(),
            color = p.muted,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** The group header: the word and the count, uppercase, quiet — the same
 *  rule as board 03 (the screen has its own: the shared one is private to
 *  MainActivity.kt). */
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

/** Errors already carry their message from the core's typed Display. */
private fun describeOr(error: String): String = error
