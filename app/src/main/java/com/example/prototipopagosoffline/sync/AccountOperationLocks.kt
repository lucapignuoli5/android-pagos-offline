package com.example.prototipopagosoffline.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object AccountOperationLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(role: String, accountId: String, action: suspend () -> T): T {
        require(role in setOf("cliente", "comercio") && accountId.isNotBlank())
        return locks.getOrPut("$role:$accountId") { Mutex() }.withLock { action() }
    }
}
