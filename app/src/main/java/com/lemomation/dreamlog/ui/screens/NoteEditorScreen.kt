package com.lemomation.dreamlog.ui.screens

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lemomation.dreamlog.DreamLogViewModel
import com.lemomation.dreamlog.data.ConceptNode
import com.lemomation.dreamlog.data.DreamNote
import com.lemomation.dreamlog.ui.components.FlatButton
import com.lemomation.dreamlog.ui.components.Hairline
import com.lemomation.dreamlog.ui.components.SectionLabel
import com.lemomation.dreamlog.ui.components.TextAction
import com.lemomation.dreamlog.ui.theme.DreamColors
import com.lemomation.dreamlog.util.Formats
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun NoteEditorScreen(
    noteId: Long,
    viewModel: DreamLogViewModel,
    onBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenConcept: (Long) -> Unit,
) {
    val noteFlow = remember(noteId) { viewModel.noteFlow(noteId) }
    val note by noteFlow.collectAsStateWithLifecycle(initialValue = null)
    val linkedNodesFlow = remember(noteId) { viewModel.nodesForNote(noteId) }
    val linkedNodes by linkedNodesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val backlinkFlow = remember(noteId) { viewModel.backlinkNotes(noteId) }
    val backlinkNotes by backlinkFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val scope = rememberCoroutineScope()
    var title by rememberSaveable(noteId) { mutableStateOf("") }
    var bodyValue by rememberSaveable(noteId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var savedTitle by rememberSaveable(noteId) { mutableStateOf("") }
    var savedBody by rememberSaveable(noteId) { mutableStateOf("") }
    var initialized by rememberSaveable(noteId) { mutableStateOf(false) }
    var saving by rememberSaveable(noteId) { mutableStateOf(false) }
    var status by rememberSaveable(noteId) { mutableStateOf<String?>(null) }

    val loadedNote = note
    LaunchedEffect(loadedNote?.id) {
        if (!initialized && loadedNote != null) {
            title = loadedNote.title
            bodyValue = TextFieldValue(loadedNote.body)
            savedTitle = loadedNote.title
            savedBody = loadedNote.body
            initialized = true
        }
    }

    val isDirty = initialized && (title != savedTitle || bodyValue.text != savedBody)

    fun normalizedTitle(): String =
        title.trim().ifBlank { Formats.defaultDreamTitle() }

    fun persist(andThen: (() -> Unit)? = null) {
        if (!initialized) {
            andThen?.invoke()
            return
        }
        if (saving) return
        if (!isDirty) {
            andThen?.invoke()
            return
        }
        saving = true
        status = null
        scope.launch {
            try {
                val updated = viewModel.updateNote(
                    noteId = noteId,
                    title = normalizedTitle(),
                    body = bodyValue.text,
                )
                if (updated != null) {
                    title = updated.title
                    bodyValue = bodyValue.copy(text = updated.body)
                    savedTitle = updated.title
                    savedBody = updated.body
                    status = "Saved"
                }
                andThen?.invoke()
            } catch (_: Exception) {
                status = "Could not save."
            } finally {
                saving = false
            }
        }
    }

    fun saveAndBack() {
        persist { onBack() }
    }

    fun insertWikiLink() {
        status = null
        val text = bodyValue.text
        val selection = bodyValue.selection
        if (!selection.collapsed) {
            val start = selection.min
            val end = selection.max
            val selected = text.substring(start, end)
            val replacement = if (
                selected.startsWith("[[") && selected.endsWith("]]")
            ) {
                selected
            } else {
                "[[$selected]]"
            }
            val newText = text.replaceRange(start, end, replacement)
            val newEnd = start + replacement.length
            bodyValue = bodyValue.copy(
                text = newText,
                selection = TextRange(newEnd),
            )
        } else {
            val cursor = selection.start
            val insertion = "[[]]"
            val newText = text.replaceRange(cursor, cursor, insertion)
            bodyValue = bodyValue.copy(
                text = newText,
                selection = TextRange(cursor + 2),
            )
        }
    }

    BackHandler(enabled = true) { saveAndBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DreamColors.Background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextAction(text = "Back", onClick = ::saveAndBack)
            Text(
                text = when {
                    status != null -> status.orEmpty()
                    saving -> "Saving..."
                    isDirty -> "Unsaved"
                    else -> ""
                },
                color = DreamColors.TextSecondary,
                fontSize = 11.sp,
            )
            TextAction(
                text = "Save",
                enabled = !saving && initialized,
                onClick = { persist() },
            )
        }
        Hairline()

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            if (!initialized) {
                Text(
                    text = "Loading note...",
                    color = DreamColors.TextSecondary,
                    fontSize = 13.sp,
                )
                return@Column
            }

            BasicTextField(
                value = title,
                onValueChange = {
                    title = it
                    status = null
                },
                singleLine = true,
                textStyle = TextStyle(
                    color = DreamColors.TextPrimary,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                ),
                cursorBrush = SolidColor(DreamColors.TextPrimary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (title.isEmpty()) {
                            Text(
                                text = Formats.defaultDreamTitle(),
                                color = DreamColors.TextSecondary,
                                fontSize = 24.sp,
                                lineHeight = 30.sp,
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = Formats.timestamp(loadedNote?.createdAt ?: System.currentTimeMillis()),
                color = DreamColors.TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(20.dp))

            BasicTextField(
                value = bodyValue,
                onValueChange = {
                    bodyValue = it
                    status = null
                },
                textStyle = TextStyle(
                    color = DreamColors.TextPrimary,
                    fontSize = 16.sp,
                    lineHeight = 26.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(DreamColors.TextPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 260.dp),
                decorationBox = { inner ->
                    Box {
                        if (bodyValue.text.isEmpty()) {
                            Text(
                                text = "Write the dream...",
                                color = DreamColors.TextSecondary,
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        inner()
                    }
                },
            )

            Spacer(Modifier.height(24.dp))
            if (!loadedNote?.audioPath.isNullOrBlank()) {
                AudioBar(
                    path = loadedNote?.audioPath,
                    fallbackDurationMs = loadedNote?.audioDurationMs ?: 0L,
                )
                Spacer(Modifier.height(24.dp))
            }

            BacklinksFooter(
                linkedNodes = linkedNodes,
                backlinkNotes = backlinkNotes,
                onOpenConcept = onOpenConcept,
                onOpenNote = onOpenNote,
            )
        }

        Hairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            FlatButton(
                text = "[[Link]]",
                onClick = ::insertWikiLink,
                modifier = Modifier.height(40.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Wrap the selected words, or insert [[]] at the cursor.",
                color = DreamColors.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BacklinksFooter(
    linkedNodes: List<ConceptNode>,
    backlinkNotes: List<DreamNote>,
    onOpenConcept: (Long) -> Unit,
    onOpenNote: (Long) -> Unit,
) {
    if (linkedNodes.isEmpty() && backlinkNotes.isEmpty()) {
        Text(
            text = "No links yet.",
            color = DreamColors.TextSecondary,
            fontSize = 12.sp,
        )
        return
    }

    if (linkedNodes.isNotEmpty()) {
        SectionLabel(text = "Links")
        Spacer(Modifier.height(4.dp))
        linkedNodes.forEach { node ->
            Text(
                text = "[[${node.name}]]",
                color = DreamColors.TextPrimary,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenConcept(node.id) }
                    .padding(vertical = 8.dp),
            )
            Hairline()
        }
        Spacer(Modifier.height(18.dp))
    }

    if (backlinkNotes.isNotEmpty()) {
        SectionLabel(text = "Backlinks")
        Spacer(Modifier.height(4.dp))
        backlinkNotes.forEach { backlink ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenNote(backlink.id) }
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    text = backlink.title,
                    color = DreamColors.TextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Text(
                    text = Formats.timestamp(backlink.createdAt),
                    color = DreamColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
            Hairline()
        }
    }
}

@Composable
private fun AudioBar(path: String?, fallbackDurationMs: Long) {
    if (path.isNullOrBlank()) return
    val exists = remember(path) { File(path).exists() }
    val player = remember(path) {
        if (exists) {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
        } else {
            null
        }
    }

    var prepared by remember(path) { mutableStateOf(false) }
    var playing by remember(path) { mutableStateOf(false) }
    var durationMs by remember(path) { mutableLongStateOf(fallbackDurationMs) }
    var positionMs by remember(path) { mutableLongStateOf(0L) }
    var speed by rememberSaveable(path) { mutableStateOf(1f) }
    var failed by remember(path) { mutableStateOf(!exists) }

    DisposableEffect(player) {
        onDispose {
            try {
                player?.release()
            } catch (_: Exception) {
                // Already released.
            }
        }
    }

    LaunchedEffect(player) {
        val mp = player ?: return@LaunchedEffect
        try {
            mp.setDataSource(path)
            mp.setOnPreparedListener {
                prepared = true
                durationMs = it.duration.toLong().coerceAtLeast(0L)
            }
            mp.setOnCompletionListener {
                playing = false
                positionMs = 0L
                runCatching { it.seekTo(0) }
            }
            mp.setOnErrorListener { _, _, _ ->
                playing = false
                prepared = false
                failed = true
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            failed = true
        }
    }

    LaunchedEffect(playing, prepared) {
        if (!playing) return@LaunchedEffect
        while (playing) {
            val mp = player ?: break
            if (mp.isPlaying) {
                positionMs = mp.currentPosition.toLong()
                if (durationMs <= 0L) durationMs = mp.duration.toLong()
            }
            delay(200L)
        }
    }

    LaunchedEffect(speed, prepared) {
        val mp = player ?: return@LaunchedEffect
        if (!prepared) return@LaunchedEffect
        try {
            val wasPlaying = mp.isPlaying
            mp.playbackParams = PlaybackParams().setSpeed(speed)
            if (wasPlaying) {
                mp.start()
                playing = true
            } else {
                mp.pause()
                playing = false
            }
        } catch (_: Exception) {
            failed = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DreamColors.Border)
            .background(DreamColors.Surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextAction(
                text = if (playing) "Pause" else "Play",
                enabled = prepared && !failed,
                onClick = {
                    val mp = player ?: return@TextAction
                    if (!prepared) return@TextAction
                    if (playing) {
                        mp.pause()
                        playing = false
                        positionMs = mp.currentPosition.toLong()
                    } else {
                        if (durationMs > 0L && positionMs >= durationMs - 150L) {
                            mp.seekTo(0)
                            positionMs = 0L
                        }
                        mp.start()
                        playing = true
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${Formats.duration(positionMs)} / ${Formats.duration(durationMs)}",
                color = DreamColors.TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            TextAction(
                text = when (speed) {
                    1.5f -> "1.5x"
                    2f -> "2x"
                    else -> "1x"
                },
                enabled = !failed,
                color = DreamColors.TextSecondary,
                onClick = {
                    speed = when (speed) {
                        1f -> 1.5f
                        1.5f -> 2f
                        else -> 1f
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        val fraction = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DreamColors.Border),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(1.dp)
                    .background(DreamColors.TextPrimary),
            )
        }
        if (failed) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Audio file unavailable.",
                color = DreamColors.TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}
