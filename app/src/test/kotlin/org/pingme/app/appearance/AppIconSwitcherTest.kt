// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.appearance

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.pingme.core.ui.theme.AppIcon
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** App icon variants (UI_DESIGN.md 4.6): exactly one launcher entry is on at a time. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppIconSwitcherTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val packages = context.packageManager

    private fun component(icon: AppIcon) = ComponentName(context, AppIconSwitcher.aliasFor(icon))

    @Test
    fun everyVariantHasALauncherEntryPointingAtTheApp() {
        AppIcon.entries.forEach { icon ->
            val info = packages.getActivityInfo(component(icon), PackageManager.MATCH_DISABLED_COMPONENTS)
            assertEquals(icon.name, "org.pingme.app.MainActivity", info.targetActivity)
        }
    }

    @Test
    fun switchingLeavesOnlyTheChosenIconOn() {
        AppIconSwitcher(context).apply(AppIcon.SUNSET)
        AppIcon.entries.forEach { icon ->
            val expected =
                if (icon ==
                    AppIcon.SUNSET
                ) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
            assertEquals(icon.name, expected, packages.getComponentEnabledSetting(component(icon)))
        }
    }
}
