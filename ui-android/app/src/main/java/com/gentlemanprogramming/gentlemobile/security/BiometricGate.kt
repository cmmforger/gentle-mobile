package com.gentlemanprogramming.gentlemobile.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.ui.theme.KeyValueStore

/**
 * The biometric gate (T18): when the user turns the switch on, opening a
 * session — and every connect, which is the device key being used — must
 * first pass a `BiometricPrompt` (BIOMETRIC_WEAK, device credential as the
 * fallback). The gate is at *use* time: the key itself never moves — it
 * stays in app-private storage exactly as before, and nothing in this file
 * touches it. A device without usable biometrics keeps the switch off with
 * its reason on screen, never a switch that pretends to work.
 */

/** What the device's real capability means for the switch. */
enum class BiometricDecision {
    /** Hardware and enrolment exist: the switch is live. */
    AVAILABLE,
    /** No usable biometric hardware (or the sensor is disabled): switch off. */
    NOT_AVAILABLE,
    /** Hardware exists but nothing is enrolled: switch off with the reason. */
    NOT_ENROLLED,
}

/**
 * Pure mapping from `BiometricManager.canAuthenticate(...)` to the decision.
 * The androidx result codes are compile-time constants (SUCCESS=0,
 * ERROR_NO_HARDWARE=1, ERROR_HW_UNAVAILABLE=2, ERROR_NONE_ENROLLED=11);
 * anything else fails closed.
 */
fun biometricDecisionFor(resultCode: Int): BiometricDecision = when (resultCode) {
    BiometricManager.BIOMETRIC_SUCCESS -> BiometricDecision.AVAILABLE
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricDecision.NOT_ENROLLED
    else -> BiometricDecision.NOT_AVAILABLE
}

/** The device's real capability, asked once per Settings visit. */
fun biometricDecision(context: Context): BiometricDecision {
    val manager = BiometricManager.from(context)
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    return biometricDecisionFor(manager.canAuthenticate(authenticators))
}

/** The persisted switch state, in the app's private preferences. */
class BiometricUnlockStore(private val store: KeyValueStore) {
    fun enabled(): Boolean = store.getString(KEY) == "true"
    fun setEnabled(value: Boolean) {
        store.putString(KEY, value.toString())
    }

    companion object {
        const val KEY = "biometric_unlock"
    }
}

/**
 * Runs `action` — opening a session, connecting — only after the user
 * confirms with the device's biometrics or credential. When the gate is off,
 * or the device cannot enforce it (which is the only way the switch got
 * turned on against a dead sensor), the action runs directly.
 */
fun requireBiometricUnlock(
    activity: FragmentActivity,
    store: BiometricUnlockStore,
    decision: BiometricDecision?,
    onUnlocked: () -> Unit,
) {
    if (!store.enabled() || decision != BiometricDecision.AVAILABLE) {
        onUnlocked()
        return
    }
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onUnlocked()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Cancelled or failed: nothing opens. No retry loop — the
                // user taps the row again when they are ready.
            }
        },
    )
    // S24-1: the prompt's words follow the stored locale.
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.bio_title))
            .setSubtitle(activity.getString(R.string.bio_subtitle))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build(),
    )
}
