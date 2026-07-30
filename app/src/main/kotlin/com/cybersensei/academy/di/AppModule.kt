package com.cybersensei.academy.di

import com.cybersensei.academy.StartupProblems
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.curriculum.Curriculum
import com.cybersensei.academy.core.curriculum.TrophyEngine
import com.cybersensei.academy.engine.mastery.MasteryEngine
import com.cybersensei.academy.engine.nlu.FaqContent
import com.cybersensei.academy.engine.nlu.KnowledgeBase
import com.cybersensei.academy.engine.nlu.StudyAvailability
import com.cybersensei.academy.engine.nlu.StudyPaths
import com.cybersensei.academy.engine.regole.Palestra
import com.cybersensei.academy.engine.scenario.ScenarioLibrary
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

    /** Trophy definitions, content like everything else the student reads. */
    @Provides
    @Singleton
    fun provideTrophyEngine(): TrophyEngine = runCatching { TrophyEngine.fromResources() }
        .getOrElse { error ->
            StartupProblems.record("Elenco dei trofei", error)
            TrophyEngine(emptyList())
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

    /** The routes through the study: content, like the questions they lead to. */
    @Provides
    @Singleton
    fun provideStudyPaths(): StudyPaths = runCatching { StudyPaths.fromResources() }
        .getOrElse { error ->
            StartupProblems.record("Percorsi dello studio", error)
            StudyPaths(emptyList())
        }

    /**
     * The rule that decides what the student can ask.
     *
     * No syllabus needed any more: what opens an answer is having sat its interrogation, and
     * that is written in the student's own record.
     */
    @Provides
    @Singleton
    fun provideStudyAvailability(): StudyAvailability = StudyAvailability()

    /**
     * Every case the school can put in front of the student, content like everything else.
     *
     * A failure here loses the cases and nothing more: the rest of the school still opens,
     * and the path says so instead of closing before anything is drawn.
     */
    @Provides
    @Singleton
    fun provideScenarioLibrary(): ScenarioLibrary =
        runCatching { ScenarioLibrary.fromResources() }
            .getOrElse { error ->
                StartupProblems.record("Casi da risolvere", error)
                ScenarioLibrary(emptyList())
            }

    /**
     * The rule-writing exercises and the logs they are set on.
     *
     * A failure here loses the workshop and nothing else: the rest of the school still opens,
     * and the path says so instead of closing before anything is drawn.
     */
    @Provides
    @Singleton
    fun providePalestra(): Palestra = runCatching { Palestra.fromResources() }
        .getOrElse { error ->
            StartupProblems.record("Esercizi del tirocinio", error)
            Palestra(emptyList(), emptyList())
        }

    @Provides
    @Singleton
    fun provideMasteryEngine(): MasteryEngine = MasteryEngine()

    @Provides
    @Singleton
    fun provideReviewScheduler(): ReviewScheduler = ReviewScheduler()
}
