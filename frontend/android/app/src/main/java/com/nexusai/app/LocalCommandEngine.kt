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

    internal enum class Command {
        TIME,
        DATE,
        SETTINGS,
        CALCULATOR,
        GREETING,
        HOW_ARE_YOU,
        IDENTITY,
        HELP,
        UNKNOWN
    }

    internal fun normalize(rawCommand: String): String = rawCommand
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    internal fun classify(rawCommand: String): Command = when (normalize(rawCommand)) {
        "what time is it", "tell me the time", "current time", "what is the time",
        "what s the time", "time", "anong oras", "anong oras na", "oras ngayon" -> Command.TIME

        "what is the date", "what s the date", "today s date", "current date",
        "what day is it", "anong petsa", "petsa ngayon", "anong araw ngayon" -> Command.DATE

        "open settings", "open my settings", "settings", "open phone settings",
        "open device settings", "buksan ang settings", "buksan settings" -> Command.SETTINGS

        "open calculator", "calculator", "launch calculator", "start calculator",
        "buksan ang calculator", "buksan calculator" -> Command.CALCULATOR

        "hello", "hi", "hey", "hello nexus", "hi nexus", "hey nexus",
        "kamusta", "kumusta", "mabuhay" -> Command.GREETING

        "how are you", "how are you doing", "kumusta ka" -> Command.HOW_ARE_YOU
        "who are you", "what is your name", "who is nexus", "sino ka" -> Command.IDENTITY
        "what can you do", "help", "show me what you can do", "ano ang kaya mong gawin" -> Command.HELP
        else -> Command.UNKNOWN
    }

    fun execute(context: Context, rawCommand: String): String {
        Log.i(TAG, "Processing local command")

        return when (classify(rawCommand)) {
            Command.TIME -> {
                val time = SimpleDateFormat(
                    "h:mm a",
                    Locale.getDefault()
                ).format(Date())

                "It's $time."
            }

            Command.DATE -> {
                val date = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
                "Today is $date."
            }

            Command.SETTINGS -> {
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

            Command.CALCULATOR -> {
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

            Command.GREETING ->
                "Hello! I'm NEXUS. How can I help you?"

            Command.HOW_ARE_YOU ->
                "I'm running and ready to assist you."

            Command.IDENTITY ->
                "I'm NEXUS, your personal AI assistant."

            Command.HELP ->
                "I can tell the current time, open device settings, " +
                    "open the calculator, and respond to basic greetings. General AI answers " +
                    "need an online assistant service, which has not been configured."

            Command.UNKNOWN ->
                "I can't answer that with the local features available. General questions need " +
                    "a configured online AI service."
        }
    }
}
