package com.example.prototipopagosoffline.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AuthorizationDao {
    @Query("SELECT * FROM local_offline_authorizations WHERE id = :authorizationId LIMIT 1")
    suspend fun getAuthorization(authorizationId: String): LocalOfflineAuthorization?

    @Query("SELECT * FROM local_offline_authorizations WHERE wallet_id = :walletId AND authorization_envelope IS NOT NULL AND expires_at_epoch_seconds <= :now AND closure_state IN ('OPEN','FROZEN','READY') ORDER BY expires_at_epoch_seconds ASC")
    suspend fun getExpiredForRecovery(walletId: String, now: Long): List<LocalOfflineAuthorization>

    @Query("SELECT * FROM local_offline_authorizations WHERE wallet_id = :walletId AND authorization_envelope IS NOT NULL AND (expires_at_epoch_seconds <= :now OR remaining_payments <= 0 OR remaining_amount_cents <= 0) AND closure_state IN ('OPEN','FROZEN','READY') ORDER BY expires_at_epoch_seconds ASC")
    suspend fun getExplicitlyClosable(walletId: String, now: Long): List<LocalOfflineAuthorization>

    @Query("SELECT EXISTS(SELECT 1 FROM local_offline_authorizations WHERE wallet_id = :walletId AND authorization_envelope IS NOT NULL)")
    suspend fun hasV3AuthorizationHistory(walletId: String): Boolean

    @Query("SELECT * FROM authorization_issuance_attempts WHERE wallet_id = :walletId LIMIT 1")
    suspend fun getIssuanceAttempt(walletId: String): AuthorizationIssuanceAttempt?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIssuanceAttempt(attempt: AuthorizationIssuanceAttempt)

    @Query("UPDATE authorization_issuance_attempts SET state = 'SENT', last_result_code = NULL WHERE wallet_id = :walletId AND request_id = :requestId")
    suspend fun markIssuanceSent(walletId: String, requestId: String): Int

    @Query("UPDATE authorization_issuance_attempts SET last_result_code = :code WHERE wallet_id = :walletId AND request_id = :requestId")
    suspend fun recordIssuanceResult(walletId: String, requestId: String, code: String?): Int

    @Query("DELETE FROM authorization_issuance_attempts WHERE wallet_id = :walletId AND request_id = :requestId")
    suspend fun completeIssuanceAttempt(walletId: String, requestId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertAuthorization(authorization: LocalOfflineAuthorization)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertKeyMetadata(metadata: WalletKeyMetadata)

    @Query("SELECT * FROM wallet_key_metadata WHERE key_id = :keyId AND wallet_id = :walletId LIMIT 1")
    suspend fun getKeyMetadata(walletId: String, keyId: String): WalletKeyMetadata?

    @Query("UPDATE wallet_key_metadata SET retired = 1 WHERE key_id = :keyId AND wallet_id = :walletId")
    suspend fun retireKey(walletId: String, keyId: String): Int

    @Query("SELECT * FROM local_offline_authorizations WHERE wallet_id = :walletId AND authorization_envelope IS NOT NULL AND expires_at_epoch_seconds <= :now AND closure_state IN ('OPEN','FROZEN','READY') ORDER BY expires_at_epoch_seconds ASC")
    suspend fun getClosable(walletId: String, now: Long): List<LocalOfflineAuthorization>

    @Query("UPDATE local_offline_authorizations SET active = 0, closure_payload = :payload, closure_manifest = :manifest, closure_state = 'FROZEN' WHERE id = :authorizationId AND closure_state = 'OPEN' AND active = 1")
    suspend fun freezeClosurePayload(authorizationId: String, payload: ByteArray, manifest: ByteArray): Int

    @Query("UPDATE local_offline_authorizations SET closure_envelope = :closure, closure_state = 'READY' WHERE id = :authorizationId AND closure_state = 'FROZEN' AND active = 0")
    suspend fun saveClosureEnvelope(authorizationId: String, closure: ByteArray): Int

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
