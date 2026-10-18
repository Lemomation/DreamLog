package com.lemomation.dreamlog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lemomation.dreamlog.alarm.AlarmIntents
import com.lemomation.dreamlog.alarm.AlarmRuntime
import com.lemomation.dreamlog.ui.DreamLogApp
import com.lemomation.dreamlog.ui.theme.DreamLogTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DreamLogViewModel by viewModels()

    private var alarmRequested by mutableStateOf(false)
    private var alarmActive by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Results are intentionally non-blocking. The alarm capture UI checks the microphone
        // permission at the moment the user starts recording.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val launchedForAlarm = intent?.let { incoming ->
            incoming.getBooleanExtra(AlarmIntents.EXTRA_HOSTAGE_ALARM, false) ||
                incoming.action == AlarmIntents.ACTION_OPEN_HOSTAGE
        } ?: false
        alarmRequested = launchedForAlarm
        alarmActive = launchedForAlarm || AlarmRuntime.isRunning.value
        setAlarmWindow(alarmActive)

        setContent {
            DreamLogTheme {
                DreamLogApp(
                    viewModel = viewModel,
                    startWithAlarm = alarmActive,
                    alarmRequested = alarmRequested,
                    onPrepareAlarmWindow = {
                        alarmActive = true
                        setAlarmWindow(true)
                    },
                    onAlarmFinished = {
                        alarmActive = false
                        alarmRequested = false
                        setAlarmWindow(false)
                    },
                )
            }
        }

        if (!alarmActive) {
            requestStartupPermissions()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val openAlarm = intent.getBooleanExtra(AlarmIntents.EXTRA_HOSTAGE_ALARM, false) ||
            intent.action == AlarmIntents.ACTION_OPEN_HOSTAGE
        if (openAlarm) {
            alarmRequested = true
            alarmActive = true
            setAlarmWindow(true)
        }
    }

    private fun requestStartupPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun setAlarmWindow(active: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(active)
            setTurnScreenOn(active)
        }
        if (active) {
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
        } else {
            window.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}
