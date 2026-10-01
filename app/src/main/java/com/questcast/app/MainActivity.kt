package com.questcast.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.questcast.app.service.CastService
import com.questcast.app.ui.MainScreen
import com.questcast.app.util.NetworkUtils

class MainActivity : ComponentActivity() {

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCastService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Continue regardless of notification permission grant
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        setContent {
            val diagnostics by CastService.diagnostics.collectAsState()
            MainScreen(
                diagnostics = diagnostics.copy(
                    ipAddress = if (diagnostics.ipAddress == "0.0.0.0") NetworkUtils.getLocalIpAddress() else diagnostics.ipAddress
                ),
                onStartCasting = { requestScreenCapture() },
                onStopCasting = { stopCastService() }
            )
        }
    }

    private fun requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestScreenCapture() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mediaProjectionManager != null) {
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            mediaProjectionLauncher.launch(captureIntent)
        } else {
            Toast.makeText(this, "MediaProjection service not available on this device", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCastService(resultCode: Int, resultData: Intent) {
        val intent = Intent(this, CastService::class.java).apply {
            action = CastService.ACTION_START
            putExtra(CastService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CastService.EXTRA_RESULT_DATA, resultData)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopCastService() {
        val intent = Intent(this, CastService::class.java).apply {
            action = CastService.ACTION_STOP
        }
        startService(intent)
    }
}
