package com.gentlemanprogramming.gentlemobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.pair.HooksCommand
import com.gentlemanprogramming.gentlemobile.ui.terminal.TerminalPalette
import com.gentlemanprogramming.gentlemobile.ui.theme.Air
import com.gentlemanprogramming.gentlemobile.ui.theme.AppTheme
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette

/** The two platform tabs the command block offers. */
private enum class HooksPlatform { MAC_LINUX, WINDOWS }

/**
 * The Hooks screen (T55): the steps to install and connect this phone from
 * the computer, with platform tabs and a copyable command block, this
 * phone's key fingerprint (never a fake token) with a regenerate action,
 * and a status area that asks the host whether this key is trusted — a
 * truthful word, never a guess. The command block is the repo's own
 * helper, per platform, never Moshi's commands.
 */
@Composable
fun HooksScreen(
    fingerprint: String?,
    status: HooksStatus?,
    checking: Boolean,
    theme: AppTheme,
    onRegenerate: () -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    val term = remember(theme) { TerminalPalette.forTheme(theme) }
    val terminalPrompt = Color(term.colors[2])
    val terminalText = Color(term.foreground)
    val terminalBackground = Color(term.background)
    var tab by remember { mutableStateOf(HooksPlatform.MAC_LINUX) }
    var copiedCommand by remember { mutableStateOf(false) }
    var copiedFingerprint by remember { mutableStateOf(false) }
    var confirmRegenerate by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Air.SIDE_DP.dp, vertical = Air.TOP_DP.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val backCd = stringResource(R.string.cd_back)
            Box(
                modifier = Modifier
                    .size(Air.TOUCH_DP.dp)
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = backCd },
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", color = p.accent, fontSize = 20.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.hooks_title),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = Air.TITLE_SP.sp,
                lineHeight = (Air.TITLE_SP * Air.TITLE_LINE_HEIGHT).sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.hooks_intro),
            color = p.muted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(12.dp))

        // Platform tabs: one command block per tab, whole block copied.
        SectionLabel(stringResource(R.string.hooks_steps))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlatformTab(
                label = stringResource(R.string.hooks_tab_mac_linux),
                selected = tab == HooksPlatform.MAC_LINUX,
                onClick = { tab = HooksPlatform.MAC_LINUX; copiedCommand = false },
            )
            PlatformTab(
                label = stringResource(R.string.hooks_tab_windows),
                selected = tab == HooksPlatform.WINDOWS,
                onClick = { tab = HooksPlatform.WINDOWS; copiedCommand = false },
            )
        }
        Spacer(Modifier.height(8.dp))
        val block = when (tab) {
            HooksPlatform.MAC_LINUX -> HooksCommand.macLinuxBlock()
            HooksPlatform.WINDOWS -> HooksCommand.windowsBlock()
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Air.RADIUS_FIELD_DP.dp))
                        .background(terminalBackground)
                        .padding(12.dp),
                ) {
                    block.lines().forEach { line ->
                        Row {
                            Text(
                                text = "$ ",
                                color = terminalPrompt,
                                fontSize = Air.COMMAND_SP.sp,
                                lineHeight = (Air.COMMAND_SP * Air.BODY_LINE_HEIGHT).sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = line,
                                color = terminalText,
                                fontSize = Air.COMMAND_SP.sp,
                                lineHeight = (Air.COMMAND_SP * Air.BODY_LINE_HEIGHT).sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                CopyAction(
                    label = stringResource(R.string.pair_copy),
                    onClick = {
                        clipboard.setText(AnnotatedString(block))
                        copiedCommand = true
                    },
                )
            }
            if (copiedCommand) {
                CopiedLine()
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = when (tab) {
                    HooksPlatform.MAC_LINUX -> stringResource(R.string.hooks_mac_note)
                    HooksPlatform.WINDOWS -> stringResource(R.string.hooks_windows_note)
                },
                color = p.muted,
                fontSize = Air.META_SP.sp,
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel(stringResource(R.string.hooks_identity_label))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = fingerprint ?: stringResource(R.string.hooks_identity_loading),
                        color = if (fingerprint != null) {
                            MaterialTheme.colorScheme.onBackground
                        } else {
                            p.textDisabled
                        },
                        fontSize = Air.COMMAND_SP.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.hooks_identity_sub),
                        color = p.muted,
                        fontSize = Air.META_SP.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                CopyAction(
                    label = stringResource(R.string.pair_copy),
                    onClick = {
                        fingerprint?.let {
                            clipboard.setText(AnnotatedString(it))
                            copiedFingerprint = true
                        }
                    },
                )
            }
            if (copiedFingerprint) {
                CopiedLine()
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Air.TOUCH_DP.dp)
                    .clickable { confirmRegenerate = true },
            ) {
                Text(
                    text = stringResource(R.string.hooks_regenerate),
                    color = p.accent,
                    fontSize = Air.ROW_NAME_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.hooks_regenerate_note),
                color = p.muted,
                fontSize = Air.META_SP.sp,
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel(stringResource(R.string.hooks_status_label))
        Card {
            val line = when {
                checking -> stringResource(R.string.hooks_status_checking)
                status == null -> stringResource(R.string.hooks_status_checking)
                status is HooksStatus.ConnectFirst ->
                    stringResource(R.string.hooks_status_connect_first)
                status is HooksStatus.Registered ->
                    stringResource(R.string.hooks_status_registered)
                status is HooksStatus.NotRegistered ->
                    stringResource(R.string.hooks_status_not_registered)
                status is HooksStatus.CannotTell ->
                    if (status.reason.isBlank()) {
                        stringResource(R.string.hooks_status_cannot_tell)
                    } else {
                        stringResource(R.string.hooks_status_cannot_tell_reason, status.reason)
                    }
                else -> stringResource(R.string.hooks_status_cannot_tell)
            }
            Text(
                text = line,
                color = when {
                    checking || status == null -> p.muted
                    status is HooksStatus.Registered -> p.accent
                    else -> MaterialTheme.colorScheme.onBackground
                },
                fontSize = Air.ROW_NAME_SP.sp,
                lineHeight = (Air.ROW_NAME_SP * Air.ROW_NAME_LINE_HEIGHT).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text(stringResource(R.string.hooks_regenerate_confirm_title), fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    stringResource(R.string.hooks_regenerate_confirm_body),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRegenerate = false
                    onRegenerate()
                }) {
                    Text(
                        stringResource(R.string.hooks_regenerate),
                        color = p.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerate = false }) {
                    Text(stringResource(R.string.set_cancel), color = p.muted)
                }
            },
            containerColor = p.surfaceRaised,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            textContentColor = p.muted,
        )
    }
}

/** A quiet section label, the board's grouped-list shape. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = LocalPalette.current.muted,
        fontSize = Air.SECTION_LABEL_SP.sp,
        letterSpacing = Air.SECTION_TRACKING_SP.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

/** The hairline-bordered card the groups share. */
@Composable
private fun Card(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Air.RADIUS_CARD_DP.dp))
            .background(p.surface)
            .border(Air.HAIRLINE_DP.dp, p.line, RoundedCornerShape(Air.RADIUS_CARD_DP.dp))
            .padding(horizontal = Air.CARD_PADDING_DP.dp, vertical = Air.CARD_PADDING_DP.dp),
    ) {
        content()
    }
}

/** One platform tab chip: the accent marks the active platform. */
@Composable
private fun PlatformTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = Air.TOUCH_DP.dp)
            .clip(RoundedCornerShape(Air.RADIUS_FIELD_DP.dp))
            .background(if (selected) p.selection else p.surfaceRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Text(
            text = label,
            color = if (selected) p.accent else p.text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The Copy button, sized like the board's mini actions. */
@Composable
private fun CopyAction(label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier = Modifier
            .heightIn(min = Air.TOUCH_DP.dp)
            .padding(horizontal = 8.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = p.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** The quiet "Copied" acknowledgement under a copy action. */
@Composable
private fun CopiedLine() {
    val p = LocalPalette.current
    Text(
        text = stringResource(R.string.pair_copied),
        color = p.accent,
        fontSize = Air.META_SP.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}
