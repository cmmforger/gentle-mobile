package com.gentlemanprogramming.gentlemobile.identity

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64

/**
 * Minimal OpenSSH-format ed25519 material, hand-rolled because the russh core
 * loads the identity from a file (`decode_secret_key`) and AndroidKeyStore
 * keys cannot be exported into that format. Only ed25519 is produced; the
 * wire shapes here are the documented OpenSSH ones:
 *
 * - Public line: "ssh-ed25519 " + base64(string "ssh-ed25519" ++ string pub32) + comment
 * - Private PEM: "openssh-key-v1\0" + none/none/none + 1 key
 *   + pub blob + checkint² + type + pub32 + seed‖pub32 + comment + 1,2,3… padding
 */
object Ed25519Identity {
    /** Public key bytes for a seed. */
    fun publicFromSeed(seed: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(seed).generatePublicKey().encoded

    /** Fresh random seed. */
    fun newSeed(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
}

object OpenSshKey {

    /** One SSH wire string: uint32 big-endian length + bytes. */
    fun sshString(bytes: ByteArray): ByteArray {
        val out = ByteArray(4 + bytes.size)
        out[0] = (bytes.size ushr 24).toByte()
        out[1] = (bytes.size ushr 16).toByte()
        out[2] = (bytes.size ushr 8).toByte()
        out[3] = bytes.size.toByte()
        bytes.copyInto(out, 4)
        return out
    }

    /** The public-key blob carried by the public line and inside the PEM. */
    fun publicBlob(pub: ByteArray): ByteArray =
        sshString("ssh-ed25519".toByteArray(Charsets.UTF_8)) + sshString(pub)

    /** `ssh-ed25519 AAAA… gentle-companion@<device>` — what --add-key trusts. */
    fun publicKeyLine(pub: ByteArray, comment: String): String =
        "ssh-ed25519 " +
            Base64.getEncoder().encodeToString(publicBlob(pub)) +
            " " + comment

    /** The base64 blob of a public line, or null when it is not one. */
    fun publicBlobFromLine(line: String): ByteArray? {
        val body = line.trim().split(Regex("\\s+")).getOrNull(1) ?: return null
        val blob = runCatching { Base64.getDecoder().decode(body) }.getOrNull() ?: return null
        return blob
    }

    /** 32-byte public key out of an `ssh-ed25519 …` public blob. */
    fun ed25519PublicFromBlob(blob: ByteArray): ByteArray? {
        val buf = ByteBuffer.wrap(blob)
        val type = readString(buf) ?: return null
        if (!type.contentEquals("ssh-ed25519".toByteArray(Charsets.UTF_8))) return null
        val pub = readString(buf) ?: return null
        if (pub.size != 32 || buf.hasRemaining()) return null
        return pub
    }

    /**
     * A full OpenSSH private PEM for the seed. Unencrypted (none/none), which
     * is what the russh core expects; the file lives in app-private storage.
     */
    fun privatePem(pub: ByteArray, seed: ByteArray, comment: String): String {
        val check = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val commentBytes = comment.toByteArray(Charsets.UTF_8)
        val priv = java.io.ByteArrayOutputStream().also { out ->
            out.write(check); out.write(check)
            out.write(sshString("ssh-ed25519".toByteArray(Charsets.UTF_8)))
            out.write(sshString(pub))
            out.write(sshString(seed + pub))
            out.write(sshString(commentBytes))
            // Padding: 1,2,3,… until the section is a multiple of 16.
            var pad = 1
            while (out.size() % 16 != 0) out.write(pad++)
        }.toByteArray()

        val body = java.io.ByteArrayOutputStream().also { out ->
            out.write("openssh-key-v1\u0000".toByteArray(Charsets.UTF_8))
            out.write(sshString("none".toByteArray()))          // cipher
            out.write(sshString("none".toByteArray()))          // kdf
            out.write(sshString(ByteArray(0)))                  // kdf options
            out.write(byteArrayOf(0, 0, 0, 1))                  // one key
            out.write(sshString(publicBlob(pub)))
            out.write(sshString(priv))
        }.toByteArray()

        val base64 = Base64.getEncoder().encodeToString(body)
        val wrapped = base64.chunked(70).joinToString("\n")
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$wrapped\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    /** Decoded pieces of an OpenSSH ed25519 private PEM, or null. */
    data class Decoded(
        val keyType: String,
        val pub: ByteArray,
        val priv: ByteArray,
        val comment: String,
    )

    /** Parse our own (or ssh-keygen's) unencrypted OpenSSH ed25519 PEM. */
    fun decodePrivatePem(pem: String): Decoded? {
        val body = pem.lines()
            .filter { it.isNotBlank() && !it.startsWith("-----") }
            .joinToString("")
        val data = runCatching { Base64.getMimeDecoder().decode(body) }.getOrNull() ?: return null
        val buf = ByteBuffer.wrap(data)
        val magic = ByteArray("openssh-key-v1".length)
        return runCatching {
            buf.get(magic)
            if (!String(magic, Charsets.UTF_8).startsWith("openssh-key-v1")) return null
            if (buf.get() != 0.toByte()) return null // the magic is NUL-terminated
            val cipher = readString(buf) ?: return null
            val kdf = readString(buf) ?: return null
            if (!cipher.contentEquals("none".toByteArray()) ||
                !kdf.contentEquals("none".toByteArray())
            ) {
                return null // encrypted keys are out of scope for this encoder
            }
            readString(buf) // kdf options
            val nkeys = buf.int
            if (nkeys < 1) return null
            val pubBlob = readString(buf) ?: return null
            val privSection = readString(buf) ?: return null
            val p = ByteBuffer.wrap(privSection)
            p.position(p.position() + 4 + 4) // two checkints, not verified further
            val keyType = readString(p) ?: return null
            val pub = readString(p) ?: return null
            val priv = readString(p) ?: return null
            val commentBytes = readString(p) ?: return null
            if (pub.size != 32 || priv.size != 64) return null
            Decoded(
                keyType = String(keyType, Charsets.UTF_8),
                pub = pub,
                priv = priv,
                comment = String(commentBytes, Charsets.UTF_8),
            ).also {
                if (!ed25519PublicFromBlob(pubBlob).contentEquals(pub)) return null
            }
        }.getOrNull()
    }

    private fun readString(buf: ByteBuffer): ByteArray? {
        if (buf.remaining() < 4) return null
        val len = buf.int
        if (len < 0 || len > buf.remaining()) return null
        val out = ByteArray(len)
        buf.get(out)
        return out
    }
}
