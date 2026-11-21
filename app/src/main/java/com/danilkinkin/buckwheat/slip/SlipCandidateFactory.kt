package com.danilkinkin.buckwheat.slip

import com.danilkinkin.buckwheat.data.entities.SlipDetails
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.util.roundToDay
import com.danilkinkin.buckwheat.util.toDate
import kotlinx.coroutines.flow.first
import androidx.lifecycle.asFlow
import java.math.BigDecimal
import java.util.Date
import kotlin.math.abs

enum class SlipWarning {
    /** No text could be read at all */
    UNREADABLE,
    NO_AMOUNT,

    /** No date found, today is used */
    NO_DATE,

    /** Before the start of the current budget period: goes into a past period */
    OUTSIDE_PERIOD,

    /** Later than now, can't be added */
    FUTURE,

    /** A spend with the same amount at about the same time already exists */
    DUPLICATE,
}

data class SlipCandidate(
    val id: Int,
    val amountText: String,
    val date: Date,
    val tag: String,
    val payee: String?,
    val note: String?,
    val warnings: Set<SlipWarning>,
    val selected: Boolean,
    /** MediaStore id when the slip came from a watched folder */
    val mediaId: Long? = null,
    /** What OCR read, shown on request so a wrong reading can be understood */
    val rawText: String = "",
    /** Why the image couldn't be read at all, e.g. an OCR engine error */
    val error: String? = null,
    val reference: String? = null,
) {
    val amount: BigDecimal?
        get() = amountText.replace(",", "").trim().toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }

    /** Slips before the current period go into past periods; only future ones can't be added */
    val canBeAdded: Boolean
        get() = SlipWarning.FUTURE !in warnings

    /** Goes into a past period, not the current budget */
    val isPast: Boolean
        get() = SlipWarning.OUTSIDE_PERIOD in warnings

    /** Looks right: ticked in the review list */
    val looksGood: Boolean
        get() = canBeAdded && amount != null && warnings.none {
            it == SlipWarning.DUPLICATE || it == SlipWarning.NO_DATE || it == SlipWarning.UNREADABLE
        }

    /**
     * Safe to add without the user looking at it, e.g. from a notification: also only into the
     * current period, which never creates past periods behind the user's back
     */
    val canBeAddedDirectly: Boolean
        get() = looksGood && !isPast

    fun toDetails() = SlipDetails(
        transactionUid = 0,
        payee = payee,
        reference = reference,
        note = note,
        rawText = rawText,
        mediaId = mediaId,
    )

    /** @param defaultTag used when the tag is left empty, e.g. "Other" */
    fun toHistoryRow(pastPeriodBudget: BigDecimal?, defaultTag: String) = com.danilkinkin.buckwheat.wallet.HistoryRow(
        date = java.time.LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault()),
        amount = amount!!,
        currency = "",
        tag = tag.trim().ifEmpty { defaultTag },
        manualTreat = false,
        automaticTreat = false,
        periodStart = null,
        periodEnd = null,
        periodBudget = pastPeriodBudget,
        payee = payee,
        reference = reference,
        note = note,
        rawText = rawText,
        mediaId = mediaId,
    )

    /** @param defaultTag used when the tag is left empty, e.g. "Other" */
    fun toTransaction(defaultTag: String) = Transaction(
        type = TransactionType.SPENT,
        value = amount!!,
        date = date,
        comment = tag.trim().ifEmpty { defaultTag },
    )
}

/** Turns OCR text into a reviewable [SlipCandidate], checked against the current budget. */
class SlipCandidateFactory(
    private val tagCounts: Map<String, Int>,
    private val payeeTags: Map<String, String>,
    private val existingSpends: List<Transaction>,
    private val periodStart: Date,
    /** Earlier days of this month can still join the current period, moving its start back */
    private val addableToCurrentFrom: Date = periodStart,
    private val now: Date = Date(),
    /** References of slips already added, in any period */
    private val usedReferences: Set<String> = emptySet(),
) {
    fun build(
        id: Int,
        text: String,
        others: List<SlipCandidate> = emptyList(),
        mediaId: Long? = null,
        error: String? = null,
        /** When the image was saved, helps with a day that OCR lost, see [SlipParser.parse] */
        imageDate: java.time.LocalDateTime? = null,
    ): SlipCandidate {
        val slip = SlipParser.parse(text, imageDate)
        val date = slip.dateTime?.toDate() ?: now
        val warnings = mutableSetOf<SlipWarning>()

        if (text.isBlank()) warnings += SlipWarning.UNREADABLE
        if (slip.amount == null) warnings += SlipWarning.NO_AMOUNT
        if (slip.dateTime == null) warnings += SlipWarning.NO_DATE
        if (date.before(addableToCurrentFrom)) warnings += SlipWarning.OUTSIDE_PERIOD
        if (date.time > now.time + FUTURE_TOLERANCE) warnings += SlipWarning.FUTURE

        // Same bank transfer: certain, whatever the amount or time
        if (slip.reference != null &&
            (slip.reference in usedReferences || others.any { it.reference == slip.reference })
        ) {
            warnings += SlipWarning.DUPLICATE
        }

        if (slip.amount != null) {
            val sameSpend = { value: BigDecimal?, other: Date ->
                value != null && value.compareTo(slip.amount) == 0 &&
                        abs(other.time - date.time) <= DUPLICATE_WINDOW
            }
            // Already in the budget, or the same slip picked twice
            if (existingSpends.any { sameSpend(it.value, it.date) } ||
                others.any { sameSpend(it.amount, it.date) }
            ) {
                warnings += SlipWarning.DUPLICATE
            }
        }

        val candidate = SlipCandidate(
            id = id,
            amountText = slip.amount?.toPlainString().orEmpty(),
            date = date,
            tag = TagSuggester.suggest(slip.note, slip.payee, tagCounts, payeeTags),
            payee = slip.payee,
            note = slip.note,
            warnings = warnings,
            selected = false,
            mediaId = mediaId,
            rawText = text,
            error = error,
            reference = slip.reference,
        )

        return candidate.copy(selected = candidate.looksGood)
    }

    /**
     * Re-checks a slip found earlier against the budget as it is now: the period may have been
     * restarted, or the spend added by hand since the notification was shown.
     */
    fun stillAddable(candidate: SlipCandidate): Boolean {
        val amount = candidate.amount ?: return false
        if (candidate.reference != null && candidate.reference in usedReferences) return false
        if (candidate.date.before(periodStart) || candidate.date.time > now.time + FUTURE_TOLERANCE) return false

        return existingSpends.none {
            it.value.compareTo(amount) == 0 && abs(it.date.time - candidate.date.time) <= DUPLICATE_WINDOW
        }
    }

    /** For review: still not in the budget already (by reference or amount and time), and not in the future */
    fun stillNew(candidate: SlipCandidate): Boolean {
        if (candidate.date.time > now.time + FUTURE_TOLERANCE) return false
        if (candidate.reference != null && candidate.reference in usedReferences) return false
        val amount = candidate.amount ?: return true
        return existingSpends.none {
            it.value.compareTo(amount) == 0 && abs(it.date.time - candidate.date.time) <= DUPLICATE_WINDOW
        }
    }

    companion object {
        private const val DUPLICATE_WINDOW = 30 * 60 * 1000L
        private const val FUTURE_TOLERANCE = 5 * 60 * 1000L

        /** Factory with the user's current budget, history and remembered payee tags */
        suspend fun load(
            spendsRepository: SpendsRepository,
            tagMemory: SlipTagMemory,
        ): SlipCandidateFactory {
            val tagCounts = spendsRepository.getSpendingHistory().asFlow().first()
                .filter { it.comment.isNotBlank() }
                .groupingBy { it.comment.trim() }
                .eachCount()

            return SlipCandidateFactory(
                tagCounts = tagCounts,
                payeeTags = tagMemory.getAll(),
                existingSpends = spendsRepository.getAllSpends().asFlow().first(),
                periodStart = roundToDay(spendsRepository.getStartPeriodDate().first()),
                addableToCurrentFrom = spendsRepository.getCurrentPeriodExtendableStart(),
                usedReferences = spendsRepository.getUsedSlipReferences(),
            )
        }
    }
}
