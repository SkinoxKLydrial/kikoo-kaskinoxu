package app.yomu.data

import app.yomu.source.Cache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Recharge la liste des chapitres de chaque manga de la bibliothèque. */
object LibraryUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start() {
        if (!_running.compareAndSet(false, true)) return
        scope.launch {
            try {
                for (e in Store.data.value.library) {
                    runCatching { Cache.chapters(e.manga.sourceId, e.manga.id, refresh = true) }
                        .onSuccess { ch -> Store.setChapters(e.manga.sourceId, e.manga.id, ch.map { it.id }) }
                }
            } finally {
                _running.value = false
            }
        }
    }
}
