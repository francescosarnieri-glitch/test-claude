package com.cybersensei.academy.di

import com.cybersensei.academy.StartupProblems
import com.cybersensei.academy.core.common.SystemTimeProvider
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.BadgeEngine
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.engine.mastery.MasteryEngine
import com.cybersensei.academy.engine.nlu.FaqContent
import com.cybersensei.academy.engine.nlu.KnowledgeBase
import com.cybersensei.academy.engine.nlu.QuestionAnswerer
import com.cybersensei.academy.engine.scenario.Debriefing
import com.cybersensei.academy.engine.scenario.Scenario
import com.cybersensei.academy.engine.scheduler.ReviewScheduler
import com.cybersensei.academy.engine.tutor.DialogueContent
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

    /**
     * The professor's script, read once from the resources packaged in the APK.
     *
     * A failure here must not be fatal: the app opens with an empty script and says so,
     * which is infinitely more useful than closing before anything is drawn.
     */
    @Provides
    @Singleton
    fun provideDialogueLibrary(): DialogueLibrary = runCatching { DialogueLibrary.fromResources() }
        .getOrElse { error ->
            StartupProblems.record("Copione del professore", error)
            DialogueLibrary(DialogueContent(pools = emptyList(), rules = emptyList()))
        }

    /** Badge definitions, content like everything else the student reads. */
    @Provides
    @Singleton
    fun provideBadgeEngine(): BadgeEngine = runCatching { BadgeEngine.fromResources() }
        .getOrElse { error ->
            StartupProblems.record("Elenco dei badge", error)
            BadgeEngine(emptyList())
        }

    /** The syllabus: lessons and questions, likewise read from the packaged content. */
    @Provides
    @Singleton
    fun provideCurriculum(): Curriculum = runCatching { Curriculum.fromResources() }
        .getOrElse { error ->
            StartupProblems.record("Programma didattico", error)
            Curriculum(levels = emptyList())
        }

    @Provides
    @Singleton
    fun provideTutorEngine(
        library: DialogueLibrary,
        timeProvider: TimeProvider,
    ): TutorEngine = TutorEngine(library, timeProvider)

    @Provides
    @Singleton
    fun provideKnowledgeBase(): KnowledgeBase = runCatching { KnowledgeBase.fromResources() }
        .getOrElse { error ->
            StartupProblems.record("Domande frequenti", error)
            KnowledgeBase(FaqContent(entries = emptyList()))
        }

    @Provides
    @Singleton
    fun provideQuestionAnswerer(knowledgeBase: KnowledgeBase): QuestionAnswerer =
        QuestionAnswerer(knowledgeBase)

    /** The capstone scenario: a branching script, content like everything else. */
    @Provides
    @Singleton
    fun provideScenario(): Scenario = runCatching { Scenario.fromResources() }
        .getOrElse { error ->
            StartupProblems.record("Scenario del capstone", error)
            Scenario(
                id = "assente",
                title = "L'Incidente",
                subtitle = "Lo scenario non è stato caricato",
                briefing = "Non riesco ad aprire il copione di questa esercitazione.",
                minutes = 0,
                startSceneId = "",
                scenes = emptyList(),
                debriefing = Debriefing(opening = "", bands = emptyList(), closing = ""),
            )
        }

    @Provides
    @Singleton
    fun provideMasteryEngine(): MasteryEngine = MasteryEngine()

    @Provides
    @Singleton
    fun provideReviewScheduler(): ReviewScheduler = ReviewScheduler()
}
