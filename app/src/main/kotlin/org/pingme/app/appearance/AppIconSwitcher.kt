// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.appearance

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.pingme.core.ui.theme.AppIcon
import javax.inject.Inject

/**
 * Switches the launcher icon by enabling one activity alias and disabling the others
 * (UI_DESIGN.md 4.6). Each alias points at MainActivity with a different icon.
 */
class AppIconSwitcher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun apply(icon: AppIcon) {
            AppIcon.entries.forEach { variant ->
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, aliasFor(variant)),
                    if (variant ==
                        icon
                    ) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    },
                    PackageManager.DONT_KILL_APP,
                )
            }
        }

        companion object {
            fun aliasFor(icon: AppIcon) =
                "org.pingme.app.Icon" + icon.name.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
