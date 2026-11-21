package com.danilkinkin.buckwheat.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod

@Dao
abstract class PeriodDao {
    @Query("SELECT * FROM periods ORDER BY startDate DESC")
    abstract fun getAll(): LiveData<List<BudgetPeriod>>

    @Query("SELECT * FROM periods WHERE uid = :uid")
    abstract fun getById(uid: Int): LiveData<BudgetPeriod?>

    @Insert
    abstract fun insert(period: BudgetPeriod): Long

    @Query("UPDATE transactions SET periodId = :periodId WHERE periodId IS NULL")
    abstract fun attachCurrentTransactions(periodId: Int)

    /**
     * Saves the period and moves all transactions of the current period into it
     */
    @Transaction
    open fun archiveCurrentPeriod(period: BudgetPeriod): Int {
        val periodId = insert(period).toInt()
        attachCurrentTransactions(periodId)

        return periodId
    }
}
