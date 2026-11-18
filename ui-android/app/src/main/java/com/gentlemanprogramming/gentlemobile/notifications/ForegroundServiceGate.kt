package com.gentlemanprogramming.gentlemobile.notifications

/**
 * T15 (S11), pure service gate: the foreground service runs exactly while a
 * connection is live. One start per connection, one stop when the
 * connection ends or the user disconnects — start/stop pairs, never two
 * starts without a stop between, never a stop for a service that is not
 * running. Unit-tested in [ForegroundServiceGateTest].
 */
class ForegroundServiceGate {
    /** The host the running service names, or `null` when stopped. */
    var runningHost: String? = null
        private set

    /** The connection went live. True means the caller should issue the
     *  start command; false means the service already runs for this host
     *  and a second start would stack a duplicate notification. */
    fun onConnected(host: String): Boolean {
        if (runningHost == host) return false
        runningHost = host
        return true
    }

    /** The connection ended, or the user disconnected. True means the
     *  caller should issue the stop command; false means nothing is
     *  running, so there is no pair to stop. */
    fun onConnectionEnded(): Boolean {
        val wasRunning = runningHost != null
        runningHost = null
        return wasRunning
    }
}
