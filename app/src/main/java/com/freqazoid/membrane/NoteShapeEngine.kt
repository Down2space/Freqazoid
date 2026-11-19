package com.freqazoid.membrane

import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.*

/** Vector graphs extracted from the supplied chart, with optimal transport between neighbors. */
class NoteAtlas(input:InputStream) {
    data class Glyph(val midi:Int,val points:FloatArray,val edges:IntArray)
    val glyphs:List<Glyph>
    val permutations:List<IntArray>
    val vertices:Int
    init {
        DataInputStream(input.buffered()).use { data ->
            require(data.readInt()==0x46515a41 && data.readInt()==1){"Invalid note atlas"}
            val count=data.readInt();vertices=data.readInt();require(count==57 && vertices in 32..4096)
            glyphs=List(count){
                val midi=data.readInt();val edges=data.readInt();require(edges in 1..20000)
                Glyph(midi,FloatArray(vertices*2){data.readFloat()},IntArray(edges*2){data.readInt()})
            }
            permutations=List(count-1){IntArray(vertices){data.readInt()}.also{require(it.toSet().size==vertices && it.all{v->v in 0 until vertices})}}
        }
        require(glyphs.zipWithNext().all{it.first.midi<it.second.midi})
    }
    /** Continuous pitch -> chart interval, with a 4-cent stability dead band. */
    fun interval(hz:Double):Pair<Int,Float> {
        var pitch=69+12*ln(hz/440)/ln(2.0)
        val nearest=round(pitch)
        if(abs(pitch-nearest)<=.04)pitch=nearest
        pitch=pitch.coerceIn(glyphs.first().midi.toDouble(),glyphs.last().midi.toDouble())
        val index=glyphs.indexOfLast{it.midi<=pitch}.coerceAtMost(glyphs.lastIndex-1)
        val t=((pitch-glyphs[index].midi)/(glyphs[index+1].midi-glyphs[index].midi)).toFloat()
        return index to t.coerceIn(0f,1f)
    }
    fun positions(index:Int,t:Float):FloatArray {
        val a=glyphs[index].points;val b=glyphs[index+1].points;val permutation=permutations[index]
        return FloatArray(a.size){i->a[i]*(1-t)+b[permutation[i/2]*2+i%2]*t}
    }
    fun segments(index:Int,t:Float,destination:Boolean=false,noise:Double=0.0,centroid:Double=0.0):FloatArray {
        val points=positions(index,t)
        if(noise>.05){
            val amount=((noise-.05)/.95).coerceIn(0.0,1.0)*.035
            for(i in points.indices step 2){val x=points[i];val y=points[i+1]
                points[i]+=(amount*sin(7*x+centroid*.001)*cos(5*y)).toFloat()
                points[i+1]+=(amount*cos(6*y+centroid*.0007)*sin(4*x)).toFloat()
            }
        }
        val edges=if(destination)glyphs[index+1].edges else glyphs[index].edges
        val inverse=if(destination)IntArray(vertices).also{out->permutations[index].forEachIndexed{i,v->out[v]=i}}else null
        return FloatArray(edges.size*2){i->val node=edges[i/2];val source=inverse?.get(node) ?: node;points[source*2+i%2]}
    }
}

object PitchNames {
    private val names=arrayOf("C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B")
    fun midi(hz:Double)=69+12*ln(hz/440)/ln(2.0)
    fun name(hz:Double):String {val m=round(midi(hz)).toInt();return names[Math.floorMod(m,12)]+(Math.floorDiv(m,12)-1)}
    fun cents(hz:Double)=(midi(hz)-round(midi(hz)))*100
}

/** Conservative harmonic grouping: a fundamental must itself be detected. */
object HarmonicGroups {
    fun combine(tones:List<Tone>):List<Tone> {
        val sorted=tones.filter{it.amplitude>0}.sortedBy{it.hz};val used=BooleanArray(sorted.size);val result=ArrayList<Tone>()
        for(i in sorted.indices){
            if(used[i])continue
            val base=sorted[i];var power=base.amplitude*base.amplitude;var weighted=base.hz*power;var matched=0
            for(j in i+1 until sorted.size){
                if(used[j])continue
                val k=round(sorted[j].hz/base.hz).toInt();if(k !in 2..8)continue
                val cents=1200*ln(sorted[j].hz/(base.hz*k))/ln(2.0)
                // Avoid a very weak stray low peak claiming unrelated stronger high notes.
                if(abs(cents)<22 && base.amplitude>=sorted[j].amplitude*.18){
                    val p=sorted[j].amplitude.pow(2);power+=p;weighted+=(sorted[j].hz/k)*p;used[j]=true;matched++
                }
            }
            used[i]=true
            result+=Tone(weighted/power,sqrt(power).coerceAtMost(1.0))
        }
        return result.sortedByDescending{it.amplitude}.take(8)
    }
}

/** Explicit sound-to-shape grammar, not an inferred physical plate simulation. */
class NoteShapeEngine(val atlas:NoteAtlas) {
    private data class Track(var hz:Double,var amplitude:Double,var missed:Double=0.0)
    private val tracks=ArrayList<Track>();private var previousStamp=0L
    fun update(frame:AudioFrame):LaserScene {
        if(frame.timestampNanos==0L){tracks.clear();previousStamp=0;return LaserScene()}
        val dt=if(previousStamp==0L).066 else ((frame.timestampNanos-previousStamp)/1e9).coerceIn(.005,.2)
        previousStamp=frame.timestampNanos
        val groups=(if(frame.noiseLevel>.15)frame.tones.sortedByDescending{it.amplitude}.take(8) else HarmonicGroups.combine(frame.tones)).filter{it.hz>=31.77 && it.hz<=8617.4}
        val available=tracks.toMutableList()
        for(group in groups){
            val match=available.minByOrNull{abs(1200*ln(it.hz/group.hz)/ln(2.0))}
            if(match!=null && abs(1200*ln(match.hz/group.hz)/ln(2.0))<140){
                val p=1-exp(-dt/.045);match.hz=exp(ln(match.hz)*(1-p)+ln(group.hz)*p)
                val a=1-exp(-dt/(if(group.amplitude>match.amplitude).045 else .16))
                match.amplitude+=(group.amplitude-match.amplitude)*a;match.missed=0.0;available.remove(match)
            }else tracks+=Track(group.hz,group.amplitude)
        }
        for(track in available){track.missed+=dt;track.amplitude*=exp(-dt/.16)}
        tracks.removeAll{it.missed>.7 || it.amplitude<.0002}
        tracks.sortByDescending{it.amplitude};while(tracks.size>8)tracks.removeAt(tracks.lastIndex)
        val total=tracks.sumOf{it.amplitude.pow(2)}
        val drive=(1-exp(-sqrt(total)*8)).toFloat()
        val layers=ArrayList<LaserLayer>()
        for(track in tracks){
            if(total<1e-10)continue
            val weight=(track.amplitude.pow(2)/total).pow(.65).toFloat()*drive
            val (index,t)=atlas.interval(track.hz)
            if(1-t>.005)layers+=LaserLayer(track.hz,weight*(1-t),atlas.segments(index,t,false,frame.noiseLevel,frame.centroidHz),emptyList())
            if(t>.005)layers+=LaserLayer(track.hz,weight*t,atlas.segments(index,t,true,frame.noiseLevel,frame.centroidHz),emptyList())
        }
        return LaserScene(layers,frame.rmsDbfs,tracks.firstOrNull()?.hz ?: 0.0,8372.02,frame.timestampNanos)
    }
}

