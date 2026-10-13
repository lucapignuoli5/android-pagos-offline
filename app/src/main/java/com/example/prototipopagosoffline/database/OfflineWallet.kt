package com.example.prototipopagosoffline.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "offline_wallet")
data class OfflineWallet(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "confirmed_balance_cents")
    val confirmedBalanceCents: Long,
    @ColumnInfo(name = "reserved_balance_cents")
    val reservedBalanceCents: Long = 0,
    val currency: String = "ARS"
) {
    val spendableBalanceCents: Long
        get() = confirmedBalanceCents - reservedBalanceCents
}
