package app.yomu.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Toutes les données de l'appli, gardées en mémoire et sauvegardées dans un fichier JSON. */
object Store {
    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var saveJob: Job? = null

    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data.asStateFlow()
    val prefs: Prefs get() = _data.value.prefs

    fun init(context: Context) {
        file = File(context.filesDir, "data.json")
        if (file.exists()) {
            runCatching {
                _data.value = Net.json.decodeFromString(AppData.serializer(), file.readText())
            }
        }
    }

    fun update(transform: (AppData) -> AppData) {
        _data.update(transform)
        scheduleSave()
    }

    @Synchronized
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            withContext(NonCancellable) {
                mutex.withLock {
                    val tmp = File(file.parentFile, "data.json.tmp")
                    tmp.writeText(Net.json.encodeToString(AppData.serializer(), _data.value))
                    if (!tmp.renameTo(file)) {
                        file.delete()
                        tmp.renameTo(file)
                    }
                }
            }
        }
    }

    fun toggleLibrary(manga: MangaRef, chapterIds: List<String>) = update { d ->
        if (d.libraryEntry(manga.sourceId, manga.id) != null) {
            d.copy(library = d.library.filterNot { it.manga.sourceId == manga.sourceId && it.manga.id == manga.id })
        } else {
            val now = System.currentTimeMillis()
            d.copy(library = d.library + LibraryEntry(manga, now, chapterIds, now))
        }
    }

    fun setChapters(sourceId: String, id: String, chapterIds: List<String>) = update { d ->
        d.copy(library = d.library.map {
            if (it.manga.sourceId == sourceId && it.manga.id == id) {
                it.copy(chapterIds = chapterIds, lastUpdate = System.currentTimeMillis())
            } else it
        })
    }

    fun setProgress(sourceId: String, chapterId: String, page: Int, read: Boolean) = update { d ->
        val key = progressKey(sourceId, chapterId)
        val old = d.progress[key] ?: Progress()
        d.copy(progress = d.progress + (key to Progress(read = old.read || read, page = page)))
    }

    fun setRead(sourceId: String, chapterIds: Collection<String>, read: Boolean) = update { d ->
        val p = d.progress.toMutableMap()
        chapterIds.forEach { p[progressKey(sourceId, it)] = Progress(read = read, page = 0) }
        d.copy(progress = p)
    }

    fun addHistory(entry: HistoryEntry) = update { d ->
        val others = d.history.filterNot { it.manga.sourceId == entry.manga.sourceId && it.manga.id == entry.manga.id }
        d.copy(history = (listOf(entry) + others).take(300))
    }

    fun removeHistory(entry: HistoryEntry) = update { d -> d.copy(history = d.history - entry) }

    fun clearHistory() = update { it.copy(history = emptyList()) }

    fun updatePrefs(transform: (Prefs) -> Prefs) = update { it.copy(prefs = transform(it.prefs)) }
}
