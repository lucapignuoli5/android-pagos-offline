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
import com.example.prototipopagosoffline.database.TransactionHistory
import com.example.prototipopagosoffline.network.RetrofitClient
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
    override fun onStart() {
        super.onStart()
        com.example.prototipopagosoffline.sync.SyncManager.enqueueSync(this)
    }
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
                    val savedWalletId = tokenManager.getWalletId()
                    val savedTerminalId = tokenManager.getTerminalId()
                    val savedUserId = tokenManager.getUserId()
                    val savedRole = tokenManager.getRole()
                    val database = AppDatabase.getInstance(context)

                    val hasLocalTerminal = if (savedTerminalId != null && savedUserId != null) {
                        database.mobileTerminalDao().getById(savedTerminalId)?.userId == savedUserId
                    } else false
                    if (!savedToken.isNullOrEmpty() && savedRole == "cliente"
                        && !savedWalletId.isNullOrBlank() && savedUserId != null
                    ) {
                        com.example.prototipopagosoffline.sync.SyncManager.enqueueSync(context)
                        currentRole = UserRole.CLIENTE
                        currentScreen = AppScreen.HOME
                    } else if (!savedToken.isNullOrEmpty() && savedRole == "comercio" && hasLocalTerminal) {
                        com.example.prototipopagosoffline.sync.SyncManager.enqueueSync(context)
                        currentRole = UserRole.COMERCIO
                        currentScreen = AppScreen.HOME
                    } else if (
                        !savedToken.isNullOrEmpty() || !savedWalletId.isNullOrEmpty() ||
                        !savedTerminalId.isNullOrEmpty() || savedRole != null
                    ) {
                        tokenManager.clearToken()
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
    val walletId = remember(role) {
        val session = TokenManager(context)
        if (role == UserRole.COMERCIO) session.getUserId()?.let { "wallet-$it" }
        else session.getWalletId()
    }
    var confirmedBalanceCents by remember { mutableStateOf<Long?>(null) }
    var reservedBalanceCents by remember { mutableStateOf(0L) }
    var syncPauseReason by remember { mutableStateOf<String?>(null) }
    var preparingAuthorization by remember { mutableStateOf(false) }

    LaunchedEffect(role, walletId) {
        TokenManager(context).observeSyncPauseReason().collect { syncPauseReason = it }
    }

    LaunchedEffect(role, walletId) {
        if (role == UserRole.COMERCIO) withContext(Dispatchers.IO) {
            val session = TokenManager(context)
            session.getTerminalId()?.let { terminal ->
                try {
                    com.example.prototipopagosoffline.sync.MerchantBalanceRepository(
                        AppDatabase.getInstance(context), RetrofitClient.apiService, session
                    ).refresh(terminal)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Offline: retain the last confirmed value stored for this account.
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val dbTransactions = walletId
                ?.let { db.transactionDao().getTransactionsForWallet(it).take(10) }
                .orEmpty()
            val v3Transactions = walletId
                ?.let { id -> db.nfcExchangeDao().getHistoryForWallet(id, 10) }
                .orEmpty()
                .filter { it.receiptEnvelope != null && it.amountCents != null }
                .map { exchange ->
                    com.example.prototipopagosoffline.database.TransactionHistory(
                        idTransaccion = exchange.operationId,
                        monto = requireNotNull(exchange.amountCents),
                        timestamp = java.time.Instant.ofEpochSecond(exchange.createdAtEpochSeconds).toString(),
                        comercioId = 0,
                        tokenId = requireNotNull(exchange.walletId),
                        firma = "",
                        payloadOriginal = "",
                        clavePublica = "",
                        isOutgoing = true,
                        walletId = exchange.walletId,
                        estadoSincronizacion = if (exchange.state == "SYNCED") {
                            com.example.prototipopagosoffline.database.SyncState.SYNCED
                        } else {
                            com.example.prototipopagosoffline.database.SyncState.PENDING
                        }
                    )
                }
            val recent = (v3Transactions + dbTransactions)
                .distinctBy { it.idTransaccion }
                .sortedByDescending { it.timestamp }
                .take(10)
            withContext(Dispatchers.Main) {
                if (PaymentState.recentTransactions.isEmpty()) {
                    PaymentState.recentTransactions.addAll(recent)
                }
            }
        }
    }

    LaunchedEffect(walletId) {
        if (walletId != null) {
            AppDatabase.getInstance(context).walletDao().observeWallet(walletId).collect { wallet ->
                confirmedBalanceCents = wallet?.confirmedBalanceCents
                reservedBalanceCents = wallet?.reservedBalanceCents ?: 0L
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        if (syncPauseReason != null) {
            Text(if (syncPauseReason == "REAUTH_REQUIRED")
                "Sincronización pausada por falta de acceso. Los pagos pendientes se conservan."
            else "La sincronización requiere revisar la configuración o evidencia. Los pagos pendientes se conservan.")
            if (syncPauseReason == "REAUTH_REQUIRED") {
                Button(onClick = onLogout) { Text("Volver a ingresar") }
            }
        }
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
                    // Mostramos el saldo que se actualiza dinámicamente
                    text = confirmedBalanceCents?.let {
                        "$${String.format(java.util.Locale.US, "%.2f", (it - reservedBalanceCents) / 100.0)}"
                    } ?: "Sin saldo local",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-1.5).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(32.dp))
                if (reservedBalanceCents > 0) {
                    Text(
                        text = "Reservado pendiente: $${String.format(java.util.Locale.US, "%.2f", reservedBalanceCents / 100.0)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                
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
                CleanActionButton(icon = Icons.Default.Sync, label = "Actualizar", onClick = {
                    com.example.prototipopagosoffline.sync.SyncManager.enqueueSync(context)
                    scope.launch(Dispatchers.IO) {
                        try {
                            val currentWalletId = walletId ?: error("Billetera no configurada")
                            val response = RetrofitClient.apiService.getWalletBalance(currentWalletId)
                            withContext(Dispatchers.Main) {
                                if (response.isSuccessful && response.body() != null) {
                                    Toast.makeText(context, "Saldo actualizado", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Error al obtener saldo", Toast.LENGTH_SHORT).show()
                                }
                            }
                            response.body()?.let { balance ->
                                val db = AppDatabase.getInstance(context)
                                db.walletDao().insertWallet(
                                    com.example.prototipopagosoffline.database.OfflineWallet(
                                        id = currentWalletId,
                                        confirmedBalanceCents = balance.confirmed_balance_cents,
                                        reservedBalanceCents = 0,
                                        currency = balance.currency
                                    )
                                )
                                db.walletDao().updateConfirmedBalance(
                                    currentWalletId,
                                    balance.confirmed_balance_cents,
                                    balance.currency
                                )
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Error de red: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })
                CleanActionButton(icon = Icons.Default.Nfc, label = "Pagar NFC", onClick = onClientClick)
                CleanActionButton(icon = Icons.Default.QrCodeScanner, label = "Pagar QR", onClick = onPagarQrClick)
                CleanActionButton(
                    icon = Icons.Default.Wallet,
                    label = if (preparingAuthorization) "Preparando" else "Cupo",
                    onClick = {
                        if (!preparingAuthorization) {
                            preparingAuthorization = true
                            scope.launch(Dispatchers.IO) {
                                try {
                                val session = TokenManager(context)
                                val token = session.getToken() ?: error("Volvé a ingresar para renovar el cupo")
                                val currentWallet = session.getWalletId() ?: error("Billetera no configurada")
                                val userId = session.getUserId() ?: error("Sesión incompleta; volvé a ingresar")
                                val login = com.example.prototipopagosoffline.network.LoginResponse(
                                    access_token = token,
                                    token_type = "bearer",
                                    user_id = userId,
                                    wallet_id = currentWallet
                                )
                                com.example.prototipopagosoffline.utils.OfflineProvisioner(
                                    AppDatabase.getInstance(context),
                                    RetrofitClient.authenticatedApi(token),
                                    requestStore = session
                                ).renew(login)
                                    com.example.prototipopagosoffline.sync.SyncManager.enqueueSync(context)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Cupo offline preparado", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, error.message ?: "No se pudo preparar el cupo", Toast.LENGTH_LONG).show()
                                    }
                                } finally {
                                    withContext(Dispatchers.Main) { preparingAuthorization = false }
                                }
                            }
                        }
                    }
                )
                CleanActionButton(
                    icon = Icons.Default.Refresh,
                    label = "Cargar",
                    onClick = {
                        run {
                            Toast.makeText(context, "Recarga simulada de $1.000", Toast.LENGTH_SHORT).show()
                            scope.launch(Dispatchers.IO) {
                                try {
                                    com.example.prototipopagosoffline.utils.SimulatedTopupService(
                                        context, AppDatabase.getInstance(context), RetrofitClient.apiService
                                    ).addDemoCredit()
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Recarga simulada acreditada", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (_: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Recarga pendiente: reintente con Internet", Toast.LENGTH_SHORT).show()
                                    }
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
                                text = "Saldo confirmado del comercio",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = confirmedBalanceCents?.let {
                                    "$${java.math.BigDecimal.valueOf(it, 2).toPlainString()}"
                                } ?: "Sin saldo confirmado local",
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
                            com.example.prototipopagosoffline.sync.SyncManager.enqueueSync(context)
                            Toast.makeText(context, "Sincronización programada", Toast.LENGTH_SHORT).show()
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
            text = "${if (transaction.isOutgoing) "-" else "+"}$${String.format(java.util.Locale.US, "%.2f", transaction.monto / 100.0)}",
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
    val database = remember { AppDatabase.getInstance(context) }
    val coordinator = remember { com.example.prototipopagosoffline.utils.OfflinePaymentCoordinator(database) }
    val walletId = remember { TokenManager(context).getWalletId() }
    var pending by remember { mutableStateOf<com.example.prototipopagosoffline.database.NfcExchange?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val nfcAdapter = android.nfc.NfcAdapter.getDefaultAdapter(context)
    val cardEmulation = nfcAdapter?.let { android.nfc.cardemulation.CardEmulation.getInstance(it) }
    val componentName = android.content.ComponentName(context, com.example.prototipopagosoffline.utils.PaymentHCEService::class.java)

    androidx.compose.runtime.DisposableEffect(Unit) {
        if (nfcAdapter != null && nfcAdapter.isEnabled) {
            cardEmulation?.setPreferredService(context as android.app.Activity, componentName)
        }
        onDispose {
            if (nfcAdapter != null && nfcAdapter.isEnabled) {
                cardEmulation?.unsetPreferredService(context as android.app.Activity)
            }
        }
    }

    LaunchedEffect(Unit) {
        walletId?.let { account ->
            database.nfcExchangeDao().observeOldestPendingRequest(account).collect { pending = it }
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
                text = "Solicitud de pago",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Spacer(modifier = Modifier.height(32.dp))
        if (pending == null) {
            Text("Acercá el teléfono al POS para recibir una solicitud.", style = MaterialTheme.typography.bodyLarge)
        } else {
            val request = runCatching {
                com.example.prototipopagosoffline.protocol.ProtocolV3.parseSignedEnvelope(pending!!.requestEnvelope).payload
            }.getOrElse {
                runCatching { com.example.prototipopagosoffline.protocol.ProtocolV2.parseSignedEnvelope(pending!!.requestEnvelope).payload }.getOrNull()
            }
            Text("Comercio: ${request?.get("merchant_id")?.asString ?: "desconocido"}", style = MaterialTheme.typography.titleMedium)
            Text("Importe: $${String.format(java.util.Locale.US, "%.2f", (request?.get("amount_cents")?.asLong ?: 0L) / 100.0)}", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = {
                scope.launch {
                    try {
                        val currentWalletId = walletId ?: error("Billetera no configurada")
                        require(database.walletDao().getWalletById(currentWalletId) != null) {
                            "Billetera no configurada"
                        }
                        coordinator.confirm(pending!!.operationId, currentWalletId)
                        com.example.prototipopagosoffline.sync.SyncManager.enqueueSync(context)
                        message = "Pago reservado. Acercá nuevamente el teléfono para entregar el comprobante."
                    } catch (e: Exception) { message = e.message ?: "No se pudo confirmar" }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Confirmar pago") }
        }
        message?.let { Spacer(modifier = Modifier.height(16.dp)); Text(it, color = MaterialTheme.colorScheme.secondary) }
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = onPagoQrClick, modifier = Modifier.fillMaxWidth()) { Text("Pagar con QR") }
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
                        val response = RetrofitClient.apiService.deviceLogin(
                            TokenManager(context).demoIdentity("cliente"))
                        if (response.isSuccessful && response.body() != null) {
                            val login = response.body()!!
                            val tokenManager = com.example.prototipopagosoffline.utils.TokenManager(context)
                            check(tokenManager.saveSession(login.access_token, login.wallet_id, login.user_id)) {
                                "No se pudo guardar la sesion"
                            }
                            var provisioningError: String? = null
                            try {
                                com.example.prototipopagosoffline.utils.OfflineProvisioner(
                                    AppDatabase.getInstance(context),
                                    com.example.prototipopagosoffline.network.RetrofitClient.authenticatedApi(login.access_token),
                                    requestStore = tokenManager
                                ).provision(login)
                            } catch (error: Exception) {
                                provisioningError = error.message
                            }
                            com.example.prototipopagosoffline.sync.SyncManager.enqueueSync(context)

                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                isLoading = false
                                val message = if (provisioningError == null) "Login exitoso"
                                else "Sesión iniciada; el cupo queda pendiente: $provisioningError"
                                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
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
            onClick = {
                isLoading = true
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val response = RetrofitClient.apiService.deviceLogin(
                            TokenManager(context).demoIdentity("comercio"))
                        if (!response.isSuccessful || response.body() == null) {
                            error("Credenciales de comercio invalidas")
                        }
                        val login = response.body()!!
                        val tokenManager = TokenManager(context)
                        tokenManager.saveToken(login.access_token)
                        try {
                            val terminal = com.example.prototipopagosoffline.utils.MerchantProvisioner(
                                AppDatabase.getInstance(context),
                                com.example.prototipopagosoffline.network.RetrofitClient.apiService
                            ).provision(login)
                            check(
                                tokenManager.saveMerchantSession(
                                    login.access_token,
                                    terminal.terminalId,
                                    login.user_id
                                )
                            ) { "No se pudo guardar la sesion de comercio" }
                        } catch (error: Exception) {
                            tokenManager.clearToken()
                            throw error
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            isLoading = false
                            android.widget.Toast.makeText(
                                context,
                                "Comercio aprovisionado",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            com.example.prototipopagosoffline.sync.SyncManager.enqueueSync(context)
                            onRoleSelected(UserRole.COMERCIO)
                        }
                    } catch (_: Exception) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            isLoading = false
                            android.widget.Toast.makeText(
                                context,
                                "No se pudo iniciar el comercio",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            enabled = !isLoading
        ) {
            Text("Ingresar como Comercio", fontSize = 18.sp)
        }
    }
}
