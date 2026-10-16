// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.NetworkId
import org.pingme.core.model.SpaceId
import org.pingme.core.store.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One button in the inbox bottom bar besides All (UI_DESIGN.md 3.1, 10.4, 10.7): a filter
 * the user chose.
 */
@Serializable
sealed interface InboxBarItem {
    @Serializable
    @SerialName("unread")
    data object Unread : InboxBarItem

    @Serializable
    @SerialName("network")
    data class Network(
        val network: NetworkId,
    ) : InboxBarItem

    @Serializable
    @SerialName("space")
    data class Space(
        val id: SpaceId,
    ) : InboxBarItem

    @Serializable
    @SerialName("low_priority")
    data object LowPriority : InboxBarItem
}

/** What a long-press on a network button narrowed it to (UI_DESIGN.md 6.4, 6.5). */
@Serializable
sealed interface Narrowing {
    @Serializable
    @SerialName("account")
    data class Account(
        val id: AccountId,
    ) : Narrowing

    /** Instagram: Primary only or General only. */
    @Serializable
    @SerialName("folder")
    data class Folder(
        val folder: ChatFolder,
    ) : Narrowing
}

/**
 * The bar (UI_DESIGN.md 10.4): All first unless the user removed it, then their picks, four
 * buttons at most; a fixed fifth "More" button holds everything else (owner, 2026-10-03).
 * With All removed the inbox opens on the first button. Narrowings are remembered per
 * network until changed.
 */
@Serializable
data class InboxBarConfig(
    val items: List<InboxBarItem>,
    val narrowings: Map<NetworkId, Narrowing> = emptyMap(),
    val showAll: Boolean = true,
) {
    /** The buttons in order, null standing for All. */
    val buttons: List<InboxBarItem?> get() = (if (showAll) listOf(null) else emptyList()) + items

    /** Keeps to four buttons and never leaves the bar empty: with nothing else, All comes back. */
    fun tidy(): InboxBarConfig {
        val picks = items.distinct()
        val all = showAll || picks.isEmpty()
        return copy(items = picks.take(if (all) MAX_BUTTONS - 1 else MAX_BUTTONS), showAll = all)
    }

    /** These [buttons], null standing for All, keeping the narrowings. */
    fun withButtons(buttons: List<InboxBarItem?>) =
        copy(items = buttons.filterNotNull(), showAll = null in buttons).tidy()

    companion object {
        /** The chosen positions; the fifth is always More. */
        const val MAX_BUTTONS = 4

        /** Before the user sets anything: All, Unread, then the networks they have, in order of adding. */
        fun default(networks: List<NetworkId>) =
            InboxBarConfig(
                listOf(InboxBarItem.Unread) +
                    networks.distinct().take(MAX_BUTTONS - 2).map { InboxBarItem.Network(it) },
            )
    }
}

/** Keeps the bar in the settings store as JSON. Null until the user changes it. */
@Singleton
class InboxBarRepository
    @Inject
    constructor(
        private val settings: SettingsRepository,
    ) {
        /** A bar saved with five picks before the More button existed loads as four (owner, 2026-10-03). */
        val config: Flow<InboxBarConfig?> = settings.inboxBarJson.map { decode(it)?.tidy() }

        /** [change] gets the current bar, or the default when none is saved yet. */
        suspend fun update(
            default: InboxBarConfig,
            change: (InboxBarConfig) -> InboxBarConfig,
        ) {
            settings.updateInboxBarJson { json ->
                val next = change(decode(json) ?: default)
                JSON.encodeToString(
                    InboxBarConfig.serializer(),
                    next.tidy(),
                )
            }
        }

        private fun decode(json: String?): InboxBarConfig? =
            json?.let {
                try {
                    JSON.decodeFromString(InboxBarConfig.serializer(), it)
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

        private companion object {
            val JSON = Json { ignoreUnknownKeys = true }
        }
    }
