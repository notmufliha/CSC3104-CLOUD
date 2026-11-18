package com.example.scamdetect.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.scamdetect.R
import com.example.scamdetect.data.AppPrefs
import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.logic.Dashboard
import com.example.scamdetect.logic.Onboarding
import com.example.scamdetect.logic.PrivacyLevel
import com.example.scamdetect.logic.Risk
import com.example.scamdetect.logic.Tour
import kotlinx.coroutines.launch

// Simple mode (Home, Settings) and the settings pages both modes share: text size, family contact,
// Advanced settings, PIN. Same layout and wording as iOS UI/SimpleViews.swift.

// ---------------------------------------------------------------- Home (Simple mode)

/** Home's status: protecting (green ✓), not connected (yellow, "Fix it"), or still checking. */
enum class HomeStatus { PROTECTING, NOT_CONNECTED, CHECKING }

fun homeStatus(privacy: PrivacyLevel, connection: Boolean?): HomeStatus = when {
    privacy == PrivacyLevel.ON_DEVICE -> HomeStatus.PROTECTING        // needs no server
    connection == true -> HomeStatus.PROTECTING
    connection == false -> HomeStatus.NOT_CONNECTED
    else -> HomeStatus.CHECKING
}

@Composable
fun SimpleHomeScreen(
    status: HomeStatus,
    calls: List<CallRecord>,
    onPractice: () -> Unit,
    onFix: suspend () -> Boolean,
    onRefresh: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    var fixFailed by rememberSaveable { mutableStateOf(false) }
    var fixing by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Page(stringResource(R.string.app_name_text)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            StatusCard(status, fixing) {
                fixing = true
                scope.launch { fixFailed = !onFix(); fixing = false }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPractice,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).tourTarget(Tour.Target.PRACTICE_CALL).testTag("practiceCall"),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.btn_practice_call), style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.home_practice_line), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
            RecentCallsCard(calls)
            Spacer(Modifier.size(8.dp))
        }
    }
    if (fixFailed) YesNoDialog(
        title = stringResource(R.string.fix_title),
        text = stringResource(R.string.fix_text),
        yes = stringResource(R.string.btn_try_again),
        no = stringResource(R.string.btn_close),
        onYes = { fixFailed = false; fixing = true; scope.launch { fixFailed = !onFix(); fixing = false } },
        onNo = { fixFailed = false },
    )
}

@Composable
private fun StatusCard(status: HomeStatus, fixing: Boolean, onFix: () -> Unit) {
    val (level, title, line) = when (status) {
        HomeStatus.PROTECTING -> Triple(Risk.Level.GREEN, R.string.home_status_ok, R.string.home_status_ok_line)
        HomeStatus.NOT_CONNECTED -> Triple(Risk.Level.YELLOW, R.string.home_status_off, R.string.home_status_off_line)
        HomeStatus.CHECKING -> Triple(Risk.Level.LISTENING, R.string.home_status_checking, null)
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (status == HomeStatus.NOT_CONNECTED) WarnYellow.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(2.dp, levelColor(level)),
        modifier = Modifier.fillMaxWidth().testTag("homeStatus"),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RiskIcon(level, 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(title) + if (status == HomeStatus.PROTECTING) " ✓" else "",
                        style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    if (line != null) Text(stringResource(line), style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (status == HomeStatus.NOT_CONNECTED) Button(
                onClick = onFix, enabled = !fixing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("fixIt"),
            ) { Text(stringResource(if (fixing) R.string.status_checking else R.string.btn_fix_it), style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** The last 3 calls: icon + word + when. Information only (details are in "For family"). */
@Composable
private fun RecentCallsCard(calls: List<CallRecord>) {
    AppCard {
        Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (calls.isEmpty()) Text(stringResource(R.string.home_no_calls), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        calls.take(3).forEachIndexed { i, c ->
            if (i > 0) HorizontalDivider()
            val level = Dashboard.level(c.finalVerdict)
            val word = riskWord(level)
            val time = whenText(c.endedAt)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) { contentDescription = "$word, $time" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RiskIcon(level, 32.dp)
                Text(word, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(time, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** An existing user's one-time card: "Try Simple mode?" [Turn on Simple mode] [No, thanks]. */
@Composable
fun SimpleOfferCard(onYes: () -> Unit, onNo: () -> Unit) {
    AppCard(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.testTag("simpleOffer")) {
        Text(stringResource(R.string.offer_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(stringResource(R.string.offer_text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Button(onClick = onYes, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.btn_offer_yes), style = MaterialTheme.typography.labelLarge)
        }
        OutlinedButton(onClick = onNo, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.btn_offer_no), style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ---------------------------------------------------------------- settings rows

/** One settings row: icon, title, current value, chevron. At least 64 dp tall. */
@Composable
fun SettingsRow(icon: ImageVector, title: String, value: String? = null, valueColor: Color? = null, tag: String = "",
                modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick)
            .then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (value != null) Text(value, style = MaterialTheme.typography.bodyLarge, color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 70.dp))

/** Everything both modes put first: text size, read aloud, family contact. */
@Composable
fun EverydaySettings(
    textSize: String, readAloud: Boolean, familyName: String?,
    onOpen: (SettingsPage) -> Unit, onReadAloud: (Boolean) -> Unit,
) {
    Group {
        SettingsRow(Icons.Filled.Info, stringResource(R.string.set_text_size),
            stringResource(if (textSize == AppPrefs.TEXT_XLARGE) R.string.text_xlarge else R.string.text_large), tag = "rowTextSize") { onOpen(SettingsPage.TEXT_SIZE) }
        HorizontalDivider(Modifier.padding(start = 16.dp))
        ToggleRow(stringResource(R.string.set_read_aloud), stringResource(R.string.set_read_aloud_line), readAloud, onReadAloud)
        HorizontalDivider(Modifier.padding(start = 16.dp))
        SettingsRow(Icons.Filled.Phone, stringResource(R.string.set_family), familyName ?: stringResource(R.string.not_set), tag = "rowFamily") {
            onOpen(SettingsPage.FAMILY)
        }
    }
}

/** Simple mode's whole Settings: the everyday rows, Help, and "Advanced settings" (PIN-protected if set). */
@Composable
fun SimpleSettingsMenu(
    textSize: String, readAloud: Boolean, familyName: String?, pinSet: Boolean,
    onOpen: (SettingsPage) -> Unit, onReadAloud: (Boolean) -> Unit,
) {
    Page(stringResource(R.string.tab_settings)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EverydaySettings(textSize, readAloud, familyName, onOpen, onReadAloud)
            Group {
                SettingsRow(Icons.Filled.Info, stringResource(R.string.set_help), tag = "rowHelp") { onOpen(SettingsPage.HELP) }
                Divider()
                SettingsRow(if (pinSet) Icons.Filled.Lock else Icons.Filled.Settings, stringResource(R.string.set_advanced),
                    stringResource(R.string.set_advanced_line), tag = "rowAdvanced", modifier = Modifier.tourTarget(Tour.Target.PRIVACY_ROW)) {
                    onOpen(SettingsPage.ADVANCED)
                }
            }
        }
    }
}

/** What Advanced settings holds (and the full app's Settings shows directly). */
@Composable
fun AdvancedRows(
    simple: Boolean, showCareful: Boolean, onShowCareful: (Boolean) -> Unit, onSimple: (Boolean) -> Unit,
    pinSet: Boolean, privacy: PrivacyLevel, connection: Boolean?, modelsUpdated: Boolean, callCount: Int,
    onOpen: (SettingsPage) -> Unit,
) {
    Group {
        ToggleRow(stringResource(R.string.set_show_careful), stringResource(R.string.set_show_careful_line), showCareful, onShowCareful)
        HorizontalDivider(Modifier.padding(start = 16.dp))
        ToggleRow(stringResource(R.string.set_simple_mode), stringResource(R.string.set_simple_mode_line), simple, onSimple)
        HorizontalDivider(Modifier.padding(start = 16.dp))
        SettingsRow(Icons.Filled.Lock, stringResource(R.string.set_pin),
            stringResource(if (pinSet) R.string.set_pin_line_on else R.string.set_pin_line_off), tag = "rowPin") { onOpen(SettingsPage.PIN) }
    }
    Text(stringResource(R.string.set_group_checking), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Group {
        SettingsRow(Icons.Filled.Lock, stringResource(R.string.set_how_checked),
            if (simple) stringResource(privacyTagRes(privacy)) else "${privacy.shortTitle()} · ${stringResource(privacyTagRes(privacy))}",
            modifier = if (simple) Modifier else Modifier.tourTarget(Tour.Target.PRIVACY_ROW)) { onOpen(SettingsPage.PRIVACY) }
        Divider()
        SettingsRow(Icons.Filled.Share, stringResource(R.string.set_connection), connectionLabel(connection), connectionColor(connection)) {
            onOpen(SettingsPage.SERVER)
        }
        Divider()
        SettingsRow(Icons.Filled.Build, stringResource(R.string.set_updates),
            stringResource(if (modelsUpdated) R.string.models_updated else R.string.models_built_in)) { onOpen(SettingsPage.MODELS) }
    }
    Group {
        SettingsRow(Icons.Filled.Person, stringResource(R.string.set_my_data), callsCount(callCount)) { onOpen(SettingsPage.DATA) }
    }
}

@Composable
fun callsCount(n: Int) = if (n == 1) stringResource(R.string.calls_one) else stringResource(R.string.calls_many, n.toString())

@Composable
fun AdvancedPage(content: @Composable ColumnScope.() -> Unit, onBack: () -> Unit) {
    Page(stringResource(R.string.set_advanced), onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

// ---------------------------------------------------------------- pages

@Composable
fun TextSizePage(current: String, onPick: (String) -> Unit, onBack: () -> Unit) {
    Page(stringResource(R.string.set_text_size), onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { TextSizeChoices(current, onPick) }
    }
}

@Composable
fun FamilyPage(name: String, phone: String, onSave: (String, String) -> Unit, onBack: () -> Unit) {
    var n by rememberSaveable(name) { mutableStateOf(name) }
    var p by rememberSaveable(phone) { mutableStateOf(phone) }
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    val saved = stringResource(R.string.saved)
    val needPhone = stringResource(R.string.family_need_phone)
    Page(stringResource(R.string.set_family), onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.family_page_line), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ContactFields(n, p, { n = it; note = null }, { p = it; note = null })
            Button(onClick = {
                if (p.isBlank()) note = needPhone else { onSave(n, p); note = "$saved ✓" }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("familySave")) {
                Text(stringResource(R.string.btn_save), style = MaterialTheme.typography.labelLarge)
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) }
            if (phone.isNotBlank()) OutlinedButton(onClick = { n = ""; p = ""; onSave("", ""); note = null },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                border = BorderStroke(2.dp, ScamRed)) {
                Text(stringResource(R.string.btn_remove_contact), style = MaterialTheme.typography.labelLarge, color = WarningInkFor())
            }
        }
    }
}

@Composable
fun PinSettingsPage(pinSet: Boolean, onSet: (String) -> Unit, onClear: () -> Unit, onBack: () -> Unit) {
    var newPin by rememberSaveable { mutableStateOf("") }
    var changing by rememberSaveable { mutableStateOf(!pinSet) }
    var done by rememberSaveable { mutableStateOf(false) }
    Page(stringResource(R.string.set_pin), onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.pin_setup_line), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(if (pinSet) R.string.set_pin_line_on else R.string.set_pin_line_off), style = MaterialTheme.typography.titleMedium)
            if (changing) {
                NewPinFields { newPin = it }
                Button(onClick = { onSet(newPin); changing = false; done = true }, enabled = newPin.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("pinSave")) {
                    Text(stringResource(R.string.btn_save), style = MaterialTheme.typography.labelLarge)
                }
            } else {
                if (done) Text(stringResource(R.string.saved) + " ✓", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = { changing = true; done = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(if (pinSet) R.string.btn_change_pin else R.string.btn_set_pin), style = MaterialTheme.typography.labelLarge)
                }
            }
            if (pinSet) OutlinedButton(onClick = { onClear(); changing = true; done = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                border = BorderStroke(2.dp, ScamRed)) {
                Text(stringResource(R.string.btn_turn_off_pin), style = MaterialTheme.typography.labelLarge, color = WarningInkFor())
            }
        }
    }
}
