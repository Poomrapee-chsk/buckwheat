package com.danilkinkin.buckwheat.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.di.SpendsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ArchiveViewModel @Inject constructor(
    private val spendsRepository: SpendsRepository,
) : ViewModel() {
    val periods: LiveData<List<BudgetPeriod>> = spendsRepository.getArchivedPeriods()

    fun getPeriod(uid: Int): LiveData<BudgetPeriod?> = spendsRepository.getArchivedPeriod(uid)

    fun getTransactions(uid: Int): LiveData<List<Transaction>> =
        spendsRepository.getTransactionsOfArchivedPeriod(uid)
}
