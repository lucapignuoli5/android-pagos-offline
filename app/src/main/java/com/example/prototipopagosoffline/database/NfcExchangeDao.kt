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

    @Query("UPDATE nfc_exchanges SET ack_envelope = :ack, state = 'LOCALLY_ACCEPTED', next_sync_at_epoch_seconds = NULL, last_sync_code = NULL WHERE operation_id = :operationId AND state IN ('RESERVED', 'DELIVERY_UNCERTAIN')")
    suspend fun setAck(operationId: String, ack: ByteArray): Int

    @Query("SELECT * FROM nfc_exchanges WHERE wallet_id = :walletId AND receipt_envelope IS NOT NULL AND state IN ('RESERVED','DELIVERY_UNCERTAIN','LOCALLY_ACCEPTED') AND (next_sync_at_epoch_seconds IS NULL OR next_sync_at_epoch_seconds <= :now) ORDER BY created_at_epoch_seconds ASC LIMIT :limit")
    suspend fun getSyncCandidates(walletId: String, now: Long, limit: Int = 100): List<NfcExchange>

    @Query("SELECT MIN(COALESCE(next_sync_at_epoch_seconds, 0)) FROM nfc_exchanges WHERE wallet_id = :walletId AND receipt_envelope IS NOT NULL AND state IN ('RESERVED','DELIVERY_UNCERTAIN','LOCALLY_ACCEPTED')")
    suspend fun getNextSyncAt(walletId: String): Long?

    @Query("UPDATE nfc_exchanges SET sync_attempts = sync_attempts + 1, next_sync_at_epoch_seconds = :nextAttempt, last_sync_code = :code WHERE operation_id = :operationId AND state IN ('RESERVED','DELIVERY_UNCERTAIN','LOCALLY_ACCEPTED')")
    suspend fun scheduleRetry(operationId: String, nextAttempt: Long, code: String?): Int

    @Query("UPDATE nfc_exchanges SET state = :state, next_sync_at_epoch_seconds = NULL, last_sync_code = :code WHERE operation_id = :operationId AND state IN ('RESERVED','DELIVERY_UNCERTAIN','LOCALLY_ACCEPTED')")
    suspend fun finishSync(operationId: String, state: String, code: String?): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrustedKey(key: TrustedPublicKey)

    @Query("SELECT * FROM trusted_public_keys WHERE key_id = :keyId AND fingerprint = :fingerprint AND owner_type = :ownerType AND revoked = 0 LIMIT 1")
    suspend fun getActiveTrustedKey(
        keyId: String,
        fingerprint: String,
        ownerType: String
    ): TrustedPublicKey?
}
