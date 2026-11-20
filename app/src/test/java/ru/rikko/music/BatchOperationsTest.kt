package ru.rikko.music

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchOperationsTest {
    @Test fun errorInMiddlePreservesCompletedAndRetryableItems() {
        val result = BatchOperations.run(listOf("a", "b", "c")) { item ->
            if (item == "b") throw IllegalStateException("Нет доступа")
            true
        }
        assertEquals(listOf("a", "c"), result.succeeded)
        assertEquals(listOf("b"), result.failed.map { it.item })
        assertEquals("Нет доступа", result.failed.single().reason)
    }

    @Test fun updateReturningZeroIsReportedAsFailure() {
        val result = BatchOperations.run(listOf(1, 2)) { it == 1 }
        assertEquals(listOf(1), result.succeeded)
        assertEquals(listOf(2), result.failed.map { it.item })
    }
}
