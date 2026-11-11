# NEXUS Android Development Report

Updated: 2026-11-04

## Current project state

- Android application: `frontend/android`, Kotlin, Jetpack Compose, application ID `com.nexusai.app`.
- The app is a small standalone Android project. No backend, desktop app, AI client, or settings storage is present in this checkout.
- Git branch is `main`, tracking `origin/main`.
- Existing user changes at the start of this work were in `WakeWordCoordinator.kt` and `WakeWordDetection.kt`, plus five untracked backup files. Those changes and backups have been preserved.

## Architecture inspected

- `WakeWordService` is a started foreground service with the `microphone` service type. It creates an ongoing notification and requests sticky restart behavior.
- `WakeWordCoordinator` serializes microphone sessions across service instances. It manages owner and session tokens, wake acknowledgment, command ownership, stop cleanup, and a single inference executor. The existing workspace changes add worker exit reasons and bounded exponential recovery retries.
- `WakeWordDetection` loads Sherpa-ONNX 2.0.1 from the packaged AAR and the local encoder, decoder, joiner, tokens, BPE model, and keyword file. It records mono 16 kHz PCM with `AudioRecord`, streams samples to keyword spotting, and releases audio/model resources before signaling a wake event. The existing workspace changes add termination diagnostics.
- `MainActivity` owns the Compose UI and a token-scoped Android `SpeechRecognizer`. It requests on-device recognition only and tears down the recognizer before returning microphone ownership to wake detection.
- `LocalCommandEngine` handles exact-match English local commands: time, opening Settings, opening the Transsion calculator, greetings, and basic identity/help prompts.
- The UI is a single black animated-ring screen with a tap-to-start/stop control and basic status text.
- Model assets and the Sherpa AAR are included in the Android project. There are no API keys or network integration.

## Working features evidenced by source

- Local "Nexus" keyword spotting with Sherpa-ONNX and packaged model files.
- Foreground microphone service and ongoing low-priority notification.
- Token-checked microphone handoff from keyword detection to speech recognition and back.
- On-device speech recognition when the installed recognition service reports availability.
- Basic English local commands.
- Workspace retry changes attempt to restart the wake worker after unexpected termination with bounded backoff.

These are source-level capabilities, not verified physical-device behavior.

## Incomplete features

- Spoken acknowledgment and spoken responses; the current command result is displayed as text only. There is no TextToSpeech integration.
- General AI conversation, follow-up context, cloud connectivity, or secure backend integration.
- Filipino/Tagalog/Taglish recognition or response selection. Current recognition requests prefer offline and do not select or test language support.
- Voice selection, preview, rate/pitch settings, language settings, and memory controls.
- Persistent user preferences and optional memory.
- Expanded local commands and clear network/offline indicators.
- Service status synchronization with the UI, detailed permission recovery guidance, and battery-optimization guidance.
- Automatic post-reboot activation. There is no boot receiver, and Android restrictions prevent starting a microphone foreground service from `BOOT_COMPLETED` for this target level.
- Automated tests and physical-device validation.

## Reliability findings

- The coordinator already prevents overlapping worker sessions and uses token checks to ignore stale wake/recognizer callbacks.
- `AudioRecord.read` treats negative results as session failure; positive reads feed the decoder. Zero-length reads currently have no explicit stall handling or diagnostic counter.
- Existing workspace edits add recovery for unexpected worker exits, but there are no device logs to establish whether inactivity, audio read errors, inference latency, speech-recognizer cleanup, or OEM process management caused the reported intermittent issue.
- The UI's `isListening` flag reflects a request, not confirmation from the service that the microphone worker is recording.
- Notification permission was only requested along with microphone permission. If microphone access had already been granted, activation skipped the Android 13+ notification prompt. This turn fixes that path.
- `SpeechRecognizer` is on-device only; availability and Filipino/English language packs depend on the phone's installed recognition provider.

## Android and device constraints

- Microphone foreground services need `RECORD_AUDIO`, the `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE` declarations, and must be started while the app is visible under Android 14 while-in-use rules. The project declares these permissions and the service type.
- Starting this microphone service from a boot receiver is disallowed for apps targeting Android 14 or later. After reboot, the user must open NEXUS and start listening again. Doze, vendor battery management, force-stop, revoked permission, and microphone use by other apps can still interrupt operation.
- A foreground service is not a guarantee that an OEM device will preserve the process indefinitely. Infinix Hot 50 background behavior requires physical testing and, if needed, user-configured battery settings.
- A five-minute screen-off idle test on the connected Infinix confirmed the foreground service and active unsilenced microphone persisted. Spoken recognition while locked, app removal from Recents, offline behavior, and USB-disconnected operation remain untested.

## Work completed this turn

- Fixed the Android 13+ notification permission path in `MainActivity.kt`: when microphone permission already exists but notification permission does not, activation asks for notification permission and then proceeds to start listening. Denying notification permission does not block microphone use.
- Preserved all pre-existing uncommitted source changes and backups.

## Verification

- `git diff --check`: passed.
- `toolbox run --container nexus-dev ./gradlew :app:assembleDebug :app:testDebugUnitTest`: passed. The unit-test task reports `NO-SOURCE`; the project has no test source files.
- Debug APK: `frontend/android/app/build/outputs/apk/debug/app-debug.apk`; built and installed on the connected Infinix.
- ADB identified the phone as `Infinix_X6882`. Five-minute screen-off service/microphone idle check passed as described below.
- The locked-screen spoken recognition flow remains untested; no result/error callback evidence is available from a real command attempt.

## Prioritized continuation

1. Capture the instrumented locked-screen spoken command and classify the missing callback stage before changing the recognition architecture.
2. If the logs isolate service startup, missing audio, or a missing result callback, repair that path and rerun repeated locked-screen conversations on the Infinix.
3. Review and verify the existing wake-worker retry changes. Add zero-read/stall handling only if device logs support that failure path; avoid changing the model or thresholds without evidence.
4. Verify app-minimized operation, longer idle, pocket use, microphone interruption, timeout recovery, and USB-disconnected operation on the Infinix.
5. Add local TTS and voice settings with graceful installed-voice fallback; validate English and Filipino voices on the actual phone.
6. Add language-aware recognition and Filipino/Taglish support based on the installed provider's measured capabilities.
7. Design an opt-in secure remote AI path with explicit internet, privacy, and cost behavior; never place API secrets in the APK.
8. Add privacy-controlled preferences/memory and improve status UI after the voice and service lifecycle is verified.

## Locked-screen command-timeout investigation (2026-11-04)

### Exact timeout source

The UI text `COMMAND TIMEOUT` was emitted only by the app's own `COMMAND_TIMEOUT = 10_000L` watchdog in `MainActivity`. Android `SpeechRecognizer` errors follow a separate `onError()` path and are mapped to messages such as `NO COMMAND DETECTED` or `NETWORK ERROR`. Thus the reported text establishes that no result/error callback completed the command before NEXUS's watchdog fired. It does not, by itself, establish whether no audio reached the recognizer, the recognition service stalled, or the user spoke after the timeout.

### Source lifecycle findings

- Sherpa detection exits on the wake, then `finally` closes the `AudioRecord`, stream, and spotter before `WakeWordCoordinator.workerFinished()` broadcasts the wake token. The coordinator accepts the token before the Activity starts recognition. This source path does not overlap the wake worker and `SpeechRecognizer`.
- The Activity creates the on-device recognizer only when `isOnDeviceRecognitionAvailable()` is true, registers the listener before `startListening()`, requests free-form speech with `EXTRA_PREFER_OFFLINE`, and has token checks against stale callbacks.
- Screen lock does not dispose the Activity composition by itself. There is no source evidence that lock state automatically cancels the recognizer.
- Android's availability query reports whether an on-device recognition service is available; it does not prove a language model is installed or that a callback arrives in the locked-screen scenario. The service-specific result must be measured on-device.
- The previous app watchdog was one 10-second timer that started before the recognizer reported readiness, so recognizer initialization consumed the user's speaking time. It is now four bounded stage deadlines: 10 seconds for recognizer readiness, 10 seconds to begin speaking after readiness, up to 30 seconds for speech capture, and 10 seconds for a final result after speech ends.
- Diagnostic logs include monotonic elapsed time, command token, interactive/keyguard state, lifecycle events, and recognition error code. Transcript contents are no longer logged.

### Device and build evidence

- ADB identified the connected device as `Infinix_X6882` (Hot 50).
- The device reports `RECORD_AUDIO` and `POST_NOTIFICATIONS` granted and two speech recognition services. Service enumeration alone does not prove which recognizer handles an on-device request or which language data is installed.
- After installing and activating the updated debug APK, Android reported `WakeWordService` foreground with service type `microphone`. The log showed the wake worker starting at 16 kHz with a 6,400-byte buffer and recording state 3.
- Debug APK build in `nexus-dev`: successful after adding diagnostic logs; APK at `frontend/android/app/build/outputs/apk/debug/app-debug.apk`.
- The locked-screen spoken command has not yet been performed in this session. Therefore recognition startup/readiness/audio reception and callback behavior while locked remain unverified. No root cause beyond the exact app-watchdog source is claimed.
- A five-minute screen-off idle check was performed on the connected phone. `dumpsys power` reported `mWakefulness=Asleep`; `dumpsys activity services` still showed the NEXUS microphone foreground service; `dumpsys audio` showed NEXUS's 16 kHz recording configuration active and `silenced:false`. This validates microphone service survival while asleep, not speech-to-text success.
- The rebuilt APK was installed and launched on the Infinix. No lock-screen speech was generated by the developer, so the recognition/callback path still needs the user's spoken test.

### Changes for this investigation

- `MainActivity.kt`: add monotonic lifecycle logs with command token, screen interactive/keyguard state, on-device recognizer availability, start/readiness/speech/result/error callbacks, and timeout phase. Remove speech transcript logging. Replace the premature one-shot 10-second watchdog with bounded, callback-driven stage deadlines.
- `WakeWordCoordinator.kt`: add explicit events for handoff grant, command completion, wake acknowledgment watchdog, broadcast-after-release, and wake session creation.
- `WakeWordDetection.kt`: log the monotonic point at which the audio recorder lease is released.
- `WakeWordService.kt`: log service creation, start commands, foreground promotion, and destruction with monotonic timestamps and owner token.
- `LocalCommandEngine.kt`: stop logging normalized command text; retain a content-free processing event.
- No wake phrase, ONNX model, score, threshold, permission, or microphone ownership behavior was changed.
- The latest diagnostic APK was installed. The phone was asleep/locked during installation; the Activity launch did not start the microphone service, and no background start was attempted. Unlock the phone and activate NEXUS visibly before the next test.

### Diagnostic test on Infinix

1. Keep the updated APK installed, open NEXUS, tap the center control, and confirm the ongoing NEXUS notification appears.
2. On the development computer, start:

   ```sh
   adb logcat -v threadtime NEXUS-MainActivity:I NEXUS-WakeWord:I NEXUS-WakeWordService:I NEXUS-AUDIO:D RecognitionService:V SpeechRecognizer:V '*:S' > nexus-lock-test.log
   ```

3. Lock the phone, leave the display off for five minutes, then say “Nexus” followed immediately by “what time is it.” Keep the screen locked during the request.
4. After the reply or timeout, stop logcat with Ctrl+C and capture the relevant output. For a saved log, redirect the command to a file on the computer.
5. Look for `WAKE_TOKEN_CLAIMED`, `MICROPHONE_RELEASED`, `MICROPHONE_HANDOFF_GRANTED`, `ON_DEVICE_RECOGNIZER_AVAILABILITY`, `START_LISTENING_RETURNED`, `RECOGNIZER_READY`, `SPEECH_BEGIN`, `RECOGNIZER_ERROR`, `RECOGNIZER_RESULTS`, and `APP_STAGE_WATCHDOG_TIMEOUT`. The `stage` field identifies `waiting_for_recognizer_ready`, `waiting_for_speech_start`, `capturing_speech`, or `waiting_for_result`.

If `RECOGNIZER_READY` is missing, investigate service startup/binding. If it appears without `SPEECH_BEGIN`, investigate microphone availability/audio capture after handoff. If speech begins but neither results nor an error arrives, investigate recognizer service completion and the app watchdog. Preserve the error code and timestamps; do not include transcripts.
