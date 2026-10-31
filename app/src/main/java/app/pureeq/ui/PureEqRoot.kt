package app.pureeq.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureeq.R
import app.pureeq.ui.screens.AboutScreen
import app.pureeq.ui.screens.EditorScreen
import app.pureeq.ui.screens.ExportScreen
import app.pureeq.ui.screens.HeadphonePickerScreen
import app.pureeq.ui.screens.HeadphonesScreen
import app.pureeq.ui.screens.HearingDoneScreen
import app.pureeq.ui.screens.HearingScreen
import app.pureeq.ui.screens.HomeScreen
import app.pureeq.ui.screens.IntroScreen
import app.pureeq.ui.screens.QuietScreen
import app.pureeq.ui.screens.SettingsScreen
import app.pureeq.ui.screens.TasteIntroScreen
import app.pureeq.ui.screens.TasteScreen
import app.pureeq.ui.screens.VolumeScreen

@Composable
fun PureEqRoot(vm: AppViewModel, onExit: () -> Unit) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val error by vm.musicError.collectAsStateWithLifecycle()

    BackHandler(enabled = screen != Screen.Hearing) {
        if (!vm.back()) onExit()
    }

    // A Surface (not just a background) so text outside Scaffolds gets the theme's content colour.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Screens(vm, screen)
    }

    if (error != null) {
        AlertDialog(
            onDismissRequest = { vm.musicError.value = null },
            confirmButton = { TextButton(onClick = { vm.musicError.value = null }) { Text(stringResource(R.string.root_ok)) } },
            text = { Text(error ?: "") },
        )
    }
}

@Composable
private fun Screens(vm: AppViewModel, screen: Screen) {
    AnimatedContent(
        targetState = screen,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        label = "screen",
    ) { s ->
        when (s) {
            Screen.Home -> HomeScreen(vm)
            Screen.Intro -> IntroScreen(vm)
            Screen.Headphones -> HeadphonesScreen(vm)
            is Screen.HeadphonePicker -> HeadphonePickerScreen(vm, s.forEditor)
            Screen.Quiet -> QuietScreen(vm)
            Screen.Volume -> VolumeScreen(vm)
            Screen.Hearing -> HearingScreen(vm)
            Screen.HearingDone -> HearingDoneScreen(vm)
            Screen.TasteIntro -> TasteIntroScreen(vm)
            Screen.Taste -> TasteScreen(vm)
            is Screen.Editor -> EditorScreen(vm)
            is Screen.Export -> ExportScreen(vm, s.profileId)
            Screen.Settings -> SettingsScreen(vm)
            Screen.About -> AboutScreen(vm)
        }
    }
}
