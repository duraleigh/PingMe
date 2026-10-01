// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store.db

import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.PersonId
import org.pingme.core.model.Space
import org.pingme.core.model.SpaceId

// Conversions between core/model types and database rows. Repositories are the only callers.

internal fun Account.toEntity() =
    AccountEntity(id.value, network, displayName, colorArgb, state, showInInbox, notificationMode, credentialRef)

internal fun AccountEntity.toModel() =
    Account(AccountId(id), network, displayName, colorArgb, state, showInInbox, notificationMode, credentialRef)

internal fun Chat.toEntity() =
    ChatEntity(
        id = id.value,
        accountId = accountId.value,
        kind = kind,
        title = title,
        unreadCount = unreadCount,
        lastActivityAt = lastActivityAt,
        isPinned = isPinned,
        pinOrder = pinOrder,
        isMuted = isMuted,
        muteUntil = muteUntil,
        isArchived = isArchived,
        isLowPriority = isLowPriority,
        isObscured = isObscured,
        folder = folder,
        spaceId = spaceId?.value,
        mergedInto = mergedInto?.value,
        avatarSource = avatarSource,
        nameOverride = nameOverride,
        defaultSendAccount = defaultSendAccount?.value,
        networkRemoteId = networkRemoteId,
    )

internal fun Chat.participantEntities() =
    participants.mapIndexed { position, person -> ChatParticipantEntity(id.value, person.value, position) }

internal fun ChatWithParticipants.toModel() =
    Chat(
        id = ChatId(chat.id),
        accountId = AccountId(chat.accountId),
        kind = chat.kind,
        title = chat.title,
        participants = participants.sortedBy { it.position }.map { PersonId(it.personId) },
        unreadCount = chat.unreadCount,
        lastActivityAt = chat.lastActivityAt,
        isPinned = chat.isPinned,
        pinOrder = chat.pinOrder,
        isMuted = chat.isMuted,
        muteUntil = chat.muteUntil,
        isArchived = chat.isArchived,
        isLowPriority = chat.isLowPriority,
        isObscured = chat.isObscured,
        folder = chat.folder,
        spaceId = chat.spaceId?.let(::SpaceId),
        mergedInto = chat.mergedInto?.let(::ChatId),
        avatarSource = chat.avatarSource,
        nameOverride = chat.nameOverride,
        defaultSendAccount = chat.defaultSendAccount?.let(::AccountId),
        networkRemoteId = chat.networkRemoteId,
    )

internal fun Space.toEntity() = SpaceEntity(id.value, accountId?.value, title, kind, icon, showInAll)

internal fun Space.chatEntities() =
    chatIds.mapIndexed { position, chat -> SpaceChatEntity(id.value, chat.value, position) }

internal fun SpaceWithChats.toModel() =
    Space(
        id = SpaceId(space.id),
        accountId = space.accountId?.let(::AccountId),
        title = space.title,
        kind = space.kind,
        chatIds = chats.sortedBy { it.position }.map { ChatId(it.chatId) },
        icon = space.icon,
        showInAll = space.showInAll,
    )
