package com.lemomation.dreamlog.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lemomation.dreamlog.DreamLogApplication
import com.lemomation.dreamlog.MainActivity
import com.lemomation.dreamlog.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale

data class CaptureResult(
    val filePath: String?,
    val durationMs: Long,
    val transcript: String,
    val generation: Int,
)

data class CaptureUiState(
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L,
    val transcript: String = "",
    val error: String? = null,
    val generation: Int = 0,
    val lastResult: CaptureResult? = null,
)

/**
 * Process-local mirror of the alarm service. Compose reads these state flows; commands travel to
 * the foreground service through explicit intents. This keeps the alarm alive independently of
 * any activity recreation.
 */
object AlarmRuntime {
    val isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    val volumePercent = kotlinx.coroutines.flow.MutableStateFlow(100)
    val isPlaying = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isPrepared = kotlinx.coroutines.flow.MutableStateFlow(false)
    val capture = kotlinx.coroutines.flow.MutableStateFlow(CaptureUiState())
    val watchdogEscalations = kotlinx.coroutines.flow.MutableStateFlow(0)
}

object AlarmIntents {
    const val ACTION_OPEN_HOSTAGE = "com.lemomation.dreamlog.action.OPEN_HOSTAGE_ALARM"
    const val EXTRA_HOSTAGE_ALARM = "com.lemomation.dreamlog.extra.HOSTAGE_ALARM"
}

object AlarmCommands {
    const val ACTION_START = "com.lemomation.dreamlog.alarm.START"
    const val ACTION_SET_VOLUME = "com.lemomation.dreamlog.alarm.SET_VOLUME"
    const val ACTION_START_CAPTURE = "com.lemomation.dreamlog.alarm.START_CAPTURE"
    const val ACTION_STOP_CAPTURE = "com.lemomation.dreamlog.alarm.STOP_CAPTURE"
    const val ACTION_ARM_WATCHDOG = "com.lemomation.dreamlog.alarm.ARM_WATCHDOG"
    const val ACTION_RESET_WATCHDOG = "com.lemomation.dreamlog.alarm.RESET_WATCHDOG"
    const val ACTION_CANCEL_WATCHDOG = "com.lemomation.dreamlog.alarm.CANCEL_WATCHDOG"
    const val ACTION_STOP = "com.lemomation.dreamlog.alarm.STOP"

    const val EXTRA_VOLUME_PERCENT = "volume_percent"
    const val EXTRA_WATCHDOG_MS = "watchdog_ms"
    const val EXTRA_DELETE_AUDIO = "delete_audio"

    fun start(context: Context) {
        val intent = Intent(context, AlarmForegroundService::class.java)
            .setAction(ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun setVolume(context: Context, percent: Int) {
        send(context, ACTION_SET_VOLUME) { putExtra(EXTRA_VOLUME_PERCENT, percent) }
    }

    fun startCapture(context: Context) {
        send(context, ACTION_START_CAPTURE)
    }

    fun stopCapture(context: Context) {
        send(context, ACTION_STOP_CAPTURE)
    }

    fun armWatchdog(context: Context, timeoutMs: Long) {
        send(context, ACTION_ARM_WATCHDOG) { putExtra(EXTRA_WATCHDOG_MS, timeoutMs) }
    }

    fun resetWatchdog(context: Context, timeoutMs: Long) {
        send(context, ACTION_RESET_WATCHDOG) { putExtra(EXTRA_WATCHDOG_MS, timeoutMs) }
    }

    fun cancelWatchdog(context: Context) {
        send(context, ACTION_CANCEL_WATCHDOG)
    }

    fun stop(context: Context, deleteAudio: Boolean) {
        send(context, ACTION_STOP) { putExtra(EXTRA_DELETE_AUDIO, deleteAudio) }
    }

    private inline fun send(
        context: Context,
        action: String,
        configure: Intent.() -> Unit = {},
    ) {
        val intent = Intent(context, AlarmForegroundService::class.java)
            .setAction(action)
            .apply(configure)
        try {
            context.startService(intent)
        } catch (_: IllegalStateException) {
            // The service already left; a pending alarm launch is handled by the UI.
        }
    }
}

object AlarmScheduler {
    private const val REQUEST_CODE = 7301
    private const val SHOW_REQUEST_CODE = 7302

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
    }

    fun schedule(context: Context, triggerAtMillis: Long): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        if (!canScheduleExact(context)) return false
        if (triggerAtMillis <= System.currentTimeMillis()) return false

        val alarmIntent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_TRIGGER)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val showIntent = PendingIntent.getActivity(
            context,
            SHOW_REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return try {
            manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
                pendingIntent,
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TRIGGER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.cancel(pendingIntent)
        pendingIntent.cancel()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TRIGGER) return
        val serviceIntent = Intent(context, AlarmForegroundService::class.java)
            .setAction(AlarmCommands.ACTION_START)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (_: Exception) {
            // AlarmManager will still have woken the device. The full-screen notification is
            // attached by the service; if the OS rejects the start there is no recovery here.
        }
    }

    companion object {
        const val ACTION_TRIGGER = "com.lemomation.dreamlog.action.TRIGGER_ALARM"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val application = context.applicationContext as DreamLogApplication
                val config = application.database.alarmConfigDao().get() ?: return@launch
                if (config.scheduledAt > System.currentTimeMillis()) {
                    AlarmScheduler.schedule(context, config.scheduledAt)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class AlarmForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var prepared = false
    private var desiredVolumePercent = 100
    private var foregroundStarted = false
    private var foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    private var audioManager: AudioManagerHolder? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var coordinator: DualCaptureCoordinator? = null
    private var captureTicker: Job? = null
    private var watchdog: Job? = null
    private var releaseSafety: Job? = null
    private var captureGeneration = maxOf(
        AlarmRuntime.capture.value.generation,
        AlarmRuntime.capture.value.lastResult?.generation ?: 0,
    )

    override fun onCreate() {
        super.onCreate()
        AlarmRuntime.isRunning.value = true
        // A new service instance is a new alarm session. Do not let a result from the previous
        // session suppress the next capture generation in the UI.
        AlarmRuntime.capture.value = CaptureUiState()
        createNotificationChannel()
        acquireWakeLock()
        configureAudio()
        configureVibrator()
        preparePlayer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: AlarmCommands.ACTION_START
        ensureForeground(foregroundType)

        when (action) {
            AlarmCommands.ACTION_START -> {
                ensureForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                startAlarmPlayback()
            }

            AlarmCommands.ACTION_SET_VOLUME -> {
                val percent = intent?.getIntExtra(AlarmCommands.EXTRA_VOLUME_PERCENT, 100) ?: 100
                setAlarmVolume(percent)
            }

            AlarmCommands.ACTION_START_CAPTURE -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    ensureForeground(
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                    )
                    startCapture()
                } else {
                    AlarmRuntime.capture.update {
                        it.copy(
                            isRecording = false,
                            error = "Microphone permission is not granted.",
                        )
                    }
                }
            }

            AlarmCommands.ACTION_STOP_CAPTURE -> stopCapture()

            AlarmCommands.ACTION_ARM_WATCHDOG -> {
                val timeout = intent?.getLongExtra(AlarmCommands.EXTRA_WATCHDOG_MS, 12_000L)
                    ?: 12_000L
                armWatchdog(timeout)
            }

            AlarmCommands.ACTION_RESET_WATCHDOG -> {
                val timeout = intent?.getLongExtra(AlarmCommands.EXTRA_WATCHDOG_MS, 12_000L)
                    ?: 12_000L
                if (watchdog?.isActive == true) armWatchdog(timeout)
            }

            AlarmCommands.ACTION_CANCEL_WATCHDOG -> cancelWatchdog()

            AlarmCommands.ACTION_STOP -> {
                val deleteAudio = intent?.getBooleanExtra(AlarmCommands.EXTRA_DELETE_AUDIO, false) ?: false
                shutdown(deleteAudio = deleteAudio)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cleanup()
        AlarmRuntime.isRunning.value = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureForeground(type: Int) {
        if (foregroundStarted && type == foregroundType) return
        foregroundType = type
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun startAlarmPlayback() {
        setSystemAlarmStreamToMax()
        desiredVolumePercent = 100
        setAlarmVolume(100)
    }

    private fun setAlarmVolume(percent: Int) {
        releaseSafety?.cancel()
        releaseSafety = null
        desiredVolumePercent = percent.coerceIn(0, 100)
        AlarmRuntime.volumePercent.value = desiredVolumePercent
        val mp = player
        if (!prepared || mp == null) return

        if (desiredVolumePercent <= 0) {
            mp.setVolume(0f, 0f)
            if (mp.isPlaying) mp.pause()
            AlarmRuntime.isPlaying.value = false
            stopContinuousVibration()
            return
        }

        val fraction = desiredVolumePercent / 100f
        mp.setVolume(fraction, fraction)
        if (!mp.isPlaying) {
            try {
                mp.start()
            } catch (_: IllegalStateException) {
                // The player may still be preparing; onPrepared will start it.
            }
        }
        AlarmRuntime.isPlaying.value = mp.isPlaying
        if (desiredVolumePercent >= 100) {
            startContinuousVibration()
        } else {
            stopContinuousVibration()
        }
    }

    private fun preparePlayer() {
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            resources.openRawResourceFd(R.raw.alarm).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener {
                prepared = true
                AlarmRuntime.isPrepared.value = true
                if (desiredVolumePercent > 0) {
                    setAlarmVolume(desiredVolumePercent)
                }
            }
            mp.setOnCompletionListener {
                if (mp.isLooping) {
                    try {
                        mp.start()
                    } catch (_: IllegalStateException) {
                        // Loop is best-effort; the service remains alive.
                    }
                }
            }
            mp.setOnErrorListener { _, _, _ ->
                AlarmRuntime.isPlaying.value = false
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            AlarmRuntime.isPrepared.value = false
        }
    }

    private fun configureAudio() {
        val manager = getSystemService(AudioManager::class.java)
        val previous = manager.getStreamVolume(AudioManager.STREAM_ALARM)
        audioManager = AudioManagerHolder(manager, previous)
    }

    private fun setSystemAlarmStreamToMax() {
        val holder = audioManager ?: return
        try {
            val max = holder.manager.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
            holder.manager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, max, 0)
        } catch (_: SecurityException) {
            // Do Not Disturb or device policy may prevent changing system volume. The app still
            // plays the alarm at the highest volume it is allowed to use.
        }
    }

    private fun restoreSystemAlarmVolume() {
        val holder = audioManager ?: return
        if (holder.previousVolume < 0) return
        try {
            holder.manager.setStreamVolume(
                android.media.AudioManager.STREAM_ALARM,
                holder.previousVolume,
                0,
            )
        } catch (_: SecurityException) {
            // Best effort.
        }
    }

    private fun configureVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun startContinuousVibration() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 750L, 350L), 0))
        } catch (_: Exception) {
            // Vibration is optional feedback.
        }
    }

    private fun stopContinuousVibration() {
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
            // Vibration is optional feedback.
        }
    }

    private fun startCapture() {
        if (coordinator?.isRecording == true) return
        cancelWatchdog()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            AlarmRuntime.capture.update {
                it.copy(error = "Microphone permission is not granted.")
            }
            return
        }

        setAlarmVolume(0)
        stopContinuousVibration()

        val generation = captureGeneration + 1
        captureGeneration = generation
        AlarmRuntime.capture.value = CaptureUiState(
            isRecording = true,
            elapsedMs = 0L,
            transcript = "",
            error = null,
            generation = generation,
            lastResult = AlarmRuntime.capture.value.lastResult,
        )

        val newCoordinator = DualCaptureCoordinator(
            context = this,
            onTranscript = { transcript ->
                AlarmRuntime.capture.update { current ->
                    if (current.generation == generation) {
                        current.copy(transcript = transcript)
                    } else {
                        current
                    }
                }
            },
            onRecognitionError = { message ->
                AlarmRuntime.capture.update { current ->
                    if (current.generation == generation) {
                        current.copy(error = message)
                    } else {
                        current
                    }
                }
            },
        )

        val started = newCoordinator.start()
        if (!started) {
            AlarmRuntime.capture.update {
                it.copy(
                    isRecording = false,
                    error = "Could not start the microphone.",
                )
            }
            setAlarmVolume(100)
            return
        }
        coordinator = newCoordinator

        val startElapsed = SystemClock.elapsedRealtime()
        captureTicker?.cancel()
        captureTicker = serviceScope.launch {
            var lastHapticBucket = 0L
            while (isActive) {
                val elapsed = SystemClock.elapsedRealtime() - startElapsed
                AlarmRuntime.capture.update { current ->
                    if (current.generation == generation) current.copy(elapsedMs = elapsed) else current
                }
                val bucket = elapsed / 4_000L
                if (bucket > lastHapticBucket) {
                    lastHapticBucket = bucket
                    if (bucket > 0L) hapticTick()
                }
                delay(100L)
            }
        }
    }

    private fun stopCapture() {
        captureTicker?.cancel()
        captureTicker = null
        val active = coordinator ?: return
        coordinator = null
        val rawResult = active.stop()
        val result = rawResult.copy(generation = captureGeneration)
        AlarmRuntime.capture.update { current ->
            current.copy(
                isRecording = false,
                elapsedMs = result.durationMs,
                transcript = result.transcript.ifBlank { current.transcript },
                lastResult = result,
            )
        }
        // If the UI process dies immediately after a capture, do not leave a silenced alarm.
        // A normal UI response sets the ducked volume long before this failsafe expires.
        releaseSafety?.cancel()
        releaseSafety = serviceScope.launch {
            delay(20_000L)
            setAlarmVolume(100)
        }
    }

    private fun hapticTick() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            v.vibrate(VibrationEffect.createOneShot(18L, 55))
        } catch (_: Exception) {
            // Optional feedback.
        }
    }

    private fun armWatchdog(timeoutMs: Long) {
        watchdog?.cancel()
        watchdog = serviceScope.launch {
            delay(timeoutMs.coerceIn(1_000L, 15L * 60_000L))
            setAlarmVolume(100)
            AlarmRuntime.watchdogEscalations.update { it + 1 }
        }
    }

    private fun cancelWatchdog() {
        watchdog?.cancel()
        watchdog = null
    }

    private fun shutdown(deleteAudio: Boolean) {
        val existingResult = AlarmRuntime.capture.value.lastResult
        if (coordinator?.isRecording == true) {
            val active = coordinator
            coordinator = null
            val result = active?.stop()
            if (deleteAudio) {
                result?.filePath?.let { runCatching { File(it).delete() } }
            }
        } else if (deleteAudio) {
            existingResult?.filePath?.let { runCatching { File(it).delete() } }
        }
        cleanup()
        AlarmRuntime.isRunning.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        captureTicker?.cancel()
        captureTicker = null
        watchdog?.cancel()
        watchdog = null
        releaseSafety?.cancel()
        releaseSafety = null
        coordinator?.let { active ->
            active.stop()
            if (AlarmRuntime.capture.value.lastResult == null) {
                // Nothing else to persist at shutdown.
            }
        }
        coordinator = null
        stopContinuousVibration()
        vibrator = null
        player?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
            } catch (_: Exception) {
                // Already released or in an invalid state.
            }
            try {
                mp.reset()
                mp.release()
            } catch (_: Exception) {
                // Already released.
            }
        }
        player = null
        prepared = false
        AlarmRuntime.isPlaying.value = false
        AlarmRuntime.isPrepared.value = false
        restoreSystemAlarmVolume()
        audioManager = null
        releaseWakeLock()
        foregroundStarted = false
    }

    private fun acquireWakeLock() {
        try {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DreamLog:HostageAlarm",
            ).apply {
                setReferenceCounted(false)
                acquire(4L * 60L * 60L * 1000L)
            }
        } catch (_: Exception) {
            wakeLock = null
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
            // Already released.
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.alarm_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.alarm_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = AlarmIntents.ACTION_OPEN_HOSTAGE
            putExtra(AlarmIntents.EXTRA_HOSTAGE_ALARM, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val fullScreenIntent = PendingIntent.getActivity(
            this,
            7310,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.alarm_notification_title))
            .setContentText(getString(R.string.alarm_notification_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private data class AudioManagerHolder(
        val manager: AudioManager,
        val previousVolume: Int,
    )

    companion object {
        const val NOTIFICATION_ID = 4101
        const val CHANNEL_ID = "dreamlog_alarm"
    }
}

/**
 * Owns the microphone and speech recognizer together. The recorder is the source of truth for the
 * unlock threshold; speech recognition runs alongside it and can fail without invalidating the
 * recording. Raw audio is written to app-private storage as an .m4a file.
 */
class DualCaptureCoordinator(
    private val context: Context,
    private val onTranscript: (String) -> Unit,
    private val onRecognitionError: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var recognizer: SpeechRecognizer? = null
    private var file: File? = null
    private var startedAtElapsed = 0L
    private var active = false
    private var stableTranscript = StringBuilder()
    private var partialTranscript = ""
    private var currentTranscript = ""

    val isRecording: Boolean get() = active

    fun start(): Boolean {
        if (active) return true
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val output = File(dir, "dream_${System.currentTimeMillis()}.m4a")

        val newRecorder = try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
        } catch (_: Exception) {
            return false
        }

        try {
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioEncodingBitRate(128_000)
            newRecorder.setAudioSamplingRate(44_100)
            newRecorder.setOutputFile(output.absolutePath)
            newRecorder.prepare()
            newRecorder.start()
        } catch (_: Exception) {
            runCatching { newRecorder.release() }
            runCatching { output.delete() }
            return false
        }

        recorder = newRecorder
        file = output
        startedAtElapsed = SystemClock.elapsedRealtime()
        active = true
        stableTranscript = StringBuilder()
        partialTranscript = ""
        currentTranscript = ""

        startSpeechRecognition()
        return true
    }

    fun stop(): CaptureResult {
        val wasActive = active
        active = false
        mainHandler.removeCallbacksAndMessages(null)
        stopSpeechRecognition()

        val duration = if (wasActive) {
            (SystemClock.elapsedRealtime() - startedAtElapsed).coerceAtLeast(0L)
        } else {
            0L
        }

        var outputPath: String? = null
        val outputFile = file
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // A stop() before any audio was captured throws. Duration and file checks decide
            // whether this capture is usable.
        }
        try {
            recorder?.reset()
            recorder?.release()
        } catch (_: Exception) {
            // Already released.
        }
        recorder = null
        file = null

        if (outputFile != null && outputFile.exists() && outputFile.length() > 0L) {
            outputPath = outputFile.absolutePath
        } else {
            outputFile?.delete()
        }

        val transcript = combineTranscript()
        currentTranscript = transcript
        return CaptureResult(
            filePath = outputPath,
            durationMs = if (outputPath != null) duration else 0L,
            transcript = transcript,
            generation = 0,
        )
    }

    private fun startSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onRecognitionError("Speech recognition is unavailable; audio is still recording.")
            return
        }

        mainHandler.postDelayed({
            if (!active) return@postDelayed
            try {
                val created = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                ) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
                recognizer = created
                created.setRecognitionListener(listener)
                created.startListening(recognitionIntent())
            } catch (_: Exception) {
                onRecognitionError("Speech recognition could not start; audio is still recording.")
            }
        }, 250L)
    }

    private fun recognitionIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4_000L)
        }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (!active) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> {
                    restartRecognition(delayMs = 350L)
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    onRecognitionError("Speech recognition permission is unavailable.")
                }

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_CLIENT,
                -> {
                    onRecognitionError("Live transcription paused; audio is still recording.")
                }

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                -> {
                    onRecognitionError("Offline transcription is unavailable on this device.")
                }

                else -> {
                    restartRecognition(delayMs = 750L)
                }
            }
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            if (!active) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotEmpty()) {
                partialTranscript = text
                publishTranscript()
            }
        }

        override fun onResults(results: android.os.Bundle?) {
            if (!active) return
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotEmpty() && text != partialTranscript) {
                if (stableTranscript.isNotEmpty()) stableTranscript.append(' ')
                stableTranscript.append(text)
                partialTranscript = ""
                publishTranscript()
            } else if (text.isNotEmpty()) {
                if (stableTranscript.isNotEmpty()) stableTranscript.append(' ')
                stableTranscript.append(text)
                partialTranscript = ""
                publishTranscript()
            }
            restartRecognition(delayMs = 250L)
        }

        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
    }

    private fun restartRecognition(delayMs: Long) {
        if (!active) return
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        mainHandler.postDelayed({
            if (!active) return@postDelayed
            startSpeechRecognition()
        }, delayMs)
    }

    private fun publishTranscript() {
        currentTranscript = combineTranscript()
        onTranscript(currentTranscript)
    }

    private fun combineTranscript(): String {
        val stable = stableTranscript.toString().trim()
        val partial = partialTranscript.trim()
        return when {
            stable.isEmpty() -> partial
            partial.isEmpty() -> stable
            else -> "$stable $partial"
        }.replace(Regex("\\s+"), " ").trim()
    }

    private fun stopSpeechRecognition() {
        val current = recognizer
        recognizer = null
        try {
            current?.stopListening()
            current?.cancel()
            current?.destroy()
        } catch (_: Exception) {
            // Recognition is best-effort.
        }
    }
}
