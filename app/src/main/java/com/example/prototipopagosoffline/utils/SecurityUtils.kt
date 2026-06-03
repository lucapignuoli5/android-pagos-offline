package com.example.prototipopagosoffline.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature

object SecurityUtils {
    private const val KEY_ALIAS = "PaymentKeyAlias"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    fun generateKeyPairIfNeeded() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE
            )
            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).run {
                setDigests(KeyProperties.DIGEST_SHA256)
                build()
            }
            kpg.initialize(parameterSpec)
            kpg.generateKeyPair()
        }
    }

    fun signPayload(data: String): String {
        generateKeyPairIfNeeded()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey
        
        // Debugging Criptográfico
        val hashString = java.security.MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val publicKeyBytes = publicKey.encoded 
        val hashPublicKey = java.security.MessageDigest.getInstance("SHA-256")
            .digest(publicKeyBytes)
            .joinToString("") { "%02x".format(it) }

        android.util.Log.d("FIRMA_DEBUG", "String firmado: '$data'")
        android.util.Log.d("FIRMA_DEBUG", "SHA256 string UTF-8: $hashString")
        android.util.Log.d("FIRMA_DEBUG", "public_key_sha256: $hashPublicKey")

        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(data.toByteArray(Charsets.UTF_8))
        val signatureBytes = signature.sign()
        return Base64.encodeToString(signatureBytes, Base64.DEFAULT).trim()
    }

    fun getPublicKey(): String {
        generateKeyPairIfNeeded()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey
        return Base64.encodeToString(publicKey.encoded, Base64.DEFAULT).trim()
    }
}
