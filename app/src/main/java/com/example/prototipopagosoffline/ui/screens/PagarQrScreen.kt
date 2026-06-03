package com.example.prototipopagosoffline.ui.screens

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.prototipopagosoffline.PaymentState
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.SyncState
import com.example.prototipopagosoffline.database.TransactionHistory
import com.example.prototipopagosoffline.utils.ReceiptUtils
import com.example.prototipopagosoffline.utils.SecurityUtils
import com.google.gson.Gson
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.PrintWriter
import java.net.ConnectException
import java.net.Socket
import java.util.Locale

@Composable
fun PagarQrScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    
    var showAmountDialog by remember { mutableStateOf(false) }
    var targetIp by remember { mutableStateOf("") }
    var targetPort by remember { mutableStateOf(0) }
    var inputAmount by remember { mutableStateOf("") }
    
    var lastTransaction by remember { mutableStateOf<TransactionHistory?>(null) }

    // Use a custom contract to avoid the "lower 16 bits for requestCode" issue
    val scanLauncher = rememberLauncherForActivityResult<ScanOptions, ScanIntentResult>(
        object : ActivityResultContract<ScanOptions, ScanIntentResult>() {
            override fun createIntent(context: Context, input: ScanOptions): Intent {
                return input.createScanIntent(context)
            }
            override fun parseResult(resultCode: Int, intent: Intent?): ScanIntentResult {
                return ScanContract().parseResult(resultCode, intent)
            }
        }
    ) { result ->
        if (result.contents != null) {
            try {
                val json = JSONObject(result.contents)
                targetIp = json.getString("ip")
                targetPort = json.getInt("port")
                showAmountDialog = true
            } catch (e: Exception) {
                Toast.makeText(context, "QR no válido para pago", Toast.LENGTH_SHORT).show()
                Log.e("TCP_CLIENT", "Error al parsear QR", e)
            }
        }
    }

    if (showAmountDialog) {
        AlertDialog(
            onDismissRequest = { showAmountDialog = false },
            title = { Text("Confirmar Pago") },
            text = {
                Column {
                    Text("Ingresar monto a pagar al comercio:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputAmount,
                        onValueChange = { if (it.all { char -> char.isDigit() }) inputAmount = it },
                        label = { Text("Monto") },
                        prefix = { Text("$") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val amountLong = inputAmount.toLongOrNull() ?: 0L
                        if (amountLong > 0) {
                            if (amountLong.toDouble() <= PaymentState.userBalance) {
                                authenticateAndPay(
                                    context = context,
                                    onAuthenticated = {
                                        showAmountDialog = false
                                        sendPayment(targetIp, targetPort, amountLong, context, scope, 
                                            onSuccess = { tx ->
                                                lastTransaction = tx
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                playSuccessSound(context)
                                            },
                                            onBack = onBack
                                        )
                                    },
                                    onFailure = {
                                        Toast.makeText(context, "Pago cancelado o fallido", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            } else {
                                Toast.makeText(context, "❌ Saldo insuficiente", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Ingrese un monto válido", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Confirmar Pago")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAmountDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
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
            text = "Pagar con QR",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (lastTransaction != null) {
            Text(
                text = "✅ ¡Pago Exitoso!",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Monto: $${Math.abs(lastTransaction!!.monto)}",
                style = MaterialTheme.typography.bodyLarge
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = { ReceiptUtils.shareReceipt(context, lastTransaction!!) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text("Compartir Comprobante")
            }
        } else {
            Text(
                text = "Escanea el código QR del comercio para iniciar el pago.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(64.dp))

            Button(
                onClick = {
                    val options = ScanOptions()
                    options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    options.setPrompt("Escanea el QR del comercio")
                    options.setCameraId(0)
                    options.setBeepEnabled(false)
                    options.setBarcodeImageEnabled(true)
                    scanLauncher.launch(options)
                },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Escanear QR del Comercio", fontSize = 18.sp)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Volver al Menú")
        }
    }
}

private fun playSuccessSound(context: android.content.Context) {
    try {
        val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val r = RingtoneManager.getRingtone(context, notification)
        r.play()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun authenticateAndPay(
    context: android.content.Context,
    onAuthenticated: () -> Unit,
    onFailure: () -> Unit
) {
    val activity = context as? FragmentActivity ?: return
    val executor = ContextCompat.getMainExecutor(context)
    val biometricPrompt = BiometricPrompt(activity, executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onAuthenticated()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onFailure()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
            }
        })

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Autorizar pago offline")
        .setSubtitle("Confirme su identidad para proceder con el pago")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        .build()

    biometricPrompt.authenticate(promptInfo)
}

private fun sendPayment(
    ip: String, 
    port: Int, 
    amount: Long,
    context: android.content.Context, 
    scope: kotlinx.coroutines.CoroutineScope,
    onSuccess: (TransactionHistory) -> Unit,
    onBack: () -> Unit
) {
    scope.launch {
        withContext(Dispatchers.IO) {
            try {
                Log.d("TCP_CLIENT", "Intentando conectar a $ip:$port...")
                val socket = Socket(ip, port)
                val out = PrintWriter(socket.getOutputStream(), true)
                
                val txId = "TX-QR-${System.currentTimeMillis()}"
                val timestamp = (System.currentTimeMillis() / 1000).toString()
                val tokenId = "TOKEN-USER-QR"
                val publicKey = SecurityUtils.getPublicKey()
                
                // Asegurar monto absoluto y formato decimal estricto para la firma
                val absoluteAmount = Math.abs(amount)
                val amountFormatted = String.format(Locale.US, "%.1f", absoluteAmount.toDouble())
                
                // Nuevo formato estricto con separadores pipe (|)
                val payloadToSign = "${txId}|${amountFormatted}|${timestamp}"
                
                Log.d("FirmaDebug", "Firmando: $payloadToSign")
                val signatureBase64 = SecurityUtils.signPayload(payloadToSign)
                
                val paymentData = mapOf(
                    "id_transaccion" to txId,
                    "monto" to absoluteAmount,
                    "timestamp" to timestamp,
                    "token_id" to tokenId,
                    "firma" to signatureBase64,
                    "payload_original" to payloadToSign,
                    "clave_publica" to publicKey
                )
                
                val json = Gson().toJson(paymentData)
                out.println(json)
                out.flush()
                
                Log.d("TCP_CLIENT", "Pago enviado exitosamente: $json")
                
                val transaction = TransactionHistory(
                    idTransaccion = txId,
                    monto = absoluteAmount,
                    timestamp = timestamp,
                    comercioId = 1,
                    tokenId = tokenId,
                    firma = signatureBase64,
                    payloadOriginal = payloadToSign,
                    clavePublica = publicKey,
                    isOutgoing = true,
                    estadoSincronizacion = SyncState.PENDING
                )

                withContext(Dispatchers.Main) {
                    PaymentState.addTransaction(transaction)
                    Toast.makeText(context, "✅ Pago enviado de $$absoluteAmount", Toast.LENGTH_LONG).show()
                    onSuccess(transaction)
                }
                
                val db = AppDatabase.getInstance(context)
                db.transactionDao().insertTransaction(transaction)
                
                socket.close()
            } catch (e: ConnectException) {
                Log.e("TCP_CLIENT", "No se pudo conectar: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: Comercio no disponible en la red", Toast.LENGTH_SHORT).show()
                }
            } catch (e: java.net.NoRouteToHostException) {
                Log.e("TCP_CLIENT", "Host inalcanzable (NoRouteToHost): ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "❌ Error: Dispositivo inalcanzable. Verifica que ambos estén en el MISMO Wi-Fi o Hotspot.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("TCP_CLIENT", "Error al enviar pago", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error de red: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
