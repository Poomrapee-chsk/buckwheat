package com.danilkinkin.buckwheat.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.danilkinkin.buckwheat.data.entities.SlipDetails

@Dao
interface SlipDetailsDao {
    @Query("SELECT * FROM slip_details")
    fun getAll(): LiveData<List<SlipDetails>>

    /** References of slips whose spend still exists, in any period */
    @Query(
        "SELECT d.reference FROM slip_details d JOIN transactions t ON t.uid = d.transactionUid " +
                "WHERE d.reference IS NOT NULL"
    )
    fun getUsedReferences(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(details: SlipDetails)

    /** Details whose spend was deleted */
    @Query("DELETE FROM slip_details WHERE transactionUid NOT IN (SELECT uid FROM transactions)")
    fun deleteOrphans()
}
