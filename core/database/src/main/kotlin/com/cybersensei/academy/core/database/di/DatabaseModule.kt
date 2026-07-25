package com.cybersensei.academy.core.database.di

import android.content.Context
import androidx.room.Room
import com.cybersensei.academy.core.database.MasteryDao
import com.cybersensei.academy.core.database.ProgressDao
import com.cybersensei.academy.core.database.ReviewDao
import com.cybersensei.academy.core.database.SchoolDatabase
import com.cybersensei.academy.core.database.StatsDao
import com.cybersensei.academy.core.database.StudentDao
import com.cybersensei.academy.core.database.StudyEventDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SchoolDatabase =
        Room.databaseBuilder(context, SchoolDatabase::class.java, SchoolDatabase.NAME).build()

    @Provides
    fun provideStudentDao(database: SchoolDatabase): StudentDao = database.studentDao()

    @Provides
    fun provideMasteryDao(database: SchoolDatabase): MasteryDao = database.masteryDao()

    @Provides
    fun provideReviewDao(database: SchoolDatabase): ReviewDao = database.reviewDao()

    @Provides
    fun provideStudyEventDao(database: SchoolDatabase): StudyEventDao = database.studyEventDao()

    @Provides
    fun provideProgressDao(database: SchoolDatabase): ProgressDao = database.progressDao()

    @Provides
    fun provideStatsDao(database: SchoolDatabase): StatsDao = database.statsDao()
}
