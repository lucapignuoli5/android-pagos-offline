package com.example.prototipopagosoffline.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MobilePosOperationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(operation: MobilePosOperation)

    @Query("SELECT * FROM mobile_pos_operations WHERE operation_id = :operationId LIMIT 1")
    suspend fun get(operationId: String): MobilePosOperation?

    @Query("SELECT * FROM mobile_pos_operations WHERE terminal_id = :terminalId AND merchant_id = :merchantId AND receipt_envelope IS NOT NULL ORDER BY created_at_epoch_seconds DESC LIMIT :limit")
    fun observePaymentHistory(terminalId: String, merchantId: String, limit: Int = 100): Flow<List<MobilePosOperation>>

    @Query("UPDATE mobile_pos_operations SET authorization_envelope = :authorization, receipt_envelope = :receipt, ack_envelope = :ack, wallet_id = :walletId, authorization_id = :authorizationId, state = 'LOCALLY_ACCEPTED', next_sync_at_epoch_seconds = NULL, last_sync_code = NULL WHERE operation_id = :operationId AND state = 'REQUEST_CREATED' AND receipt_envelope IS NULL AND ack_envelope IS NULL")
    suspend fun accept(
        operationId: String,
        authorization: ByteArray,
        receipt: ByteArray,
        ack: ByteArray,
        walletId: String,
        authorizationId: String
    ): Int

    @Query("SELECT * FROM mobile_pos_operations WHERE terminal_id = :terminalId AND authorization_envelope IS NOT NULL AND receipt_envelope IS NOT NULL AND ack_envelope IS NOT NULL AND state = 'LOCALLY_ACCEPTED' AND (next_sync_at_epoch_seconds IS NULL OR next_sync_at_epoch_seconds <= :now) ORDER BY created_at_epoch_seconds ASC LIMIT :limit")
    suspend fun getSyncCandidates(terminalId: String, now: Long, limit: Int = 100): List<MobilePosOperation>

    @Query("SELECT MIN(COALESCE(next_sync_at_epoch_seconds, 0)) FROM mobile_pos_operations WHERE terminal_id = :terminalId AND receipt_envelope IS NOT NULL AND ack_envelope IS NOT NULL AND state = 'LOCALLY_ACCEPTED'")
    suspend fun getNextSyncAt(terminalId: String): Long?

    @Query("UPDATE mobile_pos_operations SET sync_attempts = sync_attempts + 1, next_sync_at_epoch_seconds = :nextAttempt, last_sync_code = :code WHERE operation_id = :operationId AND state = 'LOCALLY_ACCEPTED'")
    suspend fun scheduleRetry(operationId: String, nextAttempt: Long, code: String): Int

    @Query("UPDATE mobile_pos_operations SET state = :state, next_sync_at_epoch_seconds = NULL, last_sync_code = :code WHERE operation_id = :operationId AND state = 'LOCALLY_ACCEPTED'")
    suspend fun finishSync(operationId: String, state: String, code: String?): Int

    @Query("UPDATE mobile_pos_operations SET state = :state, next_sync_at_epoch_seconds = NULL, last_sync_code = :code, resolution_envelope = :resolution, resolution_recovery_status = 'COMPLETE' WHERE operation_id = :operationId AND state = 'LOCALLY_ACCEPTED' AND (resolution_envelope IS NULL OR resolution_envelope = :resolution)")
    suspend fun finishSyncWithResolution(operationId: String, state: String, code: String?, resolution: ByteArray): Int

    @Query("SELECT * FROM mobile_pos_operations WHERE terminal_id = :terminalId AND state IN ('SYNCED','REJECTED') AND receipt_envelope IS NOT NULL AND resolution_envelope IS NULL AND resolution_recovery_status = 'PENDING' AND (next_sync_at_epoch_seconds IS NULL OR next_sync_at_epoch_seconds <= :now) ORDER BY created_at_epoch_seconds ASC LIMIT :limit")
    suspend fun getResolutionRecoveryCandidates(terminalId: String, now: Long, limit: Int): List<MobilePosOperation>

    @Query("SELECT MIN(COALESCE(next_sync_at_epoch_seconds, 0)) FROM mobile_pos_operations WHERE terminal_id = :terminalId AND state IN ('SYNCED','REJECTED') AND receipt_envelope IS NOT NULL AND resolution_envelope IS NULL AND resolution_recovery_status = 'PENDING'")
    suspend fun getNextResolutionRecoveryAt(terminalId: String): Long?

    @Query("UPDATE mobile_pos_operations SET resolution_envelope = :resolution, resolution_recovery_status = 'COMPLETE', next_sync_at_epoch_seconds = NULL WHERE operation_id = :operationId AND state IN ('SYNCED','REJECTED') AND resolution_envelope IS NULL AND resolution_recovery_status = 'PENDING'")
    suspend fun attachFinalResolution(operationId: String, resolution: ByteArray): Int

    @Query("UPDATE mobile_pos_operations SET sync_attempts = sync_attempts + 1, next_sync_at_epoch_seconds = :nextAttempt WHERE operation_id = :operationId AND state IN ('SYNCED','REJECTED') AND resolution_envelope IS NULL AND resolution_recovery_status = 'PENDING'")
    suspend fun retryResolutionRecovery(operationId: String, nextAttempt: Long): Int

    @Query("UPDATE mobile_pos_operations SET resolution_recovery_status = 'ATTENTION_REQUIRED', next_sync_at_epoch_seconds = NULL WHERE operation_id = :operationId AND state IN ('SYNCED','REJECTED') AND resolution_envelope IS NULL")
    suspend fun flagResolutionRecovery(operationId: String): Int

    @Query("UPDATE mobile_pos_operations SET resolution_recovery_status = 'PENDING' WHERE terminal_id = :terminalId AND resolution_recovery_status = 'ATTENTION_REQUIRED' AND state IN ('SYNCED','REJECTED') AND resolution_envelope IS NULL")
    suspend fun retryAttentionResolutionRecovery(terminalId: String): Int
}
