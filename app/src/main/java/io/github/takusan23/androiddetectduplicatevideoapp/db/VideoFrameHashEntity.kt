package io.github.takusan23.androiddetectduplicatevideoapp.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * データベースのカラム
 *
 * @param id プライマリキーでオートインクリメント
 * @param videoUri 動画の Uri。文字列なので toUri() して
 * @param frameMs ハッシュを出した時点でのフレームの時間
 * @param aHash aHash。toULong() してください
 * @param dHash dHash。toULong() してください。
 */
@Entity(tableName = "video_frame_hash")
data class VideoFrameHashEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "video_uri") val videoUri: String,
    @ColumnInfo(name = "frame_ms") val frameMs: Long,
    @ColumnInfo(name = "a_hash") val aHash: Long,
    @ColumnInfo(name = "d_hash") val dHash: Long
)