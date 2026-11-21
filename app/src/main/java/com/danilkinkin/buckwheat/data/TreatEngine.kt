package com.danilkinkin.buckwheat.data

import java.math.BigDecimal

object TreatEngine {
    const val MINIMUM_SAMPLES = 5

    fun isLavish(amount: BigDecimal, history: List<BigDecimal>): Boolean {
        val amounts = history.filter { it > BigDecimal.ZERO }.sorted()
        if (amounts.size < MINIMUM_SAMPLES) return false
        val middle = amounts.size / 2
        val median = if (amounts.size % 2 == 0) {
            (amounts[middle - 1] + amounts[middle]).divide(BigDecimal(2))
        } else {
            amounts[middle]
        }
        return amount > median.multiply(BigDecimal("1.5"))
    }
}
