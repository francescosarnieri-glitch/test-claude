package com.cybersensei.academy.di

import com.cybersensei.academy.core.common.SystemTimeProvider
import com.cybersensei.academy.core.common.TimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The clock, alone in its own module.
 *
 * Separated from [AppModule] so a test can replace time without also having to replace the
 * syllabus, the professor's script and everything else. Anything that only happens after a
 * day has passed — streaks, absences, a review falling due — is untestable otherwise.
 */
@Module
@InstallIn(SingletonComponent::class)
object TimeModule {

    @Provides
    @Singleton
    fun provideTimeProvider(): TimeProvider = SystemTimeProvider()
}
