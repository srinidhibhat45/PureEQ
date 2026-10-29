package app.pureeq.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureeq.headphones.AutoEqEntry
import app.pureeq.hearing.HeadphoneType
import app.pureeq.ui.AppViewModel
import app.pureeq.ui.components.Headline
import app.pureeq.ui.components.StepScaffold
import app.pureeq.ui.theme.StatusColors

/** Search AutoEq's database for the headphone model and take its measured correction. */
@Composable
fun HeadphonePickerScreen(vm: AppViewModel, forEditor: Boolean) {
    val ui by vm.picker.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val current = if (forEditor) editor?.profile?.headphoneModel else draft.headphoneModel
    StepScaffold(
        title = "Your headphones",
        onBack = { vm.back() },
        bottom = {
            if (current != null) {
                TextButton(onClick = { vm.setHeadphoneModel(null, forEditor); vm.back() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove ${current.name}")
                }
            } else {
                TextButton(onClick = { vm.back() }, modifier = Modifier.fillMaxWidth()) { Text("Skip, mine isn't listed") }
            }
        },
    ) {
        Headline(
            "Find your model",
            "PureEQ applies its measured correction, which brings your headphones to the Harman target most listeners prefer. Your hearing check then only fixes what's left: your ears and how the headphones sit on them.",
        )
        OutlinedTextField(
            value = ui.query,
            onValueChange = vm::searchHeadphones,
            label = { Text("Brand and model") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        )
        when {
            ui.loading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "Loading AutoEq's database of over 6,000 headphones (about 850 KB, only the first time)…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ui.error != null -> {
                Text(ui.error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = StatusColors.Warn)
                OutlinedButton(onClick = vm::loadAutoEq) { Text("Try again") }
            }
            ui.query.isNotBlank() && ui.searched == ui.query && ui.results.isEmpty() -> Text(
                "No match. Try just the model name, for example \"Buds2 Pro\" or \"HD 650\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.results.forEach { entry ->
            ResultRow(entry, fetching = ui.fetching == entry, selected = current?.let { it.name == entry.name && it.source == entry.source } == true) {
                vm.pickHeadphone(entry)
            }
        }
        Text(
            "Measurements from oratory1990, crinacle, Rtings and others, collected and corrected by AutoEq (Jaakko Pasanen, MIT licence). " +
                "The same model can be listed by several measurers; the first is usually the most trusted. Pick a variant (ANC on, passive…) if it matches how you listen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultRow(entry: AutoEqEntry, fetching: Boolean, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(enabled = !fetching, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Headphones, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(entry.source, entry.rig, if (entry.type == HeadphoneType.IN_EAR) "in-ear" else "over-ear").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (fetching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}
