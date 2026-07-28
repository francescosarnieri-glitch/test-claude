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
        SeenQuestionEntity::class,
    ],
    version = 5,
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
    abstract fun seenQuestionDao(): SeenQuestionDao

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

        /**
         * The study started opening gradually, and needed to remember what it had already
         * announced.
         *
         * The table arrives empty, which means a student who was already enrolled is told
         * once — on the first opening after the update — about everything that is open to
         * them. It is a single sentence, it is true, and it is better than the alternative:
         * pre-filling the table would have silently swallowed the announcement for the only
         * people who have actually earned it.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `seen_question` (" +
                        "`questionId` TEXT NOT NULL, " +
                        "`seenAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`questionId`))",
                )
            }
        }

        /**
         * Il tempo di studio smette di contare solo le lezioni.
         *
         * Parte da zero per tutti, ed e' la scelta giusta: inventare i secondi passati sulle
         * interrogazioni gia' fatte sarebbe stato riempire una colonna con un numero che
         * nessuno ha misurato. Il totale delle lezioni resta dov'e', quindi nessuno perde
         * quello che aveva.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE `stats` ADD COLUMN `studySeconds` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
    }
}
