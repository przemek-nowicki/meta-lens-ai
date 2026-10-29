package com.metalens.app.conversation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Controls audio routing + low-latency PCM I/O for voice conversation.
 *
 * Important:
 * - We route audio through Bluetooth SCO to target the glasses microphone + speaker when available.
 * - Audio format is PCM16 mono @ 24kHz, matching OpenAI Realtime defaults.
 */
class AudioIoController(
    private val appContext: Context,
    private val sampleRateHz: Int = 24_000,
) {
    private val audioManager: AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var previousMode: Int? = null
    private var previousScoOn: Boolean? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    suspend fun startRoutingToBluetoothSco() {
        if (previousMode == null) {
            previousMode = audioManager.mode
        }
        
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // Modern API 31+ routing
        val devices = audioManager.availableCommunicationDevices
        val bluetoothDevice = devices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }

        if (bluetoothDevice != null) {
            val success = audioManager.setCommunicationDevice(bluetoothDevice)
            if (success) {
                delay(500) // Small delay to let device switch complete
                return
            }
        }

        // Fallback to older SCO routing
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            throw IllegalStateException("Bluetooth SCO not available on this device")
        }

        if (previousScoOn == null) {
            @Suppress("DEPRECATION")
            previousScoOn = audioManager.isBluetoothScoOn
        }

        // Starting SCO can be flaky across devices; try a few times with a bounded wait.
        val maxAttempts = 3
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                startScoAndWaitConnected(timeoutMs = 4_000)
                return
            } catch (t: Throwable) {
                lastError = t
                stopSco()
                // Small backoff before retry
                delay(250L * (attempt + 1))
            }
        }
        throw IllegalStateException("Failed to start Bluetooth SCO", lastError)
    }

    private suspend fun startScoAndWaitConnected(timeoutMs: Long) {
        val connected =
            withTimeout(timeoutMs) {
                waitForScoConnected {
                    @Suppress("DEPRECATION")
                    audioManager.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = true
                }
            }

        if (!connected) {
            throw IllegalStateException("Timed out waiting for Bluetooth SCO connected")
        }
    }

    private suspend fun waitForScoConnected(start: () -> Unit): Boolean {
        // Fast path if already connected
        if (audioManager.isBluetoothScoOn) return true

        return suspendCancellableCoroutine { cont ->
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                        val state =
                            intent.getIntExtra(
                                AudioManager.EXTRA_SCO_AUDIO_STATE,
                                AudioManager.SCO_AUDIO_STATE_ERROR,
                            )
                        if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                            try {
                                appContext.unregisterReceiver(this)
                            } catch (_: Throwable) {
                                // ignore
                            }
                            if (!cont.isCompleted) cont.resume(true)
                        } else if (state == AudioManager.SCO_AUDIO_STATE_ERROR) {
                            try {
                                appContext.unregisterReceiver(this)
                            } catch (_: Throwable) {
                                // ignore
                            }
                            if (!cont.isCompleted) {
                                cont.resumeWithException(IllegalStateException("Bluetooth SCO error"))
                            }
                        }
                    }
                }

            try {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(
                    receiver,
                    IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                )
            } catch (t: Throwable) {
                cont.resumeWithException(t)
                return@suspendCancellableCoroutine
            }

            cont.invokeOnCancellation {
                try {
                    appContext.unregisterReceiver(receiver)
                } catch (_: Throwable) {
                    // ignore
                }
            }

            start()
        }
    }

    fun startPcmPlayback() {
        if (audioTrack != null) return

        val format =
            AudioFormat.Builder()
                .setSampleRate(sampleRateHz)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

        val minBuffer =
            AudioTrack.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        val bufferSize = maxOf(minBuffer, sampleRateHz / 2) // ~0.5s safety buffer

        val track =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()

        // Try to route directly to Bluetooth
        val devices = audioManager.availableCommunicationDevices
        val bluetoothDevice = devices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
        if (bluetoothDevice != null) {
            track.setPreferredDevice(bluetoothDevice)
        }

        track.play()
        audioTrack = track
    }

    /**
     * Immediately stop any buffered assistant audio from playing.
     * This is used when the user interrupts the assistant mid-response.
     */
    fun interruptPlayback() {
        val track = audioTrack ?: return
        try {
            track.pause()
            track.flush()
            track.play()
        } catch (_: Throwable) {
            // ignore
        }
    }

    fun playPcm16Mono(pcm16: ByteArray, length: Int = pcm16.size) {
        val track = audioTrack ?: return
        if (length <= 0) return
        // Use WRITE_BLOCKING to keep ordering; AudioTrack handles buffering.
        track.write(pcm16, 0, length, AudioTrack.WRITE_BLOCKING)
    }

    fun startMicCapture(
        scope: CoroutineScope,
        onPcm16Chunk: suspend (ByteArray) -> Unit,
    ): Job {
        val record = createAndStartAudioRecord()
        audioRecord = record

        // 20ms frames at 24kHz = 480 samples = 960 bytes (16-bit)
        val chunkBytes = (sampleRateHz / 50) * 2

        return scope.launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(chunkBytes)
                while (isActive) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    // Copy to avoid mutation before consumer finishes encoding
                    onPcm16Chunk(buffer.copyOf(read))
                }
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                // Bubble up via cancellation; caller should handle errors via their own try/catch.
                throw t
            }
        }
    }

    private fun createAndStartAudioRecord(): AudioRecord {
        val minBuffer =
            AudioRecord.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        val bufferSize = maxOf(minBuffer, sampleRateHz) // ~1s safety

        val record =
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

        // Try to route directly to Bluetooth
        val devices = audioManager.availableCommunicationDevices
        val bluetoothDevice = devices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
        if (bluetoothDevice != null) {
            record.setPreferredDevice(bluetoothDevice)
        }

        record.startRecording()
        return record
    }

    fun stop() {
        audioRecord?.run {
            try {
                stop()
            } catch (_: Throwable) {
                // ignore
            }
            release()
        }
        audioRecord = null

        audioTrack?.run {
            try {
                pause()
                flush()
                stop()
            } catch (_: Throwable) {
                // ignore
            }
            release()
        }
        audioTrack = null

        stopSco()

        previousMode?.let { audioManager.mode = it }
        @Suppress("DEPRECATION")
        previousScoOn?.let { audioManager.isBluetoothScoOn = it }
        previousMode = null
        previousScoOn = null
    }

    private fun stopSco() {
        audioManager.clearCommunicationDevice()
        try {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
        } catch (_: Throwable) {
            // ignore
        }
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = false
    }
}

