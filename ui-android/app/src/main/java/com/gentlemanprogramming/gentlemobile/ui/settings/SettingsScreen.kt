package com.gentlemanprogramming.gentlemobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.gentlemanprogramming.gentlemobile.core.KnownHostDto
import com.gentlemanprogramming.gentlemobile.notifications.notificationsRowDetail
import com.gentlemanprogramming.gentlemobile.notifications.notificationsRowWord
import com.gentlemanprogramming.gentlemobile.identity.DeviceIdentity
import com.gentlemanprogramming.gentlemobile.identity.OpenSshKey
import com.gentlemanprogramming.gentlemobile.security.BiometricDecision
import com.gentlemanprogramming.gentlemobile.ui.sessions.SessionsConfig
import com.gentlemanprogramming.gentlemobile.ui.theme.AppTheme
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette
import com.gentlemanprogramming.gentlemobile.ui.theme.SharedPreferencesStore
import java.security.MessageDigest

/**
 * The Settings screen (S21, board `07-settings.html`): one surface with
 * hairline-separated rows — never a card per row — one accent and one error
 * colour (Revoke). Everything here is real (T18): the key regenerates with a
 * confirmation, known hosts list and forget per host, the biometric switch
 * opens a real prompt and only explains itself when the device cannot
 * enforce it, and revoke wipes this device after its own confirmation. An
 * element the device cannot support is disabled with its exact reason —
 * never a dead control that pretends to work.
 */
@Composable
fun SettingsScreen(
    identity: DeviceIdentity.Identity?,
    identityError: String?,
    knownHosts: List<KnownHostDto>,
    biometricDecision: BiometricDecision?,
    biometricEnabled: Boolean,
    theme: AppTheme,
    connected: Boolean,
    regenerated: Boolean,
    /** T15 (S11): the notification permission as the device holds it —
     *  `null` below Android 13, where there is nothing to deny. */
    notificationsGranted: Boolean?,
    onCopyKey: () -> Unit,
    onOpenThemes: () -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onRegenerateKey: () -> Unit,
    onForgetHost: (KnownHostDto) -> Unit,
    onRevoke: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    var confirmRegenerate by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
    var hostsOpen by remember { mutableStateOf(false) }
    // S24-2: the four sections are collapsible, one open at a time — the
    // screen opens as a compact index of four rows, not one long wall, and
    // no section can push the others past a screen.
    var openSection by remember { mutableStateOf<Section?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        // Compact top area (S24-2): one title next to the back chevron — the
        // duplicated small topbar word is gone.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", color = p.text, fontSize = 18.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Settings",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 22.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))

        SectionBody(
            section = Section.Security,
            open = openSection == Section.Security,
            onToggle = { openSection = if (openSection == Section.Security) null else Section.Security },
        ) {

        // Device key: the real identity, its type, a short fingerprint and
        // where it lives. Regenerate is real (T18): a confirmation first —
        // it destroys the old key — then a fresh identity and cleared pins.
        SettingRow(
            name = "Device key",
            sub = when {
                identityError != null -> "The identity could not be read."
                identity != null -> "ed25519 · ${keyFingerprint(identity.publicKeyLine) ?: "fingerprint unavailable"} · app-private storage"
                else -> "Reading the key…"
            },
            isMono = true,
            trailing = {
                MiniButton(
                    label = "Regenerate",
                    enabled = identity != null,
                    onClick = { confirmRegenerate = true },
                )
            },
        )
        if (regenerated) {
            // The plain promise after a regeneration: new key, new trust.
            // The copy action hands over the exact line the host needs.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "New key generated — the pinned hosts were cleared. " +
                        "Each host must trust this key again before it connects.",
                    color = p.text,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                MiniButton(label = "Copy key", enabled = identity != null, onClick = onCopyKey)
            }
        }

        // Known hosts: what the core actually pinned, read from its
        // known_hosts store, with the fingerprint each connect challenge
        // shows. A forgotten host challenges its fingerprint again.
        SettingRow(
            name = "Known hosts",
            sub = if (knownHosts.isEmpty()) {
                "No hosts pinned yet — connect once and the host key pins itself."
            } else {
                if (hostsOpen) "${knownHosts.size} pinned — tap to hide" else
                    "${knownHosts.joinToString(" · ") { it.spec }} — ${knownHosts.size} pinned"
            },
            isMono = true,
            onClick = if (knownHosts.isEmpty()) null else ({ hostsOpen = !hostsOpen }),
            trailing = if (knownHosts.isEmpty()) null else {
                {
                    MiniButton(label = if (hostsOpen) "Hide" else "Manage", enabled = true) {
                        hostsOpen = !hostsOpen
                    }
                }
            },
        )
        if (hostsOpen) {
            knownHosts.forEach { host ->
                SettingRow(
                    name = host.spec,
                    sub = host.fingerprint,
                    isMono = true,
                    trailing = {
                        MiniButton(label = "Forget", enabled = true) { onForgetHost(host) }
                    },
                )
            }
            UnavailableNote(
                "Forgetting a host clears only its pin — the next connection asks you to confirm the host's fingerprint again.",
            )
        }

        // Biometric unlock: a real switch (T18). On, it stands between the
        // user and every session open and every connect — a BiometricPrompt
        // with the device credential as fallback. A device that cannot
        // enforce it keeps the switch off with the exact reason.
        SettingRow(
            name = "Biometric unlock",
            sub = "fingerprint before opening any session",
            trailing = {
                GateSwitch(
                    checked = biometricEnabled,
                    enabled = biometricDecision == BiometricDecision.AVAILABLE,
                    onToggle = onToggleBiometric,
                )
            },
        )
        when (biometricDecision) {
            BiometricDecision.NOT_AVAILABLE -> UnavailableNote(
                "This device has no usable biometric hardware — the switch stays off.",
            )
            BiometricDecision.NOT_ENROLLED -> UnavailableNote(
                "No fingerprint or device credential is enrolled — enrol one in Android settings first.",
            )
            null -> UnavailableNote("Checking this device's biometric capability…")
            else -> {}
        }

        // Host-key pinning is real and locked on: the core refuses a host
        // whose key changed, with no way to turn it off.
        SettingRow(
            name = "Host-key pinning",
            sub = "Refuses a host whose SSH key changed.",
            trailing = { DisabledSwitch(checked = true, locked = true) },
        )
        }

        SectionBody(
            section = Section.Connection,
            open = openSection == Section.Connection,
            onToggle = { openSection = if (openSection == Section.Connection) null else Section.Connection },
        ) {

        // Where the Sessions screen lists from (T17a): the host's own
        // default unless a debug/test override was entered. A connection
        // setting: it points at the host, not at a security decision.
        val context = LocalContext.current
        val sessionsStore = remember { SharedPreferencesStore(context) }
        var editingSessionsDir by remember { mutableStateOf(false) }
        var sessionsDirDraft by remember { mutableStateOf("") }
        var sessionsDirOverride by remember {
            mutableStateOf(sessionsStore.getString(SessionsConfig.OVERRIDE_KEY))
        }
        SettingRow(
            name = "Sessions directory",
            sub = sessionsDirOverride?.trim()?.takeIf { it.isNotEmpty() }
                ?: "default · ~/.gentle-shell/agent/sessions",
            isMono = true,
            trailing = {
                MiniButton(label = "Edit", enabled = true) {
                    sessionsDirDraft = sessionsDirOverride?.trim().orEmpty()
                    editingSessionsDir = true
                }
            },
        )
        if (editingSessionsDir) {
            AlertDialog(
                onDismissRequest = { editingSessionsDir = false },
                title = { Text("Sessions directory") },
                text = {
                    OutlinedTextField(
                        value = sessionsDirDraft,
                        onValueChange = { sessionsDirDraft = it },
                        placeholder = { Text("blank = the host default") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        sessionsStore.putString(SessionsConfig.OVERRIDE_KEY, sessionsDirDraft)
                        sessionsDirOverride = sessionsDirDraft
                        editingSessionsDir = false
                    }) { Text("Save", color = p.accent) }
                },
                dismissButton = {
                    TextButton(onClick = { editingSessionsDir = false }) {
                        Text("Cancel", color = p.muted)
                    }
                },
                containerColor = p.surfaceRaised,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                textContentColor = p.muted,
            )
        }

        // The lane selector. Herdr TUI is the only implemented lane; Gentle
        // Shell direct sessions are not in the core yet (S3), and the radio
        // says so instead of selecting into a dead end.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .heightIn(min = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LaneOption(
                title = "Herdr TUI",
                subtitle = "Attach to Herdr",
                selected = true,
                enabled = true,
                modifier = Modifier.weight(1f),
            )
            LaneOption(
                title = "Gentle Shell",
                subtitle = "Start a shell session",
                selected = false,
                enabled = false,
                modifier = Modifier.weight(1f),
            )
        }
        UnavailableNote("Gentle Shell direct sessions are not in the core yet — Herdr TUI is the only lane.")

        // T15 (S11): the permission, stated honestly. A denial is never a
        // silent failure — the row says notifications are off and why, and
        // the way back is one tap into the system's own settings.
        SettingRow(
            name = "Agent notifications",
            sub = notificationsRowDetail(notificationsGranted),
            trailing = { Text(notificationsRowWord(notificationsGranted), color = p.muted, fontSize = 13.sp) },
        )
        if (notificationsGranted == false) {
            SettingRow(
                name = "Enable notifications",
                sub = "Opens Android's notification settings for Gentle Mobile.",
                onClick = onOpenNotificationSettings,
                trailing = { Text("›", color = p.muted, fontSize = 15.sp) },
            )
        }

        }

        SectionBody(
            section = Section.Appearance,
            open = openSection == Section.Appearance,
            onToggle = { openSection = if (openSection == Section.Appearance) null else Section.Appearance },
        ) {
        SettingRow(
            name = "Theme · ${theme.label}",
            sub = if (theme == AppTheme.CUTE) {
                "default · tap to pick Sexy or Gentle"
            } else {
                "tap to pick Cute, Sexy or Gentle"
            },
            onClick = onOpenThemes,
            trailing = { Text("›", color = p.muted, fontSize = 15.sp) },
        )
        }

        SectionBody(
            section = Section.About,
            open = openSection == Section.About,
            onToggle = { openSection = if (openSection == Section.About) null else Section.About },
        ) {
        val version = appVersion()
        SettingRow(
            name = "Gentle Mobile $version",
            sub = "protocol 22 · Apache-2.0",
            isMono = true,
            trailing = { MiniButton(label = "What's new", enabled = false) },
        )
        UnavailableNote("No release notes yet — this is $version.")
        }
        Spacer(Modifier.height(24.dp))

        // Actions. Disconnect works only against a live connection; revoke
        // wipes this device after its own confirmation — the host side keeps
        // trusting the old key until `gentle-mobile-pair --revoke` runs
        // there, which the app cannot do: it holds only this phone's key,
        // never a shell on the host.
        OutlinedButton(
            onClick = onDisconnect,
            enabled = connected,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = p.text,
                disabledContentColor = p.textDisabled,
            ),
        ) {
            Text(text = "Disconnect", fontWeight = FontWeight.SemiBold)
        }
        if (!connected) {
            UnavailableNote("Not connected — the disconnect action belongs to a live session.")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { confirmRevoke = true },
            enabled = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = p.error,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = p.error,
            ),
        ) {
            Text(text = "Revoke on this device", fontWeight = FontWeight.SemiBold)
        }
        UnavailableNote(
            "Wipes this phone's key and its pinned hosts. The host side still needs " +
                "gentle-mobile-pair --revoke on each host — the app cannot run it: " +
                "it holds only this phone's key, never a shell on the host.",
        )
        Spacer(Modifier.height(24.dp))
    }

    if (confirmRegenerate) {
        ConfirmDialog(
            title = "Regenerate the device key?",
            body = "This destroys the old key and clears every pinned host — a new key means new trust. " +
                "Each host must trust the new key before it can connect.",
            confirmLabel = "Regenerate",
            onConfirm = {
                confirmRegenerate = false
                onRegenerateKey()
            },
            onDismiss = { confirmRegenerate = false },
        )
    }
    if (confirmRevoke) {
        ConfirmDialog(
            title = "Revoke on this device?",
            body = "This wipes this phone's key and every pinned host. The host still trusts the old key " +
                "until you run gentle-mobile-pair --revoke on it — the app cannot run that for you: " +
                "it holds only this phone's key, never a shell on the host.",
            confirmLabel = "Revoke",
            destructive = true,
            onConfirm = {
                confirmRevoke = false
                onRevoke()
            },
            onDismiss = { confirmRevoke = false },
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = { Text(body, fontSize = 13.sp, lineHeight = 18.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) p.error else p.accentEdge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = p.muted) }
        },
        containerColor = p.surfaceRaised,
        titleContentColor = MaterialTheme.colorScheme.onBackground,
        textContentColor = p.muted,
    )
}

/// The four Settings sections (S24-2): the screen opens as a compact index
/// of these four rows, each collapsible, one open at a time, so no section
/// can push the rest past one screen. `summary` is the collapsed one-liner.
private enum class Section(val label: String, val summary: String) {
    Security("Security", "device key · known hosts · biometrics · pinning"),
    Connection("Connection", "lane · sessions directory · notifications"),
    Appearance("Appearance", "theme"),
    About("About", "version · what's new"),
}

/** One collapsible section (S24-2): a full-width 44dp header row — label,
 *  one-line summary and a chevron that turns when open — then the rows only
 *  while it is open. Exactly one section is open at a time (the caller owns
 *  `openSection`). */
@Composable
private fun SectionBody(
    section: Section,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = section.label.uppercase(),
                color = p.muted,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = section.summary,
                color = p.textDisabled,
                fontSize = 11.sp,
            )
        }
        Text(
            text = "›",
            color = p.muted,
            fontSize = 15.sp,
            modifier = Modifier.graphicsLayer { rotationZ = if (open) 90f else 0f },
        )
    }
    HairlineRow()
    if (open) {
        content()
    }
}

/** One hairline row: name + sub on the left, trailing control on the right. */
@Composable
private fun SettingRow(
    name: String,
    sub: String,
    isMono: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = sub,
                color = p.muted,
                fontSize = if (isMono) 11.sp else 13.sp,
                fontFamily = if (isMono) FontFamily.Monospace else FontFamily.Default,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
    HairlineRow()
}

/** The 1px separator the refinement rules require between rows. */
@Composable
private fun HairlineRow() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalPalette.current.line),
    )
}

/** A quiet one-line reason under an unavailable element. */
@Composable
private fun UnavailableNote(text: String) {
    Text(
        text = text,
        color = LocalPalette.current.textDisabled,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

/** A mini action like the board's `Regenerate` / `Manage` / `Forget`. */
@Composable
private fun MiniButton(label: String, enabled: Boolean, onClick: (() -> Unit)? = null) {
    val p = LocalPalette.current
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .padding(horizontal = 8.dp)
            .then(if (enabled && onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) p.text else p.textDisabled,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * The board's locked switch (board 07): checked-on renders ON — the thumb in
 * textSecondary parked right on a surfaceRaised track with the board's 1px
 * border — so mandatory pinning cannot read as an OFF switch (the T16 fix).
 */
@Composable
private fun DisabledSwitch(checked: Boolean, locked: Boolean = false) {
    val p = LocalPalette.current
    Box(
        modifier = Modifier.size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(26.dp)
                .border(
                    1.dp,
                    if (checked && locked) p.line else Color.Transparent,
                    RoundedCornerShape(999.dp),
                )
                .background(
                    if (checked && locked) p.surfaceRaised else p.surface,
                    RoundedCornerShape(999.dp),
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(start = if (checked) 21.dp else 3.dp)
                    .size(18.dp)
                    .background(p.muted, CircleShape),
            )
        }
    }
}

/**
 * The live switch (board 07 `.switch[aria-checked]`): on, the track is the
 * accent with the background-coloured thumb parked right — the board's one
 * accent use on this screen. Off, the quiet track. A switch that cannot be
 * enabled renders disabled and explains itself in the note under its row.
 */
@Composable
private fun GateSwitch(checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier = Modifier.size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(26.dp)
                .then(if (enabled) Modifier.clickable { onToggle(!checked) } else Modifier)
                .border(
                    1.dp,
                    when {
                        checked && enabled -> p.accent
                        !enabled -> Color.Transparent
                        else -> p.line
                    },
                    RoundedCornerShape(999.dp),
                )
                .background(
                    when {
                        checked && enabled -> p.accent
                        else -> p.surface
                    },
                    RoundedCornerShape(999.dp),
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(start = if (checked) 21.dp else 3.dp)
                    .size(18.dp)
                    .background(
                        when {
                            checked && enabled -> p.bg
                            else -> p.muted
                        },
                        CircleShape,
                    ),
            )
        }
    }
}

/** One lane of the connection selector; disabled lanes are honest, not dead. */
@Composable
private fun LaneOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Column(
        modifier = modifier
            .heightIn(min = 44.dp)
            .padding(vertical = 4.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = when {
                selected -> p.accentEdge
                !enabled -> p.textDisabled
                else -> p.text
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            color = if (enabled) p.muted else p.textDisabled,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * A short OpenSSH-style fingerprint of the device key: the first two and the
 * last byte of the SHA-256 over the raw public key, in the board's `ab:cd:…:ef`
 * shape. Never the whole key — the full public line stays on the Pair screen.
 */
internal fun keyFingerprint(publicKeyLine: String): String? {
    val blob = OpenSshKey.publicBlobFromLine(publicKeyLine) ?: return null
    val pub = OpenSshKey.ed25519PublicFromBlob(blob) ?: return null
    val digest = MessageDigest.getInstance("SHA-256").digest(pub)
    return "%02x:%02x:…:%02x".format(digest[0], digest[1], digest[31])
}

/** The real versionName from the installed package — never a hardcoded one. */
@Composable
private fun appVersion(): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}
