package com.lemomation.dreamlog.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lemomation.dreamlog.DreamLogViewModel
import com.lemomation.dreamlog.alarm.AlarmCommands
import com.lemomation.dreamlog.alarm.AlarmRuntime
import com.lemomation.dreamlog.alarm.CaptureResult
import com.lemomation.dreamlog.data.AlarmConfig
import com.lemomation.dreamlog.ui.components.FlatButton
import com.lemomation.dreamlog.ui.components.TextAction
import com.lemomation.dreamlog.ui.theme.DreamColors
import com.lemomation.dreamlog.util.Formats
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

private enum class HostagePhase { RINGING, VOICE, TEXT }

/**
 * The 3 AM state machine. There is deliberately no dismiss, no snooze, and no route back to the
 * normal app shell until a note is saved or the 15-second hold escape completes.
 */
@Composable
fun HostageAlarmScreen(
    config: AlarmConfig,
    viewModel: DreamLogViewModel,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    val capture by AlarmRuntime.capture.collectAsStateWithLifecycle()
    val watchdogEscalations by AlarmRuntime.watchdogEscalations.collectAsStateWithLifecycle()
    val alarmVolume by AlarmRuntime.volumePercent.collectAsStateWithLifecycle()
    val alarmPlaying by AlarmRuntime.isPlaying.collectAsStateWithLifecycle()

    var phase by rememberSaveable { mutableStateOf(HostagePhase.RINGING) }
    var bodyText by rememberSaveable { mutableStateOf("") }
    var manualBody by rememberSaveable { mutableStateOf(false) }
    var capturedAudioPath by rememberSaveable { mutableStateOf<String?>(null) }
    var capturedAudioDuration by rememberSaveable { mutableStateOf(0L) }
    var voiceUnlocked by rememberSaveable { mutableStateOf(false) }

    var voiceCountdownActive by rememberSaveable { mutableStateOf(false) }
    var voiceCountdown by rememberSaveable { mutableStateOf(10) }
    var voiceCountdownRun by rememberSaveable { mutableStateOf(0) }

    var typingRemaining by rememberSaveable { mutableStateOf(12) }
    var typingRun by rememberSaveable { mutableStateOf(0) }

    var handledResultGeneration by rememberSaveable { mutableStateOf(0) }
    var observedEscalations by rememberSaveable {
        mutableStateOf(AlarmRuntime.watchdogEscalations.value)
    }
    var permissionNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }

    var holdActive by remember { mutableStateOf(false) }
    var holdElapsedMs by remember { mutableStateOf(0L) }

    val wordCount = remember(bodyText) { countWords(bodyText) }
    val minVoiceMs = config.minVoiceSeconds * 1_000L
    val textUnlocked = phase == HostagePhase.TEXT && wordCount >= config.minWords
    val isUnlocked = voiceUnlocked || textUnlocked

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        keyboard?.hide()
        if (!AlarmRuntime.isRunning.value) {
            AlarmCommands.start(context)
        }
        if (AlarmRuntime.capture.value.lastResult != null) {
            handledResultGeneration = AlarmRuntime.capture.value.lastResult?.generation ?: 0
        }
    }

    LaunchedEffect(capture.isRecording) {
        if (capture.isRecording && phase == HostagePhase.RINGING) {
            phase = HostagePhase.VOICE
        }
    }

    LaunchedEffect(capture.isRecording, capture.transcript) {
        if (phase == HostagePhase.VOICE && capture.isRecording && !manualBody) {
            bodyText = capture.transcript
        }
    }

    LaunchedEffect(capture.lastResult, capture.isRecording) {
        val result: CaptureResult = capture.lastResult ?: return@LaunchedEffect
        if (capture.isRecording || result.generation <= handledResultGeneration) {
            return@LaunchedEffect
        }
        handledResultGeneration = result.generation
        capturedAudioPath = result.filePath
        capturedAudioDuration = result.durationMs
        if (!manualBody && result.transcript.isNotBlank()) {
            bodyText = result.transcript
        }
        if (phase == HostagePhase.VOICE) {
            if (result.durationMs >= minVoiceMs) {
                voiceUnlocked = true
                voiceCountdownActive = false
                AlarmCommands.cancelWatchdog(context)
                AlarmCommands.setVolume(context, config.duckedVolumePercent)
            } else {
                voiceUnlocked = false
                voiceCountdownActive = true
                voiceCountdown = 10
                voiceCountdownRun += 1
                AlarmCommands.setVolume(context, config.duckedVolumePercent)
                AlarmCommands.armWatchdog(context, 10_000L)
            }
        }
    }

    LaunchedEffect(phase, voiceUnlocked, capture.isRecording, voiceCountdownRun) {
        if (phase != HostagePhase.VOICE ||
            voiceUnlocked ||
            capture.isRecording ||
            !voiceCountdownActive
        ) {
            return@LaunchedEffect
        }
        while (voiceCountdown > 0) {
            delay(1_000L)
            voiceCountdown -= 1
        }
        AlarmCommands.cancelWatchdog(context)
        AlarmCommands.setVolume(context, 100)
        voiceCountdownActive = false
        phase = HostagePhase.RINGING
    }

    LaunchedEffect(typingRun, phase, isUnlocked) {
        if (phase != HostagePhase.TEXT || isUnlocked) {
            typingRemaining = 12
            return@LaunchedEffect
        }
        typingRemaining = 12
        while (typingRemaining > 0) {
            delay(1_000L)
            typingRemaining -= 1
        }
        AlarmCommands.cancelWatchdog(context)
        AlarmCommands.setVolume(context, 100)
        phase = HostagePhase.RINGING
    }

    LaunchedEffect(isUnlocked, phase) {
        if (isUnlocked) {
            AlarmCommands.cancelWatchdog(context)
        }
    }

    LaunchedEffect(watchdogEscalations) {
        if (watchdogEscalations > observedEscalations) {
            observedEscalations = watchdogEscalations
            voiceCountdownActive = false
            if (isUnlocked) {
                AlarmCommands.setVolume(context, config.duckedVolumePercent)
            } else {
                phase = HostagePhase.RINGING
                AlarmCommands.setVolume(context, 100)
            }
        }
    }

    LaunchedEffect(phase) {
        if (phase == HostagePhase.TEXT) {
            delay(120L)
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    LaunchedEffect(holdActive) {
        if (!holdActive) {
            holdElapsedMs = 0L
            return@LaunchedEffect
        }
        val started = SystemClock.elapsedRealtime()
        while (holdActive) {
            val elapsed = SystemClock.elapsedRealtime() - started
            holdElapsedMs = elapsed
            if (elapsed >= 15_000L) {
                holdActive = false
                AlarmCommands.stop(context, deleteAudio = true)
                onFinished()
                break
            }
            delay(50L)
        }
    }

    fun startVoiceNow() {
        permissionNotice = null
        saveError = null
        voiceCountdownActive = false
        AlarmCommands.cancelWatchdog(context)
        phase = HostagePhase.VOICE
        AlarmCommands.startCapture(context)
    }

    val microphonePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startVoiceNow()
        } else {
            permissionNotice = "Microphone permission is required for voice capture. Type instead."
        }
    }

    fun requestVoiceStart() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceNow()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun switchToText() {
        if (capture.isRecording) {
            AlarmCommands.stopCapture(context)
        }
        AlarmCommands.cancelWatchdog(context)
        voiceCountdownActive = false
        phase = HostagePhase.TEXT
        AlarmCommands.setVolume(context, config.duckedVolumePercent)
        if (wordCount < config.minWords) {
            AlarmCommands.armWatchdog(context, 12_000L)
        }
        typingRun += 1
    }

    fun onBodyChange(newValue: String) {
        bodyText = newValue
        manualBody = true
        if (phase == HostagePhase.VOICE) {
            switchToText()
        }
        phase = HostagePhase.TEXT
        val words = countWords(newValue)
        if (words < config.minWords) {
            AlarmCommands.armWatchdog(context, 12_000L)
        } else {
            AlarmCommands.cancelWatchdog(context)
        }
        typingRun += 1
    }

    fun escalate() {
        voiceCountdownActive = false
        AlarmCommands.cancelWatchdog(context)
        AlarmCommands.setVolume(context, 100)
        phase = HostagePhase.RINGING
    }

    fun saveAndStop() {
        if (saving || !isUnlocked) return
        saving = true
        saveError = null
        val resultBody = bodyText.trim()
        val audioPath = capturedAudioPath
        val audioDuration = capturedAudioDuration
        AlarmCommands.stop(context, deleteAudio = false)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    viewModel.saveDream(
                        title = Formats.defaultDreamTitle(),
                        body = resultBody,
                        audioPath = audioPath,
                        audioDurationMs = audioDuration,
                    )
                }
                onFinished()
            } catch (_: Exception) {
                saving = false
                saveError = "Could not save the note. The alarm has stopped."
            }
        }
    }

    BackHandler(enabled = true) {
        // The alarm cannot be dismissed with the system back gesture.
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DreamColors.AlarmBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column {
                    Text(
                        text = Formats.clockTime(System.currentTimeMillis()),
                        color = DreamColors.TextPrimary,
                        fontSize = 42.sp,
                        lineHeight = 46.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = Formats.timestamp().substringBefore(" "),
                        color = DreamColors.TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    text = when {
                        alarmPlaying && alarmVolume >= 100 -> "ALARM"
                        capture.isRecording -> "MUTED"
                        alarmPlaying -> "DUCKED ${alarmVolume}%"
                        else -> "ALARM"
                    },
                    color = DreamColors.TextSecondary,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(36.dp))

            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll),
            ) {
                when (phase) {
                    HostagePhase.RINGING -> RingingContent(
                        isUnlocked = isUnlocked,
                        permissionNotice = permissionNotice,
                        onRecord = ::requestVoiceStart,
                    )

                    HostagePhase.VOICE -> VoiceContent(
                        capture = capture,
                        bodyText = bodyText,
                        minVoiceMs = minVoiceMs,
                        voiceUnlocked = voiceUnlocked,
                        countdownActive = voiceCountdownActive,
                        countdownSeconds = voiceCountdown,
                        permissionNotice = permissionNotice,
                        saveError = saveError,
                        saving = saving,
                        isUnlocked = isUnlocked,
                        onStopRecording = { AlarmCommands.stopCapture(context) },
                        onResumeRecording = ::requestVoiceStart,
                        onSwitchToText = ::switchToText,
                        onBodyChange = ::onBodyChange,
                        onSave = ::saveAndStop,
                    )

                    HostagePhase.TEXT -> TextContent(
                        bodyText = bodyText,
                        wordCount = wordCount,
                        minWords = config.minWords,
                        typingRemaining = typingRemaining,
                        isUnlocked = isUnlocked,
                        saving = saving,
                        saveError = saveError,
                        focusRequester = focusRequester,
                        onBodyChange = ::onBodyChange,
                        onRecordInstead = ::requestVoiceStart,
                        onSave = ::saveAndStop,
                    )
                }

                SaveHint(
                    isUnlocked = isUnlocked,
                    isUnlockedByText = textUnlocked,
                    voiceSeconds = capturedAudioDuration / 1000L,
                    wordCount = wordCount,
                )
            }

            NoDreamEscape(
                active = holdActive,
                elapsedMs = holdElapsedMs,
                onPressChanged = { pressed -> holdActive = pressed },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun RingingContent(
    isUnlocked: Boolean,
    permissionNotice: String?,
    onRecord: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Record a dream to silence this alarm.",
            color = DreamColors.TextPrimary,
            fontSize = 20.sp,
            lineHeight = 28.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The alarm has no dismiss and no snooze.",
            color = DreamColors.TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(32.dp))
        FlatButton(
            text = "Lower Volume & Record Dream",
            onClick = onRecord,
            strong = true,
            enabled = !isUnlocked,
            modifier = Modifier.fillMaxWidth(),
        )
        permissionNotice?.let {
            Spacer(Modifier.height(16.dp))
            Text(
                text = it,
                color = DreamColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun VoiceContent(
    capture: com.lemomation.dreamlog.alarm.CaptureUiState,
    bodyText: String,
    minVoiceMs: Long,
    voiceUnlocked: Boolean,
    countdownActive: Boolean,
    countdownSeconds: Int,
    permissionNotice: String?,
    saveError: String?,
    saving: Boolean,
    isUnlocked: Boolean,
    onStopRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onSwitchToText: () -> Unit,
    onBodyChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (capture.isRecording) "Recording" else "Dream",
            color = DreamColors.TextPrimary,
            fontSize = 22.sp,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = Formats.duration(capture.elapsedMs),
                color = DreamColors.TextPrimary,
                fontSize = 40.sp,
                lineHeight = 44.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "/ ${Formats.duration(minVoiceMs)} required",
                color = DreamColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 7.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = when {
                capture.isRecording -> "Alarm muted while the microphone is open."
                voiceUnlocked -> "Voice length unlocked."
                countdownActive -> "Resume recording within ${countdownSeconds}s or type below."
                else -> "Start recording, or type instead."
            },
            color = DreamColors.TextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        capture.error?.let { warning ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = warning,
                color = DreamColors.TextPrimary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
        permissionNotice?.let { warning ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = warning,
                color = DreamColors.TextPrimary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }

        Spacer(Modifier.height(20.dp))

        if (capture.isRecording) {
            FlatButton(
                text = "Stop Recording",
                onClick = onStopRecording,
                strong = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (countdownActive) {
            FlatButton(
                text = "Resume Recording",
                onClick = onResumeRecording,
                strong = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (isUnlocked) {
            FlatButton(
                text = if (saving) "Saving..." else "Save & Stop Alarm",
                onClick = onSave,
                strong = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            FlatButton(
                text = "Record",
                onClick = onResumeRecording,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(20.dp))

        if (countdownActive) {
            BodyField(
                value = bodyText,
                onValueChange = onBodyChange,
                editable = true,
                placeholder = "Type instead...",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp),
            )
        } else {
            Text(
                text = bodyText.ifBlank { "The live transcript will appear here." },
                color = if (bodyText.isBlank()) DreamColors.TextSecondary else DreamColors.TextPrimary,
                fontSize = 15.sp,
                lineHeight = 23.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DreamColors.Border)
                    .background(DreamColors.Surface)
                    .padding(12.dp)
                    .heightIn(min = 120.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        TextAction(
            text = "Type instead",
            onClick = onSwitchToText,
            enabled = capture.isRecording || countdownActive || !isUnlocked,
        )
        saveError?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                color = DreamColors.TextPrimary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun TextContent(
    bodyText: String,
    wordCount: Int,
    minWords: Int,
    typingRemaining: Int,
    isUnlocked: Boolean,
    saving: Boolean,
    saveError: String?,
    focusRequester: FocusRequester,
    onBodyChange: (String) -> Unit,
    onRecordInstead: () -> Unit,
    onSave: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Dream",
            color = DreamColors.TextPrimary,
            fontSize = 22.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (isUnlocked) {
                "Unlocked. Save when you are ready."
            } else {
                "The alarm resumes in ${typingRemaining}s without typing."
            },
            color = DreamColors.TextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(16.dp))
        BodyField(
            value = bodyText,
            onValueChange = onBodyChange,
            editable = true,
            placeholder = "Write what you remember...",
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp)
                .focusRequester(focusRequester),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$wordCount / $minWords words",
                color = if (isUnlocked) DreamColors.TextPrimary else DreamColors.TextSecondary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
            TextAction(text = "Record instead", onClick = onRecordInstead)
        }
        Spacer(Modifier.height(16.dp))
        FlatButton(
            text = if (saving) "Saving..." else "Save & Stop Alarm",
            onClick = onSave,
            strong = true,
            enabled = isUnlocked && !saving,
            modifier = Modifier.fillMaxWidth(),
        )
        saveError?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, color = DreamColors.TextPrimary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BodyField(
    value: String,
    onValueChange: (String) -> Unit,
    editable: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = !editable,
        modifier = modifier
            .border(1.dp, DreamColors.Border)
            .background(DreamColors.Surface)
            .padding(12.dp),
        textStyle = TextStyle(
            color = DreamColors.TextPrimary,
            fontSize = 15.sp,
            lineHeight = 23.sp,
            fontFamily = FontFamily.Monospace,
        ),
        cursorBrush = SolidColor(DreamColors.TextPrimary),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = DreamColors.TextSecondary,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun SaveHint(
    isUnlocked: Boolean,
    isUnlockedByText: Boolean,
    voiceSeconds: Long,
    wordCount: Int,
) {
    if (!isUnlocked) return
    Spacer(Modifier.height(12.dp))
    Text(
        text = buildString {
            append("Unlocked")
            if (voiceSeconds > 0L) append(" / ${voiceSeconds}s audio")
            if (isUnlockedByText) append(" / $wordCount words")
        },
        color = DreamColors.TextSecondary,
        fontSize = 11.sp,
    )
}

@Composable
private fun NoDreamEscape(
    active: Boolean,
    elapsedMs: Long,
    onPressChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val remaining = (15_000L - elapsedMs).coerceAtLeast(0L)
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(horizontal = 8.dp)
                .pointerHold { pressed -> onPressChanged(pressed) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (active) {
                    "Keep holding / ${((remaining + 999L) / 1_000L)}s remaining"
                } else {
                    "No dream to recall (Hold 15s to dismiss)"
                },
                color = if (active) DreamColors.TextPrimary else DreamColors.TextSecondary,
                fontSize = 11.sp,
                letterSpacing = 0.2.sp,
            )
        }
    }
}

private fun Modifier.pointerHold(onChanged: (Boolean) -> Unit): Modifier =
    this.pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                onChanged(true)
                tryAwaitRelease()
                onChanged(false)
            },
        )
    }

private fun countWords(text: String): Int {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0
    return trimmed.split(Regex("\\s+")).count { token -> token.any { it.isLetterOrDigit() } }
}
