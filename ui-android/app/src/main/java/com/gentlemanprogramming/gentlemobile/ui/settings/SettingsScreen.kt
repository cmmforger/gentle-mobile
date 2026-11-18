package com.gentlemanprogramming.gentlemobile.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gentlemanprogramming.gentlemobile.identity.DeviceIdentity
import com.gentlemanprogramming.gentlemobile.identity.OpenSshKey
import com.gentlemanprogramming.gentlemobile.ui.theme.AppTheme
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette
import java.io.File
import java.security.MessageDigest

/**
 * The Settings screen (S21, board `07-settings.html`): one surface with
 * hairline-separated rows — never a card per row — one accent (the selected
 * lane) and one error colour (Revoke). Elements the core cannot do yet are
 * rendered in their honest unavailable state with a one-line reason, never a
 * dead switch that pretends to work.
 */
@Composable
fun SettingsScreen(
    identity: DeviceIdentity.Identity?,
    identityError: String?,
    knownHostsPath: String,
    theme: AppTheme,
    connected: Boolean,
    onOpenThemes: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        // Topbar: back stays a 44dp target; the title sits between.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", color = p.text, fontSize = 18.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(text = "Settings", color = p.muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Settings",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
        )

        SectionLabel("Security")

        // Device key: the real identity, its type, a short fingerprint and
        // where it lives. Regeneration does not exist in the core yet — the
        // row says so instead of offering a button that lies.
        SettingRow(
            name = "Device key",
            sub = when {
                identityError != null -> "The identity could not be read."
                identity != null -> "ed25519 · ${keyFingerprint(identity.publicKeyLine) ?: "fingerprint unavailable"} · app-private storage"
                else -> "Reading the key…"
            },
            isMono = true,
            trailing = {
                MiniButton(label = "Regenerate", enabled = false)
            },
        )
        UnavailableNote("Key regeneration is not implemented — the identity is created once per device.")

        // Known hosts: what the core actually pinned, read from the file it
        // writes on first connect. Host management UI does not exist yet.
        val hosts = pinnedHosts(knownHostsPath)
        SettingRow(
            name = "Known hosts",
            sub = if (hosts.isEmpty()) {
                "No hosts pinned yet — connect once and the host key pins itself."
            } else {
                "${hosts.joinToString(" · ")} — ${hosts.size} pinned"
            },
            isMono = true,
        )
        UnavailableNote("Host management is not in the app yet — pinning happens on first connect.")

        // Biometric unlock: no fingerprint gate exists in the core, so the
        // switch is off and disabled with its reason attached.
        SettingRow(
            name = "Biometric unlock",
            sub = "fingerprint before opening any session",
            trailing = { DisabledSwitch(checked = false) },
        )
        UnavailableNote("Not implemented yet — the key opens without a fingerprint on this build.")

        // Host-key pinning is real and locked on: the core refuses a host
        // whose key changed, with no way to turn it off.
        SettingRow(
            name = "Host-key pinning",
            sub = "Refuses a host whose SSH key changed.",
            trailing = { DisabledSwitch(checked = true, locked = true) },
        )

        SectionLabel("Connection")

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

        SectionLabel("Appearance")
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

        SectionLabel("About")
        val version = appVersion()
        SettingRow(
            name = "Gentle Mobile $version",
            sub = "protocol 22 · Apache-2.0",
            isMono = true,
            trailing = { MiniButton(label = "What's new", enabled = false) },
        )
        UnavailableNote("No release notes yet — this is $version.")
        Spacer(Modifier.height(24.dp))

        // Actions. Disconnect works only against a live connection; revoke
        // has no core call yet, so it names the host-side command that does.
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
            onClick = {},
            enabled = false,
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
            Text(text = "Revoke this device on all hosts", fontWeight = FontWeight.SemiBold)
        }
        UnavailableNote("The core has no revoke call yet — run gentle-companion-pair --revoke on each host.")
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    // design/system.md: uppercase meta labels, one step smaller and muted.
    Text(
        text = text.uppercase(),
        color = LocalPalette.current.muted,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
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

/** A mini action like the board's `Regenerate` / `Manage`; disabled renders muted. */
@Composable
private fun MiniButton(label: String, enabled: Boolean) {
    val p = LocalPalette.current
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .padding(horizontal = 8.dp),
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
 * The board's locked switch: checked but not togglable. `locked` keeps the
 * track on the raised surface (the pinning row); a plain disabled switch
 * reads as "off" (biometrics).
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
                    .background(
                        if (checked) p.textDisabled else p.muted,
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

/** The hosts the core actually pinned, read from its known_hosts file. */
internal fun pinnedHosts(knownHostsPath: String): List<String> =
    File(knownHostsPath)
        .takeIf { it.exists() }
        ?.readLines()
        ?.mapNotNull { line ->
            line.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() }
        }
        ?.flatMap { it.split(",") }
        ?.distinct()
        .orEmpty()

/** The real versionName from the installed package — never a hardcoded one. */
@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}
