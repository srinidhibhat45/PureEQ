package app.pureeq.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pureeq.R
import app.pureeq.dsp.Ear
import app.pureeq.dsp.FilterType
import app.pureeq.profile.BandSet
import app.pureeq.profile.Exporters
import app.pureeq.profile.FilterRow
import app.pureeq.profile.ProfileEq
import app.pureeq.ui.AppViewModel
import app.pureeq.ui.components.SectionCard
import app.pureeq.ui.components.StepScaffold
import app.pureeq.ui.theme.NumberStyle
import app.pureeq.ui.theme.StatusColors
import app.pureeq.ui.titleRes
import app.pureeq.ui.whereRes

private fun copy(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, R.string.export_copied, Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, subject))
}

@Composable
fun ExportScreen(vm: AppViewModel, profileId: String) {
    val context = LocalContext.current
    val eq by produceState<ProfileEq?>(null, profileId) { value = vm.profileEq(profileId) }
    var pending by remember { mutableStateOf("") }
    val onSaved = { ok: Boolean -> Toast.makeText(context, if (ok) R.string.export_saved else R.string.export_save_failed, Toast.LENGTH_SHORT).show() }
    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) onSaved(vm.writeText(uri, pending))
    }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) onSaved(vm.writeText(uri, pending))
    }
    var pendingRate by remember { mutableStateOf(48000) }
    val saveWav = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val e = eq
        if (uri != null && e != null) onSaved(vm.writeBytes(uri) { Exporters.convolution(e, pendingRate) })
    }

    StepScaffold(title = stringResource(R.string.export_title), onBack = { vm.back() }) {
        val e = eq
        if (e == null) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@StepScaffold
        }
        val name = Exporters.fileSafe(e.profile.name)
        val linkedCurve = Exporters.linkedCurveWithPreamp(e)
        val graphic = Exporters.graphicEq(linkedCurve)
        val parametric = Exporters.parametric(e.linkedFilters, e.linkedPreampDb)
        val perEar = Exporters.parametricPerEar(e)

        Text(
            stringResource(R.string.export_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Exporters.balanceAdvice(e)?.let { a ->
            Text(
                stringResource(if (a.ear == Ear.LEFT) R.string.export_balance_left else R.string.export_balance_right, a.db),
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.Warn,
            )
        }

        ExportCard("Wavelet", stringResource(R.string.export_wavelet)) {
            ActionButton(stringResource(R.string.export_save_file), Icons.Rounded.Download) { pending = graphic; saveTxt.launch("$name GraphicEQ.txt") }
            ActionButton(stringResource(R.string.export_share), Icons.Rounded.Share) { share(context, "$name GraphicEQ", graphic) }
        }
        ExportCard("Poweramp Equalizer", stringResource(R.string.export_poweramp)) {
            ActionButton(stringResource(R.string.export_save_file), Icons.Rounded.Download) { pending = parametric; saveTxt.launch("$name ParametricEQ.txt") }
            ActionButton(stringResource(R.string.export_copy), Icons.Rounded.ContentCopy) { copy(context, "ParametricEQ", parametric) }
        }
        ExportCard("JamesDSP / RootlessJamesDSP", stringResource(R.string.export_jamesdsp)) {
            ActionButton(stringResource(R.string.export_copy), Icons.Rounded.ContentCopy) { copy(context, "GraphicEQ", graphic) }
            ActionButton(stringResource(R.string.export_share), Icons.Rounded.Share) { share(context, "$name GraphicEQ", graphic) }
        }
        ExportCard(stringResource(R.string.export_apo_title), stringResource(R.string.export_apo)) {
            ActionButton(stringResource(R.string.export_save_file), Icons.Rounded.Download) { pending = perEar; saveTxt.launch("$name per-ear.txt") }
            ActionButton(stringResource(R.string.export_copy), Icons.Rounded.ContentCopy) { copy(context, "Equalizer APO", perEar) }
        }
        ExportCard(stringResource(R.string.export_convolution_title), stringResource(R.string.export_convolution)) {
            ActionButton("48 kHz", Icons.Rounded.Download) { pendingRate = 48000; saveWav.launch("$name 48000 Hz.wav") }
            ActionButton("44.1 kHz", Icons.Rounded.Download) { pendingRate = 44100; saveWav.launch("$name 44100 Hz.wav") }
        }
        GraphicBandsCard(e)
        SectionCard {
            Text(stringResource(R.string.export_parametric_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.export_parametric),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.export_preamp, Exporters.signed(e.linkedPreampDb)), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle))
            Exporters.filterTable(e.linkedFilters).forEach { Text(filterLine(it), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle)) }
            if (e.leftFilters != e.rightFilters) {
                Text(stringResource(R.string.export_per_ear), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                Text(stringResource(R.string.export_left), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Exporters.filterTable(e.leftFilters).forEach { Text(filterLine(it), style = MaterialTheme.typography.bodySmall.merge(NumberStyle)) }
                Text(stringResource(R.string.export_right), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Exporters.filterTable(e.rightFilters).forEach { Text(filterLine(it), style = MaterialTheme.typography.bodySmall.merge(NumberStyle)) }
            }
            ActionRow { ActionButton(stringResource(R.string.export_copy), Icons.Rounded.ContentCopy) { copy(context, "Parametric EQ", parametric) } }
        }
        ExportCard(stringResource(R.string.export_profile_title), stringResource(R.string.export_profile)) {
            val json = e.profile.toJson().toString(2)
            val subject = stringResource(R.string.export_profile_subject, name)
            ActionButton(stringResource(R.string.export_save_file), Icons.Rounded.Download) { pending = json; saveJson.launch("$name.pureeq.json") }
            ActionButton(stringResource(R.string.export_share), Icons.Rounded.Share) { share(context, subject, json) }
        }
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun GraphicBandsCard(e: ProfileEq) {
    val context = LocalContext.current
    var set by remember { mutableStateOf(BandSet.TEN) }
    val values = remember(e, set) { Exporters.bands(e.linkedCurve, set) }
    SectionCard {
        Text(stringResource(R.string.export_graphic_title), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BandSet.entries.forEach { s -> FilterChip(selected = s == set, onClick = { set = s }, label = { Text(stringResource(s.titleRes)) }) }
        }
        Text(stringResource(set.whereRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val rows = values.chunked(if (values.size > 10) 3 else 2)
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { v ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(Exporters.freqLabel(v.freq), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
                        Text(
                            "${Exporters.signed(v.gainDb)} dB" + if (v.clipped) "*" else "",
                            style = MaterialTheme.typography.bodyMedium.merge(NumberStyle),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                repeat((if (values.size > 10) 3 else 2) - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (values.any { it.clipped }) {
            Text(stringResource(R.string.export_bands_limited, set.minDb.toInt(), set.maxDb.toInt()), style = MaterialTheme.typography.bodySmall, color = StatusColors.Warn)
        }
        Text(
            stringResource(R.string.export_bands_centred),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val setTitle = stringResource(set.titleRes)
        ActionRow {
            ActionButton(stringResource(R.string.export_copy), Icons.Rounded.ContentCopy) { copy(context, setTitle, Exporters.bandsText(e.profile.name, values, set)) }
        }
    }
}

@Composable
private fun ExportCard(title: String, text: String, actions: @Composable () -> Unit) {
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ActionRow(actions)
    }
}

/** A row of [Exporters.filterTable] as shown: "1. Low shelf · 105 Hz · +6.4 dB · Q 0.70". */
@Composable
private fun filterLine(row: FilterRow): String = stringResource(
    R.string.export_filter_row,
    row.number,
    stringResource(
        when (row.type) {
            FilterType.LOW_SHELF -> R.string.export_filter_low_shelf
            FilterType.HIGH_SHELF -> R.string.export_filter_high_shelf
            else -> R.string.export_filter_peak
        },
    ),
    row.freq,
    row.gain,
    row.q,
)

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun ActionButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(12.dp)) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text)
    }
}
