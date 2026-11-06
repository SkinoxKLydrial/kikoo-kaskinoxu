package app.yomu

import android.app.Application
import app.yomu.data.Net
import app.yomu.data.Store
import app.yomu.download.Downloader
import app.yomu.source.Sources
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Sources.init(this)
        Downloader.init(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Net.client)
        .crossfade(true)
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(500L * 1024 * 1024)
                .build()
        }
        .build()
}
