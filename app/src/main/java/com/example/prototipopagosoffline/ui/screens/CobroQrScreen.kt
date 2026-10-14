package com.example.prototipopagosoffline.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.protocol.QrTcpProtocol
import com.example.prototipopagosoffline.protocol.QrTcpServer
import com.example.prototipopagosoffline.sync.SyncManager
import com.example.prototipopagosoffline.utils.MobilePaymentRequest
import com.example.prototipopagosoffline.utils.MobilePosCoordinator
import com.example.prototipopagosoffline.utils.NetworkUtils
import com.example.prototipopagosoffline.utils.TokenManager
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CobroQrScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { AppDatabase.getInstance(context) }
    val coordinator = remember { MobilePosCoordinator(database) }
    val terminalId = remember { TokenManager(context).getTerminalId() }
    val localIp = remember { NetworkUtils.getLocalIpAddress() }
    val port = 8080
    var inputAmount by remember { mutableStateOf("") }
    var request by remember { mutableStateOf<MobilePaymentRequest?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var acceptedOperation by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(request?.operationId) {
        val current = request ?: return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                QrTcpServer(coordinator).serve(port, current.operationId) { operationId ->
                    SyncManager.enqueueSync(context)
                    withContext(Dispatchers.Main) {
                        acceptedOperation = operationId
                        message = "Comprobante y acuse persistidos. Conciliación pendiente."
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = "No se pudo mantener el servidor TCP local."
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
        }
        Text("Cobrar con QR", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        if (request == null) {
            OutlinedTextField(
                value = inputAmount,
                onValueChange = { value ->
                    if (value.length <= 8 && value.all { it.isDigit() || it == ',' || it == '.' }) {
                        inputAmount = value
                    }
                },
                label = { Text("Importe en pesos") },
                prefix = { Text("$") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    scope.launch {
                        try {
                            val host = localIp ?: error("Activá una red local o punto de acceso")
                            val currentTerminal = terminalId ?: error("Terminal no aprovisionado")
                            val amount = QrTcpProtocol.parseAmountCents(inputAmount)
                            val created = withContext(Dispatchers.IO) {
                                coordinator.createRequest(amount, currentTerminal)
                            }
                            val descriptor = QrTcpProtocol.encodeDescriptor(host, port, created.envelope)
                            val bitmap = withContext(Dispatchers.Default) {
                                BarcodeEncoder().encodeBitmap(descriptor, BarcodeFormat.QR_CODE, 700, 700)
                            }
                            request = created
                            qrBitmap = bitmap
                            message = "Solicitud firmada. Esperando comprobante del cliente."
                        } catch (error: Exception) {
                            Toast.makeText(
                                context,
                                error.message ?: "No se pudo crear el cobro",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Crear solicitud")
            }
        } else {
            qrBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Solicitud de cobro QR",
                    modifier = Modifier.size(280.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(message ?: "Esperando comprobante…", style = MaterialTheme.typography.bodyLarge)
            acceptedOperation?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Operación: $it", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Finalizar") }
    }
}
