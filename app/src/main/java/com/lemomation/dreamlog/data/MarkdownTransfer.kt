package com.lemomation.dreamlog.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImportSummary(
    val importedTitles: List<String>,
    val failedFiles: List<String>,
)

data class ExportSummary(
    val exportedCount: Int,
    val failedCount: Int,
)

/**
 * Android Storage Access Framework bridge for Obsidian-compatible Markdown. The database remains
 * the source of truth; files are only read on import and written on export.
 */
object MarkdownTransfer {

    suspend fun importDocuments(
        context: Context,
        uris: List<Uri>,
        repository: DreamRepository,
    ): ImportSummary = withContext(Dispatchers.IO) {
        val imported = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (uri in uris) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
                // The grant is temporary; reading still works for this session.
            }

            val displayName = runCatching { queryDisplayName(context.contentResolver, uri) }
                .getOrNull()
                ?: uri.lastPathSegment
                ?: "Imported.md"
            val raw = runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?.removePrefix("\uFEFF")
            }.getOrNull()

            if (raw == null) {
                failed += displayName
                continue
            }

            try {
                val title = titleFromFileName(displayName)
                val modifiedAt = runCatching {
                    queryLastModified(context.contentResolver, uri)
                }.getOrNull()
                    ?.takeIf { it > 0L }
                    ?: System.currentTimeMillis()
                repository.importMarkdown(
                    title = title,
                    body = raw,
                    modifiedAt = modifiedAt,
                )
                imported += title
            } catch (_: Exception) {
                failed += displayName
            }
        }
        ImportSummary(importedTitles = imported, failedFiles = failed)
    }

    suspend fun exportNotes(
        context: Context,
        treeUri: Uri,
        notes: List<DreamNote>,
    ): ExportSummary = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
            // Some providers do not grant persistable access.
        }

        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext ExportSummary(0, notes.size)
        if (!tree.canWrite()) return@withContext ExportSummary(0, notes.size)

        val usedNames = tree.listFiles()
            .mapNotNull { it.name?.lowercase() }
            .toMutableSet()
        var exported = 0
        var failed = 0

        for (note in notes) {
            val base = sanitizeFileName(note.title)
            var candidate = "$base.md"
            var suffix = 2
            while (!usedNames.add(candidate.lowercase())) {
                candidate = "$base ($suffix).md"
                suffix += 1
            }

            try {
                val file = tree.createFile("text/markdown", candidate)
                if (file == null) {
                    failed += 1
                    continue
                }
                val stream = context.contentResolver.openOutputStream(file.uri, "wt")
                if (stream == null) {
                    failed += 1
                    continue
                }
                try {
                    stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write(note.body)
                    }
                } finally {
                    runCatching { stream.close() }
                }
                exported += 1
            } catch (_: Exception) {
                failed += 1
            }
        }
        ExportSummary(exportedCount = exported, failedCount = failed)
    }

    fun titleFromFileName(displayName: String): String =
        MarkdownCodec.titleFromFileName(displayName)

    fun sanitizeFileName(title: String): String =
        MarkdownCodec.sanitizeFileName(title)

    private fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && !cursor.isNull(index)) return cursor.getString(index)
                }
            }
        return null
    }

    private fun queryLastModified(contentResolver: ContentResolver, uri: Uri): Long? {
        contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null,
            null,
            null,
        )
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
                }
            }
        return null
    }
}
