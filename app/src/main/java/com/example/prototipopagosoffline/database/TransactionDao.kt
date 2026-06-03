package com.example.prototipopagosoffline.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionHistory)

    @Query("SELECT * FROM transaction_history WHERE id_transaccion = :idTransaccion LIMIT 1")
    suspend fun getTransactionById(idTransaccion: String): TransactionHistory?

    @Query("SELECT * FROM transaction_history ORDER BY timestamp DESC")
    suspend fun getAllTransactions(): List<TransactionHistory>

    @Query("SELECT * FROM transaction_history WHERE estado_sincronizacion = :syncState ORDER BY timestamp ASC")
    suspend fun getTransactionsBySyncState(syncState: SyncState): List<TransactionHistory>

    @Query("UPDATE transaction_history SET estado_sincronizacion = :syncState WHERE id_transaccion IN (:transactionIds)")
    suspend fun updateSyncState(transactionIds: List<String>, syncState: SyncState)
}
