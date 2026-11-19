# Tested reference improvements for Claude to integrate

**Integrated in 0.6.0:** the analyzer now lives at app/src/main/java/com/freqazoid/analysis/UnifiedAudioAnalyzer.kt and the controller was adapted into analysis/AudioController.kt + AndroidMicSource.kt. These originals are kept unchanged for provenance.

UnifiedAudioAnalyzer.kt provides one immutable-by-ownership snapshot containing complete DC-to-Nyquist FFT levels, separate selected peaks, digital RMS, clipping, spectral flatness, centroid, and sample-window timestamps. It reuses FFT twiddle/window tables and scratch arrays. SpectrumHistory provides bounded timestamped history with duplicate-frame suppression and clearing on FFT-layout changes.

Use one worker per analyzer instance. Never mutate published snapshot arrays. All monitor modes must consume spectrumDbfs directly; visualizer peak selection must not prune that array. The RMS and dBFS values are uncalibrated digital measurements. Peak interpolation does not create additional independent FFT resolution. SpectrumHistory copies incoming spectra so later producer mutations cannot corrupt history. UI code must treat returned history rows as read-only.

Compile/run its standalone tests with Kotlin 2.0.21 and Java 17+:

    kotlinc UnifiedAudioAnalyzer.kt UnifiedAnalyzerTest.kt -include-runtime -d analyzer-tests.jar
    java -jar analyzer-tests.jar

Actual Android recording ownership, lifecycle, permission handling, UI integration, performance benchmarking and release validation remain Claude's implementation tasks, specified in CLAUDE_REBUILD.md at the project root.

