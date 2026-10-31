package app.pureeq.ui.screens

import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureeq.BuildConfig
import app.pureeq.R
import app.pureeq.profile.AppSettings
import app.pureeq.profile.OutputStates
import app.pureeq.profile.SystemEqMode
import app.pureeq.system.EqTileService
import app.pureeq.system.SystemEqStatus
import app.pureeq.ui.AppViewModel
import app.pureeq.ui.Screen
import app.pureeq.ui.components.SectionCard
import app.pureeq.ui.components.StepScaffold
import app.pureeq.ui.components.switchRow
import app.pureeq.ui.detailRes
import app.pureeq.ui.outputName
import app.pureeq.ui.titleRes
import app.pureeq.ui.theme.StatusColors
import app.pureeq.update.UpdateState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.store.settings.collectAsStateWithLifecycle()
    val status by vm.systemStatus.collectAsStateWithLifecycle()
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importProfile(uri) }
    val update = { f: (AppSettings) -> AppSettings -> vm.store.updateSettings(f) }
    StepScaffold(title = stringResource(R.string.settings_title), onBack = { vm.back() }) {
        SectionCard {
            Text(stringResource(R.string.settings_system_eq), style = MaterialTheme.typography.titleSmall)
            SystemEqMode.entries.forEach { m ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = s.mode == m, role = Role.RadioButton) { update { it.copy(mode = m) } },
                    verticalAlignment = Alignment.Top,
                ) {
                    RadioButton(selected = s.mode == m, onClick = null, modifier = Modifier.padding(12.dp))
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(stringResource(m.titleRes), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(m.detailRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            (status as? SystemEqStatus.Failed)?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, color = StatusColors.Bad) }
            Text(
                stringResource(R.string.settings_other_eqs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutputsSection(vm, s)
        QuickTileSection()
        SectionCard {
            ToggleRow(stringResource(R.string.settings_auto_switch), stringResource(R.string.settings_auto_switch_detail), s.autoSwitch) { v ->
                update { it.copy(autoSwitch = v) }
            }
            ToggleRow(stringResource(R.string.settings_start_on_boot), stringResource(R.string.settings_start_on_boot_detail), s.startOnBoot) { v ->
                update { it.copy(startOnBoot = v) }
            }
            ToggleRow(
                stringResource(R.string.settings_warble),
                stringResource(R.string.settings_warble_detail),
                s.warbleTones,
            ) { v -> update { it.copy(warbleTones = v) } }
        }
        if (BuildConfig.SELF_UPDATER) {
            UpdatesSection(vm, s.autoUpdate) { v -> update { it.copy(autoUpdate = v) } }
        } else {
            // The Play build updates through Google Play; only the version is worth showing.
            SectionCard { Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge) }
        }
        SectionCard {
            OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.FileOpen, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_import))
            }
            OutlinedButton(onClick = { vm.go(Screen.About) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Info, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_how_it_works))
            }
        }
    }
}

/**
 * On or off for each output PureEQ knows, the one playing now first, with the profile each one
 * plays. A switch here works without that output connected.
 */
@Composable
private fun OutputsSection(vm: AppViewModel, s: AppSettings) {
    val profiles by vm.store.profiles.collectAsStateWithLifecycle()
    val activeId by vm.store.activeId.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    SectionCard {
        Text(stringResource(R.string.settings_where_on), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.settings_where_on_text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        s.outputs.known(profiles, output).forEachIndexed { i, d ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val playing = OutputStates.keyOf(d) == OutputStates.keyOf(output)
            val on = s.outputs.isOn(d, profiles)
            // What it plays when it comes back: the profile last used there, or the one in use.
            val uses = if (playing || !s.autoSwitch) active else s.outputs.profileFor(d, profiles, activeId) ?: active
            val detail = listOfNotNull(
                stringResource(R.string.settings_playing_now).takeIf { playing },
                if (on) uses?.name else stringResource(R.string.settings_off),
            ).joinToString(" · ")
            Row(Modifier.switchRow(on, enabled = active != null) { vm.setOutputOn(d, it) }, verticalAlignment = Alignment.CenterVertically) {
                Icon(deviceIcon(d), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(outputName(d.name), style = MaterialTheme.typography.bodyLarge)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = on, enabled = active != null, onCheckedChange = null)
            }
        }
    }
}

/** Offers the Quick Settings tile: Android 13+ adds it on request, older versions by editing the panel. */
@Composable
private fun QuickTileSection() {
    val context = LocalContext.current
    var added by remember { mutableStateOf<Boolean?>(null) }
    SectionCard {
        Text(stringResource(R.string.settings_tile), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.settings_tile_text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (EqTileService.canRequestAdd) {
            OutlinedButton(onClick = { EqTileService.requestAdd(context) { added = it } }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_tile_add))
            }
            when (added) {
                true -> Text(stringResource(R.string.settings_tile_added), style = MaterialTheme.typography.bodySmall, color = StatusColors.Good)
                false -> Text(
                    stringResource(R.string.settings_tile_not_added),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                null -> Unit
            }
        } else {
            Text(
                stringResource(R.string.settings_tile_how_to_add),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        Text(stringResource(R.string.settings_updates), style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
                Text(
                    updateStatus(state, lastChecked),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is UpdateState.Failed) StatusColors.Bad else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state !is UpdateState.Disabled) {
                val working = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
                OutlinedButton(onClick = vm::checkForUpdates, enabled = !working) { Text(stringResource(R.string.settings_check_now)) }
            }
        }
        (state as? UpdateState.Downloading)?.let { d ->
            LinearProgressIndicator(progress = { d.fraction }, modifier = Modifier.fillMaxWidth())
        }
        (state as? UpdateState.Ready)?.let { r ->
            FilledTonalButton(onClick = vm::installUpdate, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_install_version, r.manifest.versionName)) }
        }
        if (state !is UpdateState.Disabled) {
            if (Build.VERSION.SDK_INT >= 31) {
                ToggleRow(
                    stringResource(R.string.settings_auto_update),
                    stringResource(R.string.settings_auto_update_detail),
                    autoUpdate,
                    onAutoUpdate,
                )
            } else {
                Text(
                    stringResource(R.string.settings_update_asks, Build.VERSION.RELEASE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!allowed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_install_not_allowed),
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.Warn,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { openInstallSettings(context) }) { Text(stringResource(R.string.settings_allow)) }
                }
            }
            Text(stringResource(R.string.settings_update_source, vm.updateSource), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun updateStatus(s: UpdateState, lastChecked: Long): String = when (s) {
    UpdateState.Disabled -> stringResource(R.string.settings_update_disabled)
    UpdateState.Idle -> if (lastChecked > 0) stringResource(R.string.settings_update_checked, ago(lastChecked)) else stringResource(R.string.settings_update_not_checked)
    UpdateState.Checking -> stringResource(R.string.settings_update_checking)
    UpdateState.UpToDate -> stringResource(R.string.settings_update_up_to_date, ago(lastChecked))
    is UpdateState.Downloading -> stringResource(R.string.settings_update_downloading, s.manifest.versionName, (s.fraction * 100).roundToInt())
    is UpdateState.Ready -> stringResource(R.string.settings_update_ready, s.manifest.versionName)
    is UpdateState.Installing -> stringResource(R.string.settings_update_installing, s.manifest.versionName)
    is UpdateState.Failed -> s.message
}

@Composable
private fun ago(t: Long): String {
    val now = System.currentTimeMillis()
    return if (now - t < 60_000) stringResource(R.string.settings_just_now) else DateUtils.getRelativeTimeSpanString(t, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

@Composable
private fun ToggleRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.switchRow(checked, onChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun AboutScreen(vm: AppViewModel) {
    StepScaffold(title = stringResource(R.string.about_title), onBack = { vm.back() }) {
        AboutSection(stringResource(R.string.about_check_title), stringResource(R.string.about_check_text))
        AboutSection(stringResource(R.string.about_eq_title), stringResource(R.string.about_eq_text))
        AboutSection(stringResource(R.string.about_measured_title), stringResource(R.string.about_measured_text))
        AboutSection(stringResource(R.string.about_bass_title), stringResource(R.string.about_bass_text))
        AboutSection(stringResource(R.string.about_taste_title), stringResource(R.string.about_taste_text))
        AboutSection(stringResource(R.string.about_volume_bass_title), stringResource(R.string.about_volume_bass_text))
        AboutSection(stringResource(R.string.about_applying_title), stringResource(R.string.about_applying_text))
        AboutSection(
            stringResource(R.string.about_privacy_title),
            stringResource(if (BuildConfig.SELF_UPDATER) R.string.about_privacy_text_updates else R.string.about_privacy_text),
        )
        AboutSection(stringResource(R.string.about_medical_title), stringResource(R.string.about_medical_text))
        // The licence itself is legal text and stays in English (translatable="false").
        AboutSection(
            stringResource(R.string.about_licences_title),
            stringResource(R.string.about_licences_intro) + "\n\n" + stringResource(R.string.about_licence_mit),
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
