package app.yomu.download

import android.content.Context
import android.net.Uri
import app.yomu.data.Net
import app.yomu.source.Sources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Télécharge les chapitres un par un pour la lecture hors ligne. */
object Downloader {
    private lateinit var root: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** Chapitres en cours ou en attente → progression (0..1). */
    private val _active = MutableStateFlow<Map<String, Float>>(emptyMap())
    val active: StateFlow<Map<String, Float>> = _active.asStateFlow()

    /** Change à chaque téléchargement terminé ou supprimé. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    fun init(context: Context) {
        root = File(context.filesDir, "downloads")
    }

    fun key(sourceId: String, chapterId: String) = "$sourceId|$chapterId"

    private fun dir(sourceId: String, chapterId: String) =
        File(root, "$sourceId/${chapterId.replace(Regex("[^A-Za-z0-9_-]"), "_")}")

    fun isDownloaded(sourceId: String, chapterId: String) = File(dir(sourceId, chapterId), ".done").exists()

    fun localPages(sourceId: String, chapterId: String): List<String>? {
        if (!isDownloaded(sourceId, chapterId)) return null
        return dir(sourceId, chapterId).listFiles().orEmpty()
            .filter { !it.name.startsWith(".") }
            .sortedBy { it.name }
            .map { Uri.fromFile(it).toString() }
    }

    fun enqueue(sourceId: String, chapterId: String) {
        val k = key(sourceId, chapterId)
        if (k in _active.value || isDownloaded(sourceId, chapterId)) return
        _active.update { it + (k to 0f) }
        scope.launch {
            mutex.withLock {
                try {
                    download(sourceId, chapterId, k)
                } catch (e: Exception) {
                    dir(sourceId, chapterId).deleteRecursively()
                    _errors.tryEmit("Échec du téléchargement : ${e.message ?: e}")
                } finally {
                    _active.update { it - k }
                    _version.update { it + 1 }
                }
            }
        }
    }

    private suspend fun download(sourceId: String, chapterId: String, k: String) {
        val pages = Sources.get(sourceId).pages(chapterId)
        val d = dir(sourceId, chapterId)
        d.deleteRecursively()
        d.mkdirs()
        pages.forEachIndexed { i, url ->
            val ext = url.substringAfterLast('/').substringAfterLast('.', "jpg")
                .filter { it.isLetterOrDigit() }.take(5).ifEmpty { "jpg" }
            val f = File(d, "%04d.%s".format(i, ext))
            withContext(Dispatchers.IO) {
                Net.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("Image vide")
                    body.byteStream().use { input -> f.outputStream().use { input.copyTo(it) } }
                }
            }
            _active.update { it + (k to (i + 1f) / pages.size) }
        }
        File(d, ".done").createNewFile()
    }

    fun delete(sourceId: String, chapterId: String) {
        dir(sourceId, chapterId).deleteRecursively()
        _version.update { it + 1 }
    }

    fun deleteAll() {
        root.deleteRecursively()
        _version.update { it + 1 }
    }
}
