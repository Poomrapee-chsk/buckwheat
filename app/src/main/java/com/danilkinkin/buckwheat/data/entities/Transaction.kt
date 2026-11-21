package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.*

enum class TransactionType {
    SET_DAILY_BUDGET,
    INCOME,
    SPENT
}

@Entity(tableName = "transactions")
data class Transaction(
    @ColumnInfo(name = "type")
    val type: TransactionType,

    @ColumnInfo(name = "value")
    val value: BigDecimal,

    @ColumnInfo(name = "date")
    val date: Date,

    @ColumnInfo(name = "comment", defaultValue = "")
    val comment: String = "",

    @ColumnInfo(defaultValue = "0")
    val manualTreat: Boolean = false,

    @ColumnInfo(defaultValue = "0")
    val automaticTreat: Boolean = false,

    // null = belongs to the current (live) budget period.
    // Set to a Period.uid when the period is archived into history.
    @ColumnInfo(name = "periodId")
    val periodId: Int? = null,
) {
    @PrimaryKey(autoGenerate = true) var uid: Int = 0
}