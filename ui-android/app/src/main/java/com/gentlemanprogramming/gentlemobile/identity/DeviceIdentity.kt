package com.gentlemanprogramming.gentlemobile.identity

import android.content.Context
import android.os.Build
import java.io.File

/**
 * The device identity key: generated on this phone at first launch, kept in
 * app-private storage (`filesDir`, mode 600), never in shared/external
 * storage. A legacy key found at the old external path is migrated once and
 * the external copy is deleted. The public line is what the user pastes into
 * `gentle-mobile-pair --add-key` on the host — no key ever leaves the
 * phone through files or adb pushes.
 *
 * Regeneration (T18) replaces the key in place and clears the pinned hosts:
 * a new key means new trust, so every host must confirm the new key's
 * fingerprint before it connects again.
 */
object DeviceIdentity {

    data class Identity(
        val privateKeyPath: String,
        val publicKeyLine: String,
        /** True when this run moved a legacy external key into private storage. */
        val migratedFromLegacy: Boolean,
        /** True when this run generated a brand-new keypair. */
        val generated: Boolean,
    )

    const val KEY_FILE = "client_ed25519"
    const val PUB_FILE = "client_ed25519.pub"

    /** The core's pin store lives beside the key; regeneration clears it. */
    const val KNOWN_HOSTS_FILE = "known_hosts"

    fun ensure(context: Context): Identity {
        val dir = context.filesDir
        val comment = deviceComment()
        existingIdentity(dir, comment)?.let { return it }

        // One-time migration: private storage in, external copy out.
        val legacyDir = context.getExternalFilesDir(null)
        val legacyKey = legacyDir?.let { File(it, KEY_FILE) }
        if (legacyKey != null && legacyKey.exists()) {
            val key = File(dir, KEY_FILE)
            val pub = File(dir, PUB_FILE)
            key.parentFile?.mkdirs()
            key.writeText(legacyKey.readText())
            restrictivePermissions(key)
            val legacyPub = legacyDir?.let { File(it, PUB_FILE) }
            val line = when {
                legacyPub != null && legacyPub.exists() -> legacyPub.readText().trim()
                else -> OpenSshKey.decodePrivatePem(key.readText())?.let {
                    OpenSshKey.publicKeyLine(it.pub, comment)
                } ?: error("The legacy identity key is not a valid OpenSSH ed25519 key.")
            }
            pub.writeText(line + "\n")
            legacyKey.delete()
            legacyPub?.delete()
            return Identity(key.absolutePath, line, migratedFromLegacy = true, generated = false)
        }

        // First launch: generate on this phone.
        return createAt(dir, comment)
    }

    /**
     * A new ed25519 identity replacing the stored one (T18, Regenerate):
     * the old key is destroyed, the pinned hosts are cleared — a new key
     * means new trust — and the caller owes the user the "the host must
     * trust the new key" message with the copy action.
     */
    fun regenerate(context: Context): Identity = regenerateAt(context.filesDir, deviceComment())

    /** The stored identity, or `null` when this directory has no key yet. */
    private fun existingIdentity(dir: File, comment: String): Identity? {
        val key = File(dir, KEY_FILE)
        val pub = File(dir, PUB_FILE)
        if (!key.exists()) return null
        val line = if (pub.exists()) {
            pub.readText().trim()
        } else {
            // Recover the public line from the private PEM (same private
            // storage; no network, no shared storage).
            val decoded = OpenSshKey.decodePrivatePem(key.readText())
                ?: error("The existing identity key is not a valid OpenSSH ed25519 key.")
            OpenSshKey.publicKeyLine(decoded.pub, comment).also { pub.writeText(it + "\n") }
        }
        return Identity(key.absolutePath, line, migratedFromLegacy = false, generated = false)
    }

    /** A brand-new ed25519 identity written into `dir`. File-based and pure
     * enough to be unit-tested: no Context, no Android classes. */
    internal fun createAt(dir: File, comment: String): Identity {
        val seed = Ed25519Identity.newSeed()
        val pubBytes = Ed25519Identity.publicFromSeed(seed)
        val key = File(dir, KEY_FILE)
        val pub = File(dir, PUB_FILE)
        key.parentFile?.mkdirs()
        key.writeText(OpenSshKey.privatePem(pubBytes, seed, comment))
        restrictivePermissions(key)
        val line = OpenSshKey.publicKeyLine(pubBytes, comment)
        pub.writeText(line + "\n")
        return Identity(key.absolutePath, line, migratedFromLegacy = false, generated = true)
    }

    /**
     * Destroy the identity and the pin store in `dir` — nothing else. The
     * host side keeps trusting the old key until `gentle-mobile-pair
     * --revoke` runs there; this only wipes what this device holds.
     */
    internal fun wipeAt(dir: File) {
        File(dir, KEY_FILE).delete()
        File(dir, PUB_FILE).delete()
        File(dir, KNOWN_HOSTS_FILE).delete()
    }

    /** Regenerate inside `dir`: wipe, then write a fresh keypair. */
    internal fun regenerateAt(dir: File, comment: String): Identity {
        wipeAt(dir)
        return createAt(dir, comment)
    }

    /** `gentle-mobile@<device>` — the comment the host's --list shows. */
    fun deviceComment(): String {
        val model = Build.MODEL?.trim()?.replace(Regex("\\s+"), "-").orEmpty()
        return if (model.isEmpty()) "gentle-mobile@android" else "gentle-mobile@$model"
    }

    private fun restrictivePermissions(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }
}
