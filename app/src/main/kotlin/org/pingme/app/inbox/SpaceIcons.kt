// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.pingme.app.R
import org.pingme.core.model.SpaceIcon
import org.pingme.core.ui.R as UiR

/** A space's icon as drawn in the bottom bar, the avatar menu, and the space editor (UI_DESIGN.md 10.4). */
@DrawableRes
fun SpaceIcon.drawable(): Int = LOOKS.getValue(this).first

/** What a screen reader says for the icon in the space editor. */
@StringRes
fun SpaceIcon.label(): Int = LOOKS.getValue(this).second

// Each icon's drawable and spoken name. SpaceIconsTest checks every icon has both.
internal val LOOKS: Map<SpaceIcon, Pair<Int, Int>> =
    mapOf(
        SpaceIcon.SPACE to (UiR.drawable.ic_apps to R.string.space_icon_space),
        SpaceIcon.HOME to (UiR.drawable.ic_home to R.string.space_icon_home),
        SpaceIcon.WORK to (UiR.drawable.ic_work to R.string.space_icon_work),
        SpaceIcon.FAMILY to (UiR.drawable.ic_family_restroom to R.string.space_icon_family),
        SpaceIcon.FRIENDS to (UiR.drawable.ic_groups to R.string.space_icon_friends),
        SpaceIcon.SCHOOL to (UiR.drawable.ic_school to R.string.space_icon_school),
        SpaceIcon.SPORT to (UiR.drawable.ic_sports_soccer to R.string.space_icon_sport),
        SpaceIcon.FITNESS to (UiR.drawable.ic_fitness_center to R.string.space_icon_fitness),
        SpaceIcon.TRAVEL to (UiR.drawable.ic_flight to R.string.space_icon_travel),
        SpaceIcon.HEART to (UiR.drawable.ic_favorite to R.string.space_icon_heart),
        SpaceIcon.STAR to (UiR.drawable.ic_star to R.string.space_icon_star),
        SpaceIcon.PARTY to (UiR.drawable.ic_celebration to R.string.space_icon_party),
        SpaceIcon.SHOPPING to (UiR.drawable.ic_shopping_cart to R.string.space_icon_shopping),
        SpaceIcon.FOOD to (UiR.drawable.ic_restaurant to R.string.space_icon_food),
        SpaceIcon.MUSIC to (UiR.drawable.ic_music_note to R.string.space_icon_music),
        SpaceIcon.BOOKS to (UiR.drawable.ic_menu_book to R.string.space_icon_books),
        SpaceIcon.PETS to (UiR.drawable.ic_pets to R.string.space_icon_pets),
        SpaceIcon.CHAT to (UiR.drawable.ic_forum to R.string.space_icon_chat),
    )
