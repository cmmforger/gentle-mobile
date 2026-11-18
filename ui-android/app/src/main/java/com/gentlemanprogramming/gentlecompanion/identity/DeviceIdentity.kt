package com.gentlemanprogramming.gentlecompanion.identity

import android.content.Context
import android.os.Build
import java.io.File

/**
 * The device identity key: generated on this phone at first launch, kept in
 * app-private storage (`filesDir`, mode 600), never in shared/external
 * storage. A legacy key found at the old external path is migrated once and
 * the external copy is deleted. The public line is what the user pastes into
 * `gentle-companion-pair --add-key` on the host — no key ever leaves the
 * phone through files or adb pushes.
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

    fun ensure(context: Context): Identity {
        val key = File(context.filesDir, KEY_FILE)
        val pub = File(context.filesDir, PUB_FILE)
        val legacyDir = context.getExternalFilesDir(null)
        val legacyKey = legacyDir?.let { File(it, KEY_FILE) }
        val legacyPub = legacyDir?.let { File(it, PUB_FILE) }
        val comment = deviceComment()

        if (key.exists()) {
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

        if (legacyKey != null && legacyKey.exists()) {
            // One-time migration: private storage in, external copy out.
            key.parentFile?.mkdirs()
            key.writeText(legacyKey.readText())
            restrictivePermissions(key)
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
        val seed = Ed25519Identity.newSeed()
        val pubBytes = Ed25519Identity.publicFromSeed(seed)
        key.writeText(OpenSshKey.privatePem(pubBytes, seed, comment))
        restrictivePermissions(key)
        val line = OpenSshKey.publicKeyLine(pubBytes, comment)
        pub.writeText(line + "\n")
        return Identity(key.absolutePath, line, migratedFromLegacy = false, generated = true)
    }

    /** `gentle-companion@<device>` — the comment the host's --list shows. */
    fun deviceComment(): String {
        val model = Build.MODEL?.trim()?.replace(Regex("\\s+"), "-").orEmpty()
        return if (model.isEmpty()) "gentle-companion@android" else "gentle-companion@$model"
    }

    private fun restrictivePermissions(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }
}
