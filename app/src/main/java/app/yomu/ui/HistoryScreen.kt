package app.yomu.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.yomu.data.HistoryEntry
import app.yomu.data.MangaRef
import app.yomu.data.Store
import coil.compose.AsyncImage

@Composable
fun HistoryScreen(onResume: (HistoryEntry) -> Unit, onOpenManga: (MangaRef) -> Unit) {
    val data by Store.data.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    YScaffold("Historique", actions = {
        if (data.history.isNotEmpty()) {
            IconButton(onClick = { confirmClear = true }) { Icon(Icons.Outlined.DeleteSweep, "Tout effacer") }
        }
    }) { pad ->
        if (data.history.isEmpty()) {
            Box(Modifier.padding(pad)) { CenterMessage("Aucune lecture récente") }
            return@YScaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(data.history, key = { it.manga.sourceId + "|" + it.manga.id }) { h ->
                ListItem(
                    modifier = Modifier.clickable { onResume(h) },
                    leadingContent = {
                        AsyncImage(
                            model = h.manga.cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(48.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onOpenManga(h.manga) },
                        )
                    },
                    headlineContent = { Text(h.manga.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            listOf(h.chapterName, DateUtils.getRelativeTimeSpanString(h.time).toString())
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            maxLines = 2,
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { Store.removeHistory(h) }) { Icon(Icons.Outlined.Delete, "Retirer") }
                    },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Effacer l'historique ?") },
            confirmButton = {
                TextButton(onClick = { Store.clearHistory(); confirmClear = false }) { Text("Effacer") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }
}
