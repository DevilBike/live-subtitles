# Live Subtitles

A personal Android app that shows live English subtitles over anything playing
on the phone (videos, streams, calls in a browser). It detects the spoken
language by itself and runs entirely on the device, with no cloud.

**Status:** step 3 of 4. The app captures the phone's playback audio,
recognizes and translates speech to English on the phone with whisper.cpp, and
shows the subtitles on the app screen and in the notification, with live
latency figures. The floating subtitle overlay comes in step 4.

| Step | What | State |
|---|---|---|
| 1 | Project, permissions, foreground service | done |
| 2 | Capture the phone's playback audio (AudioPlaybackCapture) | done |
| 3 | On-device speech recognition and translation | done |
| 4 | Floating subtitle overlay, testing, release APK | next |

## Getting the APK

Every push to `main` builds a debug APK on GitHub Actions. Open the
**Actions** tab, pick the latest **Build APK** run, and download
`live-subtitles-debug-apk` at the bottom. Unzip it and install `app-debug.apk`
on the phone (you will need to allow installs from unknown sources).

To build it yourself, open the folder in Android Studio and press Run, or run
`./gradlew assembleDebug`. The first build also downloads whisper.cpp and
compiles it with the Android NDK, which takes a few minutes.

## Requirements

- Android 10 (API 29) or newer. That is the first version that lets an app
  capture other apps' audio.
- A 64-bit phone with an ARMv8.2 CPU (dot product and fp16 instructions),
  which covers phones from about 2018 on. The app checks this and says so if
  the phone is older; see "Older phones" at the end.
- On first launch the app asks for three things: audio capture (the system
  calls it "microphone", but only playback audio is used), drawing over other
  apps (for the subtitles), and the screen capture prompt each time you start.

## Spoken language

Pick it under **3. Spoken language**. The app is tuned for Japanese and Korean:

- **Japanese or Korean (detected)**, the default, **Japanese**, or **Korean**:
  Whisper writes down what is said in that language, and Google's on-device
  translator (ML Kit) turns each finished sentence into English. Small
  Whisper models are much better at transcribing Japanese and Korean than at
  translating them, and a translator given whole sentences keeps more of the
  meaning than one given 4-second fragments. The unfinished sentence is
  translated too and shown in grey, and the original text is shown in small
  print under the English, so you can tell a hearing mistake from a
  translation mistake. Picking the language directly skips detection.
- **Any language**: Whisper translates to English by itself, for languages
  other than Japanese and Korean. Lower quality.

## Speech model

The first time, pick a model under **4. Speech model** and tap **Download**.
It is a one-time download: whisper.cpp's quantized multilingual Whisper
models and a 1 MB voice activity detection model from Hugging Face into the
app's private storage, plus Google's Japanese and Korean translation models
(about 30 MB each) through ML Kit. This is the only thing the app uses the
internet for; recognition and translation then run offline on the phone.

| Model | Size | Notes |
|---|---|---|
| Tiny | ~44 MB | Fastest, rough translations. For slower phones. |
| Base | ~82 MB | The default. A good start on a mid-range or better phone. |
| Small | ~190 MB | Clearly better translations, roughly 3x slower than Base. |

The models are not bundled in the APK, so the APK stays small and you can
switch models without reinstalling. A model change applies the next time you
tap Start.

## Checking that subtitles work, and how far behind they are

Tap **Start subtitles**, accept the screen capture prompt, play a video with
speech in another app and come back. Subtitles appear under **Subtitles**:
settled text in normal colour and the newest words in grey, which may still
change. The notification shows the latest line too, so you can follow along
from the other app by pulling down the notification shade.

Under the subtitles the app shows the detected language and three latency
figures, averaged over the last 20 passes:

- **Pass**: how long one whisper.cpp run takes. It needs to stay under 500 ms
  (the hop between windows) to keep up. If the app says it is slower than
  real time, pick a smaller model.
- **Text behind its audio**: from the end of the newest audio in a window to
  its text being on screen. This is how far the grey text trails the speech.
- **Settled after speech**: from the end of a spoken word to it turning from
  grey to normal (committed).

The same figures are logged for every pass. With the phone on USB:

```
adb logcat -s LiveSubsLatency
```

prints a line per pass such as
`pass=420ms audio=4000ms speech=2900ms lang=ja->ja committed+3 tentative=4 translate=40ms lag=610ms`.

## Checking that capture works

The **Captured audio** meter moves with the sound, and the line under it says
"Hearing audio" with the level and how many buffer windows have been produced.
If the meter stays empty while something is clearly playing, that app blocks
capture.

## Apps that block capture

Android lets each app decide whether its audio can be captured. Blocked apps
are not muted, they just reach this app as silence.

- **Allowed by default:** any app targeting Android 10 or newer that plays
  media, game or uncategorised audio and has not opted out. That covers most
  browsers (Chrome, Firefox), most video players (VLC, MX Player) and most
  social and video apps.
- **Blocked by the app:** apps that set `allowAudioPlaybackCapture="false"` or
  a "capture by none/system only" policy. This is common for paid streaming
  and DRM apps, for example Netflix, Disney+, Prime Video, and many music
  apps such as Spotify. Their web versions in a browser often work instead.
- **Blocked by Android:** apps built for Android 9 or older, and all call and
  voice chat audio (phone calls, WhatsApp/Meet/Zoom calls), which is never
  capturable by other apps.

App behaviour changes between versions, so treat the named apps as typical
rather than certain. The meter above is the quickest way to check one.

## Code layout

```
app/src/main/java/dev/bike/livesubs/
  MainActivity.kt            permissions, model download, start/stop, live subtitles
  service/CaptureService.kt  foreground service, owns the MediaProjection
  audio/                     PlaybackAudioSource -> 100 ms AudioFrames,
                             SlidingWindowBuffer -> overlapping AudioWindows
  asr/                       WhisperRecognizer -> SubtitleUpdate, with
                             TranscriptStabilizer (committed vs tentative text),
                             LanguageLock, SpeechActivity (VAD), LatencyStats,
                             ModelStore (download), WhisperNative (JNI)
  translate/                 optional separate translation stage
  overlay/                   SubtitleOverlay window (step 4)
app/src/main/cpp/
  CMakeLists.txt             fetches whisper.cpp (pinned tag), builds it with the NDK
  whisper_jni.cpp            thin JNI bridge to whisper.cpp
```

The pipeline is a chain of Kotlin Flows running inside the service:

```
AudioPlaybackCapture -> AudioFrame (100 ms) -> AudioWindow (last 4 s, every 0.5 s)
  -> WhisperRecognizer (VAD, whisper.cpp translate) -> SubtitleUpdate -> screen, notification
```

Audio is captured as 16 kHz mono 16-bit PCM (Android does the resampling and
downmix) and converted to floats, which is Whisper's input format. The
AudioRecord buffer is kept to a few frames so capture adds very little delay.
Windows are conflated before the recognizer: if a pass takes longer than
0.5 s, it gets the newest window next instead of a backlog.

## Latency plan: as close to live as possible

Plain Whisper waits for a whole chunk of audio (often 5 to 30 seconds) and
then transcribes it, so subtitles arrive several seconds late. The design here
aims for subtitles that start appearing about a second after the words are
spoken. These are targets; the app measures the real figures (see above).

1. **Small frames, sliding window.** Audio arrives in 100 ms frames. Every
   ~0.5 s the recognizer re-runs whisper.cpp on the last few seconds of audio
   (a sliding window), so new words show up within roughly one step plus the
   model's run time.
2. **Tentative vs committed text.** Each `SubtitleUpdate` carries `committed`
   text, which never changes, and `tentative` text for the newest audio, which
   may be corrected on the next pass. Words are committed once two passes in a
   row agree on them (the "local agreement" approach used by whisper_streaming),
   when they are about to slide out of the window, or when speech pauses.
   Words a pass repeats from already committed text are recognized by their
   text, or failing that by their timestamps, and dropped. The screen shows
   tentative words immediately in a lighter colour, so the subtitles feel live
   while still settling on correct text.
3. **Transcribe, then translate whole sentences.** For Japanese and Korean,
   Whisper transcribes and ML Kit's on-device translator (tens of
   milliseconds per sentence) translates each sentence once it ends, at
   sentence punctuation or a pause; the unfinished sentence is translated as
   a preview. For other languages, Whisper's `translate` task goes straight
   to English in one pass.
4. **Voice activity detection.** Silero VAD (built into whisper.cpp) checks
   each window first. Windows without speech are skipped, so the model only
   runs when someone is talking. About 0.7 s of silence after speech counts as
   a pause: the sentence is committed and the next window starts after the
   pause, so sentences don't bleed into each other. Segments Whisper itself
   flags as "no speech" are dropped too, which stops it inventing text over
   music.
5. **Detect language once.** Passes detect the language (choosing only
   between Japanese and Korean in the default setting) until two in a row
   with enough speech agree, then the language is pinned, which saves an
   extra decoder run on every later pass. Stop and Start to detect again, or
   pick the language to skip detection.
6. **Small, quantized model, using the phone's fast cores.** Multilingual
   `base` (8-bit) by default, `small` (5-bit) if the phone is fast enough,
   `tiny` (8-bit) for slower phones. whisper.cpp is compiled with the NDK for
   ARMv8.2 (NEON dot product and fp16) and runs one thread per fast core, up
   to 4.
7. **Shrunk encoder.** Whisper's encoder normally processes 30 s of audio
   however short the input is. Each pass sets the encoder context to the
   window's length (about 4 s), which makes the encoder several times faster.
   Temperature fallback is off, since a retry would double the pass time and
   the next pass comes 0.5 s later anyway.

**Expected result:** tentative subtitles roughly 0.5 to 1.5 s behind the
audio, and settled lines about 2 s behind, on a recent mid-range or flagship
phone. Translation quality from small Whisper models is decent for major
languages and weaker for rarer ones.

**Fallback if that is still too slow:** a true streaming recognizer such as
sherpa-onnx's streaming Zipformer models emits words within a few hundred
milliseconds, but each model covers one or two languages and needs a separate
on-device translation step. The `Translator` interface is there so that option
can be added without reworking the pipeline.

## Older phones

The native library is built for ARMv8.2 (Cortex-A55/A75 and newer cores).
Phones whose cores are all Cortex-A53 or older lack those instructions; the
app detects this and shows a message instead of crashing. To build for them,
at roughly half the speed:

```
./gradlew assembleDebug -Pwhisper.armArch=armv8-a
```
