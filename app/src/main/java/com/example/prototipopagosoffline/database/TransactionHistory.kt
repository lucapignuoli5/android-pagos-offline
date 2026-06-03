package com.example.prototipopagosoffline.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "transaction_history")
data class TransactionHistory(
    @PrimaryKey
    @ColumnInfo(name = "id_transaccion")
    @SerializedName("id_transaccion")
    val idTransaccion: String,
    
    val monto: Long,
    
    val timestamp: String,
    
    @ColumnInfo(name = "comercio_id")
    @SerializedName("comercio_id")
    val comercioId: Int,
    
    @ColumnInfo(name = "token_id")
    @SerializedName("token_id")
    val tokenId: String,
    
    val firma: String,
    
    @ColumnInfo(name = "payload_original")
    @SerializedName("payload_original")
    val payloadOriginal: String,

    @ColumnInfo(name = "clave_publica")
    @SerializedName("clave_publica")
    val clavePublica: String,

    @ColumnInfo(name = "is_outgoing")
    val isOutgoing: Boolean = false,

    @Transient
    @ColumnInfo(name = "estado_sincronizacion")
    @SerializedName("estado_sincronizacion")
    val estadoSincronizacion: SyncState
)
