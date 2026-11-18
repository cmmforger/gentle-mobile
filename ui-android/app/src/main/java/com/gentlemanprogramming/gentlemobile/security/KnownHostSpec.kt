package com.gentlemanprogramming.gentlemobile.security

/**
 * Reverse the core's known_hosts hostspec (`spec_for` in the Rust core):
 * a bare host means port 22, `[host]:port` is everything else. A shape the
 * core never wrote is refused, not guessed.
 */
fun parseHostSpec(spec: String): Pair<String, Int>? {
    val trimmed = spec.trim()
    if (trimmed.startsWith("[") && trimmed.contains("]:")) {
        val host = trimmed.removePrefix("[").substringBefore("]:")
        val port = trimmed.substringAfter("]:").toIntOrNull()
        if (host.isNotEmpty() && port != null && port in 1..65535) return host to port
        return null
    }
    if (trimmed.isEmpty() || trimmed.contains(":") || trimmed.contains(" ")) return null
    return trimmed to 22
}
