package com.lemomation.dreamlog

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lemomation.dreamlog.data.AlarmConfig
import com.lemomation.dreamlog.data.ConceptNode
import com.lemomation.dreamlog.data.DreamNote
import com.lemomation.dreamlog.data.DreamRepository
import com.lemomation.dreamlog.data.ExportSummary
import com.lemomation.dreamlog.data.ImportSummary
import com.lemomation.dreamlog.data.MarkdownTransfer
import com.lemomation.dreamlog.data.NoteNodeCrossRef
import com.lemomation.dreamlog.util.Formats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class GraphNodeKind { NOTE, CONCEPT }

data class GraphNodeModel(
    val key: String,
    val label: String,
    val kind: GraphNodeKind,
    val noteId: Long? = null,
    val conceptId: Long? = null,
)

data class GraphEdgeModel(
    val fromKey: String,
    val toKey: String,
)

data class GraphModel(
    val nodes: List<GraphNodeModel> = emptyList(),
    val edges: List<GraphEdgeModel> = emptyList(),
)

class DreamLogViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: DreamRepository =
        (application as DreamLogApplication).repository

    val notes = repository.observeNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val nodes = repository.observeNodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val config = repository.observeAlarmConfig()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val graph = combine(
        repository.observeNotes(),
        repository.observeNodes(),
        repository.observeCrossRefs(),
    ) { allNotes, allNodes, allRefs ->
        buildGraph(allNotes, allNodes, allRefs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GraphModel())

    fun noteFlow(noteId: Long): Flow<DreamNote?> = repository.observeNote(noteId)

    fun nodesForNote(noteId: Long): Flow<List<ConceptNode>> =
        repository.observeNodesForNote(noteId)

    fun backlinkNotes(noteId: Long): Flow<List<DreamNote>> =
        repository.observeBacklinkNotes(noteId)

    fun notesForNode(nodeId: Long): Flow<List<DreamNote>> =
        repository.observeNotesForNode(nodeId)

    suspend fun createNote(): Long = repository.createNote(
        title = Formats.defaultDreamTitle(),
        body = "",
    )

    suspend fun updateNote(noteId: Long, title: String, body: String): DreamNote? =
        repository.updateNote(noteId, title, body)

    suspend fun deleteNote(noteId: Long) = repository.deleteNote(noteId)

    suspend fun saveDream(
        title: String,
        body: String,
        audioPath: String?,
        audioDurationMs: Long,
    ): Long = repository.createNote(
        title = title,
        body = body,
        audioPath = audioPath,
        audioDurationMs = audioDurationMs,
    )

    suspend fun allNotesSnapshot(): List<DreamNote> = repository.getAllNotes()

    suspend fun updateThresholds(
        minVoiceSeconds: Int,
        minWords: Int,
        duckedVolumePercent: Int,
    ) {
        repository.updateThresholds(minVoiceSeconds, minWords, duckedVolumePercent)
    }

    suspend fun setScheduledAt(triggerAtMillis: Long) {
        repository.setScheduledAt(triggerAtMillis)
    }

    suspend fun importMarkdownFiles(uris: List<Uri>): ImportSummary =
        MarkdownTransfer.importDocuments(getApplication(), uris, repository)

    suspend fun exportMarkdown(treeUri: Uri): ExportSummary {
        val snapshot = repository.getAllNotes()
        return MarkdownTransfer.exportNotes(getApplication(), treeUri, snapshot)
    }

    private fun buildGraph(
        allNotes: List<DreamNote>,
        allNodes: List<ConceptNode>,
        allRefs: List<NoteNodeCrossRef>,
    ): GraphModel {
        val noteNodes = allNotes.map { note ->
            GraphNodeModel(
                key = "note:${note.id}",
                label = note.title,
                kind = GraphNodeKind.NOTE,
                noteId = note.id,
            )
        }
        val conceptNodes = allNodes.map { node ->
            GraphNodeModel(
                key = "concept:${node.id}",
                label = node.name,
                kind = GraphNodeKind.CONCEPT,
                conceptId = node.id,
            )
        }
        val knownKeys = (noteNodes + conceptNodes).associateBy { it.key }
        val edges = allRefs.mapNotNull { ref ->
            val noteKey = "note:${ref.noteId}"
            val conceptKey = "concept:${ref.nodeId}"
            if (knownKeys.containsKey(noteKey) && knownKeys.containsKey(conceptKey)) {
                GraphEdgeModel(fromKey = noteKey, toKey = conceptKey)
            } else {
                null
            }
        }
        return GraphModel(nodes = noteNodes + conceptNodes, edges = edges)
    }
}
