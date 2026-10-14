package com.example.prototipopagosoffline.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MobileTerminalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(terminal: MobileTerminal)

    @Query("SELECT * FROM mobile_terminals WHERE terminal_id = :terminalId LIMIT 1")
    suspend fun getById(terminalId: String): MobileTerminal?

    @Query("SELECT * FROM mobile_terminals WHERE user_id = :userId LIMIT 1")
    suspend fun getForUser(userId: Int): MobileTerminal?
}
