package com.nexusai.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import java.util.concurrent.CancellationException

internal class WakeWordSession(
    val ownerToken: String,
    val sessionToken: String,
    val startId: Int,
    val applicationContext: Context
) {
    @Volatile
    var cancelled: Boolean = false

    @Volatile
    private var recorderLease: AudioRecordLease? = null

    fun attachRecorder(lease: AudioRecordLease) {
        recorderLease = lease
        if (cancelled) {
            WakeWordCoordinator.scheduleRecorderStop(this)
        }
    }

    fun stopRecorderForCancellation() {
        recorderLease?.stopForCancellation()
    }

    fun throwIfCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) {
            throw CancellationException("Wake-word session cancelled")
        }
    }
}

/** Serializes stop/release calls while keeping all blocking work off main. */
internal class AudioRecordLease(private val recorder: AudioRecord) {
    private val lock = Any()
    private var started = false
    private var stopped = false
    private var released = false

    fun startRecording(session: WakeWordSession) {
        synchronized(lock) {
            session.throwIfCancelled()
            check(!released) { "AudioRecord was already released" }
            started = true
            recorder.startRecording()
        }
    }

    fun read(buffer: ShortArray): Int = recorder.read(buffer, 0, buffer.size)

    fun stopForCancellation() {
        synchronized(lock) {
            if (released || stopped || !started) return
            try {
                recorder.stop()
            } catch (exception: Exception) {
                Log.w("NEXUS-WakeWord", "AudioRecord stop during cancellation failed", exception)
            } finally {
                stopped = true
            }
        }
    }

    fun close() {
        synchronized(lock) {
            if (released) return

            if (started && !stopped) {
                try {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                } catch (exception: Exception) {
                    Log.w("NEXUS-WakeWord", "AudioRecord stop during cleanup failed", exception)
                } finally {
                    stopped = true
                }
            }

            // Set this before the native call so no later cancellation path can
            // invoke release again, even if release itself throws.
            released = true
            try {
                recorder.release()
            } catch (exception: Exception) {
                Log.w("NEXUS-WakeWord", "AudioRecord release failed", exception)
            }
        }
    }
}

internal object WakeWordDetection {
    private const val TAG = "NEXUS-WakeWord"
    private const val SAMPLE_RATE = 16000

    fun run(session: WakeWordSession, coordinator: WakeWordCoordinator) {
        var spotter: KeywordSpotter? = null
        var stream: OnlineStream? = null
        var recorderLease: AudioRecordLease? = null
        var wakeDetected = false

        try {
            session.throwIfCancelled()
            Log.i(TAG, "Initializing offline wake-word model session=${session.sessionToken}")

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
                keywordsFile = "$assetDirectory/nexus-keywords.txt",
                keywordsScore = 2.0f,
                keywordsThreshold = 0.25f
            )

            val activeSpotter = KeywordSpotter(
                assetManager = session.applicationContext.assets,
                config = config
            )
            spotter = activeSpotter
            session.throwIfCancelled()

            val activeStream = activeSpotter.createStream()
            stream = activeStream
            session.throwIfCancelled()

            val minimumBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minimumBufferSize <= 0) {
                throw IllegalStateException("Invalid microphone buffer size: $minimumBufferSize")
            }

            val bufferSize = maxOf(minimumBufferSize * 2, SAMPLE_RATE * 2 / 5)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            val activeRecorderLease = AudioRecordLease(recorder)
            recorderLease = activeRecorderLease
            session.attachRecorder(activeRecorderLease)

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Microphone initialization failed")
            }

            session.throwIfCancelled()
            activeRecorderLease.startRecording(session)
            coordinator.markListening(session)

            Log.i(
                TAG,
                "Wake-word detection started: requestedRate=$SAMPLE_RATE " +
                    "actualRate=${recorder.sampleRate} bufferBytes=$bufferSize " +
                    "bufferFrames=${recorder.bufferSizeInFrames} " +
                    "state=${recorder.recordingState} " +
                    "session=${session.sessionToken}"
            )

            val audioBuffer = ShortArray(SAMPLE_RATE / 10)
            var audioLogCounter = 0

            while (!session.cancelled && !Thread.currentThread().isInterrupted) {
                val samplesRead = activeRecorderLease.read(audioBuffer)
                if (samplesRead > 0) {
                    logAudioLevel(audioBuffer, samplesRead, ++audioLogCounter)

                    val samples = FloatArray(samplesRead) { index ->
                        audioBuffer[index] / 32768.0f
                    }
                    activeStream.acceptWaveform(samples, SAMPLE_RATE)

                    while (activeSpotter.isReady(activeStream)) {
                        activeSpotter.decode(activeStream)
                        val result = activeSpotter.getResult(activeStream)
                        if (result.keyword.isNotBlank()) {
                            Log.i(TAG, "Wake word detected: ${result.keyword}")
                            wakeDetected = true
                            break
                        }
                    }

                    if (wakeDetected) break
                } else if (samplesRead < 0) {
                    if (!session.cancelled) {
                        Log.e(TAG, "AudioRecord read error: $samplesRead")
                    }
                    break
                }
            }
        } catch (exception: CancellationException) {
            Log.i(TAG, "Wake-word session cancelled: ${session.sessionToken}")
        } catch (exception: Exception) {
            Log.e(TAG, "Wake-word session failed: ${session.sessionToken}", exception)
        } finally {
            coordinator.markCleaning(session)
            session.cancelled = session.cancelled || !wakeDetected

            try {
                recorderLease?.close()
            } catch (exception: Exception) {
                Log.w(TAG, "Microphone cleanup issue", exception)
            }

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

            Log.i(TAG, "Wake-word session cleanup complete: ${session.sessionToken}")
            coordinator.workerFinished(session, wakeDetected)
        }
    }

    private fun logAudioLevel(buffer: ShortArray, samplesRead: Int, counter: Int) {
        if (counter % 10 != 0) return

        var sumSquares = 0.0
        var peak = 0
        for (index in 0 until samplesRead) {
            val sample = buffer[index].toInt()
            sumSquares += sample.toDouble() * sample.toDouble()
            peak = maxOf(peak, kotlin.math.abs(sample))
        }

        val rms = kotlin.math.sqrt(sumSquares / samplesRead)
        Log.d(TAG, "NEXUS-AUDIO rms=${"%.1f".format(rms)} peak=$peak")
    }
}
