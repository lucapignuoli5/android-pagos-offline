package com.example.prototipopagosoffline.utils

import androidx.room.withTransaction
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.MobileTerminal
import com.example.prototipopagosoffline.database.TrustedPublicKey
import com.example.prototipopagosoffline.network.ApiService
import com.example.prototipopagosoffline.network.KeyRegistrationRequest
import com.example.prototipopagosoffline.network.LoginResponse
import com.example.prototipopagosoffline.protocol.ProtocolV2
import com.google.gson.JsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

class MerchantProvisioner(
    private val database: AppDatabase,
    private val api: ApiService,
    private val keyManager: TerminalKeyManager = TerminalKeyManager(),
    private val now: () -> Instant = { Instant.now() }
) {
    suspend fun provision(login: LoginResponse): MobileTerminal {
        val terminalId = "mobile-terminal-${login.user_id}"
        val merchantId = "merchant-${login.user_id}"
        val keyId = "mobile-pos-key-${sha256(terminalId).take(24)}"
        val key = keyManager.ensureKey(terminalId, keyId)
        val proof = buildTerminalRegistrationProof(
            login.user_id,
            terminalId,
            key,
            now(),
            registrationNonce()
        )
        val response = api.registerKey(
            KeyRegistrationRequest(
                public_key_b64 = key.publicKeyBase64,
                proof_payload_b64 = Base64.getEncoder().encodeToString(proof),
                proof_signature_b64 = keyManager.sign(key.alias, proof)
            )
        )
        require(response.isSuccessful) { "No se pudo registrar la clave del terminal" }
        val registered = requireNotNull(response.body()) { "Respuesta de clave vacia" }
        require(registered.key_id == key.keyId && registered.fingerprint == key.fingerprint)
        require(registered.scope_id == terminalId && registered.purpose == "pos")

        val trustedResponse = api.getTrustedKeys("wallet")
        require(trustedResponse.isSuccessful) { "No se pudieron actualizar las claves de billetera" }
        val trustedWallets = requireNotNull(trustedResponse.body())
        val terminal = MobileTerminal(
            terminalId = terminalId,
            merchantId = merchantId,
            userId = login.user_id,
            posKeyId = key.keyId,
            posKeyFingerprint = key.fingerprint,
            keyStoreAlias = key.alias,
            publicKeyBase64 = key.publicKeyBase64,
            createdAtEpochSeconds = now().epochSecond
        )
        database.withTransaction {
            trustedWallets.forEach { trusted ->
                require(trusted.purpose == "wallet" && trusted.key_id.isNotBlank())
                database.nfcExchangeDao().insertTrustedKey(
                    TrustedPublicKey(
                        keyId = trusted.key_id,
                        fingerprint = trusted.fingerprint,
                        publicKeyBase64 = trusted.public_key_b64,
                        ownerType = "wallet"
                    )
                )
            }
            database.mobileTerminalDao().upsert(terminal)
        }
        return terminal
    }

    private fun registrationNonce(): String {
        val value = ByteArray(16).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun buildTerminalRegistrationProof(
    userId: Int,
    terminalId: String,
    key: TerminalKeyMaterial,
    issuedAt: Instant,
    nonce: String
): ByteArray {
    require(userId > 0 && terminalId.isNotBlank() && nonce.length in 16..120)
    val timestampFormat = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)
    val proof = JsonObject().apply {
        addProperty("version", ProtocolV2.VERSION)
        addProperty("type", "key_registration")
        addProperty("key_id", key.keyId)
        addProperty("purpose", "pos")
        addProperty("owner_user_id", userId)
        addProperty("scope_id", terminalId)
        addProperty("fingerprint", key.fingerprint)
        addProperty("nonce", nonce)
        addProperty("issued_at", timestampFormat.format(issuedAt))
    }
    return ProtocolV2.canonicalJson(proof).toByteArray(Charsets.UTF_8)
}
