# Freqazoid 0.6.0 — shared analysis rebuild (Sound · Spectrum · Waterfall · Color Δ)

One activity, one retained ViewModel, **one AudioRecord at a time**. All four modes read the same
analysis snapshots; switching modes never opens another recorder or restarts capture. Tap the
top chip (**FREQAZOID · Sound ▾**) for the mode picker, settings, TEST signals and Info.

| Mode | Behavior |
| --- | --- |
| Sound visualizer | 3D laser curves from measured frequency ratios, depth-sorted with perspective. |
| Spectrum | Full raw FFT (all bins, noise included), log axis, dBFS, white peak hold, compact waterfall. |
| Waterfall | Timestamped spectral history, newest at top, black→blue→cyan→yellow→red→white. |
| Color Δ | sqrt(ΔR²+ΔG²+ΔB²) between consecutive palette colors per frequency column, as %. |

## Architecture

- `com/freqazoid/analysis/UnifiedAudioAnalyzer.kt` — integrated reference analyzer: Hann window,
  DC removed for the spectrum (RMS from raw PCM), normalized single-sided FFT, DC…Nyquist bins kept,
  interpolated peaks as a separate derived list, clipping, flatness, centroid. Snapshots carry
  sample rate, FFT size, bin spacing, window duration, window-end time, timestamp source,
  sequence and session. FFT sizes 2048–32768; one global setting governs every mode.
- `analysis/AudioController.kt` — the single capture owner (adapted from
  `reference-improvements/SharedAudioController.kt`). Commands are serialized; every restart
  cancels **and joins** the previous job, whose `finally` releases its recorder first.
  States: Idle, RequestingPermission, Starting, Listening (incl. system-silenced / digital-zero
  flags), Frozen (mic off, last frame kept), PermissionDenied, Error. Analysis runs on
  `Dispatchers.IO`; the hop grows automatically if analysis can’t keep up (never overflowing the
  recorder). `StateFlow` conflation: renderers draw the latest snapshot; no backlog.
- Timestamps: the window end is the hardware `AudioTimestamp` (CLOCK_MONOTONIC) extrapolated to
  the last sample when it is plausible (≤ 5 ms in the future, < 500 ms old); otherwise the
  read-completion time. Render time is never used as capture time. Source shown in Info/debug.
- `analysis/AndroidMicSource.kt` — 48 → 44.1 → 16 kHz mono PCM16; `UNPROCESSED` source when the
  device declares support, else `VOICE_RECOGNITION`.
- `analysis/SyntheticSignals.kt` — labeled TEST inputs (single 440, octave 220/440, fifth 220/330,
  fourth 300/400, detuned fifth 220/331.5, unequal chord, guitar-like 110 Hz + harmonics,
  broadband noise) fed through the same analyzer. TEST never opens the microphone; every
  screen says TEST.
- `analysis/MonitorModel.kt` — Spectrum/Waterfall/Color Δ state from raw `spectrumDbfs`:
  fixed 160 columns (resizing can’t change data), peak-preserving max pooling, shared-bin flags
  (drawn as one wide bar, not fake detail), Nyquist clamp, peak hold, 96-row timestamped ring,
  duplicate/older frames ignored, resets on rate/FFT/range change, Color Δ zero baseline per
  capture session.
- `membrane/HarmonyEngine.kt` — Sound mode tracking + geometry (pure Kotlin, tested).
- `membrane/HarmonyCanvas.kt`, `MonitorCanvas.kt`, `FreqazoidApp.kt`, `MainActivity.kt`,
  `FreqazoidViewModel.kt`, `Readouts.kt`, `RoundSafeArea.kt` — UI.
- Retained for provenance, not active: `NoteShapeEngine.kt` / `CymaticsLaserCanvas.kt` (note
  chart), `MembranePhysics.kt` (Bessel membrane), `HarmonyGeometry.scene()` (v0.5 scene),
  `com/freakazoid/watch/Spectrum.java` (old monitor FFT; its tests still run), `PatternAtlas.java`.
- **Removed:** `MonitorActivity.java` and `AudioSpectrum.kt` — each owned its own AudioRecord.

## Sound visualizer rules

For each pair (f_low, f_high) of measured components:

    x = A_x sin(2π f_low s + φ_low)
    y = A_y cos(2π f_high s + φ_high)
    z = 0.42 sin((2π f_high s + φ_high) − (2π f_low s + φ_low))

over s ∈ [0, q / f_low], where p:q is the nearest small fraction. p:q is a **label and view
window only** — measured frequencies are never snapped, so exact 2:1, 3:2, 4:3 close and
tempered/detuned ratios stay open and drift. Very wide ratios use a shorter partial window
(bounded points per cycle) rather than drawing aliased scribble.

- **Anchor** = strongest stable component. It changes only when another is ≥ 3 dB stronger for
  ≥ 0.30 s, or the anchor is missing for 0.15 s. Dropouts < 100 ms hold; missing components fade
  (τ 0.12 s) and are removed after 0.5 s. Pairs cross-fade (τ 0.15 s) on anchor changes.
- **Phase** = generated display oscillators, integrated φ += 2π f k dt; never reset on frequency
  updates. Not measured acoustic phase. **k** is one common slowed display clock (fastest drawn
  component ≤ 1.5 cycles/s), shown as “clock ×1/N” beside the legend; ratios are preserved.
- **Amplitude** (digital energy) → brightness/extent only; it never changes frequency.
- **Depth**: optional slow yaw (0.035 rev/s, only while active) + fixed tilt + perspective.
  Segments go into 8 global depth bins drawn far→near across all curves, with dark occlusion
  halos and near = brighter/thicker; shade also uses the Euclidean radius √(x²+y²+z²) (the
  Pythagorean theorem in space — unrelated to Pythagorean tuning ratios). Glow: one restrained
  pass, near half only, phone quality only. Paths are built once per frame off the main thread
  (triple-buffered); glow passes reuse them.
- **Hue** = frequency (log, 30 Hz red … 20 kHz violet) with a visible legend. Text sits on a dark
  scrim, independent of line color.
- **One component**: compared with an explicit, toggleable **110 Hz visual reference** — drawn
  gray and dashed, labeled “REFERENCE (generated)”. With it off, a 1:1 self figure is drawn.
- **Harmonic grouping** (optional, off by default): the fundamental must be detected; ≥ 2
  harmonics within 20 cents, or one harmonic ≥ 3 dB weaker. Raw spectra are never changed.
- Readouts: strongest frequency, optional note + cents, dBFS, ratio p:q and error in cents.
  No ratio is called happy/sad/good/bad; small equal-temperament deviations are explained as normal.
- Silence/stop/stale input (> 0.6 s) settle to an explicit inactive state; the frame loop stops.
- Load: sustained frames > 1.5× budget switch to reduced decorative detail; measurement is untouched.

## Layout

Square viewport = min(width, height), circular clip. On round screens the controls are a mode
chip (top) and Start/Stop (bottom), each ≥ 48 dp tall, sized **before measurement** by
`RoundSafeArea` so every corner is inside the circle (verified 180–411 dp). Readouts and plots use
the band between them. Rectangular phones with spare room put controls beside/below the circle.
Rotary: zoom (Sound) / move the inspection cursor (monitors); menus scroll by rotary. System back
closes overlays. Debug readout (menu): render fps, quality level, sample rate, FFT, hop, analysis
rate, snapshot age + window, timestamp source, conflated snapshots, analysis ms, hop increases.

## Lifecycle policy

Mic opens only after an explicit Start (and permission). Leaving RESUMED stops it; returning
never restarts it. Permission granted while not resumed starts on the next resume (the user’s
tap). Permanently denied → “Allow mic” opens app Settings. Freeze = mic off with the frame held
and labeled. Rotation is handled in place (`configChanges`), and a real recreation keeps the
retained controller. Changing FFT size rebuilds capture once; monitor history resets.

## Build / update

Copy this tree over the v0.5.0 project (it keeps `app/src/main/res`, `assets/note-atlas.bin`,
`debug-signing/` and the logo). **Delete** `app/src/main/java/com/freakazoid/watch/MonitorActivity.java`
and `app/src/main/java/com/freqazoid/membrane/AudioSpectrum.kt` if present. Then:

    python3 build.py        # or Android Studio, JDK 17+, SDK 35
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Pinned: Gradle 8.9, AGP 8.7.3, Kotlin/Compose compiler 2.0.21, Compose BOM 2024.12.01. Added only
`activity-ktx:1.9.3` and `lifecycle-viewmodel-ktx:2.8.7` (same families as pinned deps).
versionCode 9 / 0.6.0, applicationId `com.freakazoid.watch`, minSdk 30. The bundled keystore
(android / androiddebugkey) is a public development key, not a production key.

No Android Studio? See GITHUB_BUILD.md — GitHub Actions builds the APK in the cloud.

JVM tests (no Android needed): `tests/run_tests.sh <project-dir>` — see VALIDATION.md.
