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

The diagnostic logs transcription for these supplied test clips, compares normalized text to the expected phrase, and separately logs that keyword spotting was not run. A transcript match alone is not proof of external PCM consumption: the `MICROPHONE_RECORDING_STATE` samples must show no active capture during the run, and every PCM byte must have been written. Any active recording makes the result evidence of possible microphone fallback and invalidates an external-source pass.
