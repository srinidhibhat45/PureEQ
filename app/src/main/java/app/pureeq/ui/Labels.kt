package app.pureeq.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.pureeq.R
import app.pureeq.audio.OutputDevice
import app.pureeq.hearing.HeadphoneType
import app.pureeq.hearing.TestLength
import app.pureeq.profile.BandSet
import app.pureeq.profile.SystemEqMode
import app.pureeq.taste.TasteBand

/**
 * Words from the model in the phone's language. The model keeps its English names, because some
 * are also keys: profiles and the per-output switches are stored under an output's name, and an
 * output without a product name of its own is called "Wired headphones" whatever the language.
 * They are translated only when shown.
 */
object Labels {

    /** An output's name to show: the generic names translated, a product name as it is. */
    fun outputName(context: Context, name: String): String = when (name) {
        OutputDevice.SPEAKER_NAME -> context.getString(R.string.output_phone_speaker)
        OutputDevice.WIRED_NAME -> context.getString(R.string.output_wired_headphones)
        OutputDevice.USB_NAME -> context.getString(R.string.output_usb_headphones)
        OutputDevice.HEADPHONES_NAME -> context.getString(R.string.output_headphones)
        OutputDevice.OTHER_NAME -> context.getString(R.string.output_other)
        else -> name
    }

    /** An output inside a sentence: "the phone speaker", otherwise its name as shown. */
    fun outputInText(context: Context, name: String): String =
        if (name == OutputDevice.SPEAKER_NAME) context.getString(R.string.output_phone_speaker_in_text) else outputName(context, name)

    /** Outputs by name, in a sentence: "Galaxy Buds2", "Galaxy Buds2 and WH-1000XM4", "Galaxy Buds2 and 2 more". */
    fun outputList(context: Context, names: List<String>): String {
        val shown = names.map { outputInText(context, it) }
        return when (shown.size) {
            0 -> ""
            1 -> shown[0]
            2 -> context.getString(R.string.list_two, shown[0], shown[1])
            else -> context.resources.getQuantityString(R.plurals.list_more, shown.size - 1, shown[0], shown.size - 1)
        }
    }

    fun kind(context: Context, kind: OutputDevice.Kind): String = context.getString(
        when (kind) {
            OutputDevice.Kind.WIRED -> R.string.kind_wired
            OutputDevice.Kind.USB -> R.string.kind_usb
            OutputDevice.Kind.BLUETOOTH -> R.string.kind_bluetooth
            OutputDevice.Kind.HEARING_AID -> R.string.kind_hearing_aid
            OutputDevice.Kind.SPEAKER -> R.string.kind_speaker
            OutputDevice.Kind.OTHER -> R.string.kind_other
        },
    )
}

/** [Labels.outputName] in a composable. */
@Composable
fun outputName(name: String): String = Labels.outputName(LocalContext.current, name)

/** [Labels.outputInText] in a composable. */
@Composable
fun outputInText(name: String): String = Labels.outputInText(LocalContext.current, name)

/** [Labels.outputList] in a composable. */
@Composable
fun outputList(names: List<String>): String = Labels.outputList(LocalContext.current, names)

/** [Labels.kind] in a composable. */
@Composable
fun kindLabel(kind: OutputDevice.Kind): String = Labels.kind(LocalContext.current, kind)

@get:StringRes
val SystemEqMode.titleRes: Int
    get() = when (this) {
        SystemEqMode.GLOBAL -> R.string.mode_global
        SystemEqMode.PER_APP -> R.string.mode_per_app
    }

@get:StringRes
val SystemEqMode.detailRes: Int
    get() = when (this) {
        SystemEqMode.GLOBAL -> R.string.mode_global_detail
        SystemEqMode.PER_APP -> R.string.mode_per_app_detail
    }

@get:StringRes
val TasteBand.titleRes: Int
    get() = when (this) {
        TasteBand.BASS -> R.string.band_bass
        TasteBand.WARMTH -> R.string.band_warmth
        TasteBand.VOCALS -> R.string.band_vocals
        TasteBand.TREBLE -> R.string.band_treble
        TasteBand.AIR -> R.string.band_air
    }

@get:StringRes
val TasteBand.hintRes: Int
    get() = when (this) {
        TasteBand.BASS -> R.string.band_bass_hint
        TasteBand.WARMTH -> R.string.band_warmth_hint
        TasteBand.VOCALS -> R.string.band_vocals_hint
        TasteBand.TREBLE -> R.string.band_treble_hint
        TasteBand.AIR -> R.string.band_air_hint
    }

@get:StringRes
val HeadphoneType.titleRes: Int
    get() = when (this) {
        HeadphoneType.IN_EAR -> R.string.headphones_in_ear
        HeadphoneType.OVER_EAR -> R.string.headphones_over_ear
    }

@get:StringRes
val TestLength.titleRes: Int
    get() = when (this) {
        TestLength.QUICK -> R.string.length_quick
        TestLength.STANDARD -> R.string.length_standard
        TestLength.THOROUGH -> R.string.length_thorough
    }

@get:StringRes
val TestLength.detailRes: Int
    get() = when (this) {
        TestLength.QUICK -> R.string.length_quick_detail
        TestLength.STANDARD -> R.string.length_standard_detail
        TestLength.THOROUGH -> R.string.length_thorough_detail
    }

@get:StringRes
val BandSet.titleRes: Int
    get() = when (this) {
        BandSet.TEN -> R.string.bands_ten
        BandSet.SAMSUNG -> R.string.bands_samsung
        BandSet.ANDROID5 -> R.string.bands_android
        BandSet.JAMES15 -> R.string.bands_james
        BandSet.THIRD_OCTAVE -> R.string.bands_third_octave
    }

@get:StringRes
val BandSet.whereRes: Int
    get() = when (this) {
        BandSet.TEN -> R.string.bands_ten_where
        BandSet.SAMSUNG -> R.string.bands_samsung_where
        BandSet.ANDROID5 -> R.string.bands_android_where
        BandSet.JAMES15 -> R.string.bands_james_where
        BandSet.THIRD_OCTAVE -> R.string.bands_third_octave_where
    }
