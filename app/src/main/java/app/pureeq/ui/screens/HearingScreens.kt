package app.pureeq.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        title = "Hearing check",
        onBack = { confirmStop = true },
        scroll = false,
        actions = { IconButton(onClick = { vm.pauseHearing(PauseReason.USER) }) { Icon(Icons.Rounded.Pause, "Pause") } },
        bottom = {
            PrimaryButton("Yes, I heard it", { vm.answer(true) }, enabled = h.canSayYes || h.canSayNo)
            OutlinedButton(
                onClick = { vm.answer(false) },
                enabled = h.canSayNo,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("No") }
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
            Text("${(h.progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ListeningOrb(active = h.playing, size = 230.dp) }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                h.playing -> "Listen…"
                h.canSayNo -> "Did you hear a beep?"
                else -> "Get ready"
            },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Beeps come in either ear and get very quiet. Tap Yes as soon as you hear one, even faintly.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.weight(1f))
        if (h.guessingWarning) {
            SectionCard {
                Text(
                    "Some beeps are silent on purpose, and you said Yes to a few of them. Only tap Yes when you're sure you heard something.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = vm::dismissGuessingWarning) { Text("Got it") }
            }
        }
    }

    h.pause?.let { reason ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(reason.title) },
            text = { Text(reason.text) },
            confirmButton = {
                TextButton(onClick = vm::resumeHearing) {
                    Text(if (reason == PauseReason.VOLUME) "Restore volume and continue" else "Continue")
                }
            },
            dismissButton = { TextButton(onClick = { vm.home() }) { Text("Stop test") } },
        )
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop the hearing check?") },
            text = { Text("Your answers so far will be discarded.") },
            confirmButton = { TextButton(onClick = { confirmStop = false; vm.home() }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep going") } },
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
            cross = ear == Ear.LEFT,
        )
    }
    val reliability = result.reliability
    StepScaffold(
        title = "Hearing check",
        onBack = null,
        step = 6,
        bottom = {
            PrimaryButton("Continue to taste", { vm.go(Screen.TasteIntro) })
            TextButton(onClick = { vm.home(); vm.startNewProfile() }, modifier = Modifier.fillMaxWidth()) { Text("Start over") }
        },
    ) {
        Text("Done. Here's how you hear", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (correction.isEmpty()) {
                "Each point is how well you hear that band through these headphones, compared with a young normal-hearing listener on well-tuned ${type.label.lowercase()} headphones, with your mid range as the zero line. Lower on the chart means that band needs more help. It includes your headphones' own sound, which is exactly what the EQ corrects. The EQ follows the smooth trend, not every point."
            } else {
                "Each point is how well you hear that band, compared with a young normal-hearing listener, after your headphones' measured response is taken out, with your mid range as the zero line. Lower on the chart means that band needs more help. What's left is your ears and how the headphones sit on them. The EQ follows the smooth trend, not every point."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("✕ Left", EarColors.Left)
                Pill("○ Right", EarColors.Right)
                Spacer(Modifier.weight(1f))
                Text("dB, relative", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HearingChart(series, height = 220.dp)
        }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reliability", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                when (reliability) {
                    Reliability.GOOD -> Pill("Good", StatusColors.Good)
                    Reliability.FAIR -> Pill("Fair", StatusColors.Warn)
                    Reliability.POOR -> Pill("Low", StatusColors.Bad)
                }
            }
            Text(
                buildString {
                    append("Silent check beeps answered Yes: ${result.falseAlarms} of ${result.catchTrials}. ")
                    listOfNotNull(result.retestDiffLeft, result.retestDiffRight).takeIf { it.isNotEmpty() }?.let {
                        append("1 kHz re-check differed by up to ${"%.0f".format(it.maxOf { d -> kotlin.math.abs(d) })} dB. ")
                    }
                    if (result.trialLog.isNotEmpty()) append("Answers that didn't fit the rest: ${result.inconsistent} of ${result.trialLog.size}. ")
                    append("${result.trials} answers in ${result.durationSec / 60} min ${result.durationSec % 60} s.")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val edge = result.points.count { it.flag != ThresholdFlag.OK }
            if (edge > 0) {
                Text(
                    "$edge band(s) were at the edge of the test range; PureEQ limits the correction there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Warn,
                )
            }
            if (reliability == Reliability.POOR) {
                Text(
                    "The answers weren't very consistent. A quieter room and only answering Yes when sure usually fixes this; consider starting over.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Bad,
                )
            }
        }
        Text(
            "PureEQ is not a medical device and this is not a diagnosis. If you're worried about your hearing, see an audiologist.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(1.dp))
    }
}
