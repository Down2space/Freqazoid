package com.freqazoid.membrane

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.*

private data class Packet(val scene:LaserScene=LaserScene(),val seconds:Float=0f)
private data class PreparedLayer(val path:Path,val segments:FloatArray,val color:Color,val strength:Float)

/**
 * Display-paced animation, targeting 60 fps. Actual fps depends on the device.
 * The caller must set active=false whenever the host is not RESUMED.
 * Live FFT peaks drive vector graphs traced from the supplied reference-note chart.
 */
@Composable
fun CymaticsLaserCanvas(
    audio:StateFlow<AudioFrame>,
    modifier:Modifier=Modifier,
    active:Boolean=true,
    onScene:(LaserScene)->Unit={}
) {
    val context=LocalContext.current
    val model by produceState<NoteShapeEngine?>(null){value=withContext(Dispatchers.IO){NoteShapeEngine(NoteAtlas(context.assets.open("note-atlas.bin")))}}
    var packet by remember(){mutableStateOf(Packet())}
    val callback by rememberUpdatedState(onScene)
    LaunchedEffect(model,active,audio) {
        val engine=model ?: return@LaunchedEffect
        if(!active){packet=Packet();callback(LaserScene());return@LaunchedEffect}
        var lastNanos=0L;var lastInput:AudioFrame?=null;var lastModel=0L;var start=0L
        var scene=LaserScene()
        while(isActive) {
            val now=withFrameNanos{it};if(start==0L)start=now
            if(now-lastNanos<16_000_000L)continue
            lastNanos=now;val seconds=(now-start)/1e9
            val input=audio.value
            // Silence stale microphone data; this also makes Stop fade immediately to no drive.
            val fresh=if(input.timestampNanos==0L || System.nanoTime()-input.timestampNanos>600_000_000L)AudioFrame() else input
            if((fresh!==lastInput || fresh.tones.isEmpty()) && now-lastModel>66_000_000L) {
                scene=withContext(Dispatchers.Default){engine.update(fresh)};lastInput=fresh;lastModel=now;callback(scene)
            }
            packet=Packet(scene,seconds.toFloat())
        }
    }
    val layers=remember(packet.scene){packet.scene.layers.map{layer->
        val path=Path();val data=layer.segments
        for(i in data.indices step 4){path.moveTo(data[i],data[i+1]);path.lineTo(data[i+2],data[i+3])}
        val hue=(((PitchNames.midi(layer.hz)%12+12)%12)*30).toFloat()
        PreparedLayer(path,data,Color.hsv(hue,.85f,1f),layer.strength)
    }}
    BoxWithConstraints(modifier.fillMaxSize(),contentAlignment=Alignment.Center){
        val diameter=minOf(maxWidth,maxHeight)
        Box(Modifier.size(diameter),contentAlignment=Alignment.Center){
            Canvas(Modifier.fillMaxSize().semantics{contentDescription="Reference-note laser morphing. ${packet.scene.layers.size} frequency layers. Strongest input ${packet.scene.strongestHz.toInt()} hertz."}){
                val radius=min(size.width,size.height)/2f
                drawCircle(Color(0xff05060b),radius,center)
                val clip=Path().apply{addOval(androidx.compose.ui.geometry.Rect(center-Offset(radius,radius),center+Offset(radius,radius)))}
                clipPath(clip){
                    translate(center.x,center.y){scale(radius,radius,pivot=Offset.Zero){
                        for(layer in layers){
                            val intensity=layer.strength.coerceIn(0f,1f)
                            drawPath(layer.path,layer.color.copy(alpha=.08f*intensity),style=Stroke(8/radius,cap=StrokeCap.Round),blendMode=BlendMode.Plus)
                            drawPath(layer.path,layer.color.copy(alpha=.5f*intensity),style=Stroke(3/radius,cap=StrokeCap.Round),blendMode=BlendMode.Plus)
                            drawPath(layer.path,Color.White.copy(alpha=.85f*intensity),style=Stroke(.8f/radius,cap=StrokeCap.Round),blendMode=BlendMode.Plus)
                            // A moving scan-head is a rendering effect; its path is the computed contour.
                            if(layer.segments.size>=4){val index=((packet.seconds*.25f%1)* (layer.segments.size/4)).toInt()*4
                                drawCircle(layer.color.copy(alpha=intensity),2.5f/radius,Offset(layer.segments[index],layer.segments[index+1]),blendMode=BlendMode.Plus)}
                        }
                    }}
                }
                val rim=layers.maxOfOrNull{it.strength} ?: 0f
                drawCircle(Color(0xff61e6ff).copy(alpha=.10f+.65f*rim),radius-1,center,style=Stroke(1.5f))
            }
        }
    }
}

