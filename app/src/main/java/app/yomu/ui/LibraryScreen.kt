package app.yomu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yomu.data.LibraryUpdater
import app.yomu.data.MangaRef
import app.yomu.data.Store
import app.yomu.data.isRead

@Composable
fun LibraryScreen(onOpen: (MangaRef) -> Unit) {
    val data by Store.data.collectAsState()
    val updating by LibraryUpdater.running.collectAsState()
    var filter by rememberSaveable { mutableStateOf("") }

    YScaffold("Bibliothèque", actions = {
        IconButton(onClick = { LibraryUpdater.start() }, enabled = !updating) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Chercher les nouveaux chapitres")
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (updating) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (data.library.isEmpty()) {
                CenterMessage("Ta bibliothèque est vide.\nVa dans « Explorer » pour ajouter des mangas.")
                return@Column
            }
            if (data.library.size > 8) SearchField(filter, { filter = it }, "Filtrer")

            val entries = data.library
                .map { e -> e to e.chapterIds.count { !data.isRead(e.manga.sourceId, it) } }
                .filter { filter.isBlank() || it.first.manga.title.contains(filter, ignoreCase = true) }
                .sortedWith(
                    compareByDescending<Pair<app.yomu.data.LibraryEntry, Int>> { it.second > 0 }
                        .thenBy { it.first.manga.title.lowercase() }
                )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries, key = { it.first.manga.sourceId + "|" + it.first.manga.id }) { (e, unread) ->
                    CoverCard(e.manga.title, e.manga.cover, unread) { onOpen(e.manga) }
                }
            }
        }
    }
}
