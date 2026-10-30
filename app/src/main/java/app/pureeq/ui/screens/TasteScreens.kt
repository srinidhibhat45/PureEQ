package app.pureeq.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureeq.taste.Choice
import app.pureeq.ui.AppViewModel
import app.pureeq.ui.Screen
import app.pureeq.ui.components.Headline
import app.pureeq.ui.components.PrimaryButton
import app.pureeq.ui.components.SectionCard
import app.pureeq.ui.components.StepScaffold

/** Lets the listener choose the music and set a comfortable volume before comparing. */
@Composable
fun MusicPicker(vm: AppViewModel) {
    val track by vm.music.collectAsStateWithLifecycle()
    val busy by vm.musicBusy.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.pickSong(uri) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track?.title ?: "Preparing music…", style = MaterialTheme.typography.titleSmall)
            Text(
                if (busy) "Loading…" else "Loops seamlessly while you compare",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { pick.launch(arrayOf("audio/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) {
            Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("My song")
        }
        OutlinedButton(onClick = vm::useBuiltIn, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Built-in track") }
    }
}

@Composable
fun TasteIntroScreen(vm: AppViewModel) {
    val playing by vm.musicPlaying.collectAsStateWithLifecycle()
    val track by vm.music.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    // Redoing the taste test of a saved profile (from Fine-tune) rather than making a new one.
    val retune = draft.retuneOf != null
    StepScaffold(
        title = "Taste",
        onBack = { vm.back() },
        step = if (retune) null else 6,
        bottom = {
            PrimaryButton("Start comparing", { vm.previewForVolume(false); vm.go(Screen.Taste) }, enabled = track != null)
            if (!retune) {
                TextButton(onClick = { vm.skipTasteFromIntro() }, modifier = Modifier.fillMaxWidth()) { Text("Skip, use the listening check only") }
            }
        },
    ) {
        if (retune) {
            Headline(
                "Redo the taste test",
                "You'll hear the same music as A and B and pick the one you enjoy more, usually 5 to 7 rounds. Both are equally loud. " +
                    "Your listening result stays as it is; the outcome replaces your bass and treble (warmth and vocals go back to 0) and becomes what Reset goes back to.",
            )
        } else {
            Headline(
                "Now, what do you like?",
                "You'll hear the same music as A and B. Switch between them as often as you like and pick the one you enjoy more. Both are equally loud, so go with what pleases you. PureEQ learns from every answer, \"Same\" included, and stops once it knows your taste: usually 5 to 7 rounds.",
            )
        }
        SectionCard {
            Text("Music", style = MaterialTheme.typography.titleSmall)
            MusicPicker(vm)
            Text(
                "Your own song works best: pick something you know well, with vocals, bass and cymbals. Files on your phone only (streaming apps protect theirs).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard {
            Text("Listening volume", style = MaterialTheme.typography.titleSmall)
            Text(
                "Play the music and set the volume you normally listen at. Taste depends on volume, so this matters.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = { vm.previewForVolume(!playing) }, enabled = track != null, modifier = Modifier.fillMaxWidth()) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (playing) "Pause" else "Play music")
            }
        }
    }
}

@Composable
fun TasteScreen(vm: AppViewModel) {
    val t by vm.taste.collectAsStateWithLifecycle()
    val playing by vm.musicPlaying.collectAsStateWithLifecycle()
    val round = (t.answered + 1).coerceAtMost(t.total)
    val canAnswer = !t.locked && t.heardB
    StepScaffold(
        title = "Taste",
        onBack = { vm.back() },
        bottom = {
            Text(
                when {
                    t.locked -> "New pair…"
                    !t.heardB -> "Listen to B as well, then choose."
                    else -> "Which do you enjoy more?"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceButton("A is better", Modifier.weight(1f), enabled = canAnswer) { vm.choose(Choice.A) }
                ChoiceButton("Same", Modifier.weight(0.8f), outlined = true, enabled = canAnswer) { vm.choose(Choice.SAME) }
                ChoiceButton("B is better", Modifier.weight(1f), enabled = canAnswer) { vm.choose(Choice.B) }
            }
            TextButton(onClick = vm::skipTaste, modifier = Modifier.fillMaxWidth()) { Text("Finish now") }
        },
    ) {
        LinearProgressIndicator(
            progress = { maxOf(t.certainty, if (t.total == 0) 0f else t.answered.toFloat() / t.total) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            strokeCap = StrokeCap.Round,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = round,
                transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                label = "round",
            ) { r ->
                Text(
                    "Round $r",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Spacer(Modifier.weight(1f))
            Text("usually 5 to 7", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(visible = t.locked && t.answered > 0, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "New pair: a different A and B. Listen to both again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        Text(
            "Tap A and B to switch instantly, as often as you like. Listen to the whole sound: bass, voices, cymbals, clarity.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Each new pair slides in, so the change of round is hard to miss.
        AnimatedContent(
            targetState = t.comparison?.round ?: 0,
            transitionSpec = { (slideInHorizontally { it / 2 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 2 } + fadeOut()) },
            label = "pair",
        ) { _ ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AbTile("A", t.listening == Choice.A && playing, heard = true, Modifier.weight(1f)) { vm.listen(Choice.A) }
                AbTile("B", t.listening == Choice.B && playing, heard = t.heardB, Modifier.weight(1f)) { vm.listen(Choice.B) }
            }
        }
        FilledTonalButton(onClick = { vm.setMusicPlaying(!playing) }, modifier = Modifier.fillMaxWidth()) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(if (playing) "Pause music" else "Play music")
        }
        SectionCard { MusicPicker(vm) }
    }
}

@Composable
private fun AbTile(label: String, active: Boolean, heard: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "ab",
    )
    Card(
        modifier.aspectRatio(0.9f).clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.displayMedium, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(
                    when {
                        active -> "Playing"
                        heard -> "Heard · tap to switch"
                        else -> "Tap to hear"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ChoiceButton(text: String, modifier: Modifier, outlined: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val tap = {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onClick()
    }
    if (outlined) {
        OutlinedButton(onClick = tap, enabled = enabled, modifier = modifier.height(54.dp), shape = RoundedCornerShape(16.dp)) {
            Text(text, textAlign = TextAlign.Center)
        }
    } else {
        Button(onClick = tap, enabled = enabled, modifier = modifier.height(54.dp), shape = RoundedCornerShape(16.dp)) {
            Text(text, textAlign = TextAlign.Center)
        }
    }
}
