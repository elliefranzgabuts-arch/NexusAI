
package com.nexusai.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val TAG = "NEXUS-MainActivity"
private const val COMMAND_TIMEOUT = 10_000L
private val mainHandler = Handler(Looper.getMainLooper())

private fun endCommandSessionAfterRecognizerCleanup(
    commandToken: String,
    onEnded: ((Boolean) -> Unit)? = null
) {
    // SpeechRecognizer dispatches cancel/destroy work through the main looper.
    // Queue coordinator resumption after those framework cleanup messages.
    mainHandler.post {
        val ended = WakeWordCoordinator.endCommandSession(commandToken)
        onEnded?.invoke(ended)
    }
}

private fun disposeSpeechRecognizer(recognizer: SpeechRecognizer?) {
    if (recognizer == null) return

    try {
        recognizer.cancel()
    } catch (exception: Exception) {
        Log.w(TAG, "Could not cancel speech recognizer during cleanup.", exception)
    }

    try {
        recognizer.destroy()
    } catch (exception: Exception) {
        Log.w(TAG, "Could not destroy speech recognizer during cleanup.", exception)
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                NexusScreen()
            }
        }
    }
}

@Composable
fun NexusScreen() {
    val context = LocalContext.current

    var isListening by remember { mutableStateOf(false) }
    var isActivated by remember { mutableStateOf(false) }
    var isCommandListening by remember { mutableStateOf(false) }

    var recognizedCommand by remember { mutableStateOf("") }
    var commandResponse by remember { mutableStateOf("") }
    var speechError by remember { mutableStateOf("") }
    var activeCommandToken by remember { mutableStateOf<String?>(null) }
    var activeSpeechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    // Complete only this command's token, and release SpeechRecognizer before
    // the coordinator is allowed to reacquire the microphone.
    val finishCommandSession = rememberUpdatedState<(String) -> Unit>(
        newValue = { commandToken ->
            if (activeCommandToken == commandToken) {
                activeCommandToken = null
                isCommandListening = false
                isActivated = false

                val recognizer = activeSpeechRecognizer
                activeSpeechRecognizer = null
                disposeSpeechRecognizer(recognizer)

                endCommandSessionAfterRecognizerCleanup(commandToken) { ended ->
                    if (ended) {
                        isListening = true
                        Log.i(TAG, "Command session $commandToken finished; wake detection resumed.")
                    }
                }
            } else {
                Log.i(TAG, "Ignoring stale command completion: $commandToken")
            }
        }
    )

    // Each recognition listener captures its command token. A delayed callback
    // from an older SpeechRecognizer cannot finish a newer command session.
    val startCommandRecognition = rememberUpdatedState<(String) -> Unit>(
        newValue = { commandToken ->
            if (activeCommandToken == commandToken) {
                val recognizer = try {
                    if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } else {
                        null
                    }
                } catch (exception: Exception) {
                    Log.e(TAG, "Could not create speech recognizer.", exception)
                    null
                }

                if (recognizer == null) {
                    speechError = "ON-DEVICE SPEECH UNAVAILABLE"
                    isActivated = false
                    finishCommandSession.value(commandToken)
                } else {
                    activeSpeechRecognizer = recognizer

                    fun isCurrentSession(): Boolean =
                        activeCommandToken == commandToken &&
                            activeSpeechRecognizer === recognizer

                    val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }

                    try {
                        recognizer.setRecognitionListener(
                            object : RecognitionListener {
                                override fun onReadyForSpeech(params: Bundle?) {
                                    if (isCurrentSession()) Log.i(TAG, "Ready for command speech.")
                                }

                                override fun onBeginningOfSpeech() {
                                    if (isCurrentSession()) Log.i(TAG, "Command speech detected.")
                                }

                                override fun onRmsChanged(rmsdB: Float) {}

                                override fun onBufferReceived(buffer: ByteArray?) {}

                                override fun onEndOfSpeech() {
                                    if (isCurrentSession()) Log.i(TAG, "End of command speech.")
                                }

                                override fun onError(error: Int) {
                                    if (!isCurrentSession()) {
                                        Log.i(TAG, "Ignoring late speech error for $commandToken")
                                        return
                                    }

                                    Log.e(TAG, "Speech recognition error: $error")
                                    speechError = when (error) {
                                        SpeechRecognizer.ERROR_NO_MATCH -> "NO SPEECH RECOGNIZED"
                                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "NO COMMAND DETECTED"
                                        SpeechRecognizer.ERROR_NETWORK,
                                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK ERROR"
                                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                            "MICROPHONE PERMISSION REQUIRED"
                                        else -> "SPEECH ERROR: $error"
                                    }

                                    finishCommandSession.value(commandToken)
                                }

                                override fun onResults(results: Bundle?) {
                                    if (!isCurrentSession()) {
                                        Log.i(TAG, "Ignoring late speech results for $commandToken")
                                        return
                                    }

                                    val matches = results?.getStringArrayList(
                                        SpeechRecognizer.RESULTS_RECOGNITION
                                    )
                                    val command = matches?.firstOrNull()

                                    if (!command.isNullOrBlank()) {
                                        recognizedCommand = command
                                        speechError = ""
                                        commandResponse = try {
                                            LocalCommandEngine.execute(context, command)
                                        } catch (exception: Exception) {
                                            Log.e(TAG, "Command execution failed.", exception)
                                            "I couldn't execute that command."
                                        }

                                        Log.i(TAG, "Recognized command: $command")
                                        Log.i(TAG, "Command response: $commandResponse")
                                    } else {
                                        commandResponse = ""
                                        speechError = "NO COMMAND RECOGNIZED"
                                    }

                                    finishCommandSession.value(commandToken)
                                }

                                override fun onPartialResults(partialResults: Bundle?) {}

                                override fun onEvent(eventType: Int, params: Bundle?) {}
                            }
                        )
                        recognizer.startListening(speechIntent)
                        Log.i(TAG, "10-second command listening started for $commandToken.")
                    } catch (exception: Exception) {
                        Log.e(TAG, "Could not start speech recognition.", exception)
                        speechError = "COULD NOT START SPEECH RECOGNITION"
                        finishCommandSession.value(commandToken)
                    }
                }
            }
        }
    )

    // Composition disposal ends an active command, but backgrounding the
    // Activity alone does not dispose this effect or stop wake-word listening.
    DisposableEffect(context) {
        onDispose {
            val commandToken = activeCommandToken
            activeCommandToken = null
            isCommandListening = false

            val recognizer = activeSpeechRecognizer
            activeSpeechRecognizer = null
            disposeSpeechRecognizer(recognizer)

            if (commandToken != null) {
                endCommandSessionAfterRecognizerCleanup(commandToken)
            }
        }
    }

    // Start command recognition when the wake phrase is received.
    val onWakeDetected = rememberUpdatedState<(Intent?) -> Unit>(
        newValue = { intent ->

            Log.i(TAG, "Broadcast received: ${intent?.action}")

            val isWakeEvent = intent?.action == WakeWordService.ACTION_WAKE_DETECTED
            val commandToken = if (isWakeEvent) {
                intent.getStringExtra(WakeWordService.EXTRA_WAKE_TOKEN)
            } else {
                null
            }
            val wakeTokenClaimed = if (isWakeEvent) {
                WakeWordCoordinator.acknowledgeWake(commandToken)
            } else {
                false
            }

            if (isWakeEvent && !wakeTokenClaimed) {
                Log.w(TAG, "Ignoring expired or duplicate wake event.")
            }

            if (isWakeEvent && wakeTokenClaimed && commandToken != null) {
                val claimedCommandToken = commandToken
                activeCommandToken = claimedCommandToken

                Log.i(TAG, "HEY NEXUS detected.")

                recognizedCommand = ""
                commandResponse = ""
                speechError = ""
                isActivated = true

                isCommandListening = true
                startCommandRecognition.value(claimedCommandToken)
            }
        }
    )

    // Register wake-word receiver.
    DisposableEffect(context) {

        val wakeWordReceiver = object : BroadcastReceiver() {
            override fun onReceive(
                receiverContext: Context?,
                intent: Intent?
            ) {
                onWakeDetected.value(intent)
            }
        }

        val filter = IntentFilter(
            WakeWordService.ACTION_WAKE_DETECTED
        )

        context.registerReceiver(
            wakeWordReceiver,
            filter,
            Context.RECEIVER_NOT_EXPORTED
        )

        Log.i(TAG, "Wake-word receiver registered.")

        onDispose {
            context.unregisterReceiver(wakeWordReceiver)
            Log.i(TAG, "Wake-word receiver unregistered.")
        }
    }

    // Enforce the 10-second command timeout.
    LaunchedEffect(isCommandListening) {

        val commandToken = activeCommandToken
        if (isCommandListening && commandToken != null) {
            delay(COMMAND_TIMEOUT)

            if (isCommandListening && activeCommandToken == commandToken) {
                Log.i(TAG, "10-second command timeout reached.")

                speechError = "COMMAND TIMEOUT"
                finishCommandSession.value(commandToken)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->

        val microphoneGranted =
            permissions[Manifest.permission.RECORD_AUDIO] == true ||
                context.checkSelfPermission(
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

        if (microphoneGranted) {
            val serviceIntent = Intent(
                context,
                WakeWordService::class.java
            ).apply {
                action = WakeWordService.ACTION_START
            }

            context.startForegroundService(serviceIntent)
            isListening = true

            Log.i(TAG, "Wake-word service start requested.")

        } else {
            Toast.makeText(
                context,
                "Microphone permission is required for NEXUS.",
                Toast.LENGTH_LONG
            ).show()

            isListening = false
        }
    }

    fun startListening() {
        val microphoneGranted =
            context.checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

        if (microphoneGranted) {
            val serviceIntent = Intent(
                context,
                WakeWordService::class.java
            ).apply {
                action = WakeWordService.ACTION_START
            }

            context.startForegroundService(serviceIntent)
            isListening = true

            Log.i(TAG, "Wake-word service start requested.")

        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            )
        }
    }

    fun stopListening() {
        WakeWordCoordinator.prepareExplicitStop()

        activeCommandToken = null
        isCommandListening = false

        val recognizer = activeSpeechRecognizer
        activeSpeechRecognizer = null
        disposeSpeechRecognizer(recognizer)

        val serviceIntent = Intent(
            context,
            WakeWordService::class.java
        ).apply {
            action = WakeWordService.ACTION_STOP
        }

        context.startService(serviceIntent)

        isListening = false
        isActivated = false

        Log.i(TAG, "NEXUS listening stopped.")
    }

    val backgroundColor = Color.Black
    val ringColor = Color(0xFF383838)
    val highlightColor = Color(0xFFD9D9D9)

    val activeColor = if (isActivated) {
        Color.White
    } else {
        highlightColor
    }

    val infiniteTransition = rememberInfiniteTransition(
        label = "nexusRings"
    )

    val outerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                18000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "outerRotation"
    )

    val middleRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                12000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "middleRotation"
    )

    val innerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                8000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "innerRotation"
    )

    val centerScale by animateFloatAsState(
        targetValue = when {
            isCommandListening -> 1.22f
            isActivated -> 1.22f
            isListening -> 1.12f
            else -> 1f
        },
        animationSpec = tween(350),
        label = "centerScale"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Text(
                text = "PERSONAL AI ASSISTANT",
                color = Color(0xFF666666),
                fontSize = 9.sp,
                letterSpacing = 3.sp
            )

            Spacer(modifier = Modifier.height(38.dp))

            Box(
                modifier = Modifier.size(280.dp),
                contentAlignment = Alignment.Center
            ) {

                // Outer mechanical ring
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .rotate(outerRotation)
                        .border(
                            width = if (isActivated) 2.dp else 1.dp,
                            color = if (isActivated) activeColor else ringColor,
                            shape = CircleShape
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .width(22.dp)
                            .height(3.dp)
                            .background(activeColor)
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .width(22.dp)
                            .height(3.dp)
                            .background(ringColor)
                    )
                }

                // Middle ring
                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .rotate(middleRotation)
                        .border(
                            width = if (isActivated) 3.dp else 2.dp,
                            color = if (isActivated) activeColor
                            else Color(0xFF555555),
                            shape = CircleShape
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(4.dp)
                            .height(18.dp)
                            .background(activeColor)
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(4.dp)
                            .height(18.dp)
                            .background(ringColor)
                    )
                }

                // Inner ring
                Box(
                    modifier = Modifier
                        .size(155.dp)
                        .rotate(innerRotation)
                        .border(
                            width = if (isActivated) 2.dp else 1.dp,
                            color = if (isActivated) activeColor
                            else Color(0xFF777777),
                            shape = CircleShape
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .size(7.dp)
                            .background(activeColor, CircleShape)
                    )
                }

                // Interactive center
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .scale(centerScale)
                        .background(
                            Color(0xFF111111),
                            CircleShape
                        )
                        .border(
                            width = if (isActivated) 3.dp
                            else if (isListening) 2.dp else 1.dp,
                            color = if (isActivated) Color.White
                            else if (isListening) highlightColor
                            else ringColor,
                            shape = CircleShape
                        )
                        .clickable(
                            interactionSource = remember {
                                MutableInteractionSource()
                            },
                            indication = null
                        ) {
                            if (isListening) {
                                stopListening()
                            } else {
                                startListening()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (isActivated) 17.dp else 13.dp)
                            .background(
                                color = Color.White,
                                shape = CircleShape
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(42.dp))

            Text(
                text = "NEXUS",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "INTELLIGENCE IN MOTION",
                color = Color(0xFF888888),
                fontSize = 10.sp,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = when {
                    isCommandListening -> "LISTENING FOR COMMAND"
                    isActivated -> "ACTIVATED"
                    isListening -> "LISTENING"
                    else -> "STANDBY"
                },
                color = if (isActivated || isListening) {
                    Color.White
                } else {
                    Color(0xFF888888)
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 3.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = when {
                    isCommandListening -> "SPEAK NOW · 10 SECOND WINDOW"
                    recognizedCommand.isNotBlank() ->
                        "HEARD: ${recognizedCommand.uppercase()}"
                    speechError.isNotBlank() ->
                        speechError
                    isListening ->
                        "LISTENING FOR NEXUS"
                    else ->
                        "TAP CENTER TO ACTIVATE"
                },
                color = if (isActivated) {
                    Color(0xFFD9D9D9)
                } else {
                    Color(0xFF555555)
                },
                fontSize = 9.sp,
                letterSpacing = 1.2.sp
            )

            if (commandResponse.isNotBlank()) {
                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = commandResponse,
                    color = Color.White,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
