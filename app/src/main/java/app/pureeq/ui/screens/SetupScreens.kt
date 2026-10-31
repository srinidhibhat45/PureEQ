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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureeq.R
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
import app.pureeq.ui.components.switchRow
import app.pureeq.ui.detailRes
import app.pureeq.ui.kindLabel
import app.pureeq.ui.outputName
import app.pureeq.ui.titleRes
import app.pureeq.ui.components.StepScaffold
import app.pureeq.ui.theme.StatusColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun IntroScreen(vm: AppViewModel) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    StepScaffold(
        title = stringResource(R.string.setup_intro_title),
        onBack = { vm.back() },
        step = 0,
        bottom = { PrimaryButton(stringResource(R.string.setup_intro_start), vm::beginChecks) },
    ) {
        Headline(
            stringResource(R.string.setup_intro_headline),
            stringResource(R.string.setup_intro_subtitle),
        )
        SectionCard {
            InfoRow(Icons.Rounded.Hearing, stringResource(R.string.setup_intro_step_check), stringResource(R.string.setup_intro_step_check_text))
            InfoRow(Icons.Rounded.Tune, stringResource(R.string.setup_intro_step_taste), stringResource(R.string.setup_intro_step_taste_text))
            InfoRow(Icons.Rounded.Equalizer, stringResource(R.string.setup_intro_step_eq), stringResource(R.string.setup_intro_step_eq_text))
        }
        SectionCard {
            Text(stringResource(R.string.setup_intro_tips), style = MaterialTheme.typography.titleSmall)
            listOf(
                R.string.setup_intro_tip_room,
                R.string.setup_intro_tip_effects,
                R.string.setup_intro_tip_noise_cancelling,
            ).forEach {
                Text("•  ${stringResource(it)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionCard {
            Row(Modifier.switchRow(draft.includeHearing, onChange = vm::setIncludeHearing), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.setup_intro_check), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(if (draft.includeHearing) draft.length.detailRes else R.string.setup_intro_check_skipped),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = draft.includeHearing, onCheckedChange = null)
            }
            if (draft.includeHearing) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    TestLength.entries.forEachIndexed { i, l ->
                        SegmentedButton(
                            selected = draft.length == l,
                            onClick = { vm.setTestLength(l) },
                            shape = SegmentedButtonDefaults.itemShape(i, TestLength.entries.size),
                        ) { Text(stringResource(l.titleRes)) }
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
        title = stringResource(R.string.setup_headphones_title),
        onBack = { vm.back() },
        step = 1,
        bottom = {
            PrimaryButton(stringResource(R.string.setup_continue), vm::afterHeadphones, enabled = output.isHeadphones)
            if (!output.isHeadphones) {
                TextButton(onClick = vm::afterHeadphones, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_headphones_continue_without)) }
            }
        },
    ) {
        val draft by vm.draft.collectAsStateWithLifecycle()
        LaunchedEffect(output) { vm.updateDevice() }
        Headline(stringResource(R.string.setup_headphones_headline), stringResource(R.string.setup_headphones_subtitle))
        SectionCard(padding = 22.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    deviceIcon(output), null, Modifier.size(40.dp),
                    tint = if (output.isHeadphones) MaterialTheme.colorScheme.primary else StatusColors.Warn,
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    val name = outputName(output.name)
                    val kind = kindLabel(output.kind)
                    Text(name, style = MaterialTheme.typography.titleLarge)
                    if (kind != name) {
                        Text(kind, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!output.isHeadphones) {
                Text(
                    stringResource(R.string.setup_headphones_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StatusColors.Warn,
                )
            }
        }
        // The type only matters for the listening check's reference curve.
        if (draft.includeHearing) {
            SectionCard {
                Text(stringResource(R.string.setup_headphones_type), style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    HeadphoneType.entries.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = draft.headphones == t,
                            onClick = { vm.setHeadphones(t) },
                            shape = SegmentedButtonDefaults.itemShape(i, HeadphoneType.entries.size),
                        ) { Text(stringResource(if (t == HeadphoneType.IN_EAR) R.string.setup_headphones_in_ear else R.string.setup_headphones_over_ear)) }
                    }
                }
                Text(
                    stringResource(R.string.setup_headphones_type_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SectionCard {
            Text(stringResource(R.string.setup_model_title), style = MaterialTheme.typography.titleSmall)
            val model = draft.headphoneModel
            if (model == null) {
                Text(
                    stringResource(R.string.setup_model_add),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { vm.openHeadphonePicker(forEditor = false) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.setup_model_find))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = StatusColors.Good)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(model.name, style = MaterialTheme.typography.bodyLarge)
                        // HeadphoneModel.targetLabel in the phone's language; the target's name stays as it is.
                        val corrected = model.target?.let { stringResource(R.string.setup_model_corrected_to, it) }
                            ?: stringResource(R.string.setup_model_corrected_autoeq)
                        Text(
                            if (draft.modelRemembered) "$corrected ${stringResource(R.string.setup_model_remembered)}" else corrected,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { vm.openHeadphonePicker(forEditor = false) }) { Text(stringResource(R.string.setup_model_change)) }
                }
                // HeadphoneModel.attribution in the phone's language.
                Text(stringResource(R.string.setup_model_attribution, model.source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            stringResource(R.string.setup_headphones_bluetooth),
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
        title = stringResource(R.string.setup_quiet_title),
        onBack = { vm.back() },
        step = 2,
        bottom = {
            PrimaryButton(
                stringResource(if (quiet.result != null) R.string.setup_continue else R.string.setup_quiet_skip),
                { vm.go(Screen.Volume) },
                enabled = !quiet.measuring,
            )
        },
    ) {
        Headline(
            stringResource(R.string.setup_quiet_headline),
            stringResource(R.string.setup_quiet_subtitle),
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
                    Text(stringResource(R.string.setup_quiet_level), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (quiet.measuring) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            val r = quiet.result
            when {
                quiet.failed -> Text(stringResource(R.string.setup_quiet_failed), color = StatusColors.Warn)
                r != null && r < NoiseMeter.QUIET_DB -> Text(stringResource(R.string.setup_quiet_good), color = StatusColors.Good)
                r != null && r < NoiseMeter.NOISY_DB -> Text(stringResource(R.string.setup_quiet_fair), color = StatusColors.Warn)
                r != null -> Text(stringResource(R.string.setup_quiet_noisy), color = StatusColors.Bad)
            }
            OutlinedButton(onClick = check, enabled = !quiet.measuring, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Mic, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (quiet.result == null) R.string.setup_quiet_check else R.string.setup_quiet_check_again))
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
        title = stringResource(R.string.setup_volume_title),
        onBack = { vm.back() },
        step = 3,
        bottom = { PrimaryButton(stringResource(R.string.setup_volume_confirm), vm::confirmVolume) },
    ) {
        Headline(
            stringResource(R.string.setup_volume_headline),
            stringResource(R.string.setup_volume_subtitle),
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ListeningOrb(active = true, size = 180.dp) }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = { vm.setVolume(volume - 1); volume = vm.currentVolume() }) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeDown, stringResource(R.string.setup_volume_down))
                }
                val volumeName = stringResource(R.string.setup_volume_name)
                val volumeText = stringResource(R.string.setup_volume_level, volume, max)
                Slider(
                    value = volume.toFloat(),
                    onValueChange = { vm.setVolume(it.roundToInt()); volume = it.roundToInt() },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = (max - min - 1).coerceAtLeast(0),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = volumeName; stateDescription = volumeText },
                )
                FilledIconButton(onClick = { vm.setVolume(volume + 1); volume = vm.currentVolume() }) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.setup_volume_up))
                }
            }
            Text(
                stringResource(R.string.setup_volume_level, volume, max),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        Text(
            stringResource(R.string.setup_volume_cant_hear),
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
        title = stringResource(R.string.setup_channel_title),
        onBack = { vm.redoVolume() },
        step = 4,
        bottom = {
            when (c.verdict) {
                null, ChannelVerdict.OK -> Unit
                ChannelVerdict.NOT_HEARD -> {
                    PrimaryButton(stringResource(R.string.setup_channel_volume_again), vm::redoVolume, icon = Icons.Rounded.Refresh)
                    TextButton(onClick = { vm.go(Screen.Hearing) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_channel_continue_anyway)) }
                }
                else -> {
                    PrimaryButton(stringResource(R.string.setup_channel_try_again), vm::retryChannel, icon = Icons.Rounded.Refresh)
                    TextButton(onClick = { vm.go(Screen.Hearing) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_channel_continue_anyway)) }
                }
            }
        },
    ) {
        Headline(stringResource(R.string.setup_channel_headline), stringResource(R.string.setup_channel_subtitle))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ListeningOrb(active = sounding && c.verdict == null, size = 170.dp)
        }
        when (val v = c.verdict) {
            null -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(
                        R.string.setup_channel_left to Ear.LEFT,
                        R.string.setup_channel_both to Ear.BOTH,
                        R.string.setup_channel_right to Ear.RIGHT,
                    ).forEach { (label, ear) ->
                        OutlinedButton(
                            onClick = { vm.answerChannel(ear) },
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) { Text(stringResource(label)) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { vm.playChannel(c.side) }) { Text(stringResource(R.string.setup_channel_play_again)) }
                    TextButton(onClick = { vm.answerChannel(null) }) { Text(stringResource(R.string.setup_channel_not_heard)) }
                }
            }
            else -> SectionCard {
                val (ok, text) = when (v) {
                    ChannelVerdict.OK -> true to R.string.setup_channel_ok
                    ChannelVerdict.SWAPPED -> false to R.string.setup_channel_swapped
                    ChannelVerdict.MONO -> false to R.string.setup_channel_mono
                    ChannelVerdict.NOT_HEARD -> false to R.string.setup_channel_missed
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, tint = if (ok) StatusColors.Good else StatusColors.Warn)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
