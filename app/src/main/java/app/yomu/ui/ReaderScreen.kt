package app.yomu.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.yomu.data.HistoryEntry
import app.yomu.data.ReaderMode
import app.yomu.data.Store
import app.yomu.data.progressKey
import app.yomu.download.Downloader
import app.yomu.source.Cache
import app.yomu.source.Sources
import app.yomu.source.toRef
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(sourceId: String, mangaId: String, initialChapterId: String, onBack: () -> Unit) {
    val source = remember(sourceId) { Sources.get(sourceId) }
    var chapterId by rememberSaveable { mutableStateOf(initialChapterId) }
    var chapters by remember { mutableStateOf(Cache.cachedChapters(sourceId, mangaId)) }
    var pages by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    val data by Store.data.collectAsState()
    val mode = data.prefs.readerMode

    LaunchedEffect(Unit) {
        if (chapters == null) chapters = runCatching { Cache.chapters(sourceId, mangaId) }.getOrNull()
    }
    LaunchedEffect(chapterId, retry) {
        pages = null
        error = null
        try {
            val list = withContext(Dispatchers.IO) { Downloader.localPages(sourceId, chapterId) }
                ?: source.pages(chapterId)
            if (list.isEmpty()) error = "Ce chapitre ne contient aucune page" else pages = list
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.friendly()
        }
    }

    val list = chapters
    val chapter = list?.firstOrNull { it.id == chapterId }
    val idx = list?.indexOfFirst { it.id == chapterId } ?: -1
    val next = if (list != null && idx > 0) list[idx - 1] else null
    val prev = if (list != null && idx >= 0) list.getOrNull(idx + 1) else null
    val mangaTitle = Cache.cachedManga(sourceId, mangaId)?.title.orEmpty()

    LaunchedEffect(chapterId, chapter != null) {
        val m = Cache.cachedManga(sourceId, mangaId)
            ?: runCatching { Cache.manga(sourceId, mangaId) }.getOrNull()
            ?: return@LaunchedEffect
        Store.addHistory(HistoryEntry(m.toRef(sourceId), chapterId, chapter?.name.orEmpty(), System.currentTimeMillis()))
    }

    // Plein écran quand le menu est caché, écran toujours allumé pendant la lecture.
    val view = LocalView.current
    DisposableEffect(showMenu) {
        (view.context as? Activity)?.window?.let { window ->
            val c = WindowCompat.getInsetsController(window, view)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (showMenu) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            (view.context as? Activity)?.window?.let {
                WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val p = pages
        val err = error
        when {
            err != null -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(err, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { retry++ }) { Text("Réessayer") }
                    OutlinedButton(onClick = onBack) { Text("Retour", color = Color.White) }
                }
            }
            p == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
            else -> key(chapterId, p) {
                val start = remember {
                    Store.data.value.progress[progressKey(sourceId, chapterId)]
                        ?.takeIf { !it.read }?.page?.coerceIn(0, p.size - 1) ?: 0
                }
                val scope = rememberCoroutineScope()
                val goNext: () -> Unit = { next?.let { chapterId = it.id } }
                val goPrev: () -> Unit = { prev?.let { chapterId = it.id } }
                val toggleMenu: () -> Unit = { showMenu = !showMenu }

                if (mode == ReaderMode.WEBTOON) {
                    val listState = rememberLazyListState(start)
                    LaunchedEffect(listState) {
                        snapshotFlow { listState.firstVisibleItemIndex to listState.canScrollForward }
                            .collect { (i, more) ->
                                Store.setProgress(sourceId, chapterId, i.coerceIn(0, p.size - 1), read = !more || i >= p.size - 1)
                            }
                    }
                    WebtoonReader(p, listState, next?.name, onTap = toggleMenu, onNext = goNext)
                    if (showMenu) {
                        ReaderMenu(
                            title = mangaTitle, chapterName = chapter?.name.orEmpty(),
                            page = listState.firstVisibleItemIndex.coerceAtMost(p.size - 1), pageCount = p.size,
                            mode = mode, hasPrev = prev != null, hasNext = next != null,
                            onBack = onBack, onSeek = { scope.launch { listState.scrollToItem(it) } },
                            onPrev = goPrev, onNext = goNext,
                        )
                    }
                } else {
                    val pagerState = rememberPagerState(initialPage = start) { p.size + 1 }
                    LaunchedEffect(pagerState) {
                        snapshotFlow { pagerState.currentPage }.collect { i ->
                            Store.setProgress(sourceId, chapterId, i.coerceAtMost(p.size - 1), read = i >= p.size - 1)
                        }
                    }
                    PagedReader(p, pagerState, rtl = mode == ReaderMode.RTL, nextName = next?.name, onTap = toggleMenu, onNext = goNext)
                    if (showMenu) {
                        ReaderMenu(
                            title = mangaTitle, chapterName = chapter?.name.orEmpty(),
                            page = pagerState.currentPage.coerceAtMost(p.size - 1), pageCount = p.size,
                            mode = mode, hasPrev = prev != null, hasNext = next != null,
                            onBack = onBack, onSeek = { scope.launch { pagerState.scrollToPage(it) } },
                            onPrev = goPrev, onNext = goNext,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PagedReader(
    pages: List<String>,
    state: PagerState,
    rtl: Boolean,
    nextName: String?,
    onTap: () -> Unit,
    onNext: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    HorizontalPager(
        state = state,
        reverseLayout = rtl,
        beyondViewportPageCount = 2,
        modifier = Modifier.fillMaxSize(),
    ) { i ->
        if (i < pages.size) {
            ZoomableImage(pages[i]) { pos, width ->
                // Zones de toucher : un tiers de chaque côté pour tourner la page, le centre pour le menu.
                val third = width / 3f
                val left = pos.x < third
                val right = pos.x > width - third
                val forward = if (rtl) left else right
                val backward = if (rtl) right else left
                when {
                    forward -> scope.launch { state.animateScrollToPage(i + 1) }
                    backward -> scope.launch { state.animateScrollToPage((i - 1).coerceAtLeast(0)) }
                    else -> onTap()
                }
            }
        } else {
            EndOfChapter(nextName, onNext, onTap, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ZoomableImage(url: String, onTap: (Offset, Float) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var retry by remember { mutableIntStateOf(0) }
    val tap by rememberUpdatedState(onTap)
    val ctx = LocalContext.current

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
                    },
                    onTap = { pos -> if (scale <= 1f) tap(pos, size.width.toFloat()) },
                )
            }
            .pointerInput(Unit) {
                // Pincer pour zoomer ; une fois zoomé, un doigt déplace l'image au lieu de tourner la page.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            val pan = event.calculatePan()
                            val maxX = size.width * (scale - 1f) / 2f
                            val maxY = size.height * (scale - 1f) / 2f
                            offset = if (scale <= 1f) Offset.Zero else Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(ctx).data(url).setParameter("retry", retry, null).build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            },
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { retry++ }) { Text("Erreur de chargement — réessayer", color = Color.White) }
                }
            },
        )
    }
}

@Composable
private fun WebtoonReader(
    pages: List<String>,
    listState: LazyListState,
    nextName: String?,
    onTap: () -> Unit,
    onNext: () -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { tap() } },
    ) {
        items(pages) { url ->
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
                loading = {
                    Box(Modifier.fillMaxWidth().height(480.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                },
                error = {
                    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                        Text("Erreur de chargement", color = Color.White)
                    }
                },
            )
        }
        item {
            EndOfChapter(nextName, onNext, onTap, Modifier.fillMaxWidth().height(320.dp))
        }
    }
}

@Composable
private fun EndOfChapter(nextName: String?, onNext: () -> Unit, onTap: () -> Unit, modifier: Modifier) {
    val tap by rememberUpdatedState(onTap)
    Column(
        modifier.pointerInput(Unit) { detectTapGestures { tap() } }.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Fin du chapitre", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        if (nextName != null) {
            Text("Suivant : $nextName", color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onNext) { Text("Chapitre suivant") }
        } else {
            Text("Pas de chapitre suivant", color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun ReaderMenu(
    title: String,
    chapterName: String,
    page: Int,
    pageCount: Int,
    mode: ReaderMode,
    hasPrev: Boolean,
    hasNext: Boolean,
    onBack: () -> Unit,
    onSeek: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val barColor = Color.Black.copy(alpha = 0.85f)
    Box(Modifier.fillMaxSize()) {
        Surface(color = barColor, contentColor = Color.White, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
            Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour") }
                Column(Modifier.weight(1f)) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(
                        chapterName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f),
                    )
                }
                var modeMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { modeMenu = true }) { Icon(Icons.Outlined.AutoStories, "Mode de lecture") }
                    DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        ReaderMode.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.label) },
                                leadingIcon = { if (m == mode) Icon(Icons.Outlined.Check, null) },
                                onClick = {
                                    modeMenu = false
                                    Store.updatePrefs { it.copy(readerMode = m) }
                                },
                            )
                        }
                    }
                }
            }
        }
        Surface(color = barColor, contentColor = Color.White, modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
            Row(Modifier.navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev, enabled = hasPrev) { Icon(Icons.Filled.SkipPrevious, "Chapitre précédent") }
                if (pageCount > 1) {
                    Slider(
                        value = page.toFloat(),
                        onValueChange = { onSeek(it.roundToInt()) },
                        valueRange = 0f..(pageCount - 1).toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text("${page + 1}/$pageCount", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                IconButton(onClick = onNext, enabled = hasNext) { Icon(Icons.Filled.SkipNext, "Chapitre suivant") }
            }
        }
    }
}
