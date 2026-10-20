package com.example.prototipopagosoffline.sync

import androidx.room.withTransaction
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.LocalOfflineAuthorization
import com.example.prototipopagosoffline.network.ApiService
import com.example.prototipopagosoffline.network.OfflineAuthorizationCloseRequest
import com.example.prototipopagosoffline.protocol.ProtocolV3
import com.example.prototipopagosoffline.protocol.IssuerTrust
import com.example.prototipopagosoffline.utils.WalletKeyManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

class OfflineAuthorizationCloser(
    private val database: AppDatabase,
    private val api: ApiService,
    private val keyManager: WalletKeyManager = WalletKeyManager(),
    private val now: () -> Instant = { Instant.now() }
) {
    suspend fun closeExpired(walletId: String) {
        close(database.authorizationDao().getClosable(walletId, now().epochSecond))
    }

    suspend fun closeExpiredOrExhausted(walletId: String) {
        close(database.authorizationDao().getExplicitlyClosable(walletId, now().epochSecond))
    }

    private suspend fun close(authorizations: List<LocalOfflineAuthorization>) {
        for (authorization in authorizations) {
            val prepared = if (authorization.closureState == "READY") authorization else prepare(authorization) ?: continue
            val closure = prepared.closureEnvelope ?: continue
            val manifest = prepared.closureManifest ?: continue
            val response = syncHttp {
                api.closeOfflineAuthorizationV3(
                    prepared.id,
                    OfflineAuthorizationCloseRequest(
                        Base64.getEncoder().encodeToString(closure),
                        Base64.getEncoder().encodeToString(manifest)
                    )
                )
            }
            val result = response?.body()
            if (response?.isSuccessful == true && result?.closed == true
                && result.authorization_id == prepared.id
            ) database.authorizationDao().markClosed(prepared.id)
        }
    }

    private suspend fun prepare(authorization: LocalOfflineAuthorization): LocalOfflineAuthorization? {
        val dao = database.authorizationDao()
        var current = database.withTransaction {
            val stored = dao.getAuthorization(authorization.id) ?: return@withTransaction null
            if (stored.closureState == "READY" || stored.closureState == "FROZEN") return@withTransaction stored
            if (stored.closureState != "OPEN" || !stored.active) return@withTransaction null
            val grantBytes = stored.authorizationEnvelope ?: return@withTransaction null
            val grant = ProtocolV3.parseSignedEnvelope(grantBytes)
            val operations = database.nfcExchangeDao().getByAuthorization(stored.id)
            if (operations.any {
                    it.state !in setOf("SYNCED", "REJECTED", "RESERVED", "DELIVERY_UNCERTAIN", "LOCALLY_ACCEPTED")
                            || (!it.serverEvidenceStored && it.state !in setOf("SYNCED", "REJECTED"))
                            || (it.state == "REJECTED" && it.resolutionEnvelope == null)
                }) return@withTransaction null
            val receipts = operations.map { exchange ->
                val request = ProtocolV3.parseSignedEnvelope(exchange.requestEnvelope)
                val receipt = ProtocolV3.parseSignedEnvelope(requireNotNull(exchange.receiptEnvelope))
                ProtocolV3.validateReceiptBinding(request, receipt, grant)
                require(receipt.payload["operation_id"].asString == exchange.operationId)
                require(receipt.payload["wallet_id"].asString == stored.walletId)
                require(receipt.payload["offline_authorization_id"].asString == stored.id)
                if (exchange.state == "REJECTED") {
                    // A rejected receipt still consumed its permanent index.
                    // Only an issuer-signed final result proves its disposition.
                    val resolution = ProtocolV3.parseSignedEnvelope(requireNotNull(exchange.resolutionEnvelope))
                    val result = resolution.payload
                    require(result["type"].asString == "payment_resolution")
                    require(resolution.verify(IssuerTrust.publicKey(result)))
                    require(result["operation_status"].asString == "REJECTED")
                    require(result["operation_id"].asString == exchange.operationId)
                    require(result["wallet_id"].asString == stored.walletId)
                    require(result["offline_authorization_id"].asString == stored.id)
                    require(result["merchant_id"].asString == request.payload["merchant_id"].asString)
                    require(result["terminal_id"].asString == request.payload["terminal_id"].asString)
                    require(result["amount_cents"].asLong == receipt.payload["amount_cents"].asLong)
                    require(result["currency"].asString == ProtocolV3.CURRENCY)
                    require(result["request_digest"].asString == ProtocolV3.payloadDigest(request))
                    require(result["receipt_digest"].asString == ProtocolV3.payloadDigest(receipt))
                    require(result["authorization_digest"].asString == ProtocolV3.payloadDigest(grant))
                }
                receipt
            }.sortedBy { it.payload["issuance_index"].asInt }
            if (receipts.map { it.payload["issuance_index"].asInt } != (1..receipts.size).toList()) {
                return@withTransaction null
            }
            if (receipts.size > grant.payload["max_payments"].asInt) return@withTransaction null
            val manifest = JsonArray().apply {
                receipts.forEach { receipt ->
                    add(JsonObject().apply {
                        addProperty("issuance_index", receipt.payload["issuance_index"].asInt)
                        addProperty("operation_id", receipt.payload["operation_id"].asString)
                        addProperty("amount_cents", receipt.payload["amount_cents"].asLong)
                        addProperty("receipt_digest", ProtocolV3.payloadDigest(receipt))
                    })
                }
            }
            val manifestBytes = ProtocolV3.canonicalJson(manifest).toByteArray(Charsets.UTF_8)
            val metadata = dao.getKeyMetadata(stored.walletId, stored.walletKeyId)
                ?: return@withTransaction null
            if (!keyManager.hasKey(metadata.keyStoreAlias)) return@withTransaction null
            val emittedTotal = receipts.fold(0L) { total, receipt ->
                Math.addExact(total, receipt.payload["amount_cents"].asLong)
            }
            require(emittedTotal <= grant.payload["authorized_total_cents"].asLong)
            val payload = JsonObject().apply {
                addProperty("version", ProtocolV3.VERSION)
                addProperty("type", "offline_authorization_close")
                addProperty("closure_id", UUID.randomUUID().toString())
                addProperty("authorization_id", stored.id)
                addProperty("wallet_id", stored.walletId)
                addProperty("wallet_key_id", stored.walletKeyId)
                addProperty("wallet_key_fingerprint", metadata.fingerprint)
                addProperty("authorization_digest", ProtocolV3.payloadDigest(grant))
                addProperty("final_issuance_index", receipts.size)
                addProperty("emitted_total_cents", emittedTotal)
                addProperty("manifest_digest", ProtocolV3.payloadDigest(manifestBytes))
                addProperty("issued_at", now().truncatedTo(ChronoUnit.SECONDS).toString())
            }
            val payloadBytes = ProtocolV3.canonicalJson(payload).toByteArray(Charsets.UTF_8)
            check(dao.freezeClosurePayload(stored.id, payloadBytes, manifestBytes) == 1)
            dao.getAuthorization(stored.id)
        } ?: return null

        if (current.closureState == "FROZEN") {
            val payloadBytes = current.closurePayload ?: return null
            val manifestBytes = current.closureManifest ?: return null
            val metadata = dao.getKeyMetadata(current.walletId, current.walletKeyId) ?: return null
            if (!keyManager.hasKey(metadata.keyStoreAlias)) return null
            val payload = ProtocolV3.parseCanonicalPayload(payloadBytes)
            val grant = ProtocolV3.parseSignedEnvelope(requireNotNull(current.authorizationEnvelope))
            val manifest = ProtocolV3.parseCloseManifest(manifestBytes)
            require(payload["authorization_id"].asString == current.id)
            require(payload["manifest_digest"].asString == ProtocolV3.payloadDigest(manifestBytes))
            require(payload["final_issuance_index"].asInt == manifest.size)
            val closure = ProtocolV3.encodeSignedEnvelope(
                payload, keyManager.sign(metadata.keyStoreAlias, payloadBytes)
            )
            ProtocolV3.validateCloseManifest(ProtocolV3.parseSignedEnvelope(closure), grant, manifestBytes)
            check(dao.saveClosureEnvelope(current.id, closure) == 1)
            current = dao.getAuthorization(current.id) ?: return null
        }
        return current.takeIf { it.closureState == "READY" }
    }
}
