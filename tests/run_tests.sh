#!/bin/bash
# JVM tests + preview images (no Android needed). KOTLIN_LIB = a Gradle 8.14 lib/ folder (has Kotlin 2.0.21).
set -eu; P=${1:-$(cd "$(dirname "$0")/.." && pwd)}; L=${KOTLIN_LIB:-/opt/gradle-8.14.3/lib}; O=${TMPDIR:-/tmp}/freqazoid-tests; rm -rf "$O"
CP=$L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$(ls $L/trove4j-*.jar|head -1):$L/kotlin-daemon-embeddable-2.0.21.jar:$(ls $L/annotations-*.jar|head -1):$(ls $L/kotlinx-coroutines-core-jvm-*.jar|head -1)
J=$P/app/src/main/java/com/freqazoid
SRC="$J/Music.kt $J/NoteDetector.kt $J/Synth.kt $J/WaveSlots.kt $J/Harmony.kt $J/Processor.kt $J/Renderer.kt $P/tools/Java2dGfx.kt"
java -Xmx2g -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-reflect -no-stdlib -jvm-target 17 -nowarn -classpath $L/kotlin-stdlib-2.0.21.jar -d $O $SRC $P/tests/EngineTest.kt $P/tools/Preview.kt
java -Djava.awt.headless=true -cp $O:$L/kotlin-stdlib-2.0.21.jar com.freqazoid.EngineTestKt
java -Djava.awt.headless=true -cp $O:$L/kotlin-stdlib-2.0.21.jar com.freqazoid.PreviewKt "${PREVIEW_DIR:-$P/previews}" 450
