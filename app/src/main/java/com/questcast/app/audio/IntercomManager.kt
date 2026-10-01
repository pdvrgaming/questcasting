package com.questcast.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.questcast.app.util.AppLogger as Log

/**
 * Low-latency audio intercom player on Meta Quest 2.
 * Receives 16kHz 16-bit Mono Linear PCM audio chunks streamed from the web operator's
 * browser microphone over the local network and plays them directly through the headset
 * speakers/headphones.
 */
class IntercomManager(
    private val sampleRate: Int = 16000,
    private val volumeMultiplier: Float = 1.3f // Boost operator voice slightly over VR game audio
) {
    companion object {
        private const val TAG = "QuestCast"
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private val bufferSize: Int

    init {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Allocate 2x minimum buffer for smooth playback without underrun
        bufferSize = if (minBuf > 0) minBuf * 2 else 4096
    }

    @Synchronized
    fun start() {
        if (isPlaying && audioTrack != null) return

        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                audioTrack?.setVolume(volumeMultiplier.coerceIn(0.0f, 2.0f))
            }

            audioTrack?.play()
            isPlaying = true
            Log.i(TAG, "QuestCast Intercom: AudioTrack initialized at ${sampleRate}Hz Mono PCM")
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast Intercom: Failed to initialize AudioTrack", e)
            isPlaying = false
        }
    }

    @Synchronized
    fun writePcm(pcmBytes: ByteArray) {
        if (!isPlaying || audioTrack == null) {
            start()
        }

        try {
            audioTrack?.let { track ->
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
                var written = 0
                while (written < pcmBytes.size && isPlaying) {
                    val res = track.write(pcmBytes, written, pcmBytes.size - written, AudioTrack.WRITE_BLOCKING)
                    if (res > 0) {
                        written += res
                    } else {
                        Log.w(TAG, "QuestCast Intercom: AudioTrack.write error code: $res")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast Intercom: Error writing PCM audio", e)
        }
    }

    @Synchronized
    fun stopPtt() {
        try {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.flush()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "QuestCast Intercom: Error flushing AudioTrack", e)
        }
    }

    @Synchronized
    fun release() {
        isPlaying = false
        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    stop()
                }
                flush()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast Intercom: Error releasing AudioTrack", e)
        } finally {
            audioTrack = null
        }
        Log.i(TAG, "QuestCast Intercom: AudioTrack released")
    }
}
