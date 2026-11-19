package com.freqazoid.analysis

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * A PCM input (microphone or synthetic test). Exactly one is open at a time per [AudioController].
 * Implementations: AndroidMicSource (AudioRecord) and SyntheticPcmSource (TEST mode, no microphone).
 */
interface PcmSource : AutoCloseable {
    val sampleRate: Int
    val label: String
    val isMicrophone: Boolean
    fun start()
    /** Blocking read of mono 16-bit PCM. Returns >0 samples read, or a negative platform error code. */
    fun read(buffer: ShortArray, offset: Int, length: Int): Int
    /** Monotonic-clock time of frame [framePosition] (frames since start), if the source can measure it. */
    fun frameTimeNanos(framePosition: Long): Long? = null
    /** True when the platform reports this client as silenced (call, privacy toggle, other app). */
    fun isSilenced(): Boolean = false
    /** Must release the underlying recorder. Called exactly once, on the capture worker. */
    override fun close()
}

fun interface PcmSourceOpener { fun open(settings: AnalysisSettings): PcmSource }

class AudioReadException(val code: Int) : Exception("Microphone read failed (code $code)")

sealed interface CaptureStatus {
    data object Idle : CaptureStatus
    /** The UI has asked the system for RECORD_AUDIO; nothing is open. */
    data object RequestingPermission : CaptureStatus
    data class Starting(val label: String, val test: Boolean) : CaptureStatus
    data class Listening(
        val sampleRate: Int, val label: String, val test: Boolean,
        /** Platform reports this recorder silenced (call, mic privacy toggle, another app). */
        val silenced: Boolean = false,
        /** Input has been exact digital zero for > 2 s (privacy toggle on some devices). */
        val digitalSilence: Boolean = false
    ) : CaptureStatus
    /** Display frozen; the microphone is OFF. Last snapshot is retained and labeled. */
    data class Frozen(val sampleRate: Int, val test: Boolean) : CaptureStatus
    data class PermissionDenied(val canAskAgain: Boolean) : CaptureStatus
    data class Error(val message: String) : CaptureStatus
}

val CaptureStatus.isCapturing get() = this is CaptureStatus.Starting || this is CaptureStatus.Listening

data class CaptureStats(
    val sourcesOpened: Int = 0,
    val analyses: Long = 0,
    val hopSize: Int = 0,
    val hopIncreases: Int = 0,
    val lastAnalysisMillis: Double = 0.0,
    val timestampSource: TimestampSource? = null,
    val readFailures: Int = 0
)

/**
 * The single owner of audio capture. All modes subscribe to [frames]; switching views never opens
 * another recorder. Commands are serialized through one channel, so repeated taps cannot race:
 * every (re)start first cancels the previous capture job and JOINS it, and that job's `finally`
 * releases its source before the next source is opened.
 *
 * [frames] is a conflated StateFlow: the UI always draws the latest available snapshot; skipped
 * snapshots are counted by sequence gaps rather than queued.
 */
class AudioController(
    private val scope: CoroutineScope,
    private val microphone: PcmSourceOpener,
    private val hasPermission: () -> Boolean,
    initialSettings: AnalysisSettings = AnalysisSettings(),
    private val testOpener: (TestSignal) -> PcmSourceOpener = { signal -> PcmSourceOpener { SyntheticPcmSource(signal) } },
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::nanoTime
) {
    private sealed interface Command {
        data object Start : Command
        data class StartTest(val signal: TestSignal) : Command
        data object Stop : Command
        data object Freeze : Command
        data object HostPaused : Command
        data class SetFft(val size: Int) : Command
        data object PermissionRequested : Command
        data class PermissionResult(val granted: Boolean, val canAskAgain: Boolean) : Command
        data class Barrier(val done: CompletableDeferred<Unit>) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutableFrames = MutableStateFlow<AnalysisSnapshot?>(null)
    private val mutableStatus = MutableStateFlow<CaptureStatus>(CaptureStatus.Idle)
    private val mutableSettings = MutableStateFlow(initialSettings)
    private val mutableStats = MutableStateFlow(CaptureStats())
    private val mutableTest = MutableStateFlow<TestSignal?>(null)
    val frames: StateFlow<AnalysisSnapshot?> = mutableFrames.asStateFlow()
    val status: StateFlow<CaptureStatus> = mutableStatus.asStateFlow()
    val settings: StateFlow<AnalysisSettings> = mutableSettings.asStateFlow()
    val stats: StateFlow<CaptureStats> = mutableStats.asStateFlow()
    /** Non-null while TEST mode is the active source. */
    val activeTest: StateFlow<TestSignal?> = mutableTest.asStateFlow()

    /** Number of currently open sources. Invariant: never exceeds 1. */
    val openSources = AtomicInteger(0)
    /** Highest simultaneous open count ever observed (regression evidence for single ownership). */
    val maxConcurrentSources = AtomicInteger(0)

    private var job: Job? = null
    private var session = 0L
    private var sequence = 0L
    private var lastOpener: PcmSourceOpener? = null
    private var lastTest: TestSignal? = null

    init { scope.launch { for (c in commands) handle(c) } }

    /** Explicit user action. Permission must already be granted (host requests it first). */
    fun start() { commands.trySend(Command.Start) }
    fun startTest(signal: TestSignal) { commands.trySend(Command.StartTest(signal)) }
    fun stop() { commands.trySend(Command.Stop) }
    /** Stops the microphone but keeps the last snapshot on screen, labeled FROZEN. */
    fun freeze() { commands.trySend(Command.Freeze) }
    /** Host left RESUMED: stop capturing. Never restarts on its own. */
    fun onHostPaused() { commands.trySend(Command.HostPaused) }
    fun setFftSize(size: Int) { commands.trySend(Command.SetFft(size)) }
    fun onPermissionRequested() { commands.trySend(Command.PermissionRequested) }
    fun onPermissionResult(granted: Boolean, canAskAgain: Boolean) { commands.trySend(Command.PermissionResult(granted, canAskAgain)) }
    /** Suspends until every command sent before this call has been fully handled. */
    suspend fun sync() { val d = CompletableDeferred<Unit>(); commands.send(Command.Barrier(d)); d.await() }

    private suspend fun handle(c: Command) {
        when (c) {
            Command.Start -> {
                // Repeated Start taps while the microphone is already starting/listening are ignored.
                if (mutableStatus.value.isCapturing && lastTest == null && job?.isActive == true) return
                if (!hasPermission()) { stopCapture(false); mutableStatus.value = CaptureStatus.PermissionDenied(true); return }
                stopCapture(false)
                launchCapture(microphone, null)
            }
            is Command.StartTest -> { stopCapture(false); launchCapture(testOpener(c.signal), c.signal) }
            Command.Stop -> { stopCapture(false); mutableStatus.value = CaptureStatus.Idle }
            Command.HostPaused -> {
                val s = mutableStatus.value
                if (s.isCapturing) { stopCapture(false); mutableStatus.value = CaptureStatus.Idle }
            }
            Command.Freeze -> {
                val s = mutableStatus.value
                if (s is CaptureStatus.Listening && mutableFrames.value != null) {
                    stopCapture(true)
                    mutableStatus.value = CaptureStatus.Frozen(s.sampleRate, s.test)
                }
            }
            is Command.SetFft -> {
                if (c.size !in AnalysisSettings.FFT_SIZES || c.size == mutableSettings.value.fftSize) return
                val wasCapturing = mutableStatus.value.isCapturing
                val opener = lastOpener; val test = lastTest
                mutableSettings.value = mutableSettings.value.copy(fftSize = c.size)
                if (wasCapturing && opener != null) {
                    // Incompatible layout: release the old source/analyzer, then rebuild.
                    stopCapture(false); launchCapture(opener, test)
                } else {
                    stopCapture(false)
                    if (mutableStatus.value is CaptureStatus.Frozen) mutableStatus.value = CaptureStatus.Idle
                }
            }
            Command.PermissionRequested -> if (!mutableStatus.value.isCapturing) mutableStatus.value = CaptureStatus.RequestingPermission
            is Command.PermissionResult -> {
                if (c.granted) handle(Command.Start)
                else { stopCapture(false); mutableStatus.value = CaptureStatus.PermissionDenied(c.canAskAgain) }
            }
            is Command.Barrier -> c.done.complete(Unit)
        }
    }

    private suspend fun stopCapture(keepFrame: Boolean) {
        job?.cancelAndJoin()   // the job's finally{} has released its source when this returns
        job = null
        if (!keepFrame) mutableFrames.value = null
        mutableTest.value = null
    }

    private fun launchCapture(opener: PcmSourceOpener, test: TestSignal?) {
        val mySession = ++session
        val settings = mutableSettings.value
        lastOpener = opener; lastTest = test
        mutableTest.value = test
        mutableStatus.value = CaptureStatus.Starting(test?.label ?: "microphone", test != null)
        job = scope.launch(workerDispatcher) { runCapture(opener, settings, mySession, test) }
    }

    private suspend fun runCapture(opener: PcmSourceOpener, settings: AnalysisSettings, mySession: Long, test: TestSignal?) {
        var source: PcmSource? = null
        var counted = false
        try {
            source = opener.open(settings)
            val now = openSources.incrementAndGet(); counted = true
            maxConcurrentSources.accumulateAndGet(now) { a, b -> max(a, b) }
            mutableStats.value = mutableStats.value.copy(sourcesOpened = mutableStats.value.sourcesOpened + 1)
            captureLoop(source, settings, mySession, test)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            fail(mySession, CaptureStatus.PermissionDenied(true))
        } catch (e: AudioReadException) {
            mutableStats.value = mutableStats.value.copy(readFailures = mutableStats.value.readFailures + 1)
            fail(mySession, CaptureStatus.Error(readErrorText(e.code)))
        } catch (e: Exception) {
            fail(mySession, CaptureStatus.Error(e.message ?: "Audio capture failed"))
        } finally {
            try { source?.close() } finally { if (counted) openSources.decrementAndGet() }
        }
    }

    private fun fail(mySession: Long, status: CaptureStatus) {
        if (mySession != session) return
        mutableFrames.value = null
        mutableStatus.value = status
        mutableTest.value = null
    }

    private suspend fun captureLoop(source: PcmSource, settings: AnalysisSettings, mySession: Long, test: TestSignal?) {
        val n = settings.fftSize
        val rate = source.sampleRate
        require(rate in 8000..192000) { "Unsupported sample rate $rate" }
        val analyzer = UnifiedAudioAnalyzer(settings)
        val ring = ShortArray(n); val window = ShortArray(n); val block = ShortArray(512)
        val defaultHop = settings.defaultHop
        var hop = defaultHop
        var head = 0; var filled = 0; var sinceAnalysis = 0
        var totalFrames = 0L; var lastEnd = 0L; var lastSilenceCheck = 0L
        var zeroRun = 0L; var silenced = false; var digitalSilence = false
        var fastStreak = 0
        source.start()
        val label = source.label
        mutableStatus.value = CaptureStatus.Listening(rate, label, test != null)
        mutableStats.value = mutableStats.value.copy(hopSize = hop)
        val ctx = currentCoroutineContext()
        while (true) {
            ctx.ensureActive()
            val count = source.read(block, 0, block.size)
            if (count <= 0) throw AudioReadException(count)
            ctx.ensureActive()
            var allZero = true
            for (i in 0 until count) if (block[i].toInt() != 0) { allZero = false; break }
            zeroRun = if (allZero) zeroRun + count else 0
            val first = min(count, ring.size - head)
            System.arraycopy(block, 0, ring, head, first)
            if (first < count) System.arraycopy(block, first, ring, 0, count - first)
            head = (head + count) % ring.size; filled = min(ring.size, filled + count); sinceAnalysis += count
            totalFrames += count
            if (totalFrames - lastSilenceCheck >= rate / 2) {
                lastSilenceCheck = totalFrames
                val s = source.isSilenced(); val d = zeroRun > 2L * rate
                if (s != silenced || d != digitalSilence) {
                    silenced = s; digitalSilence = d
                    if (mySession == session) mutableStatus.value = CaptureStatus.Listening(rate, label, test != null, s, d)
                }
            }
            if (filled == ring.size && sinceAnalysis >= hop) {
                val readDone = clock()
                // Window end = time of the newest sample. Prefer the hardware timestamp, validated
                // against the monotonic clock; otherwise read completion. Never render time.
                val hw = source.frameTimeNanos(totalFrames)
                var source0 = if (!source.isMicrophone) TimestampSource.SYNTHETIC else TimestampSource.READ_COMPLETION
                var end = readDone
                if (hw != null && hw <= readDone + 5_000_000L && readDone - hw < 500_000_000L) {
                    end = hw; if (source.isMicrophone) source0 = TimestampSource.HARDWARE
                }
                if (end <= lastEnd) end = lastEnd + 1
                lastEnd = end
                System.arraycopy(ring, head, window, 0, ring.size - head)
                if (head > 0) System.arraycopy(ring, 0, window, ring.size - head, head)
                val t0 = clock()
                val raw = analyzer.analyze(window, rate, end)
                val took = clock() - t0
                sinceAnalysis = 0
                ctx.ensureActive()
                val snapshot = raw.copy(
                    sequence = ++sequence, sessionId = mySession, timestampSource = source0,
                    sourceLabel = label, isTest = test != null
                )
                if (mySession == session) mutableFrames.value = snapshot
                // Analysis must keep up with capture: enlarge the hop rather than overflow the recorder.
                val hopNanos = hop * 1e9 / rate
                var increased = 0
                if (took > .75 * hopNanos && hop < n) { hop = min(n, hop * 2); increased = 1; fastStreak = 0 }
                else if (took < .2 * hopNanos && hop > defaultHop) { if (++fastStreak > 40) { hop = max(defaultHop, hop / 2); fastStreak = 0 } }
                else fastStreak = 0
                val st = mutableStats.value
                mutableStats.value = st.copy(
                    analyses = st.analyses + 1, hopSize = hop, hopIncreases = st.hopIncreases + increased,
                    lastAnalysisMillis = took / 1e6, timestampSource = source0
                )
            }
        }
    }

    private fun readErrorText(code: Int) = when (code) {
        -6 -> "Microphone disconnected (recorder died). Tap Start to retry."
        -3 -> "Microphone read rejected (invalid operation). Tap Start to retry."
        -2 -> "Microphone read rejected (bad value). Tap Start to retry."
        0 -> "Microphone returned no data. Tap Start to retry."
        else -> "Microphone read failed (code $code). Tap Start to retry."
    }
}
