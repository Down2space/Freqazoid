package com.freqazoid.membrane
import kotlin.math.*
fun main(){
 fun close(actual:Double,expected:Double,tolerance:Double=1e-8){check(abs(actual-expected)<tolerance){"$actual != $expected"}}
 close(Bessel.j(0,0.0),1.0);close(Bessel.j(3,0.0),0.0)
 val z0=Bessel.zeros(0,3);val z1=Bessel.zeros(1,2);val z2=Bessel.zeros(2,1)
 close(z0[0],2.404825557695773);close(z0[1],5.520078110286311);close(z0[2],8.653727912911013)
 close(z1[0],3.8317059702075125);close(z2[0],5.135622301840683)
 val config=MembraneConfig(maxAngularOrder=4,radialModes=4,gridSize=65)
 val engine=MembraneEngine(config);val fundamental=engine.modes.first()
 close(engine.modes.first{it.n==1&&it.m==1}.hz/fundamental.hz,3.8317059702075125/2.404825557695773)
 close(config.copy(radiusMeters=.2).resonance(z0[0]),fundamental.hz/2)
 close(config.copy(tensionNewtonsPerMeter=1536.0).resonance(z0[0]),fundamental.hz*2)
 for(mode in engine.modes){close(Bessel.j(mode.n,mode.alpha),0.0);close(engine.displacement(mode,1.0,0.0,.123),0.0)}
 val resonant=engine.responses(Tone(fundamental.hz,.05)).first{it.mode==fundamental}.magnitude
 val off=engine.responses(Tone(fundamental.hz*1.2,.05)).first{it.mode==fundamental}.magnitude
 check(resonant>off*8){"Resonance must be much stronger than off resonance"}
 val scene=engine.update(AudioFrame(listOf(Tone(fundamental.hz,.05)),-29.0,1))
 check(scene.layers.isNotEmpty());check(scene.layers.all{it.segments.all{p->p.isFinite()}})
 check(engine.update(AudioFrame()).layers.isEmpty());check(engine.surface(.1).all{it==0f})
 val radial=engine.modes.first{it.n==0&&it.m==2};val size=65
 val grid=FloatArray(size*size){i->engine.eigenfunction(radial,2.0*(i%size)/(size-1)-1,2.0*(i/size)/(size-1)-1).toFloat()}
 val segments=Contours.zero(grid,size);check(segments.isNotEmpty())
 val expectedRadius=z0[0]/z0[1]
 for(i in segments.indices step 2)close(hypot(segments[i].toDouble(),segments[i+1].toDouble()),expectedRadius,.002)
 val center=MembraneEngine(config.copy(driveRadius=0.0))
 check(center.responses(Tone(fundamental.hz*1.6,.05)).all{it.mode.n==0}){"Central forcing must not excite n>0"}
 for(rate in listOf(16000,44100,48000)){
  val dsp=AudioDsp(rate,8192);val pcm=ShortArray(8192){i->(8000*sin(2*PI*440*i/rate)+5000*sin(2*PI*1200*i/rate)).toInt().toShort()}
  val tones=dsp.analyze(pcm).tones
  check(tones.any{abs(it.hz-440)<rate/8192.0});check(tones.any{abs(it.hz-1200)<rate/8192.0})
  check(dsp.analyze(ShortArray(8192)).tones.isEmpty())
 }
 println("PASS: Bessel zeros; fixed rim; resonance ratios; radius/tension scaling; off-resonance attenuation; silence; nodal ring geometry; forcing symmetry; dual-tone FFT at 3 sample rates")
 val full=MembraneEngine();println("Default membrane mode bank: ${full.modes.size} modes, ${full.modes.first().hz}–${full.modes.last().hz} Hz")
}

