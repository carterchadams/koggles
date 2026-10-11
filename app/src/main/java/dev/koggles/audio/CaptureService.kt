package dev.koggles.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.IntentCompat
import dev.koggles.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Foreground service owning the MediaProjection and the playback-capture AudioRecord. */
class CaptureService : Service() {

    private var capture: Capture? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val generation = intent?.getLongExtra(EXTRA_GENERATION, -1L) ?: -1L
        // One capture per instance: tear down the previous one; its callbacks carry its old
        // generation and are dropped by CaptureBus (F3).
        capture?.close()
        capture = null
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val resultCode = intent!!.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = checkNotNull(IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java))
            val projection = checkNotNull(
                getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data),
            ) { "consent result did not yield a MediaProjection" }
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() = CaptureBus.deliver(generation) { onProjectionStopped() }
                },
                Handler(Looper.getMainLooper()),
            )
            capture = Capture(projection).also { it.start() }
            CaptureBus.deliver(generation) { onStarted() }
        } catch (e: Exception) {
            Log.w(TAG, "capture failed to start", e)
            capture?.close()
            capture = null
            CaptureBus.deliver(generation) { onFailed(FailReason.CaptureStartFailed) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        capture?.close()
        capture = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Audio capture", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Koggles")
            .setContentText("Capturing device audio")
            .build()
    }

    private class Capture(private val projection: MediaProjection) {
        private val record: AudioRecord
        private val bytes = ByteBuffer.allocateDirect(FRAMES_PER_READ * 2 * 4).order(ByteOrder.nativeOrder())
        private val floats = bytes.asFloatBuffer()

        @Volatile
        private var running = false
        private var reader: Thread? = null

        init {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBytes, FRAMES_PER_READ * 2 * 4 * 4))
                .setAudioPlaybackCaptureConfig(config)
                .build()
        }

        fun start() {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
            record.startRecording()
            running = true
            reader = Thread({
                while (running) {
                    bytes.clear()
                    val read = record.read(bytes, bytes.capacity(), AudioRecord.READ_BLOCKING)
                    if (read > 0) NativeBridge.pushPcmFloat(floats, read / (2 * 4))
                    else if (read < 0) break
                }
            }, "koggles-capture").also { it.start() }
        }

        fun close() {
            running = false
            runCatching { record.stop() }
            reader?.join(500)
            record.release()
            projection.stop()
        }
    }

    companion object {
        private const val TAG = "KogglesCapture"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 44_100
        private const val FRAMES_PER_READ = 1024
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_GENERATION = "generation"

        fun intent(context: Context, consent: ConsentData, generation: Long): Intent =
            Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, consent.resultCode)
                .putExtra(EXTRA_DATA, consent.data)
                .putExtra(EXTRA_GENERATION, generation)
    }
}
