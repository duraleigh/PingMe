// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.appearance

import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.pingme.app.R
import org.pingme.core.model.NetworkId
import org.pingme.core.ui.components.ChoiceSetting
import org.pingme.core.ui.components.ColorSwatch
import org.pingme.core.ui.components.SliderSetting
import org.pingme.core.ui.components.SwitchSetting
import org.pingme.core.ui.theme.AppIcon
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.BubbleStyle
import org.pingme.core.ui.theme.BundledFont
import org.pingme.core.ui.theme.ChatWallpaper
import org.pingme.core.ui.theme.ColorSource
import org.pingme.core.ui.theme.ContrastLevel
import org.pingme.core.ui.theme.FontChoice
import org.pingme.core.ui.theme.Haptics
import org.pingme.core.ui.theme.InboxDensity
import org.pingme.core.ui.theme.MotionIntensity
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.PinnedStyle
import org.pingme.core.ui.theme.SenderNameColors
import org.pingme.core.ui.theme.ShapeFamily
import org.pingme.core.ui.theme.SwipeAction
import org.pingme.core.ui.theme.ThemeMode
import org.pingme.core.ui.theme.ThemePreset
import org.pingme.core.ui.theme.TimestampMode
import org.pingme.core.ui.theme.fontFamilyFor
import kotlin.math.roundToInt

// The Shape, Layout, Fonts, Motion, Icon and Theme file sections of the Appearance studio
// (UI_DESIGN.md 4.2 to 4.6). Colour is in ColourSection.kt.

@Composable
fun ShapeSection(
    appearance: Appearance,
    onChange: Change,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        ChoiceSetting(stringResource(R.string.appearance_shape_family), ShapeFamily.entries, appearance.shapeFamily, {
            stringResource(it.label())
        }, { family ->
            onChange { it.copy(shapeFamily = family) }
        })
        val familyCorner = PingMeTheme.shapes.bubbleCorner.value
        SliderSetting(
            label = stringResource(R.string.appearance_bubble_corner),
            value = appearance.bubbleCorner ?: familyCorner,
            range = Appearance.BUBBLE_CORNERS,
            valueLabel = { "${it.roundToInt()} dp" },
            onChange = { corner -> onChange { it.copy(bubbleCorner = corner) } },
        )
        if (appearance.bubbleCorner != null) {
            TextButton(onClick = { onChange { it.copy(bubbleCorner = null) } }, Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.reset))
            }
        }
        SwitchSetting(stringResource(R.string.appearance_bubble_tails), appearance.bubbleTails, { on ->
            onChange {
                it.copy(bubbleTails = on)
            }
        })
        ChoiceSetting(stringResource(R.string.appearance_bubble_style), BubbleStyle.entries, appearance.bubbleStyle, {
            stringResource(it.label())
        }, { style ->
            onChange { it.copy(bubbleStyle = style) }
        })
    }
}

private enum class WallpaperKind(
    @StringRes val label: Int,
) {
    NONE(R.string.wallpaper_none),
    COLOUR(R.string.wallpaper_colour),
    GRADIENT(R.string.wallpaper_gradient),
    PICTURE(R.string.wallpaper_picture),
}

@Composable
fun LayoutSection(
    appearance: Appearance,
    onChange: Change,
    onPickWallpaper: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    Column(modifier) {
        ChoiceSetting(stringResource(R.string.appearance_density), InboxDensity.entries, appearance.inboxDensity, {
            stringResource(it.label())
        }, { density ->
            onChange { it.copy(inboxDensity = density) }
        })
        ChoiceSetting(stringResource(R.string.appearance_pinned), PinnedStyle.entries, appearance.pinnedStyle, {
            stringResource(it.label())
        }, { style ->
            onChange { it.copy(pinnedStyle = style) }
        })
        SliderSetting(
            label = stringResource(R.string.appearance_avatar_size),
            value = appearance.avatarSize,
            range = Appearance.AVATAR_SIZES,
            valueLabel = { "${it.roundToInt()} dp" },
            onChange = { size -> onChange { it.copy(avatarSize = size) } },
        )
        SwitchSetting(stringResource(R.string.appearance_avatars_in_chat), appearance.showAvatarsInChat, { on ->
            onChange { it.copy(showAvatarsInChat = on) }
        })
        WallpaperControls(appearance.wallpaper, onChange, onPickWallpaper) { picker = it }
        ChoiceSetting(stringResource(R.string.appearance_timestamps), TimestampMode.entries, appearance.timestamps, {
            stringResource(it.label())
        }, { mode ->
            onChange { it.copy(timestamps = mode) }
        })
    }
    picker?.let { target -> ColourPicker(target, appearance, onChange) { picker = null } }
}

/** Chat wallpaper: none, one colour, a gradient, or a picture (UI_DESIGN.md 4.3). */
@Composable
private fun WallpaperControls(
    wallpaper: ChatWallpaper,
    onChange: Change,
    onPickPicture: () -> Unit,
    onPick: (PickerTarget) -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surfaceContainer.toArgb()
    val tint = MaterialTheme.colorScheme.secondaryContainer.toArgb()
    val kind =
        when (wallpaper) {
            ChatWallpaper.None -> WallpaperKind.NONE
            is ChatWallpaper.Colour -> WallpaperKind.COLOUR
            is ChatWallpaper.Gradient -> WallpaperKind.GRADIENT
            is ChatWallpaper.Image -> WallpaperKind.PICTURE
        }
    ChoiceSetting(stringResource(R.string.appearance_wallpaper), WallpaperKind.entries, kind, {
        stringResource(it.label)
    }, { chosen ->
        when (chosen) {
            WallpaperKind.NONE -> onChange { it.copy(wallpaper = ChatWallpaper.None) }
            WallpaperKind.COLOUR -> onChange { it.copy(wallpaper = ChatWallpaper.Colour(surface)) }
            WallpaperKind.GRADIENT -> onChange { it.copy(wallpaper = ChatWallpaper.Gradient(surface, tint)) }
            WallpaperKind.PICTURE -> onPickPicture()
        }
    })
    when (wallpaper) {
        is ChatWallpaper.Colour -> {
            SwatchRow(stringResource(R.string.wallpaper_colour), wallpaper.argb) {
                onPick(PickerTarget.Wallpaper(0, wallpaper.argb))
            }
        }

        is ChatWallpaper.Gradient -> {
            SwatchRow(stringResource(R.string.appearance_gradient_from), wallpaper.from) {
                onPick(PickerTarget.Wallpaper(0, wallpaper.from))
            }
            SwatchRow(stringResource(R.string.appearance_gradient_to), wallpaper.to) {
                onPick(PickerTarget.Wallpaper(1, wallpaper.to))
            }
        }

        is ChatWallpaper.Image -> {
            SwitchSetting(stringResource(R.string.appearance_wallpaper_blur), wallpaper.blurred, { on ->
                onChange { it.copy(wallpaper = wallpaper.copy(blurred = on)) }
            })
            TextButton(onClick = onPickPicture, Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.appearance_wallpaper_choose))
            }
        }

        ChatWallpaper.None -> {
            Unit
        }
    }
}

@Composable
fun FontsSection(
    appearance: Appearance,
    onChange: Change,
    onImportFont: (forMessages: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        FontPicker(stringResource(R.string.appearance_ui_font), appearance.uiFont, { font ->
            onChange {
                it.copy(uiFont = font)
            }
        }) {
            onImportFont(false)
        }
        FontPicker(stringResource(R.string.appearance_message_font), appearance.messageFont, { font ->
            onChange {
                it.copy(messageFont = font)
            }
        }) {
            onImportFont(true)
        }
        SliderSetting(
            label = stringResource(R.string.appearance_font_size),
            value = appearance.fontScale,
            range = Appearance.FONT_SCALES,
            valueLabel = { "${(it * PERCENT).roundToInt()}%" },
            onChange = { scale -> onChange { it.copy(fontScale = scale) } },
        )
        SliderSetting(
            label = stringResource(R.string.appearance_line_height),
            value = appearance.lineHeightScale,
            range = Appearance.LINE_HEIGHTS,
            valueLabel = { "${(it * PERCENT).roundToInt()}%" },
            onChange = { scale -> onChange { it.copy(lineHeightScale = scale) } },
        )
        SwitchSetting(stringResource(R.string.appearance_emphasized), appearance.emphasizedHeadlines, { on ->
            onChange { it.copy(emphasizedHeadlines = on) }
        })
    }
}

/** Every bundled font, each shown in its own face, the imported one if any, and "Import". */
@Composable
private fun FontPicker(
    label: String,
    selected: FontChoice,
    onSelect: (FontChoice) -> Unit,
    onImport: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BundledFont.entries.forEach { font ->
                val choice = FontChoice.Bundled(font)
                val family = remember(font) { fontFamilyFor(choice) }
                FilterChip(selected = selected == choice, onClick = { onSelect(choice) }, label = {
                    Text(font.displayName, style = TextStyle(fontFamily = family))
                })
            }
            if (selected is FontChoice.Imported) {
                val family = remember(selected) { fontFamilyFor(selected) }
                FilterChip(selected = true, onClick = {
                }, label = { Text(selected.displayName, style = TextStyle(fontFamily = family)) })
            }
            AssistChip(onClick = onImport, label = {
                Text(
                    stringResource(R.string.appearance_import_font),
                )
            }, leadingIcon = {
                Icon(painterResource(org.pingme.core.ui.R.drawable.ic_font_download), null)
            })
        }
    }
}

@Composable
fun MotionSection(
    appearance: Appearance,
    onChange: Change,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        ChoiceSetting(stringResource(R.string.appearance_motion), MotionIntensity.entries, appearance.motion, {
            stringResource(it.label())
        }, { motion ->
            onChange { it.copy(motion = motion) }
        })
        if (PingMeTheme.motion == MotionIntensity.OFF && appearance.motion != MotionIntensity.OFF) {
            Text(
                stringResource(R.string.appearance_motion_system_off),
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ChoiceSetting(stringResource(R.string.appearance_haptics), Haptics.entries, appearance.haptics, {
            stringResource(it.label())
        }, { haptics ->
            onChange { it.copy(haptics = haptics) }
        })
    }
}

@Composable
fun IconSection(
    appearance: Appearance,
    onChange: Change,
    onAppIcon: (AppIcon) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            stringResource(R.string.appearance_app_icon),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon.entries.forEach { icon ->
                IconChoice(icon, selected = appearance.appIcon == icon) { onAppIcon(icon) }
            }
        }
        ChoiceSetting(stringResource(R.string.appearance_swipe_right), SwipeAction.entries, appearance.swipeRight, {
            stringResource(it.label())
        }, { action ->
            onChange { it.copy(swipeRight = action) }
        })
        ChoiceSetting(stringResource(R.string.appearance_swipe_left), SwipeAction.entries, appearance.swipeLeft, {
            stringResource(it.label())
        }, { action ->
            onChange { it.copy(swipeLeft = action) }
        })
    }
}

/** One launcher icon variant, drawn from its real adaptive icon. */
@Composable
private fun IconChoice(
    icon: AppIcon,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AndroidView(
            factory = { ImageView(it) },
            update = { it.setImageResource(icon.mipmap) },
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.RadioButton, onClick = onClick)
                    .semantics {
                        contentDescription = icon.displayName
                        this.selected = selected
                        role = Role.RadioButton
                    },
        )
        Text(
            icon.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@get:DrawableRes
private val AppIcon.mipmap
    get() =
        when (this) {
            AppIcon.DEFAULT -> R.mipmap.ic_launcher
            AppIcon.LIGHT -> R.mipmap.ic_launcher_light
            AppIcon.DARK -> R.mipmap.ic_launcher_dark
            AppIcon.SUNSET -> R.mipmap.ic_launcher_sunset
            AppIcon.FOREST -> R.mipmap.ic_launcher_forest
        }

@Composable
fun ThemeFileSection(
    actions: AppearanceActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(bottom = 32.dp)) {
        ListItem(
            onClick = actions.onExport,
            leadingContent = { Icon(painterResource(org.pingme.core.ui.R.drawable.ic_upload), null) },
            supportingContent = { Text(stringResource(R.string.appearance_export_description)) },
        ) { Text(stringResource(R.string.appearance_export)) }
        ListItem(
            onClick = actions.onImport,
            leadingContent = { Icon(painterResource(org.pingme.core.ui.R.drawable.ic_download), null) },
            supportingContent = { Text(stringResource(R.string.appearance_import_description)) },
        ) { Text(stringResource(R.string.appearance_import)) }
        ListItem(
            onClick = actions.onResetAll,
            leadingContent = { Icon(painterResource(org.pingme.core.ui.R.drawable.ic_restart_alt), null) },
        ) { Text(stringResource(R.string.appearance_reset_all)) }
    }
}

private const val PERCENT = 100
