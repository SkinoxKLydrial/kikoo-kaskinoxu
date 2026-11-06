@file:OptIn(ExperimentalLayoutApi::class, coil.annotation.ExperimentalCoilApi::class)

package app.yomu.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.yomu.BuildConfig
import app.yomu.data.ReaderMode
import app.yomu.data.Store
import app.yomu.download.Downloader
import app.yomu.update.Release
import app.yomu.update.Updater
import coil.imageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val LANGUAGES = listOf(
    "fr" to "Français",
    "en" to "Anglais",
    "es" to "Espagnol",
    "es-la" to "Espagnol (Amérique latine)",
    "pt-br" to "Portugais (Brésil)",
    "it" to "Italien",
    "de" to "Allemand",
)

@Composable
fun SettingsScreen() {
    val data by Store.data.collectAsState()
    val prefs = data.prefs
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<Release?>(null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Store.updatePrefs { it.copy(localTreeUri = uri.toString()) }
        }
    }

    YScaffold("Réglages") { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle("Lecture")
            ReaderMode.entries.forEach { m ->
                ListItem(
                    headlineContent = { Text(m.label) },
                    leadingContent = { RadioButton(selected = prefs.readerMode == m, onClick = null) },
                    modifier = Modifier.clickable { Store.updatePrefs { it.copy(readerMode = m) } },
                )
            }

            SectionTitle("MangaDex")
            Text(
                "Langues des chapitres",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LANGUAGES.forEach { (code, label) ->
                    val selected = code in prefs.languages
                    FilterChip(
                        selected = selected,
                        onClick = {
                            Store.updatePrefs { p ->
                                p.copy(languages = if (selected) p.languages - code else p.languages + code)
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }
            SwitchRow("Contenu suggestif", "Afficher aussi les séries classées « suggestive »", prefs.showSuggestive) { v ->
                Store.updatePrefs { it.copy(showSuggestive = v) }
            }

            SectionTitle("Fichiers locaux")
            ListItem(
                headlineContent = { Text("Dossier des mangas") },
                supportingContent = {
                    Text(prefs.localTreeUri?.let { Uri.decode(Uri.parse(it).lastPathSegment ?: it) } ?: "Aucun dossier choisi")
                },
                leadingContent = { Icon(Icons.Outlined.Folder, null) },
                modifier = Modifier.clickable { pickFolder.launch(null) },
            )
            Text(
                "Un sous-dossier par série, avec dedans un CBZ/ZIP ou un dossier d'images par chapitre.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            SectionTitle("Mises à jour")
            SwitchRow("Vérifier au démarrage", "Propose la nouvelle version dès qu'elle est publiée", prefs.autoCheckUpdates) { v ->
                Store.updatePrefs { it.copy(autoCheckUpdates = v) }
            }
            ListItem(
                headlineContent = { Text("Vérifier maintenant") },
                supportingContent = { Text("Version installée : ${BuildConfig.VERSION_NAME}") },
                trailingContent = {
                    if (checking) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                },
                modifier = Modifier.clickable(enabled = !checking) {
                    scope.launch {
                        checking = true
                        try {
                            val r = Updater.check()
                            if (r == null) Toast.makeText(ctx, "Tu as déjà la dernière version", Toast.LENGTH_SHORT).show()
                            else release = r
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Impossible de vérifier : ${e.friendly()}", Toast.LENGTH_LONG).show()
                        } finally {
                            checking = false
                        }
                    }
                },
            )

            SectionTitle("Stockage")
            ListItem(
                headlineContent = { Text("Vider le cache") },
                supportingContent = { Text("Images en cache et chapitres locaux décompressés") },
                modifier = Modifier.clickable {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            ctx.imageLoader.diskCache?.clear()
                            File(ctx.cacheDir, "local_chapters").deleteRecursively()
                            File(ctx.cacheDir, "local_covers").deleteRecursively()
                            File(ctx.cacheDir, "updates").deleteRecursively()
                        }
                        ctx.imageLoader.memoryCache?.clear()
                        Toast.makeText(ctx, "Cache vidé", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            ListItem(
                headlineContent = { Text("Supprimer tous les téléchargements") },
                modifier = Modifier.clickable {
                    scope.launch {
                        withContext(Dispatchers.IO) { Downloader.deleteAll() }
                        Toast.makeText(ctx, "Téléchargements supprimés", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    release?.let { UpdateDialog(it) { release = null } }
}
