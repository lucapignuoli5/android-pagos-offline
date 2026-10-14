package com.example.prototipopagosoffline.utils

import androidx.room.withTransaction
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.LocalOfflineAuthorization
import com.example.prototipopagosoffline.database.OfflineWallet
import com.example.prototipopagosoffline.database.TrustedPublicKey
import com.example.prototipopagosoffline.network.ApiService
import com.example.prototipopagosoffline.network.KeyRegistrationRequest
import com.example.prototipopagosoffline.network.LegacyInventoryEntry
import com.example.prototipopagosoffline.network.LegacyInventoryRequest
import com.example.prototipopagosoffline.network.LoginResponse
import com.example.prototipopagosoffline.network.OfflineAuthorizationRequest
import com.example.prototipopagosoffline.protocol.ProtocolV2
import com.example.prototipopagosoffline.protocol.ProtocolV3
import com.example.prototipopagosoffline.BuildConfig
import com.google.gson.JsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

class OfflineProvisioner(
    private val database: AppDatabase,
    private val api: ApiService,
    private val paymentCoordinator: OfflinePaymentCoordinator = OfflinePaymentCoordinator(database),
    private val now: () -> Instant = { Instant.now() },
    private val requestStore: TokenManager? = null
) {
    suspend fun provision(login: LoginResponse) {
        require(login.wallet_id.isNotBlank())
        val keyId = "wallet-key:${sha256(login.wallet_id).take(24)}"
        val key = paymentCoordinator.registerWalletKey(login.wallet_id, keyId)
        val proofBytes = buildKeyRegistrationProof(login, key, now(), registrationNonce())
        val keyResponse = api.registerKey(
            KeyRegistrationRequest(
                public_key_b64 = key.publicKeyBase64,
                proof_payload_b64 = Base64.getEncoder().encodeToString(proofBytes),
                proof_signature_b64 = paymentCoordinator.signWithWalletKey(key.alias, proofBytes)
            )
        )
        require(keyResponse.isSuccessful) { "No se pudo registrar la clave de billetera" }
        val registered = requireNotNull(keyResponse.body()) { "Respuesta de clave vacia" }
        require(registered.key_id == key.keyId && registered.fingerprint == key.fingerprint)
        require(registered.scope_id == login.wallet_id && registered.purpose == "wallet")

        val trustedPosResponse = api.getTrustedKeys("pos")
        require(trustedPosResponse.isSuccessful) { "No se pudieron actualizar las claves POS" }
        database.withTransaction {
            database.nfcExchangeDao().revokeInventory("pos")
            requireNotNull(trustedPosResponse.body()).forEach { trusted ->
                require(trusted.purpose == "pos" && trusted.key_id.isNotBlank())
                database.nfcExchangeDao().insertTrustedKey(
                    TrustedPublicKey(
                        keyId = trusted.key_id,
                        fingerprint = trusted.fingerprint,
                        publicKeyBase64 = trusted.public_key_b64,
                        ownerType = "pos",
                        scopeId = trusted.scope_id
                    )
                )
            }
        }

        registerLegacyInventory(login.wallet_id)

        val balanceResponse = api.getWalletBalance(login.wallet_id)
        require(balanceResponse.isSuccessful) { "No se pudo consultar el saldo" }
        val balance = requireNotNull(balanceResponse.body()) { "Respuesta de saldo vacia" }
        require(balance.wallet_id == login.wallet_id && balance.currency == ProtocolV2.CURRENCY)
        require(balance.confirmed_balance_cents >= 0)

        val currentAuthorization = database.authorizationDao()
            .findActiveForProvisioning(login.wallet_id, key.keyId, now().epochSecond)
        if (currentAuthorization != null) {
            saveWallet(balance.confirmed_balance_cents, login.wallet_id)
            return
        }

        require(BuildConfig.ISSUER_PUBLIC_KEY_B64.isNotBlank() && BuildConfig.ISSUER_KEY_ID.isNotBlank()) {
            "Confianza del emisor v3 no configurada"
        }
        val authorizationResponse = api.createOfflineAuthorizationV3(
            OfflineAuthorizationRequest(
                request_id = requestStore?.pendingRequestId("authorization", login.wallet_id) ?: UUID.nameUUIDFromBytes(
                    "${login.wallet_id}:${key.keyId}:${now().epochSecond / 86400}".toByteArray(Charsets.UTF_8)
                ).toString(),
                wallet_id = login.wallet_id,
                wallet_key_id = key.keyId,
                max_payment_cents = ProtocolV2.MAX_AMOUNT_CENTS,
                max_payments = 10
            )
        )
        require(authorizationResponse.isSuccessful) { "No se pudo obtener autorizacion offline" }
        val authorization = requireNotNull(authorizationResponse.body()) { "Respuesta de autorizacion vacia" }
        val grantBytes = Base64.getDecoder().decode(authorization.authorization_envelope_b64)
        val grant = ProtocolV3.parseSignedEnvelope(grantBytes)
        require(ProtocolV3.verifyAuthorization(grant,
            com.example.prototipopagosoffline.protocol.IssuerTrust.publicKey(grant.payload)))
        val payload = grant.payload
        require(payload["authorization_id"].asString == authorization.authorization_id)
        require(payload["wallet_id"].asString == login.wallet_id)
        require(payload["wallet_key_id"].asString == key.keyId)
        require(payload["currency"].asString == ProtocolV3.CURRENCY)

        database.withTransaction {
            saveWallet(balance.confirmed_balance_cents, login.wallet_id)
            database.authorizationDao().upsertAuthorization(
                LocalOfflineAuthorization(
                    id = authorization.authorization_id,
                    walletId = authorization.wallet_id,
                    walletKeyId = payload["wallet_key_id"].asString,
                    maxPaymentCents = payload["max_payment_cents"].asLong,
                    remainingAmountCents = authorization.authorized_total_cents,
                    remainingPayments = payload["max_payments"].asInt,
                    issuedAtEpochSeconds = Instant.parse(authorization.issued_at).epochSecond,
                    expiresAtEpochSeconds = Instant.parse(authorization.spend_expires_at).epochSecond,
                    authorizationEnvelope = grantBytes
                )
            )
        }
        requestStore?.completeRequest("authorization", login.wallet_id)
    }

    private suspend fun saveWallet(confirmedBalance: Long, walletId: String) {
        database.walletDao().insertWallet(
            OfflineWallet(
                id = walletId,
                confirmedBalanceCents = confirmedBalance,
                reservedBalanceCents = 0,
                currency = ProtocolV2.CURRENCY
            )
        )
        check(database.walletDao().updateConfirmedBalance(
            walletId, confirmedBalance, ProtocolV2.CURRENCY
        ) == 1)
    }

    private suspend fun registerLegacyInventory(walletId: String) {
        while (true) {
            val pending = database.nfcExchangeDao().getLegacyInventoryPending(walletId, 100)
            if (pending.isEmpty()) return
            val entries = pending.map { exchange ->
                val receipt = ProtocolV2.parseSignedEnvelope(requireNotNull(exchange.receiptEnvelope))
                LegacyInventoryEntry(
                    operation_id = exchange.operationId,
                    authorization_id = requireNotNull(exchange.authorizationId),
                    receipt_digest = ProtocolV2.payloadDigest(receipt.payload)
                )
            }
            val response = api.registerLegacyInventory(LegacyInventoryRequest(walletId, entries))
            require(response.isSuccessful && response.body()?.total == entries.size) {
                "No se pudo registrar el inventario historico v2"
            }
            check(database.nfcExchangeDao().markLegacyInventoryRegistered(
                pending.map { it.operationId }
            ) == pending.size)
        }
    }

    private fun registrationNonce(): String {
        val value = ByteArray(16).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

}

internal fun buildKeyRegistrationProof(
    login: LoginResponse,
    key: WalletKeyMaterial,
    issuedAt: Instant,
    nonce: String
): ByteArray {
    require(nonce.length in 16..120)
    val timestampFormat = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)
    val proof = JsonObject().apply {
        addProperty("version", ProtocolV2.VERSION)
        addProperty("type", "key_registration")
        addProperty("key_id", key.keyId)
        addProperty("purpose", "wallet")
        addProperty("owner_user_id", login.user_id)
        addProperty("scope_id", login.wallet_id)
        addProperty("fingerprint", key.fingerprint)
        addProperty("nonce", nonce)
        addProperty("issued_at", timestampFormat.format(issuedAt))
    }
    return ProtocolV2.canonicalJson(proof).toByteArray(Charsets.UTF_8)
}
