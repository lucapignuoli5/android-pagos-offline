package com.example.prototipopagosoffline.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "local_offline_authorizations", indices = [Index("wallet_id"), Index("wallet_key_id")])
data class LocalOfflineAuthorization(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "wallet_id") val walletId: String,
    @ColumnInfo(name = "wallet_key_id") val walletKeyId: String,
    @ColumnInfo(name = "max_payment_cents") val maxPaymentCents: Long,
    @ColumnInfo(name = "remaining_amount_cents") val remainingAmountCents: Long,
    @ColumnInfo(name = "remaining_payments") val remainingPayments: Int,
    @ColumnInfo(name = "issued_at_epoch_seconds") val issuedAtEpochSeconds: Long,
    @ColumnInfo(name = "expires_at_epoch_seconds") val expiresAtEpochSeconds: Long,
    @ColumnInfo(name = "authorization_envelope", typeAffinity = ColumnInfo.BLOB) val authorizationEnvelope: ByteArray? = null,
    @ColumnInfo(name = "next_issuance_index", defaultValue = "1") val nextIssuanceIndex: Int = 1,
    @ColumnInfo(name = "closure_envelope", typeAffinity = ColumnInfo.BLOB) val closureEnvelope: ByteArray? = null,
    @ColumnInfo(name = "closure_manifest", typeAffinity = ColumnInfo.BLOB) val closureManifest: ByteArray? = null,
    @ColumnInfo(name = "closure_state", defaultValue = "OPEN") val closureState: String = "OPEN",
    val active: Boolean = true
)

@Entity(tableName = "wallet_key_metadata", indices = [Index("wallet_id")])
data class WalletKeyMetadata(
    @PrimaryKey @ColumnInfo(name = "key_id") val keyId: String,
    @ColumnInfo(name = "wallet_id") val walletId: String,
    @ColumnInfo(name = "keystore_alias") val keyStoreAlias: String,
    @ColumnInfo(name = "public_key_b64") val publicKeyBase64: String,
    val fingerprint: String,
    @ColumnInfo(name = "created_at_epoch_seconds") val createdAtEpochSeconds: Long,
    val retired: Boolean = false
)
