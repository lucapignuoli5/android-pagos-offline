package com.example.prototipopagosoffline.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

data class LinkPagoRequest(val token_id: String, val monto: Int)
data class LinkPagoResponse(val init_point: String)

interface ApiService {
    @POST("sync-offline-payments")
    suspend fun syncPayments(@Body payments: List<SyncItem>): Response<Unit>

    @POST("/api/login")
    suspend fun login(@Body loginRequest: LoginRequest): Response<LoginResponse>

    @POST("/api/billetera/recargar")
    suspend fun recargarSaldo(@Body request: RecargaRequest): Response<RecargaResponse>

    @GET("/api/billetera/saldo")
    suspend fun consultarSaldo(): Response<SaldoResponse>

    @POST("/api/billetera/crear-link-pago")
    suspend fun crearLinkPago(@Body request: LinkPagoRequest): Response<LinkPagoResponse>
}
