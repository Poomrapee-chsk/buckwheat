package com.danilkinkin.buckwheat.slip

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

class SlipCandidateFactoryTest {
    private fun date(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        Date.from(LocalDateTime.of(y, m, d, h, min).atZone(ZoneId.systemDefault()).toInstant())

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("slips/$name.txt")!!.readText()

    private fun factory(existing: List<Transaction> = emptyList(), periodStart: Date = date(2026, 11, 1)) =
        SlipCandidateFactory(
            tagCounts = mapOf("Matcha" to 3),
            payeeTags = emptyMap(),
            existingSpends = existing,
            periodStart = periodStart,
            now = date(2026, 11, 21, 18, 0),
        )

    @Test
    fun goodSlipIsSelected() {
        val candidate = factory().build(0, fixture("kbank_real_matcha"), mediaId = 42)

        assertEquals(BigDecimal("120.00"), candidate.amount)
        assertEquals("Matcha", candidate.tag)
        assertEquals(42L, candidate.mediaId)
        assertTrue(candidate.warnings.isEmpty())
        assertTrue(candidate.selected)
        assertTrue(candidate.canBeAddedDirectly)
    }

    @Test
    fun beforePeriodCantBeAdded() {
        // Make slip is from 27 Oct
        val candidate = factory().build(0, fixture("make_real_itcity"))

        assertTrue(SlipWarning.OUTSIDE_PERIOD in candidate.warnings)
        assertFalse(candidate.canBeAdded)
        assertFalse(candidate.selected)
    }

    @Test
    fun alreadyAddedByHandIsDuplicate() {
        val existing = Transaction(TransactionType.SPENT, BigDecimal("120.00"), date(2026, 11, 21, 16, 45))
        val candidate = factory(listOf(existing)).build(0, fixture("kbank_real_matcha"))

        assertTrue(SlipWarning.DUPLICATE in candidate.warnings)
        assertFalse(candidate.selected)
    }

    @Test
    fun sameSlipTwiceInOneBatch() {
        val factory = factory()
        val first = factory.build(0, fixture("kbank_real_matcha"))
        val second = factory.build(1, fixture("kbank_real_matcha"), others = listOf(first))

        assertTrue(first.selected)
        assertTrue(SlipWarning.DUPLICATE in second.warnings)
        assertFalse(second.selected)
    }

    @Test
    fun stillAddableRechecksTheBudget() {
        val slip = factory().build(0, fixture("kbank_real_amazon"))
        assertTrue(factory().stillAddable(slip))

        // Added by hand after the notification
        val addedByHand = Transaction(TransactionType.SPENT, BigDecimal("55.00"), date(2026, 11, 19, 10, 30))
        assertFalse(factory(listOf(addedByHand)).stillAddable(slip))

        // A new period started after the notification
        assertFalse(factory(periodStart = date(2026, 11, 20)).stillAddable(slip))
    }
}
