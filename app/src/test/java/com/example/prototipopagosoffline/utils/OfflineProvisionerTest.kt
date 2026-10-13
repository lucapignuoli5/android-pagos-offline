package com.example.prototipopagosoffline.utils

import com.example.prototipopagosoffline.network.LoginResponse
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class OfflineProvisionerTest {
    @Test
    fun `registration proof binds authenticated user wallet and key`() {
        val proof = buildKeyRegistrationProof(
            login = LoginResponse("token", "bearer", 3600, 7, "wallet-7"),
            key = WalletKeyMaterial("wallet-key:abc", "alias", "public", "fingerprint"),
            issuedAt = Instant.parse("2026-10-13T12:34:56Z"),
            nonce = "abcdefghijklmnopqrstuv"
        )
        val parsed = JsonParser.parseString(proof.toString(Charsets.UTF_8)).asJsonObject

        assertEquals(7, parsed["owner_user_id"].asInt)
        assertEquals("wallet-7", parsed["scope_id"].asString)
        assertEquals("wallet-key:abc", parsed["key_id"].asString)
        assertEquals("fingerprint", parsed["fingerprint"].asString)
        assertEquals("2026-10-13T12:34:56Z", parsed["issued_at"].asString)
    }
}
