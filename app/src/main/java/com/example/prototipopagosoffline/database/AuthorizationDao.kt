package com.example.prototipopagosoffline.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AuthorizationDao {
    @Query("SELECT * FROM local_offline_authorizations WHERE id = :authorizationId LIMIT 1")
    suspend fun getAuthorization(authorizationId: String): LocalOfflineAuthorization?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertAuthorization(authorization: LocalOfflineAuthorization)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertKeyMetadata(metadata: WalletKeyMetadata)

    @Query("SELECT * FROM wallet_key_metadata WHERE key_id = :keyId AND wallet_id = :walletId LIMIT 1")
    suspend fun getKeyMetadata(walletId: String, keyId: String): WalletKeyMetadata?

    @Query("UPDATE wallet_key_metadata SET retired = 1 WHERE key_id = :keyId AND wallet_id = :walletId")
    suspend fun retireKey(walletId: String, keyId: String): Int

    @Query("SELECT * FROM local_offline_authorizations WHERE wallet_id = :walletId AND authorization_envelope IS NOT NULL AND expires_at_epoch_seconds <= :now AND closure_state IN ('OPEN','READY') ORDER BY expires_at_epoch_seconds ASC")
    suspend fun getClosable(walletId: String, now: Long): List<LocalOfflineAuthorization>

    @Query("UPDATE local_offline_authorizations SET active = 0, closure_envelope = :closure, closure_manifest = :manifest, closure_state = 'READY' WHERE id = :authorizationId AND closure_state = 'OPEN'")
    suspend fun freezeClosure(authorizationId: String, closure: ByteArray, manifest: ByteArray): Int

    @Query("UPDATE local_offline_authorizations SET closure_state = 'CLOSED' WHERE id = :authorizationId AND closure_state = 'READY'")
    suspend fun markClosed(authorizationId: String): Int

    @Query("SELECT * FROM local_offline_authorizations WHERE wallet_id = :walletId AND active = 1 AND issued_at_epoch_seconds <= :now AND expires_at_epoch_seconds >= :expiresAt AND max_payment_cents >= :amount AND remaining_amount_cents >= :amount AND remaining_payments > 0 ORDER BY expires_at_epoch_seconds ASC LIMIT 1")
    suspend fun findEligible(walletId: String, amount: Long, now: Long, expiresAt: Long): LocalOfflineAuthorization?

    @Query("SELECT * FROM local_offline_authorizations WHERE wallet_id = :walletId AND authorization_envelope IS NOT NULL AND active = 1 AND issued_at_epoch_seconds <= :now AND expires_at_epoch_seconds >= :expiresAt AND max_payment_cents >= :amount AND remaining_amount_cents >= :amount AND remaining_payments > 0 ORDER BY expires_at_epoch_seconds ASC LIMIT 1")
    suspend fun findEligibleV3(walletId: String, amount: Long, now: Long, expiresAt: Long): LocalOfflineAuthorization?

    @Query("SELECT * FROM local_offline_authorizations WHERE wallet_id = :walletId AND wallet_key_id = :keyId AND authorization_envelope IS NOT NULL AND active = 1 AND expires_at_epoch_seconds > :now AND remaining_payments > 0 ORDER BY expires_at_epoch_seconds DESC LIMIT 1")
    suspend fun findActiveForProvisioning(walletId: String, keyId: String, now: Long): LocalOfflineAuthorization?

    @Query("UPDATE local_offline_authorizations SET remaining_amount_cents = remaining_amount_cents - :amount, remaining_payments = remaining_payments - 1, next_issuance_index = next_issuance_index + 1 WHERE id = :authorizationId AND active = 1 AND issued_at_epoch_seconds <= :now AND expires_at_epoch_seconds >= :expiresAt AND max_payment_cents >= :amount AND remaining_amount_cents >= :amount AND remaining_payments > 0")
    suspend fun consume(authorizationId: String, amount: Long, now: Long, expiresAt: Long): Int

    @Query("UPDATE local_offline_authorizations SET remaining_amount_cents = remaining_amount_cents + :amount, remaining_payments = remaining_payments + 1 WHERE id = :authorizationId")
    suspend fun restore(authorizationId: String, amount: Long): Int
}
