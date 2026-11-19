# 0.6.0 behavior changes (from 0.5.0)

- One activity, one retained controller, at most one AudioRecord. Switching modes no longer stops/restarts the mic (0.5.0 used a second activity with its own recorder).
- All four modes use the new shared analyzer; FFT size (2048–32768) is one global setting shown with window length and bin spacing.
- Explicit states: Idle, Requesting permission, Starting, Listening (+ system-silenced / digital-zero warnings), Frozen (mic off), Permission denied (with Settings shortcut), Error (with Retry).
- Capture timestamps: validated hardware AudioTimestamp, else read-completion estimate; shown in Info/debug.
- Analysis hop grows automatically under CPU load instead of overflowing the recorder.
- Sound: tracked components with documented anchor hysteresis; integrated display phase (no resets); smoothed common slow clock shown as "clock ×1/N"; pair cross-fades; global depth bins with occlusion halos; restrained glow (phone quality only); partial view windows for wide ratios; dashed gray, toggleable 110 Hz reference; optional confidence-based harmonic grouping; hue legend; explicit inactive state; frozen figure; reduced detail under load.
- Spectrum/Waterfall/Color Δ: fixed 160 columns (no resize artifacts), peak-preserving pooling, shared coarse bins drawn as one bar, Nyquist clamp, real row timestamps and ages, duplicate frames ignored, resets on rate/FFT/range change, Color Δ zero baseline per capture session, palette legend on screen.
- TEST signals (8, labeled TEST) run through the real analyzer and never open the mic.
- Round layout: controls sized before measurement with all corners inside the circle; ≥ 48 dp targets; rotary zoom/cursor/scroll; system back closes overlays; inspection drags don't trigger swipe-to-dismiss.
- Debug readout: render fps, quality level, rate, FFT, hop, analysis rate, snapshot age, timestamp source, conflated frames.
- versionCode 9 / 0.6.0; same applicationId, minSdk 30, signing config. Added activity-ktx 1.9.3 and lifecycle-viewmodel-ktx 2.8.7.
- Removed MonitorActivity.java and AudioSpectrum.kt.
