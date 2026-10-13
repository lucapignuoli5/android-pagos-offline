package com.example.prototipopagosoffline.protocol

import androidx.room.withTransaction
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.NfcExchange

class RoomNfcExchangeStore(private val database: AppDatabase) : NfcExchangeStore {
    private val dao = database.nfcExchangeDao()

    override suspend fun saveRequest(operationId: String, signedEnvelope: ByteArray): SaveResult =
        database.withTransaction {
            val existing = dao.getExchange(operationId)
            when {
                existing == null -> {
                    dao.insertExchange(
                        NfcExchange(
                            operationId = operationId,
                            requestEnvelope = signedEnvelope,
                            state = ExchangeState.REQUEST_RECEIVED.name,
                            createdAtEpochSeconds = System.currentTimeMillis() / 1000
                        )
                    )
                    SaveResult.INSERTED
                }
                existing.requestEnvelope.contentEquals(signedEnvelope) -> SaveResult.IDENTICAL
                else -> SaveResult.CONFLICT
            }
        }

    override suspend fun getRequest(operationId: String): ByteArray? = dao.getExchange(operationId)?.requestEnvelope

    override suspend fun getState(operationId: String): ExchangeState? =
        dao.getExchange(operationId)?.state?.let(ExchangeState::valueOf)

    override suspend fun getReceipt(operationId: String): ByteArray? = dao.getExchange(operationId)?.receiptEnvelope

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

class RoomTrustedPublicKeyResolver(private val database: AppDatabase) : TrustedPublicKeyResolver {
    override suspend fun resolve(keyId: String, fingerprint: String): String? =
        database.nfcExchangeDao().getActiveTrustedKey(keyId, fingerprint)?.publicKeyBase64
}
