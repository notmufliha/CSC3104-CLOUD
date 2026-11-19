package com.example.scamdetect.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.scamdetect.R
import com.example.scamdetect.logic.AfterCall
import com.example.scamdetect.logic.Risk
import com.example.scamdetect.logic.Tr
import com.example.scamdetect.logic.Warnings

// After a Scam or "Be careful" call: what to do now, in big buttons. Same order, wording and look as
// ios-app UI/AfterCallCard.swift. Nothing here is uploaded: the summary only goes to the clipboard.

@Composable
fun AfterCallCard(state: CallUiState, family: CallViewModel.Family?, includeLines: Boolean) {
    val context = LocalContext.current
    var copied by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var noApp by rememberSaveable { mutableStateOf(false) }
    val level = state.summaryLevel
    val wide = Modifier.fillMaxWidth().heightIn(min = 64.dp)

    AppCard(modifier = Modifier.testTag("afterCallCard"), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(stringResource(R.string.after_title), style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        // 1. Report: copy a masked summary first, then open ScamShield's site
        Button(
            onClick = {
                val reason = Tr.s(if (level == Risk.Level.RED) Warnings.message(state.warningKind) else Warnings.careful(state.warningKind))
                val result = Tr.s(if (level == Risk.Level.RED) R.string.risk_scam else R.string.risk_careful)
                val risky = state.finalReview?.topWindowSeqs.orEmpty()
                val lines = state.lines.filter { it.isCaller && (it.flags.isNotEmpty() || it.seq in risky) }.map { it.text }
                val text = AfterCall.reportSummary(System.currentTimeMillis(), result, reason, lines, includeLines)
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText(Tr.s(R.string.copy_title), text).apply {
                        // masked already; still keep it out of the keyboard's clipboard preview
                        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                    })
                copied = true
                try { context.startActivity(Intent(Intent.ACTION_VIEW, SCAMSHIELD_URL.toUri())) } catch (e: ActivityNotFoundException) { }
            },
            modifier = wide.testTag("afterReport").pressFeedback(),
            colors = ButtonDefaults.buttonColors(containerColor = ScamRed, contentColor = Color.White),
        ) { Text(stringResource(R.string.after_report), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
        if (copied) Text(stringResource(R.string.after_copied), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("afterCopied").semantics { liveRegion = LiveRegionMode.Polite })
        else Text(stringResource(R.string.after_report_line), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // 2. Call 1799 (the phone app opens with the number; the user taps Call)
        BigOutlined(stringResource(R.string.btn_call_1799), "after1799") { if (!dial(context, SCAMSHIELD_HELPLINE)) noApp = true }
        // 3. Tell my family (only with a trusted contact)
        if (family != null) BigOutlined(stringResource(R.string.after_tell_family), "afterFamily") {
            if (!textMessage(context, family.phone, Warnings.familyMessage())) noApp = true
        }
        // 4. How to block, 5. If I already shared details
        BigOutlined(stringResource(R.string.after_block), "afterBlock") { sheet = "block" }
        BigOutlined(stringResource(R.string.after_shared), "afterShared") { sheet = "shared" }
        if (noApp) Text(stringResource(R.string.no_phone_app), style = MaterialTheme.typography.bodyLarge)
    }

    when (sheet) {
        "block" -> StepsSheet(stringResource(R.string.block_title), null, AfterCall.blockSteps.map { stringResource(it) }, numbered = true) { sheet = null }
        "shared" -> StepsSheet(stringResource(R.string.shared_title), stringResource(R.string.shared_line),
            AfterCall.sharedSteps.map { stringResource(it) }, numbered = true,
            action = stringResource(R.string.btn_call_1799) to { if (!dial(context, SCAMSHIELD_HELPLINE)) noApp = true }) { sheet = null }
    }
}

@Composable
private fun BigOutlined(label: String, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag).pressFeedback(),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) {
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** A bottom sheet with numbered steps, an optional big action and a big Close button. Text only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepsSheet(title: String, intro: String?, steps: List<String>, numbered: Boolean,
               action: Pair<String, () -> Unit>? = null, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()).testTag("stepsSheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            intro?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            steps.forEachIndexed { i, s ->
                Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (numbered) "${i + 1}." else "•", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
                    Text(s, style = MaterialTheme.typography.bodyLarge)
                }
            }
            action?.let { (label, run) ->
                Button(onClick = run, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).pressFeedback()) {
                    Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("sheetClose")) {
                Text(stringResource(R.string.btn_close), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
