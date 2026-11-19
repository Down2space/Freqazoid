package com.freqazoid.analysis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

/** Android integration reference for Claude. Not wired into the baseline app; not Android-compiled here. */
class SharedAudioController(context:Context,private val scope:CoroutineScope) {
    sealed interface Status {
        data object Idle:Status
        data object Starting:Status
        data class Listening(val sampleRate:Int):Status
        data class Failure(val message:String):Status
    }
    private val context=context.applicationContext
    private val gate=Mutex()
    private var capture:Job?=null
    private val mutableFrames=MutableStateFlow<AnalysisSnapshot?>(null)
    private val mutableStatus=MutableStateFlow<Status>(Status.Idle)
    val frames:StateFlow<AnalysisSnapshot?> = mutableFrames.asStateFlow()
    val status:StateFlow<Status> = mutableStatus.asStateFlow()

    /** Call only after an explicit user action; permission must be handled by the host UI. */
    suspend fun start(settings:AnalysisSettings=AnalysisSettings(),hopSize:Int=2048) = gate.withLock {
        require(hopSize in 256..settings.fftSize)
        // A new recorder cannot open until the prior job has released its old recorder.
        capture?.cancelAndJoin();capture=null;mutableFrames.value=null
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            mutableStatus.value=Status.Failure("Microphone permission is required")
            return@withLock
        }
        mutableStatus.value=Status.Starting
        capture=scope.launch(Dispatchers.IO){captureLoop(settings,hopSize)}
    }
    suspend fun stop()=gate.withLock {
        capture?.cancelAndJoin();capture=null;mutableFrames.value=null;mutableStatus.value=Status.Idle
    }
    private suspend fun captureLoop(settings:AnalysisSettings,hop:Int) {
        var recorder:AudioRecord?=null
        try {
            for(rate in intArrayOf(48000,44100,16000)) {
                val minimum=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                if(minimum<=0)continue
                val candidate=try{AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum,settings.fftSize*4))}catch(_:IllegalArgumentException){continue}
                if(candidate.state==AudioRecord.STATE_INITIALIZED){recorder=candidate;break}
                candidate.release()
            }
            val mic=checkNotNull(recorder){"No supported microphone format"}
            val analyzer=UnifiedAudioAnalyzer(settings)
            val ring=ShortArray(settings.fftSize);val window=ShortArray(ring.size)
            val block=ShortArray(min(1024,hop))
            var head=0;var filled=0;var sinceAnalysis=0
            mic.startRecording()
            check(mic.recordingState==AudioRecord.RECORDSTATE_RECORDING){"Microphone unavailable"}
            mutableStatus.value=Status.Listening(mic.sampleRate)
            while(currentCoroutineContext().isActive){
                val count=mic.read(block,0,block.size,AudioRecord.READ_BLOCKING)
                check(count>0){"Microphone read failed ($count)"}
                val first=min(count,ring.size-head)
                System.arraycopy(block,0,ring,head,first)
                if(first<count)System.arraycopy(block,first,ring,0,count-first)
                head=(head+count)%ring.size;filled=min(ring.size,filled+count);sinceAnalysis+=count
                if(filled==ring.size && sinceAnalysis>=hop){
                    // Estimate capture-end time at read completion, not when a Canvas happens to draw.
                    // A release integration may refine this with AudioRecord hardware timestamps.
                    val estimatedEndNanos=System.nanoTime()
                    System.arraycopy(ring,head,window,0,ring.size-head)
                    if(head>0)System.arraycopy(ring,0,window,ring.size-head,head)
                    mutableFrames.value=analyzer.analyze(window,mic.sampleRate,estimatedEndNanos)
                    sinceAnalysis=0
                }
            }
        }catch(e:CancellationException){throw e}
        catch(e:SecurityException){mutableFrames.value=null;mutableStatus.value=Status.Failure("Microphone permission was revoked")}
        catch(e:Exception){mutableFrames.value=null;mutableStatus.value=Status.Failure(e.message ?: "Audio capture failed")}
        finally{recorder?.let{try{it.stop()}catch(_:IllegalStateException){};it.release()}}
    }
}

