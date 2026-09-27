# Rhythm Physics

A native Android app that turns a song into physics. One app, one session and one audio clock
drive four mechanic families:

| Mechanic | What happens | Presets |
|---|---|---|
| **Square** | A square bounces between surfaces that a planner places so every contact lands exactly on a musical event | Classic Planned, MIDI Playground (brick field with the route carved out), Carved Orange, Carved Navy, Stamp Walls, Dark Minimal, Neon Trail |
| **Circle** | Free physics inside a ring: continuous (time-of-impact) collisions, gaps, and 26 anomaly types triggered by beats, downbeats, big events, collisions or escapes | Rainbow Rings and Rainbow Trails (never-cleared paint), Classic Elastic, Growing Ball, Shrinking Ring, Growing Ball / Shrinking Ring, Escape the Gap, Rotating Gap, Multiplication, Gravity Chaos, Melody Collision, Collision Synth, Orbit Force, Ring Break, Chaos, Sandbox |
| **Arch** | A glowing hero flies exact ballistic arcs (`v0 = (p1 − p0 − ½gT²)/T`) onto 3-D targets, framed by a predictive camera director | Bounce Curve (grey studio, teal discs, yellow comet with floor reflection; true ballistic), Pillar Weave (ribbon-lit pillars on a black stage; a *guided* swooping spline, labelled as such) |
| **Platform** | A marble drops down a course planned from the song's events; pads are tilted so each one reflects the marble into its next arc | Music Ball (studio), Neon Platforms, Pastel Glass, Stones, Marble Machine, Piano Tiles, Staircase, Minimal Bars, Block Terrain |

The mechanics can also be combined in one run: **Journey** (auto-segmented by song sections, with
iris/morph transitions), **Scripted Journey** (you choose the mechanic and preset for each segment),
**Duet** (two side by side) and **Quad** (all four). None of these restart the audio.

Rhythm Physics plays the original song. MIDI files are rendered with a bundled SoundFont
(GeneralUser GS), not beeps, and audio files are analysed offline for onsets, beats, tempo,
downbeats, bands, spectral centroid/flux, chroma, key and sections. Everything stays on the
device: files are opened with the system picker (Storage Access Framework), and the app requests
no internet, storage or microphone permission.

---

## Status: what has and hasn't been verified

| Area | How it was verified | Result |
|---|---|---|
| Engine, planners, physics, analysis, synth, replays | 47 JVM tests (`:core:test`) + 5 pure app-logic tests (`:app:test`) | pass |
| Sync (logical contact vs musical event) | `desktop gauntlet`: all 26 presets × 3 aspect ratios on the demo MIDI, plus audio-driven runs | every planned contact 0.000 ms (goal: mean ≤ 5, p95 ≤ 15, max ≤ 30) |
| Determinism | State hash at t = 60 s identical at 60/90/120/144 Hz rendering and after a seek, for Square/Circle/Arch/Platform/Journey/Duet/Quad | pass |
| Functional matrix | Square/Circle/Arch/Platform/Journey × MIDI/audio × 9:16/16:9: play, seek-from-scratch, replay round-trip, sync goal, non-empty frames (`artifacts/test-reports/functional_matrix.json`) | 20/20 cells pass |
| Replays | Export → import → re-simulate for 6 scenes, including sandbox taps | hashes match |
| Audio analysis | Click track; the demo song rendered to audio and analysed blind | 120.08 BPM, onset p95 2.6 ms; 112.14 BPM (truth 112), beats 166/166, downbeats 41/44, onset precision 94 % |
| Android app (activity, screens, dialogs, stores, loader, packaged assets, thread cleanup) | 8 Robolectric tests (`:app:roboTest`) that drive the real `MainActivity` with native Skia graphics | pass; screenshots in `artifacts/screenshots/android/` |
| Android Canvas backend | Every preset rendered through `CanvasRenderer` (Robolectric/Skia) and compared with the Java2D reference renderer | mean difference 1.2/255, worst 4.7/255 (`artifacts/test-reports/backend_parity.csv`) |
| minSdk 26 API safety | `:app:apiCheck`: every framework and `java.*` reference is checked against API 26 and Java 8 signatures | 0 unguarded references (this check found and fixed a real `ByteBuffer` crash on older Android) |
| **On a real device** | **Not done.** No emulator or device was available. | **unverified**: AudioTrack latency, real touch, MediaCodec video export, performance, thermals, haptics |

Treat the APK as untested on hardware until someone runs it. The code paths that only run on
devices are listed under [Known limitations](#known-limitations).

---

## Install

- **Debug APK:** `artifacts/apk/rhythm-physics-debug.apk`. Sideload it (`adb install`, or open the
  file on the phone). It targets Android 8.0 (API 26) through Android 15 (API 35).
- Or build it yourself (see [Building](#building)).

## Using the app

**Home.** *Load audio file* or *Load MIDI file* opens the system file picker. *Demo song* plays
“Orbit Lines”, an original composition in this repo. *Sandbox* is the Circle with no music: tap
inside the ring to add balls. Recent files and your imported presets are listed here too. The app
also handles *Open with…* and *Share* for audio and MIDI files.

**Creator.** The live preview sits on top in portrait and on the left in landscape.
- *Transport:* restart, play/pause, time, seek bar. Seeking is instant and deterministic.
- *Mechanic:* Square · Circle · Arch · Platform · Journey.
- *Layout:* Single · Duet (with a partner picker) · Quad. *Journey:* Auto (song sections) or
  Scripted (an editor for each segment's mechanic and preset; tap a segment's time to jump there).
- *Preset:* built-in and imported presets. In Duet, Quad and Journey, pick which mechanic's preset
  you are editing.
- *Seed* (with a dice button) and *Aspect ratio*: 9:16, 16:9 or 1:1. The scene is recomposed for
  each aspect, not cropped.
- *Clean view* hides all UI (tap or press Back to leave). *Debug* shows the diagnostic overlay:
  clock, sim time, presentation lead, contact error, sync stats, steps/frame, draw calls and bodies.
- Changing any of these rebuilds the simulation in the background at the current song position;
  the music keeps playing.

**Export video.** Choose a resolution matched to the aspect ratio (720×1280, 1080×1920, 1280×720,
1920×1080, 1080×1080; only sizes your device's H.264 encoder supports are offered), 30 or 60 fps,
and the whole song or 15/30/60 s from the playhead. Frames are rendered offline at exact song
times with the same renderer as the preview. They are encoded with MediaCodec (H.264 + AAC) and
muxed with MediaMuxer into a file you pick. The UI and debug overlay are never recorded.

**Settings.**
- *Visual quality:* Low, Medium, High or Ultra. Low and Medium also render at 60 % and 80 % resolution. Quality affects visuals only, never timing or physics, and a hot device is capped automatically (thermal status, API 29+).
- *Accessibility:* reduced flash, reduced motion, disable camera shake, reduced bloom, reduced
  particles.
- *Timing:* an optional visual offset for outputs that misreport latency.
- *Haptics:* Off, Light or Medium. Only strong impacts vibrate, rate-limited.
- *Audio:* Song, Song + collision sounds, or Muted (visuals only).
- *Music events:* mode (Sparse, Beat, Percussive, Melodic, Dense or Hybrid) and density. For MIDI,
  also voice mapping and track selection.
- *SoundFont:* the bundled GeneralUser GS, or import your own `.sf2`.
- *Presets and replays:* export or import them as JSON.
- *Diagnostics:* debug overlay, and export of a sync and performance report.

## Formats

MIDI (`.mid`, `.midi`, RIFF-RMID) is parsed directly. Audio (WAV, MP3, OGG, M4A/AAC, FLAC, and
whatever else the device decodes) goes through `MediaExtractor` + `MediaCodec`. The type is decided
from the file's **bytes**, never its name: an MP3 renamed `.mid` is still treated as audio, and a
MIDI file renamed `.mp3` is still treated as MIDI.

---

## Architecture

```
core/        Pure Kotlin (no Android): the whole engine, shared by the app, desktop tools and tests
  engine/    RhythmEngine (audio clock -> fixed 120 Hz steps -> interpolated render), MechanicDirector
             (Focus/Duet/Quad/Journey slots), Journey planner + transitions, debug overlay
  mechanic/  square/ circle/ arch/ platform/ — planner + mechanic per family
  music/     normalized MusicEvent schema, MIDI normalization, event mapping/thinning, MIDI sections
  audio/     streaming analyzer (STFT flux onsets, tempo, DP beat tracking, downbeats, sections,
             key), analysis cache keyed by fingerprint + analyzer version + settings, WAV I/O
  synth/     SoundFont 2 parser + synthesizer (generators/modulators, envelopes, LFOs, filter, reverb)
  render/    DrawList (virtual 1080-based viewport), cameras, CPU 3-D projection, stateless particles
  preset/    versioned JSON presets, validation/clamping, built-ins
  session/   RhythmSession, SceneConfig, replay recipes
app/         Android framework layer: AudioTrack clock, decoder, realtime synth, SurfaceView render
             loop, Canvas backend, video exporter, UI (Home/Creator/dialogs), stores, haptics, thermals
desktop/     JVM tools: Java2D reference renderer, screenshot sheets, gauntlet, backend parity renders
buildtools/  APK pipeline helpers: lambda desugarer, zip aligner, apksig signing, minSdk API check
```

**Timing model.** The audio clock is the authority. On device it is `AudioTrack.getTimestamp()`,
smoothed and never allowed to run backwards. The simulation advances in fixed 1/120 s steps, where
step *n* is exactly time *n*/120. Rendering interpolates between steps, so the display rate (60, 90,
120 Hz) can never change the outcome. Each live frame renders the song position at the moment that
frame will actually be **presented**. On API 33+ this comes from the FrameTimeline; below that it is
estimated from the vsync period. The visuals therefore line up with what you hear, not with when
the frame started drawing. All randomness comes from named, seeded SplitMix64 streams (per impact,
where planners need it). Checkpoints every second make seeking a restore plus a deterministic
fast-forward.

**Planned sync.** Square, Arch and Platform solve their courses ahead of time from the event
stream, so each contact happens exactly at its event. Every contact is still *measured* from the
simulated trajectory, and the measurements are logged: that is what the sync numbers above report.
Circle is free physics: the music drives anomalies and colours, and collisions are real collisions,
not planned contacts.

## Building

This project deliberately builds **without the Android Gradle Plugin**. The environment it was
developed in could not reach `dl.google.com` (Google's Maven repository and SDK host). So the app
uses only the Android framework plus Kotlin, kotlinx-coroutines and kotlinx-serialization, all
from Maven Central, and packages itself with a small custom pipeline:

`kotlinc` (against the API 35 `android-all` jar) → ASM lambda desugaring → `dx` → `aapt2` (from
apktool's prebuilt) → zip alignment → `apksig` (v2 signature) → `:app:apiCheck` (minSdk 26).

Requirements: JDK 17+ (developed with 21) and internet access to Maven Central and npmjs.org. The
SoundFont is fetched from the npm `generaluser` package and checksum-verified.

```bash
./gradlew :app:assembleDebugApk      # app/build/outputs/apk/debug/rhythm-physics-debug.apk
./gradlew :app:assembleReleaseApk    # unsigned release APK, plus a signed copy when these are set:
    # -Prp.release.storeFile=... -Prp.release.storePassword=... -Prp.release.keyAlias=... -Prp.release.keyPassword=...
./gradlew :core:test :app:test       # JVM tests
./gradlew :app:roboTest              # Robolectric app tests + Android Canvas renders
./gradlew :app:apiCheck              # minSdk API safety report
./gradlew :desktop:installDist
desktop/build/install/desktop/bin/desktop gauntlet artifacts build/soundfont/GeneralUser-GS.sf2
desktop/build/install/desktop/bin/desktop sheet square.classic 9x16 20 0.5 12 out.png
python3 tools/compare_backends.py artifacts/screenshots/android-canvas <java2d-dir> out.csv
```

If `dl.google.com` is reachable in your environment, you can move `app/` to the standard AGP
build. Nothing in the code depends on the custom pipeline.

**Renaming.** The product name lives in `core/.../Branding.kt` and `app/src/main/res/values/strings.xml`.

## Repository map

- `references/manifest.json`: every reference link, with status and notes. The videos are not
  reachable from the build environment, but the user supplied still frames for 25 of them; those
  are marked `OBSERVED_FROM_USER_FRAMES`, with what the frames show and which preset rebuilds
  the look (several were miscategorized in the brief and are reclassified there). The other 29 remain
  `INACCESSIBLE`. Nothing was copied: every look is rebuilt with original code and assets.
- Tests: `core/src/test` (engine and audio), `app/src/test` (pure app logic), `app/src/roboTest`
  (Robolectric app tests). The latter includes a small test-only `androidx.test` shim, because
  `androidx.test:monitor` is published only to Google Maven; see its README.
- `artifacts/`:
  - `screenshots/`: Java2D reference sheets per mechanic, Android UI captures (`android/`) and
    Android Canvas renders of every preset (`android-canvas/`).
  - `sync/`: sync matrices.
  - `perf/`: host JVM performance. This is not a device measurement.
  - `replays/`: example recipes and their verification.
  - `test-reports/`: gauntlet summary (`GAUNTLET.md`), determinism, audio analysis, backend parity
    and API check.
  - `audio/`: a demo excerpt rendered with the SoundFont.
  - `apk/`: the debug APK.

## Known limitations

- **No on-device testing.** These paths have only been compiled, API-checked and (where Robolectric
  allows) exercised, never run on hardware:
  - MediaCodec/MediaMuxer video export;
  - real AudioTrack timestamps and output latency;
  - the low-latency collision-sound track;
  - haptics, the thermal throttling cap, and frame pacing at 90/120 Hz;
  - performance on real GPUs.
  Expect to tune these on a device.
- The **Piano Tiles** preset at 16:9 fills less of the frame (≈ 22–24 % coverage) than the other
  presets.
- No R8/ProGuard shrinking (it ships with the Android Gradle Plugin). The APK is about 30 MB,
  nearly all of it the bundled 31 MB SoundFont, which barely compresses.
- Looks were matched to still frames the user supplied, not to the videos, so motion details
  (camera timing, trail dynamics) are approximations. The two negative screenshots described in the brief
  never arrived. Their failure modes (tiny hero, dead canvas, cage, compressed Arch course,
  overwhelming trail) are covered by composition tests rather than image comparisons.
- Real-world audio testing used the demo song rendered through the SoundFont. Downloading
  CC0/public-domain recordings was blocked by the environment's network policy.

## Licenses

The code, the demo composition “Orbit Lines” and all visuals are original to this project.
**GeneralUser GS v1.471** is © S. Christian Collins and is distributed under its own license,
included in the APK as `assets/soundfonts/GeneralUser-LICENSE.txt`.
