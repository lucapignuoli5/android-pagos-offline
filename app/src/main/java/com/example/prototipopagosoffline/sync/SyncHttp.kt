package com.example.prototipopagosoffline.sync

import java.io.IOException
import retrofit2.Response

class SyncPauseRequired(val reason: String) : Exception()

// Network loss is retryable; cancellation and access failures must reach the worker.
internal suspend fun <T> syncHttp(call: suspend () -> Response<T>): Response<T>? = try {
    call().also {
        when (it.code()) {
            401, 403 -> throw SyncPauseRequired("REAUTH_REQUIRED")
            400, 404, 405, 409, 410, 413, 415, 422 -> throw SyncPauseRequired("ATTENTION_REQUIRED")
        }
    }
} catch (_: IOException) {
    null
}
