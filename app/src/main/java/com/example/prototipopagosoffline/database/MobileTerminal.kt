package com.example.prototipopagosoffline.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "mobile_terminals",
    indices = [
        Index(value = ["user_id"], unique = true),
        Index(value = ["pos_key_id"], unique = true)
    ]
)
data class MobileTerminal(
    @PrimaryKey @ColumnInfo(name = "terminal_id") val terminalId: String,
    @ColumnInfo(name = "merchant_id") val merchantId: String,
    @ColumnInfo(name = "user_id") val userId: Int,
    @ColumnInfo(name = "pos_key_id") val posKeyId: String,
    @ColumnInfo(name = "pos_key_fingerprint") val posKeyFingerprint: String,
    @ColumnInfo(name = "keystore_alias") val keyStoreAlias: String,
    @ColumnInfo(name = "public_key_b64") val publicKeyBase64: String,
    @ColumnInfo(name = "created_at_epoch_seconds") val createdAtEpochSeconds: Long
)
