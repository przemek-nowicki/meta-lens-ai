package com.metalens.app.stream

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.metalens.app.settings.AppSettings
import com.metalens.app.wearables.WearablesViewModel
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StreamViewModel(
    application: Application,
    wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "StreamViewModel"
    }

    private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
    private var streamSession: StreamSession? = null

    private val _uiState = MutableStateFlow(StreamUiState())
    val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

    // CONFLATED channel: if the decoder is busy, we drop intermediate frames instead of
    // building an unbounded queue that exhausts memory.
    private val frameChannel = Channel<VideoFrame>(Channel.CONFLATED)

    private var videoJob: Job? = null
    private var frameDecodeJob: Job? = null
    private var stateJob: Job? = null
    private var lastSessionState: StreamSessionState? = null

    fun startStream() {
        Log.d(TAG, "startStream()")
        stopStream()
        lastSessionState = null

        val session =
            try {
                Wearables.startStreamSession(
                    getApplication(),
                    deviceSelector,
                    StreamConfiguration(videoQuality = AppSettings.getCameraVideoQuality(getApplication()), 24),
                ).also { streamSession = it }
            } catch (t: Throwable) {
                Log.e(TAG, "startStreamSession() failed", t)
                _uiState.update { it.copy(recentError = t.message ?: "Failed to start stream session") }
                return
            }

        // Producer: collect raw frames and send to the conflated channel (drops stale frames).
        videoJob =
            viewModelScope.launch {
                try {
                    Log.d(TAG, "Collecting videoStream...")
                    session.videoStream.collect { frame ->
                        if (_uiState.value.frameCount == 0L) {
                            Log.d(TAG, "First video frame received: ${frame.width}x${frame.height}")
                        }
                        frameChannel.trySend(frame) // drops stale frame if decoder is still busy
                    }
                    Log.d(TAG, "videoStream completed")
                } catch (t: Throwable) {
                    if (t is CancellationException) {
                        Log.d(TAG, "videoStream collector cancelled")
                        return@launch
                    }
                    Log.e(TAG, "videoStream collector failed", t)
                    _uiState.update {
                        it.copy(
                            recentError = t.message ?: "Video stream collector failed",
                        )
                    }
                }
            }

        // Consumer: single coroutine decodes one frame at a time, preventing OOM.
        frameDecodeJob =
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    for (frame in frameChannel) {
                        val bitmap = runCatching { decodeToBitmap(frame) }.getOrNull()
                        _uiState.update { it.copy(videoFrame = bitmap, frameCount = it.frameCount + 1) }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) return@launch
                    Log.e(TAG, "frameDecodeJob failed", t)
                    _uiState.update { it.copy(recentError = t.message ?: "Frame decoder failed") }
                }
            }

        stateJob =
            viewModelScope.launch {
                session.state.collect { state ->
                    val prev = lastSessionState
                    lastSessionState = state
                    Log.d(TAG, "session.state=$state (prev=$prev)")
                    _uiState.update { it.copy(streamSessionState = state) }

                    // IMPORTANT: StreamSessionState starts as STOPPED. Only treat STOPPED as "ended"
                    // if it is a transition from a different state (like STARTING/STREAMING/etc.).
                    if (prev != null && prev != state && state == StreamSessionState.STOPPED) {
                        Log.d(TAG, "Session transitioned to STOPPED -> cleaning up")
                        stopStream()
                    }

                    if (prev != null && prev != state && state == StreamSessionState.CLOSED) {
                        Log.d(TAG, "Session transitioned to CLOSED -> cleaning up")
                        stopStream()
                    }
                }
            }
    }

    fun stopStream() {
        Log.d(TAG, "stopStream()")
        videoJob?.cancel()
        videoJob = null
        frameDecodeJob?.cancel()
        frameDecodeJob = null
        stateJob?.cancel()
        stateJob = null
        streamSession?.close()
        streamSession = null
        _uiState.update { StreamUiState() }
    }


    private fun decodeToBitmap(videoFrame: VideoFrame): android.graphics.Bitmap? {
        val buffer = videoFrame.buffer
        val dataSize = buffer.remaining()
        val byteArray = ByteArray(dataSize)

        val originalPosition = buffer.position()
        buffer.get(byteArray)
        buffer.position(originalPosition)

        val nv21 = convertI420toNV21(byteArray, videoFrame.width, videoFrame.height)
        val image = YuvImage(nv21, ImageFormat.NV21, videoFrame.width, videoFrame.height, null)
        val out = ByteArrayOutputStream()
        image.compressToJpeg(Rect(0, 0, videoFrame.width, videoFrame.height), 50, out)
        val jpegBytes = out.toByteArray()

        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    // Convert I420 (YYYYYYYY:UUVV) to NV21 (YYYYYYYY:VUVU)
    private fun convertI420toNV21(input: ByteArray, width: Int, height: Int): ByteArray {
        val output = ByteArray(input.size)
        val size = width * height
        val quarter = size / 4

        input.copyInto(output, 0, 0, size) // Y is the same

        for (n in 0 until quarter) {
            output[size + n * 2] = input[size + quarter + n] // V first
            output[size + n * 2 + 1] = input[size + n] // U second
        }
        return output
    }

    override fun onCleared() {
        super.onCleared()
        stopStream()
    }

    class Factory(
        private val application: Application,
        private val wearablesViewModel: WearablesViewModel,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
                return StreamViewModel(
                    application = application,
                    wearablesViewModel = wearablesViewModel,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

