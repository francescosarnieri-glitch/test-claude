package com.cybersensei.academy.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        StudentEntity::class,
        MasteryEntity::class,
        ReviewEntity::class,
        StudyEventEntity::class,
        LessonProgressEntity::class,
        StatsEntity::class,
        BadgeEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class SchoolDatabase : RoomDatabase() {
    abstract fun studentDao(): StudentDao
    abstract fun masteryDao(): MasteryDao
    abstract fun reviewDao(): ReviewDao
    abstract fun studyEventDao(): StudyEventDao
    abstract fun progressDao(): ProgressDao
    abstract fun statsDao(): StatsDao
    abstract fun badgeDao(): BadgeDao

    companion object {
        const val NAME = "cybersensei.db"

        /**
         * Badges arrived after the first students had already enrolled.
         *
         * Wiping the database would have been one line shorter and would have thrown away
         * someone's streak, their answers and everything the professor remembers about them.
         * A migration costs four lines and keeps all of it.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `badge` (" +
                        "`badgeId` TEXT NOT NULL, " +
                        "`earnedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`badgeId`))",
                )
            }
        }

        /**
         * The daily reminder, added when notifications arrived.
         *
         * Nullable and with no default on purpose: every student who was already enrolled
         * comes out of this migration with no reminder set, which is exactly the state they
         * were in before. A migration that switched something on for them would be a
         * migration that made a decision on their behalf.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `student` ADD COLUMN `reminderAt` TEXT")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
