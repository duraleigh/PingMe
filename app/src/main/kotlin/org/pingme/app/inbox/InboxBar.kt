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
 * The bar: All always comes first, then up to [MAX_ITEMS] of the user's choice, so five
 * buttons at most. Narrowings are remembered per network until changed.
 */
@Serializable
data class InboxBarConfig(
    val items: List<InboxBarItem>,
    val narrowings: Map<NetworkId, Narrowing> = emptyMap(),
) {
    companion object {
        const val MAX_ITEMS = 4

        /** Before the user sets anything: Unread, then the networks they have, in order of adding. */
        fun default(networks: List<NetworkId>) =
            InboxBarConfig(
                listOf(InboxBarItem.Unread) +
                    networks.distinct().take(MAX_ITEMS - 1).map { InboxBarItem.Network(it) },
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
        val config: Flow<InboxBarConfig?> = settings.inboxBarJson.map { decode(it) }

        /** [change] gets the current bar, or the default when none is saved yet. */
        suspend fun update(
            default: InboxBarConfig,
            change: (InboxBarConfig) -> InboxBarConfig,
        ) {
            settings.updateInboxBarJson { json ->
                val next = change(decode(json) ?: default)
                JSON.encodeToString(
                    InboxBarConfig.serializer(),
                    next.copy(items = next.items.distinct().take(InboxBarConfig.MAX_ITEMS)),
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
