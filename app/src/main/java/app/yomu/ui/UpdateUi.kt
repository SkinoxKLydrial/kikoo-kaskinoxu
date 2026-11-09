package app.yomu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.yomu.BuildConfig
import app.yomu.data.Store
import app.yomu.update.Release
import app.yomu.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Vérifie une fois au lancement s'il existe une nouvelle version. */
@Composable
fun StartupUpdateCheck() {
    var release by remember { mutableStateOf<Release?>(null) }
    LaunchedEffect(Unit) {
        if (Store.prefs.autoCheckUpdates) release = runCatching { Updater.check() }.getOrNull()
    }
    release?.let { UpdateDialog(it) { release = null } }
}

@Composable
fun UpdateDialog(release: Release, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        title = { Text("Mise à jour disponible") },
        text = {
            Column {
                Text("Version ${release.version} (tu as la ${BuildConfig.VERSION_NAME})")
                if (release.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(release.notes, maxLines = 8, style = MaterialTheme.typography.bodySmall)
                }
                progress?.let { p ->
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = progress == null, onClick = {
                if (!Updater.canInstall(ctx)) {
                    // Android demande d'autoriser l'appli à installer des mises à jour (une seule fois).
                    error = "Autorise Kikoo Kaskinoxu à installer des applis, puis appuie à nouveau sur Installer."
                    Updater.openInstallPermission(ctx)
                } else {
                    scope.launch {
                        progress = 0f
                        error = null
                        try {
                            val file = Updater.download(ctx, release) { progress = it }
                            Updater.install(ctx, file)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = "Échec : ${e.friendly()}"
                        } finally {
                            progress = null
                        }
                    }
                }
            }) { Text("Installer") }
        },
        dismissButton = {
            TextButton(enabled = progress == null, onClick = onDismiss) { Text("Plus tard") }
        },
    )
}
