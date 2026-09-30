// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.appearance

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pingme.app.R
import org.pingme.core.ui.components.SettingsSectionHeader
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.ContrastIssue
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.contrastIssues

/**
 * Settings > Appearance (UI_DESIGN.md 3.5, 4): the live preview sits at the top and every
 * control scrolls below it. Changes apply to the whole app as they are made.
 */
@Composable
fun AppearanceRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppearanceViewModel = hiltViewModel(),
) {
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { message ->
            val text =
                when (message) {
                    AppearanceViewModel.Message.EXPORTED -> R.string.appearance_exported
                    AppearanceViewModel.Message.EXPORT_FAILED -> R.string.appearance_export_failed
                    AppearanceViewModel.Message.IMPORTED -> R.string.appearance_imported
                    AppearanceViewModel.Message.IMPORT_FAILED -> R.string.appearance_import_failed
                    AppearanceViewModel.Message.FONT_FAILED -> R.string.appearance_font_import_failed
                }
            snackbar.showSnackbar(resources.getString(text))
        }
    }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let(viewModel::export)
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::import) }
    val fontForMessages = remember { booleanArrayOf(false) }
    val fontLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.importFont(it, fontForMessages[0]) }
        }
    val wallpaperLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
        ) { uri -> uri?.let(viewModel::importWallpaper) }
    AppearanceScreen(
        appearance = appearance,
        onChange = viewModel::update,
        actions =
            AppearanceActions(
                onBack = onBack,
                onAppIcon = viewModel::setAppIcon,
                onImportFont = { forMessages ->
                    fontForMessages[0] = forMessages
                    fontLauncher.launch(FONT_TYPES)
                },
                onPickWallpaper = {
                    wallpaperLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onExport = { exportLauncher.launch(EXPORT_NAME) },
                onImport = {
                    importLauncher.launch(
                        arrayOf("application/json", "text/plain", "application/octet-stream"),
                    )
                },
                onResetAll = viewModel::resetAll,
            ),
        snackbar = snackbar,
        modifier = modifier,
    )
}

/** What the studio can ask for beyond changing the appearance itself. */
class AppearanceActions(
    val onBack: () -> Unit,
    val onAppIcon: (org.pingme.core.ui.theme.AppIcon) -> Unit,
    val onImportFont: (forMessages: Boolean) -> Unit,
    val onPickWallpaper: () -> Unit,
    val onExport: () -> Unit,
    val onImport: () -> Unit,
    val onResetAll: () -> Unit,
)

@Composable
fun AppearanceScreen(
    appearance: Appearance,
    onChange: ((Appearance) -> Appearance) -> Unit,
    actions: AppearanceActions,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.appearance_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            painterResource(org.pingme.core.ui.R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppearancePreview(appearance, Modifier.fillMaxWidth())
            HorizontalDivider()
            val scheme = MaterialTheme.colorScheme
            val palette = PingMeTheme.networkColors
            val issues = remember(appearance, scheme, palette) { contrastIssues(appearance, scheme, palette) }
            LazyColumn(Modifier.weight(1f)) {
                if (issues.isNotEmpty()) item { ContrastWarning(issues) }
                item { SettingsSectionHeader(stringResource(R.string.appearance_section_colour)) }
                item { ColourSection(appearance, onChange) }
                item { SettingsSectionHeader(stringResource(R.string.appearance_section_shape)) }
                item { ShapeSection(appearance, onChange) }
                item { SettingsSectionHeader(stringResource(R.string.appearance_section_layout)) }
                item { LayoutSection(appearance, onChange, actions.onPickWallpaper) }
                item { SettingsSectionHeader(stringResource(R.string.appearance_section_fonts)) }
                item { FontsSection(appearance, onChange, actions.onImportFont) }
                item { SettingsSectionHeader(stringResource(R.string.appearance_section_motion)) }
                item { MotionSection(appearance, onChange) }
                item { SettingsSectionHeader(stringResource(R.string.appearance_section_icon)) }
                item { IconSection(appearance, onChange, actions.onAppIcon) }
                item { SettingsSectionHeader(stringResource(R.string.appearance_section_file)) }
                item { ThemeFileSection(actions) }
            }
        }
    }
}

/** The 4.5:1 warning (UI_DESIGN.md 7): which combinations fail, and by how much. */
@Composable
private fun ContrastWarning(issues: List<ContrastIssue>) {
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(org.pingme.core.ui.R.drawable.ic_contrast), null)
                Text(stringResource(R.string.appearance_contrast_title), style = MaterialTheme.typography.titleMedium)
            }
            issues.forEach { Text(stringResource(R.string.appearance_contrast_issue, it.what, it.ratio)) }
        }
    }
}

private const val EXPORT_NAME = "pingme-theme.json"
private val FONT_TYPES =
    arrayOf(
        "font/ttf",
        "font/otf",
        "font/sfnt",
        "application/x-font-ttf",
        "application/x-font-otf",
        "application/octet-stream",
    )
