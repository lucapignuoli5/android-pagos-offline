package com.example.prototipopagosoffline.sync

import androidx.room.withTransaction
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.LocalOfflineAuthorization
import com.example.prototipopagosoffline.network.ApiService
import com.example.prototipopagosoffline.network.OfflineAuthorizationCloseRequest
import com.example.prototipopagosoffline.protocol.ProtocolV3
import com.example.prototipopagosoffline.utils.WalletKeyManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
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
        for (authorization in database.authorizationDao().getClosable(walletId, now().epochSecond)) {
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
            if (response?.isSuccessful == true) database.authorizationDao().markClosed(prepared.id)
        }
    }

    private suspend fun prepare(authorization: LocalOfflineAuthorization): LocalOfflineAuthorization? {
        val grantBytes = authorization.authorizationEnvelope ?: return null
        val grant = ProtocolV3.parseSignedEnvelope(grantBytes)
        val operations = database.nfcExchangeDao().getByAuthorization(authorization.id)
        if (operations.any { it.state !in setOf("SYNCED", "REJECTED") }) return null
        val receipts = operations.mapNotNull { it.receiptEnvelope?.let(ProtocolV3::parseSignedEnvelope) }
            .sortedBy { it.payload["issuance_index"].asInt }
        if (receipts.map { it.payload["issuance_index"].asInt } != (1..receipts.size).toList()) return null
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
        val metadata = database.authorizationDao().getKeyMetadata(authorization.walletId, authorization.walletKeyId)
            ?: return null
        if (!keyManager.hasKey(metadata.keyStoreAlias)) return null
        val payload = JsonObject().apply {
            addProperty("version", ProtocolV3.VERSION)
            addProperty("type", "offline_authorization_close")
            addProperty("closure_id", UUID.randomUUID().toString())
            addProperty("authorization_id", authorization.id)
            addProperty("wallet_id", authorization.walletId)
            addProperty("wallet_key_id", authorization.walletKeyId)
            addProperty("wallet_key_fingerprint", metadata.fingerprint)
            addProperty("authorization_digest", ProtocolV3.payloadDigest(grant))
            addProperty("final_issuance_index", receipts.size)
            addProperty("emitted_total_cents", receipts.sumOf { it.payload["amount_cents"].asLong })
            addProperty("manifest_digest", ProtocolV3.payloadDigest(manifestBytes))
            addProperty("issued_at", now().truncatedTo(ChronoUnit.SECONDS).toString())
        }
        val payloadBytes = ProtocolV3.canonicalJson(payload).toByteArray(Charsets.UTF_8)
        val closure = ProtocolV3.encodeSignedEnvelope(payload, keyManager.sign(metadata.keyStoreAlias, payloadBytes))
        database.withTransaction {
            check(database.authorizationDao().freezeClosure(authorization.id, closure, manifestBytes) == 1)
        }
        return database.authorizationDao().getAuthorization(authorization.id)
    }
}
