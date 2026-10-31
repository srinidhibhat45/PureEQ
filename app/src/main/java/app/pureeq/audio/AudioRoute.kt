package app.pureeq.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import app.pureeq.BuildConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** The output device media is playing through. */
data class OutputDevice(val name: String, val type: Int) {
    val kind: Kind = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> Kind.WIRED
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> Kind.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST -> Kind.BLUETOOTH
        AudioDeviceInfo.TYPE_HEARING_AID -> Kind.HEARING_AID
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, TYPE_BUILTIN_SPEAKER_SAFE -> Kind.SPEAKER
        else -> Kind.OTHER
    }

    val isHeadphones: Boolean get() = kind == Kind.WIRED || kind == Kind.USB || kind == Kind.BLUETOOTH || kind == Kind.HEARING_AID

    enum class Kind(val label: String) {
        WIRED("Wired"), USB("USB-C"), BLUETOOTH("Bluetooth"), HEARING_AID("Hearing aid"), SPEAKER("Phone speaker"), OTHER("Audio output")
    }

    private companion object {
        // Constants added after API 28; numeric values from AudioDeviceInfo.
        const val TYPE_BUILTIN_SPEAKER_SAFE = 24
        const val TYPE_BLE_HEADSET = 26
        const val TYPE_BLE_SPEAKER = 27
        const val TYPE_BLE_BROADCAST = 30
    }
}

/**
 * The media volume something was done at: the volume step, the top step, Android's attenuation
 * for that step on that output in dB (0 at the top step; null when the phone doesn't say) and
 * the output's type. With Bluetooth absolute volume the headset attenuates by itself, so there
 * [db] says little.
 */
data class ListeningLevel(val index: Int, val maxIndex: Int, val db: Double?, val deviceType: Int)

/** Media volume, output routing and audio focus. */
class AudioRoute(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null

    /** Debug builds: an output to pretend media plays on, so routing can be tried on an emulator. */
    private val fake = MutableStateFlow<OutputDevice?>(null)

    val maxVolume: Int get() = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val minVolume: Int get() = am.getStreamMinVolume(AudioManager.STREAM_MUSIC)

    var volume: Int
        get() = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        set(value) {
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, value.coerceIn(minVolume, maxVolume), 0) }
        }

    /** The media volume right now and what it means in dB on the current output. */
    fun level(): ListeningLevel {
        val index = volume
        val type = currentOutput().type
        val db = runCatching { am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, type).toDouble() }.getOrNull()?.takeIf { it.isFinite() }
        return ListeningLevel(index, maxVolume, db, type)
    }

    /** The device media is routed to right now. */
    fun currentOutput(): OutputDevice {
        if (BuildConfig.DEBUG) fake.value?.let { return it }
        if (Build.VERSION.SDK_INT >= 33) {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            runCatching { am.getAudioDevicesForAttributes(attrs).firstOrNull() }.getOrNull()?.let { return describe(it) }
        }
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val best = PRIORITY.firstNotNullOfOrNull { t -> outs.firstOrNull { it.type == t } } ?: outs.firstOrNull()
        return best?.let { describe(it) } ?: OutputDevice("Phone speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    }

    private fun describe(d: AudioDeviceInfo): OutputDevice {
        val product = d.productName?.toString()?.trim().orEmpty()
        val generic = product.isEmpty() || product.equals(Build.MODEL, ignoreCase = true)
        val probe = OutputDevice("", d.type)
        val name = when {
            probe.kind == OutputDevice.Kind.SPEAKER -> "Phone speaker"
            !generic -> product
            d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
            d.type == AudioDeviceInfo.TYPE_USB_HEADSET || d.type == AudioDeviceInfo.TYPE_USB_DEVICE -> "USB-C headphones"
            probe.isHeadphones -> "Headphones"
            else -> OutputDevice.Kind.OTHER.label
        }
        return OutputDevice(name, d.type)
    }

    /** Emits the current output and every change (connect, disconnect, reroute). */
    fun outputChanges(): Flow<OutputDevice> = callbackFlow {
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                handler.postDelayed({ trySend(currentOutput()) }, 300)
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                handler.postDelayed({ trySend(currentOutput()) }, 300)
            }
        }
        // Media can also move with nothing plugged in or out (the media output picker, or headphones
        // that stay connected but stop being the active pair), so look again whenever playback changes.
        val playback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                trySend(currentOutput())
            }
        }
        trySend(currentOutput())
        am.registerAudioDeviceCallback(cb, handler)
        am.registerAudioPlaybackCallback(playback, handler)
        if (BuildConfig.DEBUG) launch { fake.drop(1).collect { trySend(currentOutput()) } }
        awaitClose {
            am.unregisterAudioDeviceCallback(cb)
            am.unregisterAudioPlaybackCallback(playback)
        }
    }.distinctUntilChanged()

    /**
     * Debug builds only: `adb shell am broadcast -a app.pureeq.debug.OUTPUT --es name "Galaxy Buds2"
     * --ei type 8` pretends media plays there (type: an AudioDeviceInfo type, Bluetooth if left
     * out); the same without a name goes back to the real output.
     */
    fun listenForFakeOutputs(context: Context) {
        if (!BuildConfig.DEBUG) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                fake.value = i.getStringExtra("name")?.let { OutputDevice(it, i.getIntExtra("type", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)) }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter("app.pureeq.debug.OUTPUT"), ContextCompat.RECEIVER_EXPORTED)
    }

    /** Asks other apps to pause while a test or preview runs. */
    fun requestFocus() {
        if (focusRequest != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        am.requestAudioFocus(req)
        focusRequest = req
    }

    fun abandonFocus() {
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private companion object {
        val PRIORITY = listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            26, // TYPE_BLE_HEADSET
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
    }
}
