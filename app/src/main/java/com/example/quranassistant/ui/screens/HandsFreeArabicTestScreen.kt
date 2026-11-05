package com.example.quranassistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.quranassistant.data.QuranSurah
import com.example.quranassistant.voice.MicrophoneRecorder
import com.example.quranassistant.voice.ParsedQuranCommand
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.quranassistant.audio.QuranAudioLibrary
import com.example.quranassistant.audio.QuranAudioPlayer
import com.example.quranassistant.audio.AlafasyAudioDownloader
import com.example.quranassistant.data.QuranAudioDatabase
import com.example.quranassistant.data.SurahCatalog
import com.example.quranassistant.voice.ArabicWakePhraseMatcher
import com.example.quranassistant.voice.AssistantSleepMatcher
import com.example.quranassistant.voice.AssistantFeedbackSounds
import com.example.quranassistant.voice.CommandAction
import com.example.quranassistant.voice.HandsFreeSegmentRecorder
import com.example.quranassistant.voice.QuranCommandParser
import com.example.quranassistant.voice.SherpaSpeechRecognizer
import com.example.quranassistant.voice.SpeechLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Step 47: Arabic-first interface over the working foreground-only assistant.
 * It reuses the existing Arabic model, wake matcher, catalog and audio player.
 * No microphone foreground service is started by this screen.
 */
@Composable
fun HandsFreeArabicTestScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val catalog = remember(appContext) { SurahCatalog(appContext) }
    val library = remember(appContext) { QuranAudioLibrary(appContext) }
    val player = remember(appContext) { QuranAudioPlayer(appContext) }
    val recorder = remember { HandsFreeSegmentRecorder() }
    val sounds = remember { AssistantFeedbackSounds() }
    val audioDatabase = remember(appContext) { QuranAudioDatabase(appContext) }
    val audioDownloader = remember(appContext) {
        AlafasyAudioDownloader(appContext, audioDatabase)
    }

    var databaseReady by remember { mutableStateOf(false) }
    var databaseStatus by remember { mutableStateOf("Loading offline library...") }
    var savedFiles by remember { mutableIntStateOf(0) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf("") }
    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var importReport by remember { mutableStateOf("لم يتم استيراد ملفات في هذه الجلسة") }
    var libraryRevision by remember { mutableIntStateOf(0) }
    var isManualRecording by remember { mutableStateOf(false) }
    var manualJob by remember { mutableStateOf<Job?>(null) }
    var currentSurahNumber by remember { mutableStateOf<Int?>(null) }
    var currentSurahTitle by remember { mutableStateOf("لا توجد تلاوة حالياً") }
    var recitationUiState by remember { mutableStateOf(RecitationUiState.STOPPED) }

    LaunchedEffect(appContext) {
        try {
            savedFiles = withContext(Dispatchers.IO) {
                audioDatabase.seedSurahs(catalog.surahs)
                audioDatabase.indexExistingImports(library)
                audioDatabase.savedFileCount()
            }
            databaseReady = true
            databaseStatus = "114 Surahs indexed in SQLite"
        } catch (e: Exception) {
            databaseStatus = "SQLite error: ${e.message}"
        }
    }

    var sessionJob by remember { mutableStateOf<Job?>(null) }
    var isListening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Hands-free mode is off") }
    var lastWakeText by remember { mutableStateOf("None") }
    var lastCommandText by remember { mutableStateOf("None") }
    var lastInterpretation by remember { mutableStateOf("No command yet") }
    var playbackStatus by remember { mutableStateOf("No recitation playing") }
    var wakeCount by remember { mutableIntStateOf(0) }
    // Awake persists across commands, until a sleep command or 30 minutes
    // without a recognized command. It still stops on app backgrounding.
    var isAwake by remember { mutableStateOf(false) }
    var awakeUntilMs by remember { mutableLongStateOf(0L) }
    val awakeIdleTimeoutMs = 30 * 60 * 1_000L

    // Step 45: Lightweight diagnostics (kept in memory; no audio saved).
    var decodedSegments by remember { mutableIntStateOf(0) }
    var rejectedWakeSegments by remember { mutableIntStateOf(0) }
    var silentWindows by remember { mutableIntStateOf(0) }
    var commandAttempts by remember { mutableIntStateOf(0) }
    var lastRecognitionMs by remember { mutableLongStateOf(0L) }
    var totalRecognitionMs by remember { mutableLongStateOf(0L) }
    var totalProcessCpuMs by remember { mutableLongStateOf(0L) }

    fun stopListening() {
        recorder.requestStop()
        sessionJob?.cancel()
        isAwake = false
        awakeUntilMs = 0L
        if (isListening) status = "Stopping microphone..."
    }

    fun playSurah(number: Int, name: String) {
        if (!library.hasAudio(number)) {
            playbackStatus = "Surah $number recognized, but audio is not installed"
            return
        }
        try {
            // If wake capture happens to be active, cut it short.
            recorder.interruptForPlayback()
            player.playSurah(number)
            playbackStatus = "Playing $name — Mishary Alafasy"
            currentSurahNumber = number
            currentSurahTitle = name
            recitationUiState = RecitationUiState.PLAYING
        } catch (e: Exception) {
            playbackStatus = "Playback error: ${e.message}"
        }
    }

    // First request: download missing MP3 and save it in SQLite, then play.
    // Later requests: use local storage, so no internet is required.
    suspend fun playOrDownload(number: Int, name: String) {
        if (number !in 1..114) {
            playbackStatus = "Invalid Surah number"
            return
        }
        if (!databaseReady) {
            playbackStatus = "Audio database is not ready"
            return
        }
        if (library.hasAudio(number)) {
            playSurah(number, name)
            return
        }
        if (isDownloading) {
            playbackStatus = "Another recitation is downloading"
            return
        }

        isDownloading = true
        player.stop()
        recitationUiState = RecitationUiState.STOPPED
        playbackStatus = "Downloading $name — internet required for first play"
        downloadProgress = "Connecting..."
        try {
            audioDownloader.ensureAvailable(number) { bytes, total ->
                withContext(Dispatchers.Main) {
                    val mb = bytes / 1_048_576.0
                    downloadProgress = if (total != null && total > 0) {
                        val percentage = (bytes * 100 / total).coerceIn(0, 100)
                        "$percentage% — ${"%.1f".format(mb)} MB"
                    } else {
                        "${"%.1f".format(mb)} MB downloaded"
                    }
                }
            }
            savedFiles = withContext(Dispatchers.IO) {
                audioDatabase.savedFileCount()
            }
            libraryRevision++
            playSurah(number, name)
            downloadProgress = "Saved for offline playback"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            playbackStatus =
                "Could not download $name: ${e.message ?: "Network error"}. " +
                    "Connect to internet or import its MP3."
        } finally {
            isDownloading = false
        }
    }

    suspend fun executeCommand(parsed: ParsedQuranCommand) {
        lastInterpretation = parsed.message
        when (parsed.action) {
            CommandAction.PLAY, CommandAction.PAUSE,
            CommandAction.RESUME, CommandAction.STOP -> {
                // The acknowledgement is heard before playback can begin.
                sounds.commandAccepted()
                delay(230)
            }
            CommandAction.CONFIRM, CommandAction.UNKNOWN -> sounds.uncertain()
        }
        when (parsed.action) {
            CommandAction.PLAY -> {
                val number = parsed.surahNumber
                if (number == null) playbackStatus = "لم يتم تحديد السورة"
                else playOrDownload(number, parsed.surahEnglishName ?: "Surah $number")
            }
            CommandAction.PAUSE -> {
                playbackStatus = if (player.pause()) {
                    recitationUiState = RecitationUiState.PAUSED
                    "Recitation paused"
                } else "Nothing playing to pause"
            }
            CommandAction.RESUME -> {
                recorder.interruptForPlayback()
                playbackStatus = if (player.resume()) {
                    recitationUiState = RecitationUiState.PLAYING
                    "Recitation resumed"
                } else "Nothing paused to resume"
            }
            CommandAction.STOP -> {
                playbackStatus = if (player.stop()) {
                    recitationUiState = RecitationUiState.STOPPED
                    "Playback stopped"
                } else "Nothing to stop"
            }
            CommandAction.CONFIRM -> playbackStatus = "Command uncertain. Please repeat."
            CommandAction.UNKNOWN -> playbackStatus = "No valid command recognized"
        }
    }

    fun startListening() {
        // Avoid launching two microphone loops at the same time.
        if (sessionJob?.isActive == true || !databaseReady || isDownloading || isImporting || isManualRecording) return

        recorder.resetSession()
        isAwake = false
        awakeUntilMs = 0L
        isListening = true
        lastWakeText = "None"
        lastCommandText = "None"
        lastInterpretation = "No command yet"
        status = "Loading Arabic model..."
        decodedSegments = 0
        rejectedWakeSegments = 0
        silentWindows = 0
        commandAttempts = 0
        lastRecognitionMs = 0L
        totalRecognitionMs = 0L
        totalProcessCpuMs = 0L

        sessionJob = scope.launch {
            try {
                // Load Moonshine ONCE per session, not once per utterance.
                val recognizer = withContext(Dispatchers.Default) {
                    SherpaSpeechRecognizer(appContext, SpeechLanguage.ARABIC)
                }
                try {
                    while (isActive && !recorder.isStopRequested) {
                        val now = SystemClock.elapsedRealtime()
                        if (isAwake && now >= awakeUntilMs) {
                            isAwake = false
                            awakeUntilMs = 0L
                            status = "انتهت 30 دقيقة من عدم الأوامر. انتظار عبارة التنبيه"
                            lastInterpretation = "Auto-sleep: 30 minutes without a command"
                            // Timeout is silent; don't interrupt Quran audio.
                        }

                        // Prevent Quran audio coming from the speaker from
                        // being reinterpreted as user speech. In this version
                        // voice commands are unavailable DURING playback.
                        if (player.isPlaying) {
                            status = "التلاوة تعمل — الميكروفون متوقف حتى الإيقاف المؤقت"
                            while (isActive && !recorder.isStopRequested && player.isPlaying) {
                                if (isAwake && SystemClock.elapsedRealtime() >= awakeUntilMs) {
                                    isAwake = false
                                    awakeUntilMs = 0L
                                    lastInterpretation = "Auto-sleep after 30 minutes"
                                    // Don't beep over a Quran recitation.
                                }
                                delay(250)
                            }
                            continue
                        }

                        status = if (isAwake) {
                            "المساعد مستيقظ — قل أمراً أو «طفي» (30 دقيقة دون أمر للنوم)"
                        } else {
                            "في انتظار «يا مساعد القرآن»"
                        }

                        val recording = withContext(Dispatchers.IO) {
                            recorder.capture()
                        }
                        if (!isActive || recorder.isStopRequested) break
                        if (!recording.speechDetected) {
                            silentWindows++
                            continue
                        }
                        if (player.isPlaying) continue

                        status = "التعرف على الكلام العربي دون إنترنت..."
                        val wallStart = SystemClock.elapsedRealtime()
                        val cpuStart = Process.getElapsedCpuTime()
                        val text = withContext(Dispatchers.Default) {
                            recognizer.transcribe(recording.samples)
                        }.trim()
                        val wallMs = SystemClock.elapsedRealtime() - wallStart
                        val cpuMs = (Process.getElapsedCpuTime() - cpuStart).coerceAtLeast(0L)
                        if (!isActive || recorder.isStopRequested) break
                        decodedSegments++
                        lastRecognitionMs = wallMs
                        totalRecognitionMs += wallMs
                        totalProcessCpuMs += cpuMs

                        // If the user speaks the wake phrase in the middle of
                        // a longer sentence, activate and optionally execute
                        // a command that follows it in that SAME utterance.
                        val wake = ArabicWakePhraseMatcher.match(text)
                        if (wake.detected) {
                            lastWakeText = text
                            wakeCount++
                            isAwake = true
                            awakeUntilMs = SystemClock.elapsedRealtime() + awakeIdleTimeoutMs
                            sounds.wake()
                            lastInterpretation = "Wake phrase detected — assistant awake"
                            status = "استيقظ المساعد — تفضل بالأمر"
                            delay(360) // Don't record the acknowledgement tone.
                            if (wake.textAfterWake.isBlank()) continue
                            // Extra conversational words after the wake phrase
                            // should not immediately trigger a failure beep.
                            if (!AssistantSleepMatcher.isSleepCommand(wake.textAfterWake) &&
                                QuranCommandParser.parse(
                                    wake.textAfterWake, catalog.surahs
                                ).action == CommandAction.UNKNOWN
                            ) {
                                continue
                            }
                        }

                        if (!isAwake) {
                            rejectedWakeSegments++
                            lastWakeText = text.ifBlank { "[empty result]" }
                            lastInterpretation = "No wake phrase; ignoring speech"
                            continue
                        }

                        val commandText = if (wake.detected) wake.textAfterWake else text
                        lastCommandText = commandText.ifBlank { "[empty result]" }

                        // IMPORTANT: "طفي" sleeps the ASSISTANT only;
                        // "وقف"/"أوقف التلاوة" stop the audio instead.
                        if (AssistantSleepMatcher.isSleepCommand(commandText)) {
                            isAwake = false
                            awakeUntilMs = 0L
                            lastInterpretation = "SLEEP: Returned to wake-word mode"
                            status = "تم إطفاء المساعد — الاستماع لعبارة التنبيه"
                            sounds.sleep()
                            delay(360) // Avoid hearing the shutdown tone.
                            continue
                        }

                        commandAttempts++
                        val parsed = QuranCommandParser.parse(commandText, catalog.surahs)
                        if (parsed.action != CommandAction.UNKNOWN) {
                            // 30-minute timeout is measured from the last
                            // understood command, not from microphone noise.
                            awakeUntilMs = SystemClock.elapsedRealtime() + awakeIdleTimeoutMs
                        }
                        executeCommand(parsed)
                    }
                } finally {
                    // Release native ASR memory even if the coroutine is cancelled.
                    withContext(NonCancellable + Dispatchers.Default) {
                        recognizer.close()
                    }
                }
            } catch (_: CancellationException) {
                // The user pressed Stop, or the app left the foreground.
            } catch (e: Exception) {
                status = "Hands-free error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                isListening = false
                isAwake = false
                awakeUntilMs = 0L
                if (!status.startsWith("Hands-free error")) {
                    status = "Hands-free mode is off"
                }
                sessionJob = null
            }
        }
    }

    // Optional manual voice command, independent from the hands-free wake loop.
    fun startManualCommand() {
        if (isListening || isManualRecording || isDownloading || isImporting || !databaseReady) return
        isManualRecording = true
        if (player.isPlaying) {
            player.pause()
            recitationUiState = RecitationUiState.PAUSED
        }
        status = "Listening for one Arabic command..."
        manualJob = scope.launch {
            try {
                val recording = withContext(Dispatchers.IO) {
                    MicrophoneRecorder.recordUntilSilence()
                }
                if (!recording.speechDetected) {
                    status = "No speech detected"
                    lastInterpretation = "لم يتم سماع كلام"
                } else {
                    status = "Recognizing Arabic offline..."
                    val text = withContext(Dispatchers.Default) {
                        SherpaSpeechRecognizer(appContext, SpeechLanguage.ARABIC).use {
                            it.transcribe(recording.samples)
                        }
                    }.trim()
                    lastCommandText = text.ifBlank { "[empty result]" }
                    executeCommand(QuranCommandParser.parse(text, catalog.surahs))
                    status = "Manual command complete"
                }
            } catch (_: CancellationException) {
                status = "Manual command cancelled"
            } catch (e: Exception) {
                status = "Microphone error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                isManualRecording = false
                manualJob = null
            }
        }
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening()
        else status = "Microphone permission denied"
    }

    val manualPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startManualCommand()
        else status = "Microphone permission denied"
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            isImporting = true
            importReport = "جاري استيراد ${uris.size} ملف..."
            scope.launch {
                try {
                    val report = withContext(Dispatchers.IO) {
                        library.importMp3Files(uris).also {
                            audioDatabase.indexExistingImports(library)
                        }
                    }
                    savedFiles = withContext(Dispatchers.IO) { audioDatabase.savedFileCount() }
                    libraryRevision++
                    importReport = "تم استيراد ${report.imported.size}، تم تخطي ${report.skipped.size}، أخطاء ${report.failed.size}" +
                        report.failed.firstOrNull()?.let { "\n$it" }.orEmpty()
                } catch (e: Exception) {
                    importReport = "Import error: ${e.message}"
                } finally {
                    isImporting = false
                }
            }
        }
    }

    // Foreground-only: automatically stop listening if the user leaves the app.
    DisposableEffect(lifecycleOwner, player, recorder) {
        player.onPlaybackCompleted = {
            playbackStatus = "Recitation finished"
            recitationUiState = RecitationUiState.STOPPED
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                stopListening()
                manualJob?.cancel()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            recorder.requestStop()
            sessionJob?.cancel()
            manualJob?.cancel()
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.close()
            sounds.close()
        }
    }

    val green = Color(0xFF164D3D)
    val gold = Color(0xFFD8AB63)
    val canvas = Color(0xFFF7F6F1)
    val actionEnabled = databaseReady && !isDownloading && !isImporting && !isListening && !isManualRecording

    fun requestManual() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startManualCommand()
        else manualPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun requestHandsFree() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening()
        else microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun playFromList(number: Int, name: String) {
        if (actionEnabled) scope.launch { playOrDownload(number, name) }
    }

    // Local database files + the three Surahs bundled in res/raw.
    // Reading 114 file-existence flags is small and keeps this screen simple.
    val installed = remember(libraryRevision, savedFiles, databaseReady) {
        catalog.surahs.filter { library.hasAudio(it.number) }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = green,
                secondary = Color(0xFF9C6C2B),
                background = canvas,
                surface = Color.White,
                onPrimary = Color.White,
                onSurface = Color(0xFF172C25)
            )
        ) {
            Scaffold(
                containerColor = canvas,
                topBar = {
                    Surface(color = green, shadowElevation = 2.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("مساعد القرآن", color = Color.White,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold)
                                Text("تلاوات مشاري راشد العفاسي", color = Color(0xFFDAE9DF),
                                    style = MaterialTheme.typography.labelMedium)
                            }
                            Surface(color = if (isListening) gold else Color(0xFF356F5C),
                                shape = RoundedCornerShape(50)) {
                                Text(if (isAwake) "● مستيقظ" else if (isListening) "● ينتظر" else "○ غير نشط",
                                    color = if (isListening) green else Color.White,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                },
                bottomBar = {
                    NavigationBar(containerColor = Color.White, tonalElevation = 5.dp) {
                        val tabs = listOf("الرئيسية", "السور", "المحفوظة", "الإعدادات")
                        val symbols = listOf("⌂", "۞", "↓", "⚙")
                        tabs.forEachIndexed { index, title ->
                            NavigationBarItem(
                                selected = currentTab == index,
                                onClick = {
                                    // Keep hands-free mode active across tabs
                                    // while QuranAssistant stays in foreground.
                                    currentTab = index
                                },
                                icon = { Text(symbols[index], style = MaterialTheme.typography.titleLarge) },
                                label = { Text(title, maxLines = 1) }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    when (currentTab) {
                        0 -> Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Card(
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = green),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(22.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("✦  استمع إلى القرآن بصوتك", color = gold,
                                        style = MaterialTheme.typography.titleMedium)
                                    Text("يا مساعد القرآن", color = Color.White,
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold)
                                    Text("قل عبارة التنبيه مرة واحدة، ثم اطلب الأوامر مباشرة خلال 30 دقيقة. قل «طفي» للنوم.",
                                        color = Color(0xFFE5F0E7),
                                        style = MaterialTheme.typography.bodyMedium)
                                    HorizontalDivider(color = Color(0xFF558472))
                                    Text(status, color = Color.White,
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        if (isAwake) "● المساعد مستيقظ — لا تحتاج لعبارة التنبيه مع كل أمر"
                                        else "○ المساعد ينتظر عبارة التنبيه",
                                        color = gold,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Button(
                                        enabled = !isListening && !isManualRecording && databaseReady && !isDownloading && !isImporting,
                                        onClick = { requestHandsFree() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = gold, contentColor = green),
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("◉  تفعيل الاستماع التلقائي") }
                                    OutlinedButton(
                                        enabled = isListening,
                                        onClick = { stopListening() },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("إيقاف الاستماع") }
                                    OutlinedButton(
                                        enabled = actionEnabled,
                                        onClick = { requestManual() },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text(if (isManualRecording) "جارٍ الاستماع..." else "تحدث الآن (مرة واحدة)") }
                                }
                            }

                            Text("التلاوة الحالية", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                            Card(shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Column(Modifier.fillMaxWidth().padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        currentSurahNumber?.let { number ->
                                            catalog.surahs.firstOrNull { it.number == number }?.arabicName
                                        } ?: currentSurahTitle,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (currentSurahNumber != null) {
                                        Text(currentSurahTitle, color = Color.Gray,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("مشاري راشد العفاسي", color = Color.Gray,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        when (recitationUiState) {
                                            RecitationUiState.PLAYING -> "● التلاوة قيد التشغيل"
                                            RecitationUiState.PAUSED -> "● التلاوة متوقفة مؤقتاً"
                                            RecitationUiState.STOPPED -> "○ لا توجد تلاوة قيد التشغيل"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = green
                                    )
                                    Text(playbackStatus, style = MaterialTheme.typography.bodySmall,
                                        color = green)
                                    if (isDownloading || downloadProgress.isNotBlank()) {
                                        Text("التنزيل: $downloadProgress", style = MaterialTheme.typography.bodySmall)
                                        if (isDownloading) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            modifier = Modifier.weight(1f),
                                            enabled = !isDownloading,
                                            onClick = {
                                                if (player.pause()) {
                                                    recitationUiState = RecitationUiState.PAUSED
                                                    playbackStatus = "تم إيقاف التلاوة مؤقتاً"
                                                }
                                            }
                                        ) { Text("إيقاف مؤقت") }
                                        Button(
                                            modifier = Modifier.weight(1f),
                                            enabled = !isDownloading,
                                            onClick = {
                                                recorder.interruptForPlayback()
                                                if (player.resume()) {
                                                    recitationUiState = RecitationUiState.PLAYING
                                                    playbackStatus = "استؤنفت التلاوة"
                                                }
                                            }
                                        ) { Text("استئناف") }
                                    }
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !isDownloading,
                                        onClick = {
                                            if (player.stop()) {
                                                recitationUiState = RecitationUiState.STOPPED
                                                playbackStatus = "تم إيقاف التلاوة"
                                            }
                                        }
                                    ) { Text("■  إيقاف التلاوة") }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OverviewTile("١١٤", "سورة", Modifier.weight(1f))
                                OverviewTile("${installed.size}", "متاحة دون إنترنت", Modifier.weight(1f))
                            }
                            Card(colors = CardDefaults.cardColors(containerColor = Color.White),
                                shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("آخر ما سمعه المساعد", fontWeight = FontWeight.SemiBold)
                                    Text(lastCommandText, style = MaterialTheme.typography.bodyMedium)
                                    Text(lastInterpretation, color = green,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        1 -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                            Text("جميع السور", style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                label = { Text("ابحث باسم السورة أو رقمها") },
                                placeholder = { Text("مثال: الرحمن أو 55") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            val query = searchText.trim()
                            val matches = catalog.surahs.filter { s ->
                                query.isBlank() || s.arabicName.contains(query) ||
                                    s.englishName.contains(query, ignoreCase = true) ||
                                    s.number.toString() == query ||
                                    s.number.toString().padStart(3, '0') == query
                            }
                            Text("${matches.size} سورة · اضغط للتشغيل أو التنزيل",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Spacer(Modifier.height(8.dp))
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(matches, key = { it.number }) { s ->
                                    SurahItemCard(
                                        surah = s,
                                        isInstalled = library.hasAudio(s.number),
                                        enabled = actionEnabled,
                                        onClick = { playFromList(s.number, s.englishName) }
                                    )
                                }
                            }
                        }

                        2 -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                            Text("مكتبتي دون إنترنت", style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("${installed.size} سورة متاحة، منها 3 مرفقة مع التطبيق",
                                style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                            Spacer(Modifier.height(12.dp))
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = actionEnabled,
                                onClick = {
                                    player.stop()
                                    recitationUiState = RecitationUiState.STOPPED
                                    importLauncher.launch(arrayOf("audio/*"))
                                }
                            ) { Text(if (isImporting) "جارٍ الاستيراد..." else "استيراد ملفات MP3 من الهاتف") }
                            Text(importReport, style = MaterialTheme.typography.bodySmall)
                            if (isDownloading) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(downloadProgress)
                            }
                            HorizontalDivider()
                            if (installed.isEmpty()) {
                                Text("لم تتوفر أي تلاوة بعد")
                            } else {
                                LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                    items(installed, key = { it.number }) { s ->
                                        SurahItemCard(s, true, actionEnabled) {
                                            playFromList(s.number, s.englishName)
                                        }
                                    }
                                }
                            }
                        }

                        else -> Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text("الإعدادات والتشخيص", style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold)
                            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("العودة إلى وضع انتظار كلمة التنبيه بعد 30 دقيقة دون أمر مفهوماً")
                                    Text("قل «طفي» أو «اطفى» للعودة لوضع الانتظار فوراً. " +
                                        "لإغلاق الميكروفون تماماً اضغط «إيقاف الاستماع» في الرئيسية.",
                                        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    Spacer(Modifier.height(12.dp))
                                    Text("SQLite: $databaseStatus")
                                    Text("ملفات MP3 المحفوظة: $savedFiles")
                                }
                            }
                            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Column(Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("إحصاءات الاستماع", style = MaterialTheme.typography.titleMedium)
                                    Text("مرات التنبيه: $wakeCount")
                                    Text("حالة المساعد: ${if (isAwake) "مستيقظ" else "في الانتظار"}")
                                    Text("مقاطع تمت معالجتها: $decodedSegments")
                                    Text("مقاطع غير مطابقة: $rejectedWakeSegments")
                                    Text("نوافذ صامتة: $silentWindows")
                                    Text("طلبات الأوامر: $commandAttempts")
                                    Text("آخر زمن تعرف: $lastRecognitionMs ms")
                                    Text("متوسط زمن التعرف: " +
                                        (if (decodedSegments == 0) "—" else "${totalRecognitionMs / decodedSegments} ms"))
                                    Text("وقت CPU التقريبي: $totalProcessCpuMs ms")
                                    HorizontalDivider()
                                    Text("آخر عبارة تنبيه: $lastWakeText")
                                    Text("آخر أمر: $lastCommandText")
                                }
                            }
                            Text("يعمل الاستماع التلقائي فقط ما دام التطبيق في الواجهة. " +
                                "هذه النسخة لا تستمع والشاشة مغلقة.",
                                color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private enum class RecitationUiState { STOPPED, PLAYING, PAUSED }

@Composable
private fun OverviewTile(value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, color = Color(0xFF164D3D),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SurahItemCard(
    surah: QuranSurah,
    isInstalled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = Color(0xFFE4F0E9), shape = RoundedCornerShape(12.dp)) {
                Text(surah.number.toString(), color = Color(0xFF164D3D),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(surah.arabicName, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(surah.englishName, color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (isInstalled) "محفوظة" else "تنزيل",
                    color = if (isInstalled) Color(0xFF1D7155) else Color(0xFF99621F),
                    style = MaterialTheme.typography.labelMedium)
                Text(if (isInstalled) "▶" else "↓", color = Color(0xFF164D3D),
                    style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
