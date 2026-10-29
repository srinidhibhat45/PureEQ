package app.pureeq.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureeq.audio.NoiseMeter
import app.pureeq.dsp.Ear
import app.pureeq.hearing.HeadphoneType
import app.pureeq.hearing.TestLength
import app.pureeq.ui.AppViewModel
import app.pureeq.ui.ChannelUi
import app.pureeq.ui.ChannelVerdict
import app.pureeq.ui.Screen
import app.pureeq.ui.components.Headline
import app.pureeq.ui.components.InfoRow
import app.pureeq.ui.components.KeepScreenOn
import app.pureeq.ui.components.ListeningOrb
import app.pureeq.ui.components.PrimaryButton
import app.pureeq.ui.components.SectionCard
import app.pureeq.ui.components.StepScaffold
import app.pureeq.ui.theme.StatusColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun IntroScreen(vm: AppViewModel) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    StepScaffold(
        title = "New profile",
        onBack = { vm.back() },
        step = 0,
        bottom = { PrimaryButton("Start", vm::beginChecks) },
    ) {
        Headline(
            "Let's find your sound",
            "PureEQ measures how well each ear hears each part of the spectrum through these headphones, then learns what you like. Both become one EQ.",
        )
        SectionCard {
            InfoRow(Icons.Rounded.Hearing, "1 · Hearing check", "Short beeps in your left and right ear. Tap Yes whenever you hear one, even faintly. PureEQ picks each beep to learn as much as possible, so it's over in about 2 minutes.")
            InfoRow(Icons.Rounded.Tune, "2 · Taste", "Hear music as A and B, pick the one you like. Both are equally loud, so trust your ears. Usually 5 to 7 rounds.")
            InfoRow(Icons.Rounded.Equalizer, "3 · Your EQ", "Apply it to all audio on this phone, or export it to Wavelet, Poweramp Equalizer, JamesDSP and others.")
        }
        SectionCard {
            Text("Before you start", style = MaterialTheme.typography.titleSmall)
            listOf(
                "Find a quiet room and wear the headphones the way you normally do.",
                "Turn off other sound effects: Dolby Atmos, Adapt Sound, other EQ apps, and the EQ in your earbuds' app.",
                "Noise cancelling on helps. Transparency / ambient mode should be off.",
            ).forEach {
                Text("•  $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hearing check", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (draft.includeHearing) draft.length.detail else "Skipped: taste only",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = draft.includeHearing, onCheckedChange = vm::setIncludeHearing)
            }
            if (draft.includeHearing) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    TestLength.entries.forEachIndexed { i, l ->
                        SegmentedButton(
                            selected = draft.length == l,
                            onClick = { vm.setTestLength(l) },
                            shape = SegmentedButtonDefaults.itemShape(i, TestLength.entries.size),
                        ) { Text(l.label) }
                    }
                }
            }
        }
    }
}

@Composable
fun HeadphonesScreen(vm: AppViewModel) {
    val output by vm.output.collectAsStateWithLifecycle()
    StepScaffold(
        title = "Headphones",
        onBack = { vm.back() },
        step = 1,
        bottom = {
            PrimaryButton("Continue", vm::afterHeadphones, enabled = output.isHeadphones)
            if (!output.isHeadphones) {
                TextButton(onClick = vm::afterHeadphones, modifier = Modifier.fillMaxWidth()) { Text("Continue without headphones") }
            }
        },
    ) {
        val draft by vm.draft.collectAsStateWithLifecycle()
        LaunchedEffect(output) { vm.updateDevice() }
        Headline("Put on your headphones", "Your result belongs to these headphones and your ears together, so use the pair you'll listen with.")
        SectionCard(padding = 22.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    deviceIcon(output), null, Modifier.size(40.dp),
                    tint = if (output.isHeadphones) MaterialTheme.colorScheme.primary else StatusColors.Warn,
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(output.name, style = MaterialTheme.typography.titleLarge)
                    if (output.kind.label != output.name) {
                        Text(output.kind.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!output.isHeadphones) {
                Text(
                    "No headphones detected. A hearing check needs one sound per ear, which a phone speaker can't do.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StatusColors.Warn,
                )
            }
        }
        // The type only matters for the hearing check's reference curve.
        if (draft.includeHearing) {
            SectionCard {
                Text("Type", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    HeadphoneType.entries.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = draft.headphones == t,
                            onClick = { vm.setHeadphones(t) },
                            shape = SegmentedButtonDefaults.itemShape(i, HeadphoneType.entries.size),
                        ) { Text(if (t == HeadphoneType.IN_EAR) "In-ear / earbuds" else "Over-ear / on-ear") }
                    }
                }
                Text(
                    "In-ear and over-ear headphones reach the eardrum differently, so each is measured against its own reference.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SectionCard {
            Text("Model (optional)", style = MaterialTheme.typography.titleSmall)
            val model = draft.headphoneModel
            if (model == null) {
                Text(
                    "Add your model's measured correction from AutoEq's database of over 6,000 headphones. It brings them to the Harman target, and the hearing check fine-tunes the rest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { vm.openHeadphonePicker(forEditor = false) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Find my headphones")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = StatusColors.Good)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(model.name, style = MaterialTheme.typography.bodyLarge)
                        Text("Measured by ${model.source}, via AutoEq", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.openHeadphonePicker(forEditor = false) }) { Text("Change") }
                }
            }
        }
        Text(
            "Bluetooth works, but disable any EQ, \"adaptive\" or \"spatial\" sound in the earbuds' companion app for the test.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun QuietScreen(vm: AppViewModel) {
    val quiet by vm.quiet.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.measureNoise()
    }
    val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val check = {
        if (allowed) vm.measureNoise() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Once the microphone is allowed, check straight away.
    LaunchedEffect(Unit) {
        if (allowed && quiet.result == null && !quiet.measuring) vm.measureNoise()
    }
    StepScaffold(
        title = "Room check",
        onBack = { vm.back() },
        step = 2,
        bottom = {
            PrimaryButton(if (quiet.result != null) "Continue" else "Skip check", { vm.go(Screen.Volume) }, enabled = !quiet.measuring)
        },
    ) {
        Headline(
            "Is it quiet enough?",
            "Background noise covers up quiet beeps and makes your hearing look worse than it is. PureEQ can listen for 3 seconds to check. Nothing is recorded or saved.",
        )
        SectionCard(padding = 22.dp) {
            val level = quiet.level
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.GraphicEq, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        if (level != null) "${level.roundToInt()} dB(A)" else "--",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text("approximate room noise", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (quiet.measuring) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            val r = quiet.result
            when {
                quiet.failed -> Text("Couldn't use the microphone. You can skip this check.", color = StatusColors.Warn)
                r != null && r < NoiseMeter.QUIET_DB -> Text("Quiet. Good for an accurate test.", color = StatusColors.Good)
                r != null && r < NoiseMeter.NOISY_DB -> Text("A little noisy. Fine with noise cancelling on; quieter is better.", color = StatusColors.Warn)
                r != null -> Text("Too noisy for an accurate test. Find a quieter spot or turn on noise cancelling.", color = StatusColors.Bad)
            }
            OutlinedButton(onClick = check, enabled = !quiet.measuring, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Mic, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (quiet.result == null) "Check noise" else "Check again")
            }
        }
    }
}

@Composable
fun VolumeScreen(vm: AppViewModel) {
    KeepScreenOn()
    val c by vm.channel.collectAsStateWithLifecycle()
    if (c.checking) ChannelCheck(vm, c) else VolumeSetting(vm)
}

@Composable
private fun VolumeSetting(vm: AppViewModel) {
    var volume by remember { mutableIntStateOf(vm.currentVolume()) }
    // Follow the hardware volume keys too.
    LaunchedEffect(Unit) {
        while (true) {
            volume = vm.currentVolume()
            delay(200)
        }
    }
    val min = vm.volumeMin
    val max = vm.volumeMax
    StepScaffold(
        title = "Volume",
        onBack = { vm.back() },
        step = 3,
        bottom = { PrimaryButton("It's soft but clear", vm::confirmVolume) },
    ) {
        Headline(
            "Set the volume",
            "A beep is repeating in both ears. Adjust the volume until it is soft but clearly audible, like a quiet clock ticking. Keep this volume until the test is over.",
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ListeningOrb(active = true, size = 180.dp) }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = { vm.setVolume(volume - 1); volume = vm.currentVolume() }) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeDown, "Quieter")
                }
                Slider(
                    value = volume.toFloat(),
                    onValueChange = { vm.setVolume(it.roundToInt()); volume = it.roundToInt() },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = (max - min - 1).coerceAtLeast(0),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                FilledIconButton(onClick = { vm.setVolume(volume + 1); volume = vm.currentVolume() }) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Louder")
                }
            }
            Text(
                "Volume $volume of $max",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        Text(
            "Can't hear anything even at full volume? Check the headphones are connected and not muted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One beep on a random side right after the volume is set: catches swapped earbuds and mono audio. */
@Composable
private fun ChannelCheck(vm: AppViewModel, c: ChannelUi) {
    val sounding by vm.toneActive.collectAsStateWithLifecycle()
    BackHandler { vm.redoVolume() }
    StepScaffold(
        title = "Left and right",
        onBack = { vm.redoVolume() },
        step = 4,
        bottom = {
            when (c.verdict) {
                null, ChannelVerdict.OK -> Unit
                ChannelVerdict.NOT_HEARD -> {
                    PrimaryButton("Set the volume again", vm::redoVolume, icon = Icons.Rounded.Refresh)
                    TextButton(onClick = { vm.go(Screen.Hearing) }, modifier = Modifier.fillMaxWidth()) { Text("Continue anyway") }
                }
                else -> {
                    PrimaryButton("Try again", vm::retryChannel, icon = Icons.Rounded.Refresh)
                    TextButton(onClick = { vm.go(Screen.Hearing) }, modifier = Modifier.fillMaxWidth()) { Text("Continue anyway") }
                }
            }
        },
    ) {
        Headline("Where was the beep?", "One beep played on one side. Tell PureEQ where you heard it; this catches swapped earbuds and mono audio.")
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ListeningOrb(active = sounding && c.verdict == null, size = 170.dp)
        }
        when (val v = c.verdict) {
            null -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Left" to Ear.LEFT, "Both" to Ear.BOTH, "Right" to Ear.RIGHT).forEach { (label, ear) ->
                        OutlinedButton(
                            onClick = { vm.answerChannel(ear) },
                            modifier = Modifier.weight(1f).height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) { Text(label) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { vm.playChannel(c.side) }) { Text("Play again") }
                    TextButton(onClick = { vm.answerChannel(null) }) { Text("I didn't hear it") }
                }
            }
            else -> SectionCard {
                val (ok, text) = when (v) {
                    ChannelVerdict.OK -> true to "Correct. Starting the hearing check…"
                    ChannelVerdict.SWAPPED -> false to "That beep was on the other side, so left and right look swapped. Check the earbuds are in the correct ears (L/R markings), then try again."
                    ChannelVerdict.MONO -> false to "It sounded on both sides, so mono audio may be on. Turn it off and centre the balance in Settings > Accessibility > Audio, then try again."
                    ChannelVerdict.NOT_HEARD -> false to "The beep was missed. Check both earbuds are in and connected; the volume may be too low."
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, tint = if (ok) StatusColors.Good else StatusColors.Warn)
                    Spacer(Modifier.width(12.dp))
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
