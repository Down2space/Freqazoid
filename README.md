# Freqazoid 1.0 — Chromatic Resonance for Galaxy Watch6

One round watch face (the same face shows on a phone for testing).

- **Wheel:** seven rings = octaves C1–B7, twelve color-coded notes per ring. Each sounding note is drawn as
  its own real sound wave bent around a small circle (isolated with band-pass filters at the note and its
  first overtones; every note shows the same 20 ms, so higher notes show more waves). Lines link the
  chord's notes, colored and labeled by interval; a white arc shows how far a note is out of tune.
- **Overlay:** the notes' waves layered on one circle, plus the white combined sound.
- **Rim:** consonance gauge across the top (red harsh → teal very smooth), beat pulse around the edge, and
  the chord name, smoothness word and beat speed in the middle.

**Controls:** first screen = Live mic or Song on watch · tap = Wheel ⇄ Overlay · Stop = back to the first
screen. Tuning is standard A4 = 440 Hz. Leaving the app stops sound; nothing restarts on its own.

Detection: harmonic-series note detector from the web version, its drum filter (pitch must hold steady),
plus a recency gate (a note must still sound in the newest 85 ms) and a noise gate (spectral flatness).

Build: every push to `main` builds on GitHub Actions; the APK is at
`releases/download/latest/Freqazoid.apk`. Tests + preview images: `tests/run_tests.sh`.
