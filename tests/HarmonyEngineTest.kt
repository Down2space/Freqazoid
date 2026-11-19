package com.freqazoid.membrane
import com.freqazoid.analysis.*
import kotlin.math.*

fun snap(hz:List<Pair<Double,Double>>,end:Long,rate:Int=48000,n:Int=8192,test:Boolean=false)=AnalysisSnapshot(rate,n,end,(1e9*n/rate).toLong(),FloatArray(n/2+1){-160f},
 hz.map{(f,a)->Peak(f,a,20*log10(a),(f*n/rate).roundToInt())},-20.0,false,0.0,0.0,sessionId=1,isTest=test)
fun pcm(signal:TestSignal,rate:Int,n:Int):ShortArray{val s=SyntheticPcmSource(signal,rate,paced=false);s.start();val b=ShortArray(n);s.read(b,0,n);return b}
fun cents(a:Double,b:Double)=1200*ln(b/a)/ln(2.0)
fun run(engine:HarmonyEngine,s:AnalysisSnapshot?,seconds:Double,settings:HarmonySettings=HarmonySettings(),q:RenderQuality=RenderQuality.PHONE):HarmonyHud{
 var h=HarmonyHud();var t=0.0;var stamp=s?.windowEndNanos?:0
 while(t<seconds){t+=1/60.0;stamp+=16_666_667;h=engine.step(s?.copy(windowEndNanos=stamp),1/60.0,settings,q,stamp)};return h}

fun main(){
 // A. Every labeled synthetic test through the real analyzer, 3 rates x 5 FFT sizes.
 var cases=0
 for(rate in listOf(16000,44100,48000))for(n in AnalysisSettings.FFT_SIZES){
  val an=UnifiedAudioAnalyzer(AnalysisSettings(fftSize=n));val bin=rate.toDouble()/n
  fun hud(sig:TestSignal,set:HarmonySettings=HarmonySettings()):Pair<HarmonyHud,HarmonyEngine>{val e=HarmonyEngine();val s=an.analyze(pcm(sig,rate,n),rate,1_000_000_000).copy(isTest=true,sessionId=1);return run(e,s,1.0,set) to e}
  fun near(a:Double,b:Double)=abs(a-b)<max(.35*bin,b*.003)
  run{val(h,e)=hud(TestSignal.SINGLE);check(h.isTest&&h.active&&h.singleComponent&&h.referenceOnly&&near(h.anchorHz,440.0)){"single $rate/$n $h"}
   val cs=CurveSet();e.fill(cs,RenderQuality.PHONE,HarmonySettings());check(cs.count==1&&cs.reference[0]){"reference curve flagged"}
   val(h2,e2)=hud(TestSignal.SINGLE,HarmonySettings(showReference=false));check(!h2.referenceOnly&&h2.singleComponent)
   val c2=CurveSet();e2.fill(c2,RenderQuality.PHONE,HarmonySettings(showReference=false));check(c2.count==1&&!c2.reference[0])}
  for((sig,label,lo,hi) in listOf(listOf(TestSignal.OCTAVE,"2:1",220.0,440.0),listOf(TestSignal.FIFTH,"3:2",220.0,330.0),listOf(TestSignal.FOURTH,"4:3",300.0,400.0))){
   sig as TestSignal;val(h,_)=hud(sig);check(!h.referenceOnly&&h.ratioLabel==label){"$sig $rate/$n ${h.ratioLabel} ${h.ratioErrorCents}"}
   check(near(min(h.anchorHz,h.comparedHz),lo as Double)&&near(max(h.anchorHz,h.comparedHz),hi as Double))
   if(n>=8192)check(abs(h.ratioErrorCents)<2){"exact ratio error ${h.ratioErrorCents} at $rate/$n"}}
  run{val(h,_)=hud(TestSignal.DETUNED_FIFTH);check(h.ratioLabel=="3:2"){"detuned label"}
   if(n>=8192)check(abs(h.ratioErrorCents-cents(330.0,331.5))<2){"detune retained ${h.ratioErrorCents} at $rate/$n"}}
  run{val(h,e)=hud(TestSignal.CHORD);check(near(h.anchorHz,261.63)&&h.components==(if(bin<12)3 else h.components.coerceIn(2,3))){"chord $rate/$n $h"}
   val cs=CurveSet();e.fill(cs,RenderQuality.PHONE,HarmonySettings());check(cs.count==h.components-1&&(cs.count<2||cs.strength[0]>cs.strength[1])){"unequal amplitudes -> unequal brightness"}}
  run{val(h,_)=hud(TestSignal.GUITAR);check(near(h.anchorHz,110.0)&&h.components>=4){"guitar raw $rate/$n $h"}
   val(g,_)=hud(TestSignal.GUITAR,HarmonySettings(groupHarmonics=true));check(g.singleComponent&&g.groupedPartials==1&&near(g.anchorHz,110.0)){"guitar grouped $rate/$n $g"}
   val(o,_)=hud(TestSignal.OCTAVE,HarmonySettings(groupHarmonics=true));check(!o.singleComponent){"equal octave must not be merged"}}
  run{val s=an.analyze(pcm(TestSignal.NOISE,rate,n),rate,1_000_000_000);check(s.spectralFlatness>.2&&s.spectrumDbfs.count{it>-110}>s.spectrumDbfs.size*.95)
   val e=HarmonyEngine();run(e,s,.5);val cs=CurveSet();e.fill(cs,RenderQuality.PHONE,HarmonySettings());for(c in 0 until cs.count)for(i in 0 until 2*cs.points[c])check(cs.xy[c][i].isFinite())}
  cases++
 }
 println("ok A: 8 labeled synthetic signals x $cases rate/FFT combinations")
 // B. Exact ratios close; measured detuning stays open (no snapping).
 for((lo,hi) in listOf(220.0 to 440.0,220.0 to 330.0,300.0 to 400.0)){
  val e=HarmonyEngine();run(e,snap(listOf(lo to .2,hi to .2),1_000_000_000),.5,HarmonySettings(rotate=false))
  val cs=CurveSet();e.fill(cs,RenderQuality.PHONE,HarmonySettings());val p=cs.xy[0];val last=2*(cs.points[0]-1)
  check(abs(p[0]-p[last])<1e-4&&abs(p[1]-p[last+1])<1e-4){"$lo:$hi must close"}}
 run{val e=HarmonyEngine();run(e,snap(listOf(220.0 to .2,220*2.0.pow(7.0/12) to .2),1_000_000_000),.5)
  val cs=CurveSet();e.fill(cs,RenderQuality.PHONE,HarmonySettings());val p=cs.xy[0];val last=2*(cs.points[0]-1)
  check(hypot(p[0]-p[last],p[1]-p[last+1])>1e-3){"equal-tempered fifth must stay open"}}
 println("ok B: closure for 2:1 3:2 4:3, equal-tempered detuning retained")
 // C. Phase continuity while frequency glides: no resets/jumps (smooth trajectory of the "now" point).
 run{val e=HarmonyEngine();val set=HarmonySettings(rotate=false);var stamp=1_000_000_000L
  var prev=Double.NaN;var maxJump=0.0;val pts=ArrayList<Pair<Float,Float>>()
  for(i in 0 until 240){stamp+=16_666_667;val f=220+6.0*i/240
   e.step(snap(listOf(f to .2,1.5*f to .15),stamp),1/60.0,set,RenderQuality.PHONE,stamp)
   val ph=e.anchorPhase();if(!prev.isNaN()&&i>30){var d=ph-prev;if(d<0)d+=2*PI;maxJump=max(maxJump,d)};prev=ph
   val cs=CurveSet();e.fill(cs,RenderQuality.PHONE,set);if(i>60&&cs.count>0)pts+=cs.xy[0][0] to cs.xy[0][1]}
  check(maxJump<=2*PI*1.6/60+1e-6){"display phase step $maxJump exceeds slowed clock"}
  var maxMove=0f;var maxAccel=0f
  for(i in 1 until pts.size-1){maxMove=max(maxMove,hypot(pts[i].first-pts[i-1].first,pts[i].second-pts[i-1].second))
   maxAccel=max(maxAccel,hypot(pts[i+1].first-2*pts[i].first+pts[i-1].first,pts[i+1].second-2*pts[i].second+pts[i-1].second))}
  check(maxAccel<.35f*maxMove){"trajectory discontinuity: accel $maxAccel vs move $maxMove"}
  println("ok C: integrated phase continuity, max phase step ${"%.4f".format(maxJump)} rad/frame, max step ${"%.4f".format(maxMove)}, max 2nd difference ${"%.4f".format(maxAccel)}")}
 // D. Anchor hysteresis: near-equal components don't flicker; a clearly stronger one takes over after hold.
 run{val e=HarmonyEngine();var stamp=1_000_000_000L;val set=HarmonySettings();var switches=0;var last=0.0
  for(i in 0 until 120){stamp+=16_666_667;val a=if(i%2==0).30 else .28;val b=if(i%2==0).28 else .30
   val h=e.step(snap(listOf(220.0 to a,330.0 to b),stamp),1/60.0,set,RenderQuality.PHONE,stamp);if(last!=0.0&&abs(h.anchorHz-last)>1)switches++;last=h.anchorHz}
  check(switches==0){"anchor flickered $switches times"}
  var switchedAt=-1.0;var t=0.0
  for(i in 0 until 60){stamp+=16_666_667;t+=1/60.0;val h=e.step(snap(listOf(220.0 to .2,330.0 to .6),stamp),1/60.0,set,RenderQuality.PHONE,stamp);if(switchedAt<0&&abs(h.anchorHz-330)<2)switchedAt=t}
  check(switchedAt>=HarmonyEngine.ANCHOR_HOLD_S-.02&&switchedAt<HarmonyEngine.ANCHOR_HOLD_S+.1){"switched at $switchedAt"}
  println("ok D: anchor hysteresis (no flicker; switch after ${"%.2f".format(switchedAt)} s)")}
 // E. Silence and stop settle to an explicit inactive state; no invented motion.
 run{val e=HarmonyEngine();run(e,snap(listOf(220.0 to .2,330.0 to .2),1_000_000_000),.3)
  val h=run(e,null,1.0);check(e.isInactive&&!h.active){"stop must settle inactive"}
  val cs=CurveSet();e.fill(cs,RenderQuality.PHONE,HarmonySettings());check(cs.count==0)
  val e2=HarmonyEngine();val an=UnifiedAudioAnalyzer();val silent=an.analyze(ShortArray(8192),48000,1_000_000_000)
  val hs=run(e2,silent,1.0);check(!hs.active&&e2.isInactive)
  // Stale live input (no new analysis for > 0.6 s) also fades out.
  val e3=HarmonyEngine();val s=snap(listOf(440.0 to .2),1_000_000_000);var now=1_000_000_000L
  repeat(120){now+=16_666_667;e3.step(s,1/60.0,HarmonySettings(),RenderQuality.PHONE,now)};check(e3.isInactive){"stale data must not keep drawing"}
  println("ok E: stop/silence/stale input -> inactive, zero curves")}
 // F. Amplitude changes brightness, never pitch; display clock disclosed and bounded; depth spread; bounded projection.
 run{val loud=HarmonyEngine();val quiet=HarmonyEngine()
  val hl=run(loud,snap(listOf(220.0 to .3,330.0 to .3),1_000_000_000),.5);val hq=run(quiet,snap(listOf(220.0 to .02,330.0 to .02),1_000_000_000),.5)
  check(abs(hl.anchorHz-hq.anchorHz)<1e-9&&hl.ratioLabel==hq.ratioLabel)
  val a=CurveSet();loud.fill(a,RenderQuality.PHONE,HarmonySettings());val b=CurveSet();quiet.fill(b,RenderQuality.PHONE,HarmonySettings())
  check(a.strength[0]>b.strength[0]*2)
  check(hl.displayScale<=1.5/330*1.05){"display clock ${hl.displayScale}"}
  var lo=1f;var hi=0f;for(i in 0 until a.points[0]){lo=min(lo,a.shade[0][i]);hi=max(hi,a.shade[0][i]);check(hypot(a.xy[0][2*i],a.xy[0][2*i+1])<1.25f)}
  check(hi-lo>.35f){"depth shading spread ${hi-lo}"}
  println("ok F: amplitude->brightness only; display clock ${"%.5f".format(hl.displayScale)} <= 1.5/f; depth spread ${"%.2f".format(hi-lo)}; projection bounded")}
 // G. Wide ratios use a shorter partial window instead of aliasing.
 run{val e=HarmonyEngine();val h=run(e,snap(listOf(41.0 to .2,3001.0 to .2),1_000_000_000),.3,q=RenderQuality.WATCH)
  check(h.partialWindow);val cs=CurveSet();e.fill(cs,RenderQuality.WATCH,HarmonySettings())
  check(cs.points[0]<=RenderQuality.WATCH.maxPoints&&cs.points[0]>=RenderQuality.WATCH.maxCyclesHigh*RenderQuality.WATCH.samplesPerCycle)
  println("ok G: wide ratio -> partial window, ${cs.points[0]} points (${RenderQuality.WATCH.samplesPerCycle}/cycle)")}
 // H. Frozen holds the figure exactly.
 run{val e=HarmonyEngine();val s=snap(listOf(220.0 to .2,330.0 to .2),1_000_000_000);run(e,s,.3)
  val a=CurveSet();e.fill(a,RenderQuality.PHONE,HarmonySettings());repeat(30){e.step(s,1/60.0,HarmonySettings(),RenderQuality.PHONE,5_000_000_000,frozen=true)}
  val b=CurveSet();e.fill(b,RenderQuality.PHONE,HarmonySettings());check(a.xy[0].contentEquals(b.xy[0]));println("ok H: frozen figure is static")}
 println("PASS: harmony engine synthetic signals, closure, detuning, continuity, hysteresis, inactive states, amplitude/pitch separation, depth, partial windows, freeze")
}
