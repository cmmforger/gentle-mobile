package com.gentlemanprogramming.gentlemobile.ui.session

/**
 * T14 (S8): dictation is speech turned into text — the host's TUI cannot
 * receive audio, so the transcript goes into the composer field and no
 * voice clip is ever recorded or kept.
 *
 * The state machine is pure Kotlin on purpose — no Android types — so the
 * transitions and the transcript merge rule are unit-tested as plain data
 * (`DictationTest`). The Android side ([AndroidDictationEngine]) drives it
 * from the real recognizer; the machine alone decides which state the
 * composer shows and what text lands in the field.
 *
 * States, per the task and the boards:
 *
 * * idle → requesting permission (only when the engine needs RECORD_AUDIO)
 *   → listening (with a partial transcript and an elapsed clock) → finished;
 * * plus denied (permission refused — the exact refusal sentence, never the
 *   request explanation), permanentlyDenied (the system will no longer show
 *   the dialog: the line points at Settings), unavailable (no recognizer / no
 *   model) and error (recognition failed) — each carrying the exact reason
 *   the line under the composer must show, never a silent no-op.
 *
 * The merge rule: typed text is preserved, every partial replaces the
 * previous one, the final replaces the partials, and exactly one space
 * separates the typed text from the dictated text. An error removes an
 * uncommitted partial and says so; text the user typed is never lost.
 */
sealed interface DictationState {
    /** Nothing happening; the accent circle is the microphone. */
    data object Idle : DictationState

    /** The system permission dialog is up; the message area stays empty —
     *  the icon's accessibility label already says what is happening. */
    data object RequestingPermission : DictationState

    /** The recognizer is listening; [partial] is the transcript so far. */
    data class Listening(val partial: String?, val startedAtMillis: Long) : DictationState

    /** Dictation ended; whatever was committed is already in the field. */
    data object Finished : DictationState

    /** The user refused the microphone; the mic stays disabled with the exact refusal. */
    data class Denied(val explanation: String) : DictationState

    /** The system will no longer show the dialog (denied permanently); the line
     *  points at the system settings, and the mic tap opens them. */
    data class PermanentlyDenied(val reason: String) : DictationState

    /** No recognizer or no model; the mic stays disabled with the reason. */
    data class Unavailable(val reason: String) : DictationState

    /** Recognition failed; the reason is shown and the partial was reverted. */
    data class Error(val message: String) : DictationState
}

/**
 * The exact line the composer shows when the microphone cannot start.
 * `null` means the mic is live (or dictation is running) — no reason line.
 */
fun DictationState?.disabledReason(): String? = when (this) {
    is DictationState.Denied -> explanation
    is DictationState.PermanentlyDenied -> reason
    is DictationState.Unavailable -> reason
    is DictationState.Error -> message
    else -> null
}

/** The recognizer seam, behind the shared session: the real
 *  [AndroidDictationEngine] implements it; the tests drive a fake. The
 *  session's contract with the phone: [start] may only ever be called after
 *  the permission gate returned granted — never before. */
interface DictationEngine {
    interface Listener {
        /** A partial transcript; it replaces the previous one in the field. */
        fun onPartial(text: String)

        /** The final transcript; it replaces every partial. */
        fun onFinal(text: String)

        /** Recognition failed; [message] is the honest reason to show. */
        fun onError(message: String)
    }

    /** True while the microphone permission is not granted yet. */
    val needsPermission: Boolean

    /** The exact line when the permission was refused (S24-9). */
    val refusalMessage: String

    /** The exact line when the system will not show the dialog any more. */
    val permanentlyRefusedMessage: String

    /** `null` when a recognizer can start; otherwise the exact reason. */
    fun availabilityReason(): String?

    /** Construct and start the recognizer. Gate first, this after. */
    fun start(listener: Listener)

    /** The user pressed Stop. */
    fun stop()

    /** The user pressed Cancel. */
    fun cancel()

    /** The screen went away. */
    fun dispose()
}

/** The state plus the field text a transition leaves behind. */
data class DictationUpdate(val state: DictationState, val draft: String)

/**
 * The dictation state machine. Holds the draft that existed before the
 * dictation began (so a cancel or an error can restore it) and merges
 * partials into it as they arrive.
 */
class DictationController(private val clock: () -> Long = System::currentTimeMillis) {

    var state: DictationState = DictationState.Idle
        private set

    /** The field text before this dictation began. */
    private var baseDraft = ""

    /** Transcript committed by a final result or a stop-commit, if any. */
    private var committedText: String? = null

    /** The uncommitted partial currently shown in the field, if any. */
    private var partialText: String? = null

    /**
     * The microphone was tapped. [currentDraft] is captured as the base the
     * dictation builds on; [needsPermission] decides whether the permission
     * gate is shown first. No explanation copy: the message area is empty
     * while the dialog is up (S24-9 amendment) — the mic icon already says
     * what it does.
     */
    fun begin(currentDraft: String, needsPermission: Boolean): DictationUpdate {
        baseDraft = currentDraft
        committedText = null
        partialText = null
        state = if (needsPermission) {
            DictationState.RequestingPermission
        } else {
            DictationState.Listening(partial = null, startedAtMillis = clock())
        }
        return DictationUpdate(state, currentDraft)
    }

    /** The system granted the microphone; the engine has been started. */
    fun onPermissionGranted(): DictationUpdate {
        if (state is DictationState.RequestingPermission) {
            state = DictationState.Listening(partial = null, startedAtMillis = clock())
        }
        return DictationUpdate(state, currentDraft())
    }

    /** The system refused the microphone: disabled, with the exact refusal
     *  sentence (S24-9) — never the request explanation. */
    fun onPermissionDenied(refusal: String): DictationUpdate {
        state = DictationState.Denied(refusal)
        return DictationUpdate(state, currentDraft())
    }

    /** The system will not show the dialog again: disabled, pointing at
     *  the settings the user must open (S24-9). */
    fun onPermissionPermanentlyDenied(refusal: String): DictationUpdate {
        state = DictationState.PermanentlyDenied(refusal)
        return DictationUpdate(state, currentDraft())
    }

    /** No recognizer, no model: disabled, with the engine's exact reason. */
    fun onUnavailable(reason: String): DictationUpdate {
        state = DictationState.Unavailable(reason)
        return DictationUpdate(state, currentDraft())
    }

    /** A partial transcript: it replaces the previous partial in the field. */
    fun onPartial(text: String): DictationUpdate {
        if (state !is DictationState.Listening) return DictationUpdate(state, currentDraft())
        val startedAt = (state as DictationState.Listening).startedAtMillis
        partialText = text.trim().ifEmpty { null }
        state = DictationState.Listening(partial = partialText, startedAtMillis = startedAt)
        return DictationUpdate(state, currentDraft())
    }

    /**
     * The final transcript: it replaces every partial. Allowed after a stop
     * too — recognizers often deliver the final result just after stop.
     */
    fun onFinal(text: String): DictationUpdate {
        if (state !is DictationState.Listening && state !is DictationState.Finished) {
            return DictationUpdate(state, currentDraft())
        }
        committedText = text.trim().ifEmpty { null }
        partialText = null
        state = DictationState.Finished
        return DictationUpdate(state, currentDraft())
    }

    /** The user pressed Stop: the last partial commits, if any. */
    fun stop(): DictationUpdate {
        val partial = (state as? DictationState.Listening)?.partial
        state = DictationState.Finished
        if (partial != null) committedText = partial
        partialText = null
        return DictationUpdate(state, currentDraft())
    }

    /** The user pressed Cancel: everything dictated is discarded. */
    fun cancel(): DictationUpdate {
        state = DictationState.Idle
        return DictationUpdate(state, baseDraft)
    }

    /**
     * Recognition failed: an uncommitted partial is removed from the field
     * (never a half-inserted transcript without saying so) and the reason
     * is shown. Committed text stays — it was acknowledged as final.
     */
    fun onError(message: String): DictationUpdate {
        val hadUncommitted = partialText != null
        state = DictationState.Error(message)
        partialText = null
        return DictationUpdate(state, if (hadUncommitted) baseDraft else currentDraft())
    }

    /**
     * The result message's four seconds are over (S24-9 amendment): the
     * line clears itself — a refusal, an unavailable recognizer or an error
     * is transient, never standing copy. The state returns to idle with
     * nothing to report and the field text is kept exactly as it is. A
     * permanent denial is sticky and never expires here; active states are
     * untouched.
     */
    fun onMessageExpired(): DictationUpdate {
        if (state is DictationState.Denied ||
            state is DictationState.Unavailable ||
            state is DictationState.Error
        ) {
            baseDraft = currentDraft()
            committedText = null
            partialText = null
            state = DictationState.Idle
        }
        return DictationUpdate(state, currentDraft())
    }

    /** The field text this machine believes the composer holds: the base
     * plus whatever has been committed, or the live partial while listening
     * (partials go into the field as they arrive). */
    private fun currentDraft() = mergeDictation(baseDraft, committedText ?: partialText ?: "")
}

/**
 * The listening line's elapsed clock, `m:ss` — how long the recognizer has
 * been listening, computed from the injected clock so it is testable.
 */
fun formatDictationElapsed(startedAtMillis: Long, nowMillis: Long): String {
    val seconds = ((nowMillis - startedAtMillis).coerceAtLeast(0L)) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * The transcript merge rule: the spoken text is appended to the typed text
 * with exactly one separating space. Typed text is never dropped or
 * duplicated, trailing whitespace collapses, and a blank transcript appends
 * nothing (no stray space is added to the field).
 */
fun mergeDictation(base: String, spoken: String): String {
    val spokenTrimmed = spoken.trim()
    if (spokenTrimmed.isEmpty()) return base
    val baseTrimmed = base.trimEnd()
    if (baseTrimmed.isEmpty()) return spokenTrimmed
    return "$baseTrimmed $spokenTrimmed"
}
