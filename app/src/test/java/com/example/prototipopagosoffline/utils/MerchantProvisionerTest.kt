package com.example.prototipopagosoffline.utils

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class MerchantProvisionerTest {
    @Test
    fun `registration proof binds merchant terminal and exact key`() {
        val key = TerminalKeyMaterial(
            keyId = "mobile-pos-key-1",
            alias = "unused-in-jvm",
            publicKeyBase64 = "unused",
            fingerprint = "a".repeat(64)
        )
        val proof = buildTerminalRegistrationProof(
            userId = 7,
            terminalId = "mobile-terminal-7",
            key = key,
            issuedAt = Instant.parse("2026-10-14T12:00:00Z"),
            nonce = "abcdefghijklmnopqrstuv"
        )
        val payload = JsonParser.parseString(proof.toString(Charsets.UTF_8)).asJsonObject

        assertEquals("key_registration", payload["type"].asString)
        assertEquals("pos", payload["purpose"].asString)
        assertEquals(7, payload["owner_user_id"].asInt)
        assertEquals("mobile-terminal-7", payload["scope_id"].asString)
        assertEquals(key.keyId, payload["key_id"].asString)
        assertEquals(key.fingerprint, payload["fingerprint"].asString)
    }
}
