// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.appearance

import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

// The Colour section of the Appearance studio (UI_DESIGN.md 4.1) and the colour picker the
// other sections share.

internal typealias Change = ((Appearance) -> Appearance) -> Unit

/** Which colour the picker sheet is editing. */
internal sealed interface PickerTarget {
    val initial: Int

    data class Seed(
        override val initial: Int,
    ) : PickerTarget

    data class Manual(
        val role: Int,
        override val initial: Int,
    ) : PickerTarget

    data class NetworkBubble(
        val network: NetworkId,
        override val initial: Int,
    ) : PickerTarget

    data class NetworkAccent(
        val network: NetworkId,
        override val initial: Int,
    ) : PickerTarget

    data class Wallpaper(
        val end: Int,
        override val initial: Int,
    ) : PickerTarget
}

private enum class SourceKind(
    @StringRes val label: Int,
) {
    DYNAMIC(R.string.colour_source_dynamic),
    SEED(R.string.colour_source_seed),
    PRESET(R.string.colour_source_preset),
    MANUAL(R.string.colour_source_manual),
}

private val ColorSource.kind
    get() =
        when (this) {
            ColorSource.Dynamic -> SourceKind.DYNAMIC
            is ColorSource.Seed -> SourceKind.SEED
            is ColorSource.Preset -> SourceKind.PRESET
            is ColorSource.Manual -> SourceKind.MANUAL
        }

/** The networks with their own colours, and how they are named in the studio. */
private val NETWORK_ROWS =
    listOf(
        NetworkId.GMESSAGES to "Google Messages",
        NetworkId.WHATSAPP to "WhatsApp",
        NetworkId.GVOICE to "Google Voice",
        NetworkId.SIGNAL to "Signal",
        NetworkId.TELEGRAM to "Telegram",
        NetworkId.MESSENGER to "Messenger",
        NetworkId.INSTAGRAM to "Instagram",
    )

@Composable
fun ColourSection(
    appearance: Appearance,
    onChange: Change,
    modifier: Modifier = Modifier,
) {
    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    Column(modifier) {
        ColourSource(appearance.colorSource, onChange) { picker = it }
        ChoiceSetting(stringResource(R.string.appearance_mode), ThemeMode.entries, appearance.mode, {
            stringResource(it.label())
        }, { mode ->
            onChange { it.copy(mode = mode) }
        })
        SwitchSetting(
            stringResource(R.string.appearance_amoled),
            appearance.amoled,
            { on -> onChange { it.copy(amoled = on) } },
            description = stringResource(R.string.appearance_amoled_description),
        )
        ChoiceSetting(stringResource(R.string.appearance_contrast), ContrastLevel.entries, appearance.contrast, {
            stringResource(it.label())
        }, { level ->
            onChange { it.copy(contrast = level) }
        })
        NetworkColours(appearance) { picker = it }
        ChoiceSetting(
            stringResource(R.string.appearance_sender_names),
            SenderNameColors.entries,
            appearance.senderNameColors,
            { stringResource(it.label()) },
            { colours -> onChange { it.copy(senderNameColors = colours) } },
        )
    }
    picker?.let { target ->
        ColourPicker(target, appearance, onChange) { picker = null }
    }
}

/** Switching source keeps what was set for that source before, or starts from the current primary colour. */
private fun sourceFor(
    kind: SourceKind,
    current: ColorSource,
    primary: Int,
): ColorSource =
    when (kind) {
        SourceKind.DYNAMIC -> ColorSource.Dynamic
        SourceKind.SEED -> current as? ColorSource.Seed ?: ColorSource.Seed(primary)
        SourceKind.PRESET -> current as? ColorSource.Preset ?: ColorSource.Preset(ThemePreset.PINGME.name)
        SourceKind.MANUAL -> current as? ColorSource.Manual ?: ColorSource.Manual(primary)
    }

/** Where the colours come from, and the controls for that source. */
@Composable
private fun ColourSource(
    source: ColorSource,
    onChange: Change,
    onPick: (PickerTarget) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    ChoiceSetting(
        label = stringResource(R.string.appearance_colour_source),
        options = SourceKind.entries,
        selected = source.kind,
        optionLabel = { stringResource(it.label) },
        onSelect = { kind ->
            onChange {
                it.copy(
                    colorSource = sourceFor(kind, it.colorSource, primary),
                )
            }
        },
    )
    when (source) {
        ColorSource.Dynamic -> {
            Unit
        }

        is ColorSource.Seed -> {
            SwatchRow(stringResource(R.string.appearance_pick_seed), source.argb) {
                onPick(PickerTarget.Seed(source.argb))
            }
        }

        is ColorSource.Preset -> {
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ThemePreset.entries.forEach { preset ->
                    ColorSwatch(
                        Color(
                            preset.seed,
                        ),
                        selected = source.name == preset.name,
                        description = preset.displayName,
                        onClick = {
                            onChange { it.copy(colorSource = ColorSource.Preset(preset.name)) }
                        },
                    )
                }
            }
        }

        is ColorSource.Manual -> {
            val roles =
                listOf(
                    R.string.appearance_manual_primary to source.primary,
                    R.string.appearance_manual_secondary to source.secondary,
                    R.string.appearance_manual_tertiary to source.tertiary,
                    R.string.appearance_manual_neutral to source.neutral,
                )
            roles.forEachIndexed { index, (label, colour) ->
                SwatchRow(stringResource(label), colour ?: source.primary, followsPrimary = colour == null) {
                    onPick(PickerTarget.Manual(index, colour ?: source.primary))
                }
            }
        }
    }
}

/** Bubble and accent colour for each network, each with its own picker. */
@Composable
private fun NetworkColours(
    appearance: Appearance,
    onPick: (PickerTarget) -> Unit,
) {
    val palette = PingMeTheme.networkColors
    Text(
        stringResource(R.string.appearance_network_colours),
        Modifier.padding(start = 16.dp, top = 16.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
    NETWORK_ROWS.forEach { (network, name) ->
        val colours = palette.outgoing(network, org.pingme.core.model.Transport.NETWORK)
        val accent = palette.accent(network).toArgb()
        // What is drawn, not a plain circle: the bubble in the current style (a gradient
        // runs from the picked colour), and the badge as rows show it (owner, 2026-10-04).
        val styled =
            org.pingme.core.ui.components.styleBubble(
                colors = colours,
                style = appearance.bubbleStyle,
                accent = Color(accent),
                surface = MaterialTheme.colorScheme.surface,
                onSurface = MaterialTheme.colorScheme.onSurface,
                outgoing = true,
            )
        ListItem(supportingContent = null, trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(width = 56.dp, height = 28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(styled.background)
                            .then(
                                styled.border?.let { Modifier.border(1.dp, it, RoundedCornerShape(14.dp)) } ?: Modifier,
                            ).clickable(
                                onClickLabel = "$name ${stringResource(R.string.appearance_bubble_colour)}",
                                role = Role.Button,
                            ) {
                                onPick(PickerTarget.NetworkBubble(network, appearance.networkColors[network] ?: accent))
                            }.semantics { contentDescription = "$name bubble" },
                    )
                    Text(stringResource(R.string.appearance_swatch_bubble), style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .height(28.dp)
                            .clickable(
                                onClickLabel = "$name ${stringResource(R.string.appearance_accent_colour)}",
                                role = Role.Button,
                            ) {
                                onPick(PickerTarget.NetworkAccent(network, accent))
                            }.semantics { contentDescription = "$name badge" },
                        contentAlignment = Alignment.Center,
                    ) {
                        org.pingme.app.inbox
                            .NetworkBadge(network)
                    }
                    Text(stringResource(R.string.appearance_swatch_badge), style = MaterialTheme.typography.labelSmall)
                }
            }
        }) { Text(name) }
    }
}

@Composable
internal fun ColourPicker(
    target: PickerTarget,
    appearance: Appearance,
    onChange: Change,
    onDismiss: () -> Unit,
) {
    ColorPickerSheet(
        title = pickerTitle(target),
        initial = target.initial,
        onDismiss = onDismiss,
        onReset = pickerReset(target)?.let { reset -> { onChange(reset) } },
        onPick = { colour -> onChange { applyPick(it, target, colour, appearance.wallpaper) } },
    )
}

@Composable
private fun pickerTitle(target: PickerTarget) =
    when (target) {
        is PickerTarget.Seed -> stringResource(R.string.appearance_pick_seed)
        is PickerTarget.Manual -> stringResource(MANUAL_LABELS[target.role])
        is PickerTarget.NetworkBubble -> stringResource(R.string.appearance_bubble_colour)
        is PickerTarget.NetworkAccent -> stringResource(R.string.appearance_accent_colour)
        is PickerTarget.Wallpaper -> stringResource(R.string.appearance_wallpaper)
    }

/** What "Reset" does for this colour, or null when it has nothing to go back to. */
private fun pickerReset(target: PickerTarget): ((Appearance) -> Appearance)? =
    when (target) {
        is PickerTarget.NetworkBubble -> {
            { a -> a.copy(networkColors = a.networkColors - target.network) }
        }

        is PickerTarget.NetworkAccent -> {
            { a -> a.copy(networkAccents = a.networkAccents - target.network) }
        }

        is PickerTarget.Manual -> {
            if (target.role ==
                0
            ) {
                null
            } else {
                { a -> a.copy(colorSource = (a.colorSource as ColorSource.Manual).withRole(target.role, null)) }
            }
        }

        is PickerTarget.Seed, is PickerTarget.Wallpaper -> {
            null
        }
    }

private fun applyPick(
    appearance: Appearance,
    target: PickerTarget,
    colour: Int,
    wallpaper: ChatWallpaper,
): Appearance =
    when (target) {
        is PickerTarget.Seed -> {
            appearance.copy(colorSource = ColorSource.Seed(colour))
        }

        is PickerTarget.Manual -> {
            appearance.copy(colorSource = (appearance.colorSource as ColorSource.Manual).withRole(target.role, colour))
        }

        is PickerTarget.NetworkBubble -> {
            appearance.copy(
                networkColors =
                    appearance.networkColors + (target.network to colour),
            )
        }

        is PickerTarget.NetworkAccent -> {
            appearance.copy(
                networkAccents =
                    appearance.networkAccents + (target.network to colour),
            )
        }

        is PickerTarget.Wallpaper -> {
            appearance.copy(
                wallpaper =
                    when (wallpaper) {
                        is ChatWallpaper.Gradient -> {
                            if (target.end ==
                                0
                            ) {
                                wallpaper.copy(from = colour)
                            } else {
                                wallpaper.copy(to = colour)
                            }
                        }

                        else -> {
                            ChatWallpaper.Colour(colour)
                        }
                    },
            )
        }
    }

private val MANUAL_LABELS =
    listOf(
        R.string.appearance_manual_primary,
        R.string.appearance_manual_secondary,
        R.string.appearance_manual_tertiary,
        R.string.appearance_manual_neutral,
    )

private fun ColorSource.Manual.withRole(
    role: Int,
    colour: Int?,
) = when (role) {
    0 -> copy(primary = colour ?: primary)
    1 -> copy(secondary = colour)
    2 -> copy(tertiary = colour)
    else -> copy(neutral = colour)
}

@Composable
internal fun SwatchRow(
    label: String,
    colour: Int,
    followsPrimary: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        onClick = onClick,
        supportingContent =
            if (followsPrimary) {
                (
                    {
                        Text(
                            stringResource(R.string.appearance_follows_primary),
                        )
                    }
                )
            } else {
                null
            },
        trailingContent = { ColorSwatch(Color(colour), selected = false, description = label, onClick = onClick) },
    ) { Text(label) }
}
