// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.appearance

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pingme.core.ui.theme.AppIcon
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.ChatWallpaper
import org.pingme.core.ui.theme.FontChoice
import javax.inject.Inject

/** The Appearance studio's state (UI_DESIGN.md 3.5, 4). Every change applies at once, app-wide. */
@HiltViewModel
class AppearanceViewModel
    @Inject
    constructor(
        private val repository: AppearanceRepository,
        private val icons: AppIconSwitcher,
    ) : ViewModel() {
        val appearance: StateFlow<Appearance> =
            repository.appearance.stateIn(viewModelScope, SharingStarted.Eagerly, Appearance())

        private val messages = Channel<Message>(Channel.BUFFERED)

        /** One-off results to show as a snackbar. */
        val events = messages.receiveAsFlow()

        fun update(change: (Appearance) -> Appearance) {
            viewModelScope.launch { repository.update(change) }
        }

        fun setAppIcon(icon: AppIcon) {
            icons.apply(icon)
            update { it.copy(appIcon = icon) }
        }

        fun export(target: Uri) {
            viewModelScope.launch {
                val sent = runCatching { repository.export(appearance.value, target) }
                messages.send(if (sent.isSuccess) Message.EXPORTED else Message.EXPORT_FAILED)
            }
        }

        fun import(source: Uri) {
            viewModelScope.launch {
                val result = repository.import(source)
                result.getOrNull()?.let { icons.apply(it.appIcon) }
                messages.send(if (result.isSuccess) Message.IMPORTED else Message.IMPORT_FAILED)
            }
        }

        /** Imports a font file and uses it for the interface or for messages (UI_DESIGN.md 4.4). */
        fun importFont(
            source: Uri,
            forMessages: Boolean,
        ) {
            viewModelScope.launch {
                runCatching { repository.importFont(source) }
                    .onSuccess { font: FontChoice.Imported ->
                        update { if (forMessages) it.copy(messageFont = font) else it.copy(uiFont = font) }
                    }.onFailure { messages.send(Message.FONT_FAILED) }
            }
        }

        fun importWallpaper(source: Uri) {
            viewModelScope.launch {
                val blurred = (appearance.value.wallpaper as? ChatWallpaper.Image)?.blurred ?: false
                runCatching { repository.importWallpaper(source) }
                    .onSuccess { file ->
                        update { it.copy(wallpaper = ChatWallpaper.Image(file.absolutePath, blurred)) }
                    }
            }
        }

        fun resetAll() {
            icons.apply(AppIcon.DEFAULT)
            update { Appearance() }
        }

        enum class Message { EXPORTED, EXPORT_FAILED, IMPORTED, IMPORT_FAILED, FONT_FAILED }
    }
