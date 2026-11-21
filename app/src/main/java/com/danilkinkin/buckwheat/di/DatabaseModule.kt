package com.danilkinkin.buckwheat.di

import androidx.room.*
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.danilkinkin.buckwheat.data.dao.PeriodDao
import com.danilkinkin.buckwheat.data.dao.StorageDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.Period
import com.danilkinkin.buckwheat.data.entities.Storage
import com.danilkinkin.buckwheat.data.entities.Transaction


class AutoMigration1to2 : AutoMigrationSpec

@DeleteColumn.Entries(
    DeleteColumn(
        tableName = "Spent",
        columnName = "deleted"
    )
)
class AutoMigration2to3 : AutoMigrationSpec

// Preparing for remove storage table
class AutoMigration3to4 : AutoMigrationSpec

// Rename Spent to Transaction
val AutoMigration4to5: Migration = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Create the new "transactions" table
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `transactions` " +
                    "(`type` TEXT NOT NULL, " +
                    "`value` TEXT NOT NULL, " +
                    "`date` INTEGER NOT NULL, " +
                    "`comment` TEXT NOT NULL DEFAULT '', " +
                    "`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)"
        )

        // Copy data from the old "Spent" table to the new "transactions" table
        database.execSQL(
            "INSERT INTO `transactions` (`type`, `value`, `date`, `comment`) " +
                    "SELECT 'SPENT', `value`, `date`, `comment` FROM `Spent`"
        )

        // Drop the old "Spent" table
        database.execSQL("DROP TABLE IF EXISTS `Spent`")
    }
}

// Add budget periods history:
//  - new "periods" table
//  - nullable "periodId" column on "transactions" (NULL = current live period)
val AutoMigration5to6: Migration = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `transactions` ADD COLUMN `periodId` INTEGER")
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `periods` " +
                    "(`budget` TEXT NOT NULL, " +
                    "`startDate` INTEGER NOT NULL, " +
                    "`finishDate` INTEGER NOT NULL, " +
                    "`actualFinishDate` INTEGER, " +
                    "`currency` TEXT NOT NULL DEFAULT '', " +
                    "`archivedAt` INTEGER NOT NULL, " +
                    "`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)"
        )
    }
}

val Migration6to7: Migration = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE transactions ADD COLUMN manualTreat INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE transactions ADD COLUMN automaticTreat INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [Transaction::class, Storage::class, Period::class],
    version = 7,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = AutoMigration1to2::class),
        AutoMigration(from = 2, to = 3, spec = AutoMigration2to3::class),
        AutoMigration(from = 3, to = 4, spec = AutoMigration3to4::class),
    ],
    exportSchema = true
)
@TypeConverters(RoomConverters::class)
abstract class DatabaseModule : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao

    abstract fun storageDao(): StorageDao

    abstract fun periodDao(): PeriodDao

    companion object {
        val MANUAL_MIGRATIONS = arrayOf(AutoMigration4to5, AutoMigration5to6, Migration6to7)
    }
}
