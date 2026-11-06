package app.yomu.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.yomu.BuildConfig
import app.yomu.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.io.File
import java.io.IOException

data class Release(val version: String, val code: Int, val notes: String, val apkUrl: String)

/**
 * Mise à jour automatique : regarde la dernière "Release" GitHub du dépôt qui a compilé l'appli.
 * Le tag est de la forme v1.0.<numéro>, le numéro étant le versionCode.
 */
object Updater {
    val enabled get() = BuildConfig.UPDATE_REPO.isNotBlank()

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        val body = Net.getString("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        val o = Net.json.parseToJsonElement(body).jsonObject
        val tag = (o["tag_name"] as? JsonPrimitive)?.contentOrNull ?: return@withContext null
        val code = tag.substringAfterLast('.').toIntOrNull() ?: return@withContext null
        val apk = (o["assets"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull?.endsWith(".apk") == true }
            ?.let { (it["browser_download_url"] as? JsonPrimitive)?.contentOrNull }
            ?: return@withContext null
        val notes = (o["body"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (code > BuildConfig.VERSION_CODE) Release(tag.removePrefix("v"), code, notes, apk) else null
    }

    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, "yomu.apk")
            Net.client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body ?: throw IOException("Fichier vide")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
            }
            file
        }

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
