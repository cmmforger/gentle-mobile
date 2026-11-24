package com.gentlemanprogramming.gentlemobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.ui.session.ShortcutAction
import com.gentlemanprogramming.gentlemobile.ui.session.ShortcutKey
import com.gentlemanprogramming.gentlemobile.ui.session.ShortcutPad
import com.gentlemanprogramming.gentlemobile.ui.theme.Air
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette

/**
 * The Shortcuts screen (T52): a preview of the pad exactly as the terminal
 * paints it, the joystick cluster as its own on/off unit, and the keys as a
 * grouped list — a minus/plus to hide or show each key, a drag handle to
 * reorder, and `+` to add a key with its own label and action. A custom key
 * can be deleted; the built-ins can only be hidden. "Nothing shown" is a
 * valid state, and it reads that way here too.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShortcutsScreen(
    pad: ShortcutPad,
    onPadChange: (ShortcutPad) -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ShortcutKey?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Air.SIDE_DP.dp),
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
                text = stringResource(R.string.shortcuts_title),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = Air.TITLE_SP.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.shortcuts_intro),
            color = p.muted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(12.dp))

        SectionLabel(stringResource(R.string.shortcuts_preview))
        PadPreview(pad)
        if (pad.visibleKeys.isEmpty() && !pad.joystickVisible) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.shortcuts_preview_empty),
                color = p.textDisabled,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel(stringResource(R.string.shortcuts_joystick))
        Card {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Air.TOUCH_DP.dp)
                    .padding(vertical = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.shortcuts_joystick),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = Air.ROW_NAME_SP.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.shortcuts_joystick_sub),
                        color = p.muted,
                        fontSize = 13.sp,
                    )
                }
                PadSwitch(
                    checked = pad.joystickVisible,
                    onToggle = { onPadChange(pad.withJoystick(it)) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        if (pad.visibleKeys.isEmpty()) {
            SectionLabel(stringResource(R.string.shortcuts_on_pad))
            Card {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Air.TOUCH_DP.dp)
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.shortcuts_preview_empty),
                        color = p.textDisabled,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            SectionLabel(stringResource(R.string.shortcuts_on_pad))
            Card {
                val currentPad by rememberUpdatedState(pad)
                val currentOnChange by rememberUpdatedState(onPadChange)
                pad.visibleKeys.forEach { key ->
                    androidx.compose.runtime.key(key.id) {
                        KeyRow(
                            key = key,
                            dragEnabled = true,
                            onMove = { direction ->
                                val visible = currentPad.visibleKeys
                                val idx = visible.indexOfFirst { it.id == key.id }
                                val target = idx + direction
                                if (target in visible.indices) {
                                    currentOnChange(currentPad.swapWith(key.id, visible[target].id))
                                }
                            },
                            onToggle = { onPadChange(pad.setKeyVisible(key.id, false)) },
                            onDelete = if (ShortcutPad.BUILT_IN_IDS.contains(key.id)) null else {
                                { deleteTarget = key }
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        val hidden = pad.keys.filterNot { it.visible }
        if (hidden.isNotEmpty()) {
            SectionLabel(stringResource(R.string.shortcuts_hidden))
            Card {
                hidden.forEach { key ->
                    KeyRow(
                        key = key,
                        dragEnabled = false,
                        onMove = null,
                        onToggle = { onPadChange(pad.setKeyVisible(key.id, true)) },
                        onDelete = if (ShortcutPad.BUILT_IN_IDS.contains(key.id)) null else {
                            { deleteTarget = key }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Air.TOUCH_DP.dp)
                    .clickable { adding = true },
            ) {
                Text("+", color = p.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.shortcuts_add),
                    color = p.accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (adding) {
        AddKeySheet(
            onSave = { key ->
                adding = false
                onPadChange(pad.addKey(key))
            },
            onDismiss = { adding = false },
        )
    }
    deleteTarget?.let { key ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.shortcuts_delete_title), fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    stringResource(R.string.shortcuts_delete_body, key.label),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    onPadChange(pad.removeKey(key.id))
                }) {
                    Text(stringResource(R.string.shortcuts_delete), color = p.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
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

/** The hairline-bordered card the groups and toggles share. */
@Composable
private fun Card(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Air.RADIUS_CARD_DP.dp))
            .background(p.surface)
            .border(Air.HAIRLINE_DP.dp, p.line, RoundedCornerShape(Air.RADIUS_CARD_DP.dp))
            .padding(horizontal = Air.CARD_PADDING_DP.dp),
    ) {
        content()
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * The preview: the partner of [TerminalKeyRow]'s pad, drawn small. The
 * joystick cross above, the visible keys in their order below — when nothing
 * is set, an empty plate instead of a fake row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PadPreview(pad: ShortcutPad) {
    val p = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Air.RADIUS_TERMINAL_DP.dp))
            .background(p.terminal)
            .border(Air.HAIRLINE_DP.dp, p.line, RoundedCornerShape(Air.RADIUS_TERMINAL_DP.dp))
            .padding(8.dp),
    ) {
        if (pad.joystickVisible) {
            JoystickPreview()
            Spacer(Modifier.height(6.dp))
        }
        if (pad.visibleKeys.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pad.visibleKeys.forEach { key ->
                    PreviewChip(key.label)
                }
            }
        }
    }
}

@Composable
private fun JoystickPreview() {
    val p = LocalPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        PreviewKey("↑")
        Row {
            PreviewKey("←")
            PreviewKey("\u21B5")
            PreviewKey("→")
        }
        PreviewKey("↓")
    }
}

@Composable
private fun PreviewKey(label: String) {
    val p = LocalPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 30.dp, height = 24.dp)
            .padding(1.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .border(1.dp, p.line, RoundedCornerShape(5.dp))
                .background(p.surfaceRaised, RoundedCornerShape(5.dp)),
        ) {
            Text(text = label, color = p.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun PreviewChip(label: String) {
    val p = LocalPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(22.dp)
            .border(1.dp, p.line, RoundedCornerShape(5.dp))
            .background(p.surfaceRaised, RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

/** One key of the grouped list: drag handle (visible keys), label + action
 *  line, a minus/plus to hide/show, and a delete for custom keys. */
@Composable
private fun KeyRow(
    key: ShortcutKey,
    dragEnabled: Boolean,
    onMove: ((Int) -> Unit)?,
    onToggle: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val p = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(Air.TOUCH_DP.dp),
    ) {
        if (dragEnabled && onMove != null) {
            DragHandle(key = key, onMove = onMove)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = key.label,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = Air.ROW_NAME_SP.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = actionSummary(key.action),
                color = p.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The minus/plus to disable or re-enable this key.
        val toggleCd = if (key.visible) {
            stringResource(R.string.shortcuts_disable)
        } else {
            stringResource(R.string.shortcuts_enable)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(Air.TOUCH_DP.dp)
                .clickable(onClick = onToggle)
                .semantics { contentDescription = toggleCd },
        ) {
            Text(
                text = if (key.visible) "\u2212" else "+",
                color = if (key.visible) p.error else p.accent,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (onDelete != null) {
            val deleteCd = stringResource(R.string.shortcuts_delete_cd, key.label)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(Air.TOUCH_DP.dp)
                    .clickable(onClick = onDelete)
                    .semantics { contentDescription = deleteCd },
            ) {
                Text("\u2715", color = p.muted, fontSize = 15.sp)
            }
        }
    }
}

/** The drag handle: accumulates vertical drag and calls [onMove] once per
 *  full row so a continued drag walks the key up or down the list. */
@Composable
private fun DragHandle(key: ShortcutKey, onMove: (Int) -> Unit) {
    val p = LocalPalette.current
    val currentOnMove by rememberUpdatedState(onMove)
    val dragCd = stringResource(R.string.shortcuts_drag)
    var accumulated by remember(key.id) { mutableFloatStateOf(0f) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(Air.TOUCH_DP.dp)
            .pointerInput(key.id) {
                val step = Air.TOUCH_DP.dp.toPx()
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    accumulated += dragAmount.y
                    while (accumulated <= -step / 2f) {
                        currentOnMove(-1)
                        accumulated += step
                    }
                    while (accumulated >= step / 2f) {
                        currentOnMove(1)
                        accumulated -= step
                    }
                }
            }
            .semantics { contentDescription = dragCd },
    ) {
        Text("\u2630", color = p.textDisabled, fontSize = 16.sp)
    }
}

/** A control byte prints as hex; a named key as its word; text verbatim. */
private fun actionSummary(action: ShortcutAction): String = when (action) {
    is ShortcutAction.Key -> keyWord(action.name)
    is ShortcutAction.ControlByte -> "0x%02X".format(action.byte)
    is ShortcutAction.Text -> action.text + if (action.sendEnter) " \u21B5" else ""
}

private fun keyWord(name: String): String = when (name) {
    "esc" -> "Esc"
    "tab" -> "Tab"
    "enter" -> "Enter"
    "up" -> "\u2191"
    "down" -> "\u2193"
    "left" -> "\u2190"
    "right" -> "\u2192"
    else -> name
}

/** The board's switch, drawn with the theme's tokens so the settings screen
 *  keeps its one-accent rule. */
@Composable
private fun PadSwitch(checked: Boolean, onToggle: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier = Modifier.size(Air.TOUCH_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(26.dp)
                .clickable { onToggle(!checked) }
                .border(
                    Air.HAIRLINE_DP.dp,
                    if (checked) p.accent else p.line,
                    RoundedCornerShape(999.dp),
                )
                .background(if (checked) p.accent else p.surface, RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(start = if (checked) 21.dp else 3.dp)
                    .size(18.dp)
                    .background(if (checked) p.bg else p.muted, CircleShape),
            )
        }
    }
}

/** The preset key actions a new key can take, in the order they are offered. */
private data class ActionPreset(val label: String, val action: ShortcutAction)

private val keyPresets: List<ActionPreset> = listOf(
    ActionPreset("Esc", ShortcutAction.Key("esc")),
    ActionPreset("Tab", ShortcutAction.Key("tab")),
    ActionPreset("Enter", ShortcutAction.Key("enter")),
    ActionPreset("\u2191", ShortcutAction.Key("up")),
    ActionPreset("\u2193", ShortcutAction.Key("down")),
    ActionPreset("\u2190", ShortcutAction.Key("left")),
    ActionPreset("\u2192", ShortcutAction.Key("right")),
    ActionPreset("Ctrl+C", ShortcutAction.ControlByte(0x03)),
    ActionPreset("Ctrl+V", ShortcutAction.ControlByte(0x16)),
    ActionPreset("Ctrl+X", ShortcutAction.ControlByte(0x18)),
    ActionPreset("Ctrl+Z", ShortcutAction.ControlByte(0x1A)),
    ActionPreset("Ctrl+A", ShortcutAction.ControlByte(0x01)),
    ActionPreset("Ctrl+D", ShortcutAction.ControlByte(0x04)),
    ActionPreset("Ctrl+L", ShortcutAction.ControlByte(0x0C)),
)

/** The add-key sheet: label + a Key or Text action, saved into the pad. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddKeySheet(
    onSave: (ShortcutKey) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    var label by remember { mutableStateOf("") }
    var textMode by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var sendEnter by remember { mutableStateOf(true) }
    var selectedPreset by remember { mutableStateOf(keyPresets.first().label) }
    val textActionWord = stringResource(R.string.shortcuts_action_text)

    val canSave = !textMode || text.isNotBlank()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Air.CARD_PADDING_DP.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.shortcuts_add_title),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                placeholder = { Text(stringResource(R.string.shortcuts_label_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.shortcuts_action),
                color = p.muted,
                fontSize = Air.SECTION_LABEL_SP.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionKindChip(stringResource(R.string.shortcuts_action_key), !textMode) {
                    textMode = false
                }
                ActionKindChip(stringResource(R.string.shortcuts_action_text), textMode) {
                    textMode = true
                }
            }
            Spacer(Modifier.height(12.dp))
            if (textMode) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.shortcuts_text_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.shortcuts_send_enter),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = Air.ROW_NAME_SP.sp,
                        modifier = Modifier.weight(1f),
                    )
                    PadSwitch(checked = sendEnter, onToggle = { sendEnter = it })
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    keyPresets.forEach { preset ->
                        val selected = preset.label == selectedPreset
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .border(
                                    Air.HAIRLINE_DP.dp,
                                    if (selected) p.accent else p.line,
                                    RoundedCornerShape(8.dp),
                                )
                                .background(
                                    if (selected) p.selection else p.surfaceRaised,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { selectedPreset = preset.label }
                                .padding(horizontal = 10.dp),
                        ) {
                            Text(
                                text = preset.label,
                                color = if (selected) p.accent else MaterialTheme.colorScheme.onBackground,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.set_cancel), color = p.muted)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        val action: ShortcutAction
                        val fallbackLabel: String
                        if (textMode) {
                            action = ShortcutAction.Text(text, sendEnter)
                            fallbackLabel = text.ifBlank { textActionWord }
                        } else {
                            val preset = keyPresets.first { it.label == selectedPreset }
                            action = preset.action
                            fallbackLabel = preset.label
                        }
                        val finalLabel = label.trim().ifEmpty { fallbackLabel }
                        onSave(ShortcutKey("custom_" + java.util.UUID.randomUUID(), finalLabel, action))
                    },
                    enabled = canSave,
                ) {
                    Text(
                        text = stringResource(R.string.set_save),
                        color = if (canSave) p.accent else p.textDisabled,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionKindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 36.dp)
            .border(
                Air.HAIRLINE_DP.dp,
                if (selected) p.accent else p.line,
                RoundedCornerShape(8.dp),
            )
            .background(if (selected) p.selection else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Text(
            text = label,
            color = if (selected) p.accent else MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}