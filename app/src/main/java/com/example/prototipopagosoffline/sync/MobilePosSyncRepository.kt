package com.example.prototipopagosoffline.sync

import androidx.room.withTransaction
import com.example.prototipopagosoffline.BuildConfig
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.MobilePosOperation
import com.example.prototipopagosoffline.network.ApiService
import com.example.prototipopagosoffline.network.OfflinePaymentResultV2
import com.example.prototipopagosoffline.network.OfflinePaymentEvidenceV3
import com.example.prototipopagosoffline.network.OfflinePaymentResultV3
import com.example.prototipopagosoffline.network.OfflinePaymentSyncV3Request
import com.example.prototipopagosoffline.protocol.MessageTypeV3
import com.example.prototipopagosoffline.protocol.ProtocolV3
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
        val response = syncHttp {
            api.syncOfflinePaymentsV3(OfflinePaymentSyncV3Request(candidates.map(::evidence)))
        }
        if (response == null || !response.isSuccessful || response.body() == null) {
            candidates.forEach { scheduleRetry(it, "NETWORK_OR_HTTP_ERROR", instant) }
            return SyncRun(0, dao.getNextSyncAt(terminalId))
        }
        val candidateIds = candidates.mapTo(mutableSetOf()) { it.operationId }
        val normalized = response.body()!!.results.map { result ->
            val operation = candidates.singleOrNull { it.operationId == result.operation_id }
            normalize(result, operation)
        }
        val grouped = normalized.groupBy { it.operation_id }
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
        val result = initial
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

    private fun evidence(operation: MobilePosOperation): OfflinePaymentEvidenceV3 = OfflinePaymentEvidenceV3(
        operation_id = operation.operationId,
        request_envelope_b64 = Base64.getEncoder().encodeToString(operation.requestEnvelope),
        authorization_envelope_b64 = Base64.getEncoder().encodeToString(requireNotNull(operation.authorizationEnvelope)),
        receipt_envelope_b64 = Base64.getEncoder().encodeToString(requireNotNull(operation.receiptEnvelope)),
        ack_envelope_b64 = Base64.getEncoder().encodeToString(requireNotNull(operation.ackEnvelope))
    )

    private fun normalize(result: OfflinePaymentResultV3, operation: MobilePosOperation?): OfflinePaymentResultV2 {
        val finalValid = if (result.final && result.operation_status in setOf("ACCEPTED", "REJECTED")) {
            operation != null && verifyResolution(result, operation)
        } else true
        val status = when {
            result.submission_status in setOf("CONFLICT", "INVALID_EVIDENCE") -> "CONFLICT"
            result.submission_status == "RETRYABLE_ERROR" -> "RETRYABLE_ERROR"
            result.final && !finalValid -> "RETRYABLE_ERROR"
            result.final && result.operation_status in setOf("ACCEPTED", "REJECTED") -> result.operation_status
            result.operation_status == "PENDING_REVIEW" -> "CONFLICT"
            else -> "PENDING_EVIDENCE"
        }
        return OfflinePaymentResultV2(
            result.operation_id, status,
            if (result.final && !finalValid) "INVALID_SIGNED_RESOLUTION" else result.code
        )
    }

    private fun verifyResolution(result: OfflinePaymentResultV3, operation: MobilePosOperation): Boolean = runCatching {
        val resolution = ProtocolV3.parseSignedEnvelope(
            Base64.getDecoder().decode(requireNotNull(result.resolution_envelope_b64))
        )
        require(ProtocolV3.validatePayload(resolution.payload) == MessageTypeV3.PAYMENT_RESOLUTION)
        require(resolution.verify(com.example.prototipopagosoffline.protocol.IssuerTrust.publicKey(resolution.payload)))
        val request = ProtocolV3.parseSignedEnvelope(operation.requestEnvelope)
        val grant = ProtocolV3.parseSignedEnvelope(requireNotNull(operation.authorizationEnvelope))
        val receipt = ProtocolV3.parseSignedEnvelope(requireNotNull(operation.receiptEnvelope))
        require(resolution.payload["operation_id"].asString == operation.operationId)
        require(resolution.payload["request_digest"].asString == ProtocolV3.payloadDigest(request))
        require(resolution.payload["authorization_digest"].asString == ProtocolV3.payloadDigest(grant))
        require(resolution.payload["receipt_digest"].asString == ProtocolV3.payloadDigest(receipt))
        require(resolution.payload["operation_status"].asString == result.operation_status)
        true
    }.getOrDefault(false)
}
