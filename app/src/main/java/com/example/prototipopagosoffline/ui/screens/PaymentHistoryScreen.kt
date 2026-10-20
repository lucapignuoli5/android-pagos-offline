package com.example.prototipopagosoffline.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.SyncState
import com.example.prototipopagosoffline.sync.SyncManager
import com.example.prototipopagosoffline.utils.TokenManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class PaymentActivity(
    val operationId: String,
    val amountCents: Long,
    val outgoing: Boolean,
    val createdAt: Long?,
    val status: String,
    val counterparty: String?,
    val needsEvidenceRetry: Boolean = false
)

private fun paymentStatus(state: String, hasFinalEvidence: Boolean): String = when (state) {
    "SYNCED" -> if (hasFinalEvidence) "Conciliado" else "Resultado final pendiente de verificar"
    "REJECTED" -> if (hasFinalEvidence) "Rechazado" else "Rechazo pendiente de verificar"
    "CONFLICT" -> "En revisión"
    "LOCALLY_ACCEPTED" -> "Aceptado localmente · conciliación pendiente"
    "RESERVED" -> "Reservado · entrega pendiente"
    "DELIVERY_UNCERTAIN" -> "Entrega por confirmar"
    else -> "Pendiente"
}

/** Observes only the active wallet/terminal; legacy rows without an owner stay excluded. */
@Composable
fun rememberPaymentHistory(isMerchant: Boolean): List<PaymentActivity> {
    val context = LocalContext.current
    val database = remember(context) { AppDatabase.getInstance(context) }
    val session = remember(isMerchant) { TokenManager(context) }
    val userId = session.getUserId()
    val walletId = if (isMerchant) userId?.let { "wallet-$it" } else session.getWalletId()
    val terminalId = if (isMerchant) session.getTerminalId() else null
    var history by remember(isMerchant, userId, walletId, terminalId) {
        mutableStateOf(emptyList<PaymentActivity>())
    }
    LaunchedEffect(isMerchant, userId, walletId, terminalId) {
        if (walletId == null || userId == null) return@LaunchedEffect
        val modern = if (isMerchant) {
            val terminal = terminalId?.let { database.mobileTerminalDao().getById(it) }
                ?.takeIf { it.userId == userId } ?: return@LaunchedEffect
            database.mobilePosOperationDao().observePaymentHistory(terminal.terminalId, terminal.merchantId)
                .map { rows -> rows.map { row ->
                    PaymentActivity(row.operationId, row.amountCents, false, row.createdAtEpochSeconds,
                        paymentStatus(row.state, row.resolutionEnvelope != null), row.walletId,
                        row.resolutionRecoveryStatus == "ATTENTION_REQUIRED")
                } }
        } else {
            database.nfcExchangeDao().observePaymentHistory(walletId).map { rows -> rows.map { row ->
                PaymentActivity(row.operationId, requireNotNull(row.amountCents), true, row.createdAtEpochSeconds,
                    paymentStatus(row.state, row.resolutionEnvelope != null), row.merchantId,
                    row.resolutionRecoveryStatus == "ATTENTION_REQUIRED")
            } }
        }
        combine(modern, database.transactionDao().observeTransactionsForWallet(walletId)) { current, legacy ->
            (current + legacy.map { row ->
                PaymentActivity(row.idTransaccion, row.monto, row.isOutgoing,
                    runCatching { Instant.parse(row.timestamp).epochSecond }.getOrNull(),
                    if (row.estadoSincronizacion == SyncState.SYNCED) "Sincronizado (histórico)"
                    else "Pendiente (histórico)", null)
            }).distinctBy { it.operationId }
                .sortedByDescending { it.createdAt ?: Long.MIN_VALUE }.take(100)
        }.collect { history = it }
    }
    return history
}

@Composable
fun PaymentActivityRow(item: PaymentActivity, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (item.outgoing) "Pago enviado" else "Cobro recibido", fontWeight = FontWeight.SemiBold)
                Text("${if (item.outgoing) "−" else "+"}$${BigDecimal.valueOf(item.amountCents, 2).toPlainString()}",
                    fontWeight = FontWeight.Bold)
            }
            Text(item.status, style = MaterialTheme.typography.bodySmall)
            Text(activityDate(item.createdAt), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun PaymentHistoryScreen(isMerchant: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val history = rememberPaymentHistory(isMerchant)
    var selected by remember { mutableStateOf<PaymentActivity?>(null) }
    val detail = selected?.let { old -> history.firstOrNull { it.operationId == old.operationId } ?: old }
    detail?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(if (item.outgoing) "Detalle del pago" else "Detalle del cobro") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Importe: $${BigDecimal.valueOf(item.amountCents, 2).toPlainString()}")
                Text("Estado: ${item.status}")
                Text(activityDate(item.createdAt))
                Text("Operación: ${item.operationId}")
                item.counterparty?.let { Text("${if (item.outgoing) "Comercio" else "Billetera cliente"}: $it") }
                if (item.needsEvidenceRetry) Text("Falta verificar el comprobante final de esta operación.")
            } },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Cerrar") } },
            dismissButton = { if (item.needsEvidenceRetry) TextButton(onClick = {
                SyncManager.enqueueSync(context, retryEvidenceAttention = true)
                selected = null
            }) { Text("Reintentar verificación") } }
        )
    }
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
            Text("Actividad", style = MaterialTheme.typography.headlineSmall)
        }
        Text("Últimos 100 movimientos guardados en este teléfono.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        if (history.isEmpty()) Text("Todavía no hay movimientos para esta cuenta.")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(history, key = { it.operationId }) { item ->
                PaymentActivityRow(item) { selected = item }
            }
        }
    }
}

private fun activityDate(epoch: Long?): String = epoch?.let {
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(it))
} ?: "Fecha no disponible"
