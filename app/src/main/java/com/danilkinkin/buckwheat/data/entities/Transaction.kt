package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.*

enum class TransactionType {
    SET_DAILY_BUDGET,
    INCOME,
    SPENT
}

@Entity(tableName = "transactions", indices = [Index(value = ["periodId"])])
data class Transaction(
    @ColumnInfo(name = "type")
    val type: TransactionType,

    @ColumnInfo(name = "value")
    val value: BigDecimal,

    @ColumnInfo(name = "date")
    val date: Date,

    @ColumnInfo(name = "comment", defaultValue = "")
    val comment: String = "",

    // Null for transactions of the current period, otherwise the uid of the archived period
    @ColumnInfo(name = "periodId")
    val periodId: Int? = null,
) {
    @PrimaryKey(autoGenerate = true) var uid: Int = 0
}