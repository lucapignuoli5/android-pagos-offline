package com.example.prototipopagosoffline.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WalletDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWallet(wallet: OfflineWallet)

    @Query("SELECT * FROM offline_wallet WHERE id = :id LIMIT 1")
    suspend fun getWalletById(id: String): OfflineWallet?

    @Query("SELECT * FROM offline_wallet LIMIT 1")
    suspend fun getWallet(): OfflineWallet?
}
