package com.example.quranassistant

import com.example.quranassistant.ui.screens.ArabicSpeechTestScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import com.example.quranassistant.ui.screens.WakePhraseTestScreen
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.quranassistant.ui.screens.HandsFreeArabicTestScreen
private val DarkGreen = Color(0xFF123B2F)
private val Gold = Color(0xFFD5B16D)
private val Cream = Color(0xFFF7F5EF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HandsFreeArabicTestScreen()
        }
    }
}

@Composable
fun QuranAssistantApp() {

    var isListening by remember {
        mutableStateOf(false)
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Cream
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Spacer(Modifier.height(40.dp))

                Text(
                    text = "القرآن الكريم",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkGreen
                )

                Text(
                    text = "QURAN ASSISTANT",
                    fontSize = 13.sp,
                    letterSpacing = 3.sp,
                    color = Gold
                )

                Spacer(Modifier.height(45.dp))

                Text(
                    text = "السلام عليكم",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkGreen
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    text = "How can I help you today?",
                    fontSize = 17.sp,
                    color = DarkGreen
                )

                Spacer(Modifier.height(40.dp))

                Button(
                    onClick = {
                        isListening = !isListening
                    },
                    modifier = Modifier.size(150.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor =
                            if (isListening) Gold else DarkGreen
                    )
                ) {
                    Text(
                        text = if (isListening) "■" else "🎙",
                        fontSize = 48.sp
                    )
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    text = if (isListening)
                        "Listening mode (demo)"
                    else
                        "Tap the microphone to activate",
                    color = DarkGreen,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(35.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Text(
                            text = "TRY SAYING",
                            color = Gold,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(15.dp))

                        Text("• Play Surah Al-Kahf")
                        Spacer(Modifier.height(10.dp))
                        Text("• شغل سورة البقرة")
                        Spacer(Modifier.height(10.dp))
                        Text("• Pause the recitation")
                    }
                }

                Spacer(Modifier.weight(1f))

                Text(
                    text = "Your Quran. Your Voice. Anytime.",
                    color = DarkGreen,
                    fontSize = 13.sp
                )
            }
        }
    }
}

