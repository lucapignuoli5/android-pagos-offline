package com.example.prototipopagosoffline.protocol

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class MobileNfcReaderTest {
    @Test
    fun `first approach sends request and waits for client confirmation`() = runBlocking {
        var receiptAccepted = false
        val card = FakeCard(ExchangeState.REQUEST_RECEIVED)
        val result = MobileNfcReader(card) { _, _ ->
            receiptAccepted = true
            byteArrayOf(1)
        }.exchange(OPERATION_ID, requestEnvelope)

        assertEquals(MobileNfcExchangeResult.WaitingForConfirmation(OPERATION_ID), result)
        assertFalse(receiptAccepted)
        assertArrayEquals(requestEnvelope, card.receivedRequest())
        assertTrue(card.instructions.contains(ProtocolV2.INS_GET_STATUS))
        assertFalse(card.instructions.contains(ProtocolV2.INS_GET_RECEIPT))
    }

    @Test
    fun `second approach receives fragmented receipt and returns exact ack`() = runBlocking {
        val receipt = ByteArray(401) { index -> (index % 251).toByte() }
        val ack = ByteArray(319) { index -> (250 - index % 251).toByte() }
        val card = FakeCard(ExchangeState.RESERVED, receipt)
        val result = MobileNfcReader(card) { operationId, receivedReceipt ->
            assertEquals(OPERATION_ID, operationId)
            assertArrayEquals(receipt, receivedReceipt)
            ack
        }.exchange(OPERATION_ID, requestEnvelope)

        assertEquals(MobileNfcExchangeResult.Accepted(OPERATION_ID), result)
        assertArrayEquals(ack, card.receivedAck())
        assertTrue(card.instructions.count { it == ProtocolV2.INS_GET_RECEIPT } > 1)
        assertTrue(card.instructions.count { it == ProtocolV2.INS_PUT_ACK } > 1)
    }

    private class FakeCard(
        private val state: ExchangeState,
        receipt: ByteArray = byteArrayOf(1)
    ) : ApduTransceiver {
        val instructions = mutableListOf<Int>()
        private val requestChunks = mutableListOf<ByteArray>()
        private val ackChunks = mutableListOf<ByteArray>()
        private val receiptChunks = ProtocolV2.chunkMessage(receipt)

        override suspend fun transceive(command: ByteArray): ByteArray {
            if (command.contentEquals(ProtocolV2.SELECT_APDU)) {
                return NfcProtocolEngine.SELECT_CAPABILITIES + ProtocolV2.SW_OK
            }
            val parsed = ProtocolV2.parseApdu(command)
            instructions += parsed.instruction
            return when (parsed.instruction) {
                ProtocolV2.INS_PUT_REQUEST -> {
                    requestChunks += parsed.data
                    status(ExchangeState.REQUEST_RECEIVED).takeIf { parsed.isLast } ?: ProtocolV2.SW_OK
                }
                ProtocolV2.INS_GET_STATUS -> status(state)
                ProtocolV2.INS_GET_RECEIPT -> {
                    val chunk = receiptChunks[parsed.sequence]
                    byteArrayOf(if (parsed.sequence == receiptChunks.lastIndex) 1 else 0) +
                        chunk + ProtocolV2.SW_OK
                }
                ProtocolV2.INS_PUT_ACK -> {
                    ackChunks += parsed.data
                    status(ExchangeState.LOCALLY_ACCEPTED).takeIf { parsed.isLast } ?: ProtocolV2.SW_OK
                }
                else -> error("Instruccion inesperada")
            }
        }

        fun receivedRequest(): ByteArray = ProtocolV2.assembleChunks(requestChunks)
        fun receivedAck(): ByteArray = ProtocolV2.assembleChunks(ackChunks)

        private fun status(value: ExchangeState): ByteArray =
            "$OPERATION_ID|${value.name}".toByteArray(Charsets.US_ASCII) + ProtocolV2.SW_OK
    }

    companion object {
        private const val OPERATION_ID = "123e4567-e89b-42d3-a456-426614174000"
        private lateinit var requestEnvelope: ByteArray

        @JvmStatic
        @BeforeClass
        fun loadVector() {
            val stream = requireNotNull(
                MobileNfcReaderTest::class.java.classLoader?.getResourceAsStream("protocol_v2_vectors.json")
            )
            val vector = stream.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
            requestEnvelope = ProtocolV2.encodeSignedEnvelope(
                vector["request"].asJsonObject,
                vector["signature_der_b64"].asString
            )
        }
    }
}
