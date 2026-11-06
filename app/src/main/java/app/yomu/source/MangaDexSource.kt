package app.yomu.source

import app.yomu.data.Net
import app.yomu.data.Store
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.time.OffsetDateTime

/** MangaDex, via son API publique officielle (https://api.mangadex.org/docs/). */
class MangaDexSource : Source {
    override val id = "mangadex"
    override val name = "MangaDex"
    override val description = "Bibliothèque en ligne, API officielle"

    private val api = "https://api.mangadex.org"
    private val pageSize = 24

    private fun langs() = Store.prefs.languages.ifEmpty { listOf("en") }
    private fun ratings() = if (Store.prefs.showSuggestive) listOf("safe", "suggestive") else listOf("safe")

    private fun mangaListUrl(page: Int, extra: String) = buildString {
        append("$api/manga?limit=$pageSize&offset=${(page - 1) * pageSize}")
        append("&includes[]=cover_art&includes[]=author")
        langs().forEach { append("&availableTranslatedLanguage[]=$it") }
        ratings().forEach { append("&contentRating[]=$it") }
        append(extra)
    }

    override suspend fun popular(page: Int) =
        parseList(Net.getString(mangaListUrl(page, "&order[followedCount]=desc")))

    override suspend fun search(query: String, page: Int) = parseList(
        Net.getString(mangaListUrl(page, "&title=${URLEncoder.encode(query, "UTF-8")}&order[relevance]=desc"))
    )

    override suspend fun details(mangaId: String): Manga {
        val body = Net.getString("$api/manga/$mangaId?includes[]=cover_art&includes[]=author")
        val data = Net.json.parseToJsonElement(body).jsonObject.obj("data") ?: error("Manga introuvable")
        return parseManga(data)
    }

    override suspend fun chapters(mangaId: String): List<Chapter> {
        val out = mutableListOf<Chapter>()
        val languages = langs()
        var offset = 0
        while (true) {
            val url = buildString {
                append("$api/manga/$mangaId/feed?limit=500&offset=$offset")
                append("&order[volume]=desc&order[chapter]=desc&includes[]=scanlation_group&includeExternalUrl=0")
                languages.forEach { append("&translatedLanguage[]=$it") }
                listOf("safe", "suggestive", "erotica").forEach { append("&contentRating[]=$it") }
            }
            val root = Net.json.parseToJsonElement(Net.getString(url)).jsonObject
            val data = root["data"] as? JsonArray ?: break
            data.forEach { el -> (el as? JsonObject)?.let { parseChapter(it, languages.size > 1) }?.let(out::add) }
            val total = root.int("total") ?: 0
            offset += data.size
            if (data.isEmpty() || offset >= total || offset + 500 > 10_000) break
        }
        // Les chapitres sans numéro (one-shots) vont à la fin.
        return out.sortedByDescending { it.number ?: -1f }
    }

    override suspend fun pages(chapterId: String): List<String> {
        val o = Net.json.parseToJsonElement(Net.getString("$api/at-home/server/$chapterId")).jsonObject
        val base = o.str("baseUrl") ?: error("Serveur d'images indisponible")
        val ch = o.obj("chapter") ?: error("Chapitre indisponible")
        val hash = ch.str("hash") ?: error("Chapitre indisponible")
        val files = ch["data"] as? JsonArray ?: return emptyList()
        return files.map { "$base/data/$hash/${it.jsonPrimitive.content}" }
    }

    private fun parseList(body: String): MangaPage {
        val root = Net.json.parseToJsonElement(body).jsonObject
        val data = (root["data"] as? JsonArray).orEmpty()
        val items = data.mapNotNull { (it as? JsonObject)?.let(::parseManga) }
        val total = root.int("total") ?: 0
        val offset = root.int("offset") ?: 0
        return MangaPage(items, offset + data.size < total && offset + 2 * pageSize <= 10_000)
    }

    private fun parseManga(o: JsonObject): Manga {
        val id = o.str("id") ?: error("Réponse invalide")
        val attr = o.obj("attributes") ?: JsonObject(emptyMap())
        val rels = (o["relationships"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val coverFile = rels.firstOrNull { it.str("type") == "cover_art" }?.obj("attributes")?.str("fileName")
        val author = rels.filter { it.str("type") == "author" }
            .mapNotNull { it.obj("attributes")?.str("name") }
            .distinct().joinToString(", ").ifBlank { null }
        val title = localized(attr.obj("title"))
            ?: (attr["altTitles"] as? JsonArray)?.firstNotNullOfOrNull { localized(it as? JsonObject) }
            ?: "Sans titre"
        val tags = (attr["tags"] as? JsonArray).orEmpty()
            .mapNotNull { localized((it as? JsonObject)?.obj("attributes")?.obj("name")) }
        val status = when (attr.str("status")) {
            "ongoing" -> "En cours"
            "completed" -> "Terminé"
            "hiatus" -> "En pause"
            "cancelled" -> "Annulé"
            else -> null
        }
        return Manga(
            id = id,
            title = title,
            cover = coverFile?.let { "https://uploads.mangadex.org/covers/$id/$it.256.jpg" },
            description = localized(attr.obj("description")),
            author = author,
            status = status,
            tags = tags,
        )
    }

    private fun parseChapter(o: JsonObject, showLang: Boolean): Chapter? {
        val id = o.str("id") ?: return null
        val attr = o.obj("attributes") ?: return null
        if (attr.str("externalUrl") != null) return null
        if ((attr.int("pages") ?: 0) == 0) return null
        val num = attr.str("chapter")
        val vol = attr.str("volume")
        val title = attr.str("title")
        val name = buildString {
            if (vol != null) append("Vol. $vol ")
            append(if (num != null) "Ch. $num" else "One-shot")
            if (!title.isNullOrBlank()) append(" – $title")
            if (showLang) attr.str("translatedLanguage")?.let { append(" [${it.uppercase()}]") }
        }
        val group = (o["relationships"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("type") == "scanlation_group" }
            ?.obj("attributes")?.str("name")
        val date = attr.str("publishAt")
            ?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L
        return Chapter(id, name, num?.toFloatOrNull(), date, group)
    }

    private fun localized(o: JsonObject?): String? {
        if (o == null || o.isEmpty()) return null
        for (l in langs() + listOf("en", "ja-ro")) {
            o.str(l)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return o.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    }
}

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
