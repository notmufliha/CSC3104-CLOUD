package com.example.scamdetect.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.scamdetect.R
import com.example.scamdetect.logic.LearnContent
import com.example.scamdetect.logic.Quiz
import com.example.scamdetect.logic.QuizItem
import com.example.scamdetect.logic.ScamType

// "Learn to spot scams": scam-type cards and the practice quiz. Same screens, order and wording as ios-app
// UI/LearnViews.swift. The content comes from assets/learn/learn.json (shared with iOS).

/** Simple Home's big "Learn to spot scams" card. */
@Composable
fun LearnHomeCard(onOpen: () -> Unit) {
    AppCard(modifier = Modifier.testTag("learnCard").pressFeedback(), color = MaterialTheme.colorScheme.primaryContainer, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.learn_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(R.string.learn_card_line), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** The list: the quiz first, then one row per scam type. */
@Composable
fun LearnScreen(content: LearnContent, onQuiz: () -> Unit, onType: (String) -> Unit, onBack: () -> Unit) {
    Page(stringResource(R.string.learn_title), onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("learnList"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.learn_line), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppCard(color = MaterialTheme.colorScheme.primaryContainer) {
                Text(stringResource(R.string.learn_quiz), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.learn_quiz_line), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Button(onClick = onQuiz, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("startQuiz").pressFeedback()) {
                    Text(stringResource(R.string.learn_quiz), style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(stringResource(R.string.learn_types), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            if (content.types.isEmpty()) EmptyState(stringResource(R.string.empty_learn), icon = Icons.Filled.Info)
            Group {
                content.types.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp))
                    SettingsRow(Icons.Filled.Warning, t.title, tag = "type_${t.id}") { onType(t.id) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** One scam type: 3 warning signs, what to do, "Hear an example", the source. */
@Composable
fun ScamTypeScreen(type: ScamType?, onHear: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    Page(type?.title.orEmpty(), onBack) {
        if (type == null) return@Page
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("scamType"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppCard {
                Text(stringResource(R.string.learn_signs), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() })
                type.signs.forEach { sign ->
                    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = WarnYellow, modifier = Modifier.size(28.dp))
                        Text(sign, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            AppCard(color = SafeGreen.copy(alpha = 0.14f)) {
                Text(stringResource(R.string.learn_todo), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() })
                Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SafeGreen, modifier = Modifier.size(28.dp))
                    Text(type.todo, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Button(onClick = { onHear(type.script) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("hearExample").pressFeedback()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.learn_hear), style = MaterialTheme.typography.labelLarge)
            }
            Text(stringResource(R.string.learn_hear_line), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(
                onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW, type.sourceUrl.toUri())) } catch (e: ActivityNotFoundException) { } },
                modifier = Modifier.heightIn(min = 48.dp).testTag("typeSource"),
            ) { Text(stringResource(R.string.learn_source, type.sourceName), style = MaterialTheme.typography.bodyLarge) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * The practice quiz: 5 real caller lines, "Is this a scam?", instant feedback with one sentence why, a result.
 * Simple mode's result is only a friendly line (no score, no best score).
 */
@Composable
fun QuizScreen(content: LearnContent, simple: Boolean, best: Int?, onFinished: (Int) -> Unit, onBack: () -> Unit,
               /** Debug builds only (screenshots): "answered" (the first question answered) or "result" (4 of 5). */
               demo: String? = null) {
    // The round's lines are saved (not the items), so turning the phone keeps the same questions.
    var roundTexts by rememberSaveable { mutableStateOf(Quiz.round(content).map { it.text }) }
    val round = remember(roundTexts) { roundTexts.mapNotNull { t -> content.quiz.firstOrNull { it.text == t } } }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var answer by rememberSaveable { mutableStateOf<Boolean?>(if (demo == "answered") true else null) }
    var score by rememberSaveable { mutableIntStateOf(if (demo == "result") 4 else 0) }
    var done by rememberSaveable { mutableStateOf(demo == "result") }
    Page(stringResource(R.string.quiz_title), onBack) {
        val item = round.getOrNull(index)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("quiz"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (done) {
                QuizResult(score, simple, best)
                return@Column
            }
            if (item == null) return@Column
            Text(stringResource(R.string.quiz_progress, (index + 1).toString(), round.size.toString()),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("quizProgress"))
            AppCard {
                Text(stringResource(R.string.quiz_caller_says), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("“${item.text}”", style = MaterialTheme.typography.headlineSmall, fontStyle = FontStyle.Italic, modifier = Modifier.testTag("quizLine"))
            }
            Text(stringResource(R.string.quiz_question), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            answer?.let { Feedback(item, Quiz.isRight(item, it)) }
        }
        // The next step stays on screen at the bottom, even at the largest text (no hunting for it below the fold).
        when {
            done -> Button(onClick = {
                roundTexts = Quiz.round(content, avoid = roundTexts.toSet()).map { it.text }
                index = 0; answer = null; score = 0; done = false
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("quizAgain").pressFeedback()) {
                Text(stringResource(R.string.btn_try_again), style = MaterialTheme.typography.labelLarge)
            }
            item == null -> {}
            answer == null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuizButton(stringResource(R.string.quiz_scam), ScamRed, "answerScam", Modifier.weight(1f)) {
                    answer = true; if (Quiz.isRight(item, true)) score++
                }
                QuizButton(stringResource(R.string.quiz_safe), SafeGreen, "answerSafe", Modifier.weight(1f)) {
                    answer = false; if (Quiz.isRight(item, false)) score++
                }
            }
            else -> {
                val last = index == round.size - 1
                Button(onClick = {
                    if (last) { done = true; onFinished(score) } else { index++; answer = null }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("quizNext").pressFeedback()) {
                    Text(stringResource(if (last) R.string.quiz_see_result else R.string.btn_next), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun QuizButton(label: String, color: Color, tag: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 72.dp).testTag(tag).pressFeedback(),
        border = BorderStroke(3.dp, color)) {
        Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Feedback(item: QuizItem, right: Boolean) {
    val color = if (right) SafeGreen else WarnYellow
    AppCard(modifier = Modifier.testTag("quizFeedback").semantics { liveRegion = LiveRegionMode.Polite }, color = color.copy(alpha = 0.16f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (right) Icons.Filled.CheckCircle else Icons.Filled.Info, contentDescription = null, tint = if (right) SafeGreen else WarningInkFor(),
                modifier = Modifier.size(36.dp))
            Text(stringResource(if (right) R.string.quiz_right else R.string.quiz_wrong) + " " +
                stringResource(if (item.scam) R.string.quiz_was_scam else R.string.quiz_was_safe),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Text(item.why, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun QuizResult(score: Int, simple: Boolean, best: Int?) {
    AppCard(modifier = Modifier.testTag("quizResult").semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.primaryContainer) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp).align(Alignment.CenterHorizontally))
        // Simple mode: only the friendly line. Standard: the score too, and the best so far.
        if (!simple) Text(stringResource(R.string.quiz_score, score.toString(), Quiz.SIZE.toString()), style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("quizScore"),
            color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(stringResource(Quiz.resultLine(score)), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onPrimaryContainer)
        if (!simple && best != null) Text(stringResource(R.string.quiz_best, best.toString(), Quiz.SIZE.toString()),
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("quizBest"),
            color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
