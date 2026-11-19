# Freqazoid 0.3.0 — Circular Membrane Laser Engine

Standalone Kotlin + Jetpack Compose Android project for phones and circular Wear OS screens. Live microphone FFT peaks drive a modeled circular membrane, whose calculated nodal contours are rendered as luminous laser-like lines. The subtle surface layer displays its oscillating displacement. There is a clearly labeled test-tone control for checking the renderer without microphone input.

## Model

For normalized radial coordinate r in [0,1]:

    phi_nm(r,theta) = J_n(alpha_nm r) cos(n(theta - theta_drive))
    Z_nm(r,theta,t) = phi_nm(r,theta) cos(2 pi f_nm t)
    f_nm = alpha_nm / (2 pi R) sqrt(T / sigma)
    f_nm / f_01 = alpha_nm / alpha_01

J_n is the Bessel function of the first kind. alpha_nm is its m-th positive boundary zero. n gives n nodal diameters; m gives m-1 internal concentric rings for a single pure eigenmode. The outer rim is fixed at zero. The implementation calculates J_n numerically from its integer-order integral, brackets roots, and bisects them; it does not select stored drawings or musical-note symbols.

The default modeled membrane has radius R=0.10 m, tension T=384 N/m, areal density sigma=0.25 kg/m², Q=35, and an off-center point exciter at normalized radius 0.37 and angle 0.20 radians. These are **virtual membrane parameters**, not measurements of the watch, the room, a plate or an instrument. Fundamental resonance is approximately 150.003 Hz. Adjust the parameters in `MembraneConfig` to model another membrane.

For each detected tone f, each mode is weighted by a damped forced response:

    H_nm(f) proportional to (phi_nm(x_drive) / norm_nm)
                            / (f_nm² - f² + i f_nm f / Q)

A common f_01² factor sets dimensionless drive units. The input tone's FFT amplitude controls the drive strength. No per-frame normalization forces off-resonant tones to appear as strongly resonant. Radius, tension, mass density, source location and damping therefore change which shapes appear. A fixed visual exposure then compresses brightness without changing the eigenfunctions.

The bank has 312 modes (n=0..12, m=1..24), ending near 5.781 kHz with the defaults. Up to four FFT peaks are retained; the three strongest modal coefficients per peak are superposed. Frequencies above the bank are not snapped to a lower mode. Increase the modeled frequency range by changing the physical parameters or expanding the bank within the documented numerical limits (n<=24, m<=32). This is a finite modal approximation, not a complete continuum solution or a transient PDE solver.

## What the lasers show

For one pure eigenmode, the laser trace lies on its stationary nodal lines. For a driven tone exciting several damped modes, the complex response can have spatially different phases. In that case there may be **no complete stationary nodal network**. The renderer explicitly takes a phase slice aligned to the strongest modal response and traces its zero contours with marching squares. Each independent input tone has its own color layer; the overlaid layers are not asserted to be the stationary nodes of their total mixed-frequency vibration.

The subtle surface uses the actual complex response at time t. Its **display clock is slowed** by one common factor so the highest displayed carrier cycles no faster than 8 Hz. This prevents pretending that a 60 Hz screen can directly resolve kilohertz oscillation. The core `surface(seconds)` and `displacement(mode,x,y,seconds)` functions accept physical seconds without slowing. The laser scan head and glow are display effects following calculated contours.

Hue is a chosen logarithmic frequency legend (30 Hz red toward 6 kHz violet); brightness follows modeled response strength. Colors are not intrinsic optical properties of sound. There are no frequency-to-image lookups. Microphone peaks can be harmonics or noise and do not identify speech, instruments, animals or independent sound sources.

This is the tensioned circular **membrane/drumhead** equation. A rigid Chladni plate's bending equation, water surface waves, viscosity and real sand-grain dynamics are different physical systems. This implementation visualizes the specified membrane model; microphone audio alone does not reveal another object's geometry or material.

## Files and API

- `MembranePhysics.kt`: pure Kotlin Bessel functions, root finder, physical parameters, mode bank, forced response, grid cache, displacement calculation and marching squares.
- `AudioDsp.kt`: pure Kotlin 8192-point Hann-windowed FFT, DC rejection, peak interpolation and RMS level.
- `AudioSpectrum.kt`: foreground microphone Flow with 1024-sample hops and cleanup on cancellation.
- `CymaticsLaserCanvas.kt`: centered square Compose Canvas, circular clipping, colored contours, glow and slowed surface.
- `MainActivity.kt`: runnable phone/watch host, microphone permission, stop/start and explicit test tone.
- `tests/PhysicsTest.kt`: numerical regression tests.
- `tests/Preview.kt`: produces an SVG from the same calculated response contours.

To supply another frequency detector instead of the included microphone analyzer:

```kotlin
val frames = MutableStateFlow(AudioFrame())
frames.value = AudioFrame(
    tones = listOf(Tone(hz = 440.0, amplitude = 0.08)),
    rmsDbfs = -25.0,
    timestampNanos = System.nanoTime()
)
// In a bounded Compose parent; active must be false when the host is not RESUMED:
CymaticsLaserCanvas(audio = frames, active = foreground)
```

Tone amplitude is linear digital peak amplitude in [0,1], not a dB value. The timestamp is essential; the renderer clears stale data after 600 ms. Use `microphoneFrames()` only with RECORD_AUDIO permission and while the host is RESUMED. The supplied activity stops capture on pause and does not automatically restart it when returning. No audio is saved, sent or collected by a background service.

## Build and install

Open this folder in Android Studio with JDK 17+ and Android SDK 35. Pinned versions: Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin/Compose compiler 2.0.21, Compose BOM 2024.12.01. Set ANDROID_HOME and JAVA_HOME, then run:

    python3 build.py

This downloads Gradle 8.9 once, checks its published SHA-256 and runs assembleDebug. Internet access to the official Gradle and Maven repositories is needed on the first build. The output is `app/build/outputs/apk/debug/app-debug.apk`.

The supplied APK keeps application ID `com.freakazoid.watch`, uses versionCode 6 / versionName 0.3.0 and is signed with the same development certificate as prior Freqazoid test APKs. Install as an update:

    adb install -r Freqazoid-WearOS.apk

For the watch, first pair/connect its wireless debugging endpoint and use `adb -s WATCH_IP:PORT install -r Freqazoid-WearOS.apk`. The included `debug-signing/freakazoid-debug.jks` is a public test key (password android, alias androiddebugkey), not a private production signing key.

This standalone host focuses on the new laser engine. It does not include the previous chart-symbol display, spectrum/waterfall settings UI or history. The earlier Java source remains separately reproducible from its prior version.

## Validation and performance

- Android/Kotlin/Compose APK compilation and APK signature verification passed.
- Pure Kotlin numerical tests passed: known Bessel zeros; zero boundary displacement; mode ratios; radius and tension scaling; resonance attenuation; zero drive; calculated nodal-ring geometry; central-exciter symmetry; dual-tone FFT peaks at 16/44.1/48 kHz.
- The preview image is generated from the response solver's contours. It is not a screenshot or proof of on-device rendering.
- A 60 FPS display target is requested through the Compose frame clock. Mode/basis construction and field work run on Dispatchers.Default. Contours update at most 15 Hz; the surface at most 30 Hz; glow/scan-head rendering targets 60 Hz. Radial Bessel tables and a 24-entry grid LRU avoid calculating special functions per Canvas stroke.
- **60 FPS has not been benchmarked on a Galaxy Watch6**, and this APK has not been run on a physical watch in the authoring environment. Actual frame rate depends on CPU/GPU, mode changes, thermals and battery. The Bessel model is built off the UI thread; an initial blank field while it builds is expected.
- The 129x129 numerical field is interpolated into vector contours; it is not a pixel-art bitmap. The faint surface image uses bilinear sampling. Very high modes require a finer field to avoid under-resolved nodal lines.
- The default microphone FFT spacing is sampleRate/8192 (5.86 Hz at 48 kHz). Close tones, low notes, transients and microphone processing limit accuracy. dBFS is uncalibrated digital level, not sound-pressure dB SPL.

To run the independent numerical tests using an installed Kotlin compiler:

    kotlinc app/src/main/java/com/freqazoid/membrane/MembranePhysics.kt app/src/main/java/com/freqazoid/membrane/AudioDsp.kt tests/PhysicsTest.kt -include-runtime -d physics-tests.jar
    java -jar physics-tests.jar

## Technical references

- NIST DLMF, Bessel zeros: https://dlmf.nist.gov/10.21
- NIST DLMF, Bessel recurrences and derivatives: https://dlmf.nist.gov/10.6
- NIST DLMF, integral representations: https://dlmf.nist.gov/10.9
- Android Compose Canvas: https://developer.android.com/develop/ui/compose/graphics/draw/overview
- AudioRecord: https://developer.android.com/reference/android/media/AudioRecord
