package app.pureeq.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureeq.PureEqApp
import app.pureeq.R
import app.pureeq.profile.OutputStates
import app.pureeq.profile.Profile
import app.pureeq.system.EqService
import app.pureeq.ui.components.AdaptiveButtons
import app.pureeq.ui.components.Pill
import app.pureeq.ui.components.switchRow
import app.pureeq.ui.outputInText
import app.pureeq.ui.theme.StatusColors

/**
 * The Quick Settings tile's long-press menu: the system-wide EQ switch for the output playing now,
 * the profiles to choose from (one per pair of headphones; the pair playing now is marked), and the
 * way to Fine-tune for the one in use or to the app. Choosing a profile applies at once and leaves
 * the sheet open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TileMenuSheet(app: PureEqApp, onFineTune: (String) -> Unit, onOpenApp: () -> Unit, onDismiss: () -> Unit) {
    val store = app.store
    val context = LocalContext.current
    val profiles by store.profiles.collectAsStateWithLifecycle()
    val activeId by store.activeId.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    val playingOn by remember { app.route.outputChanges() }.collectAsStateWithLifecycle(app.route.currentOutput())
    val current = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    val on = current != null && settings.outputs.isOn(playingOn, profiles)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val toggle = { turnOn: Boolean ->
                store.updateSettings { it.copy(outputs = it.outputs.turned(playingOn, turnOn)) }
                if (turnOn) EqService.start(context)
            }
            Row(Modifier.switchRow(on, enabled = current != null, onChange = toggle), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PureEQ", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when {
                            current == null -> stringResource(R.string.tile_menu_no_profile)
                            on -> stringResource(R.string.tile_menu_on_for, outputInText(OutputStates.keyOf(playingOn)))
                            else -> offText(playingOn, settings.outputs, profiles)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = on, enabled = current != null, onCheckedChange = null)
            }
            if (profiles.isEmpty()) {
                Text(
                    stringResource(R.string.tile_menu_no_profile_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(stringResource(R.string.tile_menu_profile), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column {
                    profiles.forEach { p ->
                        ProfileChoice(p, selected = p.id == current?.id, playingNow = p.deviceName != null && p.deviceName == playingOn.name) { store.setActive(p.id) }
                    }
                }
            }
            AdaptiveButtons(spacing = 8.dp) {
                OutlinedButton(onClick = onOpenApp, modifier = Modifier.share()) { Text(stringResource(R.string.tile_menu_open_app)) }
                Button(onClick = { current?.let { onFineTune(it.id) } }, enabled = current != null, modifier = Modifier.share()) { Text(stringResource(R.string.tile_menu_fine_tune)) }
            }
        }
    }
}

@Composable
private fun ProfileChoice(p: Profile, selected: Boolean, playingNow: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge)
            Text(profileSubtitle(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (playingNow) Pill(stringResource(R.string.tile_menu_playing_now), StatusColors.Good)
    }
}
