package com.example.quranassistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.quranassistant.voice.ArabicWakePhraseMatcher
import com.example.quranassistant.audio.QuranAudioLibrary
import com.example.quranassistant.audio.QuranAudioPlayer
import com.example.quranassistant.data.SurahCatalog
import com.example.quranassistant.voice.CommandAction
import com.example.quranassistant.voice.MicrophoneRecorder
import com.example.quranassistant.voice.QuranCommandParser
import com.example.quranassistant.voice.SherpaSpeechRecognizer
import com.example.quranassistant.voice.SpeechLanguage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import kotlin.math.abs

@Composable
fun ArabicSpeechTestScreen() {

    val context = LocalContext.current
    val surahCatalog = remember(context) {
        SurahCatalog(context.applicationContext)
    }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val audioLibrary = remember(context) {
        QuranAudioLibrary(context.applicationContext)
    }

    var isImporting by remember {
        mutableStateOf(false)
    }

    var importStatus by remember {
        mutableStateOf("No audio imported in this session.")
    }

    var testSurahNumber by remember {
        mutableStateOf("1")
    }

    var libraryStatus by remember {
        mutableStateOf("Choose a Surah number and check its audio source.")
    }

    var showRemoveDialog by remember {
        mutableStateOf(false)
    }

    // Create one audio player for this screen.
    val audioPlayer = remember(context) {
        QuranAudioPlayer(context.applicationContext)
    }

    var selectedLanguage by remember {
        mutableStateOf(SpeechLanguage.ARABIC)
    }

    var isBusy by remember {
        mutableStateOf(false)
    }

    var status by remember {
        mutableStateOf("Ready to record")
    }

    var recognizedText by remember {
        mutableStateOf("")
    }

    var parsedCommand by remember {
        mutableStateOf("")
    }

    var debugInfo by remember {
        mutableStateOf("No recording performed yet.")
    }

    var playbackStatus by remember {
        mutableStateOf("No recitation playing")
    }

    // Release resources when this screen is removed.
    DisposableEffect(audioPlayer) {

        audioPlayer.onPlaybackCompleted = {
            playbackStatus = "Recitation finished"
        }

        onDispose {
            audioPlayer.close()
        }
    }

    // Which source will QuranAudioPlayer choose for this Surah?
    fun describeAudioSource(surahNumber: Int): String = when {
        surahNumber !in 1..114 -> "Please enter a number from 1 to 114."
        audioLibrary.importedFile(surahNumber) != null ->
            "Surah $surahNumber: IMPORTED MP3 (app-private storage). " +
            "This takes priority over the bundled recording."
        audioLibrary.hasAudio(surahNumber) ->
            "Surah $surahNumber: BUNDLED MP3 (res/raw). " +
            "No imported copy is present."
        else -> "Surah $surahNumber: No offline audio installed yet."
    }

    // Start playing a selected Surah.
    fun playAudio(surahNumber: Int, surahName: String) {
        try {
            audioPlayer.playSurah(surahNumber)

            playbackStatus =
                "Playing $surahName — Mishary Alafasy"

        } catch (e: Exception) {
            playbackStatus =
                "Playback error: ${e.message}"
        }
    }

    // Pause the current recitation.
    fun pauseAudio() {
        try {
            if (audioPlayer.hasRecitation) {
                audioPlayer.pause()
                playbackStatus = "Recitation paused"
            } else {
                playbackStatus = "Nothing to pause"
            }
        } catch (e: Exception) {
            playbackStatus = "Pause error: ${e.message}"
        }
    }

    // Resume from the paused position.
    fun resumeAudio() {
        try {
            when {
                audioPlayer.resume() -> {
                    playbackStatus = "Recitation resumed"
                }

                audioPlayer.isPlaying -> {
                    playbackStatus = "Already playing"
                }

                else -> {
                    playbackStatus =
                        "Nothing to resume. Play a Surah first."
                }
            }
        } catch (e: Exception) {
            playbackStatus = "Resume error: ${e.message}"
        }
    }

    // Stop and release the recitation.
    fun stopAudio() {
        try {
            playbackStatus =
                if (audioPlayer.stop()) {
                    "Playback stopped"
                } else {
                    "Nothing to stop"
                }
        } catch (e: Exception) {
            playbackStatus = "Stop error: ${e.message}"
        }
    }

    // Record microphone audio and recognize it offline.
    fun recordAndRecognize() {

        if (isBusy) return

        val languageForRecording = selectedLanguage

        isBusy = true

        // IMPORTANT:
        // Pause the recitation before recording.
        // Do NOT stop(), because we must preserve
        // the position for the RESUME command.
        if (audioPlayer.isPlaying) {
            audioPlayer.pause()
            playbackStatus = "Paused for voice recognition"
        }

        recognizedText = ""
        parsedCommand = ""
        debugInfo = "Starting microphone..."
        status = "Listening... speak now (stops when you finish)"

        scope.launch {
            try {
                // Step 1: Record until the user finishes speaking.
                val recording = withContext(Dispatchers.IO) {
                    MicrophoneRecorder.recordUntilSilence()
                }
                val samples = recording.samples

                val peak = samples.maxOfOrNull {
                    abs(it)
                } ?: 0f

                debugInfo = """
                    Language: $languageForRecording
                    Captured duration: ${recording.capturedDurationMs} ms
                    Samples sent to model: ${samples.size}
                    Speech detected: ${recording.speechDetected}
                    Recording ended: ${recording.endReason}
                    Peak audio level: $peak
                """.trimIndent()

                if (!recording.speechDetected) {
                    status = "No speech detected. Try again."
                    recognizedText = "No speech detected"
                    parsedCommand = "No command"
                    playbackStatus = if (audioPlayer.hasRecitation) {
                        "No command heard. Recitation remains paused."
                    } else {
                        "No command heard."
                    }
                    return@launch
                }

                status = "Processing speech offline..."

                // Step 2: Recognize speech using sherpa-onnx.
                val result = withContext(Dispatchers.Default) {

                    val recognizer = SherpaSpeechRecognizer(
                        context = context.applicationContext,
                        language = languageForRecording
                    )

                    try {
                        recognizer.transcribe(samples)
                    } finally {
                        recognizer.close()
                    }
                }

                recognizedText = result.ifBlank {
                    "No speech recognized"
                }
                val wakeDetected =
                    ArabicWakePhraseMatcher.isWakePhrase(result)

                debugInfo += "\nWake phrase detected: $wakeDetected"

                debugInfo += if (result.isBlank()) {
                    "\n\nRaw model output:\n[EMPTY RESULT]"
                } else {
                    "\n\nRaw model output:\n[$result]"
                }

                // Step 3: Interpret the voice command.
                val parsed = QuranCommandParser.parse(
                    result,
                    surahCatalog.surahs
                )
                parsedCommand = parsed.message

                // Step 4: Execute the command.
                when (parsed.action) {
                    CommandAction.PLAY -> {
                        val number = parsed.surahNumber
                        val name = parsed.surahEnglishName ?: "Surah $number"

                        when {
                            number == null -> {
                                playbackStatus = "No Surah number recognized"
                            }

                            audioLibrary.hasAudio(number) -> {
                                playAudio(number, name)
                            }

                            else -> {
                                playbackStatus =
                                    "Recognized $name (Surah $number). " +
                                            "Audio not installed yet."
                            }
                        }
                    }

                    CommandAction.PAUSE -> {
                        pauseAudio()
                    }

                    CommandAction.RESUME -> {
                        resumeAudio()
                    }

                    CommandAction.STOP -> {
                        stopAudio()
                    }

                    CommandAction.CONFIRM -> {
                        playbackStatus =
                            "Uncertain command. Please repeat."
                    }

                    CommandAction.UNKNOWN -> {
                        playbackStatus =
                            if (audioPlayer.hasRecitation) {
                                "Command unclear. " +
                                        "Playback remains paused."
                            } else {
                                "No valid command recognized."
                            }
                    }
                }

                status = if (result.isBlank()) {
                    "Recognition finished, but no text returned"
                } else {
                    "Recognition complete"
                }

            } catch (e: Exception) {

                status = "Recognition error"

                recognizedText =
                    "Error: ${e.localizedMessage}"

                debugInfo +=
                    "\n\nError: ${e.javaClass.simpleName}"

            } finally {
                isBusy = false
            }
        }
    }

    // Request microphone permission if necessary.
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                recordAndRecognize()
            } else {
                status = "Microphone permission denied"
            }
        }

    val exampleText = when (selectedLanguage) {
        SpeechLanguage.ARABIC -> "شغّل سورة الفاتحة"
        SpeechLanguage.ENGLISH -> "Play Fatiha"
    }

    val audioImportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->

            if (uris.isNotEmpty()) {

                isImporting = true
                importStatus = "Importing ${uris.size} file(s)..."

                scope.launch {
                    try {
                        val report = withContext(Dispatchers.IO) {
                            audioLibrary.importMp3Files(uris)
                        }

                        val selectedNumber = testSurahNumber.toIntOrNull()
                        if (selectedNumber != null &&
                            selectedNumber in report.imported) {
                            libraryStatus = describeAudioSource(selectedNumber)
                        }

                        importStatus = buildString {
                            append(
                                "Imported: ${report.imported.size} file(s)"
                            )

                            if (report.imported.isNotEmpty()) {
                                append(
                                    "\nSurahs: ${
                                        report.imported.joinToString(", ")
                                    }"
                                )
                            }

                            append(
                                "\nSkipped: ${report.skipped.size}"
                            )

                            append(
                                "\nErrors: ${report.failed.size}"
                            )

                            if (report.skipped.isNotEmpty()) {
                                append(
                                    "\nFirst skipped: ${report.skipped.first()}"
                                )
                            }

                            if (report.failed.isNotEmpty()) {
                                append(
                                    "\nFirst error: ${report.failed.first()}"
                                )
                            }
                        }

                    } catch (e: Exception) {
                        importStatus =
                            "Import error: ${e.message}"
                    } finally {
                        isImporting = false
                    }
                }
            }
        }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text("Remove imported recording?") },
            text = {
                Text(
                    "Only QuranAssistant's imported copy will be deleted. " +
                    "Your original download and bundled Surahs will be kept."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val number = testSurahNumber.toIntOrNull()
                    showRemoveDialog = false
                    if (number != null && number in 1..114) {
                        // Release the file before deleting its imported copy.
                        audioPlayer.stop()
                        playbackStatus = "Playback stopped for library change"
                        val removed = audioLibrary.removeImportedFile(number)
                        libraryStatus = if (removed) {
                            "Imported copy removed. " + describeAudioSource(number)
                        } else {
                            "Could not remove the imported copy for Surah $number."
                        }
                    }
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(scrollState)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        Text(
            text = "QuranAssistant",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "Offline Speech Recognition",
            style = MaterialTheme.typography.titleMedium
        )

        HorizontalDivider()

        Text(
            text = "Select language:",
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            horizontalArrangement =
                Arrangement.spacedBy(12.dp)
        ) {

            FilterChip(
                selected =
                    selectedLanguage == SpeechLanguage.ARABIC,
                enabled = !isBusy && !isImporting,
                onClick = {
                    selectedLanguage = SpeechLanguage.ARABIC
                    recognizedText = ""
                    parsedCommand = ""
                    status = "Ready to record Arabic"
                },
                label = { Text("العربية") }
            )

            FilterChip(
                selected =
                    selectedLanguage == SpeechLanguage.ENGLISH,
                enabled = !isBusy && !isImporting,
                onClick = {
                    selectedLanguage = SpeechLanguage.ENGLISH
                    recognizedText = ""
                    parsedCommand = ""
                    status = "Ready to record English"
                },
                label = { Text("English") }
            )
        }

        Text("Try saying:")

        Text(
            text = exampleText,
            style = MaterialTheme.typography.headlineSmall
        )

        Button(
            enabled = !isBusy && !isImporting,
            onClick = {
                val hasPermission =
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                if (hasPermission) {
                    recordAndRecognize()
                } else {
                    permissionLauncher.launch(
                        Manifest.permission.RECORD_AUDIO
                    )
                }
            }
        ) {
            Text(
                if (isBusy) "Working..."
                else "Tap to Speak"
            )
        }

        Text(status)

        HorizontalDivider()

        Text(
            text = "Recognized text:",
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = recognizedText.ifBlank {
                "Waiting for speech..."
            }

        )

        HorizontalDivider()

        Text(
            text = "Command interpretation:",
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = parsedCommand.ifBlank {
                "No command yet."
            }
        )

        HorizontalDivider()

        // OFFLINE QURAN PLAYBACK
        Text(
            text = "Offline Quran Playback",
            style = MaterialTheme.typography.titleLarge
        )

        Text("Reciter: Mishary Rashid Alafasy")

        Text(
            text = playbackStatus,
            style = MaterialTheme.typography.bodyLarge
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBusy && !isImporting,
            onClick = {
                playAudio(1, "Al-Fatiha")
            }
        ) {
            Text("Play Al-Fatiha")
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBusy && !isImporting,
            onClick = {
                playAudio(2, "Al-Baqarah")
            }
        ) {
            Text("Play Al-Baqarah")
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBusy && !isImporting,
            onClick = {
                playAudio(112, "Al-Ikhlas")
            }
        ) {
            Text("Play Al-Ikhlas")
        }

        HorizontalDivider()

        // IMPORT RECITATIONS INTO THE APP'S OFFLINE AUDIO LIBRARY
        Text(
            text = "Offline Audio Library",
            style = MaterialTheme.typography.titleLarge
        )

        Text("Import MP3 recordings named 001.mp3, 055.mp3, surah_055.mp3, etc.")

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBusy && !isImporting,
            onClick = {
                // Do not overwrite a recording while it is being played.
                audioPlayer.stop()
                playbackStatus = "Playback stopped for import"
                audioImportLauncher.launch(arrayOf("audio/*"))
            }
        ) {
            Text(if (isImporting) "Importing..." else "Import MP3 Files")
        }

        Text(
            text = importStatus,
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = testSurahNumber,
            onValueChange = { input ->
                testSurahNumber = input.filter { it.isDigit() }.take(3)
                libraryStatus = "Tap Check Audio Source to inspect this Surah."
            },
            label = { Text("Surah number (1-114)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBusy && !isImporting,
            onClick = {
                val number = testSurahNumber.toIntOrNull()
                when {
                    number == null || number !in 1..114 -> {
                        playbackStatus = "Enter a Surah number from 1 to 114"
                    }
                    !audioLibrary.hasAudio(number) -> {
                        playbackStatus = "Audio not installed for Surah $number"
                    }
                    else -> {
                        val name = surahCatalog.surahs
                            .firstOrNull { it.number == number }
                            ?.englishName ?: "Surah $number"
                        playAudio(number, name)
                    }
                }
            }
        ) {
            Text("Play Selected Surah")
        }

        // Test the library with Surah 1; no other MP3 is required.
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBusy && !isImporting,
            onClick = {
                val number = testSurahNumber.toIntOrNull()
                libraryStatus = describeAudioSource(number ?: -1)
            }
        ) {
            Text("Check Audio Source")
        }

        Text(
            text = libraryStatus,
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBusy && !isImporting,
            onClick = {
                val number = testSurahNumber.toIntOrNull()
                when {
                    number == null || number !in 1..114 ->
                        libraryStatus = "Enter a Surah number from 1 to 114."
                    audioLibrary.importedFile(number) == null ->
                        libraryStatus = "No imported copy exists for Surah $number."
                    else -> showRemoveDialog = true
                }
            }
        ) {
            Text("Remove Imported Copy")
        }

        HorizontalDivider()

        // NEW PLAYBACK CONTROLS
        Text(
            text = "Playback Controls",
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {

            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = !isBusy && !isImporting,
                onClick = { pauseAudio() }
            ) {
                Text("Pause")
            }

            Button(
                modifier = Modifier.weight(1f),
                enabled = !isBusy && !isImporting,
                onClick = { resumeAudio() }
            ) {
                Text("Resume")
            }
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBusy && !isImporting,
            onClick = { stopAudio() }
        ) {
            Text("Stop Playback")
        }

        Text(
            text = "Voice commands: وقف مؤقت، استأنف، أوقف التلاوة",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()

        // DEBUG INFORMATION
        Text(
            text = "Debug information:",
            style = MaterialTheme.typography.titleMedium
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = debugInfo,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
