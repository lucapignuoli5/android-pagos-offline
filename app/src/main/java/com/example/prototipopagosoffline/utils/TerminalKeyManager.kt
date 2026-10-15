package com.example.prototipopagosoffline.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

data class TerminalKeyMaterial(
    val keyId: String,
    val alias: String,
    val publicKeyBase64: String,
    val fingerprint: String
)

class TerminalKeyManager {
    private val keyStoreName = "AndroidKeyStore"

    fun hasKey(alias: String): Boolean =
        KeyStore.getInstance(keyStoreName).apply { load(null) }.containsAlias(alias)

    fun ensureKey(terminalId: String, keyId: String): TerminalKeyMaterial {
        val alias = alias(terminalId, keyId)
        val keyStore = KeyStore.getInstance(keyStoreName).apply { load(null) }
        if (!keyStore.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, keyStoreName)
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generator.generateKeyPair()
        }
        val publicDer = keyStore.getCertificate(alias).publicKey.encoded
        return TerminalKeyMaterial(
            keyId,
            alias,
            Base64.getEncoder().encodeToString(publicDer),
            sha256(publicDer)
        )
    }

    fun sign(alias: String, payloadBytes: ByteArray): String {
        val keyStore = KeyStore.getInstance(keyStoreName).apply { load(null) }
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey
            ?: throw IllegalStateException("Clave del terminal no encontrada")
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(payloadBytes)
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    private fun alias(terminalId: String, keyId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$terminalId:$keyId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "tfg_terminal_${digest.take(32)}"
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }
}
