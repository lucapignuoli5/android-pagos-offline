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
    private val exchangeService = ClientPaymentExchangeService(store, trustedKeys, now)

    suspend fun process(commandApdu: ByteArray): ByteArray {
        if (isSelect(commandApdu)) {
            return ProtocolV3.SELECT_CAPABILITIES + ProtocolV2.SW_OK
        }
        val cacheKey = Base64.getEncoder().encodeToString(commandApdu)
        responseCache[cacheKey]?.let { return it.copyOf() }
        val response = try {
            when (val command = ProtocolV2.parseApdu(commandApdu)) {
                else -> when (command.instruction) {
                    ProtocolV2.INS_PUT_REQUEST -> acceptFragment(command, ::acceptRequest)
                    ProtocolV2.INS_GET_STATUS -> getStatus(command)
                    ProtocolV2.INS_GET_RECEIPT -> getReceipt(command)
                    ProtocolV3.INS_GET_AUTHORIZATION -> getAuthorization(command)
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
        val accepted = try {
            exchangeService.acceptRequest(data)
        } catch (_: IllegalStateException) {
            return ProtocolV2.SW_CONDITIONS_NOT_SATISFIED
        }
        return statusResponse(accepted.operationId, accepted.state)
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

    private suspend fun getAuthorization(command: ApduCommand): ByteArray {
        require(command.isLast)
        val operationId = operationId(command.data)
        val authorization = store.getAuthorization(operationId)
            ?: return ProtocolV2.SW_CONDITIONS_NOT_SATISFIED
        val chunks = ProtocolV2.chunkMessage(authorization)
        if (command.sequence !in chunks.indices) return ProtocolV2.SW_NOT_FOUND
        val lastFlag = if (command.sequence == chunks.lastIndex) 1.toByte() else 0.toByte()
        return byteArrayOf(lastFlag) + chunks[command.sequence] + ProtocolV2.SW_OK
    }

    private suspend fun acceptAck(data: ByteArray): ByteArray {
        val accepted = try {
            exchangeService.acceptAck(data)
        } catch (_: IllegalStateException) {
            return ProtocolV2.SW_CONDITIONS_NOT_SATISFIED
        }
        return statusResponse(accepted.operationId, accepted.state)
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
            ProtocolV3.SELECT_CAPABILITIES.copyOf()
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
    suspend fun getAuthorization(operationId: String): ByteArray? = null
    suspend fun markDeliveryStarted(operationId: String)
    suspend fun saveAck(operationId: String, signedEnvelope: ByteArray): SaveResult
}

enum class SaveResult { INSERTED, IDENTICAL, CONFLICT }

enum class ExchangeState {
    REQUEST_RECEIVED,
    REQUEST_EXPIRED,
    CANCELLED,
    RESERVED,
    DELIVERY_UNCERTAIN,
    LOCALLY_ACCEPTED,
    SYNCED,
    REJECTED,
    CONFLICT
}
