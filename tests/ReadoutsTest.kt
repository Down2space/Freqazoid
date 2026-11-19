package com.freqazoid.membrane
import com.freqazoid.analysis.*
fun main(){
 val f=UnifiedAudioAnalyzer(AnalysisSettings(fftSize=8192)).analyze(ShortArray(8192){(12000*kotlin.math.sin(2*Math.PI*440*it/48000)).toInt().toShort()},48000,1).copy(isTest=true,sourceLabel="TEST · x")
 check(Readouts.status(CaptureStatus.Listening(48000,"TEST",test=true),f).startsWith("TEST"))
 check(Readouts.status(CaptureStatus.Listening(48000,"mic",test=false),f).let{it.startsWith("LIVE")&&it.contains("dBFS")})
 check(Readouts.status(CaptureStatus.Listening(48000,"mic",false,silenced=true),f).contains("SILENCED"))
 check(Readouts.status(CaptureStatus.Frozen(48000,false),f).contains("mic off"))
 check(Readouts.status(CaptureStatus.PermissionDenied(false),null).contains("Settings"))
 check(Readouts.startLabel(CaptureStatus.Listening(48000,"m",false),false)=="Stop"&&Readouts.startLabel(CaptureStatus.Frozen(1,false),false)=="Resume"&&Readouts.startLabel(CaptureStatus.Idle,false)=="Start")
 val ref=HarmonyHud(active=true,anchorHz=440.0,comparedHz=110.0,ratioLabel="4:1",singleComponent=true,referenceOnly=true)
 check(Readouts.ratioLine(ref).contains("REFERENCE")&&Readouts.ratioLine(ref).contains("generated"))
 val fifth=HarmonyHud(active=true,anchorHz=220.0,comparedHz=329.63,ratioLabel="3:2",ratioErrorCents=-1.96,simpleRatio=true)
 check(Readouts.ratioLine(fifth)=="3:2 · -2.0¢ · 220.0 vs 329.6"){Readouts.ratioLine(fifth)}
 check(Readouts.soundReading(fifth.copy(isTest=true,rmsDbfs=-12.0),true,CaptureStatus.Listening(48000,"t",true),f).startsWith("TEST · 220.0 Hz · A3"))
 check(Readouts.fftOption(32768,48000)=="32768 · 683 ms window · 1.5 Hz bins")
 val info=Readouts.info(CaptureStatus.Listening(48000,"t",true),f,AnalysisSettings(),CaptureStats(hopSize=1024))
 for(must in listOf("Sampling rate: 48000 Hz","bin spacing 5.86 Hz","dBFS","not calibrated dB SPL","110 Hz visual reference","not measured acoustic phase","Pythagorean","not harmony","No ratio is labeled happy"))check(info.contains(must)){"info missing: $must"}
 val all=listOf(info,Readouts.ratioLine(fifth),Readouts.FFT_TRADEOFF).joinToString(" ").lowercase()
 for(bad in listOf("healthy chord","sad chord","happy chord","good chord","bad chord"))check(!all.contains(bad)){bad}
 check(all.split("db spl").size-1==all.split("not calibrated db spl").size-1){"dB SPL only appears as a disclaimer"}
 check(Readouts.hz(20000.0)=="20.0 kHz"&&Readouts.hz(440.0)=="440.0 Hz"&&Readouts.hz(55.0)=="55.00 Hz")
 println("PASS: readout wording — TEST/LIVE/FROZEN/SILENCED states, dBFS units, explicit generated reference, ratio + cents, FFT tradeoff, info panel disclosures, no value judgments")
}
