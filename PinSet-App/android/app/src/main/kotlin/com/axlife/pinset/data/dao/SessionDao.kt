package com.axlife.pinset.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.axlife.pinset.data.entity.Session
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: Session): Long

    @androidx.room.Update
    suspend fun update(session: Session)

    @androidx.room.Delete
    suspend fun delete(session: Session)

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC LIMIT 1")
    fun currentSession(): Flow<Session?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): Session?

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Session>>
}
