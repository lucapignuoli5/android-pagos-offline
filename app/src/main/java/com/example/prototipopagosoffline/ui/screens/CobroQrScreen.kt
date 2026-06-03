package com.example.prototipopagosoffline.ui.screens

import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.prototipopagosoffline.PaymentState
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.SyncState
import com.example.prototipopagosoffline.database.TransactionHistory
import com.example.prototipopagosoffline.utils.NetworkUtils
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

@Composable
fun CobroQrScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val localIp = remember { NetworkUtils.getLocalIpAddress() }
    val port = 8080
    
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var receivedPayment by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (localIp == null) {
            Toast.makeText(context, "Debes encender el Punto de Acceso Wi-Fi", Toast.LENGTH_LONG).show()
            return@LaunchedEffect
        }

        val qrContent = "{\"ip\": \"$localIp\", \"port\": $port}"

        // Generate QR
        try {
            val barcodeEncoder = BarcodeEncoder()
            qrBitmap = barcodeEncoder.encodeBitmap(qrContent, BarcodeFormat.QR_CODE, 600, 600)
        } catch (e: Exception) {
            Log.e("QR_GEN", "Error generando QR", e)
        }

        // Start TCP Server
        withContext(Dispatchers.IO) {
            try {
                // Configurar el ServerSocket con un timeout para poder re-intentar o cerrar limpiamente si es necesario
                val serverSocket = ServerSocket(port)
                // Opcional: serverSocket.soTimeout = 30000 
                
                Log.d("TCP_SERVER", "Servidor iniciado en el puerto $port. Esperando pago...")
                
                while (true) {
                    val clientSocket = serverSocket.accept()
                    // Desactivar el algoritmo de Nagle para enviar paquetes pequeños inmediatamente
                    clientSocket.tcpNoDelay = true

                    Log.d("TCP_SERVER", "Cliente conectado: ${clientSocket.inetAddress.hostAddress}")
                    
                    val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                    val paymentData = reader.readLine()
                    
                    if (paymentData != null) {
                        Log.d("TCP_SERVER", "Pago recibido: $paymentData")
                        
                        try {
                            val txJson = Gson().fromJson(paymentData, Map::class.java)
                            val monto = (txJson["monto"] as? Number)?.toLong() ?: 0L
                            val id = txJson["id_transaccion"] as? String ?: "QR-${System.currentTimeMillis()}"
                            
                            val transaction = TransactionHistory(
                                idTransaccion = id,
                                monto = Math.abs(monto), // Siempre absoluto
                                timestamp = (txJson["timestamp"] as? String) ?: (System.currentTimeMillis() / 1000).toString(),
                                comercioId = 1,
                                tokenId = txJson["token_id"] as? String ?: "UNKNOWN",
                                firma = txJson["firma"] as? String ?: "",
                                payloadOriginal = txJson["payload_original"] as? String ?: "",
                                clavePublica = txJson["clave_publica"] as? String ?: "",
                                isOutgoing = false,
                                estadoSincronizacion = SyncState.PENDING
                            )

                            withContext(Dispatchers.Main) {
                                receivedPayment = "Monto: $$monto\nID: $id"
                                PaymentState.addTransaction(transaction)
                            }

                            // Persist to DB
                            val db = AppDatabase.getInstance(context)
                            db.transactionDao().insertTransaction(transaction)

                        } catch (e: Exception) {
                            Log.e("TCP_SERVER", "Error al procesar JSON", e)
                        }
                    }
                    
                    clientSocket.close()
                }
            } catch (e: Exception) {
                Log.e("TCP_SERVER", "Error en el servidor TCP", e)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.Start)
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
        }

        Text(
            text = "Cobrar con QR",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Tu IP: ${localIp ?: "No detectada"}",
            style = MaterialTheme.typography.bodyLarge,
            color = if (localIp != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(32.dp))

        qrBitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Código QR de Cobro",
                modifier = Modifier.size(250.dp)
            )
        } ?: Text("Generando QR...")

        Spacer(modifier = Modifier.height(32.dp))

        if (receivedPayment != null) {
            Text(
                text = "✅ ¡Pago Recibido!",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = receivedPayment!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            Text(
                text = "Esperando que el cliente escanee...",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Finalizar")
        }
    }
}
