package com.freqazoid.membrane
import java.io.File
import java.util.Locale
import kotlin.math.ln
fun main(args:Array<String>){
 val model=MembraneEngine();val chosen=listOf(1 to 1,3 to 2,6 to 3)
 val sb=StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' width='1200' height='460' viewBox='0 0 1200 460'><defs><filter id='g'><feGaussianBlur stdDeviation='2.5'/></filter></defs><rect width='1200' height='460' fill='#05060b'/>")
 chosen.forEachIndexed{index,pair->
  val mode=model.modes.first{it.n==pair.first&&it.m==pair.second};val scene=model.update(AudioFrame(listOf(Tone(mode.hz,.04)),-31.0,1))
  val hue=(ln(mode.hz.coerceIn(30.0,6000.0)/30)/ln(6000.0/30)*285/360).toFloat()
  val rgb=java.awt.Color.HSBtoRGB(hue,.85f,1f) and 0xffffff
  val color=String.format("#%06x",rgb)
  sb.append("<g transform='translate(${200+400*index} 210)'><circle r='173' fill='none' stroke='$color' opacity='.5'/>")
  for(layer in scene.layers){val d=StringBuilder();val data=layer.segments
   for(i in data.indices step 4)d.append(String.format(Locale.US,"M%.2f %.2fL%.2f %.2f ",data[i]*172,data[i+1]*172,data[i+2]*172,data[i+3]*172))
   sb.append("<path d='$d' fill='none' stroke='$color' stroke-width='5' opacity='.5' filter='url(#g)'/><path d='$d' fill='none' stroke='$color' stroke-width='1.7'/><path d='$d' fill='none' stroke='white' stroke-width='.55'/>")
  }
  sb.append("</g><text x='${200+400*index}' y='416' fill='white' font-family='sans-serif' font-size='18' text-anchor='middle'>${String.format(Locale.US,"%.1f Hz",mode.hz)} · nearby mode (${mode.n},${mode.m})</text>")
 }
 sb.append("<text x='600' y='25' fill='#afbed2' font-family='sans-serif' font-size='14' text-anchor='middle'>Calculated circular membrane response · T = 384 N/m · σ = 0.25 kg/m² · R = 0.10 m</text></svg>")
 File(args[0]).writeText(sb.toString())
}

