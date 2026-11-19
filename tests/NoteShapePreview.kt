package com.freqazoid.membrane
import java.io.File
import java.util.Locale
import java.awt.Color
import kotlin.math.*
fun main(args:Array<String>){
 val atlas=NoteAtlas(File(args[0]).inputStream())
 val cases=listOf(listOf(Tone(440.0,.18)),listOf(Tone(440*2.0.pow(35.0/1200),.18)),listOf(Tone(440.0,.18),Tone(523.251,.13),Tone(659.255,.10)))
 val titles=listOf("Pure A4 · 440 Hz","A4 detuned +35 cents","A4 + C5 + E5 · weighted mixture")
 val s=StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' width='1200' height='460'><defs><filter id='g'><feGaussianBlur stdDeviation='2'/></filter></defs><rect width='1200' height='460' fill='#05060b'/><text x='600' y='27' fill='#b8c2d5' font-family='sans-serif' font-size='16' text-anchor='middle'>Reference-chart laser notation · calculated vector morphs from synthetic audio inputs</text>")
 for(case in cases.indices){val scene=NoteShapeEngine(atlas).update(AudioFrame(cases[case],-20.0,1_000_000_000));s.append("<g transform='translate(${200+400*case} 220)'><circle r='175' fill='none' stroke='#283548'/>")
  for(layer in scene.layers){val path=StringBuilder();val data=layer.segments
   for(i in data.indices step 4)path.append(String.format(Locale.US,"M%.2f %.2fL%.2f %.2f ",data[i]*175,data[i+1]*175,data[i+2]*175,data[i+3]*175))
   val hue=(((PitchNames.midi(layer.hz)%12+12)%12)*30/360).toFloat();val color=String.format("#%06x",Color.HSBtoRGB(hue,.85f,1f) and 0xffffff)
   s.append("<path d='$path' fill='none' stroke='$color' stroke-width='6' opacity='${layer.strength*.35}' filter='url(#g)'/><path d='$path' fill='none' stroke='$color' stroke-width='1.7' opacity='${layer.strength}'/><path d='$path' fill='none' stroke='white' stroke-width='.5' opacity='${layer.strength}'/>")
  }
  s.append("</g><text x='${200+400*case}' y='425' fill='white' font-family='sans-serif' font-size='17' text-anchor='middle'>${titles[case]}</text>")
 }
 s.append("</svg>");File(args[1]).writeText(s.toString())
}

