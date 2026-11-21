package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.Date

@Entity(tableName = "periods")
data class Period(
    @ColumnInfo(name = "budget")
    val budget: BigDecimal,

    @ColumnInfo(name = "startDate")
    val startDate: Date,

    @ColumnInfo(name = "finishDate")
    val finishDate: Date,

    @ColumnInfo(name = "actualFinishDate")
    val actualFinishDate: Date? = null,

    // Serialized ExtendCurrency (the `value` string, empty when none).
    // Rebuild with ExtendCurrency.getInstance(currency).
    @ColumnInfo(name = "currency", defaultValue = "")
    val currency: String = "",

    @ColumnInfo(name = "archivedAt")
    val archivedAt: Date,
) {
    @PrimaryKey(autoGenerate = true)
    var uid: Int = 0
}
