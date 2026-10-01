// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.pingme.core.model.KeywordRule
import org.pingme.core.model.KeywordRuleId
import org.pingme.core.store.db.PingMeDatabase
import org.pingme.core.store.db.toEntity
import org.pingme.core.store.db.toModel
import javax.inject.Inject
import javax.inject.Singleton

/** App settings: preferences DataStore for switches, the database for keyword rules. */
@Singleton
class SettingsRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        db: PingMeDatabase,
    ) {
        private val keywordDao = db.keywordRuleDao()

        /** "Show General in inbox" for Instagram, on by default (UI_DESIGN.md 6.4). */
        val instagramShowGeneral: Flow<Boolean> = dataStore.data.map { it[INSTAGRAM_SHOW_GENERAL] ?: true }

        suspend fun setInstagramShowGeneral(show: Boolean) {
            dataStore.edit { it[INSTAGRAM_SHOW_GENERAL] = show }
        }

        /**
         * The appearance (UI_DESIGN.md 4) as its theme-file JSON. The store keeps it opaque;
         * the app encodes and decodes it. Null until the user changes anything.
         */
        val appearanceJson: Flow<String?> = dataStore.data.map { it[APPEARANCE] }

        /** Changes the appearance atomically: [change] gets the current JSON and returns the new one. */
        suspend fun updateAppearanceJson(change: (String?) -> String) {
            dataStore.edit { it[APPEARANCE] = change(it[APPEARANCE]) }
        }

        /**
         * The inbox bottom bar (UI_DESIGN.md 3.1, 10.4): its items and, per network, which account
         * or folder a long-press narrowed it to. Opaque JSON like the appearance; null until set.
         */
        val inboxBarJson: Flow<String?> = dataStore.data.map { it[INBOX_BAR] }

        suspend fun updateInboxBarJson(change: (String?) -> String) {
            dataStore.edit { it[INBOX_BAR] = change(it[INBOX_BAR]) }
        }

        /** The quick-reaction bar, in order (UI_DESIGN.md 5.4). Edited in Settings > Reactions. */
        val quickReactions: Flow<List<String>> =
            dataStore.data.map { prefs ->
                prefs[QUICK_REACTIONS]?.split(SEPARATOR)?.filter { it.isNotEmpty() }
                    ?: DEFAULT_QUICK_REACTIONS
            }

        suspend fun setQuickReactions(emoji: List<String>) {
            dataStore.edit { it[QUICK_REACTIONS] = emoji.take(MAX_QUICK_REACTIONS).joinToString(SEPARATOR) }
        }

        /** What a double tap on a bubble sends (UI_DESIGN.md 10.5), ❤️ unless changed. */
        val doubleTapReaction: Flow<String> = dataStore.data.map { it[DOUBLE_TAP] ?: DEFAULT_DOUBLE_TAP }

        suspend fun setDoubleTapReaction(emoji: String) {
            dataStore.edit { it[DOUBLE_TAP] = emoji }
        }

        /** Emoji picked from the full picker, newest first, for its Recent row. */
        val recentEmoji: Flow<List<String>> =
            dataStore.data.map { prefs -> prefs[RECENT_EMOJI]?.split(SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty() }

        suspend fun addRecentEmoji(emoji: String) {
            dataStore.edit { prefs ->
                val now =
                    listOf(emoji) +
                        prefs[RECENT_EMOJI]?.split(SEPARATOR).orEmpty().filter { it.isNotEmpty() && it != emoji }
                prefs[RECENT_EMOJI] = now.take(MAX_RECENT).joinToString(SEPARATOR)
            }
        }

        fun keywordRules(): Flow<List<KeywordRule>> = keywordDao.observeAll().map { rows -> rows.map { it.toModel() } }

        suspend fun upsertKeywordRule(rule: KeywordRule) = keywordDao.upsert(rule.toEntity())

        suspend fun deleteKeywordRule(id: KeywordRuleId) = keywordDao.delete(id.value)

        private companion object {
            val INSTAGRAM_SHOW_GENERAL = booleanPreferencesKey("instagram_show_general")
            val APPEARANCE = stringPreferencesKey("appearance")
            val INBOX_BAR = stringPreferencesKey("inbox_bar")
            val QUICK_REACTIONS = stringPreferencesKey("quick_reactions")
            val DOUBLE_TAP = stringPreferencesKey("double_tap_reaction")
            val RECENT_EMOJI = stringPreferencesKey("recent_emoji")

            /** Emoji never contain a line break, so it separates them. */
            const val SEPARATOR = "\n"
            val DEFAULT_QUICK_REACTIONS = listOf("❤️", "😂", "👍", "😮", "😢", "🔥")
            const val DEFAULT_DOUBLE_TAP = "❤️"
            const val MAX_QUICK_REACTIONS = 8
            const val MAX_RECENT = 32
        }
    }
