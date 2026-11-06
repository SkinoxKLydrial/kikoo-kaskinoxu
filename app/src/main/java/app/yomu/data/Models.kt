package app.yomu.data

import kotlinx.serialization.Serializable

@Serializable
data class MangaRef(
    val sourceId: String,
    val id: String,
    val title: String,
    val cover: String? = null,
)

@Serializable
data class LibraryEntry(
    val manga: MangaRef,
    val addedAt: Long,
    val chapterIds: List<String> = emptyList(),
    val lastUpdate: Long = 0,
)

@Serializable
data class Progress(val read: Boolean = false, val page: Int = 0)

@Serializable
data class HistoryEntry(
    val manga: MangaRef,
    val chapterId: String,
    val chapterName: String,
    val time: Long,
)

@Serializable
enum class ReaderMode { RTL, LTR, WEBTOON }

@Serializable
data class Prefs(
    val languages: List<String> = listOf("fr", "en"),
    val readerMode: ReaderMode = ReaderMode.RTL,
    val localTreeUri: String? = null,
    val showSuggestive: Boolean = true,
    val autoCheckUpdates: Boolean = true,
)

@Serializable
data class AppData(
    val library: List<LibraryEntry> = emptyList(),
    val progress: Map<String, Progress> = emptyMap(),
    val history: List<HistoryEntry> = emptyList(),
    val prefs: Prefs = Prefs(),
)

fun progressKey(sourceId: String, chapterId: String) = "$sourceId|$chapterId"

fun AppData.isRead(sourceId: String, chapterId: String) =
    progress[progressKey(sourceId, chapterId)]?.read == true

fun AppData.libraryEntry(sourceId: String, id: String) =
    library.firstOrNull { it.manga.sourceId == sourceId && it.manga.id == id }
