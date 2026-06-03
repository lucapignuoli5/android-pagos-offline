package com.example.prototipopagosoffline

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

object CryptoManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "offline_payment_key"
    private const val KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_EC
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val EC_CURVE = "secp256r1"

    fun generateKeysIfNeeded() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
            }

            if (keyStore.containsAlias(KEY_ALIAS)) {
                return
            }

            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()

            KeyPairGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEYSTORE).apply {
                initialize(parameterSpec)
                generateKeyPair()
            }
        } catch (exception: GeneralSecurityException) {
            throw SecurityException("No se pudo generar la llave ECDSA en Android Keystore.", exception)
        } catch (exception: IllegalStateException) {
            throw SecurityException("Android Keystore no esta disponible para generar la llave.", exception)
        }
    }

    fun exportPublicKeyPem(): String {
        return try {
            generateKeysIfNeeded()

            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
            }
            val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey
                ?: throw SecurityException("No se encontro la llave publica en Android Keystore.")

            val base64PublicKey = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
            val pemBody = base64PublicKey.chunked(64).joinToString(separator = "\n")

            "-----BEGIN PUBLIC KEY-----\n$pemBody\n-----END PUBLIC KEY-----"
        } catch (exception: GeneralSecurityException) {
            throw SecurityException("No se pudo exportar la llave publica.", exception)
        } catch (exception: IllegalStateException) {
            throw SecurityException("Android Keystore no esta disponible para exportar la llave publica.", exception)
        }
    }

    fun signText(text: String): String {
        return try {
            generateKeysIfNeeded()

            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
            }
            val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
                ?: throw SecurityException("No se encontro la llave privada en Android Keystore.")

            val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initSign(privateKey)
                update(text.toByteArray(Charsets.UTF_8))
            }

            Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
        } catch (exception: GeneralSecurityException) {
            throw SecurityException("No se pudo firmar el texto con la llave privada.", exception)
        } catch (exception: IllegalStateException) {
            throw SecurityException("Android Keystore no esta disponible para firmar.", exception)
        }
    }
}
