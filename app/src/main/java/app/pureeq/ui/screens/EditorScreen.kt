package app.pureeq.ui.screens

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureeq.headphones.AutoEqTarget
import app.pureeq.profile.Exporters
import app.pureeq.profile.Profile
import app.pureeq.taste.TasteBand
import app.pureeq.ui.AppViewModel
import app.pureeq.ui.Screen
import app.pureeq.ui.components.EqChart
import app.pureeq.ui.components.Pill
import app.pureeq.ui.components.PrimaryButton
import app.pureeq.ui.components.SectionCard
import app.pureeq.ui.components.StepScaffold
import app.pureeq.ui.theme.EarColors
import app.pureeq.ui.theme.NumberStyle
import app.pureeq.ui.theme.StatusColors
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
    val reset = {
        vm.resetEditor()?.let { before ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val r = snackbar.showSnackbar("Back to PureEQ's suggestion", actionLabel = "Undo", duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) vm.restoreAdjustments(before)
            }
        }
        Unit
    }
    StepScaffold(
        title = if (e.isDraft) "Your sound" else "Fine-tune",
        onBack = { vm.back() },
        step = if (e.isDraft) 8 else null,
        snackbarHost = { SnackbarHost(snackbar) },
        bottom = {
            if (e.isDraft) {
                PrimaryButton("Save profile", vm::saveEditor)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { vm.go(Screen.Export(p.id)) },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Rounded.IosShare, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Export")
                    }
                    Button(onClick = vm::saveEditor, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("Done") }
                }
            }
        },
    ) {
        OutlinedTextField(
            value = p.name,
            onValueChange = { n -> vm.editProfile { it.copy(name = n.take(40)) } },
            label = { Text("Profile name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        )
        SectionCard {
            val eq = e.eq
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val split = eq != null && eq.leftCurve.indices.any { abs(eq.leftCurve[it] - eq.rightCurve[it]) >= 0.05 }
                if (split) {
                    Pill("Left", EarColors.Left)
                    Pill("Right", EarColors.Right)
                } else {
                    Pill("Both ears", MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.weight(1f))
                if (eq != null) {
                    Text("Preamp ${Exporters.signed(eq.preampDb)} dB", style = MaterialTheme.typography.labelMedium.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (eq != null) EqChart(curvesFor(eq, MaterialTheme.colorScheme.primary), height = 220.dp) else Spacer(Modifier.height(220.dp))
        }
        SectionCard {
            Text("Listen", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { vm.setPreview(!e.previewing || !playing) }) {
                    Icon(if (e.previewing && playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (e.previewing && playing) "Pause" else "Play")
                }
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    SegmentedButton(selected = e.eqOn, onClick = { vm.setEqOn(true) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("EQ on") }
                    SegmentedButton(selected = !e.eqOn, onClick = { vm.setEqOn(false) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Off") }
                }
            }
            Text(
                "On and off are loudness-matched, so you hear the change in balance, not just a volume jump.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MusicPicker(vm)
        }
        FineTuning(
            p,
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
    isDraft: Boolean,
    onReset: () -> Unit,
    onRedoTaste: () -> Unit,
    onPickModel: () -> Unit,
    edit: ((Profile) -> Profile) -> Unit,
) {
    val rec = p.recommended()
    val suggestedTaste = p.recommendedTaste
    val changed = buildList {
        if (p.headphoneModel != null && !p.headphoneCorrection) add("headphone correction")
        if (p.hearing != null && p.hearingStrength != rec.hearingStrength) add("ear tuning")
        suggestedTaste?.let { t -> TasteBand.entries.filter { p.taste[it] != t[it] }.forEach { add(it.label.lowercase()) } }
        if (p.balanceDb != rec.balanceDb) add("balance")
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Fine-tuning", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        changed.isNotEmpty() -> "Changed from PureEQ's suggestion: ${changed.joinToString(", ")}."
                        suggestedTaste == null -> "Ear tuning and balance are as PureEQ suggested."
                        else -> "Everything is as PureEQ suggested from your tests."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (changed.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onReset, enabled = changed.isNotEmpty()) {
                Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reset")
            }
        }
        if (suggestedTaste == null) {
            // Saved by 1.0.0, which didn't store the taste test's result.
            Text(
                "This profile was made with PureEQ 1.0, which didn't save its suggestion for bass, warmth, vocals and treble, so Reset can't bring those back. " +
                    "Redo the taste test (about 2 minutes) and PureEQ will remember its suggestion from then on.",
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.Warn,
            )
            if (!isDraft) FilledTonalButton(onClick = onRedoTaste, modifier = Modifier.fillMaxWidth()) { Text("Redo taste test") }
        }
        val model = p.headphoneModel
        if (model != null) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Headphone correction", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${model.name}, corrected to ${model.targetLabel}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(model.attribution, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = p.headphoneCorrection, onCheckedChange = { on -> edit { it.copy(headphoneCorrection = on) } })
                }
                TextButton(onClick = onPickModel, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    // Models picked with 1.3 carry AutoEq's published correction; picking again computes the new one.
                    Text(if (model.path == null && AutoEqTarget.hasMeasurement(model.source)) "Update to the Harman correction" else "Change model")
                }
            }
        } else {
            OutlinedButton(onClick = onPickModel, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Headphones, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add your headphones' measured correction")
            }
        }
        if (p.hearing != null) {
            Control(
                "Ear tuning",
                percent(p.hearingStrength),
                "How much of your listening check to apply. 75% applies most of it while leaving room for measurement noise; large differences always get proportionally less.",
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
                band.label,
                "${Exporters.signed(v)} dB",
                band.hint,
                suggested = r?.takeIf { it != v }?.let { "${Exporters.signed(it)} dB" },
                onRestore = { if (r != null) edit { it.copy(taste = it.taste.with(band, r)) } },
            ) {
                Slider(
                    value = v.toFloat(),
                    onValueChange = { x -> edit { it.copy(taste = it.taste.with(band, (x * 2).roundToInt() / 2.0)) } },
                    valueRange = band.minDb.toFloat()..band.maxDb.toFloat(),
                )
            }
        }
        Control(
            "Balance",
            balanceLabel(p.balanceDb),
            "Only needed if one side still sounds louder",
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
            TextButton(onClick = onRedoTaste, modifier = Modifier.fillMaxWidth()) { Text("Redo the taste test") }
        }
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
                Text("Suggested $suggested", style = MaterialTheme.typography.labelMedium.merge(NumberStyle))
            }
        }
        slider()
    }
}

private fun percent(x: Double) = "${(x * 100).roundToInt()}%"

private fun balanceLabel(b: Double) = when {
    abs(b) < 0.05 -> "Centre"
    b > 0 -> "Right +${"%.1f".format(b)} dB"
    else -> "Left +${"%.1f".format(-b)} dB"
}
