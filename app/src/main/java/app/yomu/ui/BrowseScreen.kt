package app.yomu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.yomu.source.Manga
import app.yomu.source.Source
import app.yomu.source.Sources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun SourcesScreen(onOpen: (String) -> Unit) {
    YScaffold("Explorer") { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Sources.all, key = { it.id }) { s ->
                ElevatedCard(onClick = { onOpen(s.id) }, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(s.name) },
                        supportingContent = { Text(s.description) },
                        leadingContent = {
                            Icon(if (s.id == "local") Icons.Outlined.Folder else Icons.Outlined.Public, null)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

class BrowseViewModel : ViewModel() {
    private var source: Source? = null
    val items = mutableStateListOf<Manga>()
    var query by mutableStateOf("")
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var hasNext = true
    private var page = 0
    private var activeQuery = ""
    private var job: Job? = null

    fun attach(sourceId: String) {
        if (source != null) return
        source = Sources.get(sourceId)
        reload()
    }

    fun search() {
        activeQuery = query.trim()
        reload()
    }

    fun reload() {
        job?.cancel()
        items.clear()
        page = 0
        hasNext = true
        loading = false
        loadMore()
    }

    fun loadMore() {
        val src = source ?: return
        if (loading || !hasNext) return
        loading = true
        error = null
        val q = activeQuery
        job = viewModelScope.launch {
            try {
                val p = page + 1
                val r = if (q.isBlank()) src.popular(p) else src.search(q, p)
                val known = items.mapTo(HashSet()) { it.id }
                items.addAll(r.items.filter { known.add(it.id) })
                page = p
                hasNext = r.hasNext
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.friendly()
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun BrowseScreen(sourceId: String, onBack: () -> Unit, onOpen: (Manga) -> Unit) {
    val vm: BrowseViewModel = viewModel()
    LaunchedEffect(sourceId) { vm.attach(sourceId) }
    val gridState = rememberLazyGridState()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last -> if (vm.items.isNotEmpty() && last >= vm.items.size - 6) vm.loadMore() }
    }

    YScaffold(Sources.get(sourceId).name, onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            SearchField(vm.query, { vm.query = it }, "Rechercher…", onSearch = { vm.search() })
            val err = vm.error
            when {
                vm.items.isEmpty() && vm.loading -> Loading()
                vm.items.isEmpty() && err != null -> CenterMessage(err) {
                    Button(onClick = { vm.reload() }) { Text("Réessayer") }
                }
                vm.items.isEmpty() -> CenterMessage("Aucun résultat")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    state = gridState,
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp + bottom),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(vm.items, key = { it.id }) { m ->
                        CoverCard(m.title, m.cover) { onOpen(m) }
                    }
                    if (vm.loading || err != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                if (vm.loading) {
                                    CircularProgressIndicator()
                                } else {
                                    TextButton(onClick = { vm.loadMore() }) { Text("Erreur — réessayer") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
