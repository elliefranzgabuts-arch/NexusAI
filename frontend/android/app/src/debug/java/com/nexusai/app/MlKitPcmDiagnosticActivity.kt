package com.nexusai.app

import android.graphics.Color
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A debug-only, external-PCM-only ML Kit GenAI recognition experiment. */
class MlKitPcmDiagnosticActivity : ComponentActivity() {
    private data class Sample(val id: String, val fileName: String)

    private data class TestResources(
        val source: AudioSource,
        val writer: AtomicReference<ParcelFileDescriptor.AutoCloseOutputStream?>,
    )

    companion object {
        private const val TAG = "NEXUS_MLKIT_PCM_DIAGNOSTIC"
        private const val SAMPLE_RATE_HZ = 16_000
        private const val CHANNEL_COUNT = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val MAX_SAMPLE_DURATION_SECONDS = 30
        private const val RECOGNITION_TIMEOUT_MS = 60_000L

        private val POSITIVE = Sample("positive", "positive.pcm")
        private val NEGATIVE = Sample("negative", "negative.pcm")
    }

    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var positiveButton: Button
    private lateinit var negativeButton: Button
    private lateinit var cancelButton: Button

    private var recognizer: SpeechRecognizer? = null
    private var featureStatus: Int? = null
    private var recognitionJob: Job? = null
    private var currentResources: TestResources? = null
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        showStatus("Recognizer initialization: starting")
        showDetails(
            "Mode: Basic\nLocale: en-US\nInput: raw signed PCM16, little-endian\n" +
                "Channels: mono\nSample rate: 16,000 Hz\nRecognition mode: ONE_SHOT"
        )
        log("ACTIVITY_CREATED", "activityId=${UUID.randomUUID()}")
        initializeRecognizerAndCheckStatus()
    }

    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(Color.rgb(5, 8, 16))
        }
        val heading = TextView(this).apply {
            text = "NEXUS · ML Kit PCM diagnostic"
            textSize = 21f
            setTextColor(Color.rgb(120, 205, 255))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }
        statusView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 14)
        }
        detailView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 18)
        }
        positiveButton = makeButton("Run positive sample (NEXUS)") { startTest(POSITIVE) }
        negativeButton = makeButton("Run negative sample (greeting)") { startTest(NEGATIVE) }
        cancelButton = makeButton("Cancel diagnostic") { cancelTest("cancel_button") }
        cancelButton.visibility = View.GONE

        content.addView(heading)
        content.addView(statusView)
        content.addView(detailView)
        content.addView(positiveButton)
        content.addView(negativeButton)
        content.addView(cancelButton)
        setContentView(ScrollView(this).apply { addView(content) })
        setButtonsEnabled(false)
    }

    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun initializeRecognizerAndCheckStatus() {
        lifecycleScope.launch {
            try {
                val options = speechRecognizerOptions {
                    locale = Locale.US
                    preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
                }
                val client = SpeechRecognition.getClient(options)
                recognizer = client
                log("RECOGNIZER_INITIALIZED", "mode=BASIC locale=en-US")
                showStatus("Recognizer initialized; checking feature availability…")

                val actualStatus = withContext(Dispatchers.IO) { client.checkStatus() }
                featureStatus = actualStatus
                val label = featureStatusName(actualStatus)
                log("FEATURE_STATUS", "status=$label statusValue=$actualStatus")
                when (actualStatus) {
                    FeatureStatus.AVAILABLE -> {
                        showStatus("Feature status: AVAILABLE. Select a PCM sample.")
                        setButtonsEnabled(true)
                    }
                    FeatureStatus.DOWNLOADABLE -> {
                        showStatus("Feature status: DOWNLOADABLE. Model preparation is required; no download was started.")
                        log("RECOGNITION_SKIPPED", "reason=feature_downloadable automaticDownload=false")
                    }
                    FeatureStatus.DOWNLOADING -> {
                        showStatus("Feature status: DOWNLOADING. No recognition was started.")
                        log("RECOGNITION_SKIPPED", "reason=feature_downloading automaticDownload=false")
                    }
                    FeatureStatus.UNAVAILABLE -> {
                        showStatus("Feature status: UNAVAILABLE. Recognition is unavailable on this device/configuration.")
                        log("RECOGNITION_SKIPPED", "reason=feature_unavailable automaticDownload=false")
                    }
                    else -> {
                        showStatus("Feature status: UNKNOWN ($actualStatus). Recognition was skipped.")
                        log("RECOGNITION_SKIPPED", "reason=unknown_feature_status value=$actualStatus")
                    }
                }
            } catch (exception: Exception) {
                logFailure("RECOGNIZER_INITIALIZATION_ERROR", exception)
                showStatus("Recognizer initialization/status check failed: ${exception.message ?: exception.javaClass.simpleName}")
            }
        }
    }

    private fun startTest(sample: Sample) {
        if (closed || recognitionJob?.isActive == true) return
        if (featureStatus != FeatureStatus.AVAILABLE) {
            showStatus("Recognition skipped: feature status is not AVAILABLE.")
            log("RECOGNITION_SKIPPED", "sample=${sample.id} reason=status_not_available status=${featureStatus?.let(::featureStatusName) ?: "unchecked"}")
            return
        }

        val sampleDirectory = getExternalFilesDir("pcm-diagnostic")
        val pcmFile = sampleDirectory?.let { File(it, sample.fileName) }
        if (pcmFile == null || !pcmFile.isFile || !pcmFile.canRead() || pcmFile.length() <= 0L || pcmFile.length() % BYTES_PER_SAMPLE != 0L) {
            val path = pcmFile?.absolutePath ?: "app external files directory unavailable"
            showStatus("Invalid or missing sample: $path")
            log("INPUT_REJECTED", "sample=${sample.id} path=$path bytes=${pcmFile?.length() ?: -1L}")
            return
        }
        val maxBytes = SAMPLE_RATE_HZ.toLong() * CHANNEL_COUNT * BYTES_PER_SAMPLE * MAX_SAMPLE_DURATION_SECONDS
        if (pcmFile.length() > maxBytes) {
            showStatus("Sample rejected: longer than $MAX_SAMPLE_DURATION_SECONDS seconds.")
            log("INPUT_REJECTED", "sample=${sample.id} reason=duration_limit bytes=${pcmFile.length()}")
            return
        }

        val client = recognizer
        if (client == null) {
            showStatus("Recognition skipped: recognizer is not initialized.")
            return
        }

        val sessionId = UUID.randomUUID().toString()
        log("SESSION_CREATED", "session=$sessionId sample=${sample.id} file=${sample.fileName} bytes=${pcmFile.length()}")
        setButtonsEnabled(false)
        cancelButton.visibility = View.VISIBLE
        showStatus("Preparing ${sample.id} sample…")
        showDetails(
            "Mode: Basic\nLocale: en-US\nSample: ${sample.fileName}\n" +
                "Format: raw signed PCM16 little-endian, mono, 16,000 Hz\n" +
                "Input bytes: ${pcmFile.length()}\nBytes written: pending\nPipe EOF: pending\n" +
                "Recognition: pending\nProvider PCM consumption: not established by bytes written"
        )

        recognitionJob = lifecycleScope.launch {
            var resources: TestResources? = null
            var pipe: Array<ParcelFileDescriptor>? = null
            var unownedWriteEnd: ParcelFileDescriptor? = null
            var unownedAudioSource: AudioSource? = null
            try {
                withTimeout(RECOGNITION_TIMEOUT_MS) {
                    pipe = ParcelFileDescriptor.createPipe()
                    unownedWriteEnd = pipe!![1]
                    val externalSource = AudioSource.fromPfd(pipe!![0], AudioSource.Mode.ONE_SHOT)
                    unownedAudioSource = externalSource
                    pipe = null // AudioSource owns the read end from this point.
                    val writerStream = ParcelFileDescriptor.AutoCloseOutputStream(unownedWriteEnd!!)
                    unownedWriteEnd = null // AutoCloseOutputStream owns the write end.
                    val writerRef = AtomicReference<ParcelFileDescriptor.AutoCloseOutputStream?>(writerStream)
                    resources = TestResources(externalSource, writerRef)
                    unownedAudioSource = null // TestResources closes the AudioSource.
                    currentResources = resources
                    log("AUDIO_SOURCE_CREATED", "session=$sessionId source=PFD mode=ONE_SHOT format=PCM_16BIT channels=1 sampleRateHz=16000")

                    val request: SpeechRecognizerRequest = speechRecognizerRequest {
                        audioSource = externalSource
                    }
                    showStatus("Recognition started for ${sample.id}; waiting for pipe EOF and response.")
                    log("RECOGNITION_STARTED", "session=$sessionId sample=${sample.id} startRecognitionFlow=collected")

                    val writer = async(Dispatchers.IO) {
                        var bytesWritten = 0L
                        var eof = false
                        try {
                            FileInputStream(pcmFile).use { input ->
                                writerStream.use { output ->
                                    val buffer = ByteArray(16 * 1024)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) {
                                            eof = true
                                            break
                                        }
                                        output.write(buffer, 0, count)
                                        bytesWritten += count
                                    }
                                    output.flush()
                                }
                            }
                            writerRef.set(null)
                            log("PCM_WRITER_EOF", "session=$sessionId eof=$eof bytesWritten=$bytesWritten/${pcmFile.length()} descriptorClosed=true")
                            runOnUiThread {
                                showDetails(
                                    "Input sample: ${sample.fileName}\nBytes written: $bytesWritten/${pcmFile.length()}\n" +
                                        "Pipe EOF: $eof\nWrite descriptor closed: true\nRecognition: in progress\n" +
                                        "Provider PCM consumption: unverified"
                                )
                            }
                            bytesWritten to eof
                        } catch (exception: Exception) {
                            try { writerRef.getAndSet(null)?.close() } catch (_: Exception) { }
                            logFailure("PCM_WRITER_ERROR", exception, "session=$sessionId bytesWritten=$bytesWritten")
                            runOnUiThread {
                                showDetails(
                                    "Input sample: ${sample.fileName}\nBytes written: $bytesWritten/${pcmFile.length()}\n" +
                                        "Pipe EOF: false\nWrite descriptor closure attempted\n" +
                                        "Writer error: ${exception.message ?: exception.javaClass.simpleName}"
                                )
                            }
                            throw exception
                        }
                    }

                    var finalTranscript: String? = null
                    var completedCallback = false
                    var responseFlowCompleted = false
                    try {
                        client.startRecognition(request).collect { response ->
                            when (response) {
                                is SpeechRecognizerResponse.PartialTextResponse -> {
                                    log("PARTIAL_RESULT", "session=$sessionId transcript=${response.text}")
                                    showDetails("Partial transcript: ${response.text}\n" + detailView.text)
                                }
                                is SpeechRecognizerResponse.FinalTextResponse -> {
                                    finalTranscript = response.text
                                    log("FINAL_RESULT", "session=$sessionId transcript=${response.text}")
                                    showDetails("Final transcript: ${response.text}\n" + detailView.text)
                                }
                                is SpeechRecognizerResponse.ErrorResponse -> {
                                    log("RECOGNITION_ERROR_RESPONSE", "session=$sessionId code=${response.e.errorCode} message=${response.e.message}")
                                    showStatus("Recognition error: ${response.e.message ?: response.e.errorCode}")
                                    showDetails("Recognition error ${response.e.errorCode}: ${response.e.message}\n" + detailView.text)
                                }
                                is SpeechRecognizerResponse.CompletedResponse -> {
                                    completedCallback = true
                                    log("RECOGNITION_COMPLETED_CALLBACK", "session=$sessionId")
                                }
                                else -> log("RECOGNITION_RESPONSE", "session=$sessionId type=${response.javaClass.simpleName}")
                            }
                        }
                        responseFlowCompleted = true
                    } finally {
                        if (!responseFlowCompleted || !currentCoroutineContext().isActive) {
                            try { writerRef.getAndSet(null)?.close() } catch (exception: Exception) {
                                logFailure("WRITER_CLOSE_ERROR", exception, "session=$sessionId reason=flow_not_completed")
                            }
                            try {
                                withContext(NonCancellable) { client.stopRecognition() }
                                log("RECOGNITION_STOPPED", "session=$sessionId reason=flow_not_completed")
                            } catch (exception: Exception) {
                                logFailure("STOP_RECOGNITION_ERROR", exception, "session=$sessionId reason=flow_not_completed")
                            }
                        }
                    }

                    val (written, writerEof) = writer.await()
                    log(
                        "SESSION_FINISHED",
                        "session=$sessionId sample=${sample.id} bytesWritten=$written/${pcmFile.length()} " +
                            "writerEof=$writerEof completedCallback=$completedCallback finalResultPresent=${finalTranscript != null} " +
                            "providerPcmConsumption=UNVERIFIED"
                    )
                    showStatus(if (finalTranscript == null) "Recognition completed without final text." else "Recognition completed.")
                    showDetails(
                        "Mode: Basic\nLocale: en-US\nSample: ${sample.fileName}\n" +
                            "Format: raw signed PCM16 little-endian, mono, 16,000 Hz\n" +
                            "Bytes written: $written/${pcmFile.length()}\nPipe EOF: $writerEof\n" +
                            "Recognition completed callback: $completedCallback\n" +
                            "Transcript: ${finalTranscript ?: "(none)"}\n" +
                            "Provider consumption of supplied PCM: UNVERIFIED"
                    )
                }
            } catch (exception: Exception) {
                if (exception !is kotlinx.coroutines.CancellationException) {
                    logFailure("SESSION_ERROR", exception, "session=$sessionId sample=${sample.id}")
                    showStatus("Diagnostic failed: ${exception.message ?: exception.javaClass.simpleName}")
                } else if (exception is kotlinx.coroutines.TimeoutCancellationException) {
                    log("SESSION_TIMEOUT", "session=$sessionId timeoutMs=$RECOGNITION_TIMEOUT_MS")
                    showStatus("Diagnostic timed out after ${RECOGNITION_TIMEOUT_MS / 1000} seconds.")
                }
            } finally {
                closeResources(resources, sessionId)
                try { unownedAudioSource?.close() } catch (_: Exception) { }
                try { unownedWriteEnd?.close() } catch (_: Exception) { }
                pipe?.forEach { descriptor ->
                    try { descriptor.close() } catch (_: Exception) { }
                }
                if (currentResources === resources) currentResources = null
                cancelButton.visibility = View.GONE
                setButtonsEnabled(featureStatus == FeatureStatus.AVAILABLE)
            }
        }
    }

    private fun cancelTest(reason: String) {
        val job = recognitionJob ?: return
        if (!job.isActive) return
        log("CANCELLATION_REQUESTED", "reason=$reason")
        showStatus("Cancelling diagnostic…")
        try { currentResources?.writer?.getAndSet(null)?.close() } catch (exception: Exception) {
            logFailure("WRITER_CLOSE_ERROR", exception, "reason=$reason")
        }
        job.cancel()
        if (reason != "activity_destroyed") showStatus("Diagnostic cancelled ($reason).")
    }

    private fun closeResources(resources: TestResources?, sessionId: String) {
        if (resources == null) return
        try {
            resources.writer.getAndSet(null)?.close()
        } catch (exception: Exception) {
            logFailure("WRITER_CLEANUP_ERROR", exception, "session=$sessionId")
        }
        try {
            resources.source.close()
            log("AUDIO_SOURCE_CLOSED", "session=$sessionId readDescriptorOwnedByAudioSource=true")
        } catch (exception: Exception) {
            logFailure("AUDIO_SOURCE_CLOSE_ERROR", exception, "session=$sessionId")
        }
    }

    private fun featureStatusName(status: Int): String = when (status) {
        FeatureStatus.AVAILABLE -> "AVAILABLE"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING"
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
        else -> "UNKNOWN($status)"
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        positiveButton.isEnabled = enabled
        negativeButton.isEnabled = enabled
    }

    private fun showStatus(value: String) {
        if (::statusView.isInitialized) statusView.text = value
    }

    private fun showDetails(value: String) {
        if (::detailView.isInitialized) detailView.text = value
    }

    private fun log(event: String, details: String) {
        Log.i(TAG, "event=$event $details")
    }

    private fun logFailure(event: String, exception: Exception, details: String = "") {
        Log.e(TAG, "event=$event $details exception=${exception.javaClass.simpleName} message=${exception.message}", exception)
    }

    override fun onDestroy() {
        closed = true
        log("ACTIVITY_DESTROYED", "changingConfigurations=$isChangingConfigurations")
        val activeJob = recognitionJob
        cancelTest("activity_destroyed")
        val client = recognizer
        recognizer = null
        if (activeJob != null && !activeJob.isCompleted) {
            activeJob.invokeOnCompletion { closeRecognizer(client) }
        } else {
            closeRecognizer(client)
        }
        super.onDestroy()
    }

    private fun closeRecognizer(client: SpeechRecognizer?) {
        if (client == null) return
        try {
            client.close()
            log("RECOGNIZER_CLOSED", "")
        } catch (exception: Exception) {
            logFailure("RECOGNIZER_CLOSE_ERROR", exception)
        }
    }
}
