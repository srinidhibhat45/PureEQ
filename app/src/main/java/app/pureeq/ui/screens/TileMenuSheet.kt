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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureeq.PureEqApp
import app.pureeq.profile.Profile
import app.pureeq.system.EqService
import app.pureeq.ui.components.Pill
import app.pureeq.ui.theme.StatusColors

/**
 * The Quick Settings tile's long-press menu: the system-wide EQ switch, the profiles to choose
 * from (one per pair of headphones; the pair playing now is marked), and the way to Fine-tune for
 * the one in use or to the app. Choosing a profile applies at once and leaves the sheet open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TileMenuSheet(app: PureEqApp, onFineTune: (String) -> Unit, onOpenApp: () -> Unit, onDismiss: () -> Unit) {
    val store = app.store
    val context = LocalContext.current
    val profiles by store.profiles.collectAsStateWithLifecycle()
    val activeId by store.activeId.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    val playingOn = remember { app.route.currentOutput() }
    val current = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PureEQ", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when {
                            current == null -> "No profile yet"
                            settings.systemEqEnabled -> "On · ${current.name}"
                            else -> "Off"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.systemEqEnabled && current != null,
                    enabled = current != null,
                    onCheckedChange = { on ->
                        store.updateSettings { it.copy(systemEqEnabled = on) }
                        if (on) EqService.start(context)
                    },
                )
            }
            if (profiles.isEmpty()) {
                Text(
                    "Make a profile in PureEQ first: a short listening check and taste test for your headphones.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Profile", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column {
                    profiles.forEach { p ->
                        ProfileChoice(p, selected = p.id == current?.id, playingNow = p.deviceName != null && p.deviceName == playingOn.name) { store.setActive(p.id) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenApp, modifier = Modifier.weight(1f)) { Text("Open PureEQ") }
                Button(onClick = { current?.let { onFineTune(it.id) } }, enabled = current != null, modifier = Modifier.weight(1f)) { Text("Fine-tune") }
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
        if (playingNow) Pill("Playing now", StatusColors.Good)
    }
}
