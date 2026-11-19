package com.freqazoid.membrane

import kotlin.math.*

/** Integer-order J_n via its periodic integral; no unstable high-order power series. */
object Bessel {
    private const val POINTS = 256
    private val tau = DoubleArray(POINTS) { PI * it / POINTS }
    private val sinTau = DoubleArray(POINTS) { sin(tau[it]) }
    fun j(n: Int, x: Double): Double {
        require(n >= 0 && x.isFinite() && abs(x) <= 160)
        if (x == 0.0) return if (n == 0) 1.0 else 0.0
        // Periodic trapezoid on [-pi,pi], exploiting even symmetry.
        var sum = 0.5 * (1.0 + if (n % 2 == 0) 1.0 else -1.0)
        for (k in 1 until POINTS) sum += cos(n * tau[k] - x * sinTau[k])
        return sum / POINTS
    }
    fun zeros(n: Int, count: Int): DoubleArray {
        require(n in 0..24 && count in 1..32)
        val result = DoubleArray(count)
        var left = max(0.01, n.toDouble()); var fl = j(n, left); var found = 0
        while (found < count) {
            val right = left + PI / 8; val fr = j(n, right)
            if (fl * fr < 0) {
                var a = left; var b = right; var fa = fl
                repeat(44) {
                    val mid = (a + b) / 2; val fm = j(n, mid)
                    if (fa * fm <= 0) b = mid else { a = mid; fa = fm }
                }
                result[found++] = (a + b) / 2
            }
            left = right; fl = fr
        }
        return result
    }
}

data class MembraneConfig(
    val radiusMeters: Double = 0.10,
    val tensionNewtonsPerMeter: Double = 384.0,
    val densityKgPerSquareMeter: Double = 0.25,
    val qualityFactor: Double = 35.0,
    val driveRadius: Double = 0.37,
    val driveAngle: Double = 0.20,
    val maxAngularOrder: Int = 12,
    val radialModes: Int = 24,
    val gridSize: Int = 129,
    val visualGain: Double = 2.0
) {
    init {
        require(radiusMeters > 0 && radiusMeters.isFinite())
        require(tensionNewtonsPerMeter > 0 && tensionNewtonsPerMeter.isFinite())
        require(densityKgPerSquareMeter > 0 && densityKgPerSquareMeter.isFinite())
        require(qualityFactor in 2.0..200.0 && driveRadius in 0.0..0.98 && driveAngle.isFinite())
        require(maxAngularOrder in 0..24 && radialModes in 1..32)
        require(gridSize in 65..257 && gridSize % 2 == 1 && visualGain in 0.01..100.0)
    }
    val waveSpeed get() = sqrt(tensionNewtonsPerMeter / densityKgPerSquareMeter)
    fun resonance(alpha: Double) = alpha * waveSpeed / (2 * PI * radiusMeters)
}

data class Tone(val hz: Double, val amplitude: Double) {
    init { require(hz > 0 && hz.isFinite() && amplitude in 0.0..1.0) }
}
data class AudioFrame(val tones: List<Tone> = emptyList(), val rmsDbfs: Double = -120.0, val timestampNanos: Long = 0, val noiseLevel: Double = 0.0, val centroidHz: Double = 0.0)
data class Mode(val n: Int, val m: Int, val alpha: Double, val hz: Double, val coupling: Double, val norm: Double)
data class Response(val mode: Mode, val real: Double, val imag: Double) {
    val magnitude get() = hypot(real, imag)
}
data class LaserLayer(val hz: Double, val strength: Float, val segments: FloatArray, val modes: List<Mode>)
data class LaserScene(
    val layers: List<LaserLayer> = emptyList(), val rmsDbfs: Double = -120.0,
    val strongestHz: Double = 0.0, val maximumModeHz: Double = 0.0,
    val madeAtNanos: Long = 0
)

/** The model is single-owner: call update/surface on one worker coroutine only. */
class MembraneEngine(val config: MembraneConfig = MembraneConfig()) {
    val modes: List<Mode>
    private val size = config.gridSize
    private val count = size * size
    private val basis = object : LinkedHashMap<Mode, FloatArray>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Mode, FloatArray>?) = this.size > 24
    }
    private data class Driven(val hz: Double, val real: FloatArray, val imag: FloatArray)
    private var driven = emptyList<Driven>()
    init {
        val all = ArrayList<Mode>()
        for (n in 0..config.maxAngularOrder) {
            Bessel.zeros(n, config.radialModes).forEachIndexed { index, alpha ->
                val jNext = Bessel.j(n + 1, alpha)
                // Area-averaged squared eigenfunction; cos^2 angular average is 1/2 for n>0.
                val norm = jNext * jNext * if (n == 0) 1.0 else .5
                all += Mode(n, index + 1, alpha, config.resonance(alpha), Bessel.j(n, alpha * config.driveRadius), norm)
            }
        }
        modes = all.sortedBy { it.hz }
    }
    fun responses(tone: Tone): List<Response> {
        val f0 = modes.first().hz
        return modes.map { mode ->
            val a = mode.hz * mode.hz - tone.hz * tone.hz
            val b = mode.hz * tone.hz / config.qualityFactor
            val factor = tone.amplitude * mode.coupling / mode.norm * f0 * f0 / (a*a + b*b)
            Response(mode, factor * a, -factor * b)
        }.sortedByDescending { it.magnitude }.take(3)
    }
    fun eigenfunction(mode: Mode, x: Double, y: Double): Double {
        val r = hypot(x,y)
        if(r >= 1) return 0.0
        return Bessel.j(mode.n, mode.alpha*r) * cos(mode.n*(atan2(y,x)-config.driveAngle))
    }
    /** Exact equation in physical seconds, for a single normalized eigenmode. */
    fun displacement(mode: Mode, x: Double, y: Double, seconds: Double): Double =
        eigenfunction(mode,x,y) * cos(2*PI*mode.hz*seconds)

    private fun sampled(mode: Mode): FloatArray = basis.getOrPut(mode) {
        val radial = FloatArray(1025) { i -> if(i == 1024) 0f else Bessel.j(mode.n,mode.alpha*i/1024).toFloat() }
        FloatArray(count) { index ->
            val x = 2.0*(index%size)/(size-1)-1; val y = 2.0*(index/size)/(size-1)-1; val r = hypot(x,y)
            if(r >= 1) 0f else {
                val q = r*1024; val k = q.toInt(); val v = radial[k]+(radial[k+1]-radial[k])*(q-k)
                (v*cos(mode.n*(atan2(y,x)-config.driveAngle))).toFloat()
            }
        }
    }
    fun update(frame: AudioFrame): LaserScene {
        val layers = ArrayList<LaserLayer>(); val fields = ArrayList<Driven>()
        // Do not snap unsupported high frequencies down into the truncated model bank.
        val tones = frame.tones.filter { it.hz <= modes.last().hz && it.amplitude > .001 }
            .sortedByDescending { it.amplitude }.take(4)
        for(tone in tones) {
            val responses = responses(tone); val real = FloatArray(count); val imag = FloatArray(count)
            for(response in responses) {
                val phi = sampled(response.mode)
                for(i in 0 until count) { real[i] += (phi[i]*response.real).toFloat(); imag[i] += (phi[i]*response.imag).toFloat() }
            }
            fields += Driven(tone.hz,real,imag)
            // Freeze the carrier at the phase of its strongest modal response. This keeps a
            // single-mode nodal pattern stationary and removes arbitrary microphone phase.
            val referencePhase = atan2(responses.first().imag,responses.first().real)
            val c = cos(referencePhase); val s = sin(referencePhase)
            val field = FloatArray(count) { (real[it]*c+imag[it]*s).toFloat() }
            var power = 0.0
            for(i in 0 until count) power += real[i].toDouble()*real[i]+imag[i].toDouble()*imag[i]
            val strength = (1-exp(-sqrt(power/count)*config.visualGain)).toFloat()
            if(strength > .003f) layers += LaserLayer(tone.hz,strength,Contours.zero(field,size),responses.map { it.mode })
        }
        driven = fields
        return LaserScene(layers,frame.rmsDbfs,tones.firstOrNull()?.hz ?: 0.0,modes.last().hz,frame.timestampNanos)
    }
    /** Oscillating surface at real physical t; caller may deliberately slow the display clock. */
    fun surface(seconds: Double): FloatArray {
        val result=FloatArray(count)
        for(field in driven) {
            val phase=2*PI*((field.hz*seconds)%1.0);val c=cos(phase);val s=sin(phase)
            for(i in 0 until count) result[i] += (field.real[i]*c-field.imag[i]*s).toFloat()
        }
        return result
    }
}

/** Linear marching squares; bilinear asymptotic decider for saddle cells. */
object Contours {
    fun zero(field: FloatArray, size: Int): FloatArray {
        require(field.size == size*size && size>=2)
        var out=FloatArray(2048);var used=0
        val v=DoubleArray(4);val edges=IntArray(4);val xy=FloatArray(4)
        val ea=intArrayOf(0,1,3,0);val eb=intArrayOf(1,2,2,3)
        fun point(x:Int,y:Int,e:Int,offset:Int) {
            val a=ea[e];val b=eb[e];val den=v[a]-v[b]
            val t=if(abs(den)<1e-30) .5 else (v[a]/den).coerceIn(0.0,1.0)
            val px=when(e){0,2->x+t;1->x+1.0;else->x.toDouble()}
            val py=when(e){0->y.toDouble();2->y+1.0;else->y+t}
            xy[offset]=(px/(size-1)*2-1).toFloat();xy[offset+1]=(py/(size-1)*2-1).toFloat()
        }
        fun segment(x:Int,y:Int,a:Int,b:Int){
            point(x,y,a,0);point(x,y,b,2)
            if(hypot(xy[0],xy[1])>.988f || hypot(xy[2],xy[3])>.988f)return
            if(used+4>out.size)out=out.copyOf(out.size*2)
            for(i in 0..3)out[used++]=xy[i]
        }
        for(y in 0 until size-1)for(x in 0 until size-1){
            v[0]=field[y*size+x].toDouble();v[1]=field[y*size+x+1].toDouble()
            v[2]=field[(y+1)*size+x+1].toDouble();v[3]=field[(y+1)*size+x].toDouble()
            if(abs(v[0])+abs(v[1])+abs(v[2])+abs(v[3])<1e-16)continue
            var num=0
            for(e in 0..3)if((v[ea[e]]>=0)!=(v[eb[e]]>=0))edges[num++]=e
            if(num==2)segment(x,y,edges[0],edges[1])
            else if(num==4){
                if(v[0]*v[2]-v[1]*v[3]>=0){segment(x,y,0,1);segment(x,y,2,3)}
                else{segment(x,y,0,3);segment(x,y,1,2)}
            }
        }
        return out.copyOf(used)
    }
}

