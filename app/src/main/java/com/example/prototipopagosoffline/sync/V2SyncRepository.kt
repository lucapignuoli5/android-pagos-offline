package com.example.prototipopagosoffline.sync

import androidx.room.withTransaction
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.NfcExchange
import com.example.prototipopagosoffline.network.ApiService
import com.example.prototipopagosoffline.network.OfflinePaymentEvidence
import com.example.prototipopagosoffline.network.OfflinePaymentResultV2
import com.example.prototipopagosoffline.network.OfflinePaymentStatusResponse
import com.example.prototipopagosoffline.network.OfflinePaymentSyncV2Request
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

        val request = OfflinePaymentSyncV2Request(candidates.map(::evidence))
        val response = runCatching { api.syncOfflinePaymentsV2(request) }.getOrNull()
        if (response == null || !response.isSuccessful || response.body() == null) {
            candidates.forEach { scheduleRetry(it, "NETWORK_OR_HTTP_ERROR", instant) }
            return SyncRun(0, database.nfcExchangeDao().getNextSyncAt(walletId))
        }

        val candidateIds = candidates.mapTo(mutableSetOf()) { it.operationId }
        val grouped = response.body()!!.results.groupBy { it.operation_id }
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
        val result = if (initial.status == "PENDING_EVIDENCE") {
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
