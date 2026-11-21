package com.danilkinkin.buckwheat.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.danilkinkin.buckwheat.data.entities.Period

@Dao
interface PeriodDao {
    @Query("SELECT * FROM periods ORDER BY finishDate DESC, uid DESC")
    fun getAll(): LiveData<List<Period>>

    @Query("SELECT * FROM periods")
    fun getAllSync(): List<Period>

    @Query("SELECT COUNT(*) FROM periods")
    fun count(): Int

    @Query("SELECT * FROM periods WHERE uid = :uid")
    fun getById(uid: Int): Period?

    @Insert
    fun insert(period: Period): Long

    @Query("DELETE FROM periods WHERE uid = :uid")
    fun deleteById(uid: Int)
}
