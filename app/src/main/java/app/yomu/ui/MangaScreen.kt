@file:OptIn(ExperimentalFoundationApi::class)

package app.yomu.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yomu.data.Store
import app.yomu.data.isRead
import app.yomu.data.libraryEntry
import app.yomu.data.progressKey
import app.yomu.download.Downloader
import app.yomu.source.Cache
import app.yomu.source.Chapter
import app.yomu.source.Manga
import app.yomu.source.Sources
import app.yomu.source.toRef
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException

@Composable
fun MangaScreen(sourceId: String, mangaId: String, onBack: () -> Unit, onRead: (String) -> Unit) {
    val source = remember(sourceId) { Sources.get(sourceId) }
    var manga by remember { mutableStateOf(Cache.cachedManga(sourceId, mangaId)) }
    var chapters by remember { mutableStateOf(Cache.cachedChapters(sourceId, mangaId)) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val data by Store.data.collectAsState()
    val active by Downloader.active.collectAsState()
    val dlVersion by Downloader.version.collectAsState()
    val inLibrary = data.libraryEntry(sourceId, mangaId) != null
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(refreshKey) {
        val force = refreshKey > 0
        refreshing = true
        error = null
        try {
            if (manga == null || force) manga = Cache.manga(sourceId, mangaId, refresh = true)
            if (chapters == null || force) {
                val ch = Cache.chapters(sourceId, mangaId, refresh = true)
                chapters = ch
                if (Store.data.value.libraryEntry(sourceId, mangaId) != null) {
                    Store.setChapters(sourceId, mangaId, ch.map { it.id })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.friendly()
        } finally {
            refreshing = false
        }
    }

    val ch = chapters
    val m = manga
    val downloaded = remember(ch, dlVersion) {
        if (!source.canDownload || ch == null) emptySet()
        else ch.filter { Downloader.isDownloaded(sourceId, it.id) }.mapTo(HashSet()) { it.id }
    }

    // Chapitre à reprendre : le dernier lu (s'il n'est pas fini), sinon le suivant, sinon le premier non lu.
    val resume: Pair<Chapter, String>? = ch?.takeIf { it.isNotEmpty() }?.let { list ->
        val last = data.history.firstOrNull { it.manga.sourceId == sourceId && it.manga.id == mangaId }
        val lastIdx = last?.let { h -> list.indexOfFirst { it.id == h.chapterId } } ?: -1
        when {
            lastIdx >= 0 && !data.isRead(sourceId, list[lastIdx].id) -> list[lastIdx] to "Continuer"
            lastIdx > 0 -> list[lastIdx - 1] to "Suivant"
            lastIdx == 0 -> list[0] to "Relire"
            else -> (list.lastOrNull { !data.isRead(sourceId, it.id) } ?: list.last()) to "Commencer"
        }
    }

    YScaffold(m?.title ?: "", onBack = onBack, actions = {
        IconButton(onClick = { refreshKey++ }, enabled = !refreshing) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Actualiser")
        }
        if (ch != null) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Plus") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (source.canDownload) {
                        DropdownMenuItem(text = { Text("Télécharger les non lus") }, onClick = {
                            menu = false
                            ch.filter { !data.isRead(sourceId, it.id) }.reversed()
                                .forEach { Downloader.enqueue(sourceId, it.id) }
                        })
                    }
                    DropdownMenuItem(text = { Text("Tout marquer comme lu") }, onClick = {
                        menu = false
                        Store.setRead(sourceId, ch.map { it.id }, true)
                    })
                    DropdownMenuItem(text = { Text("Tout marquer comme non lu") }, onClick = {
                        menu = false
                        Store.setRead(sourceId, ch.map { it.id }, false)
                    })
                }
            }
        }
    }) { pad ->
        if (m == null && ch == null) {
            Box(Modifier.padding(pad)) {
                val err = error
                if (err != null) {
                    CenterMessage(err) { Button(onClick = { refreshKey++ }) { Text("Réessayer") } }
                } else {
                    Loading()
                }
            }
            return@YScaffold
        }

        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottom + 16.dp),
        ) {
            item {
                MangaHeader(
                    m = m,
                    inLibrary = inLibrary,
                    resumeLabel = resume?.second,
                    onToggleLibrary = {
                        m?.let { Store.toggleLibrary(it.toRef(sourceId), ch?.map { c -> c.id }.orEmpty()) }
                    },
                    onResume = { resume?.let { onRead(it.first.id) } },
                )
            }
            if (refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
            error?.let { err ->
                item { Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            }
            if (ch != null) {
                item {
                    Text(
                        if (ch.size > 1) "${ch.size} chapitres" else "${ch.size} chapitre",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(ch, key = { it.id }) { c ->
                    val p = data.progress[progressKey(sourceId, c.id)]
                    val read = p?.read == true
                    ChapterRow(
                        c = c,
                        read = read,
                        page = p?.page ?: 0,
                        downloaded = c.id in downloaded,
                        dlProgress = active[Downloader.key(sourceId, c.id)],
                        canDownload = source.canDownload,
                        onClick = { onRead(c.id) },
                        onToggleRead = { Store.setRead(sourceId, listOf(c.id), !read) },
                        onMarkPreviousRead = {
                            val i = ch.indexOf(c)
                            Store.setRead(sourceId, ch.drop(i + 1).map { it.id }, true)
                        },
                        onDownload = { Downloader.enqueue(sourceId, c.id) },
                        onDeleteDownload = { Downloader.delete(sourceId, c.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MangaHeader(
    m: Manga?,
    inLibrary: Boolean,
    resumeLabel: String?,
    onToggleLibrary: () -> Unit,
    onResume: () -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        Row {
            AsyncImage(
                model = m?.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(110.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(m?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                m?.author?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                m?.status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onToggleLibrary, enabled = m != null, modifier = Modifier.weight(1f)) {
                Icon(if (inLibrary) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (inLibrary) "Dans la biblio" else "Ajouter")
            }
            if (resumeLabel != null) {
                Button(onClick = onResume, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(resumeLabel)
                }
            }
        }
        val desc = m?.description
        if (!desc.isNullOrBlank()) {
            var expanded by remember { mutableStateOf(false) }
            Text(
                desc,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp).clickable { expanded = !expanded },
            )
        }
        if (m != null && m.tags.isNotEmpty()) {
            Text(
                m.tags.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ChapterRow(
    c: Chapter,
    read: Boolean,
    page: Int,
    downloaded: Boolean,
    dlProgress: Float?,
    canDownload: Boolean,
    onClick: () -> Unit,
    onToggleRead: () -> Unit,
    onMarkPreviousRead: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val faded = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        headlineContent = {
            Text(
                c.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (read) faded else Color.Unspecified,
            )
        },
        supportingContent = {
            val parts = listOfNotNull(
                formatDate(c.date).ifBlank { null },
                c.group,
                if (!read && page > 0) "Page ${page + 1}" else null,
            )
            if (parts.isNotEmpty()) {
                Text(
                    parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (read) faded else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Box(contentAlignment = Alignment.Center) {
                when {
                    dlProgress != null -> CircularProgressIndicator(
                        progress = { dlProgress },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    downloaded -> Icon(Icons.Filled.DownloadDone, "Téléchargé", tint = MaterialTheme.colorScheme.primary)
                    canDownload -> IconButton(onClick = onDownload) { Icon(Icons.Outlined.Download, "Télécharger") }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (read) "Marquer comme non lu" else "Marquer comme lu") },
                        onClick = { menu = false; onToggleRead() },
                    )
                    DropdownMenuItem(
                        text = { Text("Marquer les précédents comme lus") },
                        onClick = { menu = false; onMarkPreviousRead() },
                    )
                    if (downloaded) {
                        DropdownMenuItem(
                            text = { Text("Supprimer le téléchargement") },
                            onClick = { menu = false; onDeleteDownload() },
                        )
                    }
                }
            }
        },
    )
}
