package app.yomu.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.yomu.data.MangaRef
import app.yomu.download.Downloader
import app.yomu.source.Cache
import app.yomu.source.toRef

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("library", "Bibliothèque", Icons.Outlined.CollectionsBookmark),
    Tab("sources", "Explorer", Icons.Outlined.Explore),
    Tab("history", "Historique", Icons.Outlined.History),
    Tab("settings", "Réglages", Icons.Outlined.Settings),
)

@Composable
fun YomuApp() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        Downloader.errors.collect { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
    }
    StartupUpdateCheck()

    val openManga: (MangaRef) -> Unit = { m ->
        nav.navigate("manga/${m.sourceId}/${Uri.encode(m.id)}")
    }
    val openReader: (String, String, String) -> Unit = { sourceId, mangaId, chapterId ->
        nav.navigate("reader/$sourceId/${Uri.encode(mangaId)}/${Uri.encode(chapterId)}")
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "library", modifier = Modifier.padding(pad)) {
            composable("library") {
                LibraryScreen(onOpen = openManga)
            }
            composable("sources") {
                SourcesScreen(onOpen = { nav.navigate("browse/$it") })
            }
            composable("browse/{sourceId}") { e ->
                val sid = e.arguments?.getString("sourceId").orEmpty()
                BrowseScreen(sid, onBack = { nav.popBackStack() }, onOpen = { m ->
                    Cache.putManga(sid, m)
                    openManga(m.toRef(sid))
                })
            }
            composable("manga/{sourceId}/{mangaId}") { e ->
                val sid = e.arguments?.getString("sourceId").orEmpty()
                val mid = e.arguments?.getString("mangaId").orEmpty()
                MangaScreen(sid, mid, onBack = { nav.popBackStack() }, onRead = { cid -> openReader(sid, mid, cid) })
            }
            composable("reader/{sourceId}/{mangaId}/{chapterId}") { e ->
                val sid = e.arguments?.getString("sourceId").orEmpty()
                val mid = e.arguments?.getString("mangaId").orEmpty()
                val cid = e.arguments?.getString("chapterId").orEmpty()
                ReaderScreen(sid, mid, cid, onBack = { nav.popBackStack() })
            }
            composable("history") {
                HistoryScreen(
                    onResume = { h -> openReader(h.manga.sourceId, h.manga.id, h.chapterId) },
                    onOpenManga = openManga,
                )
            }
            composable("settings") {
                SettingsScreen()
            }
        }
    }
}
