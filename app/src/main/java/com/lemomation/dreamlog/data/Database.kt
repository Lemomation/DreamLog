package com.lemomation.dreamlog.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.withTransaction
import java.util.Locale
import java.util.regex.Pattern

@Entity(
    tableName = "dream_notes",
    indices = [Index(value = ["createdAt"])],
)
data class DreamNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val audioPath: String? = null,
    val audioDurationMs: Long = 0L,
)

@Entity(
    tableName = "concept_nodes",
    indices = [Index(value = ["name"], unique = true)],
)
data class ConceptNode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "note_node_links",
    primaryKeys = ["noteId", "nodeId"],
    foreignKeys = [
        ForeignKey(
            entity = DreamNote::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ConceptNode::class,
            parentColumns = ["id"],
            childColumns = ["nodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["noteId"]), Index(value = ["nodeId"])],
)
data class NoteNodeCrossRef(
    val noteId: Long,
    val nodeId: Long,
)

@Entity(tableName = "alarm_config")
data class AlarmConfig(
    @PrimaryKey val id: Int = 1,
    val minVoiceSeconds: Int = 10,
    val minWords: Int = 15,
    val duckedVolumePercent: Int = 15,
    val scheduledAt: Long = 0L,
)

@Dao
interface DreamNoteDao {
    @Query("SELECT * FROM dream_notes ORDER BY createdAt DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<DreamNote>>

    @Query("SELECT * FROM dream_notes WHERE id = :id")
    fun observeById(id: Long): kotlinx.coroutines.flow.Flow<DreamNote?>

    @Query("SELECT * FROM dream_notes WHERE id = :id")
    suspend fun getById(id: Long): DreamNote?

    @Query("SELECT * FROM dream_notes ORDER BY createdAt DESC")
    suspend fun getAll(): List<DreamNote>

    @Insert
    suspend fun insert(note: DreamNote): Long

    @Update
    suspend fun update(note: DreamNote)

    @Query("DELETE FROM dream_notes WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ConceptNodeDao {
    @Query("SELECT * FROM concept_nodes ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<ConceptNode>>

    @Query("SELECT * FROM concept_nodes ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<ConceptNode>

    @Query("SELECT name FROM concept_nodes")
    suspend fun getAllNames(): List<String>

    @Query("SELECT * FROM concept_nodes WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): ConceptNode?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(node: ConceptNode): Long

    @Query("DELETE FROM concept_nodes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "DELETE FROM concept_nodes WHERE id NOT IN " +
            "(SELECT DISTINCT nodeId FROM note_node_links)",
    )
    suspend fun deleteOrphans()
}

@Dao
interface NoteNodeCrossRefDao {
    @Query("SELECT * FROM note_node_links")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<NoteNodeCrossRef>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(refs: List<NoteNodeCrossRef>)

    @Query("DELETE FROM note_node_links WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: Long)

    @Query(
        "SELECT n.* FROM concept_nodes n " +
            "INNER JOIN note_node_links l ON n.id = l.nodeId " +
            "WHERE l.noteId = :noteId ORDER BY n.name COLLATE NOCASE ASC",
    )
    fun observeNodesForNote(noteId: Long): kotlinx.coroutines.flow.Flow<List<ConceptNode>>

    @Query(
        "SELECT DISTINCT other.* FROM dream_notes other " +
            "INNER JOIN note_node_links l2 ON other.id = l2.noteId " +
            "WHERE l2.nodeId IN (SELECT nodeId FROM note_node_links WHERE noteId = :noteId) " +
            "AND other.id != :noteId ORDER BY other.createdAt DESC",
    )
    fun observeBacklinkNotes(noteId: Long): kotlinx.coroutines.flow.Flow<List<DreamNote>>

    @Query(
        "SELECT n.* FROM dream_notes n INNER JOIN note_node_links l ON n.id = l.noteId " +
            "WHERE l.nodeId = :nodeId ORDER BY n.createdAt DESC",
    )
    fun observeNotesForNode(nodeId: Long): kotlinx.coroutines.flow.Flow<List<DreamNote>>
}

@Dao
interface AlarmConfigDao {
    @Query("SELECT * FROM alarm_config WHERE id = 1")
    fun observe(): kotlinx.coroutines.flow.Flow<AlarmConfig?>

    @Query("SELECT * FROM alarm_config WHERE id = 1")
    suspend fun get(): AlarmConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: AlarmConfig)
}

@Database(
    entities = [DreamNote::class, ConceptNode::class, NoteNodeCrossRef::class, AlarmConfig::class],
    version = 1,
    exportSchema = false,
)
abstract class DreamLogDatabase : RoomDatabase() {
    abstract fun dreamNoteDao(): DreamNoteDao
    abstract fun conceptNodeDao(): ConceptNodeDao
    abstract fun noteNodeCrossRefDao(): NoteNodeCrossRefDao
    abstract fun alarmConfigDao(): AlarmConfigDao

    companion object {
        @Volatile
        private var instance: DreamLogDatabase? = null

        fun get(context: Context): DreamLogDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DreamLogDatabase::class.java,
                    "dreamlog.db",
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                    .also { instance = it }
            }
    }
}

/**
 * Pure text engine for Obsidian-style wiki links.
 *
 * A note carries the canonical text; the graph is derived from it. Links are undirected in
 * presentation: one cross-reference row links a note to a node, and backlinks are queried from
 * that row. This keeps [[Alias]]-free parsing deterministic and avoids duplicated edge rows.
 */
object WikiLinkEngine {
    val WIKI_LINK_REGEX: Regex = Regex("""\[\[([^\[\]]+)\]\]""")

    fun normalizeNodeName(raw: String): String? {
        val trimmed = raw.trim().replace(Regex("\\s+"), " ")
        return trimmed.takeIf { it.isNotEmpty() }
    }

    fun extractTargets(text: String): List<String> =
        WIKI_LINK_REGEX.findAll(text)
            .mapNotNull { normalizeNodeName(it.groupValues[1]) }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toList()

    /**
     * Wraps known node names in [[...]] while leaving existing wiki links and previously selected
     * matches untouched. Names are matched case-insensitively on whole-word boundaries and longer
     * names win when two names could occupy the same span. The replacement always uses the
     * canonical spelling from the nodes table.
     */
    fun autoLink(text: String, nodeNames: Collection<String>): String {
        val names = nodeNames
            .mapNotNull { normalizeNodeName(it) }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedByDescending { it.length }

        if (text.isBlank() || names.isEmpty()) return text

        val protectedRanges = WIKI_LINK_REGEX.findAll(text).map { it.range }.toList()
        val selectedRanges = mutableListOf<IntRange>()
        val replacements = mutableListOf<Replacement>()

        for (name in names) {
            val pattern = Pattern.compile("(?iuU)\\b${Pattern.quote(name)}\\b")
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val range = matcher.start() until matcher.end()
                if (protectedRanges.any { it.overlaps(range) }) continue
                if (selectedRanges.any { it.overlaps(range) }) continue
                selectedRanges += range
                replacements += Replacement(matcher.start(), matcher.end(), "[[$name]]")
            }
        }

        if (replacements.isEmpty()) return text

        val output = StringBuilder(text)
        replacements.sortedByDescending { it.start }.forEach { replacement ->
            output.replace(replacement.start, replacement.end, replacement.replacement)
        }
        return output.toString()
    }

    private fun IntRange.overlaps(other: IntRange): Boolean =
        start <= other.endInclusive && other.start <= endInclusive

    private data class Replacement(val start: Int, val end: Int, val replacement: String)
}

/**
 * Coordinates note text and graph rows atomically. Auto-linking happens before parsing so
 * automatic entities and manual entities follow exactly the same insertion path.
 */
class DreamRepository(private val database: DreamLogDatabase) {
    private val notes = database.dreamNoteDao()
    private val nodes = database.conceptNodeDao()
    private val refs = database.noteNodeCrossRefDao()
    private val alarmConfigs = database.alarmConfigDao()

    fun observeNotes() = notes.observeAll()
    fun observeNote(id: Long) = notes.observeById(id)
    fun observeNodes() = nodes.observeAll()
    fun observeCrossRefs() = refs.observeAll()
    fun observeNodesForNote(noteId: Long) = refs.observeNodesForNote(noteId)
    fun observeBacklinkNotes(noteId: Long) = refs.observeBacklinkNotes(noteId)
    fun observeNotesForNode(nodeId: Long) = refs.observeNotesForNode(nodeId)
    fun observeAlarmConfig() = alarmConfigs.observe()

    suspend fun getNote(id: Long): DreamNote? = notes.getById(id)

    suspend fun getAllNotes(): List<DreamNote> = notes.getAll()

    suspend fun createNote(
        title: String,
        body: String = "",
        audioPath: String? = null,
        audioDurationMs: Long = 0L,
        createdAt: Long = System.currentTimeMillis(),
    ): Long = database.withTransaction {
        insertNoteInternal(
            title = title,
            body = body,
            audioPath = audioPath,
            audioDurationMs = audioDurationMs,
            createdAt = createdAt,
        )
    }

    suspend fun updateNote(id: Long, title: String, body: String): DreamNote? =
        database.withTransaction {
            val existing = notes.getById(id) ?: return@withTransaction null
            val autoLinked = WikiLinkEngine.autoLink(body, nodes.getAllNames())
            val updated = existing.copy(
                title = title,
                body = autoLinked,
                updatedAt = System.currentTimeMillis(),
            )
            notes.update(updated)
            refs.deleteForNote(id)
            linkTargets(id, WikiLinkEngine.extractTargets(autoLinked))
            updated
        }

    suspend fun deleteNote(id: Long) {
        database.withTransaction {
            notes.deleteById(id)
            nodes.deleteOrphans()
        }
    }

    suspend fun importMarkdown(
        title: String,
        body: String,
        modifiedAt: Long = System.currentTimeMillis(),
    ): Long = database.withTransaction {
        insertNoteInternal(
            title = title,
            body = body,
            audioPath = null,
            audioDurationMs = 0L,
            createdAt = modifiedAt,
        )
    }

    suspend fun alarmConfig(): AlarmConfig = alarmConfigs.get() ?: AlarmConfig()

    suspend fun updateThresholds(
        minVoiceSeconds: Int,
        minWords: Int,
        duckedVolumePercent: Int,
    ): AlarmConfig = database.withTransaction {
        val current = alarmConfigs.get() ?: AlarmConfig()
        val updated = current.copy(
            minVoiceSeconds = minVoiceSeconds.coerceIn(1, 300),
            minWords = minWords.coerceIn(1, 500),
            duckedVolumePercent = duckedVolumePercent.coerceIn(1, 100),
        )
        alarmConfigs.upsert(updated)
        updated
    }

    suspend fun setScheduledAt(triggerAtMillis: Long): AlarmConfig = database.withTransaction {
        val current = alarmConfigs.get() ?: AlarmConfig()
        val updated = current.copy(scheduledAt = triggerAtMillis.coerceAtLeast(0L))
        alarmConfigs.upsert(updated)
        updated
    }

    private suspend fun insertNoteInternal(
        title: String,
        body: String,
        audioPath: String?,
        audioDurationMs: Long,
        createdAt: Long,
    ): Long {
        val existingNames = nodes.getAllNames()
        val autoLinkedBody = WikiLinkEngine.autoLink(body, existingNames)
        val now = System.currentTimeMillis()
        val noteId = notes.insert(
            DreamNote(
                title = title.ifBlank { "Untitled" },
                body = autoLinkedBody,
                createdAt = createdAt,
                updatedAt = now,
                audioPath = audioPath,
                audioDurationMs = audioDurationMs,
            ),
        )
        linkTargets(noteId, WikiLinkEngine.extractTargets(autoLinkedBody))
        return noteId
    }

    private suspend fun linkTargets(noteId: Long, targets: List<String>) {
        if (targets.isEmpty()) return
        val nodeIds = linkedSetOf<Long>()
        val now = System.currentTimeMillis()
        for (target in targets) {
            val existing = nodes.getByName(target)
            if (existing != null) {
                nodeIds += existing.id
                continue
            }
            val inserted = nodes.insert(ConceptNode(name = target, createdAt = now))
            if (inserted != -1L) {
                nodeIds += inserted
            } else {
                nodes.getByName(target)?.let { nodeIds += it.id }
            }
        }
        refs.insertAll(nodeIds.map { NoteNodeCrossRef(noteId = noteId, nodeId = it) })
    }
}
