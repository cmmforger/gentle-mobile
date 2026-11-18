package com.gentlemanprogramming.gentlemobile.security

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The biometric gate's decision (T18): what the device's real capability
 * means for the Settings switch. The mapping is pure — the androidx
 * `BiometricManager.canAuthenticate(...)` result codes are compile-time
 * constants, so the tests use the literal values:
 * BIOMETRIC_SUCCESS = 0, BIOMETRIC_ERROR_NO_HARDWARE = 1,
 * BIOMETRIC_ERROR_HW_UNAVAILABLE = 2, BIOMETRIC_ERROR_NONE_ENROLLED = 11.
 */
class BiometricGateTest {

    @Test
    fun `a capable device unlocks the switch`() {
        assertEquals(BiometricDecision.AVAILABLE, biometricDecisionFor(0))
    }

    @Test
    fun `missing hardware and unavailable sensors are not available`() {
        // BIOMETRIC_ERROR_NO_HARDWARE / BIOMETRIC_ERROR_HW_UNAVAILABLE: the
        // switch must be disabled with that reason, never pretend to work.
        assertEquals(BiometricDecision.NOT_AVAILABLE, biometricDecisionFor(1))
        assertEquals(BiometricDecision.NOT_AVAILABLE, biometricDecisionFor(2))
    }

    @Test
    fun `capable hardware without enrolment says not enrolled`() {
        assertEquals(BiometricDecision.NOT_ENROLLED, biometricDecisionFor(11))
    }

    @Test
    fun `an unknown result fails closed`() {
        assertEquals(BiometricDecision.NOT_AVAILABLE, biometricDecisionFor(-42))
    }
}
