package com.gentlemanprogramming.gentlemobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.core.HostProfileDto
import com.gentlemanprogramming.gentlemobile.ui.theme.Air
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
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
 * * cached listings (S24) are served only while younger than the cache
 *   TTL — anything older shows the normal loading state; the explicit
 *   Refresh action and lease mutations always fetch live;
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
    onStartNew: (sessions: List<SessionDto>) -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var sessions by remember { mutableStateOf<List<SessionDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // S24: the in-memory index cache makes re-entering this screen instant.
    // It is never shown as live: older than the TTL it is not served at all
    // (the loading state below runs instead), the explicit Refresh action
    // and every lease mutation bypass it.
    val cacheKey = SessionsCache.key(profile, sessionsDir)
    var bypassCache by remember { mutableStateOf(false) }
    // S24-3: the listing can be genuinely slow (the host's session files are
    // read over SSH in a bounded concurrent window), so the state carries a
    // ticking clock: after two seconds it says it is still working, never
    // frozen.
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
                    SessionsCache.put(cacheKey, listed, System.currentTimeMillis())
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
        // A fresh cache entry is the instant path back into Session history.
        // Anything else — no entry, past the TTL, or an explicit bypass —
        // fetches live through the normal loading state.
        val cached = if (bypassCache) {
            null
        } else {
            SessionsCache.fresh(cacheKey, System.currentTimeMillis())
        }
        bypassCache = false
        if (cached != null) {
            sessions = SessionsModel.ordered(cached)
            error = null
        } else {
            load()
        }
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
                            showNote(context.getString(R.string.hist_note_taken), isError = false)
                        }
                        LeaseTakeOutcomeDto.RECLAIMED -> {
                            onLeaseTaken(session.sessionId, token)
                            reclaimedIds = reclaimedIds + session.sessionId
                            showNote(context.getString(R.string.hist_note_reclaimed), isError = false)
                        }
                    }
                    // A lease take changed state on the host: the reload is
                    // live, never served from the cache (S24).
                    bypassCache = true
                    reloadNonce += 1
                }
            } catch (e: Exception) {
                // The core refuses a live lease held elsewhere, typed.
                mainHandler.post {
                    showNote(context.getString(R.string.hist_note_elsewhere_live), isError = true)
                    bypassCache = true
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
                showNote(context.getString(R.string.hist_note_released), isError = false)
                // A release changed state on the host: live reload (S24).
                bypassCache = true
                reloadNonce += 1
            }
        }
    }

    Column(
        modifier = Modifier
            // T36: the screen is the route's root (the early return inside
            // ConnectScreen never reaches the connect column), so the
            // background is the theme's own and it fills the phone — not
            // just the content's height (the dark window below the rows).
            .fillMaxSize()
            .background(p.bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Air.SIDE_DP.dp)
            .padding(top = Air.TOP_DP.dp),
    ) {
        // T36: the same back-button header every screen has — an accent
        // chevron in a 44 dp touch target, the title below. The old text
        // row named the screen it came from and carried the noise line
        // beside it; both are gone.
        Row(verticalAlignment = Alignment.CenterVertically) {
            val backCd = stringResource(R.string.cd_back)
            Box(
                modifier = Modifier
                    .size(Air.TOUCH_DP.dp)
                    .clickable {
                        // Closing the screen releases the lease we hold (S18).
                        onBack()
                    }
                    .semantics { contentDescription = backCd },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_back_chevron),
                    contentDescription = null,
                    tint = p.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.hist_title),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        GroupLabel(stringResource(R.string.hist_subtitle, profile.user, profile.host))
        Text(
            text = sessionsDir,
            color = p.muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        // S24-5: start a session in a repository the history already knows —
        // the start screen owns the spawn and the fresh-session lease.
        Text(
            text = stringResource(R.string.start_new),
            color = p.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clickable { onStartNew(sessions ?: emptyList()) }
                .padding(vertical = 8.dp),
        )
        // S24: the explicit refresh — always a live fetch over SSH, never
        // the cache, so the user can always ask the host for the truth now.
        Text(
            text = stringResource(R.string.session_refresh),
            color = p.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clickable {
                    sessions = null
                    error = null
                    bypassCache = true
                    reloadNonce += 1
                }
                .padding(vertical = 8.dp),
        )

        when {
            sessions == null && error == null -> Text(
                text = listingStatus(nowMs - listingStartedAtMs),
                color = p.muted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            error != null -> Text(
                text = stringResource(R.string.hist_list_error, describeOr(error!!)),
                color = p.error,
                fontSize = 13.sp,
            )
            sessions!!.isEmpty() -> Text(
                text = stringResource(R.string.hist_empty),
                color = p.muted,
                fontSize = 13.sp,
            )
            else -> sessions!!.forEach { session ->
                val isHeldHere = heldLease?.sessionId == session.sessionId
                val reclaimed = session.sessionId in reclaimedIds && isHeldHere
                val row = SessionsModel.rowState(session, System.currentTimeMillis(), reclaimed)
                SessionRow(
                    session = session,
                    state = stateWord(row),
                    isBlocked = row == SessionsModel.RowState.LEASED && !isHeldHere,
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
        val isHeldHere = heldLease?.sessionId == session.sessionId
        val row = SessionsModel.rowState(session, System.currentTimeMillis(), reclaimed = session.sessionId in reclaimedIds && isHeldHere)
        SessionSheet(
            session = session,
            row = row,
            isHeldHere = isHeldHere,
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
                    text = session.cwd?.substringAfterLast('/')?.ifBlank { stringResource(R.string.hist_fallback_name) }
                        ?: stringResource(R.string.hist_fallback_name),
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
                text = rowMeta(
                    session,
                    relativeTime(session.lastModifiedEpochMs.toLong(), System.currentTimeMillis()),
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
    row: SessionsModel.RowState,
    isHeldHere: Boolean,
    onTake: () -> Unit,
    onRelease: () -> Unit,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
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
                text = session.cwd?.substringAfterLast('/')?.ifBlank { stringResource(R.string.hist_fallback_name) }
                        ?: stringResource(R.string.hist_fallback_name),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            SheetField(stringResource(R.string.hist_sheet_session_id), session.sessionId)
            SheetField(stringResource(R.string.hist_sheet_file), session.fileName)
            SheetField(
                stringResource(R.string.hist_sheet_dir),
                session.cwd ?: stringResource(R.string.hist_sheet_dir_missing),
            )
            SheetField(
                stringResource(R.string.hist_sheet_last_write),
                relativeTime(session.lastModifiedEpochMs.toLong(), System.currentTimeMillis()),
            )
            SheetField(
                stringResource(R.string.hist_sheet_messages),
                session.messageCount?.let {
                    pluralStringResource(R.plurals.messages_count, it.toInt(), it.toInt())
                } ?: stringResource(R.string.hist_sheet_messages_too_many),
            )
            SheetField(
                stringResource(R.string.hist_sheet_state),
                when (row) {
                    SessionsModel.RowState.CLOSED ->
                        if (session.lease == LeaseViewDto.STALE) {
                            stringResource(R.string.hist_state_stale_detail)
                        } else {
                            stringResource(R.string.hist_state_closed_detail)
                        }
                    SessionsModel.RowState.RUNNING -> stringResource(R.string.hist_state_running_detail)
                    SessionsModel.RowState.LEASED ->
                        if (isHeldHere) {
                            stringResource(R.string.hist_state_held_here_detail)
                        } else {
                            stringResource(R.string.hist_state_elsewhere_detail)
                        }
                    SessionsModel.RowState.RECLAIMED -> stringResource(R.string.hist_state_held_here_detail)
                },
            )
            Spacer(Modifier.height(16.dp))
            // One honest primary action per state (T36): a closed session is
            // taken first (the lease is ours before any child exists, S18),
            // a held one resumes, and a running or elsewhere-leased session
            // gets a sentence instead of a button that cannot work.
            when (row) {
                SessionsModel.RowState.LEASED -> {
                    if (isHeldHere) {
                        Button(
                            onClick = onResume,
                            enabled = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = p.accent,
                                contentColor = p.bg,
                            ),
                        ) {
                            Text(text = stringResource(R.string.hist_resume), fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onRelease, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.hist_release),
                                color = p.accent,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.hist_resume_elsewhere),
                            color = p.muted,
                            fontSize = 13.sp,
                        )
                    }
                }
                SessionsModel.RowState.RUNNING -> {
                    Text(
                        text = stringResource(R.string.hist_running_note),
                        color = p.muted,
                        fontSize = 13.sp,
                    )
                }
                SessionsModel.RowState.CLOSED, SessionsModel.RowState.RECLAIMED -> {
                    Button(
                        onClick = onTake,
                        enabled = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = p.accent,
                            contentColor = p.bg,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.hist_take),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.hist_take_note),
                        color = p.muted,
                        fontSize = 12.sp,
                    )
                    if (session.lease == LeaseViewDto.STALE) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.hist_note_reclaimed),
                            color = p.muted,
                            fontSize = 11.sp,
                        )
                    }
                }
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

// --- S24-1: the localized words the pure SessionsModel contract renders ---

/** The loading state of the list, localized: why it can be slow, and past
 *  two seconds that it is still working (the same rule the pure
 *  [SessionsModel.listingStatus] encodes and tests). */
@Composable
private fun listingStatus(elapsedMs: Long): String =
    if (elapsedMs < SessionsModel.SLOW_LISTING_AFTER_MS) {
        stringResource(R.string.hist_listing)
    } else {
        stringResource(R.string.hist_listing_slow, elapsedMs / 1000)
    }

/** The row's state word, localized — the true word for what the app knows
 *  (T36): closed / running / leased, in both languages. */
@Composable
private fun stateWord(row: SessionsModel.RowState): String = when (row) {
    SessionsModel.RowState.CLOSED -> stringResource(R.string.hist_state_closed)
    SessionsModel.RowState.RUNNING -> stringResource(R.string.hist_state_running)
    SessionsModel.RowState.LEASED -> stringResource(R.string.hist_state_leased)
    SessionsModel.RowState.RECLAIMED -> stringResource(R.string.hist_state_reclaimed)
}

/** Relative last-used time, localized (the pure [SessionsModel.relativeTime] rule). */
@Composable
private fun relativeTime(epochMs: Long, nowMs: Long): String {
    val delta = (nowMs - epochMs).coerceAtLeast(0)
    val minutes = delta / 60_000
    val hours = delta / 3_600_000
    val days = delta / 86_400_000
    return when {
        minutes < 1 -> stringResource(R.string.time_just_now)
        hours < 1 -> stringResource(R.string.time_min_ago, minutes)
        days < 1 -> stringResource(R.string.time_h_ago, hours)
        else -> stringResource(R.string.time_d_ago, days)
    }
}

/** The row's mono meta line, localized (the pure [SessionsModel.rowMeta] rule). */
@Composable
private fun rowMeta(session: SessionDto, relative: String): String =
    listOfNotNull(
        relative,
        session.messageCount?.let {
            pluralStringResource(R.plurals.messages_count, it.toInt(), it.toInt())
        },
        session.cwd,
    ).joinToString(" · ")

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
