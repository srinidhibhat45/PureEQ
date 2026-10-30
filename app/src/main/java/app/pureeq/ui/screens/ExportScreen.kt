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
import androidx.compose.ui.unit.dp
import app.pureeq.profile.BandSet
import app.pureeq.profile.Exporters
import app.pureeq.profile.ProfileEq
import app.pureeq.ui.AppViewModel
import app.pureeq.ui.components.SectionCard
import app.pureeq.ui.components.StepScaffold
import app.pureeq.ui.theme.NumberStyle
import app.pureeq.ui.theme.StatusColors

private fun copy(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
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
    val onSaved = { ok: Boolean -> Toast.makeText(context, if (ok) "Saved" else "Couldn't save the file", Toast.LENGTH_SHORT).show() }
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

    StepScaffold(title = "Export", onBack = { vm.back() }) {
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
            "Your profile in the formats other apps understand. Or skip all this and switch on \"Apply to all audio\" on the home screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Exporters.balanceAdvice(e)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.Warn) }

        ExportCard(
            "Wavelet",
            "Save the file, then in Wavelet open AutoEq › Import and pick it. Wavelet needs this exact 127-point GraphicEQ format.",
        ) {
            ActionButton("Save file", Icons.Rounded.Download) { pending = graphic; saveTxt.launch("$name GraphicEQ.txt") }
            ActionButton("Share", Icons.Rounded.Share) { share(context, "$name GraphicEQ", graphic) }
        }
        ExportCard(
            "Poweramp Equalizer",
            "Save the file, then in Poweramp Equalizer tap Preset, long-press, choose Import and pick it (or open the file with Poweramp Equalizer). Same format as AutoEq's ParametricEQ.txt.",
        ) {
            ActionButton("Save file", Icons.Rounded.Download) { pending = parametric; saveTxt.launch("$name ParametricEQ.txt") }
            ActionButton("Copy", Icons.Rounded.ContentCopy) { copy(context, "ParametricEQ", parametric) }
        }
        ExportCard(
            "JamesDSP / RootlessJamesDSP",
            "Arbitrary response equalizer › Edit as string › paste.",
        ) {
            ActionButton("Copy", Icons.Rounded.ContentCopy) { copy(context, "GraphicEQ", graphic) }
            ActionButton("Share", Icons.Rounded.Share) { share(context, "$name GraphicEQ", graphic) }
        }
        ExportCard(
            "Equalizer APO / Peace (PC)",
            "Separate left and right filters. Save as a .txt and include it from config.txt, or paste it into Peace.",
        ) {
            ActionButton("Save file", Icons.Rounded.Download) { pending = perEar; saveTxt.launch("$name per-ear.txt") }
            ActionButton("Copy", Icons.Rounded.ContentCopy) { copy(context, "Equalizer APO", perEar) }
        }
        ExportCard(
            "Convolution (JamesDSP, ViPER4Android, Equalizer APO)",
            "Your exact curve for each ear as an impulse response: a stereo WAV with the left and right ear, balance and level as PureEQ applies them. Load it in the convolver, matching your music's sample rate.",
        ) {
            ActionButton("48 kHz", Icons.Rounded.Download) { pendingRate = 48000; saveWav.launch("$name 48000 Hz.wav") }
            ActionButton("44.1 kHz", Icons.Rounded.Download) { pendingRate = 44100; saveWav.launch("$name 44100 Hz.wav") }
        }
        GraphicBandsCard(e)
        SectionCard {
            Text("Parametric values", style = MaterialTheme.typography.titleSmall)
            Text(
                "For apps where you type filters in: Neutron, USB Audio Player Pro, Poweramp's parametric mode and others. Both ears averaged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Preamp ${Exporters.signed(e.linkedPreampDb)} dB", style = MaterialTheme.typography.bodyMedium.merge(NumberStyle))
            Exporters.filterTable(e.linkedFilters).forEach { Text(it, style = MaterialTheme.typography.bodyMedium.merge(NumberStyle)) }
            if (e.leftFilters != e.rightFilters) {
                Text("Per ear (Poweramp can set each band to L or R)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                Text("Left", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Exporters.filterTable(e.leftFilters).forEach { Text(it, style = MaterialTheme.typography.bodySmall.merge(NumberStyle)) }
                Text("Right", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Exporters.filterTable(e.rightFilters).forEach { Text(it, style = MaterialTheme.typography.bodySmall.merge(NumberStyle)) }
            }
            ActionRow { ActionButton("Copy", Icons.Rounded.ContentCopy) { copy(context, "Parametric EQ", parametric) } }
        }
        ExportCard("PureEQ profile", "A backup you can import on another phone (Settings › Import profile).") {
            val json = e.profile.toJson().toString(2)
            ActionButton("Save file", Icons.Rounded.Download) { pending = json; saveJson.launch("$name.pureeq.json") }
            ActionButton("Share", Icons.Rounded.Share) { share(context, "$name PureEQ profile", json) }
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
        Text("Graphic EQ sliders", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BandSet.entries.forEach { s -> FilterChip(selected = s == set, onClick = { set = s }, label = { Text(s.label) }) }
        }
        Text(set.where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text("* limited to the app's ${set.minDb.toInt()}…+${set.maxDb.toInt()} dB range", style = MaterialTheme.typography.bodySmall, color = StatusColors.Warn)
        }
        Text(
            "Centred so overall loudness stays the same. Slider EQs can only approximate the curve; the files above are exact.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionRow {
            ActionButton("Copy", Icons.Rounded.ContentCopy) { copy(context, set.label, Exporters.bandsText(e.profile.name, values, set)) }
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
