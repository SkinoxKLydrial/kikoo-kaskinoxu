package app.yomu.source

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.yomu.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Mangas stockés sur le téléphone, dans le dossier choisi dans les réglages.
 * Structure : un sous-dossier par série, puis un CBZ/ZIP ou un dossier d'images par chapitre.
 * Un CBZ posé directement à la racine compte comme une série d'un seul chapitre.
 */
class LocalSource(private val context: Context) : Source {
    override val id = "local"
    override val name = "Fichiers locaux"
    override val description = "CBZ, ZIP et dossiers d'images sur ton téléphone"
    override val canDownload = false

    private data class Doc(val id: String, val name: String, val mime: String) {
        val isDir get() = mime == Document.MIME_TYPE_DIR
        val isArchive get() = name.lowercase().let { it.endsWith(".cbz") || it.endsWith(".zip") }
        val isImage get() = mime.startsWith("image/") || isImageName(name)
        val baseName get() = if (isDir) name else name.substringBeforeLast('.')
    }

    private val projection = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
    )

    private fun tree(): Uri = Store.prefs.localTreeUri?.let(Uri::parse)
        ?: throw IllegalStateException("Choisis d'abord un dossier dans Réglages → Dossier des mangas.")

    private fun uriOf(tree: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    private fun children(tree: Uri, docId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val out = mutableListOf<Doc>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) out += Doc(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "")
        }
        return out.filterNot { it.name.startsWith(".") }.sortedWith(compareBy(NaturalOrder) { it.name })
    }

    private fun doc(tree: Uri, docId: String): Doc {
        context.contentResolver.query(uriOf(tree, docId), projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) return Doc(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "")
        }
        throw IllegalStateException("Fichier introuvable")
    }

    override suspend fun popular(page: Int): MangaPage = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext MangaPage(emptyList(), false)
        val tree = tree()
        val root = DocumentsContract.getTreeDocumentId(tree)
        val items = children(tree, root)
            .filter { it.isDir || it.isArchive }
            .map { Manga(it.id, it.baseName, cover = coverOf(tree, it)) }
        MangaPage(items, false)
    }

    override suspend fun search(query: String, page: Int): MangaPage {
        if (page > 1) return MangaPage(emptyList(), false)
        val all = popular(1).items
        return MangaPage(all.filter { it.title.contains(query, ignoreCase = true) }, false)
    }

    override suspend fun details(mangaId: String): Manga = withContext(Dispatchers.IO) {
        val tree = tree()
        val d = doc(tree, mangaId)
        Manga(d.id, d.baseName, cover = coverOf(tree, d))
    }

    override suspend fun chapters(mangaId: String): List<Chapter> = withContext(Dispatchers.IO) {
        val tree = tree()
        val d = doc(tree, mangaId)
        if (!d.isDir) return@withContext listOf(Chapter(d.id, d.baseName))
        val chapterDocs = children(tree, d.id).filter { it.isDir || it.isArchive }
        if (chapterDocs.isEmpty()) {
            listOf(Chapter(d.id, d.baseName))
        } else {
            chapterDocs.reversed().mapIndexed { i, c ->
                Chapter(c.id, c.baseName, number = (chapterDocs.size - i).toFloat())
            }
        }
    }

    override suspend fun pages(chapterId: String): List<String> = withContext(Dispatchers.IO) {
        val tree = tree()
        val d = doc(tree, chapterId)
        if (d.isDir) {
            children(tree, d.id).filter { it.isImage }.map { uriOf(tree, it.id).toString() }
        } else {
            extractArchive(tree, d)
        }
    }

    private fun coverOf(tree: Uri, d: Doc): String? = runCatching {
        if (d.isArchive) return@runCatching firstImageOfArchive(tree, d)
        val kids = children(tree, d.id)
        kids.firstOrNull { it.isImage && it.baseName.equals("cover", ignoreCase = true) }
            ?.let { return@runCatching uriOf(tree, it.id).toString() }
        kids.firstOrNull { it.isImage }?.let { return@runCatching uriOf(tree, it.id).toString() }
        val first = kids.firstOrNull { it.isDir || it.isArchive } ?: return@runCatching null
        if (first.isArchive) {
            firstImageOfArchive(tree, first)
        } else {
            children(tree, first.id).firstOrNull { it.isImage }?.let { uriOf(tree, it.id).toString() }
        }
    }.getOrNull()

    private fun firstImageOfArchive(tree: Uri, d: Doc): String? {
        val out = File(context.cacheDir, "local_covers/${d.id.hashCode()}.img")
        if (out.exists()) return Uri.fromFile(out).toString()
        out.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uriOf(tree, d.id))?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory && isImageName(e.name)) {
                        out.outputStream().use { zip.copyTo(it) }
                        return Uri.fromFile(out).toString()
                    }
                }
            }
        }
        return null
    }

    private fun extractArchive(tree: Uri, d: Doc): List<String> {
        val dir = File(context.cacheDir, "local_chapters/${d.id.hashCode()}")
        val done = File(dir, ".done")
        if (!done.exists()) {
            dir.deleteRecursively()
            dir.mkdirs()
            val input = context.contentResolver.openInputStream(uriOf(tree, d.id))
                ?: throw IllegalStateException("Impossible d'ouvrir l'archive")
            input.use {
                ZipInputStream(it.buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.isDirectory || !isImageName(e.name)) continue
                        val safeName = e.name.replace('/', '_').replace('\\', '_')
                        File(dir, safeName).outputStream().use { out -> zip.copyTo(out) }
                    }
                }
            }
            done.createNewFile()
        }
        return dir.listFiles().orEmpty()
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .map { Uri.fromFile(it).toString() }
    }

    companion object {
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")

        fun isImageName(name: String): Boolean {
            val file = name.substringAfterLast('/')
            return !file.startsWith(".") && file.substringAfterLast('.', "").lowercase() in IMAGE_EXT
        }
    }
}
