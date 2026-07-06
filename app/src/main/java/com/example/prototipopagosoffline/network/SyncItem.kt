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

data class LoginRequest(
    val usuario: String,
    val password: String
)

data class LoginResponse(
    val access_token: String,
    val token_type: String
)

data class RecargaRequest(val token_id: String, val monto_recarga: Double)
data class RecargaResponse(val exitoso: Boolean, val nuevo_saldo: Double)

data class SaldoResponse(
    val usuario: String,
    val saldo_real: Double
)
