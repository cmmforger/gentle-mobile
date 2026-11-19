package com.gentlemanprogramming.gentlemobile.pair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * S22 one-scan pairing: the phone posts only its public key and the
 * one-time token to the host listener. The reply mapping must turn every
 * host refusal into a readable message — never a silent spinner — and the
 * request must carry exactly the key line, JSON-escaped.
 */
class PairClientTest {

    private fun body(token: String, key: String) =
        "{\"token\":\"$token\",\"key\":\"$key\"}"

    @Test
    fun `a success reply maps to accepted`() {
        val result = PairClient.parseReply(
            200,
            "{\"ok\": true, \"written\": \"ssh-ed25519 AAAA gentle-mobile-20261118-120907\"}",
        )
        assertEquals(PairClient.Result.Accepted(alreadyTrusted = false), result)
    }

    @Test
    fun `an already-trusted reply still counts as accepted`() {
        val result = PairClient.parseReply(200, "{\"ok\": true, \"written\": \"already trusted\"}")
        assertEquals(PairClient.Result.Accepted(alreadyTrusted = true), result)
    }

    @Test
    fun `every host refusal becomes a readable message`() {
        val cases = listOf(
            401 to "wrong token",
            403 to "expired",
            409 to "already used",
            400 to "not a single well-formed SSH public key",
        )
        for ((status, fragment) in cases) {
            val body = "{\"ok\": false, \"error\": \"$fragment — detail\"}"
            val result = PairClient.parseReply(status, body)
            assertTrue("status $status", result is PairClient.Result.Refused)
            assertTrue(
                "status $status message",
                (result as PairClient.Result.Refused).message.contains(fragment),
            )
        }
    }

    @Test
    fun `a refusal with an unreadable body still names the status`() {
        val result = PairClient.parseReply(500, "<html>oops</html>")
        assertTrue(result is PairClient.Result.Refused)
        assertTrue((result as PairClient.Result.Refused).message.contains("500"))
    }

    @Test
    fun `json escaping keeps a key line intact through the request body`() {
        val escaped = PairClient.jsonEscape("ssh-ed25519 AAAA \"quoted\"\nline\ttab")
        assertEquals("ssh-ed25519 AAAA \\\"quoted\\\"\\nline\\ttab", escaped)
    }

    @Test
    fun `sendKey posts token plus key and reads the listener reply`() {
        var seenRequest: String? = null
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread {
            val socket = server.accept()
            socket.use { sock ->
                val input = sock.getInputStream()
                // Read exactly one request: headers until the blank line,
                // then Content-Length body bytes (never wait for EOF — the
                // client keeps its side open for the reply).
                val head = StringBuilder()
                var c = input.read()
                while (c >= 0) {
                    head.append(c.toChar())
                    if (head.endsWith("\r\n\r\n")) break
                    c = input.read()
                }
                val length = Regex("Content-Length: (\\d+)").find(head.toString())
                    ?.groupValues?.get(1)?.toInt() ?: 0
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                seenRequest = head.toString() + body.decodeToString()
                val reply = "{\"ok\": true, \"written\": \"ssh-ed25519 AAAA marker\"}"
                val httpResponse = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${reply.length}\r\nConnection: close\r\n\r\n$reply"
                sock.getOutputStream().apply {
                    write(httpResponse.toByteArray())
                    flush()
                }
            }
        }
        try {
            val result = PairClient.sendKey(
                pairHost = "127.0.0.1",
                pairPort = server.localPort.toString(),
                token = "tok-123",
                publicKeyLine = "ssh-ed25519 AAAATEST gentle-mobile@device",
            )
            val request = seenRequest.orEmpty()
            assertTrue(request.startsWith("POST /pair HTTP/1.1"))
            assertTrue(request.contains("{\"token\":\"tok-123\",\"key\":\"ssh-ed25519 AAAATEST gentle-mobile@device\"}"))
            assertEquals(PairClient.Result.Accepted(alreadyTrusted = false), result)
        } finally {
            server.close()
        }
    }

    @Test
    fun `an unreachable network is not blamed on a stale pairing`() {
        // The phone reported "failed to connect … after 10000 ms" and the app told
        // the human to regenerate the QR, when Tailscale was simply off (S26-1).
        assertEquals(
            PairClient.Reach.NETWORK,
            PairClient.classify(java.net.SocketTimeoutException("failed to connect after 10000 ms")),
        )
        assertEquals(
            PairClient.Reach.NETWORK,
            PairClient.classify(java.net.NoRouteToHostException("No route to host")),
        )
        assertEquals(
            PairClient.Reach.NETWORK,
            PairClient.classify(java.net.ConnectException("Network is unreachable")),
        )
    }

    @Test
    fun `a refused connection means the listener is gone, not the network`() {
        assertEquals(
            PairClient.Reach.NOT_LISTENING,
            PairClient.classify(java.net.ConnectException("Connection refused")),
        )
    }

    @Test
    fun `an address the phone cannot resolve is named as such`() {
        assertEquals(
            PairClient.Reach.UNKNOWN_HOST,
            PairClient.classify(java.net.UnknownHostException("no such host")),
        )
    }

    @Test
    fun `the message names the address and never the raw socket text`() {
        val unreachable = PairClient.defaultUnreachable("100.96.18.28", "40681", PairClient.Reach.NETWORK)
        assertTrue(unreachable.contains("100.96.18.28:40681"))
        assertTrue(unreachable.contains("Tailscale"))
        assertFalse(unreachable.contains("failed to connect to"))
        assertFalse(unreachable.contains("gentle-mobile-pair"))
        val listening = PairClient.defaultUnreachable("10.0.2.2", "22", PairClient.Reach.NOT_LISTENING)
        assertTrue(listening.contains("gentle-mobile pair"))
    }
}
