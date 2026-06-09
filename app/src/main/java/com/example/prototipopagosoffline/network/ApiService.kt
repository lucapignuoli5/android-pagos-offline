package com.example.prototipopagosoffline.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("sync-offline-payments")
    suspend fun syncPayments(@Body payments: List<SyncItem>): Response<Unit>

    @POST("/api/login")
    suspend fun login(@Body loginRequest: LoginRequest): Response<LoginResponse>

    @POST("/api/billetera/recargar")
    suspend fun recargarSaldo(@Body request: RecargaRequest): Response<RecargaResponse>
}
