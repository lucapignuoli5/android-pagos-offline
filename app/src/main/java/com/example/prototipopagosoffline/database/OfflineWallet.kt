package com.example.prototipopagosoffline.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "offline_wallet")
data class OfflineWallet(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "saldo_disponible")
    val saldoDisponible: Long
)
