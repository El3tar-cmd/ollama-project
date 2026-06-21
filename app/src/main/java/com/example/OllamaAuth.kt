package com.example

import android.content.Context
import android.util.Base64
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Implements the `ollama login` authentication flow in pure Kotlin,
 * without requiring the binary to support the `login` sub-command and
 * without any external Bouncy Castle dependency.
 *
 * Flow:
 *  1. Generate Ed25519 key pair via Java built-in JCE (Android BC provider)
 *  2. Persist keys to $HOME/.ollama/ — the Ollama daemon reads them automatically
 *  3. Return the https://ollama.com/connect?name=…&key=… URL for browser auth
 *  4. After the user authorizes in the browser the daemon is ready for cloud models
 */
class OllamaAuth(private val context: Context) {

    private val ollamaDir  get() = File(context.filesDir, ".ollama").also { it.mkdirs() }
    private val privKeyFile get() = File(ollamaDir, "id_ed25519")
    private val pubKeyFile  get() = File(ollamaDir, "id_ed25519.pub")

    fun hasStoredKeys(): Boolean = privKeyFile.exists() && pubKeyFile.exists()

    fun clearKeys() { privKeyFile.delete(); pubKeyFile.delete() }

    /**
     * Generate fresh Ed25519 keys, persist them, and return the connect URL.
     *
     * Raw key extraction rationale:
     *   X.509 public key DER  (44 bytes):  30 2a 30 05 06 03 2b 65 70 03 21 00 [32 bytes]
     *   PKCS8 private key DER (48 bytes):  30 2e 02 01 00 30 05 06 03 2b 65 70 04 22 04 20 [32 bytes]
     * → Last 32 bytes of each DER blob are the raw key material.
     */
    fun generateConnectUrl(deviceName: String = "devhive"): String {
        // Use our pure-Kotlin Ed25519 — works on every Android version
        val (rawPriv, rawPub) = Ed25519.generateKeyPair()

        val typeBytes = "ssh-ed25519".toByteArray(Charsets.US_ASCII)
        val pubWire   = sshWire(typeBytes, rawPub)
        val pubB64    = Base64.encodeToString(pubWire, Base64.NO_WRAP)

        savePubKey(pubB64, deviceName)
        savePrivKey(rawPriv, rawPub, typeBytes, pubWire, deviceName)

        // NO_PADDING strips trailing '=' — Ollama's connect page rejects padded base64
        val keyParam = Base64.encodeToString(
            "ssh-ed25519 $pubB64".toByteArray(Charsets.US_ASCII),
            Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "https://ollama.com/connect?name=${deviceName.replace(" ", "%20")}&key=$keyParam"
    }

    fun readPublicKeyLine(): String? =
        if (pubKeyFile.exists()) pubKeyFile.readText().trim() else null

    private fun sshWire(vararg chunks: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(chunks.sumOf { 4 + it.size })
        chunks.forEach { buf.putInt(it.size); buf.put(it) }
        return buf.array()
    }

    private fun savePubKey(pubB64: String, comment: String) {
        pubKeyFile.writeText("ssh-ed25519 $pubB64 $comment\n")
    }

    private fun savePrivKey(
        rawPriv: ByteArray, rawPub: ByteArray,
        typeBytes: ByteArray, pubWire: ByteArray,
        comment: String
    ) {
        val commentBytes = comment.toByteArray(Charsets.UTF_8)
        val checkInt     = SecureRandom().nextInt()
        val privKey64    = rawPriv + rawPub

        val privBlob = ByteBuffer.allocate(
            4 + 4 + 4 + typeBytes.size + 4 + rawPub.size + 4 + privKey64.size + 4 + commentBytes.size + 8
        ).run {
            putInt(checkInt); putInt(checkInt)
            putInt(typeBytes.size);    put(typeBytes)
            putInt(rawPub.size);       put(rawPub)
            putInt(privKey64.size);    put(privKey64)
            putInt(commentBytes.size); put(commentBytes)
            var pad = 1; while (position() % 8 != 0) { put(pad++.toByte()) }
            array().copyOf(position())
        }

        val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        val none  = "none".toByteArray(Charsets.US_ASCII)
        val outer = ByteBuffer.allocate(
            magic.size + (4 + none.size) * 2 + 4 + 4 + 4 + pubWire.size + 4 + privBlob.size
        ).run {
            put(magic)
            putInt(none.size); put(none)
            putInt(none.size); put(none)
            putInt(0); putInt(1)
            putInt(pubWire.size);  put(pubWire)
            putInt(privBlob.size); put(privBlob)
            array().copyOf(position())
        }

        val pem = Base64.encodeToString(outer, Base64.DEFAULT)
        privKeyFile.apply {
            writeText("-----BEGIN OPENSSH PRIVATE KEY-----\n${pem}-----END OPENSSH PRIVATE KEY-----\n")
            setReadable(false, false)
            setReadable(true, true)
        }
    }
}
