package com.example.prototipopagosoffline.protocol

import androidx.room.withTransaction
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.NfcExchange
import java.time.Instant

class RoomNfcExchangeStore(
    private val database: AppDatabase,
    private val walletId: () -> String? = { null }
) : NfcExchangeStore {
    private val dao = database.nfcExchangeDao()

    override suspend fun saveRequest(operationId: String, signedEnvelope: ByteArray): SaveResult =
        database.withTransaction {
            val existing = dao.getExchange(operationId)
            when {
                existing == null -> {
                    val account = walletId() ?: throw IllegalStateException("Billetera no autenticada")
                    val payload = runCatching { ProtocolV3.parseSignedEnvelope(signedEnvelope).payload }
                        .getOrElse { ProtocolV2.parseSignedEnvelope(signedEnvelope).payload }
                    val expiresAt = payload["expires_at"]?.asString
                        ?.let(Instant::parse)
                        ?.epochSecond
                    dao.insertExchange(
                        NfcExchange(
                            operationId = operationId,
                            requestEnvelope = signedEnvelope,
                            state = ExchangeState.REQUEST_RECEIVED.name,
                            createdAtEpochSeconds = System.currentTimeMillis() / 1000,
                            requestExpiresAtEpochSeconds = expiresAt,
                            walletId = account
                        )
                    )
                    SaveResult.INSERTED
                }
                existing.walletId == walletId() && existing.requestEnvelope.contentEquals(signedEnvelope) -> SaveResult.IDENTICAL
                else -> SaveResult.CONFLICT
            }
        }

    override suspend fun getRequest(operationId: String): ByteArray? = dao.getExchange(operationId)?.requestEnvelope

    override suspend fun getState(operationId: String): ExchangeState? =
        dao.getExchange(operationId)?.state?.let(ExchangeState::valueOf)

    override suspend fun getReceipt(operationId: String): ByteArray? = dao.getExchange(operationId)?.receiptEnvelope

    override suspend fun getAuthorization(operationId: String): ByteArray? {
        val authorizationId = dao.getExchange(operationId)?.authorizationId ?: return null
        return database.authorizationDao().getAuthorization(authorizationId)?.authorizationEnvelope
    }

    override suspend fun markDeliveryStarted(operationId: String) {
        dao.markDeliveryStarted(operationId)
    }

    override suspend fun saveAck(operationId: String, signedEnvelope: ByteArray): SaveResult =
        database.withTransaction {
            val existing = dao.getExchange(operationId) ?: return@withTransaction SaveResult.CONFLICT
            when {
                existing.ackEnvelope == null -> {
                    check(dao.setAck(operationId, signedEnvelope) == 1)
                    SaveResult.INSERTED
                }
                existing.ackEnvelope.contentEquals(signedEnvelope) -> SaveResult.IDENTICAL
                else -> SaveResult.CONFLICT
            }
        }

}

class RoomTrustedPublicKeyResolver(
    private val database: AppDatabase,
    private val ownerType: String = "pos"
) : TrustedPublicKeyResolver {
    init {
        require(ownerType == "pos" || ownerType == "wallet")
    }

    override suspend fun resolve(keyId: String, fingerprint: String, scopeId: String?): String? =
        database.nfcExchangeDao()
            .getActiveTrustedKey(keyId, fingerprint, ownerType, scopeId)
            ?.publicKeyBase64
}
