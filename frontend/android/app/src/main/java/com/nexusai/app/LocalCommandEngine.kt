package com.nexusai.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LocalCommandEngine {

    private const val TAG = "NEXUS-CommandEngine"

    fun execute(context: Context, rawCommand: String): String {
        val command = rawCommand
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        Log.i(TAG, "Processing command: $command")

        return when (command) {
            "what time is it",
            "tell me the time",
            "current time",
            "what is the time",
            "what s the time",
            "time" -> {
                val time = SimpleDateFormat(
                    "h:mm a",
                    Locale.getDefault()
                ).format(Date())

                "It's $time."
            }

            "open settings",
            "open my settings",
            "settings",
            "open phone settings",
            "open device settings" -> {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    "Opening device settings."
                } catch (exception: Exception) {
                    Log.e(TAG, "Could not open settings.", exception)
                    "I couldn't open device settings."
                }
            }

            "open calculator",
            "calculator",
            "launch calculator",
            "start calculator" -> {
                try {
                    val intent = context.packageManager
                        .getLaunchIntentForPackage("com.transsion.calculator")

                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        "Opening calculator."
                    } else {
                        "I couldn't find the calculator app."
                    }
                } catch (exception: Exception) {
                    Log.e(TAG, "Could not open calculator.", exception)
                    "I couldn't open the calculator."
                }
            }

            "hello",
            "hi",
            "hey",
            "hello nexus",
            "hi nexus",
            "hey nexus" ->
                "Hello! I'm NEXUS. How can I help you?"

            "how are you",
            "how are you doing" ->
                "I'm running and ready to assist you."

            "who are you",
            "what is your name",
            "who is nexus" ->
                "I'm NEXUS, your personal AI assistant."

            "what can you do",
            "help",
            "show me what you can do" ->
                "I can tell the current time, open device settings, " +
                    "open the calculator, and respond to basic greetings."

            else ->
                "I heard: $rawCommand. That command isn't supported yet."
        }
    }
}
