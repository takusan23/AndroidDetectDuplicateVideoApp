package io.github.takusan23.androiddetectduplicatevideoapp

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.takusan23.akaricore.common.toAkariCoreInputOutputData
import io.github.takusan23.akaricore.video.VideoFrameBitmapExtractor
import io.github.takusan23.androiddetectduplicatevideoapp.ui.theme.AndroidDetectDuplicateVideoAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit


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

private data class VideoFrameHashData(
    val videoUri: Uri,
    val durationMs: Long,
    val aHash: ULong,
    val dHash: ULong
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
    val processCount = remember{ mutableIntStateOf(0) }
    val videoFrameHashDataList = remember { mutableStateOf(emptyList<VideoFrameHashData>()) }

    fun analyze() {
        scope.launch(Dispatchers.Default) {
            val videoUriList = VideoTool.queryAllVideoIdList(context)
                .map { VideoTool.getVideoUri(it) }
            totalVideoCount.intValue = videoUriList.size

            // リストに同時にでアクセスさせないように mutex
            val listLock = Mutex()
            // 並列処理数を制限。ハードウェアデコーダーは同時起動上限がある。多分16個くらい
            val semaphore = Semaphore(16)

            videoUriList.take(10).forEach { uri ->

                // 並列ではしらす
                launch {
                    semaphore.withPermit {

                        // 動画時間取る
                        val videoDurationMs = MediaMetadataRetriever().apply {
                            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                                setDataSource(it.fileDescriptor)
                            }
                        }.use { it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()!! }

                        // 1 秒ごとにフレームを取り出す
                        val frameMsList = (0 until videoDurationMs step 1_000).toList() + videoDurationMs
                        val frameBitmapExtractor = VideoFrameBitmapExtractor()
                        try {
                            frameBitmapExtractor.prepareDecoder(uri.toAkariCoreInputOutputData(context))
                            frameMsList.forEach { frameMs ->
                                frameBitmapExtractor.getVideoFrameBitmap(frameMs)?.also { bitmap ->
                                    println("current $frameMs $uri")
                                    val aHash = ImageTool.calcAHash(bitmap)
                                    val dHash = ImageTool.calcDHash(bitmap)
                                    listLock.withLock {
                                        videoFrameHashDataList.value += VideoFrameHashData(uri, frameMs, aHash, dHash)
                                    }
                                }
                            }
                        } finally {
                            processCount.intValue++
                            frameBitmapExtractor.destroy()
                        }

                    }
                }
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

            Text(text = "総動画数 ${totalVideoCount.intValue} / 処理済み動画数 ${videoFrameHashDataList.value.distinctBy { it.videoUri }.size}")

        }
    }
}