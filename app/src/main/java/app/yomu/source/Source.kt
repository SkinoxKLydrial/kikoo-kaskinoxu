package app.yomu.source

import android.content.Context
import app.yomu.data.MangaRef
import java.util.concurrent.ConcurrentHashMap

data class Manga(
    val id: String,
    val title: String,
    val cover: String? = null,
    val description: String? = null,
    val author: String? = null,
    val status: String? = null,
    val tags: List<String> = emptyList(),
)

data class Chapter(
    val id: String,
    val name: String,
    val number: Float? = null,
    val date: Long = 0,
    val group: String? = null,
)

data class MangaPage(val items: List<Manga>, val hasNext: Boolean)

/**
 * Une source de mangas. Pour en ajouter une, il suffit d'implémenter cette interface
 * et de l'ajouter dans [Sources.init]. Les chapitres sont triés du plus récent au plus ancien.
 */
interface Source {
    val id: String
    val name: String
    val description: String
    val canDownload: Boolean get() = true

    suspend fun popular(page: Int): MangaPage
    suspend fun search(query: String, page: Int): MangaPage
    suspend fun details(mangaId: String): Manga
    suspend fun chapters(mangaId: String): List<Chapter>

    /** Adresses des images (http(s)://, content:// ou file://). */
    suspend fun pages(chapterId: String): List<String>
}

fun Manga.toRef(sourceId: String) = MangaRef(sourceId, id, title, cover)

object Sources {
    lateinit var all: List<Source>
        private set

    fun init(context: Context) {
        all = listOf(MangaDexSource(), LocalSource(context.applicationContext))
    }

    fun get(id: String): Source = all.first { it.id == id }
}

/** Cache mémoire des fiches et listes de chapitres, pour éviter de tout recharger. */
object Cache {
    private val manga = ConcurrentHashMap<String, Manga>()
    private val chapters = ConcurrentHashMap<String, List<Chapter>>()
    private fun key(sourceId: String, id: String) = "$sourceId|$id"

    fun putManga(sourceId: String, m: Manga) {
        manga[key(sourceId, m.id)] = m
    }

    fun cachedManga(sourceId: String, id: String): Manga? = manga[key(sourceId, id)]
    fun cachedChapters(sourceId: String, id: String): List<Chapter>? = chapters[key(sourceId, id)]

    suspend fun manga(sourceId: String, id: String, refresh: Boolean = false): Manga {
        val k = key(sourceId, id)
        if (!refresh) manga[k]?.let { return it }
        return Sources.get(sourceId).details(id).also { manga[k] = it }
    }

    suspend fun chapters(sourceId: String, id: String, refresh: Boolean = false): List<Chapter> {
        val k = key(sourceId, id)
        if (!refresh) chapters[k]?.let { return it }
        return Sources.get(sourceId).chapters(id).also { chapters[k] = it }
    }
}

/** Tri « naturel » : 2 avant 10. */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                p.toBigInteger().compareTo(q.toBigInteger())
            } else {
                p.compareTo(q)
            }
            if (c != 0) return c
        }
        return x.size - y.size
    }
}
