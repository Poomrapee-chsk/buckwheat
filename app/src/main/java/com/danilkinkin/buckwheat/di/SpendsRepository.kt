package com.danilkinkin.buckwheat.di

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import androidx.lifecycle.LiveData
import androidx.lifecycle.asFlow
import androidx.lifecycle.map
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.data.TreatEngine
import java.util.Calendar
import com.danilkinkin.buckwheat.data.RestedBudgetDistributionMethod
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.util.DAY
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.dao.PeriodDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.Period
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.errorForReport
import com.danilkinkin.buckwheat.util.countDays
import com.danilkinkin.buckwheat.util.isSameDay
import com.danilkinkin.buckwheat.util.roundToDay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.lang.Long.min
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date
import javax.inject.Inject

val currencyStoreKey = stringPreferencesKey("currency")
val restedBudgetDistributionMethodStoreKey = stringPreferencesKey("restedBudgetDistributionMethod")
val hideOverspendingWarnStoreKey = booleanPreferencesKey("hideOverspendingWarn")

val budgetStoreKey = stringPreferencesKey("budget")
val spentStoreKey = stringPreferencesKey("spent")
val dailyBudgetStoreKey = stringPreferencesKey("dailyBudget")
val spentFromDailyBudgetStoreKey = stringPreferencesKey("spentFromDailyBudget")
val lastChangeDailyBudgetDateStoreKey = longPreferencesKey("lastChangeDailyBudgetDate")
val startPeriodDateStoreKey = longPreferencesKey("startPeriodDate")
val finishPeriodDateStoreKey = longPreferencesKey("finishPeriodDate")
val finishPeriodActualDateStoreKey = longPreferencesKey("finishPeriodActualDate")

class SpendsRepository @Inject constructor(
    @ApplicationContext val context: Context,
    private val db: DatabaseModule,
    private val transactionDao: TransactionDao,
    private val periodDao: PeriodDao,
    private val getCurrentDateUseCase: GetCurrentDateUseCase,
) {
    fun getSpendingHistory() = transactionDao.getSpendingHistory()

    fun getAllTransactions(): LiveData<List<Transaction>> = transactionDao.getAll()
    fun getAllSpends(): LiveData<List<Transaction>> = transactionDao.getAll(TransactionType.SPENT)

    fun getAllPeriods(): LiveData<List<Period>> = periodDao.getAll()

    fun getPeriodTransactions(periodId: Int): LiveData<List<Transaction>> =
        transactionDao.getAllByPeriod(periodId)

    fun getPeriodSpends(periodId: Int): LiveData<List<Transaction>> =
        transactionDao.getAllByPeriod(periodId, TransactionType.SPENT)

    fun getPeriod(periodId: Int): Period? = periodDao.getById(periodId)

    fun getPeriodTransactionsSync(periodId: Int): List<Transaction> =
        transactionDao.getAllByPeriodSync(periodId)

    /** Permanently remove an archived period from history, along with its transactions. */
    suspend fun deletePeriod(periodId: Int) {
        transactionDao.deleteByPeriod(periodId)
        periodDao.deleteById(periodId)

        Log.d("SpendsRepository", "Deleted archived period [id: $periodId]")
    }

    /**
     * Re-insert a previously deleted period (with a fresh id) and re-attach the
     * given transactions to it. Used to undo [deletePeriod].
     */
    suspend fun restorePeriod(period: Period, transactions: List<Transaction>) {
        val newId = periodDao.insert(
            Period(
                budget = period.budget,
                startDate = period.startDate,
                finishDate = period.finishDate,
                actualFinishDate = period.actualFinishDate,
                currency = period.currency,
                archivedAt = period.archivedAt,
            )
        ).toInt()

        transactions.forEach { transaction ->
            transactionDao.insert(
                Transaction(
                    type = transaction.type,
                    value = transaction.value,
                    date = transaction.date,
                    comment = transaction.comment,
                    periodId = newId,
                    manualTreat = transaction.manualTreat,
                    automaticTreat = transaction.automaticTreat,
                )
            )
        }

        Log.d(
            "SpendsRepository",
            "Restored period [id: $newId transactions: ${transactions.size}]"
        )
    }

    /** Build a [Period] snapshot from the current datastore state, or null when there is no active budget. */
    private fun periodSnapshotFrom(prefs: Preferences): Period? {
        val budget = prefs[budgetStoreKey]?.toBigDecimalOrNull() ?: return null
        val startDate = prefs[startPeriodDateStoreKey]?.let { Date(it) } ?: return null
        val finishDate = prefs[finishPeriodDateStoreKey]?.let { Date(it) } ?: return null
        val actualFinishDate = prefs[finishPeriodActualDateStoreKey]?.let { Date(it) }
        val currency = prefs[currencyStoreKey] ?: ""

        return Period(
            budget = budget,
            startDate = startDate,
            finishDate = finishDate,
            actualFinishDate = actualFinishDate,
            currency = currency,
            archivedAt = getCurrentDateUseCase(),
        )
    }

    /** Wipe the datastore state describing the live period (archived history is kept). */
    private suspend fun clearCurrentPeriodState() {
        context.budgetDataStore.edit {
            it.remove(budgetStoreKey)
            it.remove(spentStoreKey)
            it.remove(dailyBudgetStoreKey)
            it.remove(spentFromDailyBudgetStoreKey)
            it.remove(lastChangeDailyBudgetDateStoreKey)
            it.remove(startPeriodDateStoreKey)
            it.remove(finishPeriodDateStoreKey)
            it.remove(finishPeriodActualDateStoreKey)
        }
    }

    /**
     * Archive the currently active budget period into history:
     * insert a [Period] snapshot and stamp all of the current period's
     * transactions (periodId IS NULL) with the new period id, atomically.
     *
     * No-op when there is no active budget (first run / after full reset) or
     * when the current period has no transactions to keep.
     */
    suspend fun archiveCurrentPeriod() {
        val period = periodSnapshotFrom(context.budgetDataStore.data.first()) ?: return

        db.withTransaction {
            if (transactionDao.getCurrentPeriodTransactions().isEmpty()) return@withTransaction

            val periodId = periodDao.insert(period).toInt()
            transactionDao.assignCurrentPeriodTo(periodId)

            Log.d(
                "SpendsRepository",
                "Archived period [id: $periodId budget: ${period.budget}]"
            )
        }
    }

    /**
     * One-time backfill for users upgrading to the history feature:
     * if there is no archived period yet and the active period has already
     * finished, snapshot it into history and clear the (now stale) live
     * period state so the app cleanly asks for a new budget. A still-running
     * period is left untouched — it will be archived when the next period starts.
     */
    suspend fun backfillFinishedPeriodIfNeeded() {
        if (periodDao.count() > 0) return

        val prefs = context.budgetDataStore.data.first()
        if (prefs[budgetStoreKey] == null) return

        val finishDate = prefs[finishPeriodDateStoreKey]?.let { Date(it) } ?: return
        val actualFinishDate = prefs[finishPeriodActualDateStoreKey]?.let { Date(it) }

        val finished = actualFinishDate != null || finishDate.time <= getCurrentDateUseCase().time
        if (!finished) return

        archiveCurrentPeriod()
        clearCurrentPeriodState()
    }

    fun getAllTags(): LiveData<List<String>> = transactionDao.getAll().map { transactions ->
        transactions
            .asSequence()
            .filter { transaction -> transaction.comment.isNotEmpty() }
            .groupBy { it.comment }
            .map { it.key to it.value.size }
            .sortedBy { -it.second }
            .map { it.first }
            .distinct()
            .toList()
    }

    fun getBudget() = context.budgetDataStore.data.map {
        (it[budgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO).setScale(2)
    }

    fun getSpent() = context.budgetDataStore.data.map {
        (it[spentStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO).setScale(2)
    }

    fun getDailyBudget() = context.budgetDataStore.data.map {
        (it[dailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO).setScale(2)
    }

    fun getSpentFromDailyBudget() = context.budgetDataStore.data.map {
        (it[spentFromDailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO).setScale(2)
    }

    fun getStartPeriodDate() = context.budgetDataStore.data.map {
        it[startPeriodDateStoreKey]?.let { value -> Date(value) } ?: getCurrentDateUseCase()
    }

    fun getFinishPeriodDate() = context.budgetDataStore.data.map {
        it[finishPeriodDateStoreKey]?.let { value -> Date(value) }
    }

    fun getFinishPeriodActualDate() = context.budgetDataStore.data.map {
        it[finishPeriodActualDateStoreKey]?.let { value -> Date(value) }
    }

    fun getLastChangeDailyBudgetDate() = context.budgetDataStore.data.map {
        it[lastChangeDailyBudgetDateStoreKey]?.let { value -> Date(value) }
    }

    fun getCurrency() = context.budgetDataStore.data.map {
        it[currencyStoreKey]?.let { value ->
            ExtendCurrency.getInstance(value)
        } ?: ExtendCurrency(value = null, type = ExtendCurrency.Type.NONE)
    }

    fun getRestedBudgetDistributionMethod() = context.budgetDataStore.data.map { it ->
        it[restedBudgetDistributionMethodStoreKey]?.let {
            RestedBudgetDistributionMethod.valueOf(it)
        } ?: RestedBudgetDistributionMethod.ASK
    }

    fun getHideOverspendingWarn() = context.budgetDataStore.data.map {
        it[hideOverspendingWarnStoreKey] ?: false
    }


    suspend fun changeDisplayCurrency(currency: ExtendCurrency) {
        context.budgetDataStore.edit {
            it[currencyStoreKey] = currency.value ?: ""
        }
    }

    suspend fun changeRestedBudgetDistributionMethod(method: RestedBudgetDistributionMethod) {
        context.budgetDataStore.edit {
            it[restedBudgetDistributionMethodStoreKey] = method.toString()
        }
    }

    suspend fun hideOverspendingWarn(hide: Boolean) {
        context.budgetDataStore.edit {
            it[hideOverspendingWarnStoreKey] = hide
        }
    }

    suspend fun setBudget(newBudget: BigDecimal, newFinishDate: Date) {
        // Snapshot the finishing period into history and seed the new period in a
        // single DB transaction, so a crash can never leave the datastore pointing
        // at a period whose transactions have all been archived away.
        val archivingPeriod = periodSnapshotFrom(context.budgetDataStore.data.first())

        db.withTransaction {
            if (archivingPeriod != null && transactionDao.getCurrentPeriodTransactions().isNotEmpty()) {
                val periodId = periodDao.insert(archivingPeriod).toInt()
                transactionDao.assignCurrentPeriodTo(periodId)
            }

            // Drop anything left unarchived (e.g. an unused prior budget with no
            // transactions), keeping archived history intact, then seed the new period.
            transactionDao.deleteCurrentPeriod()
            transactionDao.insert(
                Transaction(
                    TransactionType.INCOME,
                    newBudget,
                    getCurrentDateUseCase(),
                )
            )
        }

        context.budgetDataStore.edit {
            it[budgetStoreKey] = newBudget.toString()
            it[spentStoreKey] = BigDecimal.ZERO.toString()
            it[dailyBudgetStoreKey] = BigDecimal.ZERO.toString()
            it[spentFromDailyBudgetStoreKey] = BigDecimal.ZERO.toString()
            it[lastChangeDailyBudgetDateStoreKey] = roundToDay(getCurrentDateUseCase()).time
            it[startPeriodDateStoreKey] = roundToDay(getCurrentDateUseCase()).time
            it[finishPeriodDateStoreKey] = Date(roundToDay(newFinishDate).time + DAY - 1000).time
            it.remove(finishPeriodActualDateStoreKey)

            Log.d(
                "SpendsRepository",
                "Set budget ["
                        + "budget: ${it[budgetStoreKey]} "
                        + "start date: ${Date(it[startPeriodDateStoreKey]!!)} "
                        + "finish date: ${Date(it[finishPeriodDateStoreKey]!!)}"
                        + "]"
            )
        }

        setDailyBudget(whatBudgetForDay())

        hideOverspendingWarn(false)
    }

    suspend fun changeBudget(newBudget: BigDecimal, newFinishDate: Date) {
        context.budgetDataStore.edit {
            it[budgetStoreKey] = newBudget.toString()
            it[lastChangeDailyBudgetDateStoreKey] = roundToDay(getCurrentDateUseCase()).time
            it[finishPeriodDateStoreKey] = Date(roundToDay(newFinishDate).time + DAY - 1000).time
            it.remove(finishPeriodActualDateStoreKey)

            Log.d(
                "SpendsRepository",
                "Change budget ["
                        + "budget: ${it[budgetStoreKey]} "
                        + "start date: ${Date(it[startPeriodDateStoreKey]!!)} "
                        + "finish date: ${Date(it[finishPeriodDateStoreKey]!!)}"
                        + "]"
            )
        }

        val incomeTransaction =
            transactionDao.getAll(TransactionType.INCOME).asFlow().first().firstOrNull()

        if (incomeTransaction != null) {
            transactionDao.update(incomeTransaction.copy(value = newBudget))
        }

        updateDailyBudget(whatBudgetForDay())
    }

    suspend fun finishBudget(finishDate: Date) {
        context.budgetDataStore.edit {
            it[finishPeriodActualDateStoreKey] = finishDate.time

            Log.d(
                "SpendsRepository",
                "Finish budget ["
                        + "budget: ${it[budgetStoreKey]} "
                        + "start date: ${Date(it[startPeriodDateStoreKey]!!)} "
                        + "actual finish date: ${Date(it[finishPeriodActualDateStoreKey]!!)}"
                        + "finish date: ${Date(it[finishPeriodDateStoreKey]!!)}"
                        + "]"
            )
        }
    }

    suspend fun updateDailyBudget(newDailyBudget: BigDecimal) {
        context.budgetDataStore.edit {
            it[dailyBudgetStoreKey] = newDailyBudget.toString()
            it[lastChangeDailyBudgetDateStoreKey] = roundToDay(getCurrentDateUseCase()).time

            Log.d(
                "SpendsRepository",
                "Update daily budget ["
                        + "daily budget: ${it[dailyBudgetStoreKey]} "
                        + "spent: ${it[spentStoreKey]}"
                        + "]"
            )
        }


        val setDailyBudgetTransaction =
            transactionDao.getAll(TransactionType.SET_DAILY_BUDGET).asFlow().first().lastOrNull()
        if (setDailyBudgetTransaction != null) {
            transactionDao.update(setDailyBudgetTransaction.copy(value = newDailyBudget))
        }
    }

    suspend fun setDailyBudget(newDailyBudget: BigDecimal) {
        context.budgetDataStore.edit {
            val spent: BigDecimal = it[spentStoreKey]?.toBigDecimal()!!
            val spentFromDailyBudget: BigDecimal =
                it[spentFromDailyBudgetStoreKey]?.toBigDecimal()!!

            it[dailyBudgetStoreKey] = newDailyBudget.toString()
            it[spentStoreKey] = (spent + spentFromDailyBudget).toString()
            it[lastChangeDailyBudgetDateStoreKey] = roundToDay(getCurrentDateUseCase()).time
            it[spentFromDailyBudgetStoreKey] = BigDecimal.ZERO.toString()

            Log.d(
                "SpendsRepository",
                "Set daily budget ["
                        + "daily budget: ${it[dailyBudgetStoreKey]} "
                        + "spent: ${it[spentStoreKey]}"
                        + "]"
            )
        }

        transactionDao.insert(
            Transaction(
                TransactionType.SET_DAILY_BUDGET,
                newDailyBudget,
                getCurrentDateUseCase(),
            )
        )
    }

    suspend fun whatBudgetForDay(
        excludeCurrentDay: Boolean = false,
        applyTodaySpends: Boolean = false,
        notCommittedSpent: BigDecimal = BigDecimal.ZERO
    ): BigDecimal {
        val budget = getBudget().first()
        val spent = getSpent().first()
        val dailyBudget = getDailyBudget().first()
        val spentFromDailyBudget = getSpentFromDailyBudget().first()
        val finishPeriodDate =
            getFinishPeriodDate().first() ?: throw Exception("Finish period date is null")


        val restDays =
            countDays(finishPeriodDate, getCurrentDateUseCase()) - if (excludeCurrentDay) 1 else 0
        var restBudget = budget - spent

        restBudget -= notCommittedSpent

        if (applyTodaySpends) {
            restBudget -= spentFromDailyBudget
        } else if (excludeCurrentDay) {
            restBudget -= dailyBudget
        }

        val whatBudgetForDay = restBudget
            .divide(
                restDays.toBigDecimal().coerceAtLeast(BigDecimal(1)),
                2,
                RoundingMode.HALF_EVEN
            )

        Log.d(
            "SpendsRepository",
            "Check what budget for day ["
                    + "date: ${getCurrentDateUseCase()} "
                    + "what budget for day: $whatBudgetForDay "
                    + "excludeCurrentDay: $excludeCurrentDay "
                    + "applyTodaySpends: $applyTodaySpends "
                    + "notCommittedSpent: $notCommittedSpent "
                    + "budget: $budget "
                    + "spent: $spent "
                    + "daily budget: $dailyBudget "
                    + "spent from daily budget: $spentFromDailyBudget "
                    + "rest budget: $restBudget "
                    + "rest days: $restDays"
                    + "]"
        )

        return whatBudgetForDay
    }

    suspend fun howMuchBudgetRest(): BigDecimal {
        val budget = getBudget().first()
        val spent = getSpent().first()
        val spentFromDailyBudget = getSpentFromDailyBudget().first()

        return budget - spent - spentFromDailyBudget
    }

    suspend fun howMuchNotSpent(
        excludeSkippedPart: Boolean = false,
    ): BigDecimal {
        val budget = getBudget().first()
        val spent = getSpent().first()
        val dailyBudget = getDailyBudget().first()
        val spentFromDailyBudget = getSpentFromDailyBudget().first()
        val finishPeriodDate =
            getFinishPeriodDate().first() ?: throw Exception("Finish period date is null")
        val lastChangeDailyBudgetDate =
            getLastChangeDailyBudgetDate().first() ?: getStartPeriodDate().first()


        val restDays = countDays(finishPeriodDate, getCurrentDateUseCase()).coerceAtLeast(0)
        val skippedDays = countDays(
            Date(min(getCurrentDateUseCase().time, finishPeriodDate.time)),
            lastChangeDailyBudgetDate
        ) - 1

        var restBudget = budget - spent

        val howMuchNotSpent = if (restDays == 0) {
            restBudget - spentFromDailyBudget
        } else if (excludeSkippedPart) {
            restBudget
                .minus(dailyBudget * skippedDays.toBigDecimal())
                .divide(
                    (restDays).coerceAtLeast(1).toBigDecimal(),
                    2,
                    RoundingMode.HALF_EVEN,
                )
                .multiply((skippedDays).coerceAtLeast(0).toBigDecimal())
                .plus(dailyBudget - spentFromDailyBudget)
        } else {
            restBudget
                .minus(dailyBudget)
                .divide(
                    (restDays + skippedDays - 1).coerceAtLeast(1).toBigDecimal(),
                    2,
                    RoundingMode.HALF_EVEN,
                )
                .multiply((skippedDays).coerceAtLeast(0).toBigDecimal())
                .plus(dailyBudget - spentFromDailyBudget)
        }

        Log.d(
            "SpendsRepository",
            "How much not spent check ["
                    + "how much not spent: $howMuchNotSpent "
                    + "rest budget: $restBudget "
                    + "restDays: $restDays "
                    + "skippedDays: $skippedDays "
                    + "lastChangeDailyBudgetDate: $lastChangeDailyBudgetDate "
                    + "getCurrentDateUseCase: ${getCurrentDateUseCase()} "
                    + "dailyBudget: $dailyBudget "
                    + "spentFromDailyBudget: $spentFromDailyBudget "
                    + "]"
        )

        return howMuchNotSpent
    }

    suspend fun nextDayBudget(
        excludeSkippedPart: Boolean = false,
    ): BigDecimal {
        val budget = getBudget().first()
        val spent = getSpent().first()
        val dailyBudget = getDailyBudget().first()
        val spentFromDailyBudget = getSpentFromDailyBudget().first()
        val finishPeriodDate =
            getFinishPeriodDate().first() ?: throw Exception("Finish period date is null")
        val lastChangeDailyBudgetDate =
            getLastChangeDailyBudgetDate().first() ?: getStartPeriodDate().first()


        val restDays = countDays(finishPeriodDate, getCurrentDateUseCase()).coerceAtLeast(0)
        val skippedDays = countDays(
            Date(min(getCurrentDateUseCase().time, finishPeriodDate.time)),
            lastChangeDailyBudgetDate
        ) - 1

        var restBudget = budget - spent

        val nextDailyBudget = if (restDays == 0) {
            restBudget - spentFromDailyBudget
        } else if (excludeSkippedPart) {
            restBudget
                .minus(dailyBudget * skippedDays.toBigDecimal())
                .divide(
                    (restDays).coerceAtLeast(1).toBigDecimal(),
                    2,
                    RoundingMode.HALF_EVEN,
                )
        } else {
            restBudget
                .minus(dailyBudget)
                .divide(
                    (restDays + skippedDays - 1).coerceAtLeast(1).toBigDecimal(),
                    2,
                    RoundingMode.HALF_EVEN,
                )
        }

        Log.d(
            "SpendsRepository",
            "Next day budget ["
                    + "next daily budget: $nextDailyBudget "
                    + "rest budget: $restBudget "
                    + "restDays: $restDays "
                    + "skippedDays: $skippedDays "
                    + "lastChangeDailyBudgetDate: $lastChangeDailyBudgetDate "
                    + "getCurrentDateUseCase: ${getCurrentDateUseCase()} "
                    + "dailyBudget: $dailyBudget "
                    + "spentFromDailyBudget: $spentFromDailyBudget "
                    + "]"
        )

        return nextDailyBudget
    }

    suspend fun exceedsTreatThreshold(amount: BigDecimal, date: Date, excludeId: Int): Boolean {
        val currency = getCurrency().first().value ?: ""
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val start = Calendar.getInstance().apply {
                time = date
                add(Calendar.DAY_OF_YEAR, -30)
            }.timeInMillis
            val baseline = transactionDao.getTreatBaseline(start, date.time, excludeId, currency)
            TreatEngine.isLavish(amount, baseline.map { it.value })
        }
    }

    suspend fun addSpent(newTransaction: Transaction) {
        this.transactionDao.insert(newTransaction)

        context.budgetDataStore.edit {
            try {
                if (isSameDay(newTransaction.date, getCurrentDateUseCase())) {
                    val spentFromDailyBudget = it[spentFromDailyBudgetStoreKey]?.toBigDecimal()!!
                    it[spentFromDailyBudgetStoreKey] =
                        (spentFromDailyBudget + newTransaction.value).toString()
                } else {
                    val finishPeriodDate =
                        it[finishPeriodDateStoreKey]?.let { value -> Date(value) }!!
                    val dailyBudget = it[dailyBudgetStoreKey]?.toBigDecimal()!!
                    val spent = it[spentStoreKey]?.toBigDecimal()!!

                    val spreadDeltaSpentPerRestDays = newTransaction.value
                        .divide(
                            countDays(finishPeriodDate, getCurrentDateUseCase()).toBigDecimal(),
                            2,
                            RoundingMode.HALF_EVEN,
                        )

                    Log.d(
                        "SpendsRepository",
                        "Add spent for previous day ["
                                + "spent: $spent "
                                + "dailyBudget: $dailyBudget "
                                + "spreadDeltaSpentPerRestDays: $spreadDeltaSpentPerRestDays "
                                + "spentDate: ${newTransaction.date} "
                                + "getCurrentDateUseCase: ${getCurrentDateUseCase()} "
                                + "countDays: ${
                            countDays(
                                finishPeriodDate,
                                getCurrentDateUseCase()
                            )
                        } "
                                + "]"
                    )

                    it[dailyBudgetStoreKey] = (dailyBudget - spreadDeltaSpentPerRestDays).toString()
                    it[spentStoreKey] = (spent + newTransaction.value).toString()
                }
            } catch (e: Exception) {
                context.errorForReport = e.stackTraceToString()
            }
        }
    }

    suspend fun removeSpent(transactionForRemove: Transaction) {
        this.transactionDao.deleteById(transactionForRemove.uid)

        context.budgetDataStore.edit {
            if (isSameDay(transactionForRemove.date, getCurrentDateUseCase())) {
                val spentFromDailyBudget = it[spentFromDailyBudgetStoreKey]?.toBigDecimal()!!

                it[spentFromDailyBudgetStoreKey] =
                    (spentFromDailyBudget - transactionForRemove.value).toString()
            } else {
                val finishPeriodDate = it[finishPeriodDateStoreKey]?.let { value -> Date(value) }!!
                val dailyBudget = it[dailyBudgetStoreKey]?.toBigDecimal()!!
                val spent = it[spentStoreKey]?.toBigDecimal()!!

                val restDays = countDays(finishPeriodDate, getCurrentDateUseCase())
                val spreadDeltaSpentPerRestDays = transactionForRemove.value
                    .divide(
                        restDays.toBigDecimal(),
                        2,
                        RoundingMode.HALF_EVEN,
                    )

                Log.d(
                    "SpendsRepository",
                    "Remove spent from previous day { "
                            + transactionForRemove
                            + " } ["
                            + "spent: $spent "
                            + "dailyBudget: $dailyBudget "
                            + "spreadDeltaSpentPerRestDays: $spreadDeltaSpentPerRestDays "
                            + "spentDate: ${transactionForRemove.date} "
                            + "getCurrentDateUseCase: ${getCurrentDateUseCase()} "
                            + "countDays: $restDays "
                            + "]"
                )

                it[dailyBudgetStoreKey] = (dailyBudget + spreadDeltaSpentPerRestDays).toString()
                it[spentStoreKey] = (spent - transactionForRemove.value).toString()
            }
        }
    }
}