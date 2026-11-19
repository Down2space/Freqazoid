package com.freqazoid.membrane

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.freqazoid.analysis.isCapturing
import kotlinx.coroutines.launch

/**
 * The single activity for all four modes. Capture policy:
 *  - the microphone opens only after an explicit Start tap (and permission);
 *  - leaving RESUMED stops capture; returning never restarts it on its own;
 *  - rotation/resize are handled in place (configChanges), and a genuine recreation while
 *    capturing (isChangingConfigurations) keeps the retained ViewModel's single recorder.
 */
class MainActivity : ComponentActivity() {
    private val vm: FreqazoidViewModel by viewModels()
    private lateinit var permission: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.controller.onPermissionResult(true, true)
                else vm.pendingStartAfterPermission = true
            } else {
                vm.controller.onPermissionResult(false, shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xff9fd8ff), onPrimary = Color(0xff04121c), surface = Color(0xff121620))) {
                FreqazoidApp(vm = vm, onStart = ::requestStart, onOpenAppSettings = ::openAppSettings)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.controller.status.collect { s ->
                    if (s.isCapturing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    private fun requestStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.controller.start()
        else { vm.controller.onPermissionRequested(); permission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        try { startActivity(intent) } catch (_: ActivityNotFoundException) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: ActivityNotFoundException) {}
        }
    }

    override fun onResume() {
        super.onResume()
        if (vm.pendingStartAfterPermission) { vm.pendingStartAfterPermission = false; vm.controller.onPermissionResult(true, true) }
    }

    override fun onPause() {
        // Leaving the app (or opening system settings) stops the microphone.
        if (!isChangingConfigurations) vm.controller.onHostPaused()
        super.onPause()
    }
}
