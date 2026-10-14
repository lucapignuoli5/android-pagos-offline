package com.example.prototipopagosoffline.sync

import androidx.room.withTransaction
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.MobilePosOperation
import com.example.prototipopagosoffline.network.ApiService
import com.example.prototipopagosoffline.network.OfflinePaymentEvidence
import com.example.prototipopagosoffline.network.OfflinePaymentResultV2
import com.example.prototipopagosoffline.network.OfflinePaymentSyncV2Request
import java.time.Instant
import java.util.Base64

class MobilePosSyncRepository(
    private val database: AppDatabase,
    private val api: ApiService,
    private val now: () -> Instant = { Instant.now() }
) {
    suspend fun syncDue(terminalId: String, limit: Int = 100): SyncRun {
        require(terminalId.isNotBlank())
        val instant = now().epochSecond
        val dao = database.mobilePosOperationDao()
        val candidates = dao.getSyncCandidates(terminalId, instant, limit)
        if (candidates.isEmpty()) return SyncRun(0, dao.getNextSyncAt(terminalId))
        val response = runCatching {
            api.syncOfflinePaymentsV2(OfflinePaymentSyncV2Request(candidates.map(::evidence)))
        }.getOrNull()
        if (response == null || !response.isSuccessful || response.body() == null) {
            candidates.forEach { scheduleRetry(it, "NETWORK_OR_HTTP_ERROR", instant) }
            return SyncRun(0, dao.getNextSyncAt(terminalId))
        }
        val candidateIds = candidates.mapTo(mutableSetOf()) { it.operationId }
        val grouped = response.body()!!.results.groupBy { it.operation_id }
        var processed = 0
        candidates.forEach { operation ->
            val result = selectUniqueResult(operation.operationId, candidateIds, grouped[operation.operationId])
            if (result == null) {
                scheduleRetry(operation, "MISSING_OR_DUPLICATE_RESULT", instant)
            } else if (applyResult(operation, result, instant)) {
                processed++
            }
        }
        return SyncRun(processed, dao.getNextSyncAt(terminalId))
    }

    private suspend fun applyResult(
        operation: MobilePosOperation,
        initial: OfflinePaymentResultV2,
        instant: Long
    ): Boolean {
        val result = if (initial.status == "PENDING_EVIDENCE") {
            val statusResponse = runCatching { api.getOfflinePaymentStatus(operation.operationId) }.getOrNull()
            if (statusResponse?.isSuccessful == true) {
                validatedStatusResult(operation.operationId, statusResponse.body()) ?: initial
            } else initial
        } else initial
        return when (result.status) {
            "ACCEPTED" -> finish(operation.operationId, "SYNCED", result.code)
            "REJECTED" -> finish(operation.operationId, "REJECTED", result.code)
            "CONFLICT" -> finish(operation.operationId, "CONFLICT", result.code)
            "PENDING_EVIDENCE", "RETRYABLE_ERROR" -> {
                scheduleRetry(operation, result.code ?: result.status, instant)
                false
            }
            else -> {
                scheduleRetry(operation, "UNKNOWN_RESULT", instant)
                false
            }
        }
    }

    private suspend fun finish(operationId: String, state: String, code: String?): Boolean =
        database.withTransaction {
            check(database.mobilePosOperationDao().finishSync(operationId, state, code) == 1)
            true
        }

    private suspend fun scheduleRetry(operation: MobilePosOperation, code: String, instant: Long) {
        val attempt = operation.syncAttempts + 1
        val delay = minOf(15L * (1L shl minOf(attempt - 1, 8)), 3600L)
        database.mobilePosOperationDao().scheduleRetry(operation.operationId, instant + delay, code)
    }

    private fun evidence(operation: MobilePosOperation): OfflinePaymentEvidence = OfflinePaymentEvidence(
        operation_id = operation.operationId,
        request_envelope_b64 = Base64.getEncoder().encodeToString(operation.requestEnvelope),
        receipt_envelope_b64 = Base64.getEncoder().encodeToString(requireNotNull(operation.receiptEnvelope)),
        ack_envelope_b64 = Base64.getEncoder().encodeToString(requireNotNull(operation.ackEnvelope))
    )
}
