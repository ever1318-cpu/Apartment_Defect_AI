package com.axlife.pinset.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.axlife.pinset.data.entity.DefectPhoto
import kotlinx.coroutines.flow.Flow

@Dao
interface DefectPhotoDao {
    @Insert
    suspend fun insert(photo: DefectPhoto): Long

    @Query("SELECT * FROM defect_photos WHERE defectId = :defectId")
    fun observeByDefect(defectId: Long): Flow<List<DefectPhoto>>

    @Query("SELECT * FROM defect_photos WHERE defectId = :defectId")
    suspend fun getByDefect(defectId: Long): List<DefectPhoto>

    @Query("SELECT * FROM defect_photos ORDER BY id")
    suspend fun getAll(): List<DefectPhoto>

    @Update
    suspend fun update(photo: DefectPhoto)
}
