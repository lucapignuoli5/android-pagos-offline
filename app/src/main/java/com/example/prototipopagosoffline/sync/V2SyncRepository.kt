package com.example.prototipopagosoffline.sync

import androidx.room.withTransaction
import com.example.prototipopagosoffline.BuildConfig
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.NfcExchange
import com.example.prototipopagosoffline.network.ApiService
import com.example.prototipopagosoffline.network.OfflinePaymentEvidence
import com.example.prototipopagosoffline.network.OfflinePaymentResultV2
import com.example.prototipopagosoffline.network.OfflinePaymentStatusResponse
import com.example.prototipopagosoffline.network.OfflinePaymentSyncV2Request
import com.example.prototipopagosoffline.network.OfflinePaymentEvidenceV3
import com.example.prototipopagosoffline.network.OfflinePaymentResultV3
import com.example.prototipopagosoffline.network.OfflinePaymentSyncV3Request
import com.example.prototipopagosoffline.protocol.MessageTypeV3
import com.example.prototipopagosoffline.protocol.ProtocolV3
import java.time.Instant
import java.util.Base64

data class SyncRun(val processed: Int, val nextSyncAtEpochSeconds: Long?)

class V2SyncRepository(
    private val database: AppDatabase,
    private val api: ApiService,
    private val now: () -> Instant = { Instant.now() }
) {
    suspend fun syncDue(walletId: String, limit: Int = 100): SyncRun {
        require(walletId.isNotBlank())
        val instant = now().epochSecond
        val candidates = database.nfcExchangeDao().getSyncCandidates(walletId, instant, limit)
        if (candidates.isEmpty()) return SyncRun(0, database.nfcExchangeDao().getNextSyncAt(walletId))

        val v3 = mutableListOf<Pair<NfcExchange, ByteArray>>()
        val v2 = mutableListOf<NfcExchange>()
        for (candidate in candidates) {
            val authorization = candidate.authorizationId?.let {
                database.authorizationDao().getAuthorization(it)?.authorizationEnvelope
            }
            if (authorization == null) v2 += candidate else v3 += candidate to authorization
        }
        val received = mutableListOf<OfflinePaymentResultV2>()
        if (v2.isNotEmpty()) {
            val response = runCatching {
                api.syncOfflinePaymentsV2(OfflinePaymentSyncV2Request(v2.map(::evidence)))
            }.getOrNull()
            if (response?.isSuccessful == true && response.body() != null) received += response.body()!!.results
            else v2.forEach { scheduleRetry(it, "NETWORK_OR_HTTP_ERROR", instant) }
        }
        if (v3.isNotEmpty()) {
            val response = runCatching {
                api.syncOfflinePaymentsV3(OfflinePaymentSyncV3Request(v3.map { evidenceV3(it.first, it.second) }))
            }.getOrNull()
            if (response?.isSuccessful == true && response.body() != null) {
                val exchanges = v3.associateBy({ it.first.operationId }, { it.first })
                received += response.body()!!.results.map { result ->
                    normalizeV3(result, exchanges[result.operation_id])
                }
            } else v3.forEach { scheduleRetry(it.first, "NETWORK_OR_HTTP_ERROR", instant) }
        }

        val candidateIds = candidates.mapTo(mutableSetOf()) { it.operationId }
        val grouped = received.groupBy { it.operation_id }
        var processed = 0
        for (candidate in candidates) {
            val result = selectUniqueResult(candidate.operationId, candidateIds, grouped[candidate.operationId])
            if (result == null) {
                scheduleRetry(candidate, "MISSING_OR_DUPLICATE_RESULT", instant)
                continue
            }
            if (applyResult(candidate, result, instant)) processed++
        }
        return SyncRun(processed, database.nfcExchangeDao().getNextSyncAt(walletId))
    }

    private suspend fun applyResult(
        exchange: NfcExchange,
        initial: OfflinePaymentResultV2,
        instant: Long
    ): Boolean {
        val isV3 = exchange.authorizationId?.let {
            database.authorizationDao().getAuthorization(it)?.authorizationEnvelope != null
        } == true
        val result = if (initial.status == "PENDING_EVIDENCE" && !isV3) {
            val statusResponse = runCatching { api.getOfflinePaymentStatus(exchange.operationId) }.getOrNull()
            val status = statusResponse?.body()
            if (statusResponse?.isSuccessful == true) {
                validatedStatusResult(exchange.operationId, status) ?: initial
            } else initial
        } else initial

        return when (result.status) {
            "ACCEPTED" -> settleAccepted(exchange, result.code, instant)
            "REJECTED" -> settleRejected(exchange, result.code)
            "CONFLICT" -> database.withTransaction {
                check(database.nfcExchangeDao().finishSync(exchange.operationId, "CONFLICT", result.code) == 1)
                true
            }
            "PENDING_EVIDENCE", "RETRYABLE_ERROR" -> {
                scheduleRetry(exchange, result.code ?: result.status, instant)
                false
            }
            else -> {
                scheduleRetry(exchange, "UNKNOWN_RESULT", instant)
                false
            }
        }
    }

    private suspend fun settleAccepted(exchange: NfcExchange, code: String?, instant: Long): Boolean {
        val walletId = exchange.walletId ?: return retryInvalid(exchange, instant)
        val amount = exchange.amountCents ?: return retryInvalid(exchange, instant)
        val balanceResponse = runCatching { api.getWalletBalance(walletId) }.getOrNull()
        val balance = balanceResponse?.body()
        if (balanceResponse?.isSuccessful != true || balance == null || balance.wallet_id != walletId) {
            scheduleRetry(exchange, "BALANCE_REFRESH_REQUIRED", instant)
            return false
        }
        return database.withTransaction {
            check(database.walletDao().settleAccepted(walletId, amount, balance.confirmed_balance_cents) == 1)
            check(database.nfcExchangeDao().finishSync(exchange.operationId, "SYNCED", code) == 1)
            true
        }
    }

    private suspend fun settleRejected(exchange: NfcExchange, code: String?): Boolean {
        val walletId = exchange.walletId ?: return retryInvalid(exchange, now().epochSecond)
        val authorizationId = exchange.authorizationId ?: return retryInvalid(exchange, now().epochSecond)
        val amount = exchange.amountCents ?: return retryInvalid(exchange, now().epochSecond)
        return database.withTransaction {
            check(database.walletDao().releaseReservation(walletId, amount) == 1)
            check(database.authorizationDao().restore(authorizationId, amount) == 1)
            check(database.nfcExchangeDao().finishSync(exchange.operationId, "REJECTED", code) == 1)
            true
        }
    }

    private suspend fun retryInvalid(exchange: NfcExchange, instant: Long): Boolean {
        scheduleRetry(exchange, "LOCAL_METADATA_MISSING", instant)
        return false
    }

    private suspend fun scheduleRetry(exchange: NfcExchange, code: String, instant: Long) {
        val attempt = exchange.syncAttempts + 1
        val delay = minOf(15L * (1L shl minOf(attempt - 1, 8)), 3600L)
        database.nfcExchangeDao().scheduleRetry(exchange.operationId, instant + delay, code)
    }

    private fun evidence(exchange: NfcExchange): OfflinePaymentEvidence {
        val receipt = requireNotNull(exchange.receiptEnvelope)
        return OfflinePaymentEvidence(
            operation_id = exchange.operationId,
            request_envelope_b64 = Base64.getEncoder().encodeToString(exchange.requestEnvelope),
            receipt_envelope_b64 = Base64.getEncoder().encodeToString(receipt),
            ack_envelope_b64 = exchange.ackEnvelope?.let(Base64.getEncoder()::encodeToString)
        )
    }

    private fun evidenceV3(exchange: NfcExchange, authorization: ByteArray): OfflinePaymentEvidenceV3 {
        val receipt = requireNotNull(exchange.receiptEnvelope)
        return OfflinePaymentEvidenceV3(
            operation_id = exchange.operationId,
            request_envelope_b64 = Base64.getEncoder().encodeToString(exchange.requestEnvelope),
            authorization_envelope_b64 = Base64.getEncoder().encodeToString(authorization),
            receipt_envelope_b64 = Base64.getEncoder().encodeToString(receipt),
            ack_envelope_b64 = exchange.ackEnvelope?.let(Base64.getEncoder()::encodeToString)
        )
    }

    private fun normalizeV3(result: OfflinePaymentResultV3, exchange: NfcExchange?): OfflinePaymentResultV2 {
        val finalResolutionValid = if (result.final && result.operation_status in setOf("ACCEPTED", "REJECTED")) {
            exchange != null && verifyResolution(result, exchange)
        } else true
        val status = when {
            result.submission_status == "CONFLICT" -> "CONFLICT"
            result.submission_status == "INVALID_EVIDENCE" -> "CONFLICT"
            result.submission_status == "RETRYABLE_ERROR" -> "RETRYABLE_ERROR"
            result.final && !finalResolutionValid -> "RETRYABLE_ERROR"
            result.operation_status == "ACCEPTED" && result.final -> "ACCEPTED"
            result.operation_status == "REJECTED" && result.final -> "REJECTED"
            result.operation_status == "PENDING_REVIEW" -> "CONFLICT"
            else -> "PENDING_EVIDENCE"
        }
        val code = if (result.final && !finalResolutionValid) "INVALID_SIGNED_RESOLUTION" else result.code
        return OfflinePaymentResultV2(result.operation_id, status, code)
    }

    private fun verifyResolution(result: OfflinePaymentResultV3, exchange: NfcExchange): Boolean = runCatching {
        val wire = Base64.getDecoder().decode(requireNotNull(result.resolution_envelope_b64))
        val resolution = ProtocolV3.parseSignedEnvelope(wire)
        require(ProtocolV3.validatePayload(resolution.payload) == MessageTypeV3.PAYMENT_RESOLUTION)
        require(BuildConfig.ISSUER_PUBLIC_KEY_B64.isNotBlank() && BuildConfig.ISSUER_KEY_ID.isNotBlank())
        require(resolution.verify(BuildConfig.ISSUER_PUBLIC_KEY_B64))
        require(resolution.payload["issuer_key_id"].asString == BuildConfig.ISSUER_KEY_ID)
        require(resolution.payload["issuer_key_fingerprint"].asString ==
            ProtocolV3.publicKeyFingerprint(BuildConfig.ISSUER_PUBLIC_KEY_B64))
        val request = ProtocolV3.parseSignedEnvelope(exchange.requestEnvelope)
        val receipt = ProtocolV3.parseSignedEnvelope(requireNotNull(exchange.receiptEnvelope))
        require(resolution.payload["operation_id"].asString == exchange.operationId)
        require(resolution.payload["wallet_id"].asString == exchange.walletId)
        require(resolution.payload["merchant_id"].asString == exchange.merchantId)
        require(resolution.payload["terminal_id"].asString == exchange.terminalId)
        require(resolution.payload["amount_cents"].asLong == exchange.amountCents)
        require(resolution.payload["offline_authorization_id"].asString == exchange.authorizationId)
        require(resolution.payload["request_digest"].asString == ProtocolV3.payloadDigest(request))
        require(resolution.payload["receipt_digest"].asString == ProtocolV3.payloadDigest(receipt))
        require(resolution.payload["authorization_digest"].asString == receipt.payload["authorization_digest"].asString)
        require(resolution.payload["operation_status"].asString == result.operation_status)
        true
    }.getOrDefault(false)

}

internal val VALID_SYNC_RESULTS = setOf(
    "ACCEPTED", "REJECTED", "CONFLICT", "PENDING_EVIDENCE", "RETRYABLE_ERROR"
)

internal fun selectUniqueResult(
    operationId: String,
    candidateIds: Set<String>,
    results: List<OfflinePaymentResultV2>?
): OfflinePaymentResultV2? = results?.singleOrNull()?.takeIf {
    it.operation_id == operationId && it.operation_id in candidateIds && it.status in VALID_SYNC_RESULTS
}

internal fun validatedStatusResult(
    operationId: String,
    status: OfflinePaymentStatusResponse?
): OfflinePaymentResultV2? = status?.takeIf {
    it.operation_id == operationId && it.status in VALID_SYNC_RESULTS
}?.let { OfflinePaymentResultV2(it.operation_id, it.status, it.code) }
