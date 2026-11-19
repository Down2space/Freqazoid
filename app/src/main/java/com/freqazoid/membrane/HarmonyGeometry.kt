package com.freqazoid.membrane
import kotlin.math.*

data class RatioMatch(val upper:Int,val lower:Int,val errorCents:Double){val label get()="$upper:$lower"}
data class DepthCurve(val xyz:FloatArray,val frequency:Double,val strength:Float)
data class HarmonyScene(val curves:List<DepthCurve> = emptyList(),val strongestHz:Double=0.0,val rmsDbfs:Double=-120.0,val ratio:RatioMatch?=null,val referenceOnly:Boolean=false)

/** Actual measured ratios remain in the geometry; rational approximations only set the view window. */
object HarmonyGeometry {
    fun ratio(low:Double,high:Double):RatioMatch {
        require(low>0 && high>=low)
        val r=high/low
        val candidates=ArrayList<RatioMatch>()
        for(q in 1..8){val p=round(r*q).toInt();if(p !in q..32)continue
            val error=1200*ln(r/(p.toDouble()/q))/ln(2.0)
            candidates+=RatioMatch(p,q,error)
        }
        // Prefer the simplest fraction within 12 cents; otherwise choose closest without snapping.
        return candidates.filter{abs(it.errorCents)<=12}.minByOrNull{it.upper+it.lower}
            ?: candidates.minByOrNull{abs(it.errorCents)} ?: RatioMatch(round(r).toInt().coerceAtLeast(1),1,1200*ln(r/round(r).coerceAtLeast(1.0))/ln(2.0))
    }
    fun curve(low:Double,high:Double,amplitudeLow:Double,amplitudeHigh:Double,seconds:Double,samples:Int=769):FloatArray {
        require(low>0 && high>=low && samples>=3)
        val window=min(ratio(low,high).lower.toDouble(),4096.0/(32*high/low))
        val resolvedSamples=max(samples,ceil(high/low*window*32).toInt()+1).coerceAtMost(4097)
        // A common display clock slows all acoustic frequencies equally; it preserves their ratio.
        val offset=seconds*min(1.0,1.5/high)
        val balance=max(amplitudeLow,amplitudeHigh).coerceAtLeast(1e-9)
        val ax=.4+.6*sqrt(amplitudeLow/balance);val ay=.4+.6*sqrt(amplitudeHigh/balance)
        return FloatArray(resolvedSamples*3).also{out->
            for(i in 0 until resolvedSamples){val t=i.toDouble()/(resolvedSamples-1)*window/low+offset
                val phaseLow=2*PI*((low*t)%1);val phaseHigh=2*PI*((high*t)%1)
                out[3*i]=(ax*sin(phaseLow)).toFloat()
                out[3*i+1]=(ay*cos(phaseHigh)).toFloat()
                out[3*i+2]=(.42*sin(phaseHigh-phaseLow)).toFloat()
            }
        }
    }
    /** Tilt and perspective use the third coordinate; radial shading uses sqrt(x²+y²+z²). */
    fun project(x:Double,y:Double,z:Double):Triple<Float,Float,Float>{
        val tilt=.48;val yy=y*cos(tilt)-z*sin(tilt);val zz=y*sin(tilt)+z*cos(tilt)
        val scale=1.65/(2.65-zz)
        val distance=sqrt(x*x+y*y+z*z)
        return Triple((x*scale).toFloat(),(yy*scale).toFloat(),(zz*.35+.55+.08*(1-distance/1.5)).toFloat().coerceIn(.12f,1f))
    }
    fun scene(frame:AudioFrame,seconds:Double):HarmonyScene {
        if(frame.timestampNanos==0L)return HarmonyScene()
        val tones=frame.tones.filter{it.amplitude>.001}.sortedByDescending{it.amplitude}.take(5)
        if(tones.isEmpty())return HarmonyScene(rmsDbfs=frame.rmsDbfs)
        val dominant=tones.first();val input=if(tones.size==1)listOf(Tone(110.0,dominant.amplitude),dominant)else tones
        val root=if(tones.size==1)input.minBy{it.hz}else input.first();val others=input.filter{it!==root}.ifEmpty{listOf(root)}
        val curves=others.take(4).map{other->
            val low=min(root.hz,other.hz);val high=max(root.hz,other.hz)
            val a=if(root.hz<=other.hz)root.amplitude else other.amplitude;val b=if(root.hz<=other.hz)other.amplitude else root.amplitude
            DepthCurve(curve(low,high,a,b,seconds),if(tones.size==1)dominant.hz else other.hz,(1-exp(-6*sqrt(a*a+b*b))).toFloat())
        }
        val strongestPair=tones.take(2).sortedBy{it.hz}
        val match=if(strongestPair.size==2)ratio(strongestPair[0].hz,strongestPair[1].hz)else ratio(min(110.0,dominant.hz),max(110.0,dominant.hz))
        return HarmonyScene(curves,dominant.hz,frame.rmsDbfs,match,tones.size==1)
    }
}

