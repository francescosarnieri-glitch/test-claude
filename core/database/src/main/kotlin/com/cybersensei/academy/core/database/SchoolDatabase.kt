package com.cybersensei.academy.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        StudentEntity::class,
        MasteryEntity::class,
        ReviewEntity::class,
        StudyEventEntity::class,
        LessonProgressEntity::class,
        StatsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SchoolDatabase : RoomDatabase() {
    abstract fun studentDao(): StudentDao
    abstract fun masteryDao(): MasteryDao
    abstract fun reviewDao(): ReviewDao
    abstract fun studyEventDao(): StudyEventDao
    abstract fun progressDao(): ProgressDao
    abstract fun statsDao(): StatsDao

    companion object {
        const val NAME = "cybersensei.db"
    }
}
