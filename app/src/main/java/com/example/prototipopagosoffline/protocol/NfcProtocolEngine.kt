package com.example.prototipopagosoffline.protocol

import java.time.Instant
import java.util.Base64

class NfcProtocolEngine(
    private val store: NfcExchangeStore,
    private val trustedKeys: TrustedPublicKeyResolver,
    private val now: () -> Instant = { Instant.now() }
) {
    private val accumulators = mutableMapOf<Int, FragmentAccumulator>()
    private val responseCache = mutableMapOf<String, ByteArray>()

    suspend fun process(commandApdu: ByteArray): ByteArray {
        if (isSelect(commandApdu)) {
            return SELECT_CAPABILITIES + ProtocolV2.SW_OK
        }
        val cacheKey = Base64.getEncoder().encodeToString(commandApdu)
        responseCache[cacheKey]?.let { return it.copyOf() }
        val response = try {
            when (val command = ProtocolV2.parseApdu(commandApdu)) {
                else -> when (command.instruction) {
                    ProtocolV2.INS_PUT_REQUEST -> acceptFragment(command, ::acceptRequest)
                    ProtocolV2.INS_GET_STATUS -> getStatus(command)
                    ProtocolV2.INS_GET_RECEIPT -> getReceipt(command)
                    ProtocolV2.INS_PUT_ACK -> acceptFragment(command, ::acceptAck)
                    else -> ProtocolV2.SW_BAD_DATA
                }
            }
        } catch (_: IllegalArgumentException) {
            ProtocolV2.SW_BAD_DATA
        }
        responseCache[cacheKey] = response.copyOf()
        return response
    }

    fun resetActivation() {
        accumulators.clear()
        responseCache.clear()
    }

    private suspend fun acceptFragment(
        command: ApduCommand,
        onComplete: suspend (ByteArray) -> ByteArray
    ): ByteArray {
        val accumulator = accumulators.getOrPut(command.instruction) { FragmentAccumulator() }
        val completed = accumulator.add(command.sequence, command.isLast, command.data)
        if (completed == null) return ProtocolV2.SW_OK
        accumulators.remove(command.instruction)
        return onComplete(completed)
    }

    private suspend fun acceptRequest(data: ByteArray): ByteArray {
        val envelope = ProtocolV2.parseSignedEnvelope(data)
        require(ProtocolV2.validatePayload(envelope.payload) == MessageType.PAYMENT_REQUEST)
        val expiresAt = Instant.parse(envelope.payload["expires_at"].asString)
        require(expiresAt.isAfter(now())) { "Solicitud vencida" }
        val publicKey = trustedKeys.resolve(
            envelope.payload["pos_key_id"].asString,
            envelope.payload["pos_key_fingerprint"].asString
        ) ?: return ProtocolV2.SW_CONDITIONS_NOT_SATISFIED
        if (!envelope.verify(publicKey)) return ProtocolV2.SW_BAD_DATA

        val operationId = envelope.payload["operation_id"].asString
        return when (store.saveRequest(operationId, data)) {
            SaveResult.INSERTED, SaveResult.IDENTICAL -> statusResponse(operationId, ExchangeState.REQUEST_RECEIVED)
            SaveResult.CONFLICT -> ProtocolV2.SW_BAD_DATA
        }
    }

    private suspend fun getStatus(command: ApduCommand): ByteArray {
        require(command.sequence == 0 && command.isLast)
        val operationId = operationId(command.data)
        val state = store.getState(operationId) ?: return ProtocolV2.SW_NOT_FOUND
        return statusResponse(operationId, state)
    }

    private suspend fun getReceipt(command: ApduCommand): ByteArray {
        require(command.isLast)
        val operationId = operationId(command.data)
        val receipt = store.getReceipt(operationId) ?: return ProtocolV2.SW_CONDITIONS_NOT_SATISFIED
        val chunks = ProtocolV2.chunkMessage(receipt)
        if (command.sequence !in chunks.indices) return ProtocolV2.SW_NOT_FOUND
        if (command.sequence == 0) store.markDeliveryStarted(operationId)
        val lastFlag = if (command.sequence == chunks.lastIndex) 1.toByte() else 0.toByte()
        return byteArrayOf(lastFlag) + chunks[command.sequence] + ProtocolV2.SW_OK
    }

    private suspend fun acceptAck(data: ByteArray): ByteArray {
        val envelope = ProtocolV2.parseSignedEnvelope(data)
        require(ProtocolV2.validatePayload(envelope.payload) == MessageType.PAYMENT_ACK)
        val publicKey = trustedKeys.resolve(
            envelope.payload["pos_key_id"].asString,
            envelope.payload["pos_key_fingerprint"].asString
        ) ?: return ProtocolV2.SW_CONDITIONS_NOT_SATISFIED
        if (!envelope.verify(publicKey)) return ProtocolV2.SW_BAD_DATA

        val operationId = envelope.payload["operation_id"].asString
        val requestData = store.getRequest(operationId) ?: return ProtocolV2.SW_NOT_FOUND
        val receiptData = store.getReceipt(operationId) ?: return ProtocolV2.SW_CONDITIONS_NOT_SATISFIED
        val request = ProtocolV2.parseSignedEnvelope(requestData).payload
        val receipt = ProtocolV2.parseSignedEnvelope(receiptData).payload
        requireMatchingOperation(request, receipt, envelope.payload)
        require(envelope.payload["receipt_digest"].asString == ProtocolV2.payloadDigest(receipt))

        return when (store.saveAck(operationId, data)) {
            SaveResult.INSERTED, SaveResult.IDENTICAL -> statusResponse(operationId, ExchangeState.LOCALLY_ACCEPTED)
            SaveResult.CONFLICT -> ProtocolV2.SW_BAD_DATA
        }
    }

    private fun requireMatchingOperation(
        request: com.google.gson.JsonObject,
        receipt: com.google.gson.JsonObject,
        ack: com.google.gson.JsonObject
    ) {
        val common = listOf(
            "operation_id", "merchant_id", "terminal_id", "amount_cents", "currency", "nonce",
            "issued_at", "expires_at"
        )
        common.forEach { field ->
            require(request[field] == receipt[field] && receipt[field] == ack[field]) { "$field no coincide" }
        }
        listOf("wallet_id", "offline_authorization_id").forEach { field ->
            require(receipt[field] == ack[field]) { "$field no coincide" }
        }
        require(receipt["request_digest"].asString == ProtocolV2.payloadDigest(request))
    }

    private fun operationId(data: ByteArray): String {
        val value = data.toString(Charsets.US_ASCII)
        val uuid = java.util.UUID.fromString(value)
        require(uuid.toString() == value && uuid.version() == 4)
        return value
    }

    private fun statusResponse(operationId: String, state: ExchangeState): ByteArray =
        "$operationId|${state.name}".toByteArray(Charsets.US_ASCII) + ProtocolV2.SW_OK

    private fun isSelect(commandApdu: ByteArray): Boolean =
        commandApdu.contentEquals(ProtocolV2.SELECT_APDU) ||
            commandApdu.contentEquals(ProtocolV2.SELECT_APDU + byteArrayOf(0x00))

    private class FragmentAccumulator {
        private val chunks = mutableListOf<ByteArray>()

        fun add(sequence: Int, isLast: Boolean, data: ByteArray): ByteArray? {
            require(sequence == chunks.size) { "Secuencia inesperada" }
            chunks += data.copyOf()
            val currentSize = chunks.sumOf { it.size }
            require(currentSize <= ProtocolV2.MAX_MESSAGE_BYTES) { "Mensaje demasiado grande" }
            return if (isLast) ProtocolV2.assembleChunks(chunks) else null
        }
    }

    companion object {
        val SELECT_CAPABILITIES: ByteArray =
            "{\"chunk_bytes\":160,\"max_message_bytes\":4096,\"protocol_version\":2}"
                .toByteArray(Charsets.US_ASCII)
    }
}

interface TrustedPublicKeyResolver {
    suspend fun resolve(keyId: String, fingerprint: String): String?
}

interface NfcExchangeStore {
    suspend fun saveRequest(operationId: String, signedEnvelope: ByteArray): SaveResult
    suspend fun getRequest(operationId: String): ByteArray?
    suspend fun getState(operationId: String): ExchangeState?
    suspend fun getReceipt(operationId: String): ByteArray?
    suspend fun markDeliveryStarted(operationId: String)
    suspend fun saveAck(operationId: String, signedEnvelope: ByteArray): SaveResult
}

enum class SaveResult { INSERTED, IDENTICAL, CONFLICT }

enum class ExchangeState {
    REQUEST_RECEIVED,
    RESERVED,
    DELIVERY_UNCERTAIN,
    LOCALLY_ACCEPTED,
    SYNCED,
    REJECTED,
    CONFLICT
}
