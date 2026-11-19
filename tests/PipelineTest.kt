package com.freqazoid.membrane
import com.freqazoid.analysis.*
import kotlinx.coroutines.*
import kotlin.math.abs
/** End to end: one controller -> shared StateFlow -> monitor model + harmony engine; mode switches never restart capture. */
fun main()=runBlocking{
 val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
 var micOpens=0
 val c=AudioController(scope,PcmSourceOpener{micOpens++;error("mic must not open")},{true},AnalysisSettings(fftSize=4096))
 val monitor=MonitorModel();var distinct=0L;var last:AnalysisSnapshot?=null
 val consumer=scope.launch{c.frames.collect{s->if(s==null)monitor.clearLive() else{distinct++;monitor.consume(s)}}}
 c.startTest(TestSignal.FIFTH)
 val engine=HarmonyEngine();var mode=DisplayMode.SOUND;var hud=HarmonyHud()
 val opened=c.stats.value.sourcesOpened
 // A renderer polling at ~120 Hz (faster than analysis) must not create rows or duplicate updates.
 val t0=System.nanoTime();var polls=0
 while(System.nanoTime()-t0<2_000_000_000L){
  val s=c.frames.value
  if(polls%60==0)mode=DisplayMode.values()[(polls/60)%4]        // switch modes ~every 0.5 s
  if(mode==DisplayMode.SOUND)hud=engine.step(s,1/120.0,HarmonySettings(),RenderQuality.PHONE,System.nanoTime())
  polls++;delay(8)
 }
 c.sync()
 val st=c.stats.value
 check(micOpens==0 && st.sourcesOpened==1 && c.maxConcurrentSources.get()==1){"mode switches must not reopen: ${st.sourcesOpened}"}
 check(monitor.rowsAdded<=st.analyses && monitor.rowsAdded<=distinct && monitor.rowsAdded<polls){"rows ${monitor.rowsAdded} analyses ${st.analyses} polls $polls"}
 check(monitor.rowsAdded>10)
 val v=MonitorModel.View(monitor.columns,monitor.rows);monitor.copyTo(v)
 val expectedSpan=(v.count-1)*st.hopSize/48000.0
 check(abs(v.spanSeconds()-expectedSpan)<.05*expectedSpan+.02){"waterfall seconds follow capture time: ${v.spanSeconds()} vs $expectedSpan"}
 c.stop();c.sync();delay(50)
 val afterStop=engine.step(c.frames.value,1.0,HarmonySettings(),RenderQuality.PHONE,System.nanoTime())
 repeat(60){engine.step(c.frames.value,1/60.0,HarmonySettings(),RenderQuality.PHONE,System.nanoTime())}
 monitor.copyTo(v)
 check(c.frames.value==null && !v.hasLive && engine.isInactive){"no stale data after stop"}
 consumer.cancel();scope.cancel()
 println("PASS: pipeline — 1 source for ${polls} renderer polls across 4 modes; ${monitor.rowsAdded} rows from ${st.analyses} analyses (no duplicate rows); waterfall span ${"%.2f".format(v.spanSeconds())} s matches capture clock; TEST ratio ${hud.ratioLabel}; stop clears live state (${afterStop.active})")
}
