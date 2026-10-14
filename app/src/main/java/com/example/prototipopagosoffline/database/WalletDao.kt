package com.example.prototipopagosoffline.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWallet(wallet: OfflineWallet)

    @Query("SELECT * FROM offline_wallet WHERE id = :id LIMIT 1")
    suspend fun getWalletById(id: String): OfflineWallet?

    @Query("SELECT * FROM offline_wallet LIMIT 1")
    suspend fun getWallet(): OfflineWallet?

    @Query("SELECT * FROM offline_wallet WHERE id = :id LIMIT 1")
    fun observeWallet(id: String): Flow<OfflineWallet?>

    @Query("UPDATE offline_wallet SET reserved_balance_cents = reserved_balance_cents + :amount WHERE id = :walletId AND confirmed_balance_cents - reserved_balance_cents >= :amount")
    suspend fun reserve(walletId: String, amount: Long): Int

    @Query("UPDATE offline_wallet SET reserved_balance_cents = reserved_balance_cents - :amount WHERE id = :walletId AND reserved_balance_cents >= :amount")
    suspend fun releaseReservation(walletId: String, amount: Long): Int

    @Query("UPDATE offline_wallet SET confirmed_balance_cents = :confirmedBalance, reserved_balance_cents = reserved_balance_cents - :amount WHERE id = :walletId AND reserved_balance_cents >= :amount")
    suspend fun settleAccepted(walletId: String, amount: Long, confirmedBalance: Long): Int
}
