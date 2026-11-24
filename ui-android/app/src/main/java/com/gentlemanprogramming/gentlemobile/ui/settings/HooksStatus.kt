package com.gentlemanprogramming.gentlemobile.ui.settings

/**
 * The Settings → Hooks status area (T55): what the host answered when asked
 * whether this phone's key is in its `authorized_keys`. A truthful state,
 * never a guess — the mapping is pure so every case is unit-tested.
 */
sealed interface HooksStatus {
    data object Registered : HooksStatus
    data object NotRegistered : HooksStatus
    data class CannotTell(val reason: String) : HooksStatus
    data object ConnectFirst : HooksStatus
}

/**
 * Map the host's answer to one truthful state. `registered` is the core's
 * answer (true/false); `error` is a typed read failure; `connected` is
 * whether a live connection exists at all. Without a connection there is
 * nothing to ask, so the word is "connect first", never a guess.
 */
fun mapHooksStatus(connected: Boolean, registered: Boolean?, error: String?): HooksStatus =
    when {
        !connected -> HooksStatus.ConnectFirst
        registered == true -> HooksStatus.Registered
        registered == false -> HooksStatus.NotRegistered
        else -> HooksStatus.CannotTell(error.orEmpty())
    }
