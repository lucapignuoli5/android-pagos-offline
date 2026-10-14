package com.example.prototipopagosoffline.protocol

import java.time.Instant

data class AcceptedLocalMessage(val operationId: String, val state: ExchangeState)

class ClientPaymentExchangeService(
    private val store: NfcExchangeStore,
    private val trustedPosKeys: TrustedPublicKeyResolver,
    private val now: () -> Instant = { Instant.now() }
) {
    suspend fun acceptRequest(data: ByteArray): AcceptedLocalMessage {
        runCatching { ProtocolV3.parseSignedEnvelope(data) }.getOrNull()?.let { envelope ->
            require(ProtocolV3.validatePayload(envelope.payload) == MessageTypeV3.PAYMENT_REQUEST)
            require(Instant.parse(envelope.payload["expires_at"].asString).isAfter(now()))
            val keyId = envelope.payload["pos_key_id"].asString
            val fingerprint = envelope.payload["pos_key_fingerprint"].asString
            val publicKey = trustedPosKeys.resolve(
                keyId, fingerprint, envelope.payload["terminal_id"].asString
            )
                ?: throw IllegalStateException("Clave POS no confiable")
            require(ProtocolV3.verifyPosSigner(
                envelope, publicKey, keyId, fingerprint,
                envelope.payload["merchant_id"].asString,
                envelope.payload["terminal_id"].asString
            )) { "Firma POS v3 invalida" }
            val operationId = envelope.payload["operation_id"].asString
            require(store.saveRequest(operationId, data) != SaveResult.CONFLICT)
            return AcceptedLocalMessage(operationId, ExchangeState.REQUEST_RECEIVED)
        }
        val envelope = ProtocolV2.parseSignedEnvelope(data)
        require(ProtocolV2.validatePayload(envelope.payload) == MessageType.PAYMENT_REQUEST)
        require(Instant.parse(envelope.payload["expires_at"].asString).isAfter(now())) {
            "Solicitud vencida"
        }
        val publicKey = trustedPosKeys.resolve(
            envelope.payload["pos_key_id"].asString,
            envelope.payload["pos_key_fingerprint"].asString
        ) ?: throw IllegalStateException("Clave POS no confiable")
        require(envelope.verify(publicKey)) { "Firma POS invalida" }
        val operationId = envelope.payload["operation_id"].asString
        require(store.getRequest(operationId)?.contentEquals(data) == true) {
            "Las solicitudes nuevas requieren v3; v2 solo recupera operaciones existentes"
        }
        require(store.saveRequest(operationId, data) != SaveResult.CONFLICT) {
            "operation_id repetido con otra solicitud"
        }
        return AcceptedLocalMessage(operationId, ExchangeState.REQUEST_RECEIVED)
    }

    suspend fun acceptAck(data: ByteArray): AcceptedLocalMessage {
        runCatching { ProtocolV3.parseSignedEnvelope(data) }.getOrNull()?.let { envelope ->
            require(ProtocolV3.validatePayload(envelope.payload) == MessageTypeV3.PAYMENT_ACK)
            val keyId = envelope.payload["pos_key_id"].asString
            val fingerprint = envelope.payload["pos_key_fingerprint"].asString
            val publicKey = trustedPosKeys.resolve(
                keyId, fingerprint, envelope.payload["terminal_id"].asString
            )
                ?: throw IllegalStateException("Clave POS no confiable")
            require(ProtocolV3.verifyPosSigner(
                envelope, publicKey, keyId, fingerprint,
                envelope.payload["merchant_id"].asString,
                envelope.payload["terminal_id"].asString
            ))
            val operationId = envelope.payload["operation_id"].asString
            val request = ProtocolV3.parseSignedEnvelope(
                store.getRequest(operationId) ?: throw IllegalArgumentException("Solicitud no encontrada")
            )
            val receipt = ProtocolV3.parseSignedEnvelope(
                store.getReceipt(operationId) ?: throw IllegalStateException("Comprobante no disponible")
            )
            ProtocolV3.validateAckBinding(request, receipt, envelope)
            require(store.saveAck(operationId, data) != SaveResult.CONFLICT)
            return AcceptedLocalMessage(operationId, ExchangeState.LOCALLY_ACCEPTED)
        }
        val envelope = ProtocolV2.parseSignedEnvelope(data)
        require(ProtocolV2.validatePayload(envelope.payload) == MessageType.PAYMENT_ACK)
        val publicKey = trustedPosKeys.resolve(
            envelope.payload["pos_key_id"].asString,
            envelope.payload["pos_key_fingerprint"].asString
        ) ?: throw IllegalStateException("Clave POS no confiable")
        require(envelope.verify(publicKey)) { "Firma POS invalida" }

        val operationId = envelope.payload["operation_id"].asString
        val request = ProtocolV2.parseSignedEnvelope(
            store.getRequest(operationId) ?: throw IllegalArgumentException("Solicitud no encontrada")
        ).payload
        val receipt = ProtocolV2.parseSignedEnvelope(
            store.getReceipt(operationId) ?: throw IllegalStateException("Comprobante no disponible")
        ).payload
        ProtocolV2.requireMatchingAck(request, receipt, envelope.payload)
        require(envelope.payload["receipt_digest"].asString == ProtocolV2.payloadDigest(receipt))
        require(store.saveAck(operationId, data) != SaveResult.CONFLICT) {
            "Acuse distinto para la misma operacion"
        }
        return AcceptedLocalMessage(operationId, ExchangeState.LOCALLY_ACCEPTED)
    }
}
