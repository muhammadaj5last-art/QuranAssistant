package com.example.quranassistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.quranassistant.voice.HeyQuranWakeDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun WakePhraseTestScreen() {
    val context = LocalContext.current
    val activeKeywords = remember(context) {
        context.assets.open("models/kws/keywords.txt")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
    val detector = remember(context) {
        HeyQuranWakeDetector(context.applicationContext)
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Wake detection off") }
    var lastKeyword by remember { mutableStateOf("") }
    var detections by remember { mutableIntStateOf(0) }
    var samplesReceived by remember { mutableLongStateOf(0L) }
    var peakAudioLevel by remember { mutableFloatStateOf(0f) }
    var decodeCalls by remember { mutableLongStateOf(0L) }

    val requestMicrophone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) listening = true
        else status = "Microphone permission denied"
    }

    val activity = context as? ComponentActivity
    DisposableEffect(activity, detector) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                listening = false
                detector.stop()
                status = "Wake detection stopped (app left foreground)"
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            listening = false
            detector.stop()
            activity?.lifecycle?.removeObserver(observer)
        }
    }

    LaunchedEffect(listening) {
        if (!listening) return@LaunchedEffect
        status = "Initializing keyword model..."
        samplesReceived = 0L
        peakAudioLevel = 0f
        decodeCalls = 0L

        try {
            status = "Listening for keywords..."
            val detected = withContext(Dispatchers.IO) {
                detector.listenUntilDetected { metrics ->
                    mainHandler.post {
                        samplesReceived = metrics.samplesReceived
                        peakAudioLevel = metrics.peakAudioLevel
                        decodeCalls = metrics.decodeCalls
                    }
                }
            }

            if (listening && !detected.isNullOrBlank()) {
                lastKeyword = detected
                detections += 1
                status = "Keyword detected!"
                listening = false
            } else if (listening) {
                status = "Listening stopped"
                listening = false
            }
        } catch (error: Exception) {
            if (listening) {
                status = "Error: ${error.localizedMessage ?: error.javaClass.simpleName}"
                listening = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("QuranAssistant", style = MaterialTheme.typography.headlineMedium)
        Text("Wake phrase diagnostics", style = MaterialTheme.typography.titleLarge)
        Text("The microphone runs only while this screen is open and listening is enabled.")
        Text("Status: $status")
        HorizontalDivider()
        Text("Microphone samples: $samplesReceived")
        Text("Peak audio level: $peakAudioLevel")
        Text("Decoder calls: $decodeCalls")
        HorizontalDivider()
        Text("Last detected keyword: ${lastKeyword.ifBlank { "None" }}")
        Text("Detection count: $detections")
        Text("Loaded keyword tokens:")
        Text(activeKeywords)

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !listening,
            onClick = {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) listening = true
                else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
            }
        ) { Text("Enable Wake Detection") }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = listening,
            onClick = {
                detector.stop()
                listening = false
                status = "Wake detection stopped"
            }
        ) { Text("Stop Listening") }

        Text("For the first test, use the original GigaSpeech keywords.txt and say 'Hey Siri' or 'Hello World'.")
    }
}
