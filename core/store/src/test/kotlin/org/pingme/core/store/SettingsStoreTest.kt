// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.pingme.core.model.AccountId
import org.pingme.core.model.AttachmentId
import org.pingme.core.model.ChatId
import org.pingme.core.model.KeywordRule
import org.pingme.core.model.KeywordRuleId
import org.pingme.core.model.KeywordScope
import org.pingme.core.model.MediaSaveJob
import org.pingme.core.model.MediaSaveState

class SettingsStoreTest : StoreTest() {
    @Test
    fun instagramGeneralIsShownByDefaultAndCanBeHidden() =
        runTest {
            assertEquals(true, settings.instagramShowGeneral.first())
            settings.setInstagramShowGeneral(false)
            assertEquals(false, settings.instagramShowGeneral.first())
        }

    @Test
    fun keywordRulesRoundTripWithEveryScope() =
        runTest {
            val rules =
                listOf(
                    KeywordRule(KeywordRuleId("1"), "dinner", true, false, KeywordScope.All, "kw_1", false),
                    KeywordRule(
                        KeywordRuleId("2"),
                        "ESTOP",
                        false,
                        true,
                        KeywordScope.Accounts(listOf(AccountId("a"))),
                        "kw_2",
                        true,
                    ),
                    KeywordRule(
                        KeywordRuleId("3"),
                        "urgent",
                        true,
                        false,
                        KeywordScope.Chats(listOf(ChatId("c"))),
                        "kw_3",
                        true,
                    ),
                )
            rules.forEach { settings.upsertKeywordRule(it) }
            assertEquals(rules.sortedBy { it.pattern }, settings.keywordRules().first())
            settings.deleteKeywordRule(KeywordRuleId("2"))
            assertEquals(listOf("dinner", "urgent"), settings.keywordRules().first().map { it.pattern })
        }

    @Test
    fun mediaSaveJobsMoveBetweenStates() =
        runTest {
            messages.upsertMediaSaveJob(MediaSaveJob(AttachmentId("x"), MediaSaveState.PENDING))
            messages.upsertMediaSaveJob(MediaSaveJob(AttachmentId("y"), MediaSaveState.PENDING))
            messages.upsertMediaSaveJob(MediaSaveJob(AttachmentId("x"), MediaSaveState.DONE))
            assertEquals(
                listOf(AttachmentId("y")),
                messages.mediaSaveJobs(MediaSaveState.PENDING).first().map { it.attachmentId },
            )
            assertEquals(
                listOf(AttachmentId("x")),
                messages.mediaSaveJobs(MediaSaveState.DONE).first().map { it.attachmentId },
            )
        }
}
