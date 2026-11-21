package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * What was read from the payment slip a spend was imported from. Spends added by hand have none.
 *
 * Linked to [Transaction.uid] without a foreign key: editing a spend deletes and re-inserts it
 * with the same uid, which a cascading key would wipe. Transaction uids are AUTOINCREMENT and never
 * reused, so details left behind by deleted spends are harmless and cleaned up now and then.
 */
@Entity(
    tableName = "slip_details",
    indices = [
        Index(value = ["transactionUid"], unique = true),
        Index(value = ["reference"]),
    ],
)
data class SlipDetails(
    @ColumnInfo(name = "transactionUid")
    val transactionUid: Int,

    /** Shop or person paid */
    @ColumnInfo(name = "payee")
    val payee: String? = null,

    /** Bank transaction reference, unique per transfer: exact duplicate detection */
    @ColumnInfo(name = "reference")
    val reference: String? = null,

    /** Memo typed in the bank app */
    @ColumnInfo(name = "note")
    val note: String? = null,

    /** Everything OCR read, so slips can be parsed again as the parser improves */
    @ColumnInfo(name = "rawText", defaultValue = "")
    val rawText: String = "",

    /** MediaStore id of the slip image, when it came from a watched folder */
    @ColumnInfo(name = "mediaId")
    val mediaId: Long? = null,
) {
    @PrimaryKey(autoGenerate = true)
    var uid: Int = 0
}
