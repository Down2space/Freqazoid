package com.freqazoid.membrane
import kotlin.math.*
fun main(){
 for((lo,hi,p,q) in listOf(listOf(220,440,2,1),listOf(220,330,3,2),listOf(300,400,4,3))){
  val ratio=HarmonyGeometry.ratio(lo.toDouble(),hi.toDouble());check(ratio.upper==p && ratio.lower==q && abs(ratio.errorCents)<1e-8)
  val curve=HarmonyGeometry.curve(lo.toDouble(),hi.toDouble(),.1,.1,.3)
  for(c in 0..2)check(abs(curve[c]-curve[curve.size-3+c])<1e-5){"Exact small ratios must close"}
  for(i in curve.indices step 3){val projected=HarmonyGeometry.project(curve[i].toDouble(),curve[i+1].toDouble(),curve[i+2].toDouble());check(hypot(projected.first,projected.second)*.8<1);check(projected.third in .12f..1f)}
 }
 val tempered=220*2.0.pow(7.0/12);val r=HarmonyGeometry.ratio(220.0,tempered)
 check(r.upper==3&&r.lower==2&&abs(r.errorCents+1.955)<.01)
 val detuned=HarmonyGeometry.curve(220.0,tempered,.1,.1,.3)
 check((0..2).any{abs(detuned[it]-detuned[detuned.size-3+it])>.001}){"Measured detuning must not be snapped away"}
 val silent=HarmonyGeometry.scene(AudioFrame(),0.0);check(silent.curves.isEmpty())
 val one=HarmonyGeometry.scene(AudioFrame(listOf(Tone(440.0,.1)),-20.0,1),0.0);check(one.referenceOnly)
 val two=HarmonyGeometry.scene(AudioFrame(listOf(Tone(220.0,.1),Tone(330.0,.1)),-20.0,1),0.0);check(!two.referenceOnly&&two.ratio?.label=="3:2")
 val quiet=HarmonyGeometry.scene(AudioFrame(listOf(Tone(220.0,.01),Tone(330.0,.01)),-40.0,1),0.0);check(quiet.curves.first().strength<two.curves.first().strength)
 println("PASS: 2:1, 3:2, 4:3 curve closure; measured equal-tempered mismatch retained; bounded perspective; silence; explicit single-tone reference; volume response")
}

