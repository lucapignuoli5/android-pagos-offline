package com.example.prototipopagosoffline.utils

import androidx.room.withTransaction
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.MobilePosOperation
import com.example.prototipopagosoffline.protocol.MessageType
import com.example.prototipopagosoffline.protocol.ProtocolV2
import com.google.gson.JsonObject
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

data class MobilePaymentRequest(val operationId: String, val envelope: ByteArray)

class MobilePosCoordinator(
    private val database: AppDatabase,
    private val keyManager: TerminalKeyManager = TerminalKeyManager(),
    private val now: () -> Instant = { Instant.now() }
) {
    suspend fun createRequest(amountCents: Long, terminalId: String): MobilePaymentRequest {
        require(amountCents in 1..ProtocolV2.MAX_AMOUNT_CENTS) { "Importe fuera del limite" }
        val terminal = database.mobileTerminalDao().getById(terminalId)
            ?: throw IllegalStateException("Terminal no aprovisionado")
        val operationId = UUID.randomUUID().toString()
        val issuedAt = now().truncatedTo(ChronoUnit.SECONDS)
        val expiresAt = issuedAt.plusSeconds(ProtocolV2.MAX_REQUEST_LIFETIME_SECONDS)
        val payload = JsonObject().apply {
            addProperty("version", ProtocolV2.VERSION)
            addProperty("type", "payment_request")
            addProperty("operation_id", operationId)
            addProperty("merchant_id", terminal.merchantId)
            addProperty("terminal_id", terminal.terminalId)
            addProperty("amount_cents", amountCents)
            addProperty("currency", ProtocolV2.CURRENCY)
            addProperty("nonce", nonce())
            addProperty("issued_at", timestamp(issuedAt))
            addProperty("expires_at", timestamp(expiresAt))
            addProperty("pos_key_id", terminal.posKeyId)
            addProperty("pos_key_fingerprint", terminal.posKeyFingerprint)
        }
        ProtocolV2.validatePayload(payload)
        val payloadBytes = ProtocolV2.canonicalJson(payload).toByteArray(Charsets.UTF_8)
        val envelope = ProtocolV2.encodeSignedEnvelope(
            payload,
            keyManager.sign(terminal.keyStoreAlias, payloadBytes)
        )
        database.mobilePosOperationDao().insert(
            MobilePosOperation(
                operationId = operationId,
                requestEnvelope = envelope,
                merchantId = terminal.merchantId,
                terminalId = terminal.terminalId,
                amountCents = amountCents,
                state = "REQUEST_CREATED",
                createdAtEpochSeconds = issuedAt.epochSecond
            )
        )
        return MobilePaymentRequest(operationId, envelope)
    }

    suspend fun acceptReceipt(operationId: String, receiptEnvelope: ByteArray): ByteArray {
        val operation = database.mobilePosOperationDao().get(operationId)
            ?: throw IllegalArgumentException("Solicitud no encontrada")
        operation.receiptEnvelope?.let { existing ->
            require(existing.contentEquals(receiptEnvelope)) { "Comprobante conflictivo" }
            return requireNotNull(operation.ackEnvelope)
        }
        require(operation.state == "REQUEST_CREATED") { "Solicitud no disponible" }
        val request = ProtocolV2.parseSignedEnvelope(operation.requestEnvelope).payload
        val receipt = ProtocolV2.parseSignedEnvelope(receiptEnvelope)
        require(ProtocolV2.validatePayload(receipt.payload) == MessageType.PAYMENT_RECEIPT)
        ProtocolV2.requireMatchingReceipt(request, receipt.payload)
        require(!now().isAfter(Instant.parse(request["expires_at"].asString))) { "Solicitud vencida" }
        val walletKey = database.nfcExchangeDao().getActiveTrustedKey(
            receipt.payload["wallet_key_id"].asString,
            receipt.payload["wallet_key_fingerprint"].asString,
            "wallet"
        ) ?: throw IllegalStateException("Clave de billetera no confiable")
        require(receipt.verify(walletKey.publicKeyBase64)) { "Firma de billetera invalida" }

        val terminal = database.mobileTerminalDao().getById(operation.terminalId)
            ?: throw IllegalStateException("Terminal no aprovisionado")
        val acceptedAt = now().truncatedTo(ChronoUnit.SECONDS)
        val ackPayload = JsonObject().apply {
            listOf(
                "version", "operation_id", "merchant_id", "terminal_id", "amount_cents",
                "currency", "nonce", "issued_at", "expires_at"
            ).forEach { field -> add(field, request[field]) }
            addProperty("type", "payment_ack")
            addProperty("wallet_id", receipt.payload["wallet_id"].asString)
            addProperty("offline_authorization_id", receipt.payload["offline_authorization_id"].asString)
            addProperty("pos_key_id", terminal.posKeyId)
            addProperty("pos_key_fingerprint", terminal.posKeyFingerprint)
            addProperty("receipt_digest", ProtocolV2.payloadDigest(receipt.payload))
            addProperty("accepted_at", timestamp(acceptedAt))
            addProperty("status", "LOCALLY_ACCEPTED")
        }
        ProtocolV2.validatePayload(ackPayload)
        val ack = ProtocolV2.encodeSignedEnvelope(
            ackPayload,
            keyManager.sign(
                terminal.keyStoreAlias,
                ProtocolV2.canonicalJson(ackPayload).toByteArray(Charsets.UTF_8)
            )
        )
        return database.withTransaction {
            val current = database.mobilePosOperationDao().get(operationId)
                ?: throw IllegalArgumentException("Solicitud no encontrada")
            current.receiptEnvelope?.let { existing ->
                require(existing.contentEquals(receiptEnvelope)) { "Comprobante conflictivo" }
                return@withTransaction requireNotNull(current.ackEnvelope)
            }
            check(
                database.mobilePosOperationDao().accept(
                    operationId,
                    receiptEnvelope,
                    ack,
                    receipt.payload["wallet_id"].asString,
                    receipt.payload["offline_authorization_id"].asString
                ) == 1
            )
            ack
        }
    }

    private fun nonce(): String = ByteArray(16)
        .also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun timestamp(value: Instant): String = TIMESTAMP_FORMAT.format(value)

    companion object {
        private val TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)
    }
}
