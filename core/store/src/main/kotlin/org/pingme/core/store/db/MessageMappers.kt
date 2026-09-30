// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store.db

import org.pingme.core.model.AccountId
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentId
import org.pingme.core.model.ChatId
import org.pingme.core.model.LinkPreview
import org.pingme.core.model.MediaSaveJob
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.model.PersonId
import org.pingme.core.model.Quote
import org.pingme.core.model.Reaction
import org.pingme.core.model.ScheduledSend

// Conversions between core/model types and database rows. Repositories are the only callers.

internal fun Message.toEntity() =
    MessageEntity(
        id = id.value,
        chatId = chatId.value,
        senderId = senderId.value,
        sentAt = sentAt,
        receivedAt = receivedAt,
        body = body,
        kind = kind,
        replyTo = replyTo?.value,
        quote = quote?.let { QuoteColumns(it.senderName, it.text) },
        editedAt = editedAt,
        deletedForEveryone = deletedForEveryone,
        status = status,
        transport = transport,
        networkRemoteId = networkRemoteId,
        linkPreview =
            linkPreview?.let {
                LinkPreviewColumns(
                    it.url,
                    it.cleanedUrl,
                    it.title,
                    it.description,
                    it.imagePath,
                    it.fetchedAt,
                    it.source,
                )
            },
        isOutgoing = isOutgoing,
    )

internal fun Message.attachmentEntities() =
    attachments.mapIndexed { position, a ->
        AttachmentEntity(
            id = a.id.value,
            messageId = id.value,
            position = position,
            kind = a.kind,
            mimeType = a.mimeType,
            fileName = a.fileName,
            sizeBytes = a.sizeBytes,
            localPath = a.localPath,
            remoteRef = a.remoteRef,
            durationMs = a.durationMs,
            width = a.width,
            height = a.height,
            isEphemeral = a.isEphemeral,
            savedAt = a.savedAt,
        )
    }

internal fun Message.reactionEntities() = reactions.map { ReactionEntity(id.value, it.senderId.value, it.emoji, it.at) }

internal fun MessageWithParts.toModel() =
    Message(
        id = MessageId(message.id),
        chatId = ChatId(message.chatId),
        senderId = PersonId(message.senderId),
        sentAt = message.sentAt,
        receivedAt = message.receivedAt,
        body = message.body,
        kind = message.kind,
        attachments = attachments.sortedBy { it.position }.map { it.toModel() },
        replyTo = message.replyTo?.let(::MessageId),
        quote = message.quote?.let { Quote(it.senderName, it.text) },
        editedAt = message.editedAt,
        deletedForEveryone = message.deletedForEveryone,
        status = message.status,
        reactions = reactions.sortedBy { it.at }.map { Reaction(it.emoji, PersonId(it.senderId), it.at) },
        transport = message.transport,
        networkRemoteId = message.networkRemoteId,
        linkPreview =
            message.linkPreview?.let {
                LinkPreview(it.url, it.cleanedUrl, it.title, it.description, it.imagePath, it.fetchedAt, it.source)
            },
        isOutgoing = message.isOutgoing,
    )

internal fun AttachmentEntity.toModel() =
    Attachment(
        id = AttachmentId(id),
        kind = kind,
        mimeType = mimeType,
        fileName = fileName,
        sizeBytes = sizeBytes,
        localPath = localPath,
        remoteRef = remoteRef,
        durationMs = durationMs,
        width = width,
        height = height,
        isEphemeral = isEphemeral,
        savedAt = savedAt,
    )

internal fun ScheduledSend.toEntity() =
    ScheduledSendEntity(messageId.value, sendAt, accountId.value, chatId.value, payloadJson, attempts)

internal fun ScheduledSendEntity.toModel() =
    ScheduledSend(MessageId(messageId), sendAt, AccountId(accountId), ChatId(chatId), payloadJson, attempts)

internal fun MediaSaveJob.toEntity() = MediaSaveJobEntity(attachmentId.value, state)

internal fun MediaSaveJobEntity.toModel() = MediaSaveJob(AttachmentId(attachmentId), state)
