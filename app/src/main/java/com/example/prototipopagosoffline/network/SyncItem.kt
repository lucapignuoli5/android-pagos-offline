package com.example.prototipopagosoffline.network

data class SyncItem(
    val id_transaccion: String,
    val monto: Int,
    val timestamp: String,
    val comercio_id: Int,
    val token_id: String,
    val firma: String,
    val payload_original: String,
    val clave_publica: String
)
