package app.pureeq.ui.screens

import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureeq.BuildConfig
import app.pureeq.profile.AppSettings
import app.pureeq.profile.SystemEqMode
import app.pureeq.system.SystemEqStatus
import app.pureeq.ui.AppViewModel
import app.pureeq.ui.Screen
import app.pureeq.ui.components.SectionCard
import app.pureeq.ui.components.StepScaffold
import app.pureeq.ui.theme.StatusColors
import app.pureeq.update.UpdateState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.store.settings.collectAsStateWithLifecycle()
    val status by vm.systemStatus.collectAsStateWithLifecycle()
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importProfile(uri) }
    val update = { f: (AppSettings) -> AppSettings -> vm.store.updateSettings(f) }
    StepScaffold(title = "Settings", onBack = { vm.back() }) {
        SectionCard {
            Text("System-wide equalizer", style = MaterialTheme.typography.titleSmall)
            SystemEqMode.entries.forEach { m ->
                Row(Modifier.fillMaxWidth().clickable { update { it.copy(mode = m) } }, verticalAlignment = Alignment.Top) {
                    RadioButton(selected = s.mode == m, onClick = { update { it.copy(mode = m) } })
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(m.label, style = MaterialTheme.typography.bodyLarge)
                        Text(m.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            (status as? SystemEqStatus.Failed)?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, color = StatusColors.Bad) }
            Text(
                "Turn off other equalizers (Wavelet, Dolby Atmos, Samsung's own EQ/Adapt Sound) while PureEQ's is on, or the effects stack.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard {
            ToggleRow("Switch profile with headphones", "When headphones you made a profile with connect, use that profile.", s.autoSwitch) { v ->
                update { it.copy(autoSwitch = v) }
            }
            ToggleRow("Start after restart", "Turn the system equalizer back on after the phone restarts.", s.startOnBoot) { v ->
                update { it.copy(startOnBoot = v) }
            }
            ToggleRow(
                "Warble test tones",
                "Beeps sweep ±5 % around each frequency, so one narrow earbud resonance can't skew a band. Turn off for pure tones.",
                s.warbleTones,
            ) { v -> update { it.copy(warbleTones = v) } }
        }
        UpdatesSection(vm, s.autoUpdate) { v -> update { it.copy(autoUpdate = v) } }
        SectionCard {
            OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.FileOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("Import a PureEQ profile")
            }
            OutlinedButton(onClick = { vm.go(Screen.About) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Info, null)
                Spacer(Modifier.width(8.dp))
                Text("How PureEQ works")
            }
        }
    }
}

@Composable
private fun UpdatesSection(vm: AppViewModel, autoUpdate: Boolean, onAutoUpdate: (Boolean) -> Unit) {
    val state by vm.updates.collectAsStateWithLifecycle()
    val lastChecked by vm.updatesLastChecked.collectAsStateWithLifecycle()
    val allowed by vm.installAllowed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SectionCard {
        Text("Updates", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("PureEQ ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    updateStatus(state, lastChecked),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is UpdateState.Failed) StatusColors.Bad else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state !is UpdateState.Disabled) {
                val working = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
                OutlinedButton(onClick = vm::checkForUpdates, enabled = !working) { Text("Check now") }
            }
        }
        (state as? UpdateState.Downloading)?.let { d ->
            LinearProgressIndicator(progress = { d.fraction }, modifier = Modifier.fillMaxWidth())
        }
        (state as? UpdateState.Ready)?.let { r ->
            FilledTonalButton(onClick = vm::installUpdate, modifier = Modifier.fillMaxWidth()) { Text("Install ${r.manifest.versionName}") }
        }
        if (state !is UpdateState.Disabled) {
            if (Build.VERSION.SDK_INT >= 31) {
                ToggleRow(
                    "Install updates automatically",
                    "New versions install by themselves while PureEQ is closed and nothing is playing. Off: you get a notification instead. " +
                        "PureEQ checks when you open it, every 30 minutes while the phone charges on Wi-Fi, and once a day otherwise.",
                    autoUpdate,
                    onAutoUpdate,
                )
            } else {
                Text(
                    "Android ${Build.VERSION.RELEASE} asks before every app update, so PureEQ sends a notification when one is ready.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!allowed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "PureEQ isn't allowed to install updates yet, so Android will ask first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.Warn,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { openInstallSettings(context) }) { Text("Allow") }
                }
            }
            Text("Updates come from ${vm.updateSource}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun updateStatus(s: UpdateState, lastChecked: Long): String = when (s) {
    UpdateState.Disabled -> "This copy wasn't built with an update address, so it can't update itself."
    UpdateState.Idle -> if (lastChecked > 0) "Checked ${ago(lastChecked)}" else "Not checked yet"
    UpdateState.Checking -> "Checking…"
    UpdateState.UpToDate -> "Up to date · checked ${ago(lastChecked)}"
    is UpdateState.Downloading -> "Downloading ${s.manifest.versionName}… ${(s.fraction * 100).roundToInt()}%"
    is UpdateState.Ready -> "${s.manifest.versionName} is ready to install"
    is UpdateState.Installing -> "Installing ${s.manifest.versionName}…"
    is UpdateState.Failed -> s.message
}

private fun ago(t: Long): String {
    val now = System.currentTimeMillis()
    return if (now - t < 60_000) "just now" else DateUtils.getRelativeTimeSpanString(t, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

@Composable
private fun ToggleRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun AboutScreen(vm: AppViewModel) {
    StepScaffold(title = "How PureEQ works", onBack = { vm.back() }) {
        AboutSection(
            "The hearing check",
            "Like Samsung's Adapt Sound and the hearing tests in AirPods and OPPO/realme earbuds: pulsed beeps (three per presentation, 25 ms soft edges) " +
                "play in one ear at a time, ears in random order. Each ear and frequency gets its own clinical up-down staircase " +
                "(modified Hughson-Westlake: 10 dB down after a yes, 5 dB up after a no). The threshold is then estimated from all your answers " +
                "by maximum likelihood, which is finer than the 5 dB steps. Some trials are silent on purpose, to tell hearing from guessing.",
        )
        AboutSection(
            "From hearing to EQ",
            "Your threshold at each frequency is compared with normal hearing (ISO 226 / ISO 389-7). The difference covers your ears and your " +
                "headphones together, which is what an EQ can fix. Half of the difference is applied by default, the audiologists' half-gain rule. " +
                "The curve is smoothed, limited (+12 dB, +6 dB in the deep bass, -6 dB cuts) and fitted with the same parametric filters other EQ apps use.",
        )
        AboutSection(
            "The taste test",
            "Bass (a 105 Hz shelf, as in Harman's headphone research), warmth, vocals and treble are tuned by blind A/B comparisons, " +
                "each pair loudness-matched with the ITU BS.1770 weighting so louder never wins by default. Each round halves the difference.",
        )
        AboutSection(
            "Applying it",
            "\"All audio\" uses Android's DynamicsProcessing effect on the output mix with a separate curve for each ear, in fine bands " +
                "(each FFT bin below 530 Hz, 1/16 octave above). Exports use AutoEq's formats, so Wavelet, Poweramp Equalizer, JamesDSP and Equalizer APO read them directly.",
        )
        AboutSection(
            "Privacy",
            "Your profiles and test results stay on this phone. The optional room check listens for 4 seconds to measure noise level and stores nothing. " +
                "PureEQ only goes online to check for its own updates: a small update notice, then the new version when there is one.",
        )
        AboutSection(
            "Not a medical device",
            "PureEQ personalises sound; it does not diagnose hearing loss. If you notice changes in your hearing, see an audiologist.",
        )
    }
}

@Composable
private fun AboutSection(title: String, text: String) {
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
