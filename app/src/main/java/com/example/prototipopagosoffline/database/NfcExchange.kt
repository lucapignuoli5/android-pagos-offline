package com.example.prototipopagosoffline.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "nfc_exchanges", indices = [Index(value = ["state", "created_at_epoch_seconds"])])
data class NfcExchange(
    @PrimaryKey @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "request_envelope", typeAffinity = ColumnInfo.BLOB) val requestEnvelope: ByteArray,
    @ColumnInfo(name = "receipt_envelope", typeAffinity = ColumnInfo.BLOB) val receiptEnvelope: ByteArray? = null,
    @ColumnInfo(name = "ack_envelope", typeAffinity = ColumnInfo.BLOB) val ackEnvelope: ByteArray? = null,
    val state: String,
    @ColumnInfo(name = "created_at_epoch_seconds") val createdAtEpochSeconds: Long
)

@Entity(tableName = "trusted_public_keys", indices = [Index(value = ["fingerprint"], unique = true)])
data class TrustedPublicKey(
    @PrimaryKey @ColumnInfo(name = "key_id") val keyId: String,
    val fingerprint: String,
    @ColumnInfo(name = "public_key_b64") val publicKeyBase64: String,
    @ColumnInfo(name = "owner_type") val ownerType: String,
    val revoked: Boolean = false
)
