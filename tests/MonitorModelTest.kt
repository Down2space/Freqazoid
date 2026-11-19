package com.freqazoid.analysis
import com.freakazoid.watch.ColorMath
import kotlin.math.*
import kotlin.random.Random

fun tone(rate:Int,n:Int,vararg parts:Pair<Double,Double>)=ShortArray(n){i->var v=0.0;for((f,a) in parts)v+=a*sin(2*PI*f*i/rate);(v*32767).toInt().toShort()}

fun main(){
 val rate=48000;val n=8192;val an=UnifiedAudioAnalyzer(AnalysisSettings(fftSize=n))
 val toneFrame=an.analyze(tone(rate,n,1000.0 to .3),rate,1_000_000_000).copy(sessionId=1)
 val silentFrame=an.analyze(ShortArray(n),rate,1_100_000_000).copy(sessionId=1)
 val m=MonitorModel();val v=MonitorModel.View(m.columns,m.rows)
 // Duplicate suppression: same / older timestamp never adds a row.
 check(m.consume(toneFrame));check(!m.consume(toneFrame));check(!m.consume(toneFrame.copy(windowEndNanos=900_000_000)))
 check(m.rowsAdded==1L)
 m.copyTo(v)
 val col=(0 until m.columns).maxBy{v.bands[it]};val f0=v.columnHz(col.toDouble());val f1=v.columnHz(col+1.0)
 check(1000.0 in f0..f1+toneFrame.binSpacingHz){"Tone column $f0..$f1"}
 check(abs(v.bands[col]-20*log10(.3))<1.6){"Peak-preserving level ${v.bands[col]}"}
 check(v.deltas.all{it==0.0}){"First frame is a zero baseline"}
 check(v.rowTime[0]==1_000_000_000L && v.count==1)
 // Tone -> silence and silence -> tone both register palette change.
 m.consume(silentFrame);m.copyTo(v);val off=v.meanDelta;check(off>0 && v.deltas[col]>20)
 m.consume(toneFrame.copy(windowEndNanos=1_200_000_000));m.copyTo(v);check(v.meanDelta>0 && v.deltas[col]>20)
 // Identical spectrum with a new timestamp: zero change (not a fabricated difference).
 m.consume(toneFrame.copy(windowEndNanos=1_300_000_000));m.copyTo(v);check(v.meanDelta==0.0)
 check(v.count==4 && abs(v.spanSeconds()-.3)<1e-9){"Row timestamps are window times, span ${v.spanSeconds()}"}
 // Delta formula matches the spec.
 val a=ColorMath.fromDb(-80.0);val b=ColorMath.fromDb(-20.0)
 val dr=((a shr 16) and 255)-((b shr 16) and 255);val dg=((a shr 8) and 255)-((b shr 8) and 255);val db=(a and 255)-(b and 255)
 check(abs(ColorMath.percent(a,b)-100*sqrt((dr*dr+dg*dg+db*db).toDouble())/sqrt(3.0*255*255))<1e-9)
 check(abs(MeasurementMath.normalizedRgbDifference(a,b)*100-ColorMath.percent(a,b))<1e-9)
 // Peak hold >= live and survives silence; reset clears.
 check((0 until m.columns).all{v.peaks[it]>=v.bands[it]})
 m.consume(silentFrame.copy(windowEndNanos=1_400_000_000));m.copyTo(v);check(v.peaks[col]>-20)
 m.resetPeaks();m.copyTo(v);check(v.peaks.all{it==MonitorModel.NO_DATA})
 // New capture session resets the delta baseline.
 m.consume(toneFrame.copy(windowEndNanos=1_500_000_000,sessionId=2));m.copyTo(v);check(v.meanDelta==0.0)
 // Stop clears live readings but keeps history.
 val before=v.count;m.clearLive();m.copyTo(v);check(!v.hasLive && v.bands.all{it==MonitorModel.NO_DATA} && v.count==before)
 // Layout change (FFT size) clears incompatible history and peaks.
 val an2=UnifiedAudioAnalyzer(AnalysisSettings(fftSize=4096))
 m.consume(an2.analyze(tone(rate,4096,1000.0 to .3),rate,2_000_000_000));m.copyTo(v);check(v.count==1){"FFT layout change clears history"}
 // Range change clears; Nyquist clamp at 16 kHz.
 m.setRange(MonitorRange.BASS);m.copyTo(v);check(v.count==0)
 val low=UnifiedAudioAnalyzer(AnalysisSettings(fftSize=2048))
 m.consume(low.analyze(tone(rate,2048,100.0 to .3),rate,3_000_000_000));m.copyTo(v)
 check(v.repeated.count{it}>40){"Coarse bins in Bass range are flagged as repeated, not independent"}
 m.setRange(MonitorRange.FULL);m.consume(UnifiedAudioAnalyzer(AnalysisSettings(fftSize=2048)).analyze(ShortArray(2048),16000,4_000_000_000));m.copyTo(v)
 check(v.highHz<8000 && v.highHz>7900){"Nyquist clamp ${v.highHz}"}
 // Broadband noise retained across columns.
 val r=Random(3);val noise=an.analyze(ShortArray(n){r.nextInt(-5000,5001).toShort()},rate,5_000_000_000)
 m.consume(noise);m.copyTo(v);check(v.bands.count{it>-110}>m.columns*.95){"Noise energy must remain visible"}
 // Spike in a single bin survives pooling into a wide column.
 val spec=FloatArray(n/2+1){-120f};spec[3000]=-10f
 m.consume(noise.copy(windowEndNanos=6_000_000_000,spectrumDbfs=spec));m.copyTo(v);check(v.bands.max()==-10.0)
 // Copy is stable (re-render / resize does not change data or add rows).
 val rows=m.rowsAdded;val copy=v.levelPixels.copyOf();m.copyTo(v);check(v.levelPixels.contentEquals(copy) && m.rowsAdded==rows)
 // History ring bounded.
 for(i in 0 until 300)m.consume(noise.copy(windowEndNanos=7_000_000_000L+i*10_000_000L));m.copyTo(v);check(v.count==m.rows)
 println("PASS: monitor duplicate suppression, peak-preserving pooling, repeated-bin flags, Nyquist clamp, peak hold, color-Δ baseline/formula/both directions, session/layout/range resets, timestamps, bounded ring, noise retained")
}
