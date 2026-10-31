package app.pureeq.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureeq.R
import app.pureeq.dsp.Ear
import app.pureeq.hearing.HeadphoneType
import app.pureeq.hearing.Reliability
import app.pureeq.hearing.ThresholdFlag
import app.pureeq.profile.Exporters
import app.pureeq.profile.HearingCompensation
import app.pureeq.ui.AppViewModel
import app.pureeq.ui.PauseReason
import app.pureeq.ui.Screen
import app.pureeq.ui.components.HearingChart
import app.pureeq.ui.components.HearingSeries
import app.pureeq.ui.components.KeepScreenOn
import app.pureeq.ui.components.ListeningOrb
import app.pureeq.ui.components.Pill
import app.pureeq.ui.components.PrimaryButton
import app.pureeq.ui.components.SectionCard
import app.pureeq.ui.components.StepScaffold
import app.pureeq.ui.theme.EarColors
import app.pureeq.ui.theme.StatusColors

@Composable
fun HearingScreen(vm: AppViewModel) {
    KeepScreenOn()
    val h by vm.hearing.collectAsStateWithLifecycle()
    var confirmStop by remember { mutableStateOf(false) }
    BackHandler { confirmStop = true }
    StepScaffold(
        title = stringResource(R.string.check_title),
        onBack = { confirmStop = true },
        scroll = false,
        actions = { IconButton(onClick = { vm.pauseHearing(PauseReason.USER) }) { Icon(Icons.Rounded.Pause, stringResource(R.string.check_pause)) } },
        bottom = {
            PrimaryButton(stringResource(R.string.check_yes), { vm.answer(true) }, enabled = h.canSayYes || h.canSayNo)
            OutlinedButton(
                onClick = { vm.answer(false) },
                enabled = h.canSayNo,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text(stringResource(R.string.check_no)) }
            TextButton(onClick = vm::replayBeep, enabled = h.canReplay, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Replay, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.check_replay))
            }
        },
    ) {
        LinearProgressIndicator(
            progress = { h.progress },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            strokeCap = StrokeCap.Round,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                h.freq?.let { Exporters.freqLabel(it) } ?: "",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.check_progress, (h.progress * 100).toInt()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ListeningOrb(active = h.playing, size = 230.dp) }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                when {
                    h.playing -> R.string.check_listen
                    h.canSayNo -> R.string.check_question
                    else -> R.string.check_get_ready
                },
            ),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.check_instructions),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.weight(1f))
        if (h.guessingWarning) {
            SectionCard {
                Text(
                    stringResource(R.string.check_guessing),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = vm::dismissGuessingWarning) { Text(stringResource(R.string.check_got_it)) }
            }
        }
    }

    h.pause?.let { reason ->
        // PauseReason's title and text in the phone's language.
        val (title, text) = when (reason) {
            PauseReason.USER -> R.string.check_paused to R.string.check_paused_text
            PauseReason.VOLUME -> R.string.check_volume_changed to R.string.check_volume_changed_text
            PauseReason.HEADPHONES -> R.string.check_headphones_disconnected to R.string.check_headphones_disconnected_text
            PauseReason.BACKGROUND -> R.string.check_paused to R.string.check_paused_background
        }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(title)) },
            text = { Text(stringResource(text)) },
            confirmButton = {
                TextButton(onClick = vm::resumeHearing) {
                    Text(stringResource(if (reason == PauseReason.VOLUME) R.string.check_restore_volume else R.string.check_continue))
                }
            },
            dismissButton = { TextButton(onClick = { vm.home() }) { Text(stringResource(R.string.check_stop_test)) } },
        )
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.check_stop_title)) },
            text = { Text(stringResource(R.string.check_stop_text)) },
            confirmButton = { TextButton(onClick = { confirmStop = false; vm.home() }) { Text(stringResource(R.string.check_stop)) } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.check_keep_going)) } },
        )
    }
}

@Composable
fun HearingDoneScreen(vm: AppViewModel) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val result = draft.hearing ?: return
    val type = result.headphones ?: HeadphoneType.guess(draft.device?.name)
    val correction = draft.headphoneModel?.filters.orEmpty()
    val series = listOf(Ear.LEFT, Ear.RIGHT).map { ear ->
        HearingSeries(
            HearingCompensation.chartPoints(result, type, ear, correction),
            if (ear == Ear.LEFT) EarColors.Left else EarColors.Right,
            square = ear == Ear.RIGHT,
            label = stringResource(if (ear == Ear.LEFT) R.string.ear_left else R.string.ear_right),
        )
    }
    val reliability = result.reliability
    StepScaffold(
        title = stringResource(R.string.check_title),
        onBack = null,
        step = 6,
        bottom = {
            PrimaryButton(stringResource(R.string.check_done_continue), { vm.go(Screen.TasteIntro) })
            TextButton(onClick = { vm.home(); vm.startNewProfile() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.check_done_start_over)) }
        },
    ) {
        Text(stringResource(R.string.check_done_headline), style = MaterialTheme.typography.headlineSmall)
        Text(
            if (correction.isEmpty()) {
                val reference = when (type) {
                    HeadphoneType.IN_EAR -> R.string.check_done_reference_in_ear
                    HeadphoneType.OVER_EAR -> R.string.check_done_reference_over_ear
                }
                stringResource(R.string.check_done_explained, stringResource(reference))
            } else {
                stringResource(R.string.check_done_explained_corrected)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.check_done_left), EarColors.Left)
                Pill(stringResource(R.string.check_done_right), EarColors.Right)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.check_done_axis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HearingChart(series, height = 220.dp)
        }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.check_done_reliability), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                when (reliability) {
                    Reliability.GOOD -> Pill(stringResource(R.string.check_reliability_good), StatusColors.Good)
                    Reliability.FAIR -> Pill(stringResource(R.string.check_reliability_fair), StatusColors.Warn)
                    Reliability.POOR -> Pill(stringResource(R.string.check_reliability_low), StatusColors.Bad)
                }
            }
            // Whole sentences, one resource each, joined into one paragraph.
            val retest = listOfNotNull(result.retestDiffLeft, result.retestDiffRight).takeIf { it.isNotEmpty() }
            Text(
                listOfNotNull(
                    stringResource(R.string.check_done_false_alarms, result.falseAlarms, result.catchTrials),
                    retest?.let { stringResource(R.string.check_done_retest, "%.0f".format(it.maxOf { d -> kotlin.math.abs(d) })) },
                    if (result.trialLog.isNotEmpty()) stringResource(R.string.check_done_inconsistent, result.inconsistent, result.trialLog.size) else null,
                    pluralStringResource(R.plurals.check_done_answers, result.trials, result.trials, result.durationSec / 60, result.durationSec % 60),
                ).joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val edge = result.points.count { it.flag != ThresholdFlag.OK }
            if (edge > 0) {
                Text(
                    pluralStringResource(R.plurals.check_done_edge, edge, edge),
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Warn,
                )
            }
            if (reliability == Reliability.POOR) {
                Text(
                    stringResource(R.string.check_done_poor),
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Bad,
                )
            }
        }
        Text(
            stringResource(R.string.check_done_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(1.dp))
    }
}
