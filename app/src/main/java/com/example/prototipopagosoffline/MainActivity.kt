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
import androidx.compose.material.icons.filled.CreditCard
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import com.example.prototipopagosoffline.utils.TokenManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UserRole { CLIENTE, COMERCIO, NONE }
enum class AppScreen { ROLE_SELECTION, HOME, CLIENT, POS, COBRO_QR, PAGO_QR }

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RetrofitClient.init(applicationContext)
        setContent {
            PrototipoPagosOfflineTheme {
                val context = LocalContext.current
                var currentScreen by remember { mutableStateOf(AppScreen.ROLE_SELECTION) }
                var currentRole by remember { mutableStateOf(UserRole.NONE) }
                var isInitialized by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    val tokenManager = TokenManager(context)
                    val savedToken = tokenManager.getToken()

                    if (!savedToken.isNullOrEmpty()) {
                        // Si ya hay sesión guardada, entra directo offline como cliente
                        currentRole = UserRole.CLIENTE
                        currentScreen = AppScreen.HOME
                    }
                    isInitialized = true
                }

                if (!isInitialized) {
                    // Mostrar pantalla en blanco mientras lee la bóveda
                } else {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = MaterialTheme.colorScheme.background
                    ) { innerPadding ->
                        val modifier = Modifier.padding(innerPadding)
                        
                        when (currentScreen) {
                            AppScreen.ROLE_SELECTION -> RoleSelectionScreen(
                                onRoleSelected = { role ->
                                    currentRole = role
                                    currentScreen = AppScreen.HOME
                                },
                                modifier = modifier
                            )
                            AppScreen.HOME -> HomeScreen(
                                role = currentRole,
                                onLogout = {
                                    // 1. Borrar el JWT de la bóveda
                                    val tokenManager = TokenManager(context)
                                    tokenManager.clearToken()

                                    // 2. Resetear el estado y volver a la selección
                                    currentRole = UserRole.NONE
                                    currentScreen = AppScreen.ROLE_SELECTION
                                },
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
}

@Composable
fun HomeScreen(
    role: UserRole,
    onLogout: () -> Unit,
    onClientClick: () -> Unit,
    onPosClick: () -> Unit,
    onCobroQrClick: () -> Unit,
    onPagarQrClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
        // Rediseño de UI específico del Cliente
        if (role == UserRole.CLIENTE) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Cabecera Minimalista
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Hola, Usuario",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Bienvenido de nuevo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    // Mantener el IconButton del perfil que implementamos en el Logout
                    IconButton(onClick = { onLogout() }) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = "Cambiar Rol",
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Visualización de Saldo Estilo Premium
                Text(
                    text = "Saldo Disponible",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    // Formateamos el monto dividiendo por 100.00 para la visualización estándar fintech
                    text = "$${String.format(java.util.Locale.US, "%.2f", PaymentState.currentPaymentAmount / 100.0)}",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-1.5).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(32.dp))
                
                // Tarjeta Virtual (Representación visual)
                Card(
                    modifier = Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp)
                    ) {
                        // Icono de chip/seguridad
                        Icon(
                            imageVector = Icons.Default.CreditCard,
                            contentDescription = "Security Chip",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.align(Alignment.TopStart).size(32.dp)
                        )
                        
                        // Branding Neutro de la Tarjeta
                        Text(
                            text = "Billetera Digital",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.align(Alignment.BottomStart)
                        )
                    }
                }
            }

            // Menú de Acciones Rápidas para Clientes
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CleanActionButton(icon = Icons.Default.Nfc, label = "Pagar NFC", onClick = onClientClick)
                CleanActionButton(icon = Icons.Default.QrCodeScanner, label = "Pagar QR", onClick = onPagarQrClick)
                CleanActionButton(
                    icon = Icons.Default.Refresh, 
                    label = "Cargar", 
                    onClick = {
                        Toast.makeText(context, "Procesando pago externo...", Toast.LENGTH_SHORT).show()
                        scope.launch(Dispatchers.IO) {
                            try {
                                val request = com.example.prototipopagosoffline.network.RecargaRequest(
                                    token_id = "TOKEN-USER-QR",
                                    monto_recarga = 5000.0
                                )
                                val response = RetrofitClient.apiService.recargarSaldo(request)

                                withContext(Dispatchers.Main) {
                                    if (response.isSuccessful && response.body()?.exitoso == true) {
                                        val nuevoSaldo = response.body()?.nuevo_saldo
                                        Toast.makeText(context, "¡Recarga exitosa! Nuevo saldo: $$nuevoSaldo", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Error en la recarga", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Error de conexión", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                )
            }
        } else if (role == UserRole.COMERCIO) {
            // UI específica del Comercio (Caja/Resumen) - Manteniendo el estilo previo adaptado
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Hola, Comercio",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "Bienvenido de nuevo",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = { onLogout() }) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = "Cambiar Rol",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                        Column {
                            Text(
                                text = "Ventas del Día (Caja)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "$0.00",
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp).align(Alignment.BottomEnd),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.3f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    ActionButton(
                        icon = Icons.Default.ArrowUpward,
                        label = "Cobrar NFC",
                        onClick = onPosClick
                    )
                    ActionButton(
                        icon = Icons.Default.QrCode,
                        label = "Cobro QR",
                        onClick = onCobroQrClick
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
                                        com.example.prototipopagosoffline.network.SyncItem(
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

                                    val response = withContext(Dispatchers.IO) {
                                        RetrofitClient.apiService.syncPayments(syncItems)
                                    }

                                    if (response.isSuccessful) {
                                        withContext(Dispatchers.IO) {
                                            val syncedIds = pendingTransactions.map { it.idTransaccion }
                                            db.transactionDao().updateSyncState(syncedIds, SyncState.SYNCED)
                                            val updatedTransactions = db.transactionDao().getAllTransactions().take(10)
                                            withContext(Dispatchers.Main) {
                                                PaymentState.recentTransactions.clear()
                                                PaymentState.recentTransactions.addAll(updatedTransactions)
                                            }
                                        }
                                        Toast.makeText(context, "✅ Sincronización exitosa", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "❌ Error en sincronización", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "❌ Error de red", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
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
fun CleanActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            label, 
            style = MaterialTheme.typography.labelMedium, 
            fontWeight = FontWeight.Medium, 
            color = MaterialTheme.colorScheme.onSurface
        )
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
    var amount by remember { mutableStateOf("") }
    var isReadyToPay by remember { mutableStateOf(false) }

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

@Composable
fun RoleSelectionScreen(onRoleSelected: (UserRole) -> Unit, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Bienvenido a", style = MaterialTheme.typography.titleLarge)
        Text("Billetera Offline", style = MaterialTheme.typography.displayMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(64.dp))

        Button(
            onClick = {
                isLoading = true
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        // Simulamos el inicio de sesión del usuario de prueba
                        val request = com.example.prototipopagosoffline.network.LoginRequest("TOKEN-USER-QR", "123456")
                        val response = com.example.prototipopagosoffline.network.RetrofitClient.apiService.login(request)

                        if (response.isSuccessful && response.body() != null) {
                            val token = response.body()!!.access_token
                            val tokenManager = com.example.prototipopagosoffline.utils.TokenManager(context)
                            tokenManager.saveToken(token)

                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                isLoading = false
                                android.widget.Toast.makeText(context, "Login Exitoso", android.widget.Toast.LENGTH_SHORT).show()
                                onRoleSelected(UserRole.CLIENTE)
                            }
                        } else {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                isLoading = false
                                android.widget.Toast.makeText(context, "Credenciales inválidas", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Exception) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            isLoading = false
                            android.widget.Toast.makeText(context, "Error de red", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            enabled = !isLoading
        ) {
            Text(if (isLoading) "Conectando..." else "Ingresar como Cliente", fontSize = 18.sp)
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onRoleSelected(UserRole.COMERCIO) },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            enabled = !isLoading
        ) {
            Text("Ingresar como Comercio", fontSize = 18.sp)
        }
    }
}
