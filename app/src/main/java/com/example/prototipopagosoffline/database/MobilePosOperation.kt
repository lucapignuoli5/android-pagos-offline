package com.example.prototipopagosoffline.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "mobile_pos_operations",
    indices = [
        Index(value = ["state", "created_at_epoch_seconds"]),
        Index(value = ["next_sync_at_epoch_seconds"])
    ]
)
data class MobilePosOperation(
    @PrimaryKey @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "request_envelope", typeAffinity = ColumnInfo.BLOB) val requestEnvelope: ByteArray,
    @ColumnInfo(name = "authorization_envelope", typeAffinity = ColumnInfo.BLOB) val authorizationEnvelope: ByteArray? = null,
    @ColumnInfo(name = "receipt_envelope", typeAffinity = ColumnInfo.BLOB) val receiptEnvelope: ByteArray? = null,
    @ColumnInfo(name = "ack_envelope", typeAffinity = ColumnInfo.BLOB) val ackEnvelope: ByteArray? = null,
    @ColumnInfo(name = "merchant_id") val merchantId: String,
    @ColumnInfo(name = "terminal_id") val terminalId: String,
    @ColumnInfo(name = "wallet_id") val walletId: String? = null,
    @ColumnInfo(name = "authorization_id") val authorizationId: String? = null,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    val state: String,
    @ColumnInfo(name = "created_at_epoch_seconds") val createdAtEpochSeconds: Long,
    @ColumnInfo(name = "sync_attempts", defaultValue = "0") val syncAttempts: Int = 0,
    @ColumnInfo(name = "next_sync_at_epoch_seconds") val nextSyncAtEpochSeconds: Long? = null,
    @ColumnInfo(name = "last_sync_code") val lastSyncCode: String? = null,
    @ColumnInfo(name = "resolution_envelope", typeAffinity = ColumnInfo.BLOB) val resolutionEnvelope: ByteArray? = null,
    @ColumnInfo(name = "resolution_recovery_status", defaultValue = "'PENDING'") val resolutionRecoveryStatus: String = "PENDING"
)
