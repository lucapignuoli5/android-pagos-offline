package com.example.prototipopagosoffline.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NfcExchangeDao {
    @Query("UPDATE nfc_exchanges SET server_evidence_stored = 1 WHERE operation_id = :operationId AND receipt_envelope IS NOT NULL")
    suspend fun markServerEvidenceStored(operationId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExchange(exchange: NfcExchange): Long

    @Query("SELECT * FROM nfc_exchanges WHERE operation_id = :operationId LIMIT 1")
    suspend fun getExchange(operationId: String): NfcExchange?

    @Query("UPDATE nfc_exchanges SET state = 'REQUEST_EXPIRED' WHERE state = 'REQUEST_RECEIVED' AND receipt_envelope IS NULL AND request_expires_at_epoch_seconds IS NOT NULL AND request_expires_at_epoch_seconds <= :now")
    suspend fun expirePendingRequests(now: Long): Int

    @Query("UPDATE nfc_exchanges SET state = 'CANCELLED', cancelled_at_epoch_seconds = :now WHERE operation_id = :operationId AND state = 'REQUEST_RECEIVED' AND receipt_envelope IS NULL")
    suspend fun cancelBeforeReceipt(operationId: String, now: Long): Int

    @Query("SELECT * FROM nfc_exchanges WHERE wallet_id = :walletId AND state = 'REQUEST_RECEIVED' AND (request_expires_at_epoch_seconds IS NULL OR request_expires_at_epoch_seconds > strftime('%s','now')) ORDER BY created_at_epoch_seconds ASC LIMIT 1")
    fun observeOldestPendingRequest(walletId: String): Flow<NfcExchange?>

    @Query("UPDATE nfc_exchanges SET receipt_envelope = :receipt, state = 'RESERVED', wallet_id = :walletId, authorization_id = :authorizationId, amount_cents = :amount, merchant_id = :merchantId, terminal_id = :terminalId WHERE operation_id = :operationId AND state = 'REQUEST_RECEIVED' AND receipt_envelope IS NULL")
    suspend fun reserveAndSetReceipt(
        operationId: String,
        receipt: ByteArray,
        walletId: String,
        authorizationId: String,
        amount: Long,
        merchantId: String,
        terminalId: String
    ): Int

    @Query("UPDATE nfc_exchanges SET state = 'DELIVERY_UNCERTAIN' WHERE operation_id = :operationId AND state = 'RESERVED'")
    suspend fun markDeliveryStarted(operationId: String): Int

    @Query("UPDATE nfc_exchanges SET ack_envelope = :ack, state = CASE WHEN state IN ('RESERVED','DELIVERY_UNCERTAIN') THEN 'LOCALLY_ACCEPTED' ELSE state END, next_sync_at_epoch_seconds = CASE WHEN state IN ('RESERVED','DELIVERY_UNCERTAIN') THEN NULL ELSE next_sync_at_epoch_seconds END, last_sync_code = CASE WHEN state IN ('RESERVED','DELIVERY_UNCERTAIN') THEN NULL ELSE last_sync_code END WHERE operation_id = :operationId AND ack_envelope IS NULL AND state IN ('RESERVED','DELIVERY_UNCERTAIN','SYNCED','REJECTED','CONFLICT')")
    suspend fun setAck(operationId: String, ack: ByteArray): Int

    @Query("SELECT * FROM nfc_exchanges WHERE wallet_id = :walletId AND receipt_envelope IS NOT NULL AND state IN ('RESERVED','DELIVERY_UNCERTAIN','LOCALLY_ACCEPTED') AND (next_sync_at_epoch_seconds IS NULL OR next_sync_at_epoch_seconds <= :now) ORDER BY created_at_epoch_seconds ASC LIMIT :limit")
    suspend fun getSyncCandidates(walletId: String, now: Long, limit: Int = 100): List<NfcExchange>

    @Query("SELECT MIN(COALESCE(next_sync_at_epoch_seconds, 0)) FROM nfc_exchanges WHERE wallet_id = :walletId AND receipt_envelope IS NOT NULL AND state IN ('RESERVED','DELIVERY_UNCERTAIN','LOCALLY_ACCEPTED')")
    suspend fun getNextSyncAt(walletId: String): Long?

    @Query("UPDATE nfc_exchanges SET sync_attempts = sync_attempts + 1, next_sync_at_epoch_seconds = :nextAttempt, last_sync_code = :code WHERE operation_id = :operationId AND state IN ('RESERVED','DELIVERY_UNCERTAIN','LOCALLY_ACCEPTED')")
    suspend fun scheduleRetry(operationId: String, nextAttempt: Long, code: String?): Int

    @Query("UPDATE nfc_exchanges SET state = :state, next_sync_at_epoch_seconds = NULL, last_sync_code = :code WHERE operation_id = :operationId AND state IN ('RESERVED','DELIVERY_UNCERTAIN','LOCALLY_ACCEPTED')")
    suspend fun finishSync(operationId: String, state: String, code: String?): Int

    @Query("UPDATE nfc_exchanges SET state = :state, resolution_envelope = :resolution, resolution_recovery_status = 'COMPLETE', next_sync_at_epoch_seconds = NULL, last_sync_code = :code, server_evidence_stored = 1 WHERE operation_id = :operationId AND state IN ('RESERVED','DELIVERY_UNCERTAIN','LOCALLY_ACCEPTED') AND (resolution_envelope IS NULL OR resolution_envelope = :resolution)")
    suspend fun finishSyncWithResolution(operationId: String, state: String, code: String?, resolution: ByteArray): Int

    @Query("SELECT * FROM nfc_exchanges WHERE wallet_id = :walletId AND state IN ('SYNCED','REJECTED') AND receipt_envelope IS NOT NULL AND resolution_envelope IS NULL AND resolution_recovery_status = 'PENDING' AND authorization_id IN (SELECT id FROM local_offline_authorizations WHERE authorization_envelope IS NOT NULL) AND (next_sync_at_epoch_seconds IS NULL OR next_sync_at_epoch_seconds <= :now) ORDER BY created_at_epoch_seconds ASC LIMIT :limit")
    suspend fun getResolutionRecoveryCandidates(walletId: String, now: Long, limit: Int): List<NfcExchange>

    @Query("SELECT MIN(COALESCE(next_sync_at_epoch_seconds, 0)) FROM nfc_exchanges WHERE wallet_id = :walletId AND state IN ('SYNCED','REJECTED') AND receipt_envelope IS NOT NULL AND resolution_envelope IS NULL AND resolution_recovery_status = 'PENDING' AND authorization_id IN (SELECT id FROM local_offline_authorizations WHERE authorization_envelope IS NOT NULL)")
    suspend fun getNextResolutionRecoveryAt(walletId: String): Long?

    @Query("UPDATE nfc_exchanges SET resolution_envelope = :resolution, resolution_recovery_status = 'COMPLETE', next_sync_at_epoch_seconds = NULL, server_evidence_stored = 1 WHERE operation_id = :operationId AND state IN ('SYNCED','REJECTED') AND resolution_envelope IS NULL AND resolution_recovery_status = 'PENDING'")
    suspend fun attachFinalResolution(operationId: String, resolution: ByteArray): Int

    @Query("UPDATE nfc_exchanges SET sync_attempts = sync_attempts + 1, next_sync_at_epoch_seconds = :nextAttempt WHERE operation_id = :operationId AND state IN ('SYNCED','REJECTED') AND resolution_envelope IS NULL AND resolution_recovery_status = 'PENDING'")
    suspend fun retryResolutionRecovery(operationId: String, nextAttempt: Long): Int

    @Query("UPDATE nfc_exchanges SET resolution_recovery_status = 'ATTENTION_REQUIRED', next_sync_at_epoch_seconds = NULL WHERE operation_id = :operationId AND state IN ('SYNCED','REJECTED') AND resolution_envelope IS NULL")
    suspend fun flagResolutionRecovery(operationId: String): Int

    @Query("UPDATE nfc_exchanges SET resolution_recovery_status = 'PENDING' WHERE wallet_id = :walletId AND resolution_recovery_status = 'ATTENTION_REQUIRED' AND state IN ('SYNCED','REJECTED') AND resolution_envelope IS NULL")
    suspend fun retryAttentionResolutionRecovery(walletId: String): Int

    @Query("SELECT * FROM nfc_exchanges WHERE authorization_id = :authorizationId AND receipt_envelope IS NOT NULL ORDER BY created_at_epoch_seconds ASC")
    suspend fun getByAuthorization(authorizationId: String): List<NfcExchange>

    @Query("SELECT * FROM nfc_exchanges WHERE wallet_id = :walletId ORDER BY created_at_epoch_seconds DESC LIMIT :limit")
    suspend fun getHistoryForWallet(walletId: String, limit: Int = 100): List<NfcExchange>

    @Query("SELECT * FROM nfc_exchanges WHERE wallet_id = :walletId AND authorization_id IN (SELECT id FROM local_offline_authorizations WHERE authorization_envelope IS NULL) AND receipt_envelope IS NOT NULL AND legacy_inventory_registered = 0 ORDER BY created_at_epoch_seconds ASC LIMIT :limit")
    suspend fun getLegacyInventoryPending(walletId: String, limit: Int = 100): List<NfcExchange>

    @Query("UPDATE nfc_exchanges SET legacy_inventory_registered = 1 WHERE operation_id IN (:operationIds)")
    suspend fun markLegacyInventoryRegistered(operationIds: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrustedKey(key: TrustedPublicKey)

    @Query("UPDATE trusted_public_keys SET revoked = 1 WHERE owner_type = :ownerType")
    suspend fun revokeInventory(ownerType: String): Int

    @Query("SELECT * FROM trusted_public_keys WHERE key_id = :keyId AND fingerprint = :fingerprint AND owner_type = :ownerType AND revoked = 0 AND (:scopeId IS NULL OR scope_id = :scopeId) LIMIT 1")
    suspend fun getActiveTrustedKey(
        keyId: String,
        fingerprint: String,
        ownerType: String,
        scopeId: String? = null
    ): TrustedPublicKey?
}
