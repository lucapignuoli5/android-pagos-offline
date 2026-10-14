package com.example.prototipopagosoffline

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.protocol.ApduTransceiver
import com.example.prototipopagosoffline.protocol.MobileNfcExchangeResult
import com.example.prototipopagosoffline.protocol.MobileNfcReader
import com.example.prototipopagosoffline.protocol.QrTcpProtocol
import com.example.prototipopagosoffline.sync.SyncManager
import com.example.prototipopagosoffline.utils.MobilePaymentRequest
import com.example.prototipopagosoffline.utils.MobilePosCoordinator
import com.example.prototipopagosoffline.utils.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun PosScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val nfcAdapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    val database = remember { AppDatabase.getInstance(context) }
    val coordinator = remember { MobilePosCoordinator(database) }
    val terminalId = remember { TokenManager(context).getTerminalId() }
    val scope = rememberCoroutineScope()
    val busy = remember { AtomicBoolean(false) }
    var amountInput by remember { mutableStateOf("") }
    var request by remember { mutableStateOf<MobilePaymentRequest?>(null) }
    var message by remember { mutableStateOf("Ingresá el importe para crear la solicitud firmada.") }
    var acceptedOperation by remember { mutableStateOf<String?>(null) }
    val currentRequest = rememberUpdatedState(request)

    val readerCallback = remember(coordinator) {
        NfcAdapter.ReaderCallback { tag ->
            val activeRequest = currentRequest.value ?: return@ReaderCallback
            if (!busy.compareAndSet(false, true)) return@ReaderCallback
            scope.launch(Dispatchers.IO) {
                val outcome = runCatching {
                    val isoDep = IsoDep.get(tag) ?: error("El dispositivo detectado no soporta ISO-DEP")
                    isoDep.use { tech ->
                        tech.timeout = 5_000
                        tech.connect()
                        MobileNfcReader(
                            ApduTransceiver { command -> tech.transceive(command) },
                            coordinator::acceptReceipt
                        ).exchange(activeRequest.operationId, activeRequest.envelope)
                    }
                }
                withContext(Dispatchers.Main) {
                    outcome.onSuccess { result ->
                        when (result) {
                            is MobileNfcExchangeResult.WaitingForConfirmation -> {
                                message = "Solicitud entregada. Confirmá en el teléfono cliente y volvé a acercarlo."
                            }
                            is MobileNfcExchangeResult.Accepted -> {
                                acceptedOperation = result.operationId
                                message = "Comprobante y acuse persistidos. Conciliación pendiente."
                                SyncManager.enqueueSync(context)
                            }
                        }
                    }.onFailure { error ->
                        message = when (error) {
                            is TagLostException -> "Acercamiento interrumpido. Volvé a acercar el teléfono."
                            is IOException -> "No se pudo completar el intercambio NFC. Intentá nuevamente."
                            else -> error.message ?: "Respuesta NFC inválida."
                        }
                    }
                    busy.set(false)
                }
            }
        }
    }

    DisposableEffect(activity, nfcAdapter, readerCallback) {
        if (activity != null && nfcAdapter != null) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            nfcAdapter.enableReaderMode(activity, readerCallback, flags, Bundle())
        } else {
            message = "Este dispositivo no tiene NFC disponible."
        }
        onDispose {
            if (activity != null && nfcAdapter != null) nfcAdapter.disableReaderMode(activity)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Cobrar por NFC móvil", style = MaterialTheme.typography.headlineMedium)
        if (request == null) {
            OutlinedTextField(
                value = amountInput,
                onValueChange = { value ->
                    if (value.length <= 8 && value.all { it.isDigit() || it == ',' || it == '.' }) {
                        amountInput = value
                    }
                },
                label = { Text("Importe en pesos") },
                prefix = { Text("$") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    scope.launch {
                        try {
                            val currentTerminal = terminalId ?: error("Terminal no aprovisionado")
                            val amount = QrTcpProtocol.parseAmountCents(amountInput)
                            request = withContext(Dispatchers.IO) {
                                coordinator.createRequest(amount, currentTerminal)
                            }
                            message = "Solicitud lista. Acercá el teléfono cliente."
                        } catch (error: Exception) {
                            message = error.message ?: "No se pudo crear la solicitud."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Crear solicitud") }
        }

        Text(message, style = MaterialTheme.typography.bodyLarge)
        acceptedOperation?.let { operationId ->
            Text(
                "Cobro aceptado localmente",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text("Operación: $operationId", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Volver al menú") }
    }
}
