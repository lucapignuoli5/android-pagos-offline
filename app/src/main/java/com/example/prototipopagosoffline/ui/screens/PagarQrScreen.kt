package com.example.prototipopagosoffline.ui.screens

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.protocol.ClientPaymentExchangeService
import com.example.prototipopagosoffline.protocol.ProtocolV2
import com.example.prototipopagosoffline.protocol.ProtocolV3
import com.example.prototipopagosoffline.protocol.QrPaymentDescriptor
import com.example.prototipopagosoffline.protocol.QrTcpClient
import com.example.prototipopagosoffline.protocol.QrTcpProtocol
import com.example.prototipopagosoffline.protocol.RoomNfcExchangeStore
import com.example.prototipopagosoffline.protocol.RoomTrustedPublicKeyResolver
import com.example.prototipopagosoffline.sync.SyncManager
import com.example.prototipopagosoffline.utils.ConfirmationResult
import com.example.prototipopagosoffline.utils.OfflinePaymentCoordinator
import com.example.prototipopagosoffline.utils.TokenManager
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PagarQrScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val database = remember { AppDatabase.getInstance(context) }
    val store = remember { RoomNfcExchangeStore(database) { TokenManager(context).getWalletId() } }
    val exchangeService = remember {
        ClientPaymentExchangeService(store, RoomTrustedPublicKeyResolver(database, "pos"))
    }
    val coordinator = remember { OfflinePaymentCoordinator(database) }
    val walletId = remember { TokenManager(context).getWalletId() }
    var descriptor by remember { mutableStateOf<QrPaymentDescriptor?>(null) }
    var merchantLabel by remember { mutableStateOf("") }
    var amountCents by remember { mutableStateOf(0L) }
    var showConfirmation by remember { mutableStateOf(false) }
    var completedOperation by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val scanLauncher = rememberLauncherForActivityResult<ScanOptions, ScanIntentResult>(
        object : ActivityResultContract<ScanOptions, ScanIntentResult>() {
            override fun createIntent(context: Context, input: ScanOptions): Intent = input.createScanIntent(context)
            override fun parseResult(resultCode: Int, intent: Intent?): ScanIntentResult =
                ScanContract().parseResult(resultCode, intent)
        }
    ) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val parsed = withContext(Dispatchers.Default) { QrTcpProtocol.decodeDescriptor(contents) }
                withContext(Dispatchers.IO) { exchangeService.acceptRequest(parsed.requestEnvelope) }
                val request = ProtocolV3.parseSignedEnvelope(parsed.requestEnvelope).payload
                descriptor = parsed
                merchantLabel = request["merchant_id"].asString
                amountCents = request["amount_cents"].asLong
                showConfirmation = true
                message = null
            } catch (_: Exception) {
                Toast.makeText(context, "QR de pago inválido o no confiable", Toast.LENGTH_LONG).show()
            }
        }
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("Confirmar pago") },
            text = { Text("Comercio: $merchantLabel\nImporte: $${formatCents(amountCents)}") },
            confirmButton = {
                TextButton(onClick = {
                    val current = descriptor ?: return@TextButton
                    authenticateAndPay(
                        context,
                        onAuthenticated = {
                            showConfirmation = false
                            scope.launch {
                                var reserved = false
                                try {
                                    val currentWallet = walletId ?: error("Billetera no configurada")
                                    val confirmation = withContext(Dispatchers.IO) {
                                        coordinator.confirm(current.operationId, currentWallet)
                                    }
                                    reserved = true
                                    val receipt = when (confirmation) {
                                        is ConfirmationResult.Created -> confirmation.receiptEnvelope
                                        is ConfirmationResult.AlreadyCreated -> confirmation.receiptEnvelope
                                    }
                                    withContext(Dispatchers.IO) {
                                        QrTcpClient(store, exchangeService).deliver(current, receipt)
                                    }
                                    SyncManager.enqueueSync(context)
                                    completedOperation = current.operationId
                                    message = "Acuse verificado y persistido. Conciliación pendiente."
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    playSuccessSound(context)
                                } catch (_: Exception) {
                                    if (reserved) SyncManager.enqueueSync(context)
                                    message = if (reserved) {
                                        "Entrega incierta: el importe sigue reservado hasta resolver la operación."
                                    } else {
                                        "No se pudo reservar el pago."
                                    }
                                }
                            }
                        },
                        onFailure = {
                            Toast.makeText(context, "Pago cancelado", Toast.LENGTH_SHORT).show()
                        }
                    )
                }) { Text("Confirmar") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) { Text("Cancelar") }
            }
        )
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
        }
        Text("Pagar con QR", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(32.dp))
        completedOperation?.let {
            Text("Pago aceptado localmente", style = MaterialTheme.typography.headlineSmall)
            Text("Monto: $${formatCents(amountCents)}")
            Text("Operación: $it", style = MaterialTheme.typography.bodySmall)
        } ?: Text(
            message ?: "Escaneá la solicitud QR firmada del comercio.",
            style = MaterialTheme.typography.bodyLarge
        )
        message?.takeIf { completedOperation != null }?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(48.dp))
        if (completedOperation == null) {
            Button(
                onClick = {
                    val options = ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Apuntá la cámara al código QR")
                        .setBeepEnabled(false)
                        .setBarcodeImageEnabled(false)
                        .setCaptureActivity(CustomScannerActivity::class.java)
                        .setOrientationLocked(true)
                    scanLauncher.launch(options)
                },
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) { Text("Escanear solicitud", fontSize = 18.sp) }
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Volver al menú") }
    }
}

private fun formatCents(amount: Long): String = String.format(
    java.util.Locale.US,
    "%.2f",
    amount / 100.0
)

private fun playSuccessSound(context: Context) {
    runCatching {
        val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        RingtoneManager.getRingtone(context, notification).play()
    }
}

private fun authenticateAndPay(
    context: Context,
    onAuthenticated: () -> Unit,
    onFailure: () -> Unit
) {
    val activity = context as? FragmentActivity ?: return onFailure()
    val executor = ContextCompat.getMainExecutor(context)
    val biometricPrompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onAuthenticated()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onFailure()
            }
        }
    )
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Autorizar pago offline")
        .setSubtitle("Confirmá tu identidad para reservar el importe")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build()
    biometricPrompt.authenticate(promptInfo)
}
