package com.example.prototipopagosoffline.utils

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.protocol.NfcProtocolEngine
import com.example.prototipopagosoffline.protocol.ProtocolV2
import com.example.prototipopagosoffline.protocol.RoomNfcExchangeStore
import com.example.prototipopagosoffline.protocol.RoomTrustedPublicKeyResolver
import com.example.prototipopagosoffline.sync.SyncManager
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
            val response = engine.process(command)
            sendResponseApdu(response)
            val completedAck = command.size >= 5 &&
                (command[1].toInt() and 0xff) == ProtocolV2.INS_PUT_ACK &&
                (command[3].toInt() and 0xff) == ProtocolV2.P2_LAST &&
                response.size >= 2 &&
                response.copyOfRange(response.size - 2, response.size).contentEquals(ProtocolV2.SW_OK)
            if (completedAck) SyncManager.enqueueSync(applicationContext)
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
