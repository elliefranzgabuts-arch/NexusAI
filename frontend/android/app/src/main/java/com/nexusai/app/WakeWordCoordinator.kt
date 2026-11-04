package com.nexusai.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory

/** Coordinates microphone ownership across WakeWordService instances. */
internal object WakeWordCoordinator {
    private const val TAG = "NEXUS-WakeWord"

    const val WAKE_ACK_TIMEOUT_MS = 5_000L

    private enum class State {
        IDLE,
        STARTING,
        LISTENING,
        CLEANING,
        WAITING_FOR_ACK,
        COMMAND_ACTIVE,
        STOPPING
    }

    private data class StopRequest(
        val ownerToken: String,
        val startId: Int,
        val requestToken: String,
        val service: WeakReference<WakeWordService>
    )

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    // One process-wide inference executor prevents sessions from overlapping,
    // including during a Service instance handover.
    private val detectionExecutor = Executors.newSingleThreadExecutor(
        namedDaemonThreadFactory("NEXUS-WakeWord-Executor")
    )

    // This executor must remain separate so it can stop a recorder while the
    // detection executor is blocked in AudioRecord.read().
    private val controlScheduler = ScheduledThreadPoolExecutor(
        1,
        namedDaemonThreadFactory("NEXUS-WakeWord-Control")
    ).apply {
        removeOnCancelPolicy = true
    }

    private var state = State.IDLE
    private var currentOwnerToken: String? = null
    private var applicationContext: Context? = null
    private var latestStartId = 0
    private var activeSession: WakeWordSession? = null
    private var pendingStart = false
    private var desiredListening = false
    private var waitingWakeToken: String? = null
    private var activeCommandToken: String? = null
    private var wakeAckTimeout: ScheduledFuture<*>? = null
    private var stopRequest: StopRequest? = null

    private fun namedDaemonThreadFactory(name: String) = ThreadFactory { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }

    fun newOwnerToken(): String = UUID.randomUUID().toString()

    fun attachOwner(
        ownerToken: String,
        context: Context
    ) {
        var sessionToCancel: WakeWordSession? = null

        synchronized(lock) {
            if (currentOwnerToken == ownerToken) {
                return
            }

            currentOwnerToken = ownerToken
            applicationContext = context.applicationContext
            latestStartId = 0
            desiredListening = false
            pendingStart = false
            stopRequest = null
            cancelWakeAckTimeoutLocked()
            waitingWakeToken = null
            activeCommandToken = null

            val currentSession = activeSession
            if (currentSession != null && currentSession.ownerToken != ownerToken) {
                currentSession.cancelled = true
                state = State.STOPPING
                sessionToCancel = currentSession
                Log.i(TAG, "Service owner handover; cancelling old session ${currentSession.sessionToken}")
            } else if (currentSession == null) {
                state = State.IDLE
            }
        }

        sessionToCancel?.let(::scheduleRecorderStop)
    }

    fun detachOwner(ownerToken: String) {
        var sessionToCancel: WakeWordSession? = null

        synchronized(lock) {
            if (currentOwnerToken != ownerToken) {
                Log.i(TAG, "Ignoring detach from stale service owner $ownerToken")
                return
            }

            currentOwnerToken = null
            desiredListening = false
            pendingStart = false
            stopRequest = null
            cancelWakeAckTimeoutLocked()
            waitingWakeToken = null
            activeCommandToken = null

            activeSession?.let { session ->
                session.cancelled = true
                state = State.STOPPING
                sessionToCancel = session
            } ?: run {
                state = State.IDLE
            }
        }

        sessionToCancel?.let(::scheduleRecorderStop)
    }

    /** Returns true when the service can stop immediately on its main thread. */
    fun requestStop(
        ownerToken: String,
        startId: Int,
        service: WakeWordService
    ): Boolean {
        var sessionToCancel: WakeWordSession? = null
        var immediateStop = false

        synchronized(lock) {
            if (currentOwnerToken != ownerToken) return false

            desiredListening = false
            latestStartId = startId
            pendingStart = false
            cancelWakeAckTimeoutLocked()
            waitingWakeToken = null
            activeCommandToken = null

            val request = StopRequest(
                ownerToken = ownerToken,
                startId = startId,
                requestToken = UUID.randomUUID().toString(),
                service = WeakReference(service)
            )
            stopRequest = request

            val session = activeSession
            if (session == null) {
                state = State.STOPPING
                immediateStop = true
            } else {
                session.cancelled = true
                state = State.STOPPING
                sessionToCancel = session
            }
        }

        sessionToCancel?.let(::scheduleRecorderStop)
        return immediateStop
    }

    fun requestStart(ownerToken: String, startId: Int) {
        var sessionToRun: WakeWordSession? = null

        synchronized(lock) {
            if (currentOwnerToken != ownerToken) {
                Log.w(TAG, "Ignoring start from stale service owner $ownerToken")
                return
            }

            desiredListening = true
            latestStartId = startId
            stopRequest = null

            when (state) {
                State.IDLE -> {
                    sessionToRun = createSessionLocked(ownerToken, startId)
                }

                State.STARTING, State.LISTENING -> {
                    // Coalesce this request. If the active worker fails while
                    // handling it, completion can safely retry after cleanup.
                    pendingStart = true
                }

                State.CLEANING, State.STOPPING -> {
                    if (activeSession == null) {
                        state = State.IDLE
                        pendingStart = false
                        sessionToRun = createSessionLocked(ownerToken, startId)
                    } else {
                        pendingStart = true
                    }
                }

                State.WAITING_FOR_ACK -> {
                    cancelWakeAckTimeoutLocked()
                    waitingWakeToken = null
                    sessionToRun = createSessionLocked(ownerToken, startId)
                }

                State.COMMAND_ACTIVE -> {
                    // Only token-aware command completion may resume capture;
                    // an unrelated start must not seize the microphone.
                }
            }
        }

        sessionToRun?.let(::submitSession)
    }

    fun acknowledgeWake(wakeToken: String?): Boolean {
        if (wakeToken.isNullOrBlank()) return false

        synchronized(lock) {
            if (
                state != State.WAITING_FOR_ACK ||
                waitingWakeToken != wakeToken ||
                currentOwnerToken == null
            ) {
                Log.i(TAG, "Rejecting expired or duplicate wake token")
                return false
            }

            cancelWakeAckTimeoutLocked()
            waitingWakeToken = null
            activeCommandToken = wakeToken
            state = State.COMMAND_ACTIVE
            Log.i(TAG, "Wake token acknowledged; command recognition owns microphone")
            return true
        }
    }

    /** Completes only the currently acknowledged command session. */
    fun endCommandSession(commandToken: String): Boolean {
        var sessionToRun: WakeWordSession? = null

        synchronized(lock) {
            if (
                state != State.COMMAND_ACTIVE ||
                activeCommandToken != commandToken
            ) {
                Log.i(TAG, "Ignoring stale or duplicate command completion")
                return false
            }

            activeCommandToken = null
            if (currentOwnerToken != null && desiredListening) {
                sessionToRun = createSessionLocked(currentOwnerToken!!, latestStartId)
            } else {
                state = State.IDLE
            }
        }

        sessionToRun?.let(::submitSession)
        return true
    }

    /** Invalidates pending wake/command tokens before the UI sends ACTION_STOP. */
    fun prepareExplicitStop() {
        var sessionToCancel: WakeWordSession? = null

        synchronized(lock) {
            desiredListening = false
            pendingStart = false
            activeCommandToken = null
            waitingWakeToken = null
            cancelWakeAckTimeoutLocked()
            stopRequest = null

            activeSession?.let { session ->
                session.cancelled = true
                sessionToCancel = session
            }
            state = State.STOPPING
        }

        sessionToCancel?.let(::scheduleRecorderStop)
    }

    fun markListening(session: WakeWordSession) {
        synchronized(lock) {
            if (activeSession?.sessionToken == session.sessionToken && state == State.STARTING) {
                state = State.LISTENING
            }
        }
    }

    fun markCleaning(session: WakeWordSession) {
        synchronized(lock) {
            if (
                activeSession?.sessionToken == session.sessionToken &&
                state != State.STOPPING
            ) {
                state = State.CLEANING
            }
        }
    }

    fun scheduleRecorderStop(session: WakeWordSession) {
        controlScheduler.execute {
            session.stopRecorderForCancellation()
        }
    }

    fun workerFinished(session: WakeWordSession, wakeDetected: Boolean) {
        var wakeIntent: Intent? = null
        var nextSession: WakeWordSession? = null
        var stopToComplete: StopRequest? = null

        synchronized(lock) {
            if (activeSession?.sessionToken != session.sessionToken) {
                Log.w(TAG, "Ignoring completion from stale session ${session.sessionToken}")
                return
            }

            activeSession = null
            val ownerIsCurrent = currentOwnerToken == session.ownerToken

            when {
                state == State.STOPPING || !ownerIsCurrent -> {
                    val shouldStartForCurrentOwner =
                        pendingStart && currentOwnerToken != null && desiredListening
                    pendingStart = false
                    if (shouldStartForCurrentOwner) {
                        val owner = currentOwnerToken!!
                        nextSession = createSessionLocked(owner, latestStartId)
                    } else {
                        state = State.IDLE
                        stopToComplete = stopRequest
                    }
                }

                wakeDetected && !session.cancelled && desiredListening -> {
                    // Do not restart capture until the activity claims this token.
                    pendingStart = false
                    waitingWakeToken = session.sessionToken
                    state = State.WAITING_FOR_ACK
                    wakeAckTimeout = controlScheduler.schedule(
                        { onWakeAckTimeout(session.sessionToken) },
                        WAKE_ACK_TIMEOUT_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS
                    )
                    wakeIntent = Intent(WakeWordService.ACTION_WAKE_DETECTED)
                        .putExtra(WakeWordService.EXTRA_WAKE_TOKEN, session.sessionToken)
                        .setPackage(session.applicationContext.packageName)
                }

                else -> {
                    state = State.IDLE
                    waitingWakeToken = null
                    if (pendingStart && desiredListening && currentOwnerToken != null) {
                        pendingStart = false
                        nextSession = createSessionLocked(currentOwnerToken!!, latestStartId)
                    }
                }
            }
        }

        // The worker has released AudioRecord, stream, and spotter before this
        // callback. Broadcast delivery therefore cannot overlap mic ownership.
        wakeIntent?.let { intent ->
            Log.i(TAG, "Microphone and model released; sending wake broadcast")
            session.applicationContext.sendBroadcast(intent)
        }

        nextSession?.let(::submitSession)
        stopToComplete?.let(::completeStopAfterCleanup)
    }

    private fun onWakeAckTimeout(wakeToken: String) {
        var sessionToRun: WakeWordSession? = null

        synchronized(lock) {
            if (state != State.WAITING_FOR_ACK || waitingWakeToken != wakeToken) return

            waitingWakeToken = null
            wakeAckTimeout = null
            activeCommandToken = null
            state = State.IDLE
            Log.w(TAG, "Wake acknowledgement timed out; resuming local detection")

            val owner = currentOwnerToken
            if (owner != null && desiredListening) {
                sessionToRun = createSessionLocked(owner, latestStartId)
            }
        }

        sessionToRun?.let(::submitSession)
    }

    private fun createSessionLocked(ownerToken: String, startId: Int): WakeWordSession {
        val context = applicationContext
            ?: throw IllegalStateException("Wake-word coordinator has no application context")
        val session = WakeWordSession(
            ownerToken = ownerToken,
            sessionToken = UUID.randomUUID().toString(),
            startId = startId,
            applicationContext = context
        )
        activeSession = session
        state = State.STARTING
        return session
    }

    private fun submitSession(session: WakeWordSession) {
        try {
            detectionExecutor.execute {
                WakeWordDetection.run(session, this)
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Could not submit wake-word session", exception)
            workerFinished(session, wakeDetected = false)
        }
    }

    private fun completeStopAfterCleanup(request: StopRequest) {
        mainHandler.post {
            val shouldStop = synchronized(lock) {
                currentOwnerToken == request.ownerToken &&
                    stopRequest?.requestToken == request.requestToken &&
                    activeSession == null &&
                    !desiredListening
            }

            if (shouldStop) {
                request.service.get()?.stopSelfResult(request.startId)
            }
        }
    }

    private fun cancelWakeAckTimeoutLocked() {
        wakeAckTimeout?.cancel(false)
        wakeAckTimeout = null
    }
}
