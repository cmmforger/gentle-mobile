package com.gentlemanprogramming.gentlemobile.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.security.BiometricDecision
import com.gentlemanprogramming.gentlemobile.ui.locale.AppLanguage
import com.gentlemanprogramming.gentlemobile.ui.sessions.SessionsConfig
import com.gentlemanprogramming.gentlemobile.ui.terminal.CursorMode
import com.gentlemanprogramming.gentlemobile.ui.terminal.CursorPreview
import com.gentlemanprogramming.gentlemobile.ui.terminal.TerminalCursor
import com.gentlemanprogramming.gentlemobile.ui.theme.Air
import com.gentlemanprogramming.gentlemobile.ui.theme.AppTheme
import com.gentlemanprogramming.gentlemobile.ui.theme.AppTypeface
import com.gentlemanprogramming.gentlemobile.ui.theme.fontFamilyFor
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalAppTypeface
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalOnSetTypeface
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalOnSetTerminalFace
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalTerminalFace
import com.gentlemanprogramming.gentlemobile.ui.theme.SharedPreferencesStore
import com.gentlemanprogramming.gentlemobile.ui.theme.TerminalFace
import com.gentlemanprogramming.gentlemobile.ui.theme.isMonospaced
import com.gentlemanprogramming.gentlemobile.ui.theme.terminalFontFamily

/**
 * The Settings screen (S21/S33, board `07-settings.html`): one card per
 * area — icon, title and purpose line, hairline rows inside, the control on
 * the right, footnotes where honesty needs explaining. The Connection
 * choice is gone (L31): the app talks to herdr, there is nothing to choose.
 * The air numbers (S33) carry the whole screen. Everything here is real
 * (T18): the key regenerates with a confirmation, known hosts list and
 * forget per host, the biometric switch opens a real prompt and only
 * explains itself when the device cannot enforce it, and revoke wipes this
 * device after its own confirmation. An element the device cannot support
 * is disabled with its exact reason — never a dead control that pretends to
 * work.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    biometricDecision: BiometricDecision?,
    biometricEnabled: Boolean,
    theme: AppTheme,
    followThePhone: Boolean,
    language: AppLanguage,
    advanced: AdvancedSettings,
    onSetFollowThePhone: (Boolean) -> Unit,
    onSetLanguage: (AppLanguage) -> Unit,
    onSetAdvanced: (AdvancedSettings) -> Unit,
    connected: Boolean,
    /** T15 (S11): the notification permission as the device holds it —
     *  `null` below Android 13, where there is nothing to deny. */
    notificationsGranted: Boolean?,
    onOpenThemes: () -> Unit,
    onOpenShortcuts: () -> Unit,
    onOpenHooks: () -> Unit,
    onOpenVoz: () -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onRevoke: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val typeface = LocalAppTypeface.current
    val onSetTypeface = LocalOnSetTypeface.current
    val terminalFace = LocalTerminalFace.current
    val onSetTerminalFace = LocalOnSetTerminalFace.current
    var confirmRevoke by remember { mutableStateOf(false) }
    var typefacePickerOpen by remember { mutableStateOf(false) }
    var terminalFontPickerOpen by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }
    // Sessions directory (T17a): the override lives across screens, so the
    // edit state belongs to the function, not to a card slot.
    val context = LocalContext.current
    val sessionsStore = remember { SharedPreferencesStore(context) }
    var editingSessionsDir by remember { mutableStateOf(false) }
    var sessionsDirDraft by remember { mutableStateOf("") }
    var sessionsDirOverride by remember {
        mutableStateOf(sessionsStore.getString(SessionsConfig.OVERRIDE_KEY))
    }
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
                text = stringResource(R.string.set_title),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = Air.TITLE_SP.sp,
                lineHeight = (Air.TITLE_SP * Air.TITLE_LINE_HEIGHT).sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(12.dp))

        SettingsCard(
            title = stringResource(R.string.set_section_security),
            sub = stringResource(R.string.set_area_security_sub),
            icon = painterResource(R.drawable.ic_area_lock),
        ) {
            // Biometric unlock: a real switch (T18). On, it stands between the
            // user and every session open and every connect. A device that
            // cannot enforce it keeps the switch off with the exact reason.
            SettingRow(
                name = stringResource(R.string.set_biometric),
                sub = stringResource(R.string.set_biometric_sub),
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
                    stringResource(R.string.set_biometric_no_hw),
                )
                BiometricDecision.NOT_ENROLLED -> UnavailableNote(
                    stringResource(R.string.set_biometric_not_enrolled),
                )
                null -> UnavailableNote(stringResource(R.string.set_biometric_checking))
                else -> {}
            }

            // Host-key pinning is real and locked on: the core refuses a host
            // whose key changed, with no way to turn it off.
            SettingRow(
                name = stringResource(R.string.set_pinning),
                sub = stringResource(R.string.set_pinning_sub),
                trailing = { DisabledSwitch(checked = true, locked = true) },
            )

            // Where the Sessions screen lists from (T17a): the host's own
            // default unless a debug/test override was entered. It points at
            // the host, not at a security decision — it lives in this card
            // now that the Connection section is gone.
            SettingRow(
                name = stringResource(R.string.set_sessions_dir),
                sub = sessionsDirOverride?.trim()?.takeIf { it.isNotEmpty() }
                    ?: stringResource(R.string.set_sessions_dir_default),
                isMono = true,
                trailing = {
                    MiniButton(label = stringResource(R.string.set_edit), enabled = true) {
                        sessionsDirDraft = sessionsDirOverride?.trim().orEmpty()
                        editingSessionsDir = true
                    }
                },
            )

            // T15 (S11): the permission, stated honestly. A denial is never a
            // silent failure — the row says notifications are off and why,
            // and the way back is one tap into the system's own settings.
            SettingRow(
                name = stringResource(R.string.set_notifications),
                sub = when (notificationsGranted) {
                    true -> stringResource(R.string.set_notifications_on_detail)
                    false -> stringResource(R.string.set_notifications_off_detail)
                    null -> stringResource(R.string.set_notifications_na_detail)
                },
                trailing = {
                    Text(
                        text = if (notificationsGranted == false) {
                            stringResource(R.string.set_notifications_off)
                        } else {
                            stringResource(R.string.set_notifications_on)
                        },
                        color = p.muted,
                        fontSize = 13.sp,
                    )
                },
            )
            if (notificationsGranted == false) {
                SettingRow(
                    name = stringResource(R.string.set_enable_notifications),
                    sub = stringResource(R.string.set_enable_notifications_sub),
                    onClick = onOpenNotificationSettings,
                    trailing = { Text("›", color = p.muted, fontSize = 15.sp) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SettingsCard(
            title = stringResource(R.string.set_area_hooks),
            sub = stringResource(R.string.set_area_hooks_sub),
            iconGlyph = "\u2192",
        ) {
            SettingRow(
                name = stringResource(R.string.hooks_open),
                sub = stringResource(R.string.hooks_open_sub),
                onClick = onOpenHooks,
                trailing = { Text("›", color = p.muted, fontSize = 15.sp) },
            )
        }

        Spacer(Modifier.height(12.dp))
        SettingsCard(
            title = stringResource(R.string.set_area_appearance),
            sub = stringResource(R.string.set_area_appearance_sub),
            icon = painterResource(R.drawable.ic_area_contrast),
        ) {
            // L31: Follow the phone — dark renders Cute, light renders Paper.
            // Off (the default) the pinned theme stands.
            SettingRow(
                name = stringResource(R.string.set_follow_phone),
                sub = stringResource(R.string.set_follow_phone_sub),
                trailing = {
                    GateSwitch(checked = followThePhone, enabled = true, onToggle = onSetFollowThePhone)
                },
            )
            // The pinned theme opens the four-choice picker (Cute, Sexy,
            // Gentle, Paper): the same surface the theme work lives in, one
            // chooser instead of two.
            SettingRow(
                name = stringResource(R.string.set_pinned_theme),
                sub = theme.label,
                onClick = onOpenThemes,
                trailing = { Text("›", color = p.muted, fontSize = 15.sp) },
            )
        }

        Spacer(Modifier.height(12.dp))
        SettingsCard(
            title = stringResource(R.string.set_area_language),
            sub = stringResource(R.string.set_area_language_sub),
            icon = painterResource(R.drawable.ic_area_globe),
        ) {
            // The terminal is never translated (L31): the switch changes the
            // chrome's words only, and applies immediately and persists.
            SettingRow(
                name = language.displayName,
                sub = stringResource(
                    R.string.set_language_switch_sub,
                    (if (language == AppLanguage.SPANISH) AppLanguage.ENGLISH else AppLanguage.SPANISH).displayName,
                ),
                onClick = {
                    onSetLanguage(
                        if (language == AppLanguage.SPANISH) AppLanguage.ENGLISH else AppLanguage.SPANISH,
                    )
                },
                trailing = { Text("›", color = p.muted, fontSize = 15.sp) },
            )
        }

        Spacer(Modifier.height(12.dp))
        SettingsCard(
            title = stringResource(R.string.set_voz),
            sub = stringResource(R.string.set_voz_sub),
            iconGlyph = "\uD83C\uDF99",
        ) {
            SettingRow(
                name = stringResource(R.string.voz_title),
                sub = stringResource(R.string.voz_engine_label),
                onClick = onOpenVoz,
                trailing = { Text("›", color = p.muted, fontSize = 15.sp) },
            )
        }

        Spacer(Modifier.height(12.dp))
        SettingsCard(
            title = stringResource(R.string.set_area_typeface),
            sub = stringResource(R.string.set_area_typeface_sub),
            iconGlyph = "Aa",
        ) {
            SettingRow(
                name = stringResource(R.string.set_typeface_family),
                sub = if (typeface == AppTypeface.SYSTEM) {
                    stringResource(R.string.set_typeface_value)
                } else {
                    typeface.label
                },
                onClick = { typefacePickerOpen = true },
                trailing = { Text("›", color = p.muted, fontSize = 15.sp) },
            )
            Footnote(stringResource(R.string.set_typeface_foot))
        }

        Spacer(Modifier.height(12.dp))
        SettingsCard(
            title = stringResource(R.string.set_area_terminal_font),
            sub = stringResource(R.string.set_area_terminal_font_sub),
            iconGlyph = "T",
        ) {
            SettingRow(
                name = stringResource(R.string.set_typeface_family),
                sub = terminalFace.label,
                isMono = true,
                onClick = { terminalFontPickerOpen = true },
                trailing = { Text("›", color = p.muted, fontSize = 15.sp) },
            )
        }

        Spacer(Modifier.height(12.dp))
        SettingsCard(
            title = stringResource(R.string.set_area_shortcuts),
            sub = stringResource(R.string.set_area_shortcuts_sub),
            iconGlyph = "\u2318",
        ) {
            SettingRow(
                name = stringResource(R.string.shortcuts_open),
                sub = stringResource(R.string.shortcuts_intro),
                onClick = onOpenShortcuts,
                trailing = { Text("\u203A", color = p.muted, fontSize = 15.sp) },
            )
        }

        Spacer(Modifier.height(12.dp))
        SettingsCard(
            title = stringResource(R.string.set_area_advanced),
            sub = stringResource(R.string.set_area_advanced_sub),
            iconGlyph = "≡",
            expanded = advancedOpen,
            onToggle = { advancedOpen = !advancedOpen },
        ) {
            GroupHeading(stringResource(R.string.set_advanced_group_cursor))
            // T53: the three shapes live in the card — you pick by looking.
            var cursorBlinkPhase by remember { mutableStateOf(0L) }
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(CursorPreview.PERIOD_MS)
                    cursorBlinkPhase++
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (mode in CursorMode.entries) {
                    val chosen = mode == advanced.cursorMode
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Air.TOUCH_DP.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (chosen) p.selection else p.surfaceRaised)
                            .clickable { onSetAdvanced(advanced.copy(cursorMode = mode)) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CursorPreviewCell(
                            mode = mode,
                            visible = CursorPreview.blink(cursorBlinkPhase * CursorPreview.PERIOD_MS),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = cursorModeLabel(mode),
                            color = if (chosen) p.accent else p.text,
                            fontSize = 13.sp,
                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            SettingRow(
                name = stringResource(R.string.set_cursor_blink),
                sub = stringResource(R.string.set_cursor_blink_sub),
                trailing = {
                    GateSwitch(
                        checked = advanced.cursorBlink,
                        enabled = true,
                        onToggle = { onSetAdvanced(advanced.copy(cursorBlink = it)) },
                    )
                },
            )

            GroupHeading(stringResource(R.string.set_advanced_group_input))
            SettingRow(
                name = stringResource(R.string.set_use_composer),
                sub = stringResource(R.string.set_use_composer_sub),
                trailing = {
                    GateSwitch(
                        checked = advanced.useComposer,
                        enabled = true,
                        onToggle = { onSetAdvanced(advanced.copy(useComposer = it)) },
                    )
                },
            )
            SettingRow(
                name = stringResource(R.string.set_send_on_enter),
                sub = stringResource(R.string.set_send_on_enter_sub),
                trailing = {
                    GateSwitch(
                        checked = advanced.sendOnEnter,
                        enabled = advanced.useComposer,
                        onToggle = { onSetAdvanced(advanced.copy(sendOnEnter = it)) },
                    )
                },
            )
            if (!advanced.useComposer) {
                UnavailableNote(stringResource(R.string.set_send_on_enter_off))
            }

            GroupHeading(stringResource(R.string.set_advanced_group_behaviour))
            SettingRow(
                name = stringResource(R.string.set_keep_screen_on),
                sub = stringResource(R.string.set_keep_screen_on_sub),
                trailing = {
                    GateSwitch(
                        checked = advanced.keepScreenOn,
                        enabled = true,
                        onToggle = { onSetAdvanced(advanced.copy(keepScreenOn = it)) },
                    )
                },
            )

            GroupHeading(stringResource(R.string.set_advanced_group_sessions))
            SettingRow(
                name = stringResource(R.string.set_sort_sessions_by_name),
                sub = stringResource(R.string.set_sort_sessions_by_name_sub),
                trailing = {
                    GateSwitch(
                        checked = advanced.sortSessionsByName,
                        enabled = true,
                        onToggle = { onSetAdvanced(advanced.copy(sortSessionsByName = it)) },
                    )
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        SettingsCard(
            title = stringResource(R.string.set_area_about),
            sub = stringResource(R.string.set_area_about_sub),
            iconGlyph = "i",
        ) {
            val version = appVersion()
            SettingRow(
                name = stringResource(R.string.set_about_version, version),
                sub = stringResource(R.string.set_about_sub),
                isMono = true,
                trailing = { MiniButton(label = stringResource(R.string.set_whats_new), enabled = false) },
            )
            UnavailableNote(stringResource(R.string.set_no_release_notes, version))
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
                .height(Air.TOUCH_DP.dp),
            shape = RoundedCornerShape(Air.RADIUS_TERMINAL_DP.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = p.text,
                disabledContentColor = p.textDisabled,
            ),
        ) {
            Text(text = stringResource(R.string.set_disconnect), fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { confirmRevoke = true },
            enabled = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(Air.TOUCH_DP.dp),
            shape = RoundedCornerShape(Air.RADIUS_TERMINAL_DP.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = p.error,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = p.error,
            ),
        ) {
            Text(text = stringResource(R.string.set_revoke), fontWeight = FontWeight.SemiBold)
        }
        UnavailableNote(
            stringResource(R.string.set_revoke_note),
        )
        Spacer(Modifier.height(24.dp))
    }

    if (editingSessionsDir) {
        AlertDialog(
            onDismissRequest = { editingSessionsDir = false },
            title = { Text(stringResource(R.string.set_sessions_dir)) },
            text = {
                OutlinedTextField(
                    value = sessionsDirDraft,
                    onValueChange = { sessionsDirDraft = it },
                    placeholder = { Text(stringResource(R.string.set_sessions_dir_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    sessionsStore.putString(SessionsConfig.OVERRIDE_KEY, sessionsDirDraft)
                    sessionsDirOverride = sessionsDirDraft
                    editingSessionsDir = false
                }) { Text(stringResource(R.string.set_save), color = p.accent) }
            },
            dismissButton = {
                TextButton(onClick = { editingSessionsDir = false }) {
                    Text(stringResource(R.string.set_cancel), color = p.muted)
                }
            },
            containerColor = p.surfaceRaised,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            textContentColor = p.muted,
        )
    }
    if (confirmRevoke) {
        ConfirmDialog(
            title = stringResource(R.string.set_confirm_revoke_title),
            body = stringResource(R.string.set_confirm_revoke_body),
            confirmLabel = stringResource(R.string.set_confirm_revoke),
            destructive = true,
            onConfirm = {
                confirmRevoke = false
                onRevoke()
            },
            onDismiss = { confirmRevoke = false },
        )
    }
    if (typefacePickerOpen) {
        ModalBottomSheet(
            onDismissRequest = { typefacePickerOpen = false },
            containerColor = p.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Air.CARD_PADDING_DP.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.set_area_typeface),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                for (choice in AppTypeface.entries) {
                    val current = choice == typeface
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Air.TOUCH_DP.dp)
                            .clickable {
                                typefacePickerOpen = false
                                onSetTypeface(choice)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The row is its own preview: it draws in the face it names.
                        Text(
                            text = if (choice == AppTypeface.SYSTEM) {
                                stringResource(R.string.set_typeface_value)
                            } else {
                                choice.label
                            },
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 15.sp,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            fontFamily = fontFamilyFor(choice),
                        )
                        if (isMonospaced(choice)) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.set_typeface_mono),
                                color = p.muted,
                                fontSize = 11.sp,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (current) {
                            Text("✓", color = p.accent, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
    if (terminalFontPickerOpen) {
        ModalBottomSheet(
            onDismissRequest = { terminalFontPickerOpen = false },
            containerColor = p.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Air.CARD_PADDING_DP.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.set_area_terminal_font),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                for (face in TerminalFace.entries) {
                    val current = face == terminalFace
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Air.TOUCH_DP.dp)
                            .clickable {
                                terminalFontPickerOpen = false
                                onSetTerminalFace(face)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The row is its own preview: it draws in the face it names.
                        Text(
                            text = face.label,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 15.sp,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            fontFamily = terminalFontFamily(face),
                        )
                        Spacer(Modifier.weight(1f))
                        if (current) {
                            Text("✓", color = p.accent, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun cursorModeLabel(mode: CursorMode): String = when (mode) {
    CursorMode.BLOCK -> stringResource(R.string.set_cursor_mode_block)
    CursorMode.UNDERLINE -> stringResource(R.string.set_cursor_mode_underline)
    CursorMode.BAR -> stringResource(R.string.set_cursor_mode_bar)
}

/** One cursor preview in the picker: a single character cell with the
 *  cursor's shape drawn over it, blinking on the cursor duty cycle. */
@Composable
private fun CursorPreviewCell(mode: CursorMode, visible: Boolean) {
    val p = LocalPalette.current
    Canvas(modifier = Modifier.size(width = 20.dp, height = 26.dp)) {
        val stroke = 1.dp.toPx()
        drawRect(color = p.line)
        drawRect(
            color = p.surfaceRaised,
            topLeft = Offset(stroke, stroke),
            size = Size(size.width - stroke * 2, size.height - stroke * 2),
        )
        if (visible) {
            val rect = TerminalCursor.rect(mode)
            drawRect(
                color = p.accent,
                topLeft = Offset(rect.left * size.width, rect.top * size.height),
                size = Size(
                    (rect.right - rect.left) * size.width,
                    (rect.bottom - rect.top) * size.height,
                ),
            )
        }
    }
}

/** The quiet group label inside the Advanced card (Moshi's grouped shape). */
@Composable
private fun GroupHeading(text: String) {
    val p = LocalPalette.current
    Text(
        text = text,
        color = p.muted,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
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
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.set_cancel), color = p.muted)
            }
        },
        containerColor = p.surfaceRaised,
        titleContentColor = MaterialTheme.colorScheme.onBackground,
        textContentColor = p.muted,
    )
}

/** One settings card (board 07): header — icon, title, purpose line — then
 *  the hairline rows and the card's own footnote. White surface, 16 radius,
 *  hairline border; on Paper the board's soft shadow lands via elevation. */
@Composable
private fun SettingsCard(
    title: String,
    sub: String,
    icon: Painter? = null,
    iconGlyph: String? = null,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(Air.RADIUS_CARD_DP.dp),
                ambientColor = Color(0x14181215),
                spotColor = Color(0x14181215),
            )
            .clip(RoundedCornerShape(Air.RADIUS_CARD_DP.dp))
            .background(p.surface)
            .border(
                width = Air.HAIRLINE_DP.dp,
                color = p.line,
                shape = RoundedCornerShape(Air.RADIUS_CARD_DP.dp),
            )
            .padding(horizontal = Air.CARD_PADDING_DP.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(top = 12.dp, bottom = 4.dp)
                .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
                .heightIn(min = Air.TOUCH_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardIcon(icon = icon, glyph = iconGlyph)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = p.accent,
                    fontSize = Air.ROW_NAME_SP.sp,
                    lineHeight = (Air.ROW_NAME_SP * Air.ROW_NAME_LINE_HEIGHT).sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = sub,
                    color = p.muted,
                    fontSize = 13.sp,
                    lineHeight = (13 * Air.ROW_NAME_LINE_HEIGHT).sp,
                )
            }
            if (onToggle != null) {
                Text(
                    text = "›",
                    color = p.muted,
                    fontSize = 18.sp,
                    modifier = Modifier.rotate(if (expanded) 90f else 0f),
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            content()
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The card's leading mark: a drawn icon when the board drew one, or its
 *  glyph (`Aa`, `T`, `i`) inside the same 30dp tinted square. */
@Composable
private fun CardIcon(icon: Painter?, glyph: String?) {
    val p = LocalPalette.current
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(p.surfaceRaised, RoundedCornerShape(Air.RADIUS_FIELD_DP.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            icon != null -> Image(
                painter = icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                colorFilter = ColorFilter.tint(p.accent),
            )
            else -> Text(
                text = glyph ?: "",
                color = p.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
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
            .heightIn(min = Air.TOUCH_DP.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = Air.ROW_NAME_SP.sp,
                lineHeight = (Air.ROW_NAME_SP * Air.ROW_NAME_LINE_HEIGHT).sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = sub,
                color = p.muted,
                fontSize = if (isMono) 11.sp else 13.sp,
                fontFamily = if (isMono) FontFamily.Monospace else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
    HairlineRow()
}

/** The card's closing footnote (board 07 `.card-foot`): quiet, its own
 *  hairline above. */
@Composable
private fun Footnote(text: String) {
    val p = LocalPalette.current
    Column {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(p.line),
        )
        Text(
            text = text,
            color = p.muted,
            fontSize = Air.META_SP.sp,
            lineHeight = (Air.META_SP * Air.TITLE_LINE_HEIGHT + 2).sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
    }
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
            .heightIn(min = Air.TOUCH_DP.dp)
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
        modifier = Modifier.size(Air.TOUCH_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(26.dp)
                .border(
                    Air.HAIRLINE_DP.dp,
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
        modifier = Modifier.size(Air.TOUCH_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(26.dp)
                .then(if (enabled) Modifier.clickable { onToggle(!checked) } else Modifier)
                .border(
                    Air.HAIRLINE_DP.dp,
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
