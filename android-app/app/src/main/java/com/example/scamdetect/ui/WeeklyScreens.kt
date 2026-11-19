package com.example.scamdetect.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.scamdetect.R
import com.example.scamdetect.logic.WeeklyReminder

// "Share weekly summary": a preview of the plain note, the system share sheet (the user picks WhatsApp, SMS, …) and
// the optional weekly reminder. Same screen, order and wording as ios-app UI/WeeklyViews.swift.

@Composable
fun WeeklySummaryScreen(text: String, reminder: Boolean, onReminder: (Boolean) -> WeeklyReminder.Action,
                        onPermission: (Boolean) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermission(granted); denied = !granted
    }
    Page(stringResource(R.string.weekly_title), onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("weekly"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.weekly_line), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.weekly_preview), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            AppCard { Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("weeklyText")) }
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, context.getString(R.string.btn_weekly)))
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("weeklyShare").pressFeedback()) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.btn_share), style = MaterialTheme.typography.labelLarge)
            }
            Group {
                ToggleRow(stringResource(R.string.weekly_reminder), stringResource(R.string.weekly_reminder_line), reminder) { on ->
                    denied = false
                    // Notification permission is asked only now, when turning it on (Android 13+).
                    if (onReminder(on) == WeeklyReminder.Action.ASK_PERMISSION) {
                        if (Build.VERSION.SDK_INT >= 33) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        else { onPermission(false); denied = true }   // notifications are off in the phone's settings
                    }
                }
            }
            if (denied) Text(stringResource(R.string.weekly_reminder_denied), style = MaterialTheme.typography.bodyLarge,
                color = WarningInkFor(), modifier = Modifier.testTag("weeklyDenied"))
        }
    }
}
