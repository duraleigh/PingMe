// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.app.inbox.displayName
import org.pingme.core.model.LinkPreviewMode
import org.pingme.core.model.NetworkId
import org.pingme.core.model.PrivacySettings
import org.pingme.core.ui.components.ChoiceSetting
import org.pingme.core.ui.components.SettingsSectionHeader
import org.pingme.core.ui.components.SwitchSetting
import org.pingme.core.ui.R as UiR

/**
 * Settings > Privacy (UI_DESIGN.md 10.3, 10.10, 10.11, 10.12): read receipts and typing with
 * per-network exceptions, clean links, link previews, and the obscured chats.
 */
@Composable
fun PrivacyPage(
    state: SettingsState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val privacy = state.app.privacy
    val change = { f: (PrivacySettings) -> PrivacySettings -> actions.update { it.copy(privacy = f(it.privacy)) } }
    val networks = state.accounts.map { it.network }.distinct()
    Column(modifier) {
        SwitchSetting(stringResource(R.string.privacy_read_receipts), privacy.readReceipts, { on ->
            change {
                it.copy(readReceipts = on)
            }
        }, description = stringResource(R.string.privacy_read_receipts_note))
        SwitchSetting(stringResource(R.string.privacy_typing), privacy.typing, { on ->
            change {
                it.copy(typing = on)
            }
        }, description = stringResource(R.string.privacy_typing_note))
        if (networks.isNotEmpty()) {
            SettingsSectionHeader(stringResource(R.string.privacy_by_network))
            networks.forEach { network -> NetworkExceptions(network, privacy, change) }
        }
        SettingsSectionHeader(stringResource(R.string.settings_privacy))
        SwitchSetting(stringResource(R.string.privacy_clean_sent), privacy.cleanLinksSent, { on ->
            change {
                it.copy(cleanLinksSent = on)
            }
        }, description = stringResource(R.string.privacy_clean_note))
        SwitchSetting(stringResource(R.string.privacy_clean_received), privacy.cleanLinksReceived, { on ->
            change {
                it.copy(cleanLinksReceived = on)
            }
        })
        ChoiceSetting(stringResource(R.string.privacy_previews), LinkPreviewMode.entries, privacy.linkPreviews, {
            stringResource(it.label())
        }, { mode ->
            change { it.copy(linkPreviews = mode) }
        })
        Text(
            stringResource(R.string.privacy_previews_note),
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        ObscuredChats(state, actions)
    }
}

/** Same as above, On, or Off, for read receipts and typing on one network. */
@Composable
private fun NetworkExceptions(
    network: NetworkId,
    privacy: PrivacySettings,
    change: ((PrivacySettings) -> PrivacySettings) -> Unit,
) {
    val choices = listOf(null, true, false)
    val label = @Composable { choice: Boolean? ->
        stringResource(
            when (choice) {
                null -> R.string.privacy_same
                true -> R.string.privacy_on
                false -> R.string.privacy_off
            },
        )
    }
    ChoiceSetting(
        "${network.displayName} · ${stringResource(
            R.string.privacy_read_receipts,
        )}",
        choices,
        privacy.readReceiptsByNetwork[network],
        label,
        { choice ->
            change {
                it.copy(
                    readReceiptsByNetwork =
                        if (choice ==
                            null
                        ) {
                            it.readReceiptsByNetwork - network
                        } else {
                            it.readReceiptsByNetwork + (network to choice)
                        },
                )
            }
        },
    )
    ChoiceSetting(
        "${network.displayName} · ${stringResource(
            R.string.privacy_typing,
        )}",
        choices,
        privacy.typingByNetwork[network],
        label,
        { choice ->
            change {
                it.copy(
                    typingByNetwork =
                        if (choice ==
                            null
                        ) {
                            it.typingByNetwork - network
                        } else {
                            it.typingByNetwork + (network to choice)
                        },
                )
            }
        },
    )
}

/** The chats the user obscured, each with a way to stop (UI_DESIGN.md 10.10). */
@Composable
private fun ObscuredChats(
    state: SettingsState,
    actions: SettingsActions,
) {
    SettingsSectionHeader(stringResource(R.string.privacy_obscured))
    if (state.obscured.isEmpty()) {
        Text(
            stringResource(R.string.privacy_obscured_none),
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    state.obscured.forEach { chat ->
        val name = chat.nameOverride ?: chat.title
        ListItem(
            headlineContent = { Text(name) },
            trailingContent = {
                IconButton({ actions.unobscure(chat) }) {
                    Icon(painterResource(UiR.drawable.ic_visibility), stringResource(R.string.privacy_unobscure, name))
                }
            },
        )
    }
}

private fun LinkPreviewMode.label() =
    when (this) {
        LinkPreviewMode.ALWAYS -> R.string.privacy_previews_always
        LinkPreviewMode.WIFI_ONLY -> R.string.privacy_previews_wifi
        LinkPreviewMode.NEVER -> R.string.privacy_previews_never
    }
