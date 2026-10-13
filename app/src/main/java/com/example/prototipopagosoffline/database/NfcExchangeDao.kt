package com.example.prototipopagosoffline.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NfcExchangeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExchange(exchange: NfcExchange): Long

    @Query("SELECT * FROM nfc_exchanges WHERE operation_id = :operationId LIMIT 1")
    suspend fun getExchange(operationId: String): NfcExchange?

    @Query("SELECT * FROM nfc_exchanges WHERE state = 'REQUEST_RECEIVED' ORDER BY created_at_epoch_seconds ASC LIMIT 1")
    fun observeOldestPendingRequest(): Flow<NfcExchange?>

    @Query("UPDATE nfc_exchanges SET receipt_envelope = :receipt, state = 'READY_FOR_DELIVERY' WHERE operation_id = :operationId")
    suspend fun setReceipt(operationId: String, receipt: ByteArray): Int

    @Query("UPDATE nfc_exchanges SET ack_envelope = :ack, state = 'LOCALLY_ACCEPTED' WHERE operation_id = :operationId")
    suspend fun setAck(operationId: String, ack: ByteArray): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrustedKey(key: TrustedPublicKey)

    @Query("SELECT * FROM trusted_public_keys WHERE key_id = :keyId AND fingerprint = :fingerprint AND revoked = 0 LIMIT 1")
    suspend fun getActiveTrustedKey(keyId: String, fingerprint: String): TrustedPublicKey?
}
