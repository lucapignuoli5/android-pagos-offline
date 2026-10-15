package com.example.prototipopagosoffline.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AutoSyncMonitor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeNetwork: Network? = null
    private var validated = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            activeNetwork = network
            validated = false
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network != activeNetwork) return
            val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val restored = online && !validated
            validated = online
            if (restored) scope.launch {
                try {
                    SyncManager.enqueueSync(context)
                    Log.i("PaymentSync", "INTERNET_RESTORED_SYNC_QUEUED")
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { Log.w("PaymentSync", "SYNC_QUEUE_UNAVAILABLE") }
            }
        }

        override fun onLost(network: Network) {
            if (network == activeNetwork) {
                activeNetwork = null
                validated = false
            }
        }
    }

    fun start() {
        SyncManager.ensureRecovery(context)
        try {
            context.getSystemService(ConnectivityManager::class.java)
                .registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            Log.w("PaymentSync", "NETWORK_MONITOR_UNAVAILABLE_PERIODIC_RECOVERY_REMAINS")
        }
    }
}
