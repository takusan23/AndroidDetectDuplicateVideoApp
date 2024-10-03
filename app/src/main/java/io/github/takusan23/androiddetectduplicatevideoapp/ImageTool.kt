package io.github.takusan23.androiddetectduplicatevideoapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import androidx.core.graphics.blue
import androidx.core.graphics.get
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageTool {

    /**
     * [Bitmap]を取得する
     * TODO 大量に読み込む場合は Glide を使ってサイズを指定してから読み込む方が良いです。自前で書くの良くない
     *
     * @param context [Context]
     * @param uri PhotoPicker 等で取得した[Uri]
     * @return [Bitmap]
     */
    suspend fun loadBitmap(
        context: Context,
        uri: Uri
    ) = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream)
        }
    }

    /**
     * dHash を求める
     * https://www.hackerfactor.com/blog/index.php?/archives/529-Kind-of-Like-That.html
     */
    suspend fun calcDHash(bitmap: Bitmap) = withContext(Dispatchers.Default) {
        // 幅 9、高さ 8 の Bitmap にリサイズする
        val scaledBitmap = bitmap.scale(width = 9, height = 8)
        // モノクロにする
        val monochromeBitmap = scaledBitmap.toMonoChrome()
        // 縦 8、横 8 のループを回す
        // 8x8 なので結果は 64 ビットになる。ULong で格納できる
        // 幅 9 なのに 8 なのは、今のピクセルと右隣のピクセルの色と比較するため。比較して隣が大きければ 1 を立てる
        // ビットの立て方は以下に従う
        // 左上[0,0]から開始し、一番右まで読み取る。[0,7]
        // 一番右まで読み取ったらひとつ下に下がってまた読み出す[1,0]
        // ビッグエンディアンを採用するので、一番右のビットが[0,0]の結果になります
        var resultBit = 0UL
        var bitCount = 63
        repeat(8) { y ->
            repeat(8) { x ->
                val currentColor = monochromeBitmap[x, y]
                val currentRightColor = monochromeBitmap[x + 1, y]
                // ビットを立てる
                if (currentColor < currentRightColor) {
                    resultBit = resultBit or (1UL shl bitCount)
                }
                bitCount--
            }
        }
        return@withContext resultBit
    }

    /** aHash を求める */
    suspend fun calcAHash(bitmap: Bitmap) = withContext(Dispatchers.Default) {
        // 幅 8、高さ 8 の Bitmap にリサイズする
        val scaledBitmap = bitmap.scale(width = 8, height = 8)
        // モノクロにする
        val monochromeBitmap = scaledBitmap.toMonoChrome()
        // 色の平均を出す
        var totalRed = 0
        var totalGreen = 0
        var totalBlue = 0
        repeat(8) { y ->
            repeat(8) { x ->
                val color = monochromeBitmap[x, y]
                totalRed += color.red
                totalGreen += color.green
                totalBlue += color.blue
            }
        }
        val averageColor = Color.rgb(totalRed / 64, totalGreen / 64, totalBlue / 64)
        // 縦 8、横 8 のループを回す
        // 8x8 なので結果は 64 ビットになる。ULong で格納できる
        // 各ピクセルと平均を比較して、平均よりも大きい場合は 1 を立てる
        // ビットの立て方は以下に従う
        // 左上[0,0]から開始し、一番右まで読み取る。[0,7]
        // 一番右まで読み取ったらひとつ下に下がってまた読み出す[1,0]
        // ビッグエンディアンを採用するので、一番右のビットが[0,0]の結果になります
        var resultBit = 0UL
        var bitCount = 63
        repeat(8) { y ->
            repeat(8) { x ->
                val currentColor = monochromeBitmap[x, y]
                // ビットを立てる
                if (averageColor < currentColor) {
                    resultBit = resultBit or (1UL shl bitCount)
                }
                bitCount--
            }
        }
        return@withContext resultBit
    }

    /** XOR してビットを数えて 0 から 1 の範囲でどれだけ似ているかを返す */
    fun compare(a: ULong, b: ULong): Float {
        val xorResult = a xor b
        val bitCount = xorResult.countOneBits()
        // 64 はハッシュ値が 64 ビットなので
        return (64 - bitCount) / 64f
    }

    /** [Bitmap]をモノクロにする */
    private fun Bitmap.toMonoChrome(): Bitmap {
        val bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmpGrayscale)
        val paint = Paint()
        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(0f)
        val filter = ColorMatrixColorFilter(colorMatrix)
        paint.setColorFilter(filter)
        canvas.drawBitmap(this, 0f, 0f, paint)
        return bmpGrayscale
    }

}