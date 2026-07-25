package com.cybersensei.academy.di

import com.cybersensei.academy.core.common.SystemTimeProvider
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.engine.mastery.MasteryEngine
import com.cybersensei.academy.engine.nlu.KnowledgeBase
import com.cybersensei.academy.engine.nlu.QuestionAnswerer
import com.cybersensei.academy.engine.scheduler.ReviewScheduler
import com.cybersensei.academy.engine.tutor.DialogueLibrary
import com.cybersensei.academy.engine.tutor.TutorEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideTimeProvider(): TimeProvider = SystemTimeProvider()

    /** The professor's script, read once from the resources packaged in the APK. */
    @Provides
    @Singleton
    fun provideDialogueLibrary(): DialogueLibrary = DialogueLibrary.fromResources()

    @Provides
    @Singleton
    fun provideTutorEngine(
        library: DialogueLibrary,
        timeProvider: TimeProvider,
    ): TutorEngine = TutorEngine(library, timeProvider)

    @Provides
    @Singleton
    fun provideKnowledgeBase(): KnowledgeBase = KnowledgeBase.fromResources()

    @Provides
    @Singleton
    fun provideQuestionAnswerer(knowledgeBase: KnowledgeBase): QuestionAnswerer =
        QuestionAnswerer(knowledgeBase)

    @Provides
    @Singleton
    fun provideMasteryEngine(): MasteryEngine = MasteryEngine()

    @Provides
    @Singleton
    fun provideReviewScheduler(): ReviewScheduler = ReviewScheduler()
}
