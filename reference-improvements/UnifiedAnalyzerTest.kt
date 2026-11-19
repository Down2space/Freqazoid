package com.freqazoid.analysis
import kotlin.math.*
import kotlin.random.Random
fun main(){
 fun checkNear(a:Double,b:Double,tolerance:Double,label:String){check(abs(a-b)<tolerance){"$label: $a != $b"}}
 for(size in listOf(2048,4096,8192,16384,32768))for(rate in listOf(16000,44100,48000)){
  val analyzer=UnifiedAudioAnalyzer(AnalysisSettings(fftSize=size))
  val samples=ShortArray(size){i->(10000*sin(2*PI*440*i/rate)+6000*sin(2*PI*1200*i/rate)).toInt().toShort()}
  val frame=analyzer.analyze(samples,rate,2_000_000_000)
  check(frame.spectrumDbfs.size==size/2+1)
  check(frame.peaks.any{abs(it.hz-440)<frame.binSpacingHz})
  check(frame.peaks.any{abs(it.hz-1200)<frame.binSpacingHz})
  checkNear(frame.rmsDbfs,20*log10(sqrt((10000.0.pow(2)+6000.0.pow(2))/2)/32768),.15,"RMS")
  checkNear(frame.binHz(size/2),rate/2.0,1e-6,"Nyquist")
  check(frame.spectralFlatness<.05)
  check(analyzer.analyze(ShortArray(size),rate,3).peaks.isEmpty())
  check(analyzer.analyze(ShortArray(size){12000},rate,4).peaks.isEmpty())
  check(analyzer.analyze(ShortArray(size){32767},rate,5).clipped)
 }
 val analyzer=UnifiedAudioAnalyzer();val random=Random(6)
 val noise=analyzer.analyze(ShortArray(8192){random.nextInt(-5000,5001).toShort()},48000,1_000_000_000)
 check(noise.spectralFlatness>.2)
 check(noise.spectrumDbfs.count{it>-110}>noise.spectrumDbfs.size*.95)
 val history=SpectrumHistory(3);history.add(noise);history.add(noise);check(history.count==1)
 history.add(noise.copy(windowEndNanos=2_000_000_000));history.add(noise.copy(windowEndNanos=3_000_000_000));history.add(noise.copy(windowEndNanos=4_000_000_000))
 check(history.count==3);checkNear(history.ageSeconds(2),2.0,1e-9,"History time")
 history.add(noise.copy(windowEndNanos=5_000_000_000,fftSize=4096,spectrumDbfs=FloatArray(2049)));check(history.count==1)
 checkNear(MeasurementMath.normalizedRgbDifference(0xff000000.toInt(),0xffffffff.toInt()),1.0,1e-9,"RGB distance")
 checkNear(MeasurementMath.frequencyAtLogPosition(.5,20.0,20000.0),sqrt(20.0*20000),1e-9,"Log midpoint")
 println("PASS: 15 FFT/rate combinations, dual peaks, RMS, Nyquist, silence/DC/clipping, broadband bins, flatness, timestamped history and RGB/log helpers")
}

