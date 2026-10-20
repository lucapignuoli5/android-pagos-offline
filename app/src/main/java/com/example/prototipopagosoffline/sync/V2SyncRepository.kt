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
        val ordinary = syncSelected(walletId, candidates, instant)
        val recovered = recoverFinalResolutions(walletId, instant, limit.coerceIn(1, 100))
        val next = database.nfcExchangeDao().getNextResolutionRecoveryAt(walletId)
        return SyncRun(ordinary.processed + recovered,
            listOfNotNull(ordinary.nextSyncAtEpochSeconds, next).minOrNull())
    }

    private suspend fun recoverFinalResolutions(walletId: String, instant: Long, limit: Int): Int {
        val dao = database.nfcExchangeDao()
        var recovered = 0
        for (exchange in dao.getResolutionRecoveryCandidates(walletId, instant, limit)) {
            val response = try {
                api.getOfflinePaymentV3(exchange.operationId)
            } catch (_: java.io.IOException) {
                null
            }
            when {
                response == null || response.code() >= 500 || response.code() in setOf(408, 429) -> {
                    val delay = minOf(15L * (1L shl minOf(exchange.syncAttempts, 8)), 3600L)
                    dao.retryResolutionRecovery(exchange.operationId, instant + delay)
                }
                response.code() in setOf(401, 403) -> throw SyncPauseRequired("REAUTH_REQUIRED")
                !response.isSuccessful -> dao.flagResolutionRecovery(exchange.operationId)
                else -> {
                    val result = response.body()
                    val expected = if (exchange.state == "SYNCED") "ACCEPTED" else "REJECTED"
                    val authorization = exchange.authorizationId?.let {
                        database.authorizationDao().getAuthorization(it)?.authorizationEnvelope
                    }
                    if (result == null || result.operation_id != exchange.operationId || !result.final ||
                        result.operation_status != expected || authorization == null ||
                        !verifyResolution(result, exchange, authorization)) {
                        dao.flagResolutionRecovery(exchange.operationId)
                    } else {
                        val wire = Base64.getDecoder().decode(requireNotNull(result.resolution_envelope_b64))
                        database.withTransaction {
                            check(dao.attachFinalResolution(exchange.operationId, wire) == 1)
                        }
                        recovered++
                    }
                }
            }
        }
        return recovered
    }

    suspend fun syncAuthorization(authorizationId: String): SyncRun {
        val authorization = database.authorizationDao().getAuthorization(authorizationId)
            ?: return SyncRun(0, null)
        val walletId = authorization.walletId
        val candidates = database.nfcExchangeDao().getByAuthorization(authorizationId)
            .filter { it.state in setOf("RESERVED", "DELIVERY_UNCERTAIN", "LOCALLY_ACCEPTED") }
        return syncSelected(walletId, candidates, now().epochSecond)
    }

    private suspend fun syncSelected(walletId: String, candidates: List<NfcExchange>, instant: Long): SyncRun {
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
            val response = syncHttp {
                api.syncOfflinePaymentsV2(OfflinePaymentSyncV2Request(v2.map(::evidence)))
            }
            if (response?.isSuccessful == true && response.body() != null) received += response.body()!!.results
            else v2.forEach { scheduleRetry(it, "NETWORK_OR_HTTP_ERROR", instant) }
        }
        if (v3.isNotEmpty()) {
            val response = syncHttp {
                api.syncOfflinePaymentsV3(OfflinePaymentSyncV3Request(v3.map { evidenceV3(it.first, it.second) }))
            }
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
            val statusResponse = syncHttp { api.getOfflinePaymentStatus(exchange.operationId) }
            val status = statusResponse?.body()
            if (statusResponse?.isSuccessful == true) {
                validatedStatusResult(exchange.operationId, status) ?: initial
            } else initial
        } else initial

        if (initial.evidenceStored && isV3) {
            database.nfcExchangeDao().markServerEvidenceStored(exchange.operationId)
        }

        return when (result.status) {
            "ACCEPTED" -> when {
                result.resolutionEnvelope != null -> settleAccepted(exchange, result.code, instant, result.resolutionEnvelope)
                !isV3 -> settleAcceptedLegacy(exchange, result.code, instant)
                else -> retryInvalid(exchange, instant)
            }
            "REJECTED" -> when {
                result.resolutionEnvelope != null -> settleRejected(exchange, result.code, result.resolutionEnvelope)
                !isV3 -> settleRejectedLegacy(exchange, result.code)
                else -> retryInvalid(exchange, instant)
            }
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

    private suspend fun settleAccepted(exchange: NfcExchange, code: String?, instant: Long, resolution: ByteArray): Boolean {
        val walletId = exchange.walletId ?: return retryInvalid(exchange, instant)
        val amount = exchange.amountCents ?: return retryInvalid(exchange, instant)
        val balanceResponse = syncHttp { api.getWalletBalance(walletId) }
        val balance = balanceResponse?.body()
        if (balanceResponse?.isSuccessful != true || balance == null || balance.wallet_id != walletId) {
            scheduleRetry(exchange, "BALANCE_REFRESH_REQUIRED", instant)
            return false
        }
        return database.withTransaction {
            check(database.walletDao().settleAccepted(walletId, amount, balance.confirmed_balance_cents) == 1)
            check(database.nfcExchangeDao().finishSyncWithResolution(exchange.operationId, "SYNCED", code, resolution) == 1)
            true
        }
    }

    private suspend fun settleAcceptedLegacy(exchange: NfcExchange, code: String?, instant: Long): Boolean {
        val walletId = exchange.walletId ?: return retryInvalid(exchange, instant)
        val amount = exchange.amountCents ?: return retryInvalid(exchange, instant)
        val balanceResponse = syncHttp { api.getWalletBalance(walletId) }
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

    private suspend fun settleRejected(exchange: NfcExchange, code: String?, resolution: ByteArray): Boolean {
        val walletId = exchange.walletId ?: return retryInvalid(exchange, now().epochSecond)
        val authorizationId = exchange.authorizationId ?: return retryInvalid(exchange, now().epochSecond)
        val amount = exchange.amountCents ?: return retryInvalid(exchange, now().epochSecond)
        return database.withTransaction {
            check(database.walletDao().releaseReservation(walletId, amount) == 1)
            // An issued index is permanent: a rejection releases only its local reservation.
            check(database.nfcExchangeDao().finishSyncWithResolution(exchange.operationId, "REJECTED", code, resolution) == 1)
            true
        }
    }

    private suspend fun settleRejectedLegacy(exchange: NfcExchange, code: String?): Boolean {
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
            result.final && result.operation_status in setOf("ACCEPTED", "REJECTED") ->
                if (finalResolutionValid) result.operation_status else "RETRYABLE_ERROR"
            result.submission_status == "CONFLICT" -> "CONFLICT"
            result.submission_status == "INVALID_EVIDENCE" -> "CONFLICT"
            result.submission_status == "RETRYABLE_ERROR" -> "RETRYABLE_ERROR"
            result.operation_status == "PENDING_REVIEW" -> "CONFLICT"
            else -> "PENDING_EVIDENCE"
        }
        val code = when {
            result.final && !finalResolutionValid -> "INVALID_SIGNED_RESOLUTION"
            status in setOf("ACCEPTED", "REJECTED") -> ProtocolV3.parseSignedEnvelope(
                Base64.getDecoder().decode(requireNotNull(result.resolution_envelope_b64))
            ).payload["code"].asString
            else -> result.code
        }
        val evidenceStored = status in setOf("ACCEPTED", "REJECTED") ||
                (result.submission_status == "VALIDATED"
                && result.operation_status == "PENDING_EVIDENCE")
        val resolution = if (status in setOf("ACCEPTED", "REJECTED")) {
            Base64.getDecoder().decode(requireNotNull(result.resolution_envelope_b64))
        } else null
        return OfflinePaymentResultV2(result.operation_id, status, code, evidenceStored, resolution)
    }

    private fun verifyResolution(result: OfflinePaymentResultV3, exchange: NfcExchange,
                                 authorization: ByteArray? = null): Boolean = runCatching {
        val wire = Base64.getDecoder().decode(requireNotNull(result.resolution_envelope_b64))
        val resolution = ProtocolV3.parseSignedEnvelope(wire)
        require(ProtocolV3.validatePayload(resolution.payload) == MessageTypeV3.PAYMENT_RESOLUTION)
        require(BuildConfig.ISSUER_PUBLIC_KEY_B64.isNotBlank() && BuildConfig.ISSUER_KEY_ID.isNotBlank())
        require(resolution.verify(com.example.prototipopagosoffline.protocol.IssuerTrust.publicKey(resolution.payload)))
        val request = ProtocolV3.parseSignedEnvelope(exchange.requestEnvelope)
        val receipt = ProtocolV3.parseSignedEnvelope(requireNotNull(exchange.receiptEnvelope))
        require(resolution.payload["operation_id"].asString == exchange.operationId)
        require(resolution.payload["wallet_id"].asString == exchange.walletId)
        require(resolution.payload["merchant_id"].asString == exchange.merchantId)
        require(resolution.payload["terminal_id"].asString == exchange.terminalId)
        require(resolution.payload["amount_cents"].asLong == exchange.amountCents)
        require(resolution.payload["currency"].asString == "ARS")
        require(resolution.payload["offline_authorization_id"].asString == exchange.authorizationId)
        require(resolution.payload["request_digest"].asString == ProtocolV3.payloadDigest(request))
        require(resolution.payload["receipt_digest"].asString == ProtocolV3.payloadDigest(receipt))
        require(resolution.payload["authorization_digest"].asString == receipt.payload["authorization_digest"].asString)
        if (authorization != null) require(resolution.payload["authorization_digest"].asString ==
            ProtocolV3.payloadDigest(ProtocolV3.parseSignedEnvelope(authorization)))
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
