package com.example.scamdetect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.scamdetect.ui.IncomingCallScreen
import com.example.scamdetect.ui.theme.ScamDetectTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScamDetectTheme {
                // Fake call state until real call detection is wired up.
                IncomingCallScreen(
                    callerName = "SPF Hotline",
                    callerNumber = "+65 6555 0123",
                    onAnswer = {},
                    onDecline = {}
                )
            }
        }
    }
}
