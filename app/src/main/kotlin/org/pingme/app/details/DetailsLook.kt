// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.details

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.app.appearance.ColorPickerSheet
import org.pingme.app.appearance.label
import org.pingme.core.ui.components.ChoiceSetting
import org.pingme.core.ui.components.ColorSwatch
import org.pingme.core.ui.components.SettingsSectionHeader
import org.pingme.core.ui.components.SliderSetting
import org.pingme.core.ui.components.SwitchSetting
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.BubbleStyle
import org.pingme.core.ui.theme.ChatLook
import org.pingme.core.ui.theme.ChatWallpaper
import org.pingme.core.ui.theme.with
import kotlin.math.roundToInt

/**
 * This chat's look (UI_DESIGN.md 3.4, 4): its bubble colour, bubble style, wallpaper, and
 * text size. Each starts as the app's and can go back to it.
 */
@Composable
fun DetailsLook(
    state: ChatDetailsState,
    choices: LookChoices,
    modifier: Modifier = Modifier,
) {
    val network = state.account?.network ?: return
    val look = state.look
    val shown = state.appearance.with(look, network)
    var picking by remember { mutableStateOf<ColourTarget?>(null) }
    val picture =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { choices.onPicture(it, (look.wallpaper as? ChatWallpaper.Image)?.blurred ?: false) }
        }
    Column(modifier) {
        SettingsSectionHeader(stringResource(R.string.details_look))
        BubbleColourRow(look, shown.networkColors[network]) { picking = ColourTarget.BUBBLE }
        ChoiceSetting(stringResource(R.string.appearance_bubble_style), BubbleStyle.entries, shown.bubbleStyle, {
            stringResource(it.label())
        }, { choices.onLook(look.copy(bubbleStyle = it)) })
        WallpaperChoice(look, shown, { choices.onLook(look.copy(wallpaper = it)) }, { picking = it }) {
            picture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        SliderSetting(
            label = stringResource(R.string.details_text_size),
            value = shown.fontScale,
            range = Appearance.FONT_SCALES,
            valueLabel = { "${(it * PERCENT).roundToInt()}%" },
            onChange = { scale -> choices.onLook(look.copy(fontScale = scale)) },
        )
        if (!look.isEmpty) {
            TextButton({ choices.onLook(ChatLook()) }, Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.details_reset_look))
            }
        }
    }
    picking?.let { target ->
        val start = target.start(shown, network)
        ColorPickerSheet(
            stringResource(
                target.title,
            ),
            start,
            { colour -> choices.onLook(target.apply(look, colour)) },
            {
                picking =
                    null
            },
        )
    }
}

// The outgoing bubble colour: the chat's own, or the network's.
@Composable
private fun BubbleColourRow(
    look: ChatLook,
    colour: Int?,
    onPick: () -> Unit,
) {
    val label = stringResource(R.string.details_bubble_colour)
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            if (look.bubbleColor ==
                null
            ) {
                Text(stringResource(R.string.details_bubble_colour_network))
            }
        },
        trailingContent = {
            ColorSwatch(
                Color(colour ?: MaterialTheme.colorScheme.primary.toArgb()),
                selected = false,
                description = label,
                onClick = onPick,
            )
        },
    )
}

/** The wallpaper for this chat: the app's, none, a colour, a gradient, or a picture. */
@Composable
private fun WallpaperChoice(
    look: ChatLook,
    shown: Appearance,
    onWallpaper: (ChatWallpaper?) -> Unit,
    onPick: (ColourTarget) -> Unit,
    onPicture: () -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surfaceContainer.toArgb()
    val tint = MaterialTheme.colorScheme.secondaryContainer.toArgb()
    val kind = Wall.of(look.wallpaper)
    ChoiceSetting(stringResource(R.string.appearance_wallpaper), Wall.entries, kind, {
        stringResource(it.label)
    }, { chosen ->
        when (chosen) {
            Wall.APP -> onWallpaper(null)
            Wall.NONE -> onWallpaper(ChatWallpaper.None)
            Wall.COLOUR -> onWallpaper(ChatWallpaper.Colour(surface))
            Wall.GRADIENT -> onWallpaper(ChatWallpaper.Gradient(surface, tint))
            Wall.PICTURE -> onPicture()
        }
    })
    when (val wallpaper = shown.wallpaper.takeIf { look.wallpaper != null }) {
        is ChatWallpaper.Colour -> {
            SwatchLine(R.string.wallpaper_colour, wallpaper.argb) { onPick(ColourTarget.WALL) }
        }

        is ChatWallpaper.Gradient -> {
            SwatchLine(R.string.appearance_gradient_from, wallpaper.from) { onPick(ColourTarget.FROM) }
            SwatchLine(R.string.appearance_gradient_to, wallpaper.to) { onPick(ColourTarget.TO) }
        }

        is ChatWallpaper.Image -> {
            SwitchSetting(stringResource(R.string.appearance_wallpaper_blur), wallpaper.blurred, { on ->
                onWallpaper(wallpaper.copy(blurred = on))
            })
            TextButton(
                onPicture,
                Modifier.padding(start = 8.dp),
            ) { Text(stringResource(R.string.appearance_wallpaper_choose)) }
        }

        else -> {
            Unit
        }
    }
}

@Composable
private fun SwatchLine(
    @StringRes label: Int,
    colour: Int,
    onClick: () -> Unit,
) {
    val name = stringResource(label)
    ListItem(
        headlineContent = { Text(name) },
        trailingContent = { ColorSwatch(Color(colour), selected = false, description = name, onClick = onClick) },
    )
}

/** The wallpaper kinds, plus "same as the app". */
private enum class Wall(
    @param:StringRes val label: Int,
) {
    APP(R.string.details_follow_app),
    NONE(R.string.wallpaper_none),
    COLOUR(R.string.wallpaper_colour),
    GRADIENT(R.string.wallpaper_gradient),
    PICTURE(R.string.wallpaper_picture),
    ;

    companion object {
        fun of(wallpaper: ChatWallpaper?) =
            when (wallpaper) {
                null -> APP
                ChatWallpaper.None -> NONE
                is ChatWallpaper.Colour -> COLOUR
                is ChatWallpaper.Gradient -> GRADIENT
                is ChatWallpaper.Image -> PICTURE
            }
    }
}

/** Which colour the picker is choosing. */
private enum class ColourTarget(
    @param:StringRes val title: Int,
) {
    BUBBLE(R.string.details_bubble_colour),
    WALL(R.string.wallpaper_colour),
    FROM(R.string.appearance_gradient_from),
    TO(R.string.appearance_gradient_to),
    ;

    fun start(
        shown: Appearance,
        network: org.pingme.core.model.NetworkId,
    ): Int =
        when (this) {
            BUBBLE -> shown.networkColors[network] ?: DEFAULT
            WALL -> (shown.wallpaper as? ChatWallpaper.Colour)?.argb ?: DEFAULT
            FROM -> (shown.wallpaper as? ChatWallpaper.Gradient)?.from ?: DEFAULT
            TO -> (shown.wallpaper as? ChatWallpaper.Gradient)?.to ?: DEFAULT
        }

    fun apply(
        look: ChatLook,
        colour: Int,
    ): ChatLook {
        val gradient = look.wallpaper as? ChatWallpaper.Gradient
        return when (this) {
            BUBBLE -> look.copy(bubbleColor = colour)
            WALL -> look.copy(wallpaper = ChatWallpaper.Colour(colour))
            FROM -> look.copy(wallpaper = gradient?.copy(from = colour) ?: ChatWallpaper.Gradient(colour, colour))
            TO -> look.copy(wallpaper = gradient?.copy(to = colour) ?: ChatWallpaper.Gradient(colour, colour))
        }
    }

    private companion object {
        const val DEFAULT = 0xFF6750A4.toInt()
    }
}

private const val PERCENT = 100
