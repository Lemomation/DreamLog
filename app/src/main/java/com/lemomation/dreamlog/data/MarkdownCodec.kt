package com.lemomation.dreamlog.data

/**
 * Pure filename/title rules shared by the Storage Access Framework importer and exporter.
 * Kept free of Android types so the rules can be unit tested and reused.
 */
object MarkdownCodec {
    fun titleFromFileName(displayName: String): String {
        val withoutExtension = displayName
            .replace(Regex("\\.(md|markdown)$", RegexOption.IGNORE_CASE), "")
        return withoutExtension.trim().ifBlank { "Imported note" }
    }

    fun sanitizeFileName(title: String): String {
        val cleaned = title
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim()
            .trimEnd('.')
            .take(90)
        return cleaned.ifBlank { "Untitled" }
    }
}
