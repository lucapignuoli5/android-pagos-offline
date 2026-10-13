package com.example.prototipopagosoffline.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ProtocolV2Test {
    @Test
    fun canonicalVectorAndPythonSignatureMatch() {
        assertEquals(canonicalRequest, ProtocolV2.canonicalJson(request))
        assertEquals(MessageType.PAYMENT_REQUEST, ProtocolV2.validatePayload(request))
        assertEquals(request, ProtocolV2.parseCanonicalPayload(canonicalRequest.toByteArray()))
        assertTrue(ProtocolV2.verifyPayloadSignature(request, signatureBase64, publicKeyBase64))
    }

    @Test
    fun kotlinSignatureUsesP256DerBase64() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val signature = ProtocolV2.signPayload(request, pair.private)
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        assertTrue(ProtocolV2.verifyPayloadSignature(request, signature, publicKey))
    }

    @Test
    fun changingEveryFieldInvalidatesSignature() {
        request.keySet().forEach { field ->
            val changed = request.deepCopy()
            val value = changed[field]
            if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                changed.addProperty(field, value.asLong + 1)
            } else {
                changed.addProperty(field, value.asString + "x")
            }
            assertFalse(field, ProtocolV2.verifyPayloadSignature(changed, signatureBase64, publicKeyBase64))
        }
    }

    @Test
    fun signedEnvelopePreservesExactPythonPayload() {
        val wire = ProtocolV2.encodeSignedEnvelope(request, signatureBase64)
        val envelope = ProtocolV2.parseSignedEnvelope(wire)
        assertArrayEquals(canonicalRequest.toByteArray(), envelope.payloadBytes)
        assertEquals(request, envelope.payload)
        assertTrue(envelope.verify(publicKeyBase64))

        val changed = wire.toString(Charsets.UTF_8)
            .replace("payload_b64", "payload_data")
            .toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolV2.parseSignedEnvelope(changed)
        }
    }

    @Test
    fun rejectsInvalidAmountsAndAdditionalFields() {
        listOf(0, -1, 100_001).forEach { amount ->
            val changed = request.deepCopy().apply { addProperty("amount_cents", amount) }
            assertThrows(IllegalArgumentException::class.java) { ProtocolV2.validatePayload(changed) }
        }
        val floating = request.deepCopy().apply { addProperty("amount_cents", 1.5) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolV2.validatePayload(floating) }
        val extra = request.deepCopy().apply { addProperty("unexpected", "field") }
        assertThrows(IllegalArgumentException::class.java) { ProtocolV2.validatePayload(extra) }
    }

    @Test
    fun apduFragmentationRoundTrip() {
        val data = canonicalRequest.toByteArray()
        val chunks = ProtocolV2.chunkMessage(data)
        val decoded = chunks.mapIndexed { index, chunk ->
            val apdu = ProtocolV2.buildApdu(
                ProtocolV2.INS_PUT_REQUEST,
                index,
                index == chunks.lastIndex,
                chunk
            )
            val parsed = ProtocolV2.parseApdu(apdu)
            assertEquals(index, parsed.sequence)
            assertEquals(index == chunks.lastIndex, parsed.isLast)
            parsed.data
        }
        assertArrayEquals(data, ProtocolV2.assembleChunks(decoded))
    }

    companion object {
        private lateinit var request: JsonObject
        private lateinit var canonicalRequest: String
        private lateinit var publicKeyBase64: String
        private lateinit var signatureBase64: String

        @JvmStatic
        @BeforeClass
        fun loadVector() {
            val stream = requireNotNull(ProtocolV2Test::class.java.classLoader?.getResourceAsStream("protocol_v2_vectors.json"))
            val vector = stream.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
            request = vector["request"].asJsonObject
            canonicalRequest = vector["canonical_request"].asString
            publicKeyBase64 = vector["public_key_der_b64"].asString
            signatureBase64 = vector["signature_der_b64"].asString
        }
    }
}
