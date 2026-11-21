package com.freqazoid

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager

/** Freqazoid: a Chromatic Resonance watch face (seven-octave wheel + overlay) for the Galaxy Watch6; runs on phones for testing. */
class MainActivity : Activity() {
    private val processor = Processor(Renderer.SAMPLE_RATE)
    private val engine = AudioEngine(processor)
    private lateinit var view: FreqazoidView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = FreqazoidView(this, processor) { setSource(it) }
        engine.onError = { msg -> runOnUiThread { engine.stop(); view.toast(msg); keepAwake(false) } }
        setContentView(view)
        view.toast("Tap: wheel / overlay\nHold: demo / mic")
    }

    private fun setSource(s: Source) {
        if (s == Source.MIC && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        engine.start(s); keepAwake(s != Source.OFF); view.sourceChanged(s)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) setSource(Source.MIC)
        else { engine.stop(); keepAwake(false); view.toast("Mic permission denied\nHold again to retry") }
    }

    private fun keepAwake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        if (processor.source != Source.OFF) { engine.stop(); keepAwake(false); view.invalidate() }   // leaving stops sound; nothing restarts by itself
    }
}
