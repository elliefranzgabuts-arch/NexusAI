# NEXUS external PCM diagnostic

This diagnostic is isolated from the production wake-word pipeline. The app-side activity is compiled only from `app/src/debug/` and does not start `WakeWordService` or load Sherpa-ONNX.

## PCM contract used by this experiment

Android defines `RecognizerIntent.EXTRA_AUDIO_SOURCE` as an already-open `ParcelFileDescriptor`. The adjacent extras describe channel count, `AudioFormat` encoding, and sample rate. The API defaults are mono, PCM16, and 16 kHz; this diagnostic explicitly supplies those values. The NEXUS source recordings are mono 48 kHz MP3, so this tool decodes and resamples copies to raw signed PCM16 little-endian at 16 kHz mono. This is the test's declared input format, not a claim that the installed recognition provider requires or supports it.

The descriptor uses a pipe. The diagnostic writes 100 ms PCM chunks at approximately real-time cadence; closing the pipe's write side signals EOF. It retains and closes its descriptors on terminal callback, timeout, cancellation, or Activity shutdown. Android documents that the caller must close the audio descriptor and that a recognizer which ignores this feature opens its microphone instead. No API query proves this provider consumes the pipe; the recognition and active-recording observations are runtime evidence.

## Convert the supplied recordings

Requires the already-installed `ffmpeg` and `ffprobe` commands. The script refuses to overwrite either source or an existing output.

```sh
python3 frontend/android/pcm-diagnostic/convert_recordings.py
```

The output files are `backend/test-data/pcm-diagnostic/positive.pcm` and `negative.pcm`. They are separate from the MP3 originals.

## Build, install, and run one test at a time

```sh
cd frontend/android
./gradlew --offline assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell mkdir -p /sdcard/Android/data/com.nexusai.app/files/pcm-diagnostic
cd ../..
adb push backend/test-data/pcm-diagnostic/positive.pcm /sdcard/Android/data/com.nexusai.app/files/pcm-diagnostic/positive.pcm
adb push backend/test-data/pcm-diagnostic/negative.pcm /sdcard/Android/data/com.nexusai.app/files/pcm-diagnostic/negative.pcm
adb logcat -c
adb shell am start -n com.nexusai.app/.PcmDiagnosticActivity --es nexus.pcm.diagnostic.sample positive
```

After the positive run logs `SESSION_COMPLETED`, run the negative test:

```sh
adb shell am force-stop com.nexusai.app
adb shell am start -n com.nexusai.app/.PcmDiagnosticActivity --es nexus.pcm.diagnostic.sample negative
```

Collect app events and platform recording diagnostics:

```sh
adb logcat -d -v time -s NEXUS_PCM_DIAGNOSTIC:I AudioRecord:I AudioFlinger:I RecognitionService:W SpeechRecognizer:W
adb shell dumpsys audio
```

## Separate ML Kit GenAI diagnostic (debug build only)

The independent `MlKitPcmDiagnosticActivity` uses Google ML Kit GenAI Speech Recognition Basic mode, locale `en-US`, and `AudioSource.fromPfd(..., AudioSource.Mode.ONE_SHOT)`. It never calls `AudioSource.fromMic()`, Android `SpeechRecognizer`, or the production wake-word service. The dependency and Activity are in debug-only configurations.

The existing `positive.pcm` and `negative.pcm` files are used as-is from the application's external files directory. The documented PFD contract is raw/headerless signed 16-bit PCM, mono, 16 kHz. The ML Kit public docs do not expose a separate `AudioEncoding.PCM_16BIT` parameter for this input; the byte format is the source contract. `AudioSource` owns the read descriptor after `fromPfd`; the diagnostic closes the source after recognition. The diagnostic's auto-close output stream owns the write descriptor and closes it to signal EOF. Bytes written and EOF do not prove that the provider consumed the PCM.

Build and push the already prepared samples:

```sh
cd frontend/android
./gradlew --offline assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell mkdir -p /sdcard/Android/data/com.nexusai.app/files/pcm-diagnostic
cd ../..
adb push backend/test-data/pcm-diagnostic/positive.pcm /sdcard/Android/data/com.nexusai.app/files/pcm-diagnostic/positive.pcm
adb push backend/test-data/pcm-diagnostic/negative.pcm /sdcard/Android/data/com.nexusai.app/files/pcm-diagnostic/negative.pcm
adb shell am start -n com.nexusai.app/.MlKitPcmDiagnosticActivity
```

The Activity checks `checkStatus()` and enables sample buttons only for `AVAILABLE`. It does not call the model download API. `DOWNLOADABLE`, `DOWNLOADING`, and `UNAVAILABLE` are displayed and stop the test. Select exactly one sample per button press. Read diagnostic events with:

```sh
adb logcat -s NEXUS_MLKIT_PCM_DIAGNOSTIC:I
```

The screen and logs show the transcript and transfer/cleanup state. They deliberately label provider consumption as unverified. The harness itself uses a PFD source only, but no device test has independently established how a provider obtains audio internally or ruled out unrelated active recording by other app components.
