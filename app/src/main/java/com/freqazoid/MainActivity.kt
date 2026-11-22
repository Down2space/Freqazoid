package com.freqazoid

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager

/**
 * Freqazoid: a Chromatic Resonance watch face for the Galaxy Watch6 (runs on phones for testing).
 * First screen: Live mic or Song on watch → then the seven-octave wheel / overlay.
 */
class MainActivity : Activity() {
    private val processor = Processor(Renderer.SAMPLE_RATE)
    private lateinit var engine: AudioEngine
    private lateinit var view: FreqazoidView

    private companion object { const val REQ_MIC = 1; const val REQ_SONGS = 2 }
    private val songPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = AudioEngine(this, processor)
        view = FreqazoidView(this, processor) { onSourceRequest(it) }
        engine.onStopped = { msg -> runOnUiThread { engine.stop(); keepAwake(false); view.toast(msg) } }
        setContentView(view)
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun onSourceRequest(s: Source) {
        when (s) {
            Source.MIC -> if (granted(Manifest.permission.RECORD_AUDIO)) begin(Source.MIC) else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            Source.SONG -> if (granted(songPermission)) pickSong() else requestPermissions(arrayOf(songPermission), REQ_SONGS)
            else -> begin(s)
        }
    }

    private fun begin(s: Source, song: Uri? = null) {
        engine.start(s, song); keepAwake(s != Source.OFF); view.sourceChanged(s)
    }

    private class Song(val uri: Uri, val label: String)

    /** Lists the audio files stored on this watch (music sent with Galaxy Wearable, recordings, …). */
    private fun pickSong() {
        val songs = ArrayList<Song>()
        val cols = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION)
        try {
            contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, "${MediaStore.Audio.Media.DURATION} >= 3000", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val t = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val a = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                while (c.moveToNext()) {
                    val artist = c.getString(a)?.takeUnless { it.isBlank() || it == "<unknown>" }
                    songs += Song(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(id)),
                        (c.getString(t) ?: "Untitled") + (artist?.let { " · $it" } ?: ""))
                }
            }
        } catch (e: Exception) { view.toast("Can't read songs"); return }
        if (songs.isEmpty()) { view.toast("No songs on this watch\nAdd music in Galaxy Wearable"); return }
        AlertDialog.Builder(this)
            .setTitle("Pick a song")
            .setItems(songs.map { it.label }.toTypedArray()) { _, i -> begin(Source.SONG, songs[i].uri); view.toast(songs[i].label.take(28)) }
            .setNegativeButton("Back", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_MIC -> if (ok) begin(Source.MIC) else view.toast("Mic permission denied\nTap Live mic to retry")
            REQ_SONGS -> if (ok) pickSong() else view.toast("Music permission denied\nTap Song to retry")
        }
    }

    private fun keepAwake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        // Leaving the app stops the sound and returns to the first screen; nothing restarts by itself.
        if (processor.source != Source.OFF) { engine.stop(); keepAwake(false); view.invalidate() }
    }
}
