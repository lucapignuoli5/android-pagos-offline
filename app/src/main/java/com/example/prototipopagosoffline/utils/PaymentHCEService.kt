package com.example.prototipopagosoffline.utils

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.protocol.NfcProtocolEngine
import com.example.prototipopagosoffline.protocol.ProtocolV2
import com.example.prototipopagosoffline.protocol.RoomNfcExchangeStore
import com.example.prototipopagosoffline.protocol.RoomTrustedPublicKeyResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PaymentHCEService : HostApduService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine: NfcProtocolEngine by lazy {
        val database = AppDatabase.getInstance(applicationContext)
        NfcProtocolEngine(RoomNfcExchangeStore(database), RoomTrustedPublicKeyResolver(database))
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray? {
        val command = commandApdu?.copyOf() ?: return ProtocolV2.SW_BAD_DATA
        serviceScope.launch {
            sendResponseApdu(engine.process(command))
        }
        return null
    }

    override fun onDeactivated(reason: Int) {
        engine.resetActivation()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
