package io.github.takusan23.androiddetectduplicatevideoapp

import android.app.Activity
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.takusan23.akaricore.common.toAkariCoreInputOutputData
import io.github.takusan23.akaricore.video.VideoFrameBitmapExtractor
import io.github.takusan23.androiddetectduplicatevideoapp.ui.theme.AndroidDetectDuplicateVideoAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidDetectDuplicateVideoAppTheme {
                MainScreen()
            }
        }
    }
}

/** 動画Uriとフレームとハッシュ */
private data class VideoFrameHashData(
    val videoUri: Uri,
    val durationMs: Long,
    val aHash: ULong,
    val dHash: ULong
)

/** 動画と、似ているフレームがある動画 */
private data class DuplicateVideoData(
    val videoUri: Uri,
    val maybeDuplicateUriList: List<Uri>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val permissionRequest = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                Toast.makeText(context, "権限を付与しました", Toast.LENGTH_SHORT).show()
            }
        }
    )

    val totalVideoCount = remember { mutableIntStateOf(0) }
    val processCount = remember { mutableIntStateOf(0) }
    val duplicateVideoList = remember { mutableStateOf(emptyList<DuplicateVideoData>()) }

    fun analyze() {
        scope.launch(Dispatchers.Default) {
            val videoUriList = VideoTool.queryAllVideoIdList(context)
                .map { VideoTool.getVideoUri(it) }
            totalVideoCount.intValue = videoUriList.size

            // リストに同時にでアクセスさせないように mutex
            val listLock = Mutex()
            // 並列処理数を制限。ハードウェアデコーダーは同時起動上限がある。多分16個くらい
            val semaphore = Semaphore(16)
            // 処理結果
            val videoFrameHashDataList = arrayListOf<VideoFrameHashData>()

            // TODO 時間がかかりすぎるので上限
            videoUriList.take(500).map { uri ->
                println("start $uri")

                // 並列ではしらす
                launch {
                    semaphore.withPermit {

                        // 動画時間取る
                        // TODO 長すぎるとデバッグするのが面倒なので適当に10秒で
                        val videoDurationMs = minOf(
                            10_000,
                            runCatching {
                                MediaMetadataRetriever().apply {
                                    context.contentResolver.openFileDescriptor(uri, "r")?.use {
                                        setDataSource(it.fileDescriptor)
                                    }
                                }.use { it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()!! }
                            }.getOrNull() ?: return@withPermit
                        )

                        // 1 秒ごとにフレームを取り出す
                        val frameMsList = (0 until videoDurationMs step 1_000).toList()
                        val frameBitmapExtractor = VideoFrameBitmapExtractor()
                        try {
                            frameBitmapExtractor.prepareDecoder(uri.toAkariCoreInputOutputData(context))
                            frameMsList.forEach { frameMs ->
                                // println("current $frameMs $uri")
                                frameBitmapExtractor.getVideoFrameBitmap(frameMs)?.also { bitmap ->
                                    // ハッシュを出してリストに追加
                                    val aHash = ImageTool.calcAHash(bitmap)
                                    val dHash = ImageTool.calcDHash(bitmap)
                                    listLock.withLock {
                                        videoFrameHashDataList += VideoFrameHashData(uri, frameMs, aHash, dHash)
                                    }
                                }
                            }
                        } catch (e: IllegalArgumentException) {
                            // Failed to initialize video/av01, error 0xfffffffe ？
                            println("Decoder error $uri")
                        } catch (e: MediaCodec.CodecException) {
                            // 多分並列起動数が多いとエラーなる。同じ動画でも1つだけなら問題なかった
                            println("Decoder error $uri")
                        } finally {
                            println("complete $uri")
                            processCount.intValue++
                            frameBitmapExtractor.destroy()
                        }

                    }
                }

            }.joinAll()

            // しきい値
            val threshold = 0.95f
            // ImageHashList を可変長配列に。これは重複している画像が出てきら消すことで、後半になるにつれ走査回数が減るよう
            val maybeDuplicateDropFrameHashList = videoFrameHashDataList.toMutableList()
            // フレームを一枚ずつ見ていって、重複していたら消す
            while (isActive) {
                // 次のデータ。ループのたびに最初の要素を消すので、実質イテレータ。
                // ただ、重複していたらリストから Uri が消えるので、イテレータより回数は少ないはず
                val current = maybeDuplicateDropFrameHashList.removeFirstOrNull() ?: break
                // 自分以外
                val withoutTargetList = maybeDuplicateDropFrameHashList.filter { it.videoUri != current.videoUri }
                val maybeFromAHash = withoutTargetList.filter { threshold < ImageTool.compare(it.aHash, current.aHash) }
                val maybeFromDHash = withoutTargetList.filter { threshold < ImageTool.compare(it.dHash, current.dHash) }
                // aHash か dHash で重複していない場合は結果に入れない
                val totalResult = (maybeFromAHash.map { it.videoUri } + maybeFromDHash.map { it.videoUri }).distinct()
                println("totalResult = $totalResult")
                if (totalResult.isNotEmpty()) {
                    duplicateVideoList.value += DuplicateVideoData(
                        videoUri = current.videoUri,
                        maybeDuplicateUriList = totalResult
                    )
                }
                // 1回重複していることが分かったらもう消す（2回目以降検索にかけない）
                maybeDuplicateDropFrameHashList.removeAll { it.videoUri == current.videoUri }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(text = stringResource(id = R.string.app_name)) }) },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {

            Button(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionRequest.launch(android.Manifest.permission.READ_MEDIA_VIDEO)
                } else {
                    permissionRequest.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }) {
                Text(text = "権限を付与する")
            }

            Button(onClick = { analyze() }) {
                Text(text = "解析する")
            }

            Text(text = "総動画数 ${totalVideoCount.intValue} / 処理済み動画数 ${processCount.intValue}")

            LazyColumn {
                items(duplicateVideoList.value) { duplicate ->
                    Row {
                        VideoThumbnailImage(
                            modifier = Modifier.requiredSize(100.dp),
                            uri = duplicate.videoUri
                        )
                        Text(text = duplicate.videoUri.toString())
                    }
                    LazyRow {
                        items(duplicate.maybeDuplicateUriList) { maybeUri ->
                            VideoThumbnailImage(
                                modifier = Modifier.requiredSize(100.dp),
                                uri = maybeUri
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }

        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoThumbnailImage(
    modifier: Modifier = Modifier,
    uri: Uri
) {
    val context = LocalContext.current
    val thumbnailBitmap = remember { mutableStateOf<ImageBitmap?>(null) }
    val isShowBottomSheet = remember { mutableStateOf(false) }

    LaunchedEffect(key1 = uri) {
        withContext(Dispatchers.IO) {
            thumbnailBitmap.value = context.contentResolver.loadThumbnail(uri, Size(320, 320), null).asImageBitmap()
        }
    }

    // 削除とかできるボトムシート
    if (isShowBottomSheet.value) {
        ModalBottomSheet(onDismissRequest = { isShowBottomSheet.value = false }) {
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            ) {
                Text(text = "動画プレイヤーを開く")
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    // ゴミ箱に移動リクエストの Intent をつくる
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val intent = MediaStore.createTrashRequest(context.contentResolver, listOf(uri), true)
                        (context as? Activity)?.startIntentSenderForResult(intent.intentSender, 0, null, 0, 0, 0)
                    } else {
                        // TODO 古い Android は未実装
                    }
                }
            ) {
                Text(text = "ゴミ箱に移動（Google フォトから復元できます）")
            }
        }
    }

    if (thumbnailBitmap.value != null) {
        Image(
            modifier = modifier.clickable { isShowBottomSheet.value = !isShowBottomSheet.value },
            bitmap = thumbnailBitmap.value!!,
            contentDescription = null
        )
    } else {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}

