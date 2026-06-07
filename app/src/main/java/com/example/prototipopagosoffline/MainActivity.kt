package com.example.prototipopagosoffline

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.SyncState
import com.example.prototipopagosoffline.database.TransactionHistory
import com.example.prototipopagosoffline.network.RetrofitClient
import com.example.prototipopagosoffline.network.SyncItem
import com.example.prototipopagosoffline.ui.screens.CobroQrScreen
import com.example.prototipopagosoffline.ui.screens.PagarQrScreen
import com.example.prototipopagosoffline.ui.theme.PrototipoPagosOfflineTheme
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppScreen { HOME, CLIENT, POS, COBRO_QR, PAGO_QR }

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PrototipoPagosOfflineTheme {
                var currentScreen by remember { mutableStateOf(AppScreen.HOME) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    val modifier = Modifier.padding(innerPadding)
                    
                    when (currentScreen) {
                        AppScreen.HOME -> HomeScreen(
                            onClientClick = { currentScreen = AppScreen.CLIENT },
                            onPosClick = { currentScreen = AppScreen.POS },
                            onCobroQrClick = { currentScreen = AppScreen.COBRO_QR },
                            onPagarQrClick = { currentScreen = AppScreen.PAGO_QR },
                            modifier = modifier
                        )
                        AppScreen.CLIENT -> ClientScreen(
                            onBack = { currentScreen = AppScreen.HOME },
                            onPagoQrClick = { currentScreen = AppScreen.PAGO_QR },
                            modifier = modifier
                        )
                        AppScreen.POS -> PosScreen(
                            onBack = { currentScreen = AppScreen.HOME },
                            modifier = modifier
                        )
                        AppScreen.COBRO_QR -> CobroQrScreen(
                            onBack = { currentScreen = AppScreen.HOME },
                            modifier = modifier
                        )
                        AppScreen.PAGO_QR -> PagarQrScreen(
                            onBack = { currentScreen = AppScreen.HOME },
                            modifier = modifier
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    onClientClick: () -> Unit,
    onPosClick: () -> Unit,
    onCobroQrClick: () -> Unit,
    onPagarQrClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isBalanceVisible by remember { mutableStateOf(true) }
    var transactions by remember { mutableStateOf(emptyList<TransactionHistory>()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val dbTransactions = db.transactionDao().getAllTransactions().take(10)
            withContext(Dispatchers.Main) {
                if (PaymentState.recentTransactions.isEmpty()) {
                    PaymentState.recentTransactions.addAll(dbTransactions)
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Hola, Usuario",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "Bienvenido de nuevo",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = "Perfil",
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Balance Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.1f)
                            )
                        )
                    )
                    .padding(24.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Saldo disponible",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = if (isBalanceVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle Balance",
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { isBalanceVisible = !isBalanceVisible },
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isBalanceVisible) "$${String.format("%.2f", PaymentState.userBalance)}" else "••••••",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Text(
                    text = "Límite: $50.000",
                    modifier = Modifier.align(Alignment.BottomStart),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                
                Icon(
                    imageVector = Icons.Default.Wallet,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.BottomEnd),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Action Buttons
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ActionButton(
                    icon = Icons.Default.Nfc,
                    label = "Pagar NFC",
                    onClick = onClientClick
                )
                ActionButton(
                    icon = Icons.Default.Refresh,
                    label = "Cargar",
                    onClick = { Toast.makeText(context, "Próximamente", Toast.LENGTH_SHORT).show() }
                )
                ActionButton(
                    icon = Icons.Default.Sync,
                    label = "Sincronizar",
                    onClick = {
                        Toast.makeText(context, "Iniciando sincronización...", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            try {
                                val db = AppDatabase.getInstance(context)
                                val pendingTransactions = withContext(Dispatchers.IO) {
                                    db.transactionDao().getTransactionsBySyncState(SyncState.PENDING)
                                }

                                if (pendingTransactions.isEmpty()) {
                                    Toast.makeText(context, "No hay transacciones pendientes", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }

                                val syncItems = pendingTransactions.map { transaction ->
                                    SyncItem(
                                        id_transaccion = transaction.idTransaccion,
                                        monto = transaction.monto.toInt(),
                                        timestamp = transaction.timestamp,
                                        comercio_id = transaction.comercioId,
                                        token_id = transaction.tokenId,
                                        firma = transaction.firma,
                                        payload_original = transaction.payloadOriginal,
                                        clave_publica = transaction.clavePublica
                                    )
                                }

                                val jsonPayload = Gson().toJson(syncItems)
                                Log.d("SYNC_DEBUG", "Enviando a backend. Payload: $jsonPayload")

                                val response = withContext(Dispatchers.IO) {
                                    RetrofitClient.apiService.syncPayments(syncItems)
                                }

                                if (response.isSuccessful) {
                                    Log.d("SYNC_DEBUG", "Respuesta exitosa: ${response.code()}")
                                    withContext(Dispatchers.IO) {
                                        val syncedIds = pendingTransactions.map { it.idTransaccion }
                                        db.transactionDao().updateSyncState(syncedIds, SyncState.SYNCED)
                                        
                                        // Update PaymentState to reflect sync status if needed
                                        // (PaymentState currently doesn't track sync status in UI, 
                                        // but we can refresh the list from DB to be sure)
                                        val updatedTransactions = db.transactionDao().getAllTransactions().take(10)
                                        withContext(Dispatchers.Main) {
                                            PaymentState.recentTransactions.clear()
                                            PaymentState.recentTransactions.addAll(updatedTransactions)
                                        }
                                    }
                                    Toast.makeText(context, "✅ Sincronización exitosa", Toast.LENGTH_SHORT).show()
                                } else {
                                    val errorBody = response.errorBody()?.string() ?: "Sin cuerpo de error"
                                    Log.e("SYNC_ERROR", "Error HTTP ${response.code()}: $errorBody")
                                    Toast.makeText(context, "❌ Error ${response.code()}: $errorBody", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                Log.e("SYNC_ERROR", "Excepción durante la sincronización", e)
                                Toast.makeText(context, "❌ Error de red: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ActionButton(
                    icon = Icons.Default.ArrowUpward,
                    label = "Cobrar",
                    onClick = onPosClick
                )
                ActionButton(
                    icon = Icons.Default.QrCode,
                    label = "Cobro QR",
                    onClick = onCobroQrClick
                )
                ActionButton(
                    icon = Icons.Default.QrCodeScanner,
                    label = "Pagar QR",
                    onClick = onPagarQrClick
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Activity List
        Text(
            text = "Actividad Reciente",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        if (PaymentState.recentTransactions.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                Text("No hay transacciones recientes", color = MaterialTheme.colorScheme.secondary)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(PaymentState.recentTransactions) { tx ->
                    ActivityItem(tx)
                }
            }
        }
    }
}

@Composable
fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun ActivityItem(transaction: TransactionHistory) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (transaction.isOutgoing) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = if (transaction.isOutgoing) Color.Red else Color.Green,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (transaction.isOutgoing) "Pago Enviado" else "Pago Recibido",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "ID: ${transaction.idTransaccion}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        Text(
            text = "${if (transaction.isOutgoing) "-" else "+"}$${transaction.monto}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = if (transaction.isOutgoing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun ClientScreen(
    onBack: () -> Unit,
    onPagoQrClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var amount by remember { mutableStateOf("") }
    var isReadyToPay by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(isReadyToPay) {
        if (isReadyToPay) {
            // Mientras el HCE no lo ponga en 0, esperamos
            while (PaymentState.currentPaymentAmount > 0L) {
                kotlinx.coroutines.delay(500)
            }
            // Si sale del loop, el pago fue exitoso
            isReadyToPay = false
            Toast.makeText(context, "✅ ¡Pago NFC enviado!", Toast.LENGTH_LONG).show()
            onBack() // Vuelve al menú principal
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
            }
            Text(
                text = "Ingresar Monto",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (!isReadyToPay) {
            Text(
                text = "Saldo disponible: $10.000",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = if (amount.isEmpty()) "$0" else "$$amount",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.weight(1f))

            CustomNumberPad(
                onDigitClick = { if (amount.length < 8) amount += it },
                onDeleteClick = { if (amount.isNotEmpty()) amount = amount.dropLast(1) }
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    val amountLong = amount.toLongOrNull() ?: 0L
                    if (amountLong > 0) {
                        PaymentState.currentPaymentAmount = amountLong
                        isReadyToPay = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = amount.isNotEmpty() && amount.toLong() > 0
            ) {
                Text("Listo para pagar", fontSize = 18.sp)
            }
        } else {
            Spacer(modifier = Modifier.height(64.dp))
            Icon(
                imageVector = Icons.Default.Nfc,
                contentDescription = null,
                modifier = Modifier.size(120.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Confirmado: $$amount",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Acerca el teléfono al comercio",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            Button(
                onClick = { isReadyToPay = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cambiar monto")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onPagoQrClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Pagar con QR")
            }
        }
    }
}

@Composable
fun CustomNumberPad(
    onDigitClick: (String) -> Unit,
    onDeleteClick: () -> Unit
) {
    val buttons = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "delete")
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        buttons.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                row.forEach { char ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (char.isEmpty()) Color.Transparent else MaterialTheme.colorScheme.surface)
                            .clickable(enabled = char.isNotEmpty()) {
                                if (char == "delete") onDeleteClick() else onDigitClick(char)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (char == "delete") {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Borrar")
                        } else {
                            Text(
                                text = char,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
