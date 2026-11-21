package com.danilkinkin.buckwheat.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.entities.Period
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.util.countDaysToToday
import com.danilkinkin.buckwheat.util.isToday
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.Date
import javax.inject.Inject

enum class RestedBudgetDistributionMethod { REST, ADD_TODAY, ASK }

@HiltViewModel
class SpendsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val spendsRepository: SpendsRepository,
) : ViewModel() {
    val spendingHistory = spendsRepository.getSpendingHistory()

    /** Details read from payment slips, keyed by transaction uid */
    val slipDetails = spendsRepository.getAllSlipDetails().map { list -> list.associateBy { it.transactionUid } }
    var tags = spendsRepository.getAllTags()
    var transactions = spendsRepository.getAllTransactions()
    var spends = spendsRepository.getAllSpends()
    var periods: LiveData<List<Period>> = spendsRepository.getAllPeriods()
    var budget = spendsRepository.getBudget().asLiveData()
    var spent = spendsRepository.getSpent().asLiveData()
    var dailyBudget = spendsRepository.getDailyBudget().asLiveData()
    var spentFromDailyBudget = spendsRepository.getSpentFromDailyBudget().asLiveData()
    var startPeriodDate = spendsRepository.getStartPeriodDate().asLiveData()
    var finishPeriodDate = spendsRepository.getFinishPeriodDate().asLiveData()
    var finishPeriodActualDate = spendsRepository.getFinishPeriodActualDate().asLiveData()
    var lastChangeDailyBudgetDate = spendsRepository.getLastChangeDailyBudgetDate().asLiveData()

    var currency = spendsRepository.getCurrency().asLiveData()
    var restedBudgetDistributionMethod =
        spendsRepository.getRestedBudgetDistributionMethod().asLiveData()
    var hideOverspendingWarn = spendsRepository.getHideOverspendingWarn().asLiveData()

    var requireDistributionRestedBudget = MutableLiveData(false)
    var requireSetBudget = MutableLiveData(false)
    var periodFinished = MutableLiveData(false)
    var lastRemovedTransaction: MutableLiveData<Transaction> = MutableLiveData()

    init {
        runChangeDayAction()
        runScheduledDetectChangeDayTask()

        viewModelScope.launch {
            spendsRepository.backfillFinishedPeriodIfNeeded()
        }
    }

    fun getPeriodTransactions(periodId: Int): LiveData<List<Transaction>> =
        spendsRepository.getPeriodTransactions(periodId)

    fun getPeriodSpends(periodId: Int): LiveData<List<Transaction>> =
        spendsRepository.getPeriodSpends(periodId)

    fun getPeriod(periodId: Int): Period? = spendsRepository.getPeriod(periodId)

    var lastRemovedPeriod: MutableLiveData<Period> = MutableLiveData()

    // Period + its transactions captured together so an undo always restores a
    // matching pair, even after several deletions in a row. Cleared once consumed.
    private var lastRemovedPeriodSnapshot: Pair<Period, List<Transaction>>? = null

    fun deletePeriod(periodId: Int) {
        viewModelScope.launch {
            val period = spendsRepository.getPeriod(periodId) ?: return@launch
            val transactions = spendsRepository.getPeriodTransactionsSync(periodId)

            spendsRepository.deletePeriod(periodId)

            lastRemovedPeriodSnapshot = period to transactions
            lastRemovedPeriod.value = period
        }
    }

    fun undoRemovePeriod() {
        viewModelScope.launch {
            val (period, transactions) = lastRemovedPeriodSnapshot ?: return@launch
            lastRemovedPeriodSnapshot = null

            spendsRepository.restorePeriod(period, transactions)
        }
    }

    // Budget handling

    fun setBudget(newBudget: BigDecimal, newFinishDate: Date, startDate: Date? = null) {
        viewModelScope.launch {
            spendsRepository.setBudget(newBudget, newFinishDate, startDate)

            requireSetBudget.value = false
            periodFinished.value = false
        }
    }

    suspend fun importHistory(rows: List<com.danilkinkin.buckwheat.wallet.HistoryRow>) =
        spendsRepository.importHistory(rows)

    fun changeBudget(newBudget: BigDecimal, newFinishDate: Date, startDate: Date? = null) {
        viewModelScope.launch {
            spendsRepository.changeBudget(newBudget, newFinishDate, startDate)

            requireSetBudget.value = false
            periodFinished.value = false
        }
    }

    fun finishBudget() {
        viewModelScope.launch {
            spendsRepository.finishBudget(Date())

            requireSetBudget.value = false
            periodFinished.value = true
        }
    }

    fun setDailyBudget(newDailyBudget: BigDecimal) {
        viewModelScope.launch {
            spendsRepository.setDailyBudget(newDailyBudget)
        }
    }

    // Spend handling

    suspend fun exceedsTreatThreshold(amount: BigDecimal, date: Date, excludeId: Int): Boolean =
        spendsRepository.exceedsTreatThreshold(amount, date, excludeId)

    fun addSpent(transactionForAdd: Transaction) {
        viewModelScope.launch {
            spendsRepository.addSpent(transactionForAdd)
        }
    }

    fun removeSpent(transactionForRemove: Transaction, silent: Boolean = false) {
        viewModelScope.launch {
            spendsRepository.removeSpent(transactionForRemove)

            if (!silent) {
                lastRemovedTransaction.value = transactionForRemove
            }
        }
    }

    fun undoRemoveSpent() {
        viewModelScope.launch {
            lastRemovedTransaction.value?.let {
                spendsRepository.addSpent(it)
            }
        }
    }

    // Other

    fun changeDisplayCurrency(currency: ExtendCurrency) {
        viewModelScope.launch {
            spendsRepository.changeDisplayCurrency(currency)
        }
    }

    fun changeRestedBudgetDistributionMethod(method: RestedBudgetDistributionMethod) {
        viewModelScope.launch {
            spendsRepository.changeRestedBudgetDistributionMethod(method)
        }
    }

    fun hideOverspendingWarn(hide: Boolean) {
        viewModelScope.launch {
            spendsRepository.hideOverspendingWarn(hide)
        }
    }

    // Need to be refactored

    fun howMuchBudgetRest(): LiveData<BigDecimal> {
        val data = MutableLiveData<BigDecimal>()

        viewModelScope.launch {
            data.value = spendsRepository.howMuchBudgetRest()
        }

        return data
    }

    // Background tasks
    private fun runChangeDayAction() {
        viewModelScope.launch {
            val lastChangeDailyBudgetDate = spendsRepository.getLastChangeDailyBudgetDate().first()
            val finishPeriodDate = spendsRepository.getFinishPeriodDate().first()
            val finishPeriodActualDate = spendsRepository.getFinishPeriodActualDate().first()
            val dailyBudget = spendsRepository.getDailyBudget().first()
            val spentFromDailyBudget = spendsRepository.getSpentFromDailyBudget().first()
            val restedBudgetDistributionMethod =
                spendsRepository.getRestedBudgetDistributionMethod().first()

            val finishDayNotReached = if (finishPeriodActualDate === null) {
                finishPeriodDate !== null
                        && countDaysToToday(finishPeriodDate) > 0
            } else {
                countDaysToToday(finishPeriodActualDate) > 0
            }

            val finishTimeReached = if (finishPeriodActualDate === null) {
                finishPeriodDate !== null
                        && finishPeriodDate.time <= Date().time
            } else {
                finishPeriodActualDate.time <= Date().time
            }

            when {
                lastChangeDailyBudgetDate !== null
                        && !isToday(lastChangeDailyBudgetDate)
                        && finishDayNotReached -> {
                    if (dailyBudget - spentFromDailyBudget > BigDecimal.ZERO) {
                        when (restedBudgetDistributionMethod) {
                            RestedBudgetDistributionMethod.ASK -> {
                                requireDistributionRestedBudget.value = true
                            }

                            RestedBudgetDistributionMethod.REST -> {
                                val whatBudgetForDay =
                                    spendsRepository.whatBudgetForDay(applyTodaySpends = true)
                                setDailyBudget(whatBudgetForDay)
                            }

                            RestedBudgetDistributionMethod.ADD_TODAY -> {
                                val notSpent = spendsRepository.howMuchNotSpent(
                                    excludeSkippedPart = true,
                                )

                                setDailyBudget(notSpent)
                            }
                        }
                    } else {
                        val whatBudgetForDay =
                            spendsRepository.whatBudgetForDay(applyTodaySpends = true)
                        setDailyBudget(whatBudgetForDay)
                    }
                }

                lastChangeDailyBudgetDate === null -> {
                    requireSetBudget.value = true
                }

                finishTimeReached -> {
                    periodFinished.value = true
                }
            }

            // Bug fix https://github.com/danilkinkin/buckwheat/issues/28
            if (dailyBudget - spentFromDailyBudget > BigDecimal.ZERO) {
                hideOverspendingWarn(false)
            }
        }
    }

    private fun runScheduledDetectChangeDayTask() {
        var currentDay = Date()

        viewModelScope.launch {
            while (true) {
                delay(5000L)

                if (isToday(currentDay)) continue

                currentDay = Date()
                runChangeDayAction()
            }
        }
    }
}
