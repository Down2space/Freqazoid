# Validation status — Freqazoid 0.6.0 rebuild

Environment: cloud Linux workspace, OpenJDK 21.0.13, Kotlin compiler 2.0.21 (the project's pinned
version, from a local Gradle 8.14.3 distribution), kotlinx-coroutines-core-jvm 1.6.4 for JVM tests
(the app pins 1.9.0; only APIs present in both are used).

## Completed here

Command: `KOTLIN_LIB=<gradle-8.14.3>/lib tests/run_tests.sh <project-dir>` — exit status 0.

```
== compiling shared pure-Kotlin sources
== MonitorDspTest (baseline)
PASS: single tones, dual tones, silence, DC rejection, clipping at 3 sample rates
== MonitorNoiseTest (baseline)
PASS: raw spectrum retains broadband noise across FFT bins
== HarmonyTest
PASS: 2:1, 3:2, 4:3 curve closure; measured equal-tempered mismatch retained; bounded perspective; silence; explicit single-tone reference; volume response
== PhysicsTest
PASS: Bessel zeros; fixed rim; resonance ratios; radius/tension scaling; off-resonance attenuation; silence; nodal ring geometry; forcing symmetry; dual-tone FFT at 3 sample rates
Default membrane mode bank: 312 modes, 150.00278358883102–5781.350236487187 Hz
== UnifiedAnalyzerTest
PASS: 15 FFT/rate combinations, dual peaks, RMS, Nyquist, silence/DC/clipping, broadband bins, flatness, timestamped history and RGB/log helpers
== ControllerTest
ok 1-2 single owner, subscribers, metadata, stop releases, no stale data
ok 3 churn: max concurrent sources = 1
ok 4 FFT change rebuilds once, rejects unsupported sizes
ok 5 freeze holds frame with mic off; resume is a new session
ok 6 host pause stops; no automatic restart
ok 7 permission denied/retry/granted/revoked; unsupported format error
ok 8 recorder death -> error, released; retry works
ok 9 hardware timestamp validated; implausible -> read-completion fallback
ok 10 TEST mode is synthetic, labeled, and mic-free
PASS: controller ownership, lifecycle, subscribers, churn, FFT rebuild, freeze, pause, permission, errors, timestamps, TEST mode
== MonitorModelTest
PASS: monitor duplicate suppression, peak-preserving pooling, repeated-bin flags, Nyquist clamp, peak hold, color-Δ baseline/formula/both directions, session/layout/range resets, timestamps, bounded ring, noise retained
== HarmonyEngineTest
ok A: 8 labeled synthetic signals x 15 rate/FFT combinations
ok B: closure for 2:1 3:2 4:3, equal-tempered detuning retained
ok C: integrated phase continuity, max phase step 0.1047 rad/frame, max step 0.1276, max 2nd difference 0.0242
ok D: anchor hysteresis (no flicker; switch after 0.33 s)
ok E: stop/silence/stale input -> inactive, zero curves
ok F: amplitude->brightness only; display clock 0.00455 <= 1.5/f; depth spread 0.50; projection bounded
ok G: wide ratio -> partial window, 577 points (36/cycle)
ok H: frozen figure is static
PASS: harmony engine synthetic signals, closure, detuning, continuity, hysteresis, inactive states, amplitude/pitch separation, depth, partial windows, freeze
== SafeAreaTest
diameter 180 dp: top chip  88.2 wide at y= -49.8, start  64.8 wide at y= 55.7, free band  -23.8.. 29.7, text 148.6
diameter 192 dp: top chip  94.1 wide at y= -55.1, start  69.1 wide at y= 61.3, free band  -29.1.. 35.3, text 156.9
diameter 200 dp: top chip  98.0 wide at y= -58.6, start  72.0 wide at y= 65.0, free band  -32.6.. 39.0, text 162.2
diameter 216 dp: top chip 105.8 wide at y= -65.5, start  77.8 wide at y= 72.5, free band  -39.5.. 46.5, text 172.6
diameter 227 dp: top chip 111.2 wide at y= -70.3, start  81.7 wide at y= 77.6, free band  -44.3.. 51.6, text 179.5
diameter 240 dp: top chip 117.6 wide at y= -76.0, start  86.4 wide at y= 83.7, free band  -50.0.. 57.7, text 187.4
diameter 260 dp: top chip 127.4 wide at y= -84.7, start  93.6 wide at y= 93.0, free band  -58.7.. 67.0, text 199.1
diameter 320 dp: top chip 132.0 wide at y=-112.0, start 112.0 wide at y=115.2, free band  -86.0.. 89.2, text 241.2
diameter 360 dp: top chip 132.0 wide at y=-126.0, start 112.0 wide at y=129.6, free band -100.0..103.6, text 269.6
diameter 411 dp: top chip 132.0 wide at y=-143.8, start 112.0 wide at y=148.0, free band -117.8..122.0, text 305.6
PASS: all control corners inside the circle (4 dp margin), >=48 dp targets, 180-411 dp viewports
== ReadoutsTest
PASS: readout wording · TEST/LIVE/FROZEN/SILENCED states, dBFS units, explicit generated reference, ratio + cents, FFT tradeoff, info panel disclosures, no value judgments
== PipelineTest
PASS: pipeline · 1 source for 243 renderer polls across 4 modes; 90 rows from 90 analyses (no duplicate rows); waterfall span 1.90 s matches capture clock; TEST ratio 3:2; stop clears live state (true)
== NoteShapeTest SKIPPED (app/src/main/assets/note-atlas.bin not present)
ALL RUNNABLE TESTS PASSED
```

Coverage of the required regression areas:

- Baseline tests (MonitorDsp, MonitorNoise, Harmony, Physics) and the reference UnifiedAnalyzerTest: unchanged, all pass.
- Shared-recorder ownership/lifecycle: ControllerTest (max concurrent sources = 1 under 30-command churn; stop/freeze/pause/permission/error/FFT-rebuild/TEST).
- Mode switching: PipelineTest (renderer polling across all 4 modes; 1 source opened).
- No stale data after stop: ControllerTest 1–2, 6, 8 and PipelineTest.
- Duplicate-frame suppression: MonitorModelTest and PipelineTest (rows ≤ analyses while polled ~2.7× faster).
- Geometry continuity: HarmonyEngineTest C (phase step bounded by the slowed clock; smooth trajectory).
- Sample rates 16 / 44.1 / 48 kHz × FFT 2048–32768: UnifiedAnalyzerTest and HarmonyEngineTest A (all 8 labeled TEST signals).
- Round layout math: SafeAreaTest (every control corner inside the circle for 180–411 dp).
- On-screen wording/units: ReadoutsTest.

Desktop-JVM timing (reference only, NOT watch evidence): analysis 0.13 ms (FFT 2048) … 2.0 ms
(FFT 32768) per frame against 21–85 ms hop budgets; harmony step+fill ≤ 0.05 ms per frame.

The Android/Compose sources (`AndroidMicSource.kt`, `FreqazoidViewModel.kt`, `MainActivity.kt`,
`FreqazoidApp.kt`, `HarmonyCanvas.kt`, `MonitorCanvas.kt`) were passed through the Kotlin 2.0.21
parser: **no syntax errors**. Their type checking against Android/Compose was NOT possible here (see below).

## NOT completed — must be done before calling the rebuild verified

1. **Android build / APK.** This workspace's network policy blocks dl.google.com, maven.google.com,
   repo1.maven.org, plugins.gradle.org and services.gradle.org (HTTP 403), so neither the Android SDK
   nor AGP/AndroidX/Compose could be downloaded. `assembleDebug` was not run; **no APK was produced**.
   API usage was written against AGP 8.7.3 / Compose BOM 2024.12.01 / activity 1.9.3 / lifecycle 2.8.7
   but has not been compiled against them.
2. **Manifest/APK signature checks** (aapt/apksigner): not run (no APK).
3. **Emulator or device runs**: no emulator or device was available — no phone portrait/landscape or
   round-watch runtime screenshots, no permission denial/retry or leave/return test on Android,
   no rotary test, no real microphone accuracy, latency or frame-rate measurement (30/60 FPS remain targets).
4. **NoteShapeTest** (archived engine) skipped: `app/src/main/assets/note-atlas.bin` is in the
   companion ZIP, which was not supplied with the handoff. Binary resources (logo `app_icon`, note
   drawables, keystore) were likewise not present here; the source assumes they exist as in v0.5.0.

Suggested on-device checklist: build; `adb install -r`; Start → allow; deny → retry → "Don't ask
again" → Allow mic opens Settings; switch all 4 modes while listening (debug readout: sources stay
1, no restart); Freeze/Resume; home → return (mic stays off); FFT 2048 ↔ 32768 while listening;
each TEST signal; rotate phone; rotary zoom/cursor on watch; drag inspection without swipe-dismiss;
enable debug readout and record render fps / analysis rate / age on the Watch6.
