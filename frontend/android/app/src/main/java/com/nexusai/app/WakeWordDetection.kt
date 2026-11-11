package com.nexusai.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.os.SystemClock
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
        var terminationReason = "worker exited without wake detection"
        val diagnosticStartMs = SystemClock.elapsedRealtime()
        var readCalls = 0L
        var positiveReads = 0L
        var zeroReads = 0L
        var negativeReads = 0L
        var readErrors = 0L
        var samplesReadTotal = 0L
        var samplesFedTotal = 0L
        var decoderCalls = 0L
        var resultCalls = 0L
        var wakeDetections = 0L
        var lastRms = Double.NaN
        var lastPeak = -1
        var lastDiagnosticLogMs = diagnosticStartMs

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
                readCalls += 1
                val samplesRead = try {
                    activeRecorderLease.read(audioBuffer)
                } catch (exception: Exception) {
                    readErrors += 1
                    throw exception
                }

                if (samplesRead > 0) {
                    positiveReads += 1
                    samplesReadTotal += samplesRead
                    val audioLevel = measureAudioLevel(audioBuffer, samplesRead)
                    lastRms = audioLevel.first
                    lastPeak = audioLevel.second
                    audioLogCounter += 1
                    if (audioLogCounter % 10 == 0) {
                        Log.d(
                            TAG,
                            "NEXUS-AUDIO elapsedMs=${SystemClock.elapsedRealtime()} " +
                                "rms=${"%.1f".format(lastRms)} peak=$lastPeak"
                        )
                    }

                    val samples = FloatArray(samplesRead) { index ->
                        audioBuffer[index] / 32768.0f
                    }
                    activeStream.acceptWaveform(samples, SAMPLE_RATE)
                    samplesFedTotal += samplesRead

                    while (activeSpotter.isReady(activeStream)) {
                        activeSpotter.decode(activeStream)
                        decoderCalls += 1
                        val result = activeSpotter.getResult(activeStream)
                        resultCalls += 1
                        if (result.keyword.isNotBlank()) {
                            wakeDetections += 1
                            Log.i(
                                TAG,
                                "VOICE_DIAG elapsedMs=${SystemClock.elapsedRealtime()} " +
                                    "event=WAKE_RESULT detections=$wakeDetections"
                            )
                            Log.i(TAG, "Wake word detected: ${result.keyword}")
                            wakeDetected = true
                            terminationReason = "wake detected: ${result.keyword}"
                            break
                        }
                    }

                    if (wakeDetected) break
                } else if (samplesRead == 0) {
                    zeroReads += 1
                } else {
                    negativeReads += 1
                    readErrors += 1
                    terminationReason = "AudioRecord.read returned $samplesRead"
                    Log.e(TAG, "AudioRecord read error: $samplesRead")
                    break
                }

                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastDiagnosticLogMs >= 1000L) {
                    Log.i(
                        TAG,
                        "VOICE_DIAG elapsedMs=$nowMs event=PIPELINE_SNAPSHOT " +
                            "readCalls=$readCalls positiveReads=$positiveReads " +
                            "zeroReads=$zeroReads negativeReads=$negativeReads " +
                            "readErrors=$readErrors samplesRead=$samplesReadTotal " +
                            "samplesFed=$samplesFedTotal decoderCalls=$decoderCalls " +
                            "resultCalls=$resultCalls wakeDetections=$wakeDetections " +
                            "lastRms=$lastRms lastPeak=$lastPeak"
                    )
                    lastDiagnosticLogMs = nowMs
                }
            }
        } catch (exception: CancellationException) {
            terminationReason = "cancellation requested"
            Log.i(TAG, "Wake-word session cancelled: ${session.sessionToken}")
        } catch (exception: Exception) {
            terminationReason = "${exception.javaClass.simpleName}: ${exception.message ?: "no message"}"
            Log.e(TAG, "Wake-word session failed: ${session.sessionToken}", exception)
        } finally {
            coordinator.markCleaning(session)

            if (!wakeDetected && session.cancelled) {
                terminationReason = "cancellation requested"
            } else if (!wakeDetected && Thread.currentThread().isInterrupted) {
                terminationReason = "worker thread interrupted"
            }

            try {
                recorderLease?.close()
                Log.i(
                    TAG,
                    "VOICE_EVENT elapsedMs=${SystemClock.elapsedRealtime()} " +
                        "event=MICROPHONE_RELEASED session=${session.sessionToken} " +
                        "wakeDetected=$wakeDetected"
                )
            } catch (exception: Exception) {
                Log.w(TAG, "Microphone cleanup issue", exception)
            }

            Log.i(
                TAG,
                "VOICE_DIAG elapsedMs=${SystemClock.elapsedRealtime()} " +
                    "event=PIPELINE_SUMMARY durationMs=${SystemClock.elapsedRealtime() - diagnosticStartMs} " +
                    "readCalls=$readCalls positiveReads=$positiveReads " +
                    "zeroReads=$zeroReads negativeReads=$negativeReads " +
                    "readErrors=$readErrors samplesRead=$samplesReadTotal " +
                    "samplesFed=$samplesFedTotal decoderCalls=$decoderCalls " +
                    "resultCalls=$resultCalls wakeDetections=$wakeDetections " +
                    "lastRms=$lastRms lastPeak=$lastPeak"
            )

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
            coordinator.workerFinished(
                session = session,
                wakeDetected = wakeDetected,
                terminationReason = terminationReason,
                intentionalCancellation = session.cancelled
            )
        }
    }

    private fun measureAudioLevel(buffer: ShortArray, samplesRead: Int): Pair<Double, Int> {
        var sumSquares = 0.0
        var peak = 0
        for (index in 0 until samplesRead) {
            val sample = buffer[index].toInt()
            sumSquares += sample.toDouble() * sample.toDouble()
            peak = maxOf(peak, kotlin.math.abs(sample))
        }

        val rms = kotlin.math.sqrt(sumSquares / samplesRead)
        return rms to peak
    }
}
