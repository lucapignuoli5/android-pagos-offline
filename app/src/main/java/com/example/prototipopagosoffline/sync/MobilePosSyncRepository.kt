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
        if (candidates.isEmpty()) {
            val recovered = recoverFinalResolutions(terminalId, instant, limit.coerceIn(1, 100))
            return SyncRun(recovered, listOfNotNull(
                dao.getNextSyncAt(terminalId), dao.getNextResolutionRecoveryAt(terminalId)
            ).minOrNull())
        }
        val response = syncHttp {
            api.syncOfflinePaymentsV3(OfflinePaymentSyncV3Request(candidates.map(::evidence)))
        }
        if (response == null || !response.isSuccessful || response.body() == null) {
            candidates.forEach { scheduleRetry(it, "NETWORK_OR_HTTP_ERROR", instant) }
            val recovered = recoverFinalResolutions(terminalId, instant, limit.coerceIn(1, 100))
            return SyncRun(recovered, listOfNotNull(
                dao.getNextSyncAt(terminalId), dao.getNextResolutionRecoveryAt(terminalId)
            ).minOrNull())
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
        processed += recoverFinalResolutions(terminalId, instant, limit.coerceIn(1, 100))
        return SyncRun(processed, listOfNotNull(
            dao.getNextSyncAt(terminalId), dao.getNextResolutionRecoveryAt(terminalId)
        ).minOrNull())
    }

    private suspend fun recoverFinalResolutions(terminalId: String, instant: Long, limit: Int): Int {
        val dao = database.mobilePosOperationDao()
        var recovered = 0
        for (operation in dao.getResolutionRecoveryCandidates(terminalId, instant, limit)) {
            val response = try {
                api.getOfflinePaymentV3(operation.operationId)
            } catch (_: java.io.IOException) {
                null
            }
            when {
                response == null || response.code() >= 500 || response.code() in setOf(408, 429) -> {
                    val delay = minOf(15L * (1L shl minOf(operation.syncAttempts, 8)), 3600L)
                    dao.retryResolutionRecovery(operation.operationId, instant + delay)
                }
                response.code() in setOf(401, 403) -> throw SyncPauseRequired("REAUTH_REQUIRED")
                !response.isSuccessful -> dao.flagResolutionRecovery(operation.operationId)
                else -> {
                    val result = response.body()
                    val expected = if (operation.state == "SYNCED") "ACCEPTED" else "REJECTED"
                    if (result == null || result.operation_id != operation.operationId || !result.final ||
                        result.operation_status != expected || !verifyResolution(result, operation)) {
                        dao.flagResolutionRecovery(operation.operationId)
                    } else {
                        val wire = Base64.getDecoder().decode(requireNotNull(result.resolution_envelope_b64))
                        database.withTransaction {
                            check(dao.attachFinalResolution(operation.operationId, wire) == 1)
                        }
                        recovered++
                    }
                }
            }
        }
        return recovered
    }

    private suspend fun applyResult(
        operation: MobilePosOperation,
        initial: OfflinePaymentResultV2,
        instant: Long
    ): Boolean {
        val result = initial
        return when (result.status) {
            "ACCEPTED" -> finish(operation.operationId, "SYNCED", result.code,
                requireNotNull(result.resolutionEnvelope))
            "REJECTED" -> finish(operation.operationId, "REJECTED", result.code,
                requireNotNull(result.resolutionEnvelope))
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

    private suspend fun finish(operationId: String, state: String, code: String?, resolution: ByteArray? = null): Boolean =
        database.withTransaction {
            val updated = if (resolution == null) {
                database.mobilePosOperationDao().finishSync(operationId, state, code)
            } else database.mobilePosOperationDao().finishSyncWithResolution(
                operationId, state, code, resolution
            )
            check(updated == 1)
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
            result.final && result.operation_status in setOf("ACCEPTED", "REJECTED") ->
                if (finalValid) result.operation_status else "RETRYABLE_ERROR"
            result.submission_status in setOf("CONFLICT", "INVALID_EVIDENCE") -> "CONFLICT"
            result.submission_status == "RETRYABLE_ERROR" -> "RETRYABLE_ERROR"
            result.operation_status == "PENDING_REVIEW" -> "CONFLICT"
            else -> "PENDING_EVIDENCE"
        }
        val resolution = if (status in setOf("ACCEPTED", "REJECTED")) {
            Base64.getDecoder().decode(requireNotNull(result.resolution_envelope_b64))
        } else null
        val code = if (resolution != null) {
            ProtocolV3.parseSignedEnvelope(resolution).payload["code"].asString
        } else if (result.final && !finalValid) "INVALID_SIGNED_RESOLUTION" else result.code
        return OfflinePaymentResultV2(
            result.operation_id, status, code,
            resolutionEnvelope = resolution
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
        require(resolution.payload["wallet_id"].asString == operation.walletId)
        require(resolution.payload["merchant_id"].asString == operation.merchantId)
        require(resolution.payload["terminal_id"].asString == operation.terminalId)
        require(resolution.payload["amount_cents"].asLong == operation.amountCents)
        require(resolution.payload["currency"].asString == "ARS")
        require(resolution.payload["offline_authorization_id"].asString == operation.authorizationId)
        require(resolution.payload["request_digest"].asString == ProtocolV3.payloadDigest(request))
        require(resolution.payload["authorization_digest"].asString == ProtocolV3.payloadDigest(grant))
        require(resolution.payload["receipt_digest"].asString == ProtocolV3.payloadDigest(receipt))
        require(resolution.payload["operation_status"].asString == result.operation_status)
        true
    }.getOrDefault(false)
}
