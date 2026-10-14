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
    val token_type: String,
    val expires_in: Int = 3600,
    val user_id: Int,
    val wallet_id: String
)

data class RecargaRequest(val token_id: String, val monto_recarga: Double)
data class RecargaResponse(val exitoso: Boolean, val nuevo_saldo: Double)

data class SaldoResponse(
    val usuario: String,
    val saldo_real: Double
)

data class KeyRegistrationRequest(
    val public_key_b64: String,
    val proof_payload_b64: String,
    val proof_signature_b64: String
)

data class KeyRegistrationResponse(
    val key_id: String,
    val fingerprint: String,
    val purpose: String,
    val scope_id: String,
    val created: Boolean
)

data class TrustedKeyResponse(
    val key_id: String,
    val fingerprint: String,
    val purpose: String,
    val scope_id: String,
    val public_key_b64: String
)

data class OfflineAuthorizationRequest(
    val wallet_id: String,
    val wallet_key_id: String,
    val max_payment_cents: Long,
    val max_payments: Int
)

data class OfflineAuthorizationResponse(
    val authorization_id: String,
    val wallet_id: String,
    val wallet_key_id: String,
    val currency: String,
    val max_payment_cents: Long,
    val max_payments: Int,
    val authorized_total_cents: Long,
    val issued_at: String,
    val expires_at: String
)

data class OfflineAuthorizationV3Response(
    val authorization_id: String,
    val wallet_id: String,
    val authorized_total_cents: Long,
    val authorization_envelope_b64: String,
    val issued_at: String,
    val spend_expires_at: String
)

data class OfflinePaymentEvidenceV3(
    val operation_id: String,
    val request_envelope_b64: String,
    val authorization_envelope_b64: String,
    val receipt_envelope_b64: String,
    val ack_envelope_b64: String?
)

data class OfflinePaymentSyncV3Request(val operations: List<OfflinePaymentEvidenceV3>)

data class OfflinePaymentResultV3(
    val operation_id: String,
    val submission_status: String,
    val operation_status: String,
    val final: Boolean,
    val code: String?
)

data class OfflinePaymentSyncV3Response(val results: List<OfflinePaymentResultV3>)

data class OfflineAuthorizationCloseRequest(
    val close_envelope_b64: String,
    val manifest_b64: String
)

data class OfflineCommitmentResponse(
    val authorization_id: String,
    val reserved_cents: Long,
    val emitted_cents: Long,
    val emitted_count: Int,
    val closed: Boolean
)

data class WalletBalanceResponse(
    val wallet_id: String,
    val currency: String,
    val confirmed_balance_cents: Long,
    val authorized_offline_cents: Long,
    val available_for_authorization_cents: Long
)

data class OfflinePaymentEvidence(
    val operation_id: String,
    val request_envelope_b64: String,
    val receipt_envelope_b64: String,
    val ack_envelope_b64: String?
)

data class OfflinePaymentSyncV2Request(val operations: List<OfflinePaymentEvidence>)

data class OfflinePaymentResultV2(
    val operation_id: String,
    val status: String,
    val code: String?
)

data class OfflinePaymentSyncV2Response(val results: List<OfflinePaymentResultV2>)

data class OfflinePaymentStatusResponse(
    val operation_id: String,
    val status: String,
    val code: String?
)
