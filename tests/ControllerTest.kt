package com.freqazoid.analysis
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*

/** Fake recorder: 440 Hz sine, ~real-time pacing, optional failure and hardware timestamps. */
class FakeSource(override val sampleRate:Int, private val log:MutableList<String>, private val failAfterReads:Int=-1,
                 private val failCode:Int=-6, private val hw:((Long)->Long?)?=null):PcmSource{
 override val label="fake mic";override val isMicrophone=true
 private var frame=0L;var reads=0;@Volatile var closed=false
 override fun start(){log+="start"}
 override fun read(buffer:ShortArray,offset:Int,length:Int):Int{
  check(!closed){"read after close"}
  Thread.sleep(2);reads++
  if(failAfterReads in 0..reads)return failCode
  for(i in 0 until length)buffer[offset+i]=(9000*sin(2*PI*440*(frame+i)/sampleRate)).toInt().toShort()
  frame+=length;return length
 }
 override fun frameTimeNanos(framePosition:Long)=hw?.invoke(framePosition)
 override fun close(){check(!closed){"double close"};closed=true;log+="close"}
}
class FakeOpener(val rate:Int=48000,val log:MutableList<String> = CopyOnWriteArrayList(),var fail:Exception?=null,var failAfterReads:Int=-1,var hw:((Long)->Long?)?=null):PcmSourceOpener{
 val opens=AtomicInteger();val open=AtomicInteger();val maxOpen=AtomicInteger()
 override fun open(settings:AnalysisSettings):PcmSource{
  fail?.let{throw it};opens.incrementAndGet()
  val now=open.incrementAndGet();maxOpen.accumulateAndGet(now){a,b->max(a,b)}
  log+="open ${settings.fftSize}"
  val src=FakeSource(rate,log,failAfterReads,-6,hw)
  return object:PcmSource by src{override fun close(){src.close();open.decrementAndGet()}}
 }
}
suspend fun waitFor(ms:Long=4000,what:String,cond:()->Boolean){val end=System.currentTimeMillis()+ms;while(!cond()){check(System.currentTimeMillis()<end){"Timed out: $what"};delay(5)}}

fun main()=runBlocking{
 var permission=true
 fun make(opener:PcmSourceOpener,fft:Int=4096,scope:CoroutineScope)=AudioController(scope,opener,{permission},AnalysisSettings(fftSize=fft))
 val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)

 // 1. One recorder; many subscribers (modes) never open another; repeated taps ignored.
 run{
  val o=FakeOpener();val c=make(o,scope=scope)
  repeat(5){c.start()};c.sync()
  waitFor(what="listening"){c.frames.value!=null}
  val seen=List(4){AtomicInteger()}
  val subs=seen.map{n->scope.launch{c.frames.collect{if(it!=null)n.incrementAndGet()}}}
  delay(300)
  check(o.opens.get()==1){"Repeated taps / subscribers opened ${o.opens.get()} recorders"}
  check(seen.all{it.get()>0}){"Every mode subscriber receives the same stream"}
  check(c.status.value is CaptureStatus.Listening)
  // Snapshots carry layout and timing metadata and strictly increasing timestamps.
  val a=c.frames.value!!;delay(150);val b=c.frames.value!!
  check(b.windowEndNanos>a.windowEndNanos && b.sequence>a.sequence && a.fftSize==4096 && a.sampleRate==48000 && a.windowDurationNanos==(1e9*4096/48000).toLong())
  check(a.timestampSource==TimestampSource.READ_COMPLETION)
  check(a.peaks.any{abs(it.hz-440)<a.binSpacingHz})
  subs.forEach{it.cancel()}
  // 2. Stop releases before returning; no stale data afterwards.
  c.stop();c.sync()
  check(c.frames.value==null && c.status.value==CaptureStatus.Idle && o.open.get()==0 && c.openSources.get()==0)
  check(o.log.last()=="close"){"Recorder released on stop: ${o.log}"}
  delay(150);check(c.frames.value==null){"Stale frame published after stop"}
  println("ok 1-2 single owner, subscribers, metadata, stop releases, no stale data")
 }
 // 3. Rapid start/stop/test/fft churn never overlaps recorders.
 run{
  val o=FakeOpener();val c=make(o,scope=scope)
  repeat(30){i->when(i%5){0->c.start();1->c.stop();2->c.start();3->c.setFftSize(if(i%2==0)8192 else 2048);else->c.startTest(TestSignal.FIFTH)}}
  c.sync();c.stop();c.sync()
  check(o.maxOpen.get()<=1 && c.maxConcurrentSources.get()==1){"Overlapping recorders: ${o.maxOpen.get()}"}
  check(o.open.get()==0 && c.openSources.get()==0)
  println("ok 3 churn: max concurrent sources = ${c.maxConcurrentSources.get()}")
 }
 // 4. FFT change while listening rebuilds safely; incompatible frames never mix.
 run{
  val o=FakeOpener();val c=make(o,scope=scope);c.start();waitFor(what="frames"){c.frames.value!=null}
  c.setFftSize(16384);c.sync()
  check(c.frames.value==null || c.frames.value!!.fftSize==16384)
  waitFor(what="new fft"){c.frames.value?.fftSize==16384}
  check(o.opens.get()==2 && o.maxOpen.get()==1 && c.settings.value.fftSize==16384)
  c.setFftSize(16384);c.sync();check(o.opens.get()==2){"Same size must not restart"}
  c.setFftSize(1000);c.sync();check(c.settings.value.fftSize==16384){"Unsupported size rejected"}
  c.stop();c.sync();println("ok 4 FFT change rebuilds once, rejects unsupported sizes")
 }
 // 5. Freeze = mic off, last frame kept and unchanged; resume requires explicit Start.
 run{
  val o=FakeOpener();val c=make(o,scope=scope);c.start();waitFor(what="frames"){c.frames.value!=null}
  c.freeze();c.sync();val kept=c.frames.value
  check(c.status.value is CaptureStatus.Frozen && kept!=null && o.open.get()==0)
  delay(150);check(c.frames.value===kept){"Frozen frame must not change"}
  c.onHostPaused();c.sync();check(c.status.value is CaptureStatus.Frozen)
  c.start();c.sync();waitFor(what="resume"){c.frames.value!=null && c.frames.value!==kept}
  check(c.frames.value!!.sessionId!=kept.sessionId)
  c.stop();c.sync();println("ok 5 freeze holds frame with mic off; resume is a new session")
 }
 // 6. Leaving the app stops; returning does not restart.
 run{
  val o=FakeOpener();val c=make(o,scope=scope);c.start();waitFor(what="frames"){c.frames.value!=null}
  c.onHostPaused();c.sync();check(c.status.value==CaptureStatus.Idle && c.frames.value==null && o.open.get()==0)
  delay(200);check(o.opens.get()==1 && c.status.value==CaptureStatus.Idle){"Must not restart without user action"}
  println("ok 6 host pause stops; no automatic restart")
 }
 // 7. Permission flow: denied, retry, granted.
 run{
  val o=FakeOpener();val c=make(o,scope=scope);permission=false
  c.start();c.sync();check(c.status.value==CaptureStatus.PermissionDenied(true) && o.opens.get()==0)
  c.onPermissionRequested();c.sync();check(c.status.value==CaptureStatus.RequestingPermission)
  c.onPermissionResult(false,false);c.sync();check(c.status.value==CaptureStatus.PermissionDenied(false))
  permission=true;c.onPermissionResult(true,true);c.sync();waitFor(what="granted listening"){c.frames.value!=null}
  c.stop();c.sync()
  // Revoked at open time (SecurityException) -> PermissionDenied, nothing left open.
  o.fail=SecurityException("revoked");c.start();c.sync();waitFor(what="denied"){c.status.value is CaptureStatus.PermissionDenied}
  o.fail=IllegalStateException("No supported microphone format");c.start();c.sync();waitFor(what="error"){c.status.value is CaptureStatus.Error}
  check((c.status.value as CaptureStatus.Error).message.contains("format"))
  check(c.openSources.get()==0);println("ok 7 permission denied/retry/granted/revoked; unsupported format error")
 }
 // 8. Recorder failure mid-stream: error state, released, no stale frames.
 run{
  val o=FakeOpener(failAfterReads=60);val c=make(o,scope=scope);c.start()
  waitFor(what="read error"){c.status.value is CaptureStatus.Error}
  check((c.status.value as CaptureStatus.Error).message.contains("disconnected"))
  check(c.frames.value==null && o.open.get()==0 && c.openSources.get()==0 && o.log.last()=="close")
  o.failAfterReads=-1;c.start();waitFor(what="retry"){c.frames.value!=null};c.stop();c.sync()
  println("ok 8 recorder death -> error, released; retry works")
 }
 // 9. Hardware timestamps used only when plausible.
 run{
  val base=System.nanoTime()
  val good=FakeOpener(hw={f->System.nanoTime()-1_000_000});val c=make(good,scope=scope);c.start()
  waitFor(what="hw"){c.frames.value!=null};check(c.frames.value!!.timestampSource==TimestampSource.HARDWARE);c.stop();c.sync()
  val bad=FakeOpener(hw={f->System.nanoTime()+5_000_000_000});val d=make(bad,scope=scope);d.start()
  waitFor(what="fallback"){d.frames.value!=null};check(d.frames.value!!.timestampSource==TimestampSource.READ_COMPLETION)
  check(d.frames.value!!.windowEndNanos>=base);d.stop();d.sync()
  println("ok 9 hardware timestamp validated; implausible -> read-completion fallback")
 }
 // 10. TEST mode never opens the microphone and is labeled.
 run{
  val o=FakeOpener();val c=make(o,fft=8192,scope=scope);c.startTest(TestSignal.FIFTH)
  waitFor(what="test frames"){c.frames.value!=null}
  val f=c.frames.value!!;val s=c.status.value as CaptureStatus.Listening
  check(o.opens.get()==0 && f.isTest && s.test && f.sourceLabel.startsWith("TEST") && f.timestampSource==TimestampSource.SYNTHETIC)
  check(f.peaks.any{abs(it.hz-220)<f.binSpacingHz} && f.peaks.any{abs(it.hz-330)<f.binSpacingHz})
  check(c.activeTest.value==TestSignal.FIFTH)
  c.start();c.sync();waitFor(what="mic after test"){c.frames.value?.isTest==false};check(o.opens.get()==1 && c.activeTest.value==null)
  c.stop();c.sync();println("ok 10 TEST mode is synthetic, labeled, and mic-free")
 }
 scope.cancel()
 println("PASS: controller ownership, lifecycle, subscribers, churn, FFT rebuild, freeze, pause, permission, errors, timestamps, TEST mode")
}
