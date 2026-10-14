package com.example.prototipopagosoffline.protocol

import com.example.prototipopagosoffline.BuildConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Public keys pinned by external build configuration, never learned through HTTP. */
object IssuerTrust {
    fun publicKey(payload: JsonObject): String {
        val id = payload["issuer_key_id"].asString
        val fingerprint = payload["issuer_key_fingerprint"].asString
        if (BuildConfig.ISSUER_TRUST_JSON.isNotBlank()) {
            for (entry in JsonParser.parseString(BuildConfig.ISSUER_TRUST_JSON).asJsonArray) {
                val record = entry.asJsonObject
                val public = record["public_key_b64"].asString
                require(ProtocolV3.publicKeyFingerprint(public) == record["fingerprint"].asString)
                if (record["key_id"].asString == id && record["fingerprint"].asString == fingerprint) return public
            }
        }
        require(id == BuildConfig.ISSUER_KEY_ID)
        require(fingerprint == ProtocolV3.publicKeyFingerprint(BuildConfig.ISSUER_PUBLIC_KEY_B64))
        return BuildConfig.ISSUER_PUBLIC_KEY_B64
    }
}
