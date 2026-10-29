package app.pureeq.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import app.pureeq.profile.Exporters
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
    val rec = p.recommended()
    val reset = {
        vm.resetEditor()?.let { before ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val r = snackbar.showSnackbar("Back to the recommended sound", actionLabel = "Undo", duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) vm.restoreAdjustments(before)
            }
        }
    }
    StepScaffold(
        title = if (e.isDraft) "Your sound" else "Fine-tune",
        onBack = { vm.back() },
        step = if (e.isDraft) 8 else null,
        actions = {
            TextButton(onClick = { reset() }, enabled = !p.isRecommended) {
                Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reset")
            }
        },
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
        if (p.hearing != null) {
            SectionCard {
                ValueHeader(
                    "Hearing correction",
                    percent(p.hearingStrength),
                    recommended = percent(rec.hearingStrength).takeIf { p.hearingStrength != rec.hearingStrength },
                )
                Slider(
                    value = p.hearingStrength.toFloat(),
                    onValueChange = { v -> vm.editProfile { it.copy(hearingStrength = (v * 20).roundToInt() / 20.0) } },
                    valueRange = 0f..1f,
                    steps = 19,
                )
                Text(
                    "50% is the audiologists' half-gain rule: clearer detail without harshness. 100% fully flattens the measured differences.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SectionCard {
            Text("Taste", style = MaterialTheme.typography.titleSmall)
            TasteBand.entries.forEach { band ->
                val v = p.taste[band]
                val r = p.recommendedTaste?.get(band)
                Column {
                    ValueHeader(band.label, "${Exporters.signed(v)} dB", band.hint, recommended = r?.takeIf { it != v }?.let { "${Exporters.signed(it)} dB" })
                    Slider(
                        value = v.toFloat(),
                        onValueChange = { x -> vm.editProfile { it.copy(taste = it.taste.with(band, (x * 2).roundToInt() / 2.0)) } },
                        valueRange = band.minDb.toFloat()..band.maxDb.toFloat(),
                    )
                }
            }
            if (p.recommendedTaste == null) {
                Text(
                    "This profile was saved by PureEQ 1.0, which didn't keep the taste test's result, so Reset leaves these four as they are. " +
                        "Redo the taste test to give it one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!e.isDraft) {
                OutlinedButton(onClick = { vm.redoTaste(p.id) }, modifier = Modifier.fillMaxWidth()) { Text("Redo taste test") }
            }
        }
        SectionCard {
            val b = p.balanceDb
            ValueHeader(
                "Balance",
                balanceLabel(b),
                "Only needed if one side still sounds louder",
                recommended = balanceLabel(rec.balanceDb).takeIf { b != rec.balanceDb },
            )
            Slider(
                value = b.toFloat(),
                onValueChange = { x -> vm.editProfile { it.copy(balanceDb = (x * 2).roundToInt() / 2.0) } },
                valueRange = -6f..6f,
            )
        }
    }
}

/** [recommended] is shown under the value once it has been moved away from the recommendation. */
@Composable
private fun ValueHeader(title: String, value: String, hint: String? = null, recommended: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.labelLarge.merge(NumberStyle), color = MaterialTheme.colorScheme.primary)
            if (recommended != null) {
                Text(
                    "Recommended $recommended",
                    style = MaterialTheme.typography.labelSmall.merge(NumberStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun percent(x: Double) = "${(x * 100).roundToInt()}%"

private fun balanceLabel(b: Double) = when {
    abs(b) < 0.05 -> "Centre"
    b > 0 -> "Right +${"%.1f".format(b)} dB"
    else -> "Left +${"%.1f".format(-b)} dB"
}
