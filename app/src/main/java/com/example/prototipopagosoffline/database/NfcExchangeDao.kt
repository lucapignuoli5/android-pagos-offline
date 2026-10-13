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

    @Query("UPDATE nfc_exchanges SET ack_envelope = :ack, state = 'LOCALLY_ACCEPTED' WHERE operation_id = :operationId AND state IN ('RESERVED', 'DELIVERY_UNCERTAIN')")
    suspend fun setAck(operationId: String, ack: ByteArray): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrustedKey(key: TrustedPublicKey)

    @Query("SELECT * FROM trusted_public_keys WHERE key_id = :keyId AND fingerprint = :fingerprint AND revoked = 0 LIMIT 1")
    suspend fun getActiveTrustedKey(keyId: String, fingerprint: String): TrustedPublicKey?
}
