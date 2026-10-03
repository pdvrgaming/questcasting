package com.questcast.app.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log

/**
 * Isolated audio capture interface for QuestCast.
 *
 * QUEST OS AUDIO NOTE:
 * Meta Quest OS (Android 12L base) composite audio playback is managed by the Oculus VR compositor.
 * On standard Android 10+, AudioPlaybackCaptureConfiguration allows capturing audio from apps that specify
 * AudioAttributes.ALLOW_CAPTURE_BY_ALL. However, system-level Quest OS shell and protected VR titles
 * typically forbid third-party playback audio capture, or require system-level signature permissions.
 *
 * This component provides an isolated audio capture interface so that:
 * 1. Video streaming operates with guaranteed zero disruption whether audio capture is supported or blocked.
 * 2. If playback audio capture is granted by the system, PCM audio can be fed into an audio track.
 * 3. Graceful degradation: If playback capture fails, QuestCast logs the exact limitation and continues video casting.
 */
interface AudioSourceProvider {
    fun isSupported(): Boolean
    fun start(mediaProjection: MediaProjection?): Boolean
    fun stop()
}

class QuestAudioCaptureManager(
    private val context: Context
) : AudioSourceProvider {

    companion object {
        private const val TAG = "QuestCast"
        private const val SAMPLE_RATE = 48000
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    override fun isSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    override fun start(mediaProjection: MediaProjection?): Boolean {
        if (!isSupported() || mediaProjection == null) {
            Log.w(TAG, "QuestCast: audio playback capture not supported on this OS or MediaProjection is null")
            return false
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()

                val minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(minBufferSize * 2)
                    .build()

                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    record.startRecording()
                    audioRecord = record
                    isRecording = true
                    Log.i(TAG, "QuestCast: audio playback capture initialized and started")
                    return true
                } else {
                    Log.w(TAG, "QuestCast: AudioRecord failed to initialize (Quest OS policy restriction)")
                    record.release()
                    return false
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "QuestCast: SecurityException in audio playback capture - Quest OS blocked playback audio capture", e)
        } catch (e: Exception) {
            Log.w(TAG, "QuestCast: Exception starting audio capture", e)
        }

        return false
    }

    override fun stop() {
        if (!isRecording) return
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error stopping AudioRecord", e)
        }
        audioRecord = null
    }
}
