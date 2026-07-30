package com.cybersensei.academy.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StudentDao {
    @Query("SELECT * FROM student WHERE id = :id")
    fun observe(id: Int = StudentEntity.SINGLE_ROW): Flow<StudentEntity?>

    @Query("SELECT * FROM student WHERE id = :id")
    suspend fun get(id: Int = StudentEntity.SINGLE_ROW): StudentEntity?

    @Upsert
    suspend fun save(student: StudentEntity)

    @Query("DELETE FROM student")
    suspend fun clear()
}

@Dao
interface MasteryDao {
    @Query("SELECT * FROM mastery")
    suspend fun all(): List<MasteryEntity>

    @Query("SELECT * FROM mastery")
    fun observeAll(): Flow<List<MasteryEntity>>

    @Query("SELECT * FROM mastery WHERE skillId = :skillId")
    suspend fun get(skillId: String): MasteryEntity?

    @Query("SELECT * FROM mastery WHERE skillId IN (:skillIds)")
    suspend fun forSkills(skillIds: List<String>): List<MasteryEntity>

    @Upsert
    suspend fun save(mastery: MasteryEntity)

    @Query("DELETE FROM mastery")
    suspend fun clear()
}

@Dao
interface ReviewDao {
    @Query("SELECT * FROM review")
    suspend fun all(): List<ReviewEntity>

    @Query("SELECT * FROM review WHERE dueOn <= :today")
    suspend fun dueOn(today: String): List<ReviewEntity>

    @Query("SELECT COUNT(*) FROM review WHERE dueOn <= :today")
    fun observeDueCount(today: String): Flow<Int>

    @Query("SELECT * FROM review WHERE skillId = :skillId")
    suspend fun get(skillId: String): ReviewEntity?

    @Upsert
    suspend fun save(review: ReviewEntity)

    @Query("DELETE FROM review")
    suspend fun clear()
}

@Dao
interface StudyEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: StudyEventEntity)

    /** Newest first from the database, reversed by the repository into diary order. */
    @Query("SELECT * FROM study_event ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int = 500): List<StudyEventEntity>

    @Query("DELETE FROM study_event WHERE id NOT IN (SELECT id FROM study_event ORDER BY at DESC, id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM study_event")
    suspend fun clear()
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM lesson_progress")
    suspend fun all(): List<LessonProgressEntity>

    @Query("SELECT * FROM lesson_progress")
    fun observeAll(): Flow<List<LessonProgressEntity>>

    @Query("SELECT * FROM lesson_progress WHERE lessonId = :lessonId")
    suspend fun get(lessonId: String): LessonProgressEntity?

    @Upsert
    suspend fun save(progress: LessonProgressEntity)

    @Query("DELETE FROM lesson_progress")
    suspend fun clear()
}

@Dao
interface StatsDao {
    @Query("SELECT * FROM stats WHERE id = :id")
    suspend fun get(id: Int = StudentEntity.SINGLE_ROW): StatsEntity?

    @Query("SELECT * FROM stats WHERE id = :id")
    fun observe(id: Int = StudentEntity.SINGLE_ROW): Flow<StatsEntity?>

    @Upsert
    suspend fun save(stats: StatsEntity)

    @Query("DELETE FROM stats")
    suspend fun clear()
}

@Dao
interface TrophyDao {
    @Query("SELECT * FROM badge ORDER BY earnedAt")
    suspend fun all(): List<TrophyEntity>

    @Query("SELECT * FROM badge ORDER BY earnedAt")
    fun observeAll(): Flow<List<TrophyEntity>>

    @Upsert
    suspend fun save(trophy: TrophyEntity)

    @Query("DELETE FROM badge")
    suspend fun clear()
}

/** Which questions the student has already been shown as open. */
@Dao
interface SeenQuestionDao {
    @Query("SELECT questionId FROM seen_question")
    suspend fun all(): List<String>

    @Upsert
    suspend fun save(seen: List<SeenQuestionEntity>)

    @Query("DELETE FROM seen_question")
    suspend fun clear()
}
