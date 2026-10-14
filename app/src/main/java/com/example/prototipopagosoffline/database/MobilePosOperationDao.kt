package com.example.prototipopagosoffline.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MobilePosOperationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(operation: MobilePosOperation)

    @Query("SELECT * FROM mobile_pos_operations WHERE operation_id = :operationId LIMIT 1")
    suspend fun get(operationId: String): MobilePosOperation?

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
}
