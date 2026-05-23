package com.newoether.agora.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ShellClient(
    private val serverUrl: String,
    private val apiKey: String,
    cachedPublicKey: String = ""
) {
    private var serverPublicKey: java.security.PublicKey? = null
    private var supportsEncryption: Boolean? = null
    private var currentAesKey: ByteArray? = null
    private var currentKeyPair: java.security.KeyPair? = null

    init {
        if (cachedPublicKey.isNotBlank()) {
            try {
                serverPublicKey = ShellCrypto.decodePublicKey(cachedPublicKey)
                supportsEncryption = true
            } catch (_: Exception) {
                supportsEncryption = null // probe fresh
            }
        }
    }

    suspend fun probeEncryption(): Boolean {
        if (supportsEncryption != null) return supportsEncryption!!
        return try {
            val response = com.newoether.agora.api.HttpClient.fetchModels(
                "$serverUrl/public-key",
                if (apiKey.isNotBlank()) mapOf("Authorization" to "Bearer $apiKey") else emptyMap()
            )
            if (response != null) {
                val json = Json.parseToJsonElement(response).jsonObject
                val pubKeyStr = json["public_key"]?.jsonPrimitive?.content
                val nonce = json["nonce"]?.jsonPrimitive?.content
                val sig = json["signature"]?.jsonPrimitive?.content
                if (pubKeyStr != null && nonce != null && sig != null) {
                    // Verify signature to prevent MITM
                    if (apiKey.isBlank() || verifyPublicKey(pubKeyStr, nonce, sig)) {
                        serverPublicKey = ShellCrypto.decodePublicKey(pubKeyStr)
                        supportsEncryption = true
                    } else {
                        DebugLog.e("ShellClient", "Public key signature verification failed")
                        supportsEncryption = false
                    }
                } else {
                    supportsEncryption = false
                }
            } else {
                supportsEncryption = false
            }
            supportsEncryption!!
        } catch (e: Exception) {
            DebugLog.w("ShellClient", "Encryption probe failed: ${e.message}")
            supportsEncryption = false
            false
        }
    }

    private fun verifyPublicKey(pubKey: String, nonce: String, sig: String): Boolean {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(apiKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val message = "$nonce|$pubKey"
        val expected = mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return java.security.MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            sig.toByteArray(Charsets.UTF_8)
        )
    }

    fun getServerPublicKeyBase64(): String? {
        return serverPublicKey?.let { ShellCrypto.encodePublicKey(it) }
    }

    fun isEncryptionAvailable(): Boolean = supportsEncryption == true && serverPublicKey != null

    data class PreparedRequest(
        val body: String,
        val headers: Map<String, String>,
        val isEncrypted: Boolean,
        val serverUrl: String
    )

    fun prepareRequest(
        command: String,
        timeoutMs: Int,
        workdir: String
    ): PreparedRequest {
        val jsonBody = buildJsonBody(command, timeoutMs, workdir)

        if (!isEncryptionAvailable()) {
            val headers = mutableMapOf("Content-Type" to "application/json")
            if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"
            return PreparedRequest(jsonBody, headers, false, serverUrl)
        }

        // Generate ephemeral key pair and derive AES key
        val ephemeralKP = ShellCrypto.generateEphemeralKeyPair()
        val aesKey = ShellCrypto.deriveAesKey(ephemeralKP.private, serverPublicKey!!)
        currentAesKey = aesKey
        currentKeyPair = ephemeralKP

        // Encrypt body
        val encryptedBody = ShellCrypto.encrypt(aesKey, jsonBody.toByteArray(Charsets.UTF_8))
        val bodyBytes = encryptedBody.toByteArray(Charsets.UTF_8)
        val bodySha256 = ShellCrypto.sha256Hex(bodyBytes)
        val timestamp = System.currentTimeMillis() / 1000
        val nonce = ShellCrypto.generateNonce()
        val signature = ShellCrypto.sign(apiKey, timestamp, "POST", "/execute", bodySha256, nonce)
        val clientPubKey = ShellCrypto.encodePublicKey(ephemeralKP.public)

        val headers = mapOf(
            "Content-Type" to "application/octet-stream",
            "Authorization" to "Bearer $apiKey",
            "X-Timestamp" to timestamp.toString(),
            "X-Signature" to signature,
            "X-Nonce" to nonce,
            "X-Encryption" to "v1",
            "X-Client-Public-Key" to clientPubKey
        )

        return PreparedRequest(encryptedBody, headers, true, serverUrl)
    }

    fun decryptSseData(encryptedData: String): String {
        val key = currentAesKey ?: throw IllegalStateException("No session key")
        return String(ShellCrypto.decrypt(key, encryptedData), Charsets.UTF_8)
    }

    fun getSessionKey(): ByteArray? = currentAesKey

    private fun buildJsonBody(command: String, timeoutMs: Int, workdir: String): String {
        val sb = StringBuilder()
        sb.append("{\"command\":\"")
        sb.append(escapeJson(command))
        sb.append("\",\"timeout_ms\":")
        sb.append(timeoutMs)
        if (workdir.isNotBlank()) {
            sb.append(",\"workdir\":\"")
            sb.append(escapeJson(workdir))
            sb.append("\"")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
