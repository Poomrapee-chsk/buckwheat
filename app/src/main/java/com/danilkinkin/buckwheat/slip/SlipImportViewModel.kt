package com.danilkinkin.buckwheat.slip

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.util.roundToDay
import com.danilkinkin.buckwheat.util.toDate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.Date
import javax.inject.Inject
import kotlin.math.abs

enum class SlipWarning {
    /** No text could be read at all */
    UNREADABLE,
    NO_AMOUNT,

    /** No date found, today is used */
    NO_DATE,

    /** Before the start of the current budget period, can't be added */
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
) {
    val amount: BigDecimal?
        get() = amountText.replace(",", "").trim().toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }

    /** Spends outside the current period would break the daily budget math */
    val canBeAdded: Boolean
        get() = SlipWarning.OUTSIDE_PERIOD !in warnings && SlipWarning.FUTURE !in warnings
}

@HiltViewModel
class SlipImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val spendsRepository: SpendsRepository,
    private val tagMemory: SlipTagMemory,
) : ViewModel() {
    val candidates = mutableStateListOf<SlipCandidate>()
    var isReading by mutableStateOf(false)
        private set
    var readCount by mutableIntStateOf(0)
        private set
    var totalCount by mutableIntStateOf(0)
        private set

    private var nextId = 0

    fun readSlips(uris: List<Uri>) {
        if (uris.isEmpty() || isReading) return

        isReading = true
        readCount = 0
        totalCount = uris.size

        viewModelScope.launch {
            val tagCounts = spendsRepository.getSpendingHistory().asFlow().first()
                .filter { it.comment.isNotBlank() }
                .groupingBy { it.comment.trim() }
                .eachCount()
            val payeeTags = tagMemory.getAll()
            val existingSpends = spendsRepository.getAllSpends().asFlow().first()
            val periodStart = roundToDay(spendsRepository.getStartPeriodDate().first())

            try {
                SlipTextRecognizer(context).use { recognizer ->
                    uris.forEach { uri ->
                        val text = withContext(Dispatchers.Default) {
                            try {
                                recognizer.recognize(uri)
                            } catch (e: Exception) {
                                Log.e("SlipImport", "Failed to read slip $uri", e)
                                ""
                            }
                        }
                        candidates.add(
                            buildCandidate(text, tagCounts, payeeTags, existingSpends, periodStart)
                        )
                        readCount += 1
                    }
                }
            } catch (e: Exception) {
                Log.e("SlipImport", "OCR is not available", e)
            } finally {
                isReading = false
            }
        }
    }

    private fun buildCandidate(
        text: String,
        tagCounts: Map<String, Int>,
        payeeTags: Map<String, String>,
        existingSpends: List<Transaction>,
        periodStart: Date,
    ): SlipCandidate {
        val slip = SlipParser.parse(text)
        val now = Date()
        val date = slip.dateTime?.toDate() ?: now
        val warnings = mutableSetOf<SlipWarning>()

        if (text.isBlank()) warnings += SlipWarning.UNREADABLE
        if (slip.amount == null) warnings += SlipWarning.NO_AMOUNT
        if (slip.dateTime == null) warnings += SlipWarning.NO_DATE
        if (date.before(periodStart)) warnings += SlipWarning.OUTSIDE_PERIOD
        if (date.time > now.time + FUTURE_TOLERANCE) warnings += SlipWarning.FUTURE

        if (slip.amount != null) {
            val sameSpend = { value: BigDecimal?, other: Date ->
                value != null && value.compareTo(slip.amount) == 0 &&
                        abs(other.time - date.time) <= DUPLICATE_WINDOW
            }
            // Already in the budget, or the same slip picked twice
            if (existingSpends.any { sameSpend(it.value, it.date) } ||
                candidates.any { sameSpend(it.amount, it.date) }
            ) {
                warnings += SlipWarning.DUPLICATE
            }
        }

        val candidate = SlipCandidate(
            id = nextId++,
            amountText = slip.amount?.toPlainString().orEmpty(),
            date = date,
            tag = TagSuggester.suggest(slip.note, slip.payee, tagCounts, payeeTags),
            payee = slip.payee,
            note = slip.note,
            warnings = warnings,
            selected = false,
        )

        return candidate.copy(
            selected = candidate.canBeAdded && candidate.amount != null &&
                    warnings.none { it == SlipWarning.DUPLICATE || it == SlipWarning.NO_DATE }
        )
    }

    fun update(candidate: SlipCandidate) {
        val index = candidates.indexOfFirst { it.id == candidate.id }
        if (index >= 0) candidates[index] = candidate
    }

    val selectedCount: Int
        get() = candidates.count { it.selected && it.canBeAdded && it.amount != null }

    /** Adds the selected slips as spends, oldest first, and remembers the chosen tags. */
    fun addSelected(onDone: (count: Int) -> Unit) {
        val toAdd = candidates
            .filter { it.selected && it.canBeAdded && it.amount != null }
            .sortedBy { it.date }

        viewModelScope.launch {
            toAdd.forEach { candidate ->
                spendsRepository.addSpent(
                    Transaction(
                        type = TransactionType.SPENT,
                        value = candidate.amount!!,
                        date = candidate.date,
                        comment = candidate.tag.trim(),
                    )
                )
            }

            tagMemory.remember(
                toAdd
                    .filter { !it.payee.isNullOrBlank() }
                    .associate { it.payee!! to it.tag.trim() }
            )

            candidates.clear()
            onDone(toAdd.size)
        }
    }

    fun clear() {
        if (!isReading) candidates.clear()
    }

    companion object {
        private const val DUPLICATE_WINDOW = 30 * 60 * 1000L
        private const val FUTURE_TOLERANCE = 5 * 60 * 1000L
    }
}
