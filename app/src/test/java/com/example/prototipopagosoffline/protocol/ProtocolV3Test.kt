package com.example.prototipopagosoffline.protocol

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ProtocolV3Test {
    private val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream("protocol_v3_vectors.json"))
        .reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    private val messages = fixture["messages"].asJsonObject
    private val keys = fixture["keys"].asJsonObject

    private fun bytes(value: String) = Base64.getDecoder().decode(value)
    private fun evidence(name: String) = ProtocolV3.parseSignedEnvelope(bytes(messages[name].asJsonObject["envelope_b64"].asString))
    private fun publicKey(signer: String) = keys[signer].asJsonObject["public_key_b64"].asString

    @Test fun consumesSevenCompletePythonEnvelopesWithoutRebuildingThem() {
        assertEquals(7, messages.size())
        messages.entrySet().forEach { (name, value) ->
            val vector = value.asJsonObject
            val envelope = evidence(name)
            assertArrayEquals(bytes(vector["payload_b64"].asString), envelope.payloadBytes)
            assertEquals(vector["payload_sha256"].asString, ProtocolV3.payloadDigest(envelope))
            assertEquals(vector["payload"], envelope.payload)
            assertTrue(name, envelope.verify(publicKey(vector["signer"].asString)))
            assertArrayEquals(bytes(vector["envelope_b64"].asString), ProtocolV3.encodeSignedEnvelope(envelope.payload, envelope.signatureBase64))
        }
        assertTrue(ProtocolV3.verifyAuthorization(evidence("authorization"), publicKey("issuer"), keys["issuer"].asJsonObject["key_id"].asString))
        ProtocolV3.validateReceiptBinding(evidence("request"), evidence("receipt"), evidence("authorization"))
        ProtocolV3.validateAckBinding(evidence("request"), evidence("receipt"), evidence("ack"))
        assertEquals(1, ProtocolV3.validateCloseManifest(evidence("close"), evidence("authorization"), bytes(fixture["manifest_b64"].asString)).size)
    }

    @Test fun changingEachFieldOfEverySignedTypeInvalidatesItsSignature() {
        messages.entrySet().forEach { (name, value) ->
            val vector = value.asJsonObject
            val original = evidence(name)
            original.payload.keySet().forEach { field ->
                val changed = original.payload.deepCopy()
                if (changed[field].asJsonPrimitive.isNumber) changed.addProperty(field, changed[field].asLong + 1)
                else changed.addProperty(field, changed[field].asString + "X")
                assertFalse("$name.$field", ProtocolV3.verifyExactSignature(ProtocolV3.canonicalJson(changed).toByteArray(), original.signatureBase64, publicKey(vector["signer"].asString)))
            }
        }
    }

    @Test fun rejectsCoercionsMissingFieldsOverflowAndNoncanonicalInput() {
        val request = evidence("request").payload
        listOf("3.0", "true", "\"3\"", "null", "9223372036854775808", "3e0").forEach { version ->
            val text = ProtocolV3.canonicalJson(request).replace("\"version\":3", "\"version\":$version")
            assertThrows(version, IllegalArgumentException::class.java) { ProtocolV3.parseCanonicalPayload(text.toByteArray()) }
        }
        listOf(0L, -1L).forEach { amount ->
            assertThrows(IllegalArgumentException::class.java) { ProtocolV3.validatePayload(request.deepCopy().apply { addProperty("amount_cents", amount) }) }
        }
        listOf("amount_cents", "nonce", "terminal_id").forEach { field ->
            assertThrows(IllegalArgumentException::class.java) { ProtocolV3.validatePayload(request.deepCopy().apply { remove(field) }) }
        }
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.parseCanonicalPayload("{\"version\":3,\"version\":3}".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.parseCanonicalPayload(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.parseCanonicalPayload((ProtocolV3.canonicalJson(request) + " ").toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.validatePayload(request.deepCopy().apply { addProperty("issued_at", "2026-10-14T00:00:00.001Z") }) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.validatePayload(request.deepCopy().apply { addProperty("issued_at", "0000-10-14T00:00:00Z") }) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.validatePayload(request.deepCopy().apply { addProperty("expires_at", "2026-10-14T23:59:60Z") }) }
    }

    @Test fun canonicalWriterMatchesLiteralUtf8AndDoesNotHtmlEscape() {
        val value = JsonObject().apply { addProperty("texto", "á<>&='\u2028\u2029😀\n") }
        assertEquals("{\"texto\":\"á<>&='\u2028\u2029😀\\n\"}", ProtocolV3.canonicalJson(value))
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.canonicalJson(JsonObject().apply { addProperty("x", "\ud800") }) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.canonicalJson(JsonObject().apply { addProperty("x", true) }) }
    }

    @Test fun rejectsBase64VariantsAndInvalidDerAndWrongCurve() {
        listOf("TQ", "TQ=", "TR==", "TQ==\n", "TQ__", "TQ===", "").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { ProtocolV3.decodeBase64(it, "test") }
        }
        listOf("TWFu", "TWE=", "TQ==").forEach { assertEquals(it, Base64.getEncoder().encodeToString(ProtocolV3.decodeBase64(it, "test"))) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.encodeSignedEnvelope(evidence("request").payload, "MAA=") }
        val wrong = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        assertFalse(evidence("request").verify(Base64.getEncoder().encodeToString(wrong.public.encoded)))
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.signPayload(evidence("request").payload, wrong.private) }
    }

    @Test fun malformedSpkiAndUnsupportedCurveProduceControlledProtocolErrors() {
        val grant = evidence("authorization").payload
        val originalDer = bytes(grant["wallet_public_key_b64"].asString)
        val unknownCurveDer = originalDer.copyOf().apply { this[22] = 0x7f }
        listOf(byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x00), unknownCurveDer).forEach { der ->
            val changed = grant.deepCopy().apply {
                addProperty("wallet_public_key_b64", Base64.getEncoder().encodeToString(der))
                addProperty("wallet_key_fingerprint", ProtocolV3.payloadDigest(der))
            }
            assertThrows(IllegalArgumentException::class.java) { ProtocolV3.parseCanonicalPayload(ProtocolV3.canonicalJson(changed).toByteArray()) }
        }
    }

    @Test fun selectRequiresVersionLimitsAuthorizationInstructionAndIntegerTypes() {
        assertEquals(160, ProtocolV3.parseSelectCapabilities(ProtocolV3.SELECT_CAPABILITIES))
        val valid = ProtocolV3.SELECT_CAPABILITIES.toString(Charsets.UTF_8)
        listOf(valid.replace("\"protocol_version\":3", "\"protocol_version\":2"),
            valid.replace("\"protocol_version\":3", "\"protocol_version\":3.0"),
            valid.replace("\"authorization_instruction\":80", "\"authorization_instruction\":64"),
            valid.replace("\"max_message_bytes\":4096", "\"max_message_bytes\":2048"),
            valid.replace("\"chunk_bytes\":160", "\"chunk_bytes\":161")).forEach {
            assertThrows(IllegalArgumentException::class.java) { ProtocolV3.parseSelectCapabilities(it.toByteArray()) }
        }
        assertEquals(80, ProtocolV3.parseSelectCapabilities(valid.replace("\"chunk_bytes\":160", "\"chunk_bytes\":80").toByteArray()))
    }

    @Test fun completeEnvelopesCoverEveryBase64PaddingLength() {
        val pair = pair()
        val request = evidence("request").payload
        val paddings = mutableSetOf<Int>()
        for (extra in 0..8) {
            val changed = request.deepCopy().apply { addProperty("merchant_id", "merchant" + "x".repeat(extra)) }
            val signature = ProtocolV3.signPayload(changed, pair.private)
            val wire = ProtocolV3.encodeSignedEnvelope(changed, signature)
            val parsed = ProtocolV3.parseSignedEnvelope(wire)
            val encoded = Base64.getEncoder().encodeToString(parsed.payloadBytes)
            paddings += encoded.takeLastWhile { it == '=' }.length
            assertTrue(parsed.verify(Base64.getEncoder().encodeToString(pair.public.encoded)))
            assertFalse(wire.toString(Charsets.UTF_8).contains("\\u003d"))
        }
        assertEquals(setOf(0, 1, 2), paddings)
    }

    @Test fun verifiesPosOwnershipAndRejectsForeignSigningKeysAndScopes() {
        val request = evidence("request")
        val pos = keys["pos"].asJsonObject
        fun verify(merchant: String, terminal: String, public: String = publicKey("pos")) = ProtocolV3.verifyPosSigner(request, public,
            pos["key_id"].asString, pos["fingerprint"].asString, merchant, terminal)
        assertTrue(verify(request.payload["merchant_id"].asString, request.payload["terminal_id"].asString))
        assertFalse(verify("another-merchant", request.payload["terminal_id"].asString))
        assertFalse(verify(request.payload["merchant_id"].asString, "another-terminal"))
        assertFalse(verify(request.payload["merchant_id"].asString, request.payload["terminal_id"].asString, publicKey("wallet")))
        assertFalse(ProtocolV3.verifyAuthorization(evidence("authorization"), publicKey("pos")))
        assertFalse(ProtocolV3.verifyAuthorization(evidence("authorization"), publicKey("issuer"), "another-issuer"))
    }

    @Test fun bindingRejectsChangedGrantReceiptAndAckEvenWithConsistentTypedPayloads() {
        val request = evidence("request")
        val receipt = evidence("receipt")
        val grant = evidence("authorization")
        fun altered(original: SignedEnvelopeV3, field: String, value: String): SignedEnvelopeV3 {
            val payload = original.payload.deepCopy().apply { addProperty(field, value) }
            return SignedEnvelopeV3(ProtocolV3.canonicalJson(payload).toByteArray(), payload, original.signatureBase64)
        }
        listOf("wallet_id" to "wallet-other", "authorization_digest" to "0".repeat(64), "request_digest" to "1".repeat(64)).forEach { (field, value) ->
            assertThrows(IllegalArgumentException::class.java) { ProtocolV3.validateReceiptBinding(request, altered(receipt, field, value), grant) }
        }
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.validateAckBinding(request, receipt, altered(evidence("ack"), "pos_key_id", "another-key")) }
        val index = receipt.payload.deepCopy().apply { addProperty("issuance_index", grant.payload["max_payments"].asLong + 1) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.validateReceiptBinding(request, SignedEnvelopeV3(ProtocolV3.canonicalJson(index).toByteArray(), index, receipt.signatureBase64), grant) }
        val counterfeit = pair()
        val counterfeitReceipt = ProtocolV3.parseSignedEnvelope(ProtocolV3.encodeSignedEnvelope(receipt.payload, ProtocolV3.signPayload(receipt.payload, counterfeit.private)))
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.validateReceiptBinding(request, counterfeitReceipt, grant) }
    }

    @Test fun manifestsRequireExactTotalsOrderAndUniqueOperations() {
        val manifest = fixture["manifest"].asJsonArray
        val close = evidence("close")
        val grant = evidence("authorization")
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.validateCloseManifest(close, grant, "[]".toByteArray()) }
        val duplicate = manifest.deepCopy().apply { add(manifest[0].deepCopy()) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.parseCloseManifest(ProtocolV3.canonicalJson(duplicate).toByteArray()) }
        val overflow = grant.payload.deepCopy().apply { addProperty("max_payment_cents", Long.MAX_VALUE); addProperty("max_payments", 2) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.validatePayload(overflow) }
    }

    @Test fun logicalLimitIncludesEntireEnvelope() {
        val exact = JsonObject().apply { addProperty("x", "a".repeat(4088)) }
        assertEquals(4096, ProtocolV3.canonicalJson(exact).toByteArray().size)
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.canonicalJson(JsonObject().apply { addProperty("x", "a".repeat(4089)) }) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.parseSignedEnvelope(ByteArray(4097) { 'a'.code.toByte() }) }
        val v2 = ProtocolV2.encodeSignedEnvelope(JsonParser.parseString(requireNotNull(javaClass.classLoader?.getResourceAsStream("protocol_v2_vectors.json"))
            .reader().use { JsonParser.parseReader(it).asJsonObject["canonical_request"].asString }).asJsonObject,
            requireNotNull(javaClass.classLoader?.getResourceAsStream("protocol_v2_vectors.json")).reader().use { JsonParser.parseReader(it).asJsonObject["signature_der_b64"].asString })
        assertThrows(IllegalArgumentException::class.java) { ProtocolV3.parseSignedEnvelope(v2) }
    }

    @Test fun producesCoherentKotlinEnvelopesForIndependentPythonConsumption() {
        val pairs = listOf("pos", "wallet", "issuer").associateWith { pair() }
        val outputKeys = JsonObject().apply {
            pairs.forEach { (owner, pair) -> add(owner, JsonObject().apply {
                add("key_id", keys[owner].asJsonObject["key_id"].deepCopy())
                val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
                addProperty("public_key_b64", publicKey)
                addProperty("fingerprint", ProtocolV3.publicKeyFingerprint(publicKey))
            }) }
        }
        val payloads = messages.entrySet().associate { it.key to it.value.asJsonObject["payload"].asJsonObject.deepCopy() }.toMutableMap()
        payloads.values.forEach { payload ->
            listOf("pos", "wallet", "issuer").forEach { owner ->
                val field = "${owner}_key_fingerprint"
                if (payload.has(field)) payload.add(field, outputKeys[owner].asJsonObject["fingerprint"].deepCopy())
            }
        }
        payloads.getValue("authorization").add("wallet_public_key_b64", outputKeys["wallet"].asJsonObject["public_key_b64"].deepCopy())
        val outputMessages = JsonObject()
        fun sign(name: String) {
            val payload = payloads.getValue(name)
            val signer = messages[name].asJsonObject["signer"].asString
            val signature = ProtocolV3.signPayload(payload, pairs.getValue(signer).private)
            val encoded = ProtocolV3.encodeSignedEnvelope(payload, signature)
            val envelope = ProtocolV3.parseSignedEnvelope(encoded)
            assertTrue(envelope.verify(outputKeys[signer].asJsonObject["public_key_b64"].asString))
            outputMessages.add(name, JsonObject().apply {
                add("payload", payload.deepCopy())
                addProperty("payload_b64", Base64.getEncoder().encodeToString(envelope.payloadBytes))
                addProperty("signature_b64", signature)
                addProperty("envelope_b64", Base64.getEncoder().encodeToString(encoded))
                addProperty("payload_sha256", ProtocolV3.payloadDigest(envelope))
                addProperty("signer", signer)
            })
        }
        sign("authorization"); sign("request")
        val grantDigest = outputMessages["authorization"].asJsonObject["payload_sha256"].asString
        val requestDigest = outputMessages["request"].asJsonObject["payload_sha256"].asString
        payloads.getValue("receipt").apply { addProperty("authorization_digest", grantDigest); addProperty("request_digest", requestDigest) }
        sign("receipt")
        val receiptDigest = outputMessages["receipt"].asJsonObject["payload_sha256"].asString
        payloads.getValue("ack").addProperty("receipt_digest", receiptDigest); sign("ack")
        val manifest = fixture["manifest"].asJsonArray.deepCopy().apply { get(0).asJsonObject.addProperty("receipt_digest", receiptDigest) }
        val manifestBytes = ProtocolV3.canonicalJson(manifest).toByteArray()
        payloads.getValue("close").apply { addProperty("authorization_digest", grantDigest); addProperty("manifest_digest", ProtocolV3.payloadDigest(manifestBytes)) }; sign("close")
        payloads.getValue("resolution").apply { addProperty("authorization_digest", grantDigest); addProperty("request_digest", requestDigest); addProperty("receipt_digest", receiptDigest) }; sign("resolution")
        payloads.getValue("key_registration").add("fingerprint", outputKeys["wallet"].asJsonObject["fingerprint"].deepCopy()); sign("key_registration")
        val output = JsonObject().apply {
            addProperty("format_version", 1); addProperty("producer", "Kotlin JCA / ProtocolV3")
            add("keys", outputKeys); add("messages", outputMessages); add("manifest", manifest)
            addProperty("manifest_b64", Base64.getEncoder().encodeToString(manifestBytes))
            addProperty("manifest_sha256", ProtocolV3.payloadDigest(manifestBytes))
        }
        val directory = File(requireNotNull(System.getProperty("user.dir"))).let { if (it.name == "app") it else File(it, "app") }
        File(directory, "build/cross_language/v3_kotlin_vectors.json").apply {
            requireNotNull(parentFile).mkdirs()
            writeText(GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(output), Charsets.UTF_8)
        }
    }

    private fun pair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
}
