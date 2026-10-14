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

    @Query("UPDATE mobile_pos_operations SET receipt_envelope = :receipt, ack_envelope = :ack, wallet_id = :walletId, authorization_id = :authorizationId, state = 'LOCALLY_ACCEPTED', next_sync_at_epoch_seconds = NULL, last_sync_code = NULL WHERE operation_id = :operationId AND state = 'REQUEST_CREATED' AND receipt_envelope IS NULL AND ack_envelope IS NULL")
    suspend fun accept(
        operationId: String,
        receipt: ByteArray,
        ack: ByteArray,
        walletId: String,
        authorizationId: String
    ): Int
}
