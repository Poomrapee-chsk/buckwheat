package com.danilkinkin.buckwheat.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE periodId IS NULL ORDER BY date ASC")
    fun getAll(): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE periodId IS NULL AND type = :type ORDER BY date ASC")
    fun getAll(type: TransactionType): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE periodId = :periodId ORDER BY date ASC")
    fun getAllByPeriod(periodId: Int): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE periodId = :periodId AND type = :type ORDER BY date ASC")
    fun getAllByPeriod(periodId: Int, type: TransactionType): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE periodId = :periodId ORDER BY date ASC")
    fun getAllByPeriodSync(periodId: Int): List<Transaction>

    @Query("SELECT * FROM transactions WHERE periodId IS NULL")
    fun getCurrentPeriodTransactions(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE uid = :uid")
    fun getById(uid: Int): Transaction?

    @Query("SELECT * FROM transactions WHERE type = 'SPENT' ORDER BY date ASC")
    fun getSpendingHistory(): LiveData<List<Transaction>>

    @Query("SELECT t.* FROM transactions t LEFT JOIN periods p ON t.periodId = p.uid WHERE t.type = 'SPENT' AND t.date >= :start AND t.date < :end AND t.manualTreat = 0 AND t.uid != :excludeId AND (t.periodId IS NULL OR p.currency = :currency)")
    fun getTreatBaseline(start: Long, end: Long, excludeId: Int, currency: String): List<Transaction>

    @Insert
    fun insert(vararg transaction: Transaction)

    @Update(entity = Transaction::class, onConflict = OnConflictStrategy.REPLACE)
    fun update(vararg transaction: Transaction)

    @Query("UPDATE transactions SET periodId = :periodId WHERE periodId IS NULL")
    fun assignCurrentPeriodTo(periodId: Int)

    @Query("DELETE FROM transactions WHERE uid = :uid")
    fun deleteById(uid: Int)

    @Query("DELETE FROM transactions WHERE periodId IS NULL")
    fun deleteCurrentPeriod()

    @Query("DELETE FROM transactions WHERE periodId = :periodId")
    fun deleteByPeriod(periodId: Int)

    @Query("DELETE FROM transactions")
    fun deleteAll()
}
