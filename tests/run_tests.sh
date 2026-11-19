#!/bin/bash
# Runs every JVM-runnable Freqazoid test (no Android SDK needed).
# Needs JDK 17+ and Kotlin 2.0.21 jars: set KOTLIN_LIB to a folder containing
# kotlin-compiler-embeddable-2.0.21.jar, kotlin-stdlib-2.0.21.jar, kotlin-reflect-2.0.21.jar,
# kotlin-script-runtime-2.0.21.jar, kotlin-daemon-embeddable-2.0.21.jar, trove4j-*.jar,
# annotations-*.jar and kotlinx-coroutines-core-jvm-*.jar (a Gradle 8.14 distribution's lib/ has them all).
# Usage: KOTLIN_LIB=/path/to/gradle-8.14.3/lib tests/run_tests.sh [project-dir]
set -u; P=${1:-$(cd "$(dirname "$0")/.." && pwd)}; L=${KOTLIN_LIB:-/opt/gradle-8.14.3/lib}
O=${TMPDIR:-/tmp}/freqazoid-tests; rm -rf "$O"; mkdir -p "$O/java"
CO=$(ls $L/kotlinx-coroutines-core-jvm-*.jar | head -1)
CP=$L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$(ls $L/trove4j-*.jar|head -1):$L/kotlin-daemon-embeddable-2.0.21.jar:$(ls $L/annotations-*.jar|head -1):$CO
RT=$L/kotlin-stdlib-2.0.21.jar:$CO
kc(){ out=$1; extra=$2; shift 2; java -Xmx2g -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-reflect -no-stdlib -jvm-target 17 -nowarn -classpath "$RT:$extra" -d "$out" "$@"; }
J=$P/app/src/main/java; A=$J/com/freqazoid/analysis; M=$J/com/freqazoid/membrane; fail=0
javac -d $O/java $J/com/freakazoid/watch/{Spectrum,ColorMath,NoteMath}.java $P/tests/MonitorDspTest.java $P/tests/MonitorNoiseTest.java || exit 1
echo "== compiling shared pure-Kotlin sources"
kc $O/core $O/java $A/UnifiedAudioAnalyzer.kt $A/AudioController.kt $A/SyntheticSignals.kt $A/MonitorModel.kt \
   $M/HarmonyEngine.kt $M/HarmonyGeometry.kt $M/MembranePhysics.kt $M/AudioDsp.kt $M/NoteShapeEngine.kt $M/RoundSafeArea.kt $M/Readouts.kt || exit 1
t(){ name=$1; cls=$2; shift 2; echo "== $name"; kc $O/$name "$O/core:$O/java" "$@" && java -cp "$O/$name:$O/core:$O/java:$RT" $cls || { echo "FAILED: $name"; fail=1; }; }
echo "== MonitorDspTest (baseline)"; java -cp $O/java MonitorDspTest || fail=1
echo "== MonitorNoiseTest (baseline)"; java -cp $O/java MonitorNoiseTest || fail=1
t HarmonyTest com.freqazoid.membrane.HarmonyTestKt $P/tests/HarmonyTest.kt
t PhysicsTest com.freqazoid.membrane.PhysicsTestKt $P/tests/PhysicsTest.kt
t UnifiedAnalyzerTest com.freqazoid.analysis.UnifiedAnalyzerTestKt $P/tests/UnifiedAnalyzerTest.kt
t ControllerTest com.freqazoid.analysis.ControllerTestKt $P/tests/ControllerTest.kt
t MonitorModelTest com.freqazoid.analysis.MonitorModelTestKt $P/tests/MonitorModelTest.kt
t HarmonyEngineTest com.freqazoid.membrane.HarmonyEngineTestKt $P/tests/HarmonyEngineTest.kt
t SafeAreaTest com.freqazoid.membrane.SafeAreaTestKt $P/tests/SafeAreaTest.kt
t ReadoutsTest com.freqazoid.membrane.ReadoutsTestKt $P/tests/ReadoutsTest.kt
t PipelineTest com.freqazoid.membrane.PipelineTestKt $P/tests/PipelineTest.kt
if [ -f "$P/app/src/main/assets/note-atlas.bin" ]; then t NoteShapeTest com.freqazoid.membrane.NoteShapeTestKt $P/tests/NoteShapeTest.kt; java -cp "$O/NoteShapeTest:$O/core:$O/java:$RT" com.freqazoid.membrane.NoteShapeTestKt "$P/app/src/main/assets/note-atlas.bin" || fail=1
else echo "== NoteShapeTest SKIPPED (app/src/main/assets/note-atlas.bin not present)"; fi
[ $fail -eq 0 ] && echo "ALL RUNNABLE TESTS PASSED" || echo "SOME TESTS FAILED"; exit $fail
