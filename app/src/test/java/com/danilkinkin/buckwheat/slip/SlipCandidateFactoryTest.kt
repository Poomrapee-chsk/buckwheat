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

    private fun factory(
        existing: List<Transaction> = emptyList(),
        periodStart: Date = date(2026, 11, 1),
        usedReferences: Set<String> = emptySet(),
    ) = SlipCandidateFactory(
        tagCounts = mapOf("Matcha" to 3),
        payeeTags = emptyMap(),
        existingSpends = existing,
        periodStart = periodStart,
        now = date(2026, 11, 21, 18, 0),
        usedReferences = usedReferences,
    )

    private fun onnxFixture(name: String): String =
        javaClass.classLoader!!.getResource("slips/onnx/$name.txt")!!.readText()

    @Test
    fun sameReferenceIsDuplicateWhateverTheTime() {
        // The reference is certain, even if a hand-added spend has another time
        val used = setOf("016325143212ATF04567")
        val candidate = factory(usedReferences = used).build(0, onnxFixture("kbank"))

        assertEquals("016325143212ATF04567", candidate.reference)
        assertTrue(SlipWarning.DUPLICATE in candidate.warnings)
        assertFalse(candidate.selected)
        assertFalse(factory(usedReferences = used).stillAddable(candidate))
    }

    @Test
    fun detailsKeepWhatWasRead() {
        val candidate = factory().build(0, onnxFixture("kbank"), mediaId = 9)
        val details = candidate.toDetails()

        assertEquals("ร้าน กาแฟดี", details.payee)
        assertEquals("016325143212ATF04567", details.reference)
        assertEquals("กาแฟเย็น", details.note)
        assertEquals(9L, details.mediaId)
        assertTrue(details.rawText.contains("เลขที่รายการ"))
    }

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
    fun beforePeriodGoesToPastPeriodButNotFromNotifications() {
        // Make slip is from 27 Oct, the period starts on 1 Nov
        val candidate = factory().build(0, fixture("make_real_itcity"))

        assertTrue(SlipWarning.OUTSIDE_PERIOD in candidate.warnings)
        assertTrue(candidate.isPast)
        assertTrue(candidate.canBeAdded)
        assertTrue(candidate.selected)
        assertFalse(candidate.canBeAddedDirectly)

        val row = candidate.toHistoryRow(BigDecimal("5000"), "Other")
        assertEquals(BigDecimal("5000"), row.periodBudget)
        assertEquals(null, row.periodStart)
        assertEquals("IT CITY PUBLIC", row.payee)
        // No tag found: the default
        assertEquals("", candidate.tag)
        assertEquals("Other", row.tag)
        assertEquals("Other", candidate.toTransaction("Other").comment)
    }

    @Test
    fun earlierThisMonthJoinsTheCurrentPeriod() {
        // Period starts on the 20th, but the 1st-19th can still join it: the 19 Nov slip isn't "past"
        val factory = SlipCandidateFactory(
            tagCounts = emptyMap(),
            payeeTags = emptyMap(),
            existingSpends = emptyList(),
            periodStart = date(2026, 11, 20),
            now = date(2026, 11, 21, 18, 0),
            addableToCurrentFrom = date(2026, 11, 1),
        )
        val candidate = factory.build(0, fixture("kbank_real_amazon"))

        assertFalse(candidate.isPast)
        assertTrue(candidate.selected)
        assertTrue(factory.stillNew(candidate))
        // Notifications never move the period start
        assertFalse(factory.stillAddable(candidate))
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
