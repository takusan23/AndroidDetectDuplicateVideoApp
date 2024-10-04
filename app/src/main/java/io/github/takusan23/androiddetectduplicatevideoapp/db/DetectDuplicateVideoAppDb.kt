package io.github.takusan23.androiddetectduplicatevideoapp.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** 端末内データベース SQLite */
@Database(entities = [VideoFrameHashEntity::class], version = 1)
abstract class DetectDuplicateVideoAppDb : RoomDatabase() {
    abstract fun getDao(): VideoFrameHashDao

    companion object {

        private var _instance: DetectDuplicateVideoAppDb? = null

        /** インスタンスを取得する。シングルトン */
        fun getInstance(context: Context): DetectDuplicateVideoAppDb {
            if (_instance == null) {
                _instance = Room.databaseBuilder(
                    context,
                    DetectDuplicateVideoAppDb::class.java,
                    "detect_duplicate_videoapp.db"
                ).build()
            }
            return _instance!!
        }
    }
}