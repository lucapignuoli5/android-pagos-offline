package com.example.prototipopagosoffline.sync

import com.example.prototipopagosoffline.network.OfflinePaymentResultV2
import com.example.prototipopagosoffline.network.OfflinePaymentStatusResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V2SyncRepositoryTest {
    @Test
    fun `accepts exactly one known individual result`() {
        val result = OfflinePaymentResultV2("operation-1", "ACCEPTED", null)
        assertEquals(
            result,
            selectUniqueResult("operation-1", setOf("operation-1"), listOf(result))
        )
    }

    @Test
    fun `missing duplicate foreign and unknown results are not applied`() {
        val accepted = OfflinePaymentResultV2("operation-1", "ACCEPTED", null)
        assertNull(selectUniqueResult("operation-1", setOf("operation-1"), null))
        assertNull(selectUniqueResult("operation-1", setOf("operation-1"), listOf(accepted, accepted)))
        assertNull(selectUniqueResult("operation-1", setOf("operation-2"), listOf(accepted)))
        assertNull(
            selectUniqueResult(
                "operation-1",
                setOf("operation-1"),
                listOf(accepted.copy(status = "UNSUPPORTED"))
            )
        )
    }

    @Test
    fun `status lookup must match operation and known status`() {
        val accepted = OfflinePaymentStatusResponse("operation-1", "ACCEPTED", "OK")
        assertEquals(
            OfflinePaymentResultV2("operation-1", "ACCEPTED", "OK"),
            validatedStatusResult("operation-1", accepted)
        )
        assertNull(validatedStatusResult("operation-2", accepted))
        assertNull(validatedStatusResult("operation-1", accepted.copy(status = "UNSUPPORTED")))
        assertNull(validatedStatusResult("operation-1", null))
    }
}
