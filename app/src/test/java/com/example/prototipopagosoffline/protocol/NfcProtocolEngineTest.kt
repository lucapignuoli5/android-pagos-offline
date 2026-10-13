package com.example.prototipopagosoffline.protocol

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.time.Instant

class NfcProtocolEngineTest {
    @Test
    fun selectOnlyReturnsCapabilities() = runBlocking {
        val store = MemoryStore()
        val engine = engine(store)
        val response = engine.process(ProtocolV2.SELECT_APDU)
        assertTrue(response.endsWith(ProtocolV2.SW_OK))
        assertTrue(response.copyOfRange(0, response.size - 2).contentEquals(NfcProtocolEngine.SELECT_CAPABILITIES))
        assertNull(store.request)
    }

    @Test
    fun acceptsFragmentedRequestAndExactRetryOnce() = runBlocking {
        val store = MemoryStore()
        val engine = engine(store)
        val chunks = ProtocolV2.chunkMessage(requestEnvelope)
        var finalApdu = byteArrayOf()
        var finalResponse = byteArrayOf()
        chunks.forEachIndexed { index, chunk ->
            finalApdu = ProtocolV2.buildApdu(
                ProtocolV2.INS_PUT_REQUEST,
                index,
                index == chunks.lastIndex,
                chunk
            )
            finalResponse = engine.process(finalApdu)
            assertTrue(finalResponse.endsWith(ProtocolV2.SW_OK))
        }
        assertEquals(1, store.requestWrites)
        assertArrayEquals(requestEnvelope, store.request)
        assertArrayEquals(finalResponse, engine.process(finalApdu))
        assertEquals(1, store.requestWrites)
    }

    @Test
    fun rejectsUnknownKeyAndOutOfOrderFragment() = runBlocking {
        val store = MemoryStore()
        val unknownKeyEngine = NfcProtocolEngine(
            store,
            object : TrustedPublicKeyResolver {
                override suspend fun resolve(keyId: String, fingerprint: String): String? = null
            },
            now = { Instant.parse("2026-10-13T15:01:00Z") }
        )
        assertArrayEquals(
            ProtocolV2.SW_CONDITIONS_NOT_SATISFIED,
            sendRequest(unknownKeyEngine, requestEnvelope)
        )
        assertNull(store.request)

        val orderedEngine = engine(MemoryStore())
        val badOrder = ProtocolV2.buildApdu(ProtocolV2.INS_PUT_REQUEST, 1, false, byteArrayOf(1))
        assertArrayEquals(ProtocolV2.SW_BAD_DATA, orderedEngine.process(badOrder))
    }

    @Test
    fun rejectsAlteredSignature() = runBlocking {
        val parsed = JsonParser.parseString(requestEnvelope.toString(Charsets.UTF_8)).asJsonObject
        parsed.addProperty("signature_b64", parsed["signature_b64"].asString.dropLast(2) + "AA")
        val altered = ProtocolV2.canonicalJson(parsed).toByteArray()
        val store = MemoryStore()
        assertArrayEquals(ProtocolV2.SW_BAD_DATA, sendRequest(engine(store), altered))
        assertNull(store.request)
    }

    private suspend fun sendRequest(engine: NfcProtocolEngine, data: ByteArray): ByteArray {
        val chunks = ProtocolV2.chunkMessage(data)
        var response = byteArrayOf()
        chunks.forEachIndexed { index, chunk ->
            response = engine.process(
                ProtocolV2.buildApdu(
                    ProtocolV2.INS_PUT_REQUEST,
                    index,
                    index == chunks.lastIndex,
                    chunk
                )
            )
        }
        return response
    }

    private fun engine(store: MemoryStore) = NfcProtocolEngine(
        store,
        object : TrustedPublicKeyResolver {
            override suspend fun resolve(keyId: String, fingerprint: String): String? = publicKeyBase64
        },
        now = { Instant.parse("2026-10-13T15:01:00Z") }
    )

    private class MemoryStore : NfcExchangeStore {
        var request: ByteArray? = null
        var requestWrites = 0

        override suspend fun saveRequest(operationId: String, signedEnvelope: ByteArray): SaveResult {
            requestWrites += 1
            val previous = request
            return when {
                previous == null -> {
                    request = signedEnvelope.copyOf()
                    SaveResult.INSERTED
                }
                previous.contentEquals(signedEnvelope) -> SaveResult.IDENTICAL
                else -> SaveResult.CONFLICT
            }
        }

        override suspend fun getRequest(operationId: String): ByteArray? = request
        override suspend fun getState(operationId: String): ExchangeState? =
            request?.let { ExchangeState.REQUEST_RECEIVED }
        override suspend fun getReceipt(operationId: String): ByteArray? = null
        override suspend fun markDeliveryStarted(operationId: String) = Unit
        override suspend fun saveAck(operationId: String, signedEnvelope: ByteArray): SaveResult = SaveResult.INSERTED
    }

    companion object {
        private lateinit var requestEnvelope: ByteArray
        private lateinit var publicKeyBase64: String

        @JvmStatic
        @BeforeClass
        fun loadVector() {
            val stream = requireNotNull(NfcProtocolEngineTest::class.java.classLoader?.getResourceAsStream("protocol_v2_vectors.json"))
            val vector = stream.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
            val request = vector["request"].asJsonObject
            publicKeyBase64 = vector["public_key_der_b64"].asString
            requestEnvelope = ProtocolV2.encodeSignedEnvelope(request, vector["signature_der_b64"].asString)
        }
    }
}

private fun ByteArray.endsWith(suffix: ByteArray): Boolean =
    size >= suffix.size && copyOfRange(size - suffix.size, size).contentEquals(suffix)
