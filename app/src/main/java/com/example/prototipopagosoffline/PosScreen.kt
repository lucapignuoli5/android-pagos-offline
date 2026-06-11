package com.example.prototipopagosoffline

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.SyncState
import com.example.prototipopagosoffline.database.TransactionHistory
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
fun PosScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val nfcAdapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()
    var readResult by remember { mutableStateOf("Esperando lectura NFC...") }
    var lastTransaction by remember { mutableStateOf<TransactionHistory?>(null) }

    val readerCallback = remember {
        NfcAdapter.ReaderCallback { tag ->
            val result = readPaymentContractFromTag(tag)
            
            mainHandler.post {
                readResult = result
                
                // Parse and persist if it's a valid JSON response
                if (result.startsWith("NFC_PAYLOAD:")) {
                    try {
                        val data = result.substringAfter("NFC_PAYLOAD:")
                        val parts = data.split("|")
                        
                        if (parts.size == 5) {
                            val rawPubKey = parts[0].replace("\n", "").trim()
                            val signature = parts[1].replace("\n", "").trim()
                            val tokenId = parts[2]
                            val montoEnCentavos = parts[3].toLong()
                            val timestamp = parts[4]

                            // Reconstruimos el ID de transacción para no tener que mandarlo por aire
                            val txId = "TX-NFC-$timestamp"

                            // RECONSTRUCCIÓN PERFECTA PARA PYTHON (Chunks de 64 caracteres)
                            val chunkedPubKey = rawPubKey.chunked(64).joinToString("\n")
                            val publicKey = "-----BEGIN PUBLIC KEY-----\n$chunkedPubKey\n-----END PUBLIC KEY-----\n"

                            // El backend espera que el payloadOriginal coincida con lo firmado: txId|montoFormatted|timestamp
                            val amountFormatted = String.format(java.util.Locale.US, "%.1f", montoEnCentavos.toDouble())
                            val payloadOriginal = "$txId|$amountFormatted|$timestamp"

                            val transaction = TransactionHistory(
                                idTransaccion = txId,
                                monto = montoEnCentavos,
                                timestamp = timestamp,
                                comercioId = 1,
                                tokenId = tokenId,
                                firma = signature,
                                payloadOriginal = payloadOriginal,
                                clavePublica = publicKey,
                                isOutgoing = false,
                                estadoSincronizacion = SyncState.PENDING
                            )
                            
                            lastTransaction = transaction
                            PaymentState.addTransaction(transaction)
                            
                            scope.launch(Dispatchers.IO) {
                                val db = AppDatabase.getInstance(context)
                                db.transactionDao().insertTransaction(transaction)
                                com.example.prototipopagosoffline.sync.SyncManager.enqueueSync(context)
                            }
                        } else {
                            Log.e("NFC", "Error: Payload malformado o incompleto")
                        }
                    } catch (e: Exception) {
                        Log.e("NFC", "Error procesando Micro-Payload", e)
                    }
                }
            }
        }
    }

    DisposableEffect(activity, nfcAdapter, readerCallback) {
        if (activity != null && nfcAdapter != null) {
            val readerFlags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

            nfcAdapter.enableReaderMode(activity, readerCallback, readerFlags, Bundle())
        } else {
            readResult = "Este dispositivo no tiene NFC disponible."
        }

        onDispose {
            if (activity != null && nfcAdapter != null) {
                nfcAdapter.disableReaderMode(activity)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (lastTransaction != null) {
            Text(
                text = "¡Cobro exitoso de $${String.format(java.util.Locale.US, "%.2f", lastTransaction!!.monto / 100.0)}!",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "ID: ${lastTransaction!!.idTransaccion}",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                text = "Cobrar Offline",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Esperando tarjeta o celular del cliente...",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = readResult,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Button(
            onClick = onBack,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("Volver al Menú")
        }
    }
}

private fun readPaymentContractFromTag(tag: Tag): String {
    val isoDep = IsoDep.get(tag) ?: return "El tag detectado no soporta IsoDep."

    return try {
        isoDep.use { tech ->
            tech.connect()
            
            Log.d(TAG, "Enviando APDU: ${SELECT_PAYMENT_AID_APDU.toHexString()}")
            val response = tech.transceive(SELECT_PAYMENT_AID_APDU)

            if (!response.endsWith(STATUS_SUCCESS)) {
                val statusWord = response.takeLast(2).toHexString()
                Log.w(TAG, "Respuesta APDU fallo con status: $statusWord")
                return "Respuesta APDU no exitosa: $statusWord"
            }

            val payload = response.copyOfRange(0, response.size - STATUS_SUCCESS.size).toString(Charsets.UTF_8)
            
            if (payload.contains("ERROR|SALDO_INSUFICIENTE")) {
                return "ERROR: El cliente no tiene saldo suficiente."
            }

            val parts = payload.split("|")
            if (parts.size >= 5) {
                return "NFC_PAYLOAD:$payload"
            }
            return "Respuesta inválida: Faltan datos en el Micro-Payload."
        }
    } catch (exception: TagLostException) {
        Log.d(TAG, "Se perdio el tag NFC durante la lectura.", exception)
        "Lectura interrumpida: el telefono del cliente se alejo demasiado rapido."
    } catch (exception: IOException) {
        Log.e(TAG, "Error de E/S durante la lectura NFC.", exception)
        "No se pudo leer el pago NFC. Intenta acercar el telefono nuevamente."
    } catch (exception: IllegalArgumentException) {
        Log.e(TAG, "Respuesta NFC invalida.", exception)
        "La respuesta NFC recibida no tiene un formato valido."
    }
}

private val PAYMENT_AID = "F222222222".hexToByteArray()
private val SELECT_PAYMENT_AID_APDU = byteArrayOf(
    0x00,
    0xA4.toByte(),
    0x04,
    0x00,
    PAYMENT_AID.size.toByte()
) + PAYMENT_AID
private val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00)
private const val TAG = "NFC_READER"

private fun ByteArray.endsWith(suffix: ByteArray): Boolean {
    if (size < suffix.size) {
        return false
    }

    return copyOfRange(size - suffix.size, size).contentEquals(suffix)
}

private fun ByteArray.takeLast(count: Int): ByteArray {
    if (count <= 0) {
        return byteArrayOf()
    }

    return copyOfRange((size - count).coerceAtLeast(0), size)
}

private fun ByteArray.toHexString(): String = joinToString(separator = " ") { byte ->
    "%02X".format(byte)
}

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex string must have an even length." }

    return chunked(2)
        .map { hexPair -> hexPair.toInt(radix = 16).toByte() }
        .toByteArray()
}
