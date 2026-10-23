# Live Subtitles

A personal Android app that shows live English subtitles over anything playing
on the phone (videos, streams, calls in a browser). It detects the spoken
language by itself and runs entirely on the device, with no cloud.

**Status:** step 2 of 4. The app captures the phone's playback audio and shows
a live level meter, so you can check capture works. Nothing is transcribed yet.

| Step | What | State |
|---|---|---|
| 1 | Project, permissions, foreground service | done |
| 2 | Capture the phone's playback audio (AudioPlaybackCapture) | done |
| 3 | On-device speech recognition and translation | next |
| 4 | Floating subtitle overlay, testing, release APK | |

## Getting the APK

Every push to `main` builds a debug APK on GitHub Actions. Open the
**Actions** tab, pick the latest **Build APK** run, and download
`live-subtitles-debug-apk` at the bottom. Unzip it and install `app-debug.apk`
on the phone (you will need to allow installs from unknown sources).

To build it yourself, open the folder in Android Studio and press Run, or run
`./gradlew assembleDebug`.

## Requirements

- Android 10 (API 29) or newer. That is the first version that lets an app
  capture other apps' audio.
- On first launch the app asks for three things: audio capture (the system
  calls it "microphone", but only playback audio is used), drawing over other
  apps (for the subtitles), and the screen capture prompt each time you start.

## Checking that capture works

Tap **Start subtitles**, accept the screen capture prompt, then play a video in
another app and come back. The **Captured audio** meter moves with the sound,
and the line under it says "Hearing audio" with the level and how many buffer
windows have been produced. The notification also switches between "Hearing
audio" and "No sound yet". If the meter stays empty while something is
clearly playing, that app blocks capture.

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
  MainActivity.kt            permission walkthrough, start/stop
  service/CaptureService.kt  foreground service, owns the MediaProjection
  audio/                     PlaybackAudioSource -> 100 ms AudioFrames,
                             SlidingWindowBuffer -> overlapping AudioWindows
  asr/                       StreamingRecognizer -> SubtitleUpdate (step 3)
  translate/                 optional separate translation stage
  overlay/                   SubtitleOverlay window (step 4)
```

The pipeline is a chain of Kotlin Flows running inside the service:

```
AudioPlaybackCapture -> AudioFrame (100 ms) -> AudioWindow (last 4 s, every 0.5 s)
  -> StreamingRecognizer -> SubtitleUpdate -> overlay
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
spoken. These are targets to measure in step 3, not promises.

1. **Small frames, sliding window.** Audio arrives in 100 ms frames. Every
   ~0.5 s the recognizer re-runs whisper.cpp on the last few seconds of audio
   (a sliding window), so new words show up within roughly one step plus the
   model's run time.
2. **Tentative vs committed text.** Each `SubtitleUpdate` carries `committed`
   text, which never changes, and `tentative` text for the newest audio, which
   may be corrected on the next pass. Words are committed once two passes in a
   row agree on them (the "local agreement" approach used by whisper_streaming).
   The overlay shows tentative words immediately in a lighter style, so the
   subtitles feel live while still settling on correct text.
3. **One model does everything.** Whisper's `translate` task detects the
   language, transcribes and translates to English in one pass, which avoids
   the extra delay of a separate translation model.
4. **Voice activity detection.** Silence and music-only stretches are skipped,
   so the model only runs when someone is talking and the window resets
   cleanly between sentences.
5. **Detect language once.** The language is detected on the first speech and
   then pinned, which saves work on every later pass. It re-detects if the
   output stops making sense or the user taps a button.
6. **Small, quantized model, using the phone's fast cores.** Start with a
   multilingual `base` model in 5-bit or 8-bit form via whisper.cpp (built
   with the NDK, ARM NEON), and move to `small` if the phone is fast enough.
   `tiny` is the fallback for older phones, at a cost in translation quality.

**Expected result:** tentative subtitles roughly 0.5 to 1.5 s behind the
audio, and settled lines about 2 s behind, on a recent mid-range or flagship
phone. Translation quality from small Whisper models is decent for major
languages and weaker for rarer ones.

**Fallback if that is still too slow:** a true streaming recognizer such as
sherpa-onnx's streaming Zipformer models emits words within a few hundred
milliseconds, but each model covers one or two languages and needs a separate
on-device translation step. The `Translator` interface is there so that option
can be added without reworking the pipeline.
