package com.danilkinkin.buckwheat.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class TreatEngineTest {
    private fun amounts(vararg values: String) = values.map(::BigDecimal)

    @Test
    fun thresholdIsStrict() {
        val history = amounts("10", "20", "30", "40", "50")
        assertFalse(TreatEngine.isLavish(BigDecimal("45"), history))
        assertTrue(TreatEngine.isLavish(BigDecimal("45.01"), history))
    }

    @Test
    fun evenMedianKeepsPrecision() {
        val history = amounts("1", "2", "3", "4", "5", "100")
        assertFalse(TreatEngine.isLavish(BigDecimal("5.25"), history))
        assertTrue(TreatEngine.isLavish(BigDecimal("5.26"), history))
    }

    @Test
    fun sparseAndNonpositiveHistoryDoesNotTrigger() {
        assertFalse(TreatEngine.isLavish(BigDecimal("1000"), emptyList()))
        assertFalse(TreatEngine.isLavish(BigDecimal("1000"), amounts("0", "-1", "1", "2", "3", "4")))
    }
}
