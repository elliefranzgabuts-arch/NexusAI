
package com.nexusai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

class WakeWordService : Service() {

    companion object {
        private const val TAG = "NEXUS-WakeWord"

        const val ACTION_START = "com.nexusai.app.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.nexusai.app.action.STOP_WAKE_WORD"
        const val ACTION_WAKE_DETECTED = "com.nexusai.app.action.WAKE_DETECTED"

        private const val CHANNEL_ID = "nexus_wake_word"
        private const val NOTIFICATION_ID = 1001
        private const val SAMPLE_RATE = 16000
    }

    @Volatile
    private var isRunning = false

    private var audioRecord: AudioRecord? = null
    private var workerThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            ACTION_STOP -> {
                isRunning = false
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                if (!isRunning) {
                    startAsForegroundService()
                    startWakeWordDetection()
                }
            }
        }

        return START_STICKY
    }

    private fun startAsForegroundService() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NEXUS Wake Word",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Hey Nexus wake-word detection active"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)

        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NEXUS is active")
            .setContentText("Listening locally for Hey Nexus")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startWakeWordDetection() {
        isRunning = true

        workerThread = Thread {
            runWakeWordDetection()
        }.apply {
            name = "NEXUS-WakeWord-Thread"
            start()
        }
    }

    private fun runWakeWordDetection() {
        var spotter: KeywordSpotter? = null
        var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
        var wakeDetected = false

        try {
            Log.i(TAG, "Initializing offline wake-word model")

            val assetDirectory = "sherpa-onnx"

            val modelConfig = OnlineModelConfig().apply {
                transducer = OnlineTransducerModelConfig(
                    encoder = "$assetDirectory/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "$assetDirectory/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    joiner = "$assetDirectory/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                )

                tokens = "$assetDirectory/tokens.txt"
                numThreads = 1
                debug = false
                provider = "cpu"
                modelType = "zipformer2"
                modelingUnit = "bpe"
                bpeVocab = "$assetDirectory/bpe.model"
            }

            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80,
                    dither = 0f
                ),
                modelConfig = modelConfig,
                keywordsFile = "$assetDirectory/nexus-keywords.txt"
            )

            spotter = KeywordSpotter(
                assetManager = assets,
                config = config
            )

            stream = spotter.createStream()

            if (stream.ptr == 0L) {
                throw IllegalStateException(
                    "Could not create wake-word stream"
                )
            }

            val minimumBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minimumBufferSize <= 0) {
                throw IllegalStateException(
                    "Invalid microphone buffer size: $minimumBufferSize"
                )
            }

            val bufferSize = maxOf(
                minimumBufferSize * 2,
                SAMPLE_RATE * 2 / 5
            )

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw IllegalStateException(
                    "Microphone initialization failed"
                )
            }

            audioRecord = recorder
            recorder.startRecording()

            Log.i(TAG, "Wake-word detection started")

            val audioBuffer = ShortArray(SAMPLE_RATE / 10)

            while (
                isRunning &&
                !Thread.currentThread().isInterrupted
            ) {
                val samplesRead = recorder.read(
                    audioBuffer,
                    0,
                    audioBuffer.size
                )

                if (samplesRead > 0) {
                    val samples = FloatArray(samplesRead) { index ->
                        audioBuffer[index] / 32768.0f
                    }

                    stream.acceptWaveform(samples, SAMPLE_RATE)

                    while (spotter.isReady(stream)) {
                        spotter.decode(stream)

                        val result = spotter.getResult(stream)

                        if (result.keyword.isNotBlank()) {
                            Log.i(
                                TAG,
                                "Wake word detected: ${result.keyword}"
                            )

                            wakeDetected = true
                            isRunning = false
                            break
                        }
                    }

                    if (wakeDetected) {
                        break
                    }

                } else if (samplesRead < 0) {
                    Log.e(
                        TAG,
                        "AudioRecord read error: $samplesRead"
                    )
                    break
                }
            }

        } catch (exception: Exception) {
            Log.e(TAG, "Wake-word service failed", exception)

        } finally {
            isRunning = false

            // Release the microphone before notifying MainActivity.
            try {
                audioRecord?.let { recorder ->
                    if (
                        recorder.recordingState ==
                        AudioRecord.RECORDSTATE_RECORDING
                    ) {
                        recorder.stop()
                    }

                    recorder.release()
                }
            } catch (exception: Exception) {
                Log.w(
                    TAG,
                    "Microphone cleanup issue",
                    exception
                )
            }

            audioRecord = null

            try {
                stream?.release()
            } catch (exception: Exception) {
                Log.w(TAG, "Stream cleanup issue", exception)
            }

            try {
                spotter?.release()
            } catch (exception: Exception) {
                Log.w(TAG, "Model cleanup issue", exception)
            }

            Log.i(TAG, "Wake-word detection stopped")

            if (wakeDetected) {
                Log.i(
                    TAG,
                    "Microphone released. Sending wake event."
                )

                sendBroadcast(
                    Intent(ACTION_WAKE_DETECTED)
                        .setPackage(packageName)
                )
            }

            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false

        try {
            audioRecord?.let { recorder ->
                if (
                    recorder.recordingState ==
                    AudioRecord.RECORDSTATE_RECORDING
                ) {
                    recorder.stop()
                }
            }
        } catch (exception: Exception) {
            Log.w(
                TAG,
                "Error stopping microphone",
                exception
            )
        }

        workerThread?.interrupt()

        stopForeground(STOP_FOREGROUND_REMOVE)

        super.onDestroy()
    }
}
