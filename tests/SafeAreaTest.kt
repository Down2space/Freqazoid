package com.freqazoid.membrane
fun main(){
 for(d in listOf(180f,192f,200f,216f,227f,240f,260f,320f,360f,411f)){
  val p=RoundSafeArea.plan(d)
  check(RoundSafeArea.fits(p.radius,0f,p.topCenterY,p.topWidth,p.controlHeight,4f)){"top chip clipped at $d"}
  check(RoundSafeArea.fits(p.radius,0f,p.bottomCenterY,p.bottomWidth,p.controlHeight,4f)){"bottom button clipped at $d"}
  check(p.topWidth>=48&&p.bottomWidth>=48&&p.controlHeight>=48)
  check(p.bandBottom-p.bandTop>=.55f*p.radius){"plot band too small at $d: ${p.bandBottom-p.bandTop}"}
  check(p.textWidth>=.9f*p.radius){"text width ${p.textWidth} at $d"}
  println("diameter %3.0f dp: top chip %5.1f wide at y=%6.1f, start %5.1f wide at y=%5.1f, free band %6.1f..%5.1f, text %5.1f".format(d,p.topWidth,p.topCenterY,p.bottomWidth,p.bottomCenterY,p.bandTop,p.bandBottom,p.textWidth))
 }
 println("PASS: all control corners inside the circle (4 dp margin), >=48 dp targets, 180-411 dp viewports")
}
