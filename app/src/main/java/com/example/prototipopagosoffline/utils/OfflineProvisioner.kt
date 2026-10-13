package com.example.prototipopagosoffline.utils

import androidx.room.withTransaction
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.LocalOfflineAuthorization
import com.example.prototipopagosoffline.database.OfflineWallet
import com.example.prototipopagosoffline.database.TrustedPublicKey
import com.example.prototipopagosoffline.network.ApiService
import com.example.prototipopagosoffline.network.KeyRegistrationRequest
import com.example.prototipopagosoffline.network.LoginResponse
import com.example.prototipopagosoffline.network.OfflineAuthorizationRequest
import com.example.prototipopagosoffline.protocol.ProtocolV2
import com.google.gson.JsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

class OfflineProvisioner(
    private val database: AppDatabase,
    private val api: ApiService,
    private val paymentCoordinator: OfflinePaymentCoordinator = OfflinePaymentCoordinator(database),
    private val now: () -> Instant = { Instant.now() }
) {
    suspend fun provision(login: LoginResponse) {
        require(login.wallet_id.isNotBlank())
        val keyId = "wallet-key:${sha256(login.wallet_id).take(24)}"
        val key = paymentCoordinator.registerWalletKey(login.wallet_id, keyId)
        val proofBytes = buildKeyRegistrationProof(login, key, now(), registrationNonce())
        val keyResponse = api.registerKey(
            KeyRegistrationRequest(
                public_key_b64 = key.publicKeyBase64,
                proof_payload_b64 = Base64.getEncoder().encodeToString(proofBytes),
                proof_signature_b64 = paymentCoordinator.signWithWalletKey(key.alias, proofBytes)
            )
        )
        require(keyResponse.isSuccessful) { "No se pudo registrar la clave de billetera" }
        val registered = requireNotNull(keyResponse.body()) { "Respuesta de clave vacia" }
        require(registered.key_id == key.keyId && registered.fingerprint == key.fingerprint)
        require(registered.scope_id == login.wallet_id && registered.purpose == "wallet")

        val trustedPosResponse = api.getTrustedKeys("pos")
        require(trustedPosResponse.isSuccessful) { "No se pudieron actualizar las claves POS" }
        requireNotNull(trustedPosResponse.body()).forEach { trusted ->
            require(trusted.purpose == "pos" && trusted.key_id.isNotBlank())
            database.nfcExchangeDao().insertTrustedKey(
                TrustedPublicKey(
                    keyId = trusted.key_id,
                    fingerprint = trusted.fingerprint,
                    publicKeyBase64 = trusted.public_key_b64,
                    ownerType = "pos"
                )
            )
        }

        val balanceResponse = api.getWalletBalance(login.wallet_id)
        require(balanceResponse.isSuccessful) { "No se pudo consultar el saldo" }
        val balance = requireNotNull(balanceResponse.body()) { "Respuesta de saldo vacia" }
        require(balance.wallet_id == login.wallet_id && balance.currency == ProtocolV2.CURRENCY)
        require(balance.confirmed_balance_cents >= 0)

        val currentAuthorization = database.authorizationDao()
            .findActiveForProvisioning(login.wallet_id, key.keyId, now().epochSecond)
        if (currentAuthorization != null) {
            saveWallet(balance.confirmed_balance_cents, login.wallet_id)
            return
        }

        val authorizationResponse = api.createOfflineAuthorization(
            OfflineAuthorizationRequest(
                wallet_id = login.wallet_id,
                wallet_key_id = key.keyId,
                max_payment_cents = ProtocolV2.MAX_AMOUNT_CENTS,
                max_payments = 10
            )
        )
        require(authorizationResponse.isSuccessful) { "No se pudo obtener autorizacion offline" }
        val authorization = requireNotNull(authorizationResponse.body()) { "Respuesta de autorizacion vacia" }
        require(authorization.wallet_id == login.wallet_id && authorization.wallet_key_id == key.keyId)
        require(authorization.currency == ProtocolV2.CURRENCY)
        require(authorization.max_payment_cents in 1..ProtocolV2.MAX_AMOUNT_CENTS)
        require(authorization.max_payments in 1..10)
        require(authorization.authorized_total_cents >= authorization.max_payment_cents)

        database.withTransaction {
            saveWallet(balance.confirmed_balance_cents, login.wallet_id)
            database.authorizationDao().upsertAuthorization(
                LocalOfflineAuthorization(
                    id = authorization.authorization_id,
                    walletId = authorization.wallet_id,
                    walletKeyId = authorization.wallet_key_id,
                    maxPaymentCents = authorization.max_payment_cents,
                    remainingAmountCents = authorization.authorized_total_cents,
                    remainingPayments = authorization.max_payments,
                    issuedAtEpochSeconds = Instant.parse(authorization.issued_at).epochSecond,
                    expiresAtEpochSeconds = Instant.parse(authorization.expires_at).epochSecond
                )
            )
        }
    }

    private suspend fun saveWallet(confirmedBalance: Long, walletId: String) {
        val current = database.walletDao().getWalletById(walletId)
        database.walletDao().insertWallet(
            OfflineWallet(
                id = walletId,
                confirmedBalanceCents = confirmedBalance,
                reservedBalanceCents = current?.reservedBalanceCents ?: 0,
                currency = ProtocolV2.CURRENCY
            )
        )
    }

    private fun registrationNonce(): String {
        val value = ByteArray(16).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

}

internal fun buildKeyRegistrationProof(
    login: LoginResponse,
    key: WalletKeyMaterial,
    issuedAt: Instant,
    nonce: String
): ByteArray {
    require(nonce.length in 16..120)
    val timestampFormat = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)
    val proof = JsonObject().apply {
        addProperty("version", ProtocolV2.VERSION)
        addProperty("type", "key_registration")
        addProperty("key_id", key.keyId)
        addProperty("purpose", "wallet")
        addProperty("owner_user_id", login.user_id)
        addProperty("scope_id", login.wallet_id)
        addProperty("fingerprint", key.fingerprint)
        addProperty("nonce", nonce)
        addProperty("issued_at", timestampFormat.format(issuedAt))
    }
    return ProtocolV2.canonicalJson(proof).toByteArray(Charsets.UTF_8)
}
