package com.example.prototipopagosoffline.utils

import androidx.room.withTransaction
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.AuthorizationIssuanceAttempt
import com.example.prototipopagosoffline.database.LocalOfflineAuthorization
import com.example.prototipopagosoffline.database.OfflineWallet
import com.example.prototipopagosoffline.database.TrustedPublicKey
import com.example.prototipopagosoffline.network.ApiService
import com.example.prototipopagosoffline.network.KeyRegistrationRequest
import com.example.prototipopagosoffline.network.LegacyInventoryEntry
import com.example.prototipopagosoffline.network.LegacyInventoryRequest
import com.example.prototipopagosoffline.network.LoginResponse
import com.example.prototipopagosoffline.network.OfflineAuthorizationRequest
import com.example.prototipopagosoffline.protocol.ProtocolV2
import com.example.prototipopagosoffline.protocol.ProtocolV3
import com.example.prototipopagosoffline.sync.AccountOperationLocks
import com.example.prototipopagosoffline.sync.OfflineAuthorizationCloser
import com.example.prototipopagosoffline.sync.V2SyncRepository
import com.example.prototipopagosoffline.sync.SyncPauseRequired
import com.example.prototipopagosoffline.sync.syncHttp
import com.example.prototipopagosoffline.BuildConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

class OfflineProvisioner(
    private val database: AppDatabase,
    private val api: ApiService,
    private val paymentCoordinator: OfflinePaymentCoordinator = OfflinePaymentCoordinator(database),
    private val now: () -> Instant = { Instant.now() },
    private val requestStore: TokenManager? = null
) {
    suspend fun provision(login: LoginResponse) = AccountOperationLocks.withLock("cliente", login.wallet_id) {
        maintainAfterSync(login)
    }

    suspend fun renew(login: LoginResponse) = provision(login)

    // SyncWorker already owns this account's mutex; do not acquire it recursively.
    internal suspend fun maintainAfterSync(login: LoginResponse, synchronizeClosable: Boolean = true) {
        requireActiveSession(login)
        val nowSeconds = now().epochSecond
        val closable = database.authorizationDao().getExplicitlyClosable(login.wallet_id, nowSeconds)
        if (synchronizeClosable) closable.forEach { authorization ->
            V2SyncRepository(database, api, now).syncAuthorization(authorization.id)
        }
        OfflineAuthorizationCloser(database, api, now = now).closeExpiredOrExhausted(login.wallet_id)
        // A closure that cannot yet prove its manifest must keep its commitment.
        // Other free funds can still be authorized; the server enforces that budget.
        try {
            provisionLocked(login)
        } catch (_: AuthorizationBudgetUnavailable) {
            // An empty budget is normal. Keep the stable attempt for a later top-up.
        }
    }

    private fun requireActiveSession(login: LoginResponse) {
        require(login.wallet_id.isNotBlank())
        requestStore?.let { session ->
            require(session.getToken() == login.access_token
                    && session.getRole() == "cliente"
                    && session.getWalletId() == login.wallet_id
                    && session.getUserId() == login.user_id) {
                "La sesion cambio; volve a ingresar"
            }
        }
    }

    private suspend fun provisionLocked(login: LoginResponse, recoveryPasses: Int = 2) {
        requireActiveSession(login)
        val keyId = "wallet-key:${sha256(login.wallet_id).take(24)}"
        val key = paymentCoordinator.registerWalletKey(login.wallet_id, keyId)
        val proofBytes = buildKeyRegistrationProof(login, key, now(), registrationNonce())
        val keyResponse = syncHttp { api.registerKey(
            KeyRegistrationRequest(
                public_key_b64 = key.publicKeyBase64,
                proof_payload_b64 = Base64.getEncoder().encodeToString(proofBytes),
                proof_signature_b64 = paymentCoordinator.signWithWalletKey(key.alias, proofBytes)
            )
        ) } ?: throw java.io.IOException("Registro de clave temporalmente no disponible")
        require(keyResponse.isSuccessful) { "No se pudo registrar la clave de billetera" }
        val registered = requireNotNull(keyResponse.body()) { "Respuesta de clave vacia" }
        require(registered.key_id == key.keyId && registered.fingerprint == key.fingerprint)
        require(registered.scope_id == login.wallet_id && registered.purpose == "wallet")

        val trustedPosResponse = syncHttp { api.getTrustedKeys("pos") }
            ?: throw java.io.IOException("Claves POS temporalmente no disponibles")
        require(trustedPosResponse.isSuccessful) { "No se pudieron actualizar las claves POS" }
        database.withTransaction {
            database.nfcExchangeDao().revokeInventory("pos")
            requireNotNull(trustedPosResponse.body()).forEach { trusted ->
                require(trusted.purpose == "pos" && trusted.key_id.isNotBlank())
                database.nfcExchangeDao().insertTrustedKey(
                    TrustedPublicKey(
                        keyId = trusted.key_id,
                        fingerprint = trusted.fingerprint,
                        publicKeyBase64 = trusted.public_key_b64,
                        ownerType = "pos",
                        scopeId = trusted.scope_id
                    )
                )
            }
        }

        registerLegacyInventory(login.wallet_id)

        val balanceResponse = syncHttp { api.getWalletBalance(login.wallet_id) }
            ?: throw java.io.IOException("Saldo temporalmente no disponible")
        require(balanceResponse.isSuccessful) { "No se pudo consultar el saldo" }
        val balance = requireNotNull(balanceResponse.body()) { "Respuesta de saldo vacia" }
        require(balance.wallet_id == login.wallet_id && balance.currency == ProtocolV2.CURRENCY)
        require(balance.confirmed_balance_cents >= 0)

        saveWallet(balance.confirmed_balance_cents, login.wallet_id)
        if (balance.review_required) throw SyncPauseRequired("ATTENTION_REQUIRED")
        val dao = database.authorizationDao()
        val pendingAttempt = dao.getIssuanceAttempt(login.wallet_id)
        val legacyRequestId = requestStore?.peekPendingRequestId("authorization", login.wallet_id)
        if (legacyRequestId != null && legacyRequestId != pendingAttempt?.requestId) {
            val historicalAttempt = AuthorizationIssuanceAttempt(
                walletId = login.wallet_id,
                requestId = legacyRequestId,
                walletKeyId = key.keyId,
                maxPaymentCents = 100_000L,
                maxPayments = 10,
                createdAtEpochSeconds = now().epochSecond
            )
            val recovered = issueAttempt(login, key, balance.confirmed_balance_cents,
                historicalAttempt, completeRoomAttempt = false)
            requestStore?.completeRequest("authorization", login.wallet_id)
            if (recovered.expiresAtEpochSeconds <= now().epochSecond ||
                recovered.remainingAmountCents <= 0 || recovered.remainingPayments <= 0) {
                OfflineAuthorizationCloser(database, api, now = now)
                    .closeExpiredOrExhausted(login.wallet_id)
            }
            // Re-read the budget after recovery; bound recovery passes so a bad
            // clock or a stale server response cannot create an emission loop.
            if (recoveryPasses > 0) provisionLocked(login, recoveryPasses - 1)
            return
        }

        if (balance.available_for_authorization_cents <= 0 &&
            (pendingAttempt == null || pendingAttempt.lastResultCode == "AUTHORIZATION_BUDGET_UNAVAILABLE")) {
            return
        }

        requireActiveSession(login)
        val attempt = database.withTransaction {
            val existing = dao.getIssuanceAttempt(login.wallet_id)
            if (existing != null) {
                require(existing.walletKeyId == key.keyId
                        && existing.maxPaymentCents == ProtocolV2.MAX_AMOUNT_CENTS
                        && existing.maxPayments == 10) {
                    "Hay una solicitud de autorizacion anterior que requiere recuperacion"
                }
                existing
            } else {
                val created = AuthorizationIssuanceAttempt(
                    walletId = login.wallet_id,
                    requestId = UUID.randomUUID().toString(),
                    walletKeyId = key.keyId,
                    maxPaymentCents = ProtocolV2.MAX_AMOUNT_CENTS,
                    maxPayments = 10,
                    createdAtEpochSeconds = now().epochSecond
                )
                dao.insertIssuanceAttempt(created)
                created
            }
        }
        val issued = issueAttempt(login, key, balance.confirmed_balance_cents,
            attempt, completeRoomAttempt = true)
        if (legacyRequestId == attempt.requestId) {
            requestStore?.completeRequest("authorization", login.wallet_id)
        }
        if (issued.expiresAtEpochSeconds <= now().epochSecond) {
            OfflineAuthorizationCloser(database, api, now = now)
                .closeExpiredOrExhausted(login.wallet_id)
            if (recoveryPasses > 0) provisionLocked(login, recoveryPasses - 1)
            return
        }
    }

    private suspend fun issueAttempt(
        login: LoginResponse,
        key: WalletKeyMaterial,
        confirmedBalance: Long,
        attempt: AuthorizationIssuanceAttempt,
        completeRoomAttempt: Boolean
    ): LocalOfflineAuthorization {
        require(BuildConfig.ISSUER_PUBLIC_KEY_B64.isNotBlank() && BuildConfig.ISSUER_KEY_ID.isNotBlank()) {
            "Confianza del emisor v3 no configurada"
        }
        val dao = database.authorizationDao()
        val issuanceRequest = OfflineAuthorizationRequest(
            request_id = attempt.requestId,
            wallet_id = attempt.walletId,
            wallet_key_id = attempt.walletKeyId,
            max_payment_cents = attempt.maxPaymentCents,
            max_payments = attempt.maxPayments
        )
        if (completeRoomAttempt) check(dao.markIssuanceSent(login.wallet_id, attempt.requestId) == 1)
        requireActiveSession(login)
        val authorizationResponse = api.createOfflineAuthorizationV3(issuanceRequest)
        if (!authorizationResponse.isSuccessful) {
            val serverCode = if (authorizationResponse.code() == 409) runCatching {
                JsonParser.parseString(authorizationResponse.errorBody()?.string().orEmpty())
                    .asJsonObject.getAsJsonObject("detail")?.get("code")?.asString
            }.getOrNull() else null
            if (completeRoomAttempt) dao.recordIssuanceResult(
                login.wallet_id, attempt.requestId, serverCode ?: "HTTP_${authorizationResponse.code()}"
            )
            if (serverCode == "AUTHORIZATION_BUDGET_UNAVAILABLE") throw AuthorizationBudgetUnavailable()
            if (authorizationResponse.code() in setOf(401, 403)) throw SyncPauseRequired("REAUTH_REQUIRED")
            if (authorizationResponse.code() in setOf(400, 404, 409, 410, 413, 415, 422))
                throw SyncPauseRequired("ATTENTION_REQUIRED")
            error(when (authorizationResponse.code()) {
                409 -> "No hay cupo libre para una nueva autorizacion; los pagos pendientes siguen reservados"
                401, 403 -> "La sesion requiere volver a ingresar"
                else -> "No se pudo obtener autorizacion offline (HTTP ${authorizationResponse.code()})"
            })
        }
        val authorization = requireNotNull(authorizationResponse.body()) { "Respuesta de autorizacion vacia" }
        val grantBytes = Base64.getDecoder().decode(authorization.authorization_envelope_b64)
        val grant = ProtocolV3.parseSignedEnvelope(grantBytes)
        require(ProtocolV3.verifyAuthorization(grant,
            com.example.prototipopagosoffline.protocol.IssuerTrust.publicKey(grant.payload)))
        val payload = grant.payload
        require(payload["authorization_id"].asString == authorization.authorization_id)
        require(payload["wallet_id"].asString == login.wallet_id)
        require(payload["wallet_key_id"].asString == key.keyId)
        require(payload["currency"].asString == ProtocolV3.CURRENCY)
        require(payload["max_payment_cents"].asLong == attempt.maxPaymentCents)
        require(payload["max_payments"].asInt == attempt.maxPayments)
        require(payload["authorized_total_cents"].asLong == authorization.authorized_total_cents)
        require(authorization.authorized_total_cents in 1..(attempt.maxPaymentCents * attempt.maxPayments))
        require(payload["wallet_key_fingerprint"].asString == key.fingerprint)
        require(payload["wallet_public_key_b64"].asString == key.publicKeyBase64)
        require(Instant.parse(payload["issued_at"].asString).epochSecond == Instant.parse(authorization.issued_at).epochSecond)
        require(Instant.parse(payload["spend_expires_at"].asString).epochSecond == Instant.parse(authorization.spend_expires_at).epochSecond)

        database.withTransaction {
            saveWallet(confirmedBalance, login.wallet_id)
            val stored = dao.getAuthorization(authorization.authorization_id)
            if (stored != null) {
                check(stored.authorizationEnvelope?.contentEquals(grantBytes) == true) {
                    "La autorizacion recibida no coincide con el registro local"
                }
            } else dao.upsertAuthorization(
                LocalOfflineAuthorization(
                    id = authorization.authorization_id,
                    walletId = authorization.wallet_id,
                    walletKeyId = payload["wallet_key_id"].asString,
                    maxPaymentCents = payload["max_payment_cents"].asLong,
                    remainingAmountCents = authorization.authorized_total_cents,
                    remainingPayments = payload["max_payments"].asInt,
                    issuedAtEpochSeconds = Instant.parse(authorization.issued_at).epochSecond,
                    expiresAtEpochSeconds = Instant.parse(authorization.spend_expires_at).epochSecond,
                    authorizationEnvelope = grantBytes
                )
            )
            if (completeRoomAttempt) check(dao.completeIssuanceAttempt(login.wallet_id, attempt.requestId) == 1)
        }
        return requireNotNull(dao.getAuthorization(authorization.authorization_id))
    }

    private suspend fun saveWallet(confirmedBalance: Long, walletId: String) {
        database.walletDao().insertWallet(
            OfflineWallet(
                id = walletId,
                confirmedBalanceCents = confirmedBalance,
                reservedBalanceCents = 0,
                currency = ProtocolV2.CURRENCY
            )
        )
        check(database.walletDao().updateConfirmedBalance(
            walletId, confirmedBalance, ProtocolV2.CURRENCY
        ) == 1)
    }

    private suspend fun registerLegacyInventory(walletId: String) {
        while (true) {
            val pending = database.nfcExchangeDao().getLegacyInventoryPending(walletId, 100)
            if (pending.isEmpty()) return
            val entries = pending.map { exchange ->
                val receipt = ProtocolV2.parseSignedEnvelope(requireNotNull(exchange.receiptEnvelope))
                LegacyInventoryEntry(
                    operation_id = exchange.operationId,
                    authorization_id = requireNotNull(exchange.authorizationId),
                    receipt_digest = ProtocolV2.payloadDigest(receipt.payload)
                )
            }
            val response = syncHttp { api.registerLegacyInventory(LegacyInventoryRequest(walletId, entries)) }
                ?: throw java.io.IOException("Inventario historico temporalmente no disponible")
            require(response.isSuccessful && response.body()?.total == entries.size) {
                "No se pudo registrar el inventario historico v2"
            }
            check(database.nfcExchangeDao().markLegacyInventoryRegistered(
                pending.map { it.operationId }
            ) == pending.size)
        }
    }

    private fun registrationNonce(): String {
        val value = ByteArray(16).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

}

private class AuthorizationBudgetUnavailable : Exception()

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
