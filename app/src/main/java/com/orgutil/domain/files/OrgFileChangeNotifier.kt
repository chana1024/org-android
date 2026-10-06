package com.orgutil.domain.files

import com.orgutil.widget.AgendaWidgetUpdater
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process signal that org files were modified (editor save, capture,
 * agent tools, git sync). Consumers that read files directly - e.g. the
 * agenda - collect [changes] and reload.
 */
@Singleton
class OrgFileChangeNotifier @Inject constructor(
    private val widgetUpdater: AgendaWidgetUpdater
) {

    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    fun notifyChanged() {
        _changes.tryEmit(Unit)
        widgetUpdater.refreshAgenda()
    }
}
