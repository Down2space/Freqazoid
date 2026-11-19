package com.freqazoid.membrane
import java.io.File
import kotlin.math.*
import kotlin.random.Random
fun main(args:Array<String>){
 val atlas=NoteAtlas(File(args[0]).inputStream());check(atlas.glyphs.size==57)
 fun hz(midi:Double)=440*2.0.pow((midi-69)/12)
 for(glyph in atlas.glyphs){val (i,t)=atlas.interval(hz(glyph.midi.toDouble()));val points=atlas.positions(i,t)
  val expected=if(t==0f)atlas.glyphs[i].points else FloatArray(points.size){j->glyph.points[atlas.permutations[i][j/2]*2+j%2]}
  check(points.contentEquals(expected)){"Anchor ${glyph.midi} must resolve exactly to its chart geometry"}}
 val (i,t)=atlas.interval(440*2.0.pow(35.0/1200));check(abs(t-.175)<1e-5)
 val a=atlas.positions(i,0f);val mid=atlas.positions(i,t);check(a.indices.any{abs(a[it]-mid[it])>.005}){"Detuning must move vertices"}
 check(HarmonicGroups.combine(listOf(Tone(110.0,.2),Tone(220.0,.12),Tone(330.0,.08))).size==1)
 check(HarmonicGroups.combine(listOf(Tone(110.0,.2),Tone(146.832,.12))).size==2)
 val engine=NoteShapeEngine(atlas)
 val scene=engine.update(AudioFrame(listOf(Tone(440.0,.2),Tone(523.251,.1)),-20.0,1_000_000_000))
 val loud=scene.layers.filter{abs(it.hz-440)<1}.sumOf{it.strength.toDouble()};val quiet=scene.layers.filter{abs(it.hz-523.251)<1}.sumOf{it.strength.toDouble()};check(loud>quiet)
 check(scene.layers.all{it.segments.all{v->v.isFinite()}})
 check(engine.update(AudioFrame()).layers.isEmpty())
 for(rate in listOf(16000,44100,48000)){
  val dsp=AudioDsp(rate)
  for(midi in listOf(24,40,57,69,84,96)){
   val f=hz(midi.toDouble());if(f>rate*.45)continue
   val samples=ShortArray(16384){j->(12000*sin(2*PI*f*j/rate)).toInt().toShort()}
   val frame=dsp.analyze(samples,1);val tone=frame.tones.maxBy{it.amplitude}
   val error=abs(1200*ln(tone.hz/f)/ln(2.0));check(error<4){"Pure note $midi at $rate error $error cents"}
   check(frame.noiseLevel<.05)
  }
  val guitar=ShortArray(16384){j->(9000*sin(2*PI*110*j/rate)+6000*sin(2*PI*220*j/rate)+3000*sin(2*PI*330*j/rate)).toInt().toShort()}
  val grouped=HarmonicGroups.combine(dsp.analyze(guitar).tones)
  check(grouped.size==1 && abs(grouped[0].hz-110)<.2){"Harmonic-rich guitar-like note"}
  val random=Random(123);val noise=ShortArray(16384){random.nextInt(-5000,5000).toShort()}
  check(dsp.analyze(noise).noiseLevel>.2){"Broadband noise should register spectral flatness"}
 }
 println("PASS: all 57 chart endpoints; pitch-driven vertex motion; harmonic grouping; independent note mixture; loudness weighting; silence; pure-note tuning and harmonic-rich tones at 3 sample rates; broadband detection")
}

