package com.zubir.downloader

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

private val Bg = Color(0xFF14161B)
private val Surface1 = Color(0xFF1E2128)
private val Accent = Color(0xFF5EC4B6)
private val TextMain = Color(0xFFE6E8EC)
private val TextDim = Color(0xFF9AA1AD)

enum class Fmt { MP4, MP3 }

enum class Platform(val title: String, val icon: String, val hosts: List<String>) {
    YOUTUBE("يوتيوب", "▶️", listOf("youtube.com", "youtu.be")),
    TIKTOK("تيك توك", "🎵", listOf("tiktok.com")),
    INSTAGRAM("انستغرام", "📷", listOf("instagram.com"))
}

sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data class Downloading(val progress: Float) : UiState
    data class Done(val name: String) : UiState
    data class Error(val msg: String) : UiState
}

class DownloaderViewModel(private val app: Application) : AndroidViewModel(app) {
    var platform by mutableStateOf<Platform?>(null)
    var url by mutableStateOf("")
    var info by mutableStateOf<VideoInfo?>(null)
    var infoFailed by mutableStateOf(false)
    var format by mutableStateOf(Fmt.MP4)
    var quality by mutableIntStateOf(720)
    var state by mutableStateOf<UiState>(UiState.Idle)

    private var job: Job? = null

    private val initJob = viewModelScope.launch(Dispatchers.IO) {
        try {
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
        } catch (e: Exception) {
            state = UiState.Error("تعذر تهيئة المحرك: ${e.message}")
            return@launch
        }
        // تحديث محرك yt-dlp لأحدث نسخة (مهم جداً لنجاح التحميل)
        try {
            YoutubeDL.getInstance().updateYoutubeDL(app)
        } catch (_: Exception) {
        }
    }

    fun selectPlatform(p: Platform) {
        platform = p
        url = ""; info = null; infoFailed = false; state = UiState.Idle
    }

    fun setLink(text: String) {
        val link = Regex("https?://\\S+").find(text)?.value ?: text.trim()
        url = link; info = null; infoFailed = false; state = UiState.Idle
    }

    private fun matchesPlatform(): Boolean =
        platform?.hosts?.any { url.contains(it, ignoreCase = true) } == true

    private fun cleanError(e: Exception): String =
        e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(160) ?: "خطأ غير معروف"

    fun fetchInfo() {
        if (url.isBlank()) return
        if (!matchesPlatform()) {
            state = UiState.Error("الرابط ليس من منصة ${platform?.title}، تأكد من المنصة المختارة")
            return
        }
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            state = UiState.Loading
            infoFailed = false
            try {
                initJob.join()
                info = YoutubeDL.getInstance().getInfo(
                    YoutubeDLRequest(url.trim()).apply { addOption("--no-playlist") }
                )
                state = UiState.Idle
            } catch (e: Exception) {
                infoFailed = true
                state = UiState.Error("تعذرت المعاينة (${cleanError(e)}). يمكنك المحاولة بالتحميل مباشرة.")
            }
        }
    }

    fun download() {
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            state = UiState.Downloading(0f)
            try {
                initJob.join()
                val tmp = File(app.cacheDir, "dl").apply { deleteRecursively(); mkdirs() }
                val req = YoutubeDLRequest(url.trim()).apply {
                    addOption("-o", "${tmp.absolutePath}/%(title).80s.%(ext)s")
                    addOption("--no-playlist")
                    addOption("--concurrent-fragments", "4")
                    if (format == Fmt.MP3) {
                        addOption("-x")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "0")
                    } else {
                        val h = quality
                        addOption("-f", "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/b[height<=$h][ext=mp4]/b[height<=$h]/b")
                        addOption("--merge-output-format", "mp4")
                    }
                }
                YoutubeDL.getInstance().execute(req, PROCESS_ID) { p, _, _ ->
                    state = UiState.Downloading((p / 100f).coerceIn(0f, 1f))
                }
                val file = tmp.listFiles()?.firstOrNull() ?: error("لم يتم إنشاء الملف")
                saveToDownloads(file)
                tmp.deleteRecursively()
                state = UiState.Done(file.name)
            } catch (e: Exception) {
                if (state is UiState.Downloading) state = UiState.Error("فشل التحميل: ${cleanError(e)}")
            }
        }
    }

    fun cancel() {
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        job?.cancel()
        state = UiState.Idle
    }

    private fun saveToDownloads(file: File) {
        val mime = if (file.extension == "mp3") "audio/mpeg" else "video/mp4"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ZubirDownloader")
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    companion object { const val PROCESS_ID = "zubir_dl" }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, onPrimary = Color(0xFF0B1F1C),
                    background = Bg, surface = Surface1, onSurface = TextMain
                )
            ) {
                val vm: DownloaderViewModel = viewModel()
                LaunchedEffect(shared) {
                    shared?.let {
                        val p = Platform.entries.firstOrNull { pl -> pl.hosts.any { h -> it.contains(h, true) } }
                        if (p != null) vm.selectPlatform(p)
                        vm.setLink(it)
                        vm.fetchInfo()
                    }
                }
                Screen(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(vm: DownloaderViewModel) {
    val clipboard = LocalClipboardManager.current
    val st = vm.state
    val platform = vm.platform
    val showOptions = vm.info != null || vm.infoFailed

    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("ZubirDownloader", color = Accent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("اختر المنصة أولاً", color = TextDim, fontSize = 14.sp)

        // ───── اختيار المنصة ─────
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Platform.entries.forEach { p ->
                val selected = platform == p
                Card(
                    onClick = { vm.selectPlatform(p) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface1),
                    border = BorderStroke(2.dp, if (selected) Accent else Color(0xFF2E333D))
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(p.icon, fontSize = 28.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            p.title, color = if (selected) Accent else TextMain,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        if (platform != null) {
            OutlinedTextField(
                value = vm.url, onValueChange = { vm.setLink(it) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("ألصق رابط ${platform.title} هنا", color = TextDim) },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Color(0xFF2E333D),
                    focusedContainerColor = Surface1, unfocusedContainerColor = Surface1,
                    focusedTextColor = TextMain, unfocusedTextColor = TextMain
                ),
                trailingIcon = {
                    TextButton(onClick = {
                        clipboard.getText()?.text?.let { vm.setLink(it); vm.fetchInfo() }
                    }) { Text("لصق", color = Accent) }
                }
            )

            Button(
                onClick = { vm.fetchInfo() },
                enabled = vm.url.isNotBlank() && st !is UiState.Loading && st !is UiState.Downloading,
                modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    if (st is UiState.Loading) "جاري التحليل..." else "تحليل الرابط",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        vm.info?.let { info ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface1),
                shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    info.thumbnail?.let {
                        AsyncImage(
                            model = it, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(190.dp)
                                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                        )
                    }
                    Text(
                        info.title ?: "", color = TextMain, fontSize = 15.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        if (showOptions) {
            Text("الصيغة", color = TextDim, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Fmt.entries.forEach { f ->
                    FilterChip(
                        selected = vm.format == f, onClick = { vm.format = f },
                        label = { Text(if (f == Fmt.MP4) "فيديو MP4" else "صوت MP3") }
                    )
                }
            }

            if (vm.format == Fmt.MP4) {
                Text("الدقة", color = TextDim, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(1080, 720, 480, 360).forEach { q ->
                        FilterChip(
                            selected = vm.quality == q, onClick = { vm.quality = q },
                            label = { Text("${q}p") }
                        )
                    }
                }
            }

            when (st) {
                is UiState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { st.progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = Accent, trackColor = Surface1
                    )
                    Text("${(st.progress * 100).toInt()}%", color = TextDim)
                    OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("إلغاء") }
                }
                else -> Button(
                    onClick = { vm.download() },
                    modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp)
                ) { Text("تحميل", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            }
        }

        when (st) {
            is UiState.Done -> Text("✔ تم الحفظ في: Download/ZubirDownloader\n${st.name}", color = Accent)
            is UiState.Error -> Text(st.msg, color = Color(0xFFE5877F))
            else -> {}
        }
    }
}
