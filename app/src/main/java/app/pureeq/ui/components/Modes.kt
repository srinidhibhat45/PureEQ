package app.pureeq.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pureeq.profile.ListeningMode
import app.pureeq.ui.detailRes
import app.pureeq.ui.titleRes

val ListeningMode.icon: ImageVector
    get() = when (this) {
        ListeningMode.GAMING -> Icons.Rounded.SportsEsports
        ListeningMode.MUSIC -> Icons.Rounded.MusicNote
        ListeningMode.VOICE -> Icons.Rounded.RecordVoiceOver
        ListeningMode.GENERAL -> Icons.Rounded.Apps
    }

/**
 * The four modes side by side, icon over name, for switching quickly (home, Fine-tune, the tile's
 * menu). One radio group for TalkBack; [selected] null shows none picked.
 */
@Composable
fun ModeSelector(selected: ListeningMode?, onSelect: (ListeningMode) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TileGrid(modifier.fillMaxWidth().selectableGroup()) {
        ListeningMode.entries.forEach { m ->
            val on = m == selected
            val shape = RoundedCornerShape(14.dp)
            Column(
                Modifier
                    .heightIn(min = 64.dp)
                    .clip(shape)
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(BorderStroke(if (on) 1.5.dp else 0.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh), shape)
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(m) })
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                val tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(m.icon, null, Modifier.size(22.dp), tint = tint)
                Text(stringResource(m.titleRes), style = MaterialTheme.typography.labelMedium, color = tint, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * Tiles in equal columns: all in one row when every tile fits its share on one line, else two per
 * row, else one, so a name never breaks inside a word (large text, longer words in another
 * language). Tiles in a row share the tallest one's height.
 */
@Composable
private fun TileGrid(modifier: Modifier, spacing: Dp = 8.dp, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val n = measurables.size.coerceAtLeast(1)
        fun share(cols: Int) = ((constraints.maxWidth - gap * (cols - 1)) / cols).coerceAtLeast(0)
        val cols = listOf(n, 2, 1).first { c -> c == 1 || measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= share(c) } }
        val w = share(cols)
        val rows = measurables.chunked(cols).map { row ->
            val h = row.maxOf { it.minIntrinsicHeight(w) }
            row.map { it.measure(Constraints(minWidth = w, maxWidth = w, minHeight = h)) }
        }
        val height = rows.sumOf { r -> r.maxOf { it.height } } + gap * (rows.size - 1)
        layout(constraints.maxWidth, height) {
            var y = 0
            rows.forEach { r ->
                r.forEachIndexed { i, p -> p.placeRelative(i * (w + gap), y) }
                y += r.maxOf { it.height } + gap
            }
        }
    }
}

/** The modes as a list with what each is for: the new-profile question and the home screen's one-time ask. */
@Composable
fun ModeChoices(selected: ListeningMode?, onSelect: (ListeningMode) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListeningMode.entries.forEach { m ->
            val on = m == selected
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(m) })
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(m.icon, null, Modifier.size(26.dp), tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(m.titleRes), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(m.detailRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RadioButton(selected = on, onClick = null)
            }
        }
    }
}
