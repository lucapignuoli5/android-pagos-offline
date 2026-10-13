package com.example.prototipopagosoffline.utils

import androidx.room.withTransaction
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.WalletKeyMetadata
import com.example.prototipopagosoffline.protocol.ExchangeState
import com.example.prototipopagosoffline.protocol.ProtocolV2
import com.google.gson.JsonObject
import java.time.Instant

sealed class ConfirmationResult {
    data class Created(val operationId: String, val receiptEnvelope: ByteArray) : ConfirmationResult()
    data class AlreadyCreated(val operationId: String, val receiptEnvelope: ByteArray) : ConfirmationResult()
}

class OfflinePaymentCoordinator(
    private val database: AppDatabase,
    private val keyManager: WalletKeyManager = WalletKeyManager(),
    private val now: () -> Instant = { Instant.now() }
) {
    suspend fun confirm(operationId: String, walletId: String): ConfirmationResult {
        val exchange = database.nfcExchangeDao().getExchange(operationId)
            ?: throw IllegalArgumentException("Solicitud no encontrada")
        exchange.receiptEnvelope?.let { return ConfirmationResult.AlreadyCreated(operationId, it) }
        require(exchange.state == ExchangeState.REQUEST_RECEIVED.name) { "Solicitud no disponible para confirmar" }
        val request = ProtocolV2.parseSignedEnvelope(exchange.requestEnvelope).payload
        require(request["type"].asString == "payment_request")
        require(request["operation_id"].asString == operationId)
        val expiresAt = Instant.parse(request["expires_at"].asString)
        require(expiresAt.isAfter(now())) { "Solicitud vencida" }
        require(request["amount_cents"].asLong > 0)
        require(request["currency"].asString == ProtocolV2.CURRENCY)
        require(walletId.isNotBlank())

        val amount = request["amount_cents"].asLong
        val nowEpoch = now().epochSecond
        val authorization = database.authorizationDao().findEligible(walletId, amount, nowEpoch, expiresAt.epochSecond)
            ?: throw IllegalStateException("No hay autorizacion o saldo gastable suficiente")
        val metadata = database.authorizationDao().getKeyMetadata(walletId, authorization.walletKeyId)
            ?: throw IllegalStateException("Clave de billetera no disponible")
        require(!metadata.retired)

        val receipt = JsonObject().apply {
            addProperty("version", ProtocolV2.VERSION)
            addProperty("type", "payment_receipt")
            addProperty("operation_id", operationId)
            addProperty("merchant_id", request["merchant_id"].asString)
            addProperty("terminal_id", request["terminal_id"].asString)
            addProperty("amount_cents", amount)
            addProperty("currency", ProtocolV2.CURRENCY)
            addProperty("nonce", request["nonce"].asString)
            addProperty("issued_at", request["issued_at"].asString)
            addProperty("expires_at", request["expires_at"].asString)
            addProperty("wallet_id", walletId)
            addProperty("offline_authorization_id", authorization.id)
            addProperty("wallet_key_id", metadata.keyId)
            addProperty("wallet_key_fingerprint", metadata.fingerprint)
            addProperty("request_digest", ProtocolV2.payloadDigest(request))
        }
        ProtocolV2.validatePayload(receipt)
        val payloadBytes = ProtocolV2.canonicalJson(receipt).toByteArray(Charsets.UTF_8)
        val envelope = ProtocolV2.encodeSignedEnvelope(receipt, keyManager.sign(metadata.keyStoreAlias, payloadBytes))

        var created = true
        var resultingEnvelope = envelope
        database.withTransaction {
            val current = database.nfcExchangeDao().getExchange(operationId)
                ?: throw IllegalArgumentException("Solicitud no encontrada")
            current.receiptEnvelope?.let {
                created = false
                resultingEnvelope = it
                return@withTransaction
            }
            require(current.state == ExchangeState.REQUEST_RECEIVED.name)
            require(database.walletDao().reserve(walletId, amount) == 1)
            require(database.authorizationDao().consume(authorization.id, amount, nowEpoch, expiresAt.epochSecond) == 1)
            require(
                database.nfcExchangeDao().reserveAndSetReceipt(
                    operationId, envelope, walletId, authorization.id, amount,
                    request["merchant_id"].asString, request["terminal_id"].asString
                ) == 1
            )
        }
        return if (created) ConfirmationResult.Created(operationId, resultingEnvelope)
        else ConfirmationResult.AlreadyCreated(operationId, resultingEnvelope)
    }

    suspend fun registerWalletKey(walletId: String, keyId: String): WalletKeyMaterial {
        val material = keyManager.ensureKey(walletId, keyId)
        database.authorizationDao().insertKeyMetadata(
            WalletKeyMetadata(
                keyId = material.keyId,
                walletId = walletId,
                keyStoreAlias = material.alias,
                publicKeyBase64 = material.publicKeyBase64,
                fingerprint = material.fingerprint,
                createdAtEpochSeconds = now().epochSecond
            )
        )
        return material
    }

    fun signWithWalletKey(alias: String, payloadBytes: ByteArray): String =
        keyManager.sign(alias, payloadBytes)
}
