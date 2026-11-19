package com.freqazoid.membrane

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freakazoid.watch.ColorMath
import com.freqazoid.analysis.AnalysisSettings
import com.freqazoid.analysis.CaptureStatus
import com.freqazoid.analysis.MonitorModel
import com.freqazoid.analysis.MonitorRange
import com.freqazoid.analysis.TestSignal
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

private val TextPrimary = Color(0xfff2f5fa)
private val TextSecondary = Color(0xffbfcadb)
private val Scrim = Color(0xc005060b)
private val ChipColor = Color(0xff1d2334)

private enum class Overlay { NONE, MENU, INFO, TESTS }

@Composable
fun FreqazoidApp(vm: FreqazoidViewModel, onStart: () -> Unit, onOpenAppSettings: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val status by vm.controller.status.collectAsState()
    val testSignal by vm.controller.activeTest.collectAsState()
    var overlay by rememberSaveable { mutableStateOf(Overlay.NONE) }
    var inspect by remember { mutableStateOf(Inspect()) }
    var hud by remember { mutableStateOf(HarmonyHud()) }
    LaunchedEffect(ui.mode, ui.range) { inspect = Inspect() }
    val round = LocalConfiguration.current.isScreenRound
    val quality = when (ui.quality) { QualityChoice.AUTO -> if (round) RenderQuality.WATCH else RenderQuality.PHONE; QualityChoice.WATCH -> RenderQuality.WATCH; QualityChoice.PHONE -> RenderQuality.PHONE }

    BackHandler(enabled = overlay != Overlay.NONE) { overlay = if (overlay == Overlay.MENU) Overlay.NONE else Overlay.MENU }

    val startAction: () -> Unit = {
        val s = status
        when {
            s is CaptureStatus.Starting -> Unit
            s is CaptureStatus.Listening -> vm.controller.stop()
            s is CaptureStatus.PermissionDenied && !s.canAskAgain -> onOpenAppSettings()
            else -> onStart()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(BackgroundColor)) {
        val short = min(maxWidth.value, maxHeight.value); val long = max(maxWidth.value, maxHeight.value)
        val portrait = maxHeight >= maxWidth
        // Controls go outside the circle only when a rectangular screen has real spare room.
        val outside = !round && long - short >= 200f
        val content: @Composable (Float, Boolean) -> Unit = { d, overlayControls ->
            Viewport(vm, d, overlayControls, overlay == Overlay.NONE, ui, status, testSignal != null, quality, inspect, { inspect = it }, hud, { hud = it }, { overlay = Overlay.MENU }, startAction)
        }
        when {
            !outside -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content(short, true) }
            portrait -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(short.dp), contentAlignment = Alignment.Center) { content(short, false) }
                SidePanel(vm, ui, status, testSignal != null, inspect, hud, { overlay = Overlay.MENU }, startAction, Modifier.fillMaxWidth().weight(1f))
            }
            else -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxHeight().width(short.dp), contentAlignment = Alignment.Center) { content(short, false) }
                SidePanel(vm, ui, status, testSignal != null, inspect, hud, { overlay = Overlay.MENU }, startAction, Modifier.fillMaxHeight().weight(1f))
            }
        }
        if (overlay != Overlay.NONE) {
            OverlayFrame(round, short) {
                when (overlay) {
                    Overlay.MENU -> MenuContent(vm, ui, status, testSignal, { overlay = it }, { overlay = Overlay.NONE })
                    Overlay.INFO -> InfoContent(vm, status) { overlay = Overlay.MENU }
                    Overlay.TESTS -> TestsContent(vm, testSignal) { overlay = it }
                    Overlay.NONE -> Unit
                }
            }
        }
    }
}

/** Square viewport (min of width/height) with the circular visualization and, optionally, its controls. */
@Composable
private fun Viewport(
    vm: FreqazoidViewModel, diameter: Float, overlayControls: Boolean, focused: Boolean, ui: UiSettings, status: CaptureStatus, testActive: Boolean,
    quality: RenderQuality, inspect: Inspect, onInspect: (Inspect) -> Unit, hud: HarmonyHud, onHud: (HarmonyHud) -> Unit, onMenu: () -> Unit, onStartStop: () -> Unit
) {
    val density = LocalDensity.current.density
    val r = diameter / 2
    val plan = remember(diameter) { RoundSafeArea.plan(diameter) }
    val bandTop = if (overlayControls) plan.bandTop else -.80f * r
    val bandBottom = if (overlayControls) plan.bandBottom else .80f * r
    val textBlock = if (!overlayControls) 0f else if (ui.mode == DisplayMode.SOUND) (if (ui.legend) 50f else 36f) else 34f
    val plotBottom = bandBottom - textBlock
    val focus = remember { FocusRequester() }
    var renderStats by remember { mutableStateOf(RenderStats()) }
    var monitorView by remember { mutableStateOf<MonitorModel.View?>(null) }
    val version by vm.monitorVersion.collectAsState()
    val zoom by vm.zoom.collectAsState()
    // Rotary input returns to the visualization whenever no menu/info overlay is open.
    LaunchedEffect(focused) { if (focused) try { focus.requestFocus() } catch (_: IllegalStateException) {} }

    Box(
        Modifier.size(diameter.dp)
            .onRotaryScrollEvent { e ->
                // Rotary: zoom in Sound, move the inspection cursor in the monitors.
                if (ui.mode == DisplayMode.SOUND) vm.zoomBy(if (e.verticalScrollPixels > 0) 1.06f else 1 / 1.06f)
                else {
                    val c = if (inspect.column < 0) vm.monitor.columns / 2 else inspect.column
                    onInspect(inspect.copy(column = (c + if (e.verticalScrollPixels > 0) 2 else -2).coerceIn(0, vm.monitor.columns - 1)))
                }
                true
            }
            .focusRequester(focus).focusable()
    ) {
        if (ui.mode == DisplayMode.SOUND) {
            HarmonyCanvas(vm.controller.frames, status is CaptureStatus.Frozen, ui.harmony, quality, zoom, onHud, { renderStats = it }, Modifier.fillMaxSize())
        } else {
            MonitorCanvas(vm.monitor, version, ui.mode, bandTop * density, plotBottom * density, inspect, onInspect, { monitorView = it }, Modifier.fillMaxSize())
        }
        if (overlayControls) {
            // Mode/menu chip (top) — sized from the circle before measurement.
            ModeChip(ui.mode, testActive, onMenu, Modifier.align(Alignment.TopCenter).offset(y = (r + plan.topCenterY - plan.controlHeight / 2).dp)
                .width(plan.topWidth.dp).height(plan.controlHeight.dp))
            val textWidth = RoundSafeArea.maxWidth(r, bandBottom - textBlock / 2, textBlock, 8f)
            Column(Modifier.align(Alignment.TopCenter).offset(y = (r + bandBottom - textBlock).dp).width(textWidth.dp).height(textBlock.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                ReadoutBlock(vm, ui, status, hud, monitorView, inspect, compact = true)
            }
            StartButton(status, testActive, onStartStop, Modifier.align(Alignment.TopCenter).offset(y = (r + plan.bottomCenterY - plan.controlHeight / 2).dp)
                .width(plan.bottomWidth.dp).height(plan.controlHeight.dp))
            if (ui.debug) DebugBlock(vm, renderStats, ui.mode, Modifier.align(Alignment.TopCenter).offset(y = (r + bandTop + 2).dp)
                .width(RoundSafeArea.maxWidth(r, bandTop + 30, 56f, 8f).dp))
        } else if (ui.debug) DebugBlock(vm, renderStats, ui.mode, Modifier.align(Alignment.TopCenter).padding(top = (diameter * .14f).dp).width((diameter * .66f).dp))
    }
}

@Composable
private fun ModeChip(mode: DisplayMode, test: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Button(onClick = onClick, modifier = modifier.semantics { contentDescription = "Mode ${mode.label}. Opens mode picker, settings and info." },
        shape = RoundedCornerShape(24.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ChipColor, contentColor = TextPrimary)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (test) "FREQAZOID · TEST" else "FREQAZOID", fontSize = 10.sp, letterSpacing = 1.5.sp, color = if (test) Color(0xffffb3a8) else TextSecondary, maxLines = 1)
            Text("${mode.short} ▾", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StartButton(status: CaptureStatus, testActive: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val label = Readouts.startLabel(status, testActive)
    val listening = status is CaptureStatus.Listening
    Button(onClick = onClick, enabled = status !is CaptureStatus.Starting, modifier = modifier.semantics { contentDescription = "$label microphone" },
        shape = RoundedCornerShape(24.dp), contentPadding = PaddingValues(horizontal = 8.dp),
        colors = if (listening) ButtonDefaults.buttonColors(containerColor = Color(0xff4a2030), contentColor = TextPrimary) else ButtonDefaults.buttonColors()) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Mode readouts. Text sits on a scrim so contrast never depends on the artistic line color. */
@Composable
private fun ReadoutBlock(vm: FreqazoidViewModel, ui: UiSettings, status: CaptureStatus, hud: HarmonyHud, view: MonitorModel.View?, inspect: Inspect, compact: Boolean) {
    val frame by vm.controller.frames.collectAsState()
    val lines = ArrayList<Pair<String, Boolean>>()   // text, primary
    when (ui.mode) {
        DisplayMode.SOUND -> {
            lines += Readouts.ratioLine(hud) to false
            lines += Readouts.soundReading(hud, ui.noteNames, status, frame) to true
        }
        else -> {
            val v = view
            val first = when {
                v == null -> ""
                inspect.column >= 0 -> Readouts.inspection(v, inspect.column, inspect.age, System.nanoTime())
                ui.mode == DisplayMode.SPECTRUM -> Readouts.spectrumLine(v)
                ui.mode == DisplayMode.WATERFALL -> Readouts.waterfallLine(v)
                else -> Readouts.deltaLine(v)
            }
            lines += first to true
            lines += Readouts.status(status, frame) to false
        }
    }
    if (ui.mode == DisplayMode.SOUND && ui.legend) HueLegend(hud, Modifier.fillMaxWidth(if (compact) .86f else 1f).height(14.dp))
    Column(Modifier.clip(RoundedCornerShape(8.dp)).background(Scrim).padding(horizontal = 6.dp, vertical = 1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for ((text, primary) in lines) Text(text, color = if (primary) TextPrimary else TextSecondary, fontSize = if (primary) 13.sp else 11.sp,
            textAlign = TextAlign.Center, maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Frequency → hue legend (the same mapping the curves use) plus the disclosed display clock. */
@Composable
private fun HueLegend(hud: HarmonyHud, modifier: Modifier) {
    val density = LocalDensity.current.density
    val stops = remember { (0..12).map { Color.hsv(it / 12f * HueScale.MAX_DEGREES, .85f, 1f) } }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER } }
    Canvas(modifier.semantics { contentDescription = "Hue legend: red near 30 hertz to violet near 20 kilohertz, log scale" }) {
        val clockW = if (hud.active && hud.displayScale < 1) 46 * density else 0f
        val barW = size.width - clockW; val barH = 4 * density
        drawRect(Brush.horizontalGradient(stops, 0f, barW), Offset(0f, 0f), Size(barW, barH))
        paint.textSize = 10f * density * fontScale; paint.color = 0xffd8dfeb.toInt()
        val canvas = drawContext.canvas.nativeCanvas
        for (t in HueScale.TICKS) {
            val x = HueScale.position(t) * barW
            drawRect(Color.White, Offset(x - .5f, 0f), Size(1f, barH + 1.5f * density))
            paint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawText(Readouts.hzShort(t), x.coerceIn(8 * density, barW - 8 * density), size.height - 1.5f * density, paint)
        }
        if (clockW > 0) { paint.textAlign = android.graphics.Paint.Align.RIGHT
            canvas.drawText(String.format(java.util.Locale.US, "clock ×1/%.0f", 1 / hud.displayScale), size.width, size.height - 1.5f * density, paint) }
    }
}

@Composable
private fun DebugBlock(vm: FreqazoidViewModel, stats: RenderStats, mode: DisplayMode, modifier: Modifier) {
    val frame by vm.controller.frames.collectAsState()
    val capture by vm.controller.stats.collectAsState()
    var rate by remember { mutableStateOf(0.0) }
    LaunchedEffect(Unit) {
        var lastCount = vm.controller.stats.value.analyses; var lastAt = System.nanoTime()
        while (true) { kotlinx.coroutines.delay(1000); val now = System.nanoTime(); val c = vm.controller.stats.value.analyses
            rate = (c - lastCount) * 1e9 / (now - lastAt); lastCount = c; lastAt = now }
    }
    val lines = Readouts.debug(if (mode == DisplayMode.SOUND) stats.fps else 0f, stats.quality, capture, rate, frame, vm.conflated.get(), System.nanoTime())
    Column(modifier.background(Scrim), horizontalAlignment = Alignment.CenterHorizontally) {
        for (l in lines) Text(l, fontSize = 10.sp, color = Color(0xffa8f0c0), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Phone/tablet layout: controls beside or below the circle (never over it). */
@Composable
private fun SidePanel(vm: FreqazoidViewModel, ui: UiSettings, status: CaptureStatus, testActive: Boolean, inspect: Inspect, hud: HarmonyHud,
                      onMenu: () -> Unit, onStartStop: () -> Unit, modifier: Modifier) {
    val view = remember(vm.monitor) { MonitorModel.View(vm.monitor.columns, vm.monitor.rows) }
    val version by vm.monitorVersion.collectAsState()
    remember(version) { vm.monitor.copyTo(view); version }
    // The Sound HUD is produced by the canvas; mirror a lightweight copy here via the shared frame.
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoImage(Modifier.size(36.dp)); Spacer(Modifier.width(8.dp))
            Text("FREQAZOID", fontSize = 20.sp, letterSpacing = 4.sp, color = TextPrimary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in DisplayMode.values()) {
                val sel = m == ui.mode
                OutlinedButton(onClick = { vm.update { it.copy(mode = m) } }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Mode ${m.label}${if (sel) ", selected" else ""}" },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = if (sel) ButtonDefaults.outlinedButtonColors(containerColor = Color(0xff26324a), contentColor = TextPrimary) else ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)) {
                    Text(m.short, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        if (ui.mode != DisplayMode.SOUND) {
            val frame by vm.controller.frames.collectAsState()
            Text(if (inspect.column >= 0) Readouts.inspection(view, inspect.column, inspect.age, System.nanoTime()) else when (ui.mode) {
                DisplayMode.SPECTRUM -> Readouts.spectrumLine(view); DisplayMode.WATERFALL -> Readouts.waterfallLine(view); else -> Readouts.deltaLine(view) },
                color = TextPrimary, fontSize = 15.sp, textAlign = TextAlign.Center)
            Text(Readouts.status(status, frame), color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        } else SoundPanelReadout(vm, ui, status, hud)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StartButton(status, testActive, onStartStop, Modifier.heightIn(min = 48.dp).widthIn(min = 112.dp))
            if (status is CaptureStatus.Listening && !testActive) OutlinedButton(onClick = { vm.controller.freeze() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Freeze") }
            OutlinedButton(onClick = onMenu, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Menu, settings and info" }) { Text("Menu") }
        }
        if (ui.mode != DisplayMode.SOUND) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.resetPeaks() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset peaks") }
            OutlinedButton(onClick = { vm.clearHistory() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear history") }
        }
    }
}

/** Phone-panel Sound readout: the same tracked HUD the canvas produces, plus a short legend note. */
@Composable
private fun SoundPanelReadout(vm: FreqazoidViewModel, ui: UiSettings, status: CaptureStatus, hud: HarmonyHud) {
    val frame by vm.controller.frames.collectAsState()
    Text(Readouts.ratioLine(hud), color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
    Text(Readouts.soundReading(hud, ui.noteNames, status, frame), color = TextPrimary, fontSize = 16.sp, textAlign = TextAlign.Center)
    if (ui.mode == DisplayMode.SOUND && hud.active) Text(Readouts.status(status, frame), color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
    if (ui.legend) HueLegend(hud, Modifier.fillMaxWidth().height(16.dp))
    Text("Curves use measured frequency ratios. Hue = frequency; brightness = digital level and depth. " +
        "The display clock is slowed and phases are generated, not measured.", color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
}

@Composable
private fun LogoImage(modifier: Modifier) {
    val context = LocalContext.current
    // The supplied black-and-white Freqazoid logo is the app icon drawable; render any drawable type safely.
    val bitmap = remember {
        try {
            context.getDrawable(R.drawable.app_icon)?.let { d ->
                Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { b -> val c = android.graphics.Canvas(b); d.setBounds(0, 0, 128, 128); d.draw(c) }
            }
        } catch (_: RuntimeException) { null }
    }
    if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "Freqazoid logo", modifier = modifier.clip(CircleShape))
}

/** Full overlay; on a round screen it is a circle with generous insets so rows never touch the edge. */
@Composable
private fun OverlayFrame(round: Boolean, diameter: Float, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: IllegalStateException) {} }
    Box(Modifier.fillMaxSize().background(Color(0xf2070910)), contentAlignment = Alignment.Center) {
        val inset: Dp = if (round) (diameter * .15f).dp else 12.dp
        Column(
            Modifier.then(if (round) Modifier.size(diameter.dp).clip(CircleShape) else Modifier.fillMaxSize().widthIn(max = 560.dp))
                .onRotaryScrollEvent { e -> scope.launch { scroll.scrollBy(e.verticalScrollPixels) }; true }
                .focusRequester(focus).focusable()
                .verticalScroll(scroll).padding(horizontal = inset, vertical = if (round) (diameter * .12f).dp else 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp), content = content
        )
    }
}

@Composable private fun SectionTitle(text: String) = Text(text, color = Color(0xff9fd8ff), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), textAlign = TextAlign.Center)

@Composable private fun ActionRow(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp)) {
        Text(text, fontSize = 13.sp, textAlign = TextAlign.Center)
    }

@Composable private fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null); Spacer(Modifier.width(4.dp))
        Text(text, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }

@Composable private fun ToggleRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = null)
    }

@Composable
private fun MenuContent(vm: FreqazoidViewModel, ui: UiSettings, status: CaptureStatus, test: TestSignal?, go: (Overlay) -> Unit, close: () -> Unit) {
    val settings by vm.controller.settings.collectAsState()
    val frame by vm.controller.frames.collectAsState()
    val rate = frame?.sampleRate ?: 48000
    LogoImage(Modifier.size(40.dp))
    Text("FREQAZOID", color = TextPrimary, fontSize = 15.sp, letterSpacing = 3.sp)
    ActionRow("Close") { close() }
    SectionTitle("MODE")
    for (m in DisplayMode.values()) ChoiceRow(m.label, m == ui.mode) { vm.update { it.copy(mode = m) }; close() }
    SectionTitle("LISTENING")
    when {
        status is CaptureStatus.Frozen -> ActionRow("Resume listening") { close(); vm.controller.start() }
        status is CaptureStatus.Listening && test == null -> ActionRow("Freeze display (mic off)") { vm.controller.freeze(); close() }
    }
    ActionRow(if (test != null) "Test signals (TEST active)" else "Test signals…") { go(Overlay.TESTS) }
    ActionRow("Info, readings & color key") { go(Overlay.INFO) }
    SectionTitle("FFT SIZE (all modes)")
    for (n in AnalysisSettings.FFT_SIZES) ChoiceRow(Readouts.fftOption(n, rate), n == settings.fftSize) { vm.setFftSize(n) }
    Text(Readouts.FFT_TRADEOFF, color = TextSecondary, fontSize = 11.sp, textAlign = TextAlign.Center)
    SectionTitle("SOUND")
    ToggleRow("Group harmonics (confidence-based)", ui.groupHarmonics) { v -> vm.update { it.copy(groupHarmonics = v) } }
    ToggleRow("110 Hz reference for one tone", ui.showReference) { v -> vm.update { it.copy(showReference = v) } }
    ToggleRow("Note names & cents", ui.noteNames) { v -> vm.update { it.copy(noteNames = v) } }
    ToggleRow("Slow rotation (depth cue)", ui.rotate) { v -> vm.update { it.copy(rotate = v) } }
    ToggleRow("Hue legend", ui.legend) { v -> vm.update { it.copy(legend = v) } }
    SectionTitle("MONITORS")
    for (r in MonitorRange.values()) ChoiceRow(r.label, r == ui.range) { vm.update { it.copy(range = r) } }
    ActionRow("Reset peak hold") { vm.resetPeaks() }
    ActionRow("Clear waterfall / Δ history") { vm.clearHistory() }
    SectionTitle("DISPLAY")
    for (q in QualityChoice.values()) ChoiceRow(q.label, q == ui.quality) { vm.update { it.copy(quality = q) } }
    ToggleRow("Debug readout (fps, rates, latency)", ui.debug) { v -> vm.update { it.copy(debug = v) } }
    ActionRow("Close") { close() }
}

@Composable
private fun TestsContent(vm: FreqazoidViewModel, active: TestSignal?, go: (Overlay) -> Unit) {
    Text("TEST SIGNALS", color = TextPrimary, fontSize = 14.sp, letterSpacing = 2.sp)
    Text("Generated in the app and fed through the same analysis. The microphone is not used.", color = TextSecondary, fontSize = 11.sp, textAlign = TextAlign.Center)
    if (active != null) ActionRow("Stop test") { vm.controller.stop(); go(Overlay.NONE) }
    for (t in TestSignal.values()) ChoiceRow(t.label, t == active) { vm.controller.startTest(t); go(Overlay.NONE) }
    ActionRow("Back") { go(Overlay.MENU) }
}

@Composable
private fun InfoContent(vm: FreqazoidViewModel, status: CaptureStatus, back: () -> Unit) {
    val frame by vm.controller.frames.collectAsState()
    val settings by vm.controller.settings.collectAsState()
    val stats by vm.controller.stats.collectAsState()
    LogoImage(Modifier.size(40.dp))
    Text("READINGS & COLOR KEY", color = TextPrimary, fontSize = 14.sp, letterSpacing = 2.sp)
    ActionRow("Back") { back() }
    // Level palette key (Waterfall / Spectrum bars / Color Δ).
    Canvas(Modifier.fillMaxWidth().height(10.dp).semantics { contentDescription = "Level palette: black, blue, cyan, yellow, red, white from minus 110 to 0 dBFS" }) {
        for (i in 0 until 48) drawRect(Color(ColorMath.palette(i / 47.0)), Offset(i * size.width / 48, 0f), Size(size.width / 48 + .5f, size.height))
    }
    Text("−110 dBFS … 0 dBFS (Color Δ: 0 … 100 % change)", color = TextSecondary, fontSize = 11.sp, textAlign = TextAlign.Center)
    Text(Readouts.info(status, frame, settings, stats), color = TextPrimary, fontSize = 12.sp, lineHeight = 16.sp)
    ActionRow("Back") { back() }
}

