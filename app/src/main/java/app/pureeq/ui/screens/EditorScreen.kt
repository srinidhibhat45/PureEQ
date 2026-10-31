package app.pureeq.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureeq.R
import app.pureeq.headphones.AutoEqTarget
import app.pureeq.profile.Exporters
import app.pureeq.profile.Profile
import app.pureeq.profile.VolumeBass
import app.pureeq.system.EqRuntime
import app.pureeq.taste.TasteBand
import app.pureeq.ui.AppViewModel
import app.pureeq.ui.Screen
import app.pureeq.ui.components.EqChart
import app.pureeq.ui.components.Pill
import app.pureeq.ui.components.PrimaryButton
import app.pureeq.ui.components.SectionCard
import app.pureeq.ui.components.StepScaffold
import app.pureeq.ui.hintRes
import app.pureeq.ui.theme.EarColors
import app.pureeq.ui.theme.NumberStyle
import app.pureeq.ui.theme.StatusColors
import app.pureeq.ui.titleRes
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun EditorScreen(vm: AppViewModel) {
    val ui by vm.editor.collectAsStateWithLifecycle()
    val playing by vm.musicPlaying.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val e = ui ?: return
    val p = e.profile
    val resetDone = stringResource(R.string.editor_reset_done)
    val undo = stringResource(R.string.editor_undo)
    val reset = {
        vm.resetEditor()?.let { before ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val r = snackbar.showSnackbar(resetDone, actionLabel = undo, duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) vm.restoreAdjustments(before)
            }
        }
        Unit
    }
    StepScaffold(
        title = stringResource(if (e.isDraft) R.string.editor_title_draft else R.string.editor_title),
        onBack = { vm.back() },
        step = if (e.isDraft) 8 else null,
        snackbarHost = { SnackbarHost(snackbar) },
        bottom = {
            if (e.isDraft) {
                PrimaryButton(stringResource(R.string.editor_save_profile), vm::saveEditor)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { vm.go(Screen.Export(p.id)) },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Rounded.IosShare, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.editor_export))
                    }
                    Button(onClick = vm::saveEditor, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.editor_done)) }
                }
            }
        },
    ) {
        OutlinedTextField(
            value = p.name,
            onValueChange = { n -> vm.editProfile { it.copy(name = n.take(40)) } },
            label = { Text(stringResource(R.string.editor_profile_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        )
        SectionCard {
            val eq = e.eq
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val split = eq != null && eq.leftCurve.indices.any { abs(eq.leftCurve[it] - eq.rightCurve[it]) >= 0.05 }
                if (split) {
                    Pill(stringResource(R.string.editor_left), EarColors.Left)
                    Pill(stringResource(R.string.editor_right), EarColors.Right)
                } else {
                    Pill(stringResource(R.string.editor_both_ears), MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.weight(1f))
                if (eq != null) {
                    Text(stringResource(R.string.editor_preamp, Exporters.signed(eq.preampDb)), style = MaterialTheme.typography.labelMedium.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (eq != null) EqChart(curvesFor(eq, MaterialTheme.colorScheme.primary), height = 220.dp) else Spacer(Modifier.height(220.dp))
        }
        SectionCard {
            Text(stringResource(R.string.editor_listen), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { vm.setPreview(!e.previewing || !playing) }) {
                    Icon(if (e.previewing && playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (e.previewing && playing) R.string.editor_pause else R.string.editor_play))
                }
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    SegmentedButton(selected = e.eqOn, onClick = { vm.setEqOn(true) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.editor_eq_on)) }
                    SegmentedButton(selected = !e.eqOn, onClick = { vm.setEqOn(false) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.editor_eq_off)) }
                }
            }
            Text(
                stringResource(R.string.editor_loudness_matched),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MusicPicker(vm)
        }
        // What volume-linked bass adds right now, when the system-wide EQ is playing this profile.
        val shelves by EqRuntime.volumeShelves.collectAsStateWithLifecycle()
        val activeId by vm.store.activeId.collectAsStateWithLifecycle()
        FineTuning(
            p,
            subBassCostDb = e.eq?.subBassCostDb ?: 0.0,
            volumeNow = shelves.takeIf { !e.isDraft && p.id == activeId },
            isDraft = e.isDraft,
            onReset = reset,
            onRedoTaste = { vm.redoTaste(p.id) },
            onPickModel = { vm.openHeadphonePicker(forEditor = true) },
            edit = vm::editProfile,
        )
    }
}

/**
 * Every adjustment in one card, under one Reset that returns them all to what PureEQ suggested
 * from the tests. A moved control shows its suggested value, and tapping that restores just it.
 */
@Composable
private fun FineTuning(
    p: Profile,
    subBassCostDb: Double,
    /** What volume-linked bass adds right now; null when the system-wide EQ isn't playing this profile. */
    volumeNow: VolumeBass.Shelves?,
    isDraft: Boolean,
    onReset: () -> Unit,
    onRedoTaste: () -> Unit,
    onPickModel: () -> Unit,
    edit: ((Profile) -> Profile) -> Unit,
) {
    val rec = p.recommended()
    val suggestedTaste = p.recommendedTaste
    // What differs from the suggestion, each as it reads in the "Changed from…" sentence.
    val changed = buildList<Int> {
        if (p.headphoneModel != null && !p.headphoneCorrection) add(R.string.editor_changed_headphone_correction)
        if (p.fullSubBass) add(R.string.editor_changed_full_sub_bass)
        if (!p.volumeBass && p.tasteLevel != null) add(R.string.editor_changed_volume_bass)
        if (p.hearing != null && p.hearingStrength != rec.hearingStrength) add(R.string.editor_changed_ear_tuning)
        suggestedTaste?.let { t -> TasteBand.entries.filter { p.taste[it] != t[it] }.forEach { add(it.changedRes) } }
        if (p.balanceDb != rec.balanceDb) add(R.string.editor_changed_balance)
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.editor_fine_tuning), style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        changed.isNotEmpty() -> stringResource(
                            R.string.editor_changed,
                            changed.map { stringResource(it) }.joinToString(stringResource(R.string.editor_changed_separator)),
                        )
                        suggestedTaste == null -> stringResource(R.string.editor_as_suggested_no_taste)
                        else -> stringResource(R.string.editor_as_suggested)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (changed.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onReset, enabled = changed.isNotEmpty()) {
                Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.editor_reset))
            }
        }
        if (suggestedTaste == null) {
            // Saved by 1.0.0, which didn't store the taste test's result.
            Text(
                stringResource(R.string.editor_legacy_profile),
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.Warn,
            )
            if (!isDraft) FilledTonalButton(onClick = onRedoTaste, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.editor_redo_taste_legacy)) }
        }
        val model = p.headphoneModel
        if (model != null) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.editor_headphone_correction), style = MaterialTheme.typography.titleSmall)
                        val target = model.target
                        Text(
                            if (target != null) {
                                stringResource(R.string.editor_corrected_to_target, model.name, target)
                            } else {
                                stringResource(R.string.editor_corrected_to_autoeq_target, model.name)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(stringResource(R.string.editor_headphone_data, model.source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = p.headphoneCorrection, onCheckedChange = { on -> edit { it.copy(headphoneCorrection = on) } })
                }
                TextButton(onClick = onPickModel, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    // Models picked with 1.3 carry AutoEq's published correction; picking again computes the new one.
                    Text(stringResource(if (model.path == null && AutoEqTarget.hasMeasurement(model.source)) R.string.editor_update_correction else R.string.editor_change_model))
                }
            }
        } else {
            OutlinedButton(onClick = onPickModel, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Headphones, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.editor_add_correction))
            }
        }
        if (p.hearing != null) {
            Control(
                stringResource(R.string.editor_ear_tuning),
                percent(p.hearingStrength),
                stringResource(R.string.editor_ear_tuning_hint),
                suggested = percent(rec.hearingStrength).takeIf { p.hearingStrength != rec.hearingStrength },
                onRestore = { edit { it.copy(hearingStrength = rec.hearingStrength) } },
            ) {
                Slider(
                    value = p.hearingStrength.toFloat(),
                    onValueChange = { v -> edit { it.copy(hearingStrength = (v * 20).roundToInt() / 20.0) } },
                    valueRange = 0f..1f,
                    steps = 19,
                )
            }
        }
        TasteBand.entries.forEach { band ->
            val v = p.taste[band]
            val r = suggestedTaste?.get(band)
            Control(
                stringResource(band.titleRes),
                "${Exporters.signed(v)} dB",
                stringResource(if (band == TasteBand.TREBLE && p.taste.legacyTreble) R.string.editor_treble_legacy_hint else band.hintRes),
                suggested = r?.takeIf { it != v }?.let { "${Exporters.signed(it)} dB" },
                onRestore = { if (r != null) edit { it.copy(taste = it.taste.with(band, r)) } },
            ) {
                Slider(
                    value = v.toFloat(),
                    onValueChange = { x -> edit { it.copy(taste = it.taste.with(band, (x * 2).roundToInt() / 2.0)) } },
                    valueRange = band.minDb.toFloat()..band.maxDb.toFloat(),
                )
            }
            // Over-ears hold bass below 50 Hz near the 50 Hz level; worth offering only when that saves volume.
            if (band == TasteBand.BASS && (p.fullSubBass || subBassCostDb >= 0.5)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.editor_full_sub_bass), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(
                                if (p.fullSubBass) R.string.editor_full_sub_bass_on else R.string.editor_full_sub_bass_off,
                                "%.1f".format(subBassCostDb),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = p.fullSubBass, onCheckedChange = { on -> edit { it.copy(fullSubBass = on) } })
                }
            }
            if (band == TasteBand.BASS) VolumeBassRow(p, volumeNow, edit)
        }
        Control(
            stringResource(R.string.editor_balance),
            balanceLabel(p.balanceDb),
            stringResource(R.string.editor_balance_hint),
            suggested = balanceLabel(rec.balanceDb).takeIf { p.balanceDb != rec.balanceDb },
            onRestore = { edit { it.copy(balanceDb = rec.balanceDb) } },
        ) {
            Slider(
                value = p.balanceDb.toFloat(),
                onValueChange = { x -> edit { it.copy(balanceDb = (x * 2).roundToInt() / 2.0) } },
                valueRange = -6f..6f,
            )
        }
        if (!isDraft && suggestedTaste != null) {
            TextButton(onClick = onRedoTaste, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.editor_redo_taste)) }
        }
    }
}

/**
 * Bass that follows the volume ([VolumeBass]): the switch, what it does, and what it adds at the
 * volume playing now. Profiles without a tuning volume (before 1.5) can't use it until the taste
 * test is redone.
 */
@Composable
private fun VolumeBassRow(p: Profile, now: VolumeBass.Shelves?, edit: ((Profile) -> Profile) -> Unit) {
    val usable = p.tasteLevel != null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.editor_volume_bass), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    when {
                        !usable -> R.string.editor_volume_bass_needs_taste
                        p.volumeBass -> R.string.editor_volume_bass_on
                        else -> R.string.editor_volume_bass_off
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (usable && p.volumeBass && now != null) {
                Text(
                    if (now.isFlat) {
                        stringResource(R.string.editor_volume_bass_now_flat)
                    } else {
                        stringResource(R.string.editor_volume_bass_now, Exporters.signed(now.bassDb), Exporters.signed(now.trebleDb))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Switch(checked = usable && p.volumeBass, enabled = usable, onCheckedChange = { on -> edit { it.copy(volumeBass = on) } })
    }
}

/** One adjustment. Once moved off PureEQ's suggestion it shows that value, and tapping it puts it back. */
@Composable
private fun Control(
    title: String,
    value: String,
    hint: String,
    suggested: String?,
    onRestore: () -> Unit,
    slider: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.labelLarge.merge(NumberStyle), color = MaterialTheme.colorScheme.primary)
        }
        if (suggested != null) {
            TextButton(
                onClick = onRestore,
                modifier = Modifier.align(Alignment.End),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(Icons.Rounded.RestartAlt, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.editor_suggested, suggested), style = MaterialTheme.typography.labelMedium.merge(NumberStyle))
            }
        }
        slider()
    }
}

private fun percent(x: Double) = "${(x * 100).roundToInt()}%"

@Composable
private fun balanceLabel(b: Double) = when {
    abs(b) < 0.05 -> stringResource(R.string.editor_balance_centre)
    b > 0 -> stringResource(R.string.editor_balance_right, "%.1f".format(b))
    else -> stringResource(R.string.editor_balance_left, "%.1f".format(-b))
}

/** A taste band in the list of what was changed from PureEQ's suggestion. */
@get:StringRes
private val TasteBand.changedRes: Int
    get() = when (this) {
        TasteBand.BASS -> R.string.editor_changed_bass
        TasteBand.WARMTH -> R.string.editor_changed_warmth
        TasteBand.VOCALS -> R.string.editor_changed_vocals
        TasteBand.TREBLE -> R.string.editor_changed_treble
        TasteBand.AIR -> R.string.editor_changed_air
    }
