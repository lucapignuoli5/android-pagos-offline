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
data class DeviceLoginRequest(val installation_id: String, val role: String, val device_secret: String)

interface ApiService {
    @POST("/api/demo/device-login")
    suspend fun deviceLogin(@Body request: DeviceLoginRequest): Response<LoginResponse>
    @POST("sync-offline-payments")
    suspend fun syncPayments(@Body payments: List<SyncItem>): Response<Unit>

    @POST("/api/login")
    suspend fun login(@Body loginRequest: LoginRequest): Response<LoginResponse>

    @POST("/api/keys/register")
    suspend fun registerKey(@Body request: KeyRegistrationRequest): Response<KeyRegistrationResponse>

    @GET("/api/keys/trusted/{purpose}")
    suspend fun getTrustedKeys(@Path("purpose") purpose: String): Response<List<TrustedKeyResponse>>

    @POST("/api/offline-authorizations")
    suspend fun createOfflineAuthorization(
        @Body request: OfflineAuthorizationRequest
    ): Response<OfflineAuthorizationResponse>

    @POST("/api/v3/offline-authorizations")
    suspend fun createOfflineAuthorizationV3(
        @Body request: OfflineAuthorizationRequest
    ): Response<OfflineAuthorizationV3Response>

    @POST("/api/v3/offline-payments/sync")
    suspend fun syncOfflinePaymentsV3(
        @Body request: OfflinePaymentSyncV3Request
    ): Response<OfflinePaymentSyncV3Response>

    @GET("/api/v3/offline-payments/{operationId}")
    suspend fun getOfflinePaymentV3(
        @Path("operationId") operationId: String
    ): Response<OfflinePaymentResultV3>

    @POST("/api/v3/legacy-inventory")
    suspend fun registerLegacyInventory(
        @Body request: LegacyInventoryRequest
    ): Response<LegacyInventoryResponse>

    @POST("/api/v3/offline-authorizations/{authorizationId}/close")
    suspend fun closeOfflineAuthorizationV3(
        @Path("authorizationId") authorizationId: String,
        @Body request: OfflineAuthorizationCloseRequest
    ): Response<OfflineCommitmentResponse>

    @GET("/api/wallets/{walletId}/balance")
    suspend fun getWalletBalance(@Path("walletId") walletId: String): Response<WalletBalanceResponse>

    @POST("/api/offline-payments/sync")
    suspend fun syncOfflinePaymentsV2(
        @Body request: OfflinePaymentSyncV2Request
    ): Response<OfflinePaymentSyncV2Response>

    @GET("/api/offline-payments/{operationId}")
    suspend fun getOfflinePaymentStatus(
        @Path("operationId") operationId: String
    ): Response<OfflinePaymentStatusResponse>

    @POST("/api/v3/simulated-topups")
    suspend fun simulatedTopup(@Body request: SimulatedTopupRequest): Response<SimulatedTopupResponse>

    @GET("/api/billetera/saldo")
    suspend fun consultarSaldo(): Response<SaldoResponse>

    @POST("/api/billetera/crear-link-pago")
    suspend fun crearLinkPago(@Body request: LinkPagoRequest): Response<LinkPagoResponse>
}
