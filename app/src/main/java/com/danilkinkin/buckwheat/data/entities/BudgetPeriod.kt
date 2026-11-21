package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.Date

/**
 * A finished budget period that was archived when a new period was started.
 * Its transactions keep living in the `transactions` table, linked by [Transaction.periodId].
 */
@Entity(tableName = "periods")
data class BudgetPeriod(
    @ColumnInfo(name = "budget")
    val budget: BigDecimal,

    @ColumnInfo(name = "spent")
    val spent: BigDecimal,

    @ColumnInfo(name = "startDate")
    val startDate: Date,

    @ColumnInfo(name = "finishDate")
    val finishDate: Date,

    // Set only when the period was finished early. Stored as a raw timestamp
    // because the Date type converter does not accept nulls
    @ColumnInfo(name = "actualFinishDate")
    val actualFinishDate: Long? = null,

    // Raw currency value as it is kept in the budget data store
    @ColumnInfo(name = "currency")
    val currency: String? = null,
) {
    @PrimaryKey(autoGenerate = true) var uid: Int = 0
}
