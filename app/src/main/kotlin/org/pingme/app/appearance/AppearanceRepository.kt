// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.appearance

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.pingme.core.store.SettingsRepository
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.ChatWallpaper
import org.pingme.core.ui.theme.FontChoice
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The saved appearance (UI_DESIGN.md 4), and the theme file: export and import as JSON,
 * and the fonts and wallpaper pictures a theme can point at, copied into app storage.
 */
@Singleton
class AppearanceRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: SettingsRepository,
    ) {
        /** The saved look. An imported font or picture that has since gone falls back to the default. */
        val appearance: Flow<Appearance> =
            settings.appearanceJson
                .map { decodeOrDefault(it).withoutMissingFiles() }
                .flowOn(Dispatchers.IO)

        suspend fun update(change: (Appearance) -> Appearance) {
            settings.updateAppearanceJson { json -> encode(change(decodeOrDefault(json))) }
        }

        /** Writes the current theme to [target] as JSON (UI_DESIGN.md 4). */
        suspend fun export(
            appearance: Appearance,
            target: Uri,
        ) = withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(target, "wt")?.use { it.write(encode(appearance).toByteArray()) }
                ?: error("Could not write the theme file")
        }

        /**
         * Reads a theme file and applies it. Fonts and pictures from another phone are not in
         * the file, so those fall back to the defaults.
         */
        suspend fun import(source: Uri): Result<Appearance> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val text =
                        context.contentResolver.openInputStream(source)?.use { it.readBytes().decodeToString() }
                            ?: error("Could not read the file")
                    val imported = decode(text).withoutMissingFiles()
                    update { imported }
                    imported
                }
            }

        /** Copies a .ttf or .otf into app storage (UI_DESIGN.md 4.4). */
        suspend fun importFont(source: Uri): FontChoice.Imported =
            withContext(Dispatchers.IO) {
                val name = displayName(source) ?: "font.ttf"
                val file =
                    File(
                        context.filesDir,
                        "fonts/${UUID.randomUUID()}-${safe(name)}",
                    ).apply { parentFile?.mkdirs() }
                copy(source, file)
                FontChoice.Imported(file.absolutePath, name.substringBeforeLast('.'))
            }

        /** Copies a picture into app storage for the chat wallpaper (UI_DESIGN.md 4.3). */
        suspend fun importWallpaper(source: Uri): File =
            withContext(Dispatchers.IO) {
                File(
                    context.filesDir,
                    "wallpapers/${UUID.randomUUID()}",
                ).apply { parentFile?.mkdirs() }.also { copy(source, it) }
            }

        private fun copy(
            source: Uri,
            target: File,
        ) {
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
                ?: error("Could not read the file")
        }

        private fun displayName(uri: Uri): String? =
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }

        private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

        companion object {
            private val json =
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    prettyPrint = true
                }

            fun encode(appearance: Appearance): String = json.encodeToString(Appearance.serializer(), appearance)

            /** Parses a theme file. Throws [SerializationException] when it is not one. */
            fun decode(text: String): Appearance = json.decodeFromString(Appearance.serializer(), text)

            fun decodeOrDefault(text: String?): Appearance =
                text?.let { runCatching { decode(it) }.getOrNull() } ?: Appearance()
        }
    }

/** Drops references to font and picture files that are not on this phone. */
fun Appearance.withoutMissingFiles(): Appearance {
    fun FontChoice.orDefault() = if (this is FontChoice.Imported && !File(path).isFile) Appearance().uiFont else this
    val wallpaper =
        (wallpaper as? ChatWallpaper.Image)?.takeUnless { File(it.path).isFile }?.let { ChatWallpaper.None }
            ?: wallpaper
    return copy(uiFont = uiFont.orDefault(), messageFont = messageFont.orDefault(), wallpaper = wallpaper)
}
