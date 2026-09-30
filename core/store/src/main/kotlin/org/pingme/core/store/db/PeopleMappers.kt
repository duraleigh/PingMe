// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store.db

import org.pingme.core.model.AccountId
import org.pingme.core.model.ContactId
import org.pingme.core.model.KeywordRule
import org.pingme.core.model.KeywordRuleId
import org.pingme.core.model.MergeLink
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId

// Conversions between core/model types and database rows. Repositories are the only callers.

internal fun Person.toEntity() =
    PersonEntity(id.value, accountId.value, displayName, phoneNumber, networkHandle, avatarPath, contactId?.value)

internal fun PersonEntity.toModel() =
    Person(
        PersonId(id),
        AccountId(accountId),
        displayName,
        phoneNumber,
        networkHandle,
        avatarPath,
        contactId?.let(::ContactId),
    )

internal fun KeywordRule.toEntity() =
    KeywordRuleEntity(id.value, pattern, wholeWord, caseSensitive, scope, channelId, overridesSilence)

internal fun KeywordRuleEntity.toModel() =
    KeywordRule(KeywordRuleId(id), pattern, wholeWord, caseSensitive, scope, channelId, overridesSilence)

internal fun MergeLink.toEntity() = MergeLinkEntity(personId.value, contactId.value, confirmedAt)

internal fun MergeLinkEntity.toModel() = MergeLink(PersonId(personId), ContactId(contactId), confirmedAt)
