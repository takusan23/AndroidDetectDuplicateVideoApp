package io.github.takusan23.androiddetectduplicatevideoapp.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** データベースにアクセスする関数たち */
@Dao
interface VideoFrameHashDao {

    @Query("SELECT * FROM video_frame_hash")
    suspend fun getAllData(): List<VideoFrameHashEntity>

    @Query("SELECT COUNT(DISTINCT video_uri) FROM video_frame_hash")
    fun latestAnalyzedUriCount(): Flow<Int>

    @Query("DELETE FROM video_frame_hash")
    suspend fun deleteAll()

    @Query("DELETE FROM video_frame_hash WHERE video_uri = :uri")
    suspend fun deleteUri(uri: String)

    @Insert
    suspend fun insertAll(vararg videoFrameHashEntities: VideoFrameHashEntity)
}