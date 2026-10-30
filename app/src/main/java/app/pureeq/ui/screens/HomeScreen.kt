package app.pureeq.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureeq.BuildConfig
import app.pureeq.audio.OutputDevice
import app.pureeq.profile.Profile
import app.pureeq.profile.ProfileEq
import app.pureeq.system.SystemEqStatus
import app.pureeq.ui.AppViewModel
import app.pureeq.ui.Screen
import app.pureeq.ui.components.ChartCurve
import app.pureeq.ui.components.EqChart
import app.pureeq.ui.components.ListeningOrb
import app.pureeq.ui.components.Pill
import app.pureeq.ui.components.PrimaryButton
import app.pureeq.ui.components.SectionCard
import app.pureeq.ui.theme.EarColors
import app.pureeq.ui.theme.StatusColors
import app.pureeq.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

fun deviceIcon(d: OutputDevice?): ImageVector = when (d?.kind) {
    OutputDevice.Kind.BLUETOOTH -> Icons.Rounded.Bluetooth
    OutputDevice.Kind.USB -> Icons.Rounded.Usb
    OutputDevice.Kind.SPEAKER -> Icons.Rounded.Speaker
    else -> Icons.Rounded.Headphones
}

@Composable
fun HomeScreen(vm: AppViewModel) {
    val profiles by vm.store.profiles.collectAsStateWithLifecycle()
    val activeId by vm.store.activeId.collectAsStateWithLifecycle()
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val status by vm.systemStatus.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val update by vm.updates.collectAsStateWithLifecycle()
    val installAllowed by vm.installAllowed.collectAsStateWithLifecycle()
    val promptDismissed by vm.updatePromptDismissed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    // Only the GitHub build updates itself; in the Play build these cards are compiled out.
    val updateBusy = BuildConfig.SELF_UPDATER && (update is UpdateState.Downloading || update is UpdateState.Ready || update is UpdateState.Installing)
    val askToAllow = BuildConfig.SELF_UPDATER && !installAllowed && !promptDismissed && !updateBusy && update !is UpdateState.Disabled

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.setSystemEq(true) }
    val enableSystemEq = {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.setSystemEq(true)
    }
    LaunchedEffect(active?.id) { if (active != null && activeId == null) vm.setActive(active.id) }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PureEQ", style = MaterialTheme.typography.headlineMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(deviceIcon(output), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (output.name == output.kind.label) output.name else "${output.name} · ${output.kind.label}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Rounded.Settings, "Settings") }
            }
        }
        if (updateBusy) {
            item { UpdateCard(update, onInstall = vm::installUpdate) }
        }
        if (active == null) {
            item { WelcomeCard(onStart = vm::startNewProfile) }
            if (askToAllow) item { AllowUpdatesCard(onAllow = { openInstallSettings(context) }, onDismiss = vm::dismissUpdatePrompt) }
        } else {
            item {
                ActiveProfileCard(
                    profile = active,
                    output = output,
                    enabled = settings.systemEqEnabled,
                    status = status,
                    onToggle = { if (it) enableSystemEq() else vm.setSystemEq(false) },
                    onEdit = { vm.go(Screen.Editor(active.id)) },
                    onExport = { vm.go(Screen.Export(active.id)) },
                    successor = vm.store.successorOf(active.id),
                    onDelete = { vm.deleteProfile(active.id) },
                )
            }
            if (askToAllow) item { AllowUpdatesCard(onAllow = { openInstallSettings(context) }, onDismiss = vm::dismissUpdatePrompt) }
            val others = profiles.filter { it.id != active.id }
            if (others.isNotEmpty()) {
                item {
                    Text("Other profiles", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                }
                items(others, key = { it.id }) { p ->
                    ProfileRow(
                        p,
                        onActivate = { vm.setActive(p.id) },
                        onEdit = { vm.go(Screen.Editor(p.id)) },
                        onExport = { vm.go(Screen.Export(p.id)) },
                        onDelete = { vm.deleteProfile(p.id) },
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = vm::startNewProfile,
                    modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("New profile")
                }
            }
        }
    }
}

/** A new version downloading, ready, or installing. */
@Composable
private fun UpdateCard(state: UpdateState, onInstall: () -> Unit) {
    SectionCard {
        when (state) {
            is UpdateState.Downloading -> {
                UpdateTitle("Downloading PureEQ ${state.manifest.versionName}")
                LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
            }
            is UpdateState.Ready -> {
                UpdateTitle("PureEQ ${state.manifest.versionName} is ready")
                if (state.manifest.notes.isNotBlank()) {
                    Text(
                        state.manifest.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalButton(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text("Install now") }
                Text(
                    "PureEQ closes for a moment while it updates. Your profiles stay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is UpdateState.Installing -> {
                UpdateTitle("Installing PureEQ ${state.manifest.versionName}…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            else -> Unit
        }
    }
}

/** Asks once for "Install unknown apps", which lets updates install without a trip through Files. */
@Composable
private fun AllowUpdatesCard(onAllow: () -> Unit, onDismiss: () -> Unit) {
    SectionCard {
        UpdateTitle("Get updates automatically")
        Text(
            if (Build.VERSION.SDK_INT >= 31) {
                "Allow PureEQ to install its own updates. New versions then install by themselves while the app is closed."
            } else {
                "Allow PureEQ to install its own updates, so each new version is one tap away."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onAllow) { Text("Allow") }
            TextButton(onClick = onDismiss) { Text("Not now") }
        }
    }
}

@Composable
private fun UpdateTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

/** Android's "Install unknown apps" switch for PureEQ. */
fun openInstallSettings(context: Context) {
    runCatching { context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
        .onFailure { runCatching { context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) } }
}

@Composable
private fun WelcomeCard(onStart: () -> Unit) {
    SectionCard(padding = 22.dp) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ListeningOrb(active = true, size = 150.dp) }
        Text("Find your sound", style = MaterialTheme.typography.headlineSmall)
        Text(
            "A short listening check plays beeps in each ear and asks if you heard them. Then a few A/B rounds on music learn what you like. " +
                "PureEQ turns both into an EQ for your headphones that you can apply to all audio or export to your EQ app.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        PrimaryButton("Start", onStart)
    }
}

@Composable
private fun ActiveProfileCard(
    profile: Profile,
    output: OutputDevice,
    enabled: Boolean,
    status: SystemEqStatus,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    /** The profile that takes over if this one is deleted; null if it's the only one. */
    successor: Profile?,
    onDelete: () -> Unit,
) {
    val eq by produceState<ProfileEq?>(null, profile) { value = withContext(Dispatchers.Default) { ProfileEq(profile) } }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleLarge)
                Text(profileSubtitle(profile), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Pill("Active", MaterialTheme.colorScheme.secondary)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
        eq?.let { e ->
            EqChart(curvesFor(e, MaterialTheme.colorScheme.primary), height = 130.dp)
        } ?: Spacer(Modifier.height(130.dp))
        if (profile.deviceName != null && output.isHeadphones && output.name != profile.deviceName) {
            Text(
                "Made for ${profile.deviceName}; you're listening on ${output.name}.",
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.Warn,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Apply to all audio", style = MaterialTheme.typography.titleSmall)
                Text(statusText(enabled, status), style = MaterialTheme.typography.bodySmall, color = statusColor(enabled, status))
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Fine-tune")
            }
            FilledTonalButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.IosShare, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Export")
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${profile.name}?") },
            text = {
                Text(
                    "This is the profile PureEQ is using right now. Its listening results and taste choices will be removed from this phone. " +
                        if (successor != null) {
                            "PureEQ will switch to ${successor.name} (${profileSubtitle(successor)})."
                        } else {
                            "It's your only profile, so the system-wide EQ turns off until you make a new one."
                        },
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProfileRow(p: Profile, onActivate: () -> Unit, onEdit: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onActivate),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Headphones, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall)
                Text(profileSubtitle(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Use this profile") }, onClick = { menu = false; onActivate() })
                    DropdownMenuItem(text = { Text("Fine-tune") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Export") }, leadingIcon = { Icon(Icons.Rounded.IosShare, null) }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${p.name}?") },
            text = { Text("The listening results and taste choices in this profile will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** One curve when both ears share the EQ, otherwise left (blue) and right (red). */
fun curvesFor(eq: ProfileEq, combined: Color): List<ChartCurve> {
    val same = eq.leftCurve.indices.all { kotlin.math.abs(eq.leftCurve[it] - eq.rightCurve[it]) < 0.05 }
    return if (same) {
        listOf(ChartCurve(eq.leftCurve, combined, fill = true))
    } else {
        listOf(ChartCurve(eq.leftCurve, EarColors.Left, fill = true), ChartCurve(eq.rightCurve, EarColors.Right, fill = true))
    }
}

/** "Galaxy Buds2 Pro · Ears + taste · 30 Oct 2026": the profile's headphones, what made it, and when. */
internal fun profileSubtitle(p: Profile): String {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.createdAt))
    val kind = when {
        p.hearing != null -> "Ears + taste"
        else -> "Taste only"
    }
    return listOfNotNull(p.deviceName, kind, date).joinToString(" · ")
}

private fun statusText(enabled: Boolean, s: SystemEqStatus): String = when {
    !enabled -> "Off. Turn on to hear this profile in every app."
    s is SystemEqStatus.Active && s.global -> "On for all audio"
    s is SystemEqStatus.Active -> "On for music apps · ${s.sessions} playing"
    s is SystemEqStatus.Paused -> "Paused while PureEQ plays sound"
    s is SystemEqStatus.Failed -> s.message
    else -> "Starting…"
}

@Composable
private fun statusColor(enabled: Boolean, s: SystemEqStatus) = when {
    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
    s is SystemEqStatus.Failed -> StatusColors.Bad
    s is SystemEqStatus.Active -> StatusColors.Good
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
