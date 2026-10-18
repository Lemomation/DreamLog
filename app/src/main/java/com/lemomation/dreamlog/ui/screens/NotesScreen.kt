package com.lemomation.dreamlog.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lemomation.dreamlog.DreamLogViewModel
import com.lemomation.dreamlog.data.DreamNote
import com.lemomation.dreamlog.ui.components.Hairline
import com.lemomation.dreamlog.ui.components.TextAction
import com.lemomation.dreamlog.ui.theme.DreamColors
import com.lemomation.dreamlog.util.Formats
import kotlinx.coroutines.launch

@Composable
fun NotesScreen(
    viewModel: DreamLogViewModel,
    onOpenNote: (Long) -> Unit,
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            notice = "Importing..."
            val summary = runCatching { viewModel.importMarkdownFiles(uris) }.getOrNull()
            busy = false
            notice = when {
                summary == null -> "Import failed."
                summary.importedTitles.isEmpty() && summary.failedFiles.isEmpty() -> "No Markdown files imported."
                summary.failedFiles.isEmpty() -> "Imported ${summary.importedTitles.size} note(s)."
                summary.importedTitles.isEmpty() -> "Import failed for ${summary.failedFiles.size} file(s)."
                else -> "Imported ${summary.importedTitles.size}; failed ${summary.failedFiles.size}."
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            notice = "Exporting..."
            val summary = runCatching { viewModel.exportMarkdown(uri) }.getOrNull()
            busy = false
            notice = when {
                summary == null -> "Export failed."
                summary.exportedCount == 0 && summary.failedCount == 0 -> "There are no notes to export."
                summary.failedCount == 0 -> "Exported ${summary.exportedCount} note(s)."
                else -> "Exported ${summary.exportedCount}; failed ${summary.failedCount}."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DreamColors.Background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Notes",
                color = DreamColors.TextPrimary,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction(
                    text = "New",
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val created = runCatching { viewModel.createNote() }
                            busy = false
                            created.fold(
                                onSuccess = { id -> onOpenNote(id) },
                                onFailure = { notice = "Could not create a note." },
                            )
                        }
                    },
                )
                Spacer(Modifier.width(6.dp))
                TextAction(
                    text = "Import .md",
                    enabled = !busy,
                    color = DreamColors.TextSecondary,
                    onClick = {
                        importLauncher.launch(
                            arrayOf(
                                "text/markdown",
                                "text/plain",
                                "application/octet-stream",
                            ),
                        )
                    },
                )
                Spacer(Modifier.width(2.dp))
                TextAction(
                    text = "Export .md",
                    enabled = !busy,
                    color = DreamColors.TextSecondary,
                    onClick = { exportLauncher.launch(null) },
                )
            }
        }

        Hairline()

        notice?.let { message ->
            Text(
                text = message,
                color = DreamColors.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
            Hairline()
        }

        if (notes.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(20.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                Text(
                    text = "No dreams recorded.",
                    color = DreamColors.TextSecondary,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                itemsIndexed(
                    items = notes,
                    key = { _, note -> note.id },
                ) { index, note ->
                    NoteRow(note = note, onClick = { onOpenNote(note.id) })
                    if (index != notes.lastIndex) {
                        Hairline(modifier = Modifier.padding(horizontal = 20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(note: DreamNote, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = note.title,
            color = DreamColors.TextPrimary,
            fontSize = 15.sp,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Formats.timestamp(note.createdAt),
                color = DreamColors.TextSecondary,
                fontSize = 12.sp,
            )
            if (!note.audioPath.isNullOrBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "[Audio]",
                    color = DreamColors.TextSecondary,
                    fontSize = 12.sp,
                )
                if (note.audioDurationMs > 0L) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = Formats.duration(note.audioDurationMs),
                        color = DreamColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
