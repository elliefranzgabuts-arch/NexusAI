package com.nexusai.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Debug-only entry point. It never starts WakeWordService or Sherpa-ONNX. */
class PcmDiagnosticActivity : Activity() {
    private data class Sample(
        val id: String,
        val fileName: String,
        val expectedPhrase: String
    )

    private class Run(
        val token: String,
        val sample: Sample,
        val pcmFile: File,
        val recognizer: SpeechRecognizer,
        val readEnd: ParcelFileDescriptor,
        writeEnd: ParcelFileDescriptor,
        val totalBytes: Long,
        val startedAtMs: Long = SystemClock.elapsedRealtime()
    ) {
        val cleanupStarted = AtomicBoolean(false)
        val recognitionStarted = AtomicBoolean(false)
        val recognitionTerminal = AtomicBoolean(false)
        val writerFinished = AtomicBoolean(false)
        val writerReachedEof = AtomicBoolean(false)
        val writerFailed = AtomicBoolean(false)
        val writeDescriptorClosed = AtomicBoolean(false)
        val readDescriptorClosed = AtomicBoolean(false)
        val bytesWritten = AtomicLong(0L)
        val microphoneObserved = AtomicBoolean(false)
        val microphoneMonitorSupported = AtomicBoolean(true)
        val descriptorLock = Any()
        var writeDescriptor: ParcelFileDescriptor? = writeEnd
        var output: ParcelFileDescriptor.AutoCloseOutputStream? = null
        var writerThread: Thread? = null
        var lastMicrophoneState: String? = null
        var finalTranscript: String? = null
        var terminalOutcome: String? = null
        var cancellationReason: String? = null
    }

    companion object {
        private const val TAG = "NEXUS_PCM_DIAGNOSTIC"
        private const val SESSION_TIMEOUT_MS = 45_000L
        private const val PREFLIGHT_TIMEOUT_MS = 4_000L
        private const val PCM_SAMPLE_RATE = 16_000
        private const val PCM_CHANNELS = 1
        private const val PCM_BYTES_PER_SAMPLE = 2
        private const val CHUNK_BYTES = PCM_SAMPLE_RATE / 10 * PCM_CHANNELS * PCM_BYTES_PER_SAMPLE
        private val POSITIVE = Sample("positive", "positive.pcm", "NEXUS")
        private val NEGATIVE = Sample(
            "negative", "negative.pcm", "Hello, how are you doing today?"
        )
    }

    private val activityInstanceId = UUID.randomUUID().toString()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var audioManager: AudioManager
    private var activeRun: Run? = null
    private var preparing = false
    private var preparingToken: String? = null
    private var preparingSample: Sample? = null
    private var sessionTimeout: Runnable? = null
    private var microphonePoll: Runnable? = null
    private var activityStarted = false
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(AudioManager::class.java)
        log("ACTIVITY_ON_CREATE", details = "recreated=${savedInstanceState != null}")
        buildDiagnosticUi()

        // stopService() never starts WakeWordService. The user must explicitly
        // press a sample button before this diagnostic can create a recognizer.
        val stopRequested = stopService(Intent(this, WakeWordService::class.java))
        log("WAKE_SERVICE_STOP_REQUESTED", details = "serviceWasRunning=$stopRequested")
        status("Diagnostic only. Select one sample to start a test.")
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        log("ACTIVITY_ON_START")
    }

    override fun onResume() {
        super.onResume()
        log("ACTIVITY_ON_RESUME")
    }

    override fun onPause() {
        log("ACTIVITY_ON_PAUSE")
        super.onPause()
    }

    override fun onStop() {
        log("ACTIVITY_ON_STOP")
        activityStarted = false
        super.onStop()
        if (preparing) {
            log("SESSION_PREPARATION_ABORTED", details = "reason=activity_on_stop")
            clearPreparing()
        }
        activeRun?.let { abort(it, "activity_on_stop") }
    }

    override fun onDestroy() {
        destroyed = true
        log("ACTIVITY_ON_DESTROY", details = "changingConfigurations=$isChangingConfigurations")
        activeRun?.let { abort(it, "activity_on_destroy") }
        super.onDestroy()
    }

    private fun buildDiagnosticUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 24)
        }
        val heading = TextView(this).apply {
            text = "NEXUS external PCM diagnostic\n16 kHz · mono · PCM16"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }
        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        content.addView(heading)
        content.addView(statusView)
        content.addView(Button(this).apply {
            text = "Run positive (NEXUS)"
            setOnClickListener { begin(POSITIVE) }
        })
        content.addView(Button(this).apply {
            text = "Run negative (greeting)"
            setOnClickListener { begin(NEGATIVE) }
        })
        content.addView(Button(this).apply {
            text = "Cancel test"
            setOnClickListener { activeRun?.let { abort(it, "cancel_button") } }
        })
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun begin(sample: Sample) {
        if (destroyed || activeRun != null || preparing) {
            log("SESSION_START_REJECTED", details = "sample=${sample.id} busy=${activeRun != null || preparing}")
            return
        }
        preparing = true
        val token = UUID.randomUUID().toString()
        preparingToken = token
        preparingSample = sample
        val pcmFile = File(getExternalFilesDir("pcm-diagnostic"), sample.fileName)
        log(
            "SESSION_CREATED",
            details = "input=${pcmFile.name} expected=${sample.expectedPhrase} rate=$PCM_SAMPLE_RATE " +
                "channels=$PCM_CHANNELS encoding=PCM_16BIT byteOrder=LITTLE_ENDIAN"
        )

        if (!pcmFile.isFile || !pcmFile.canRead() || pcmFile.length() <= 0L || pcmFile.length() % 2L != 0L) {
            log("INPUT_REJECTED", details = "exists=${pcmFile.isFile} readable=${pcmFile.canRead()} bytes=${pcmFile.length()}")
            clearPreparing()
            status("Missing/invalid PCM. Push ${sample.fileName} into this app's pcm-diagnostic directory.")
            return
        }
        val maximumBytes = PCM_SAMPLE_RATE.toLong() * PCM_CHANNELS * PCM_BYTES_PER_SAMPLE * 30L
        if (pcmFile.length() > maximumBytes) {
            log("INPUT_REJECTED", details = "reason=over_30_seconds bytes=${pcmFile.length()}")
            clearPreparing()
            status("PCM input exceeds the 30 second safety limit.")
            return
        }

        val available = try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        } catch (exception: Exception) {
            log("SERVICE_AVAILABILITY_ERROR", details = "exception=${exception.javaClass.simpleName}")
            false
        }
        log("RECOGNITION_SERVICE_AVAILABILITY", details = "onDeviceAvailable=$available")
        if (!available) {
            clearPreparing()
            status("No on-device speech recognition service is available.")
            return
        }

        val permissionGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        log("MIC_PERMISSION_STATE", details = "recordAudioGranted=$permissionGranted")

        val deadline = SystemClock.elapsedRealtime() + PREFLIGHT_TIMEOUT_MS
        fun waitUntilMicrophoneIdle() {
            if (destroyed || !activityStarted || !preparing || preparingToken != token) {
                clearPreparing()
                return
            }
            val configurations = recordingConfigurations("PREFLIGHT")
            if (configurations != null && configurations.isEmpty()) {
                createAndStartRun(token, sample, pcmFile)
            } else if (SystemClock.elapsedRealtime() >= deadline) {
                log("PREFLIGHT_ABORTED", details = "reason=microphone_not_idle activeRecordings=${configurations?.size ?: -1}")
                clearPreparing()
                status("Test stopped: an active microphone capture remains. Stop other audio apps and retry.")
            } else {
                handler.postDelayed({ waitUntilMicrophoneIdle() }, 250L)
            }
        }
        waitUntilMicrophoneIdle()
    }

    private fun createAndStartRun(token: String, sample: Sample, pcmFile: File) {
        if (destroyed || !activityStarted || !preparing || preparingToken != token) {
            clearPreparing()
            return
        }
        var recognizer: SpeechRecognizer? = null
        var pipe: Array<ParcelFileDescriptor>? = null
        var run: Run? = null
        try {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            pipe = ParcelFileDescriptor.createPipe()
            val session = Run(
                token = token,
                sample = sample,
                pcmFile = pcmFile,
                recognizer = recognizer,
                readEnd = pipe[0],
                writeEnd = pipe[1],
                totalBytes = pcmFile.length()
            )
            run = session
            pipe = null
            activeRun = session
            clearPreparing()

            log(
                "AUDIO_DESCRIPTOR_CREATED", session,
                "bytes=${session.totalBytes} durationMs=${session.totalBytes * 1000L / (PCM_SAMPLE_RATE * PCM_CHANNELS * PCM_BYTES_PER_SAMPLE)}"
            )
            setListener(session)
            val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, session.readEnd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, PCM_CHANNELS)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, PCM_SAMPLE_RATE)
            }

            sessionTimeout = Runnable {
                if (activeRun === session) {
                    log(
                        "SESSION_TIMEOUT", session,
                        "timeoutMs=$SESSION_TIMEOUT_MS writerEof=${session.writerReachedEof.get()} " +
                            "recognitionTerminal=${session.recognitionTerminal.get()}"
                    )
                    abort(session, "session_timeout")
                }
            }.also { handler.postDelayed(it, SESSION_TIMEOUT_MS) }

            log("RECOGNITION_START_REQUESTED", session, "action=${recognitionIntent.action}")
            recognizer.startListening(recognitionIntent)
            log("RECOGNITION_START_RETURNED", session, "providerStarted=false")
            startMicrophoneMonitor(session)
            startPcmWriter(session)
            status("Running ${sample.id}; waiting for PCM EOF and a terminal recognition callback.")
        } catch (exception: Exception) {
            log("SESSION_START_ERROR", run, "exception=${exception.javaClass.simpleName} message=${exception.message}")
            if (run != null) {
                abort(run, "startup_exception")
            } else {
                pipe?.forEach { closeQuietly(it, "START_FAILURE", token, sample) }
                try { recognizer?.destroy() } catch (_: Exception) { }
                clearPreparing()
            }
            status("Could not start recognition: ${exception.javaClass.simpleName}")
        }
    }

    private fun setListener(run: Run) {
        run.recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!isCurrent(run)) return
                run.recognitionStarted.set(true)
                log("CALLBACK_READY_FOR_SPEECH", run, "elapsedMs=${elapsed(run)} recognitionStartedConfirmed=true")
            }

            override fun onBeginningOfSpeech() {
                if (!isCurrent(run)) return
                run.recognitionStarted.set(true)
                callback(run, "CALLBACK_BEGINNING_OF_SPEECH")
            }

            override fun onEndOfSpeech() = callback(run, "CALLBACK_END_OF_SPEECH")
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) {
                callback(run, "CALLBACK_BUFFER_RECEIVED", "bytes=${buffer?.size ?: 0}")
            }

            override fun onError(error: Int) {
                if (!isCurrent(run)) return
                run.recognitionStarted.set(true)
                run.recognitionTerminal.set(true)
                run.terminalOutcome = "RECOGNITION_ERROR_$error"
                log("CALLBACK_ERROR", run, "errorCode=$error elapsedMs=${elapsed(run)}")
                maybeComplete(run)
            }

            override fun onResults(results: Bundle?) {
                if (!isCurrent(run)) return
                run.recognitionStarted.set(true)
                val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                handleRecognitionResult(run, phrases)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isCurrent(run)) return
                run.recognitionStarted.set(true)
                val phrases = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                log("CALLBACK_PARTIAL_RESULTS", run, "elapsedMs=${elapsed(run)} transcripts=${phrases.joinToString(" | ")}")
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                callback(run, "CALLBACK_EVENT", "eventType=$eventType")
            }
        })
    }

    private fun handleRecognitionResult(run: Run, phrases: List<String>) {
        val transcript = phrases.firstOrNull().orEmpty()
        val matched = transcript.isNotBlank() && normalize(transcript) == normalize(run.sample.expectedPhrase)
        run.finalTranscript = transcript
        run.recognitionTerminal.set(true)
        run.terminalOutcome = when {
            phrases.isEmpty() || transcript.isBlank() -> "EMPTY_FINAL_RESULT"
            matched -> "FINAL_MATCH"
            else -> "FINAL_MISMATCH"
        }
        log(
            if (transcript.isBlank()) "EMPTY_FINAL_RESULT" else "CALLBACK_FINAL_RESULTS",
            run,
            "elapsedMs=${elapsed(run)} resultCount=${phrases.size} transcript=$transcript " +
                "phraseMatch=$matched bytesWritten=${run.bytesWritten.get()}/${run.totalBytes} " +
                "writerEof=${run.writerReachedEof.get()}"
        )
        log(
            "TRANSCRIPTION_ASSESSMENT", run,
            "result=${if (matched) "MATCH" else "MISMATCH"} keywordSpotting=NOT_RUN " +
                "providerPcmConsumption=UNVERIFIED"
        )
        status("${run.sample.id}: ${run.terminalOutcome}; waiting for writer EOF.")
        maybeComplete(run)
    }

    private fun startPcmWriter(run: Run) {
        val writer = Thread({
            var stream: ParcelFileDescriptor.AutoCloseOutputStream? = null
            var reachedEof = false
            var failure: Exception? = null
            try {
                synchronized(run.descriptorLock) {
                    val descriptor = run.writeDescriptor ?: throw IOException("write descriptor unavailable")
                    stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
                    run.writeDescriptor = null
                    run.output = stream
                }
                log("PCM_WRITER_STARTED", run, "chunkBytes=$CHUNK_BYTES pacing=realtime")
                FileInputStream(run.pcmFile).use { input ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) {
                            reachedEof = true
                            break
                        }
                        stream!!.write(buffer, 0, count)
                        val total = run.bytesWritten.addAndGet(count.toLong())
                        log("PCM_BYTES_WRITTEN", run, "chunkBytes=$count totalBytes=$total/${run.totalBytes}")
                        val pacingMs = count * 1000L / (PCM_SAMPLE_RATE * PCM_CHANNELS * PCM_BYTES_PER_SAMPLE)
                        if (pacingMs > 0) Thread.sleep(pacingMs)
                    }
                }
                stream?.close()
                run.writeDescriptorClosed.set(true)
                run.writerReachedEof.set(reachedEof)
                log("PCM_WRITER_EOF", run, "reachedEof=$reachedEof bytesWritten=${run.bytesWritten.get()}/${run.totalBytes}")
            } catch (exception: Exception) {
                failure = exception
                run.writerFailed.set(true)
                log(
                    "PCM_WRITER_FAILURE", run,
                    "exception=${exception.javaClass.simpleName} message=${exception.message} " +
                        "bytesWritten=${run.bytesWritten.get()}/${run.totalBytes}"
                )
            } finally {
                try {
                    stream?.close()
                    if (stream != null) run.writeDescriptorClosed.set(true)
                } catch (exception: Exception) {
                    log("DESCRIPTOR_CLOSE_ERROR", run, "side=writer_finally exception=${exception.javaClass.simpleName}")
                }
                synchronized(run.descriptorLock) {
                    if (run.output === stream) run.output = null
                    run.writeDescriptor?.let {
                        closeQuietly(it, "WRITER_FINALLY", run.token, run.sample)
                        run.writeDescriptor = null
                    }
                }
                run.writerFinished.set(true)
                log(
                    "PCM_WRITER_FINISHED", run,
                    "reachedEof=${run.writerReachedEof.get()} failed=${failure != null} " +
                        "bytesWritten=${run.bytesWritten.get()}/${run.totalBytes}"
                )
                handler.post { maybeComplete(run) }
            }
        }, "NEXUS-PCM-Writer-${run.sample.id}").apply { isDaemon = true }
        run.writerThread = writer
        writer.start()
    }

    private fun maybeComplete(run: Run) {
        if (!isCurrent(run) || !run.recognitionTerminal.get() || !run.writerFinished.get()) return
        if (!run.writerReachedEof.get()) {
            log(
                "SESSION_NOT_COMPLETED", run,
                "reason=writer_did_not_reach_eof writerFailed=${run.writerFailed.get()} " +
                    "recognitionOutcome=${run.terminalOutcome} providerPcmConsumption=UNVERIFIED"
            )
            status("${run.sample.id}: writer failed before EOF; session remains bounded by timeout.")
            return
        }
        complete(run)
    }

    private fun startMicrophoneMonitor(run: Run) {
        val poll = object : Runnable {
            override fun run() {
                if (!isCurrent(run)) return
                recordingConfigurations("DURING_TEST", run)
                handler.postDelayed(this, 200L)
            }
        }
        microphonePoll = poll
        poll.run()
    }

    private fun recordingConfigurations(stage: String, run: Run? = null): List<AudioRecordingConfiguration>? {
        return try {
            val configurations = audioManager.activeRecordingConfigurations
            val state = configurations.joinToString(separator = ";") { config ->
                "clientSource=${config.clientAudioSource},activeSource=${config.audioSource}," +
                    "silenced=${config.isClientSilenced},rate=${config.clientFormat.sampleRate}," +
                    "channels=${config.clientFormat.channelCount},encoding=${config.clientFormat.encoding}"
            }.ifEmpty { "none" }
            if (configurations.isNotEmpty() && stage == "DURING_TEST") run?.microphoneObserved?.set(true)
            if (run == null || run.lastMicrophoneState != state) {
                if (run != null) run.lastMicrophoneState = state
                log(
                    "MICROPHONE_RECORDING_STATE", run,
                    "stage=$stage activeCount=${configurations.size} details=$state " +
                        "pollingProvesNoFallback=false providerPcmConsumption=UNVERIFIED"
                )
            }
            configurations
        } catch (exception: Exception) {
            run?.microphoneMonitorSupported?.set(false)
            log("MICROPHONE_DIAGNOSTIC_UNAVAILABLE", run, "stage=$stage exception=${exception.javaClass.simpleName}")
            null
        }
    }

    private fun callback(run: Run, event: String, details: String = "") {
        if (isCurrent(run)) log(event, run, "elapsedMs=${elapsed(run)} $details")
    }

    private fun isCurrent(run: Run): Boolean = activeRun === run && !run.cleanupStarted.get()

    private fun complete(run: Run) {
        if (!isCurrent(run) || !run.recognitionTerminal.get() || !run.writerReachedEof.get()) return
        cleanup(run, "terminal_callback_and_writer_eof", cancelRecognizer = false)
        val micState = microphoneState(run)
        log(
            "SESSION_COMPLETED", run,
            "outcome=${run.terminalOutcome} writerEof=true bytesWritten=${run.bytesWritten.get()}/${run.totalBytes} " +
                "readClosed=${run.readDescriptorClosed.get()} writeClosed=${run.writeDescriptorClosed.get()} " +
                "microphone=$micState providerPcmConsumption=UNVERIFIED elapsedMs=${elapsed(run)}"
        )
        status("${run.sample.id}: ${run.terminalOutcome}; provider PCM consumption remains unverified.")
    }

    private fun abort(run: Run, reason: String) {
        if (!isCurrent(run)) return
        run.cancellationReason = reason
        cleanup(run, reason, cancelRecognizer = run.recognitionStarted.get() && !run.recognitionTerminal.get())
        log(
            "SESSION_ABORTED", run,
            "reason=$reason recognitionStartedConfirmed=${run.recognitionStarted.get()} " +
                "recognitionTerminal=${run.recognitionTerminal.get()} writerEof=${run.writerReachedEof.get()} " +
                "bytesWritten=${run.bytesWritten.get()}/${run.totalBytes} providerPcmConsumption=UNVERIFIED"
        )
        status("${run.sample.id}: test aborted ($reason).")
    }

    private fun cleanup(run: Run, reason: String, cancelRecognizer: Boolean) {
        if (!run.cleanupStarted.compareAndSet(false, true)) return
        activeRun = null
        sessionTimeout?.let(handler::removeCallbacks)
        microphonePoll?.let(handler::removeCallbacks)
        sessionTimeout = null
        microphonePoll = null

        log(
            "SESSION_CLEANUP_REQUEST", run,
            "reason=$reason cancellationRequested=$cancelRecognizer " +
                "recognitionStartedConfirmed=${run.recognitionStarted.get()}"
        )
        if (cancelRecognizer) {
            try {
                run.recognizer.cancel()
            } catch (exception: Exception) {
                log("RECOGNIZER_CANCEL_ERROR", run, "exception=${exception.javaClass.simpleName}")
            }
        }
        try {
            run.recognizer.destroy()
        } catch (exception: Exception) {
            log("RECOGNIZER_DESTROY_ERROR", run, "exception=${exception.javaClass.simpleName}")
        }

        synchronized(run.descriptorLock) {
            try {
                run.output?.close()
                if (run.output != null) run.writeDescriptorClosed.set(true)
                run.output = null
            } catch (exception: Exception) {
                log("DESCRIPTOR_CLOSE_ERROR", run, "side=write_stream exception=${exception.javaClass.simpleName}")
            }
            run.writeDescriptor?.let {
                if (closeQuietly(it, "SESSION_CLEANUP", run.token, run.sample)) {
                    run.writeDescriptorClosed.set(true)
                }
            }
            run.writeDescriptor = null
        }
        if (closeQuietly(run.readEnd, "SESSION_CLEANUP", run.token, run.sample)) {
            run.readDescriptorClosed.set(true)
        }
        log(
            "SESSION_CLEANUP_COMPLETED", run,
            "reason=$reason readClosed=${run.readDescriptorClosed.get()} writeClosed=${run.writeDescriptorClosed.get()} " +
                "writerEof=${run.writerReachedEof.get()} " +
                "bytesWritten=${run.bytesWritten.get()}/${run.totalBytes} microphone=${microphoneState(run)}"
        )
    }

    private fun microphoneState(run: Run): String = when {
        !run.microphoneMonitorSupported.get() -> "UNAVAILABLE"
        run.microphoneObserved.get() -> "ACTIVE_RECORDING_OBSERVED"
        else -> "NO_ACTIVE_RECORDING_OBSERVED_BY_PERIODIC_POLLING"
    }

    private fun closeQuietly(descriptor: ParcelFileDescriptor, stage: String, token: String, sample: Sample): Boolean {
        val descriptorNumber = try { descriptor.fd } catch (_: Exception) { -1 }
        try {
            descriptor.close()
            log("DESCRIPTOR_CLOSED", token, sample, "stage=$stage fd=$descriptorNumber")
            return true
        } catch (exception: Exception) {
            log("DESCRIPTOR_CLOSE_ERROR", token, sample, "stage=$stage exception=${exception.javaClass.simpleName}")
            return false
        }
    }

    private fun clearPreparing() {
        preparing = false
        preparingToken = null
        preparingSample = null
    }

    private fun normalize(text: String): String = text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    private fun elapsed(run: Run): Long = SystemClock.elapsedRealtime() - run.startedAtMs

    private fun status(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    private fun log(event: String, run: Run? = null, details: String = "") {
        val contextRun = run ?: activeRun
        val token = contextRun?.token ?: preparingToken ?: "none"
        val sample = contextRun?.sample ?: preparingSample
        Log.i(
            TAG,
            "event=$event elapsedMs=${SystemClock.elapsedRealtime()} activityInstanceId=$activityInstanceId " +
                "recreatedContext=${if (isChangingConfigurations) "configuration_change" else "current"} " +
                "sessionToken=$token sample=${sample?.id ?: "none"} $details"
        )
    }

    private fun log(event: String, token: String, sample: Sample, details: String) {
        Log.i(
            TAG,
            "event=$event elapsedMs=${SystemClock.elapsedRealtime()} activityInstanceId=$activityInstanceId " +
                "sessionToken=$token sample=${sample.id} $details"
        )
    }
}
