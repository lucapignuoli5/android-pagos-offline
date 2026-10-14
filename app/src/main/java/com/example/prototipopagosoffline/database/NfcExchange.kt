package com.example.prototipopagosoffline.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "nfc_exchanges",
    indices = [
        Index(value = ["state", "created_at_epoch_seconds"]),
        Index(value = ["next_sync_at_epoch_seconds"])
    ]
)
data class NfcExchange(
    @PrimaryKey @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "request_envelope", typeAffinity = ColumnInfo.BLOB) val requestEnvelope: ByteArray,
    @ColumnInfo(name = "receipt_envelope", typeAffinity = ColumnInfo.BLOB) val receiptEnvelope: ByteArray? = null,
    @ColumnInfo(name = "ack_envelope", typeAffinity = ColumnInfo.BLOB) val ackEnvelope: ByteArray? = null,
    @ColumnInfo(name = "wallet_id") val walletId: String? = null,
    @ColumnInfo(name = "authorization_id") val authorizationId: String? = null,
    @ColumnInfo(name = "amount_cents") val amountCents: Long? = null,
    @ColumnInfo(name = "merchant_id") val merchantId: String? = null,
    @ColumnInfo(name = "terminal_id") val terminalId: String? = null,
    val state: String,
    @ColumnInfo(name = "created_at_epoch_seconds") val createdAtEpochSeconds: Long,
    @ColumnInfo(name = "sync_attempts", defaultValue = "0") val syncAttempts: Int = 0,
    @ColumnInfo(name = "next_sync_at_epoch_seconds") val nextSyncAtEpochSeconds: Long? = null,
    @ColumnInfo(name = "last_sync_code") val lastSyncCode: String? = null,
    @ColumnInfo(name = "request_expires_at_epoch_seconds") val requestExpiresAtEpochSeconds: Long? = null,
    @ColumnInfo(name = "cancelled_at_epoch_seconds") val cancelledAtEpochSeconds: Long? = null
)

@Entity(
    tableName = "trusted_public_keys",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["owner_type", "scope_id"])
    ]
)
data class TrustedPublicKey(
    @PrimaryKey @ColumnInfo(name = "key_id") val keyId: String,
    val fingerprint: String,
    @ColumnInfo(name = "public_key_b64") val publicKeyBase64: String,
    @ColumnInfo(name = "owner_type") val ownerType: String,
    @ColumnInfo(name = "scope_id") val scopeId: String? = null,
    val revoked: Boolean = false
)
