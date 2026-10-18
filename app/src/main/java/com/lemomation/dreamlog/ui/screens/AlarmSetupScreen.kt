package com.lemomation.dreamlog.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lemomation.dreamlog.DreamLogViewModel
import com.lemomation.dreamlog.alarm.AlarmScheduler
import com.lemomation.dreamlog.ui.components.FlatButton
import com.lemomation.dreamlog.ui.components.Hairline
import com.lemomation.dreamlog.ui.components.SectionLabel
import com.lemomation.dreamlog.ui.components.TextAction
import com.lemomation.dreamlog.ui.theme.DreamColors
import com.lemomation.dreamlog.util.Formats
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun AlarmSetupScreen(
    viewModel: DreamLogViewModel,
    onTriggerTestAlarm: () -> Unit,
) {
    val context = LocalContext.current
    val configState by viewModel.config.collectAsStateWithLifecycle()
    val config = configState
    val scope = rememberCoroutineScope()

    var minVoiceSeconds by rememberSaveable { mutableStateOf(10) }
    var minWords by rememberSaveable { mutableStateOf(15) }
    var duckedVolume by rememberSaveable { mutableStateOf(15) }
    var thresholdsInitialized by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(config) {
        if (!thresholdsInitialized && config != null) {
            minVoiceSeconds = config.minVoiceSeconds
            minWords = config.minWords
            duckedVolume = config.duckedVolumePercent
            thresholdsInitialized = true
        }
    }

    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    val initialTime = remember {
        Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 3) }
    }
    var is24Hour by rememberSaveable {
        mutableStateOf(DateFormat.is24HourFormat(context))
    }
    var amPm by rememberSaveable {
        mutableStateOf(initialTime.get(Calendar.HOUR_OF_DAY) >= 12)
    }
    var hourText by rememberSaveable {
        mutableStateOf(
            initialHourText(initialTime.get(Calendar.HOUR_OF_DAY), is24Hour),
        )
    }
    var minuteText by rememberSaveable {
        mutableStateOf("%02d".format(initialTime.get(Calendar.MINUTE)))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        status = if (granted) {
            "Notification permission granted."
        } else {
            "Notifications are disabled. Full-screen alarm delivery may be limited."
        }
    }

    fun persistThresholds() {
        scope.launch {
            viewModel.updateThresholds(
                minVoiceSeconds = minVoiceSeconds,
                minWords = minWords,
                duckedVolumePercent = duckedVolume,
            )
        }
    }

    fun parseHour24(): Int? {
        val hour = hourText.toIntOrNull() ?: return null
        val minute = minuteText.toIntOrNull() ?: return null
        if (minute !in 0..59) return null
        if (is24Hour) {
            return hour.takeIf { it in 0..23 }
        }
        if (hour !in 1..12) return null
        val base = hour % 12
        return if (amPm) base else base + 12
    }

    fun openExactAlarmSettings() {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        } catch (_: Exception) {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }

    fun scheduleAt(triggerAtMillis: Long) {
        if (!AlarmScheduler.canScheduleExact(context)) {
            status = "Exact alarms are disabled for DreamLog. Allow exact alarms, then try again."
            openExactAlarmSettings()
            return
        }
        val scheduled = AlarmScheduler.schedule(context, triggerAtMillis)
        if (!scheduled) {
            status = "The alarm could not be scheduled."
            return
        }
        status = "Alarm set for ${Formats.timestamp(triggerAtMillis)}."
        scope.launch { viewModel.setScheduledAt(triggerAtMillis) }
    }

    fun switchHourMode() {
        val parsed = parseHour24()
        is24Hour = !is24Hour
        if (parsed != null) {
            amPm = parsed >= 12
            hourText = initialHourText(parsed, is24Hour)
        } else {
            hourText = initialHourText(initialTime.get(Calendar.HOUR_OF_DAY), is24Hour)
        }
    }

    fun scheduleExactInput() {
        val hour = parseHour24()
        val minute = minuteText.toIntOrNull()
        if (hour == null || minute == null) {
            status = if (is24Hour) {
                "Enter a 24-hour time (hours 0-23, minutes 0-59)."
            } else {
                "Enter a 12-hour time (hours 1-12, minutes 0-59)."
            }
            return
        }
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        scheduleAt(calendar.timeInMillis)
    }

    val scheduledAt = config?.scheduledAt?.takeIf { it > nowMillis }
    val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    val fullScreenAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        run {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.canUseFullScreenIntent() ?: true
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DreamColors.Background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = "Alarm",
            color = DreamColors.TextPrimary,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            modifier = Modifier.padding(top = 18.dp, bottom = 14.dp),
        )
        Hairline()

        Spacer(Modifier.height(20.dp))
        SectionLabel(text = "Set alarm from now")
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlatButton(
                text = "+3h 00m",
                onClick = {
                    scheduleAt(System.currentTimeMillis() + 3L * 60L * 60_000L)
                },
                modifier = Modifier.weight(1f),
            )
            FlatButton(
                text = "+3h 30m",
                onClick = {
                    scheduleAt(System.currentTimeMillis() + 3L * 60L * 60_000L + 30L * 60_000L)
                },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlatButton(
                text = "+4h 00m",
                onClick = {
                    scheduleAt(System.currentTimeMillis() + 4L * 60L * 60_000L)
                },
                modifier = Modifier.weight(1f),
            )
            FlatButton(
                text = "+4h 30m",
                onClick = {
                    scheduleAt(System.currentTimeMillis() + 4L * 60L * 60_000L + 30L * 60_000L)
                },
                modifier = Modifier.weight(1f),
            )
        }

        if (scheduledAt != null) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Next alarm: ${Formats.timestamp(scheduledAt)}",
                    color = DreamColors.TextSecondary,
                    fontSize = 12.sp,
                )
                TextAction(
                    text = "Cancel alarm",
                    color = DreamColors.TextSecondary,
                    onClick = {
                        AlarmScheduler.cancel(context)
                        scope.launch { viewModel.setScheduledAt(0L) }
                        status = "Alarm cancelled."
                    },
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        Hairline()
        Spacer(Modifier.height(20.dp))
        SectionLabel(text = "Exact time")
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumberField(
                value = hourText,
                onValueChange = { value ->
                    hourText = value.filter { it.isDigit() }.take(2)
                },
                placeholder = if (is24Hour) "HH" else "hh",
                modifier = Modifier.width(64.dp),
            )
            Text(
                text = ":",
                color = DreamColors.TextSecondary,
                fontSize = 22.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            NumberField(
                value = minuteText,
                onValueChange = { value ->
                    minuteText = value.filter { it.isDigit() }.take(2)
                },
                placeholder = "mm",
                modifier = Modifier.width(64.dp),
            )
            Spacer(Modifier.width(12.dp))
            if (!is24Hour) {
                TextAction(
                    text = "AM",
                    color = if (amPm) DreamColors.TextPrimary else DreamColors.TextSecondary,
                    onClick = { amPm = true },
                )
                TextAction(
                    text = "PM",
                    color = if (!amPm) DreamColors.TextPrimary else DreamColors.TextSecondary,
                    onClick = { amPm = false },
                )
            }
            Spacer(Modifier.weight(1f))
            TextAction(
                text = if (is24Hour) "24h" else "12h",
                color = DreamColors.TextSecondary,
                onClick = ::switchHourMode,
            )
        }
        Spacer(Modifier.height(12.dp))
        FlatButton(
            text = "Set Exact Alarm",
            onClick = ::scheduleExactInput,
            modifier = Modifier.fillMaxWidth(),
        )

        status?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                color = DreamColors.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        Spacer(Modifier.height(28.dp))
        Hairline()
        Spacer(Modifier.height(20.dp))
        SectionLabel(text = "Unlock thresholds")
        Spacer(Modifier.height(8.dp))

        StepperRow(
            label = "Minimum voice duration",
            valueText = "${minVoiceSeconds}s",
            onDecrease = {
                minVoiceSeconds = (minVoiceSeconds - 1).coerceIn(1, 120)
                persistThresholds()
            },
            onIncrease = {
                minVoiceSeconds = (minVoiceSeconds + 1).coerceIn(1, 120)
                persistThresholds()
            },
        )
        Hairline()
        StepperRow(
            label = "Minimum word count",
            valueText = "$minWords words",
            onDecrease = {
                minWords = (minWords - 1).coerceIn(1, 200)
                persistThresholds()
            },
            onIncrease = {
                minWords = (minWords + 1).coerceIn(1, 200)
                persistThresholds()
            },
        )
        Hairline()
        StepperRow(
            label = "Ducked alarm volume",
            valueText = "$duckedVolume%",
            onDecrease = {
                duckedVolume = (duckedVolume - 1).coerceIn(1, 100)
                persistThresholds()
            },
            onIncrease = {
                duckedVolume = (duckedVolume + 1).coerceIn(1, 100)
                persistThresholds()
            },
        )
        Hairline()

        Spacer(Modifier.height(28.dp))
        FlatButton(
            text = "Trigger Test Alarm Now",
            onClick = onTriggerTestAlarm,
            strong = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The alarm starts immediately. There is no dismiss or snooze on the next screen.",
            color = DreamColors.TextSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Notification permission is off. Allow it so scheduled alarms can open the full-screen alarm screen.",
                color = DreamColors.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            TextAction(
                text = "Allow notifications",
                onClick = {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            )
        }

        if (!fullScreenAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Full-screen alarm permission is off. The alarm may appear only as a notification while the screen is locked.",
                color = DreamColors.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            TextAction(
                text = "Open full-screen alarm settings",
                onClick = {
                    try {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    } catch (_: Exception) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                },
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Number,
        ),
        textStyle = TextStyle(
            color = DreamColors.TextPrimary,
            fontSize = 20.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(DreamColors.TextPrimary),
        modifier = modifier
            .border(1.dp, DreamColors.Border)
            .background(DreamColors.Surface)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = DreamColors.TextSecondary,
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            inner()
        },
    )
}

@Composable
private fun StepperRow(
    label: String,
    valueText: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = DreamColors.TextPrimary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = valueText,
            color = DreamColors.TextPrimary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(86.dp),
        )
        StepButton(text = "-", onClick = onDecrease)
        Spacer(Modifier.width(6.dp))
        StepButton(text = "+", onClick = onIncrease)
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    TextAction(
        text = text,
        onClick = onClick,
        modifier = Modifier.width(36.dp),
        color = DreamColors.TextPrimary,
    )
}

private fun initialHourText(hour24: Int, is24Hour: Boolean): String =
    if (is24Hour) {
        "%02d".format(hour24)
    } else {
        "%02d".format(if (hour24 % 12 == 0) 12 else hour24 % 12)
    }
