# Freqazoid 0.4.0 — Reference-Note Laser Morphs

Live microphone audio controls laser-drawn shapes calibrated to the user's supplied note chart. An in-tune, isolated detected note settles into its chart anchor; detuning moves the line vertices between nearby anchors; multiple note components produce a loudness-weighted mixture. Broadband noise adds a bounded, measured deformation. This version defines a visual musical notation grammar based on the chart, rather than presenting those chart symbols as universal physical membrane modes.

## The reference shapes

All 56 natural-note cells C1–B8, plus the final C9 endpoint, are extracted from the supplied chart. Repeated C cells between rows represent the same note and do not introduce duplicate note IDs. Sharps/flats are not provided in the image: their anchor shapes are derived halfway between adjacent natural-note anchors where those naturals are two semitones apart. Those derived symbols are not claimed to be reference drawings.

`tools/build_note_atlas.py` thresholds and skeletonizes each chart cell, extracts a vector graph, removes redundant diagonal triangle edges, smooths ordinary stroke vertices, and computes a minimum-squared-distance vertex assignment between every adjacent natural-note graph. Padding duplicates existing vertices so graphs have equal cardinality; it does not add random points. The runtime asset contains 57 graphs with 761 matched vertices each. All rendering uses vector line segments, not a displayed copy of the photograph. The original low-resolution photograph limits geometric precision; these are traced approximations of its strokes.

## The explicit shape rules

### 1. Continuous pitch

    p = 69 + 12 log2(f / 440)

This is continuous MIDI pitch in equal temperament. A4 is 440 Hz. A 4-cent dead band near an integer pitch stabilizes the shape against tiny estimation errors. Otherwise pitch is not snapped to a note. The atlas covers C1–C9 with a half-semitone boundary tolerance; tones beyond that are not assigned misleading octave-folded symbols.

### 2. Moving laser vertices

Between chart anchors a and b, at fraction t=(p-a)/(b-a):

    Q_i(t) = (1-t) P_a,i + t P_b,pi(i)

The assignment pi minimizes the sum of squared Euclidean distances between the anchor vertices. These are actual moving vertex positions. Source-graph connections fade by (1-t); destination connections fade by t, allowing rings, intersections and branches to appear or disappear. At each natural-note endpoint only its reference graph remains. Sharps/flats settle into the corresponding derived intermediate graph. A detuned note therefore has an intermediate geometry, rather than just a differently colored copy of a fixed symbol.

### 3. Harmonics and clear instruments

The FFT retains up to 32 strong peaks. With sufficiently tonal input, detected peaks near integer multiples 2–8 of an observed fundamental are grouped within 22 cents. Their powers are combined, and partials refine the fundamental estimate. This helps a clear harmonic-rich guitar note retain a recognizable anchor.

Grouping requires an observed fundamental and rejects extremely weak low peaks claiming unrelated stronger peaks. It is conservative, not a general polyphonic transcription or source-separation model. A real octave note can be indistinguishable from another note's second harmonic in a mono spectrum, so octave chords and missing fundamentals remain ambiguous. Overtones are a normal part of instrument tone, not an indication of bad tuning.

When measured spectral flatness exceeds 0.15, harmonic grouping is bypassed and the strongest eight measured peaks remain independent. This preserves a more complex mixture for broadband public audio rather than aggressively treating unrelated sounds as one instrument.

### 4. Loudness-weighted mixture

For each tracked note group k:

    E_k = A_k^2
    w_k = (E_k / sum(E))^0.65 * (1 - exp(-8 sqrt(sum(E))))

The first term makes stronger components more prominent while retaining quieter contributors; the second dims the whole display as signal level falls. Source and destination connection weights then multiply w_k by (1-t) and t. Up to eight note tracks are displayed. This estimates the digital energy of frequency groups, not the physical loudness of separately identified people or instruments.

Pitch is smoothed in log-frequency space with a 45 ms time constant. Amplitude has a 45 ms attack and 160 ms release. Missing tracks fade and are removed after 700 ms. The explicit Stop control clears the display. Once a steady note settles, its unchanged input does not cause random line movement.

### 5. Noise and instability

Spectral flatness is geometric mean spectral power / arithmetic mean spectral power. Values under 0.05 cause no noise warp. Above that, a deterministic sinusoidal displacement field bends the vertices by at most 0.035 normalized-radius units, with phase tied to measured spectral centroid. Combined with the separate note components, this makes broadband, changing public audio form a busy, changing mixture. It does not manufacture arbitrary perfect note shapes from silence.

### 6. Colors

Pitch class progresses around a 12-color hue wheel, with the same hue in every octave. Continuous detuning also changes hue continuously. Octaves retain their separate chart geometries. The line brightness follows the weighted digital energy; glow and the moving scan head are rendering effects following those calculated lines. Colors are a chosen notation key, not intrinsic colors of sound.

## Files

- `NoteShapeEngine.kt`: atlas loader, continuous-pitch interpolation, optimal-transport vertex morphing, harmonic grouping, note tracking and energy weights.
- `AudioDsp.kt`: 16,384-point Hann FFT, DC removal, peak interpolation, dBFS, spectral flatness and centroid.
- `AudioSpectrum.kt`: microphone Flow, 2048-sample analysis hops, release on cancellation.
- `CymaticsLaserCanvas.kt`: centered square Compose Canvas, circular clipping and laser rendering. Shape updates are capped at 15 Hz; scan-head rendering targets 60 Hz.
- `MainActivity.kt`: microphone permission, Start/Stop, note/cents/dBFS readout and an explicitly labeled synthetic A4 test tone.
- `app/src/main/assets/note-atlas.bin`: vector chart geometry and optimal assignments.
- `tools/reference-chart.jpg`: unmodified input reference.
- `tools/build_note_atlas.py`: reproducible atlas construction using Python, NumPy, SciPy and Pillow.
- `tests/NoteShapeTest.kt`: reference endpoints, morphing, mixtures, harmonic grouping, tuning accuracy and noise tests.
- `tests/NoteShapePreview.kt`: generates the included synthetic-input preview from the same runtime engine.
- `MembranePhysics.kt` / `PHYSICS_ENGINE.md`: retained Bessel membrane engine and its documentation. It is not the default renderer in this version; the reference-note grammar is.

## Kotlin API

```kotlin
val frames = MutableStateFlow(AudioFrame())
frames.value = AudioFrame(
    tones = listOf(Tone(440.0, 0.10), Tone(523.251, 0.06)),
    rmsDbfs = -22.0,
    timestampNanos = System.nanoTime(),
    noiseLevel = 0.0,
    centroidHz = 470.0
)
CymaticsLaserCanvas(audio = frames, active = foreground)
```

`active` must be false when the host is not RESUMED. Tone amplitude is linear peak amplitude [0,1]. Frequency is Hz. noiseLevel is measured spectral flatness [0,1], not a subjective noise label. Stale timestamps clear the renderer after 600 ms. The included activity stops the mic on pause and does not restart it without user action. No audio is saved or uploaded.

## Build / update

Open this folder in Android Studio, or set JAVA_HOME to JDK 17+ and ANDROID_HOME to an Android SDK containing platform/build-tools 35, then run:

    python3 build.py

The script downloads verified Gradle 8.9 and builds the pinned AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.12.01 project. First build requires the official Maven and Gradle repositories. Output: `app/build/outputs/apk/debug/app-debug.apk`.

The APK uses versionCode 7 / versionName 0.4.0, retains application ID `com.freakazoid.watch` and the original development signing key, and can update the previous test app:

    adb install -r Freqazoid-WearOS.apk

For a watch use its paired wireless ADB endpoint with `adb -s WATCH_IP:PORT install -r Freqazoid-WearOS.apk`. The included signing key is a public development key (password android, alias androiddebugkey), not a private release key.

## Verified and unverified

- Kotlin/Compose compilation and APK signing verification completed.
- Numerical tests passed for all 57 natural chart endpoints, moving vertices with detuning, harmonic grouping, independent-note mixtures, relative amplitude weighting, silence, pure-note tuning and harmonic-rich signals at 16/44.1/48 kHz, and broadband spectral-flatness detection.
- The preview uses synthetic tone inputs and the actual vector-morph engine. It is not a screenshot of a running watch or microphone recording.
- The 16,384-sample window has approximately 341 ms acquisition duration at 48 kHz, before analysis and smoothing; it improves low-note resolution but adds latency. FFT spacing is sampleRate/16384. Real microphones, noise, overlapping partials, vibrato and transients can prevent exact pitch estimates.
- 60 FPS is a target, not a measured Watch6 result. Physical watch performance, microphone behavior and UI fit remain unverified in this environment.
- Stable pitch, rich timbre, loudness and background noise are separate properties. An in-tune noisy or polyphonic signal can still look mixed; a quiet, pure but detuned tone can look clean while showing an intermediate shape. This visualizer is not a universal judgment of whether music is good or bad.
