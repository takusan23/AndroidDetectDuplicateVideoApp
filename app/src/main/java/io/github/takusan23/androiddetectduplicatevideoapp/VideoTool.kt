package io.github.takusan23.androiddetectduplicatevideoapp

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object VideoTool {

    /**
     * 全ての動画を取得する
     *
     * @param context [Context]
     */
    suspend fun queryAllVideoIdList(
        context: Context
    ) = withContext(Dispatchers.IO) {
        context.contentResolver.query(
            // 保存先、SDカードとかもあったはず
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            // 今回はメディアの ID を取得。他にもタイトルとかあります
            // 実際のデータの取得には、まず ID を取得する必要があります
            arrayOf(MediaStore.MediaColumns._ID),
            // SQL の WHERE。ユーザー入力が伴う場合はプレースホルダーを使いましょう
            null,
            // SQL の WHERE のプレースホルダーの値
            null,
            // SQL の ORDER BY
            null
        )?.use { cursor ->
            // 一応最初に移動しておく
            cursor.moveToFirst()
            // 配列を返す
            (0 until cursor.count)
                .map {
                    // ID 取得
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    // 次のレコードに移動
                    cursor.moveToNext()
                    // 返す
                    id
                }
        } ?: emptyList()
    }

    /** ID から Uri を取る */
    fun getVideoUri(id: Long) = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

    /** Uri からファイル名を取得する */
    suspend fun getFileName(
        context: Context,
        uri: Uri
    ) = withContext(Dispatchers.IO) {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            cursor.moveToFirst()
            cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
        }
    }
}