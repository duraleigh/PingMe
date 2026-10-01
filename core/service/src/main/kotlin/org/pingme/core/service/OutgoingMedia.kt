// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import org.pingme.core.connector.OutgoingAttachment
import org.pingme.core.connector.accountId
import org.pingme.core.connector.attachment
import org.pingme.core.connector.remoteId
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageKind
import java.io.File

/** How a file the user is sending shows on its pending bubble before the network has it. */
internal fun OutgoingAttachment.asAttachment(
    message: MessageId,
    index: Int,
) = Attachment(
    id = message.accountId.attachment("${message.remoteId}-$index"),
    kind = kind,
    mimeType = mimeType,
    fileName = fileName,
    sizeBytes = File(localPath).length(),
    localPath = localPath,
    remoteRef = null,
    durationMs = durationMs,
    width = null,
    height = null,
    isEphemeral = false,
    savedAt = null,
)

/** The file again, for a retry; null when it has gone from the phone. */
internal fun Attachment.asOutgoing(): OutgoingAttachment? =
    localPath?.takeIf { File(it).exists() }?.let {
        OutgoingAttachment(it, mimeType, kind, fileName, caption = null, durationMs = durationMs)
    }

/** The message kind a first attachment gives its message. */
internal fun kindOf(kind: AttachmentKind) =
    when (kind) {
        AttachmentKind.IMAGE -> MessageKind.IMAGE
        AttachmentKind.VIDEO -> MessageKind.VIDEO
        AttachmentKind.AUDIO, AttachmentKind.FILE -> MessageKind.FILE
        AttachmentKind.VOICE -> MessageKind.VOICE
        AttachmentKind.GIF -> MessageKind.GIF
        AttachmentKind.STICKER -> MessageKind.STICKER
        AttachmentKind.CONTACT -> MessageKind.CONTACT
        AttachmentKind.LOCATION -> MessageKind.LOCATION
    }
