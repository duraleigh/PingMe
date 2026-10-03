// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.telegram

import org.drinkless.tdlib.TdApi
import org.pingme.connectors.telegram.td.TdError
import org.pingme.connectors.telegram.td.TelegramBridge
import org.pingme.connectors.telegram.td.TelegramClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * TDLib replaced by a pretend Telegram: any phone number and the code 12345 sign in,
 * connecting lists one chat with a short history, and sends, reactions, and deletes are
 * recorded so the contract test can check them.
 */
class FakeTelegramBridge : TelegramBridge {
    val network = FakeTelegram()

    override fun newClient(onUpdate: (TdApi.Object) -> Unit): TelegramClient = FakeTelegramClient(network, onUpdate)
}

class FakeTelegram {
    val sentTexts = ConcurrentHashMap<Long, MutableList<String>>()
    val reactions = ConcurrentHashMap<String, MutableList<String>>()
    val revoked = CopyOnWriteArraySet<String>()
    val clients = CopyOnWriteArrayList<FakeTelegramClient>()
    private val ids = AtomicLong(1000)

    fun nextId(): Long = ids.addAndGet(1)

    fun receive(
        chatId: Long,
        text: String,
    ) {
        val msg = message(nextId(), chatId, SAM, text, outgoing = false)
        clients.forEach { it.push(TdApi.UpdateNewMessage(msg)) }
    }

    fun remoteTyping(chatId: Long) {
        clients.forEach {
            it.push(TdApi.UpdateChatAction(chatId, null, TdApi.MessageSenderUser(SAM), TdApi.ChatActionTyping()))
        }
    }

    companion object {
        const val ME = 100L
        const val SAM = 200L
        const val CHAT = 200L

        fun message(
            id: Long,
            chatId: Long,
            sender: Long,
            text: String,
            outgoing: Boolean,
        ): TdApi.Message =
            TdApi.Message().apply {
                this.id = id
                this.chatId = chatId
                senderId = TdApi.MessageSenderUser(sender)
                isOutgoing = outgoing
                date = (1_759_300_000L + id).toInt()
                content = TdApi.MessageText(TdApi.FormattedText(text, emptyArray()), null, null)
            }

        fun user(
            id: Long,
            first: String,
            phone: String,
        ): TdApi.User =
            TdApi.User().apply {
                this.id = id
                firstName = first
                lastName = ""
                phoneNumber = phone
            }
    }
}

// One branch per TDLib request the connector sends.
@Suppress("TooManyFunctions", "CyclomaticComplexMethod", "LongMethod")
class FakeTelegramClient(
    private val network: FakeTelegram,
    private val onUpdate: (TdApi.Object) -> Unit,
) : TelegramClient {
    @Volatile var authorized = false
    private var loaded = false

    init {
        network.clients += this
        push(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateWaitTdlibParameters()))
    }

    fun push(update: TdApi.Object) = onUpdate(update)

    private fun samChat(): TdApi.Chat =
        TdApi.Chat().apply {
            id = FakeTelegram.CHAT
            type = TdApi.ChatTypePrivate(FakeTelegram.SAM)
            title = "Sam Ortiz"
            positions = arrayOf(TdApi.ChatPosition(TdApi.ChatListMain(), 5L, false, null))
            unreadCount = 1
            lastMessage = FakeTelegram.message(3, FakeTelegram.CHAT, FakeTelegram.SAM, "Did you see this?", false)
            lastReadOutboxMessageId = 2
        }

    private fun history(): List<TdApi.Message> =
        listOf(
            FakeTelegram.message(3, FakeTelegram.CHAT, FakeTelegram.SAM, "Did you see this?", false),
            FakeTelegram.message(2, FakeTelegram.CHAT, FakeTelegram.ME, "Sounds good", true),
            FakeTelegram.message(1, FakeTelegram.CHAT, FakeTelegram.SAM, "Lunch at noon?", false),
        )

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T {
        val answer: TdApi.Object =
            when (function) {
                is TdApi.SetTdlibParameters -> {
                    linkDir = function.databaseDirectory
                    val linked = File(function.databaseDirectory, "linked").exists()
                    push(
                        TdApi.UpdateAuthorizationState(
                            if (linked) {
                                ready(
                                    function.databaseDirectory,
                                )
                            } else {
                                TdApi.AuthorizationStateWaitPhoneNumber()
                            },
                        ),
                    )
                    TdApi.Ok()
                }

                is TdApi.SetAuthenticationPhoneNumber -> {
                    val code =
                        TdApi.AuthenticationCodeInfo(
                            function.phoneNumber,
                            TdApi.AuthenticationCodeTypeSms(5),
                            null,
                            60,
                        )
                    push(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateWaitCode(code)))
                    TdApi.Ok()
                }

                is TdApi.CheckAuthenticationCode -> {
                    if (function.code != "12345") throw TdError(400, "PHONE_CODE_INVALID")
                    File(linkDir, "linked").apply { parentFile?.mkdirs() }.writeText("yes")
                    push(TdApi.UpdateAuthorizationState(ready(linkDir)))
                    TdApi.Ok()
                }

                is TdApi.GetMe -> {
                    FakeTelegram.user(FakeTelegram.ME, "Me", "15555550100")
                }

                is TdApi.GetUser -> {
                    FakeTelegram.user(
                        function.userId,
                        if (function.userId ==
                            FakeTelegram.SAM
                        ) {
                            "Sam Ortiz"
                        } else {
                            "User ${function.userId}"
                        },
                        if (function.userId ==
                            FakeTelegram.SAM
                        ) {
                            "15555550123"
                        } else {
                            ""
                        },
                    )
                }

                is TdApi.LoadChats -> {
                    if (loaded) throw TdError(404, "Not Found")
                    loaded = true
                    push(TdApi.UpdateNewChat(samChat()))
                    TdApi.Ok()
                }

                is TdApi.GetChat -> {
                    samChat()
                }

                is TdApi.GetChats -> {
                    TdApi.Chats(1, longArrayOf(FakeTelegram.CHAT))
                }

                is TdApi.GetContacts -> {
                    TdApi.Users(1, longArrayOf(FakeTelegram.SAM))
                }

                is TdApi.GetChatHistory -> {
                    val all = history().filter { function.fromMessageId == 0L || it.id < function.fromMessageId }
                    TdApi.Messages(all.size, all.take(function.limit).toTypedArray())
                }

                is TdApi.SendMessage -> {
                    val text = (function.inputMessageContent as? TdApi.InputMessageText)?.text?.text.orEmpty()
                    network.sentTexts.getOrPut(function.chatId) { CopyOnWriteArrayList() } += text
                    val tmp = -network.nextId()
                    val pending =
                        FakeTelegram.message(tmp, function.chatId, FakeTelegram.ME, text, true).apply {
                            sendingState =
                                TdApi.MessageSendingStatePending(0)
                        }
                    val real = FakeTelegram.message(network.nextId(), function.chatId, FakeTelegram.ME, text, true)
                    push(TdApi.UpdateMessageSendSucceeded(real, tmp))
                    pending
                }

                is TdApi.AddMessageReaction -> {
                    network.reactions.getOrPut("${function.chatId}/${function.messageId}") { CopyOnWriteArrayList() } +=
                        (function.reactionType as TdApi.ReactionTypeEmoji).emoji
                    TdApi.Ok()
                }

                is TdApi.RemoveMessageReaction -> {
                    network.reactions["${function.chatId}/${function.messageId}"]?.remove(
                        (function.reactionType as TdApi.ReactionTypeEmoji).emoji,
                    )
                    TdApi.Ok()
                }

                is TdApi.GetMessage -> {
                    val chosen = network.reactions["${function.chatId}/${function.messageId}"].orEmpty()
                    FakeTelegram.message(function.messageId, function.chatId, FakeTelegram.ME, "x", true).apply {
                        interactionInfo =
                            TdApi.MessageInteractionInfo(
                                0,
                                0,
                                null,
                                TdApi.MessageReactions(
                                    chosen
                                        .map {
                                            TdApi.MessageReaction(
                                                TdApi.ReactionTypeEmoji(it),
                                                1,
                                                true,
                                                null,
                                                emptyArray(),
                                            )
                                        }.toTypedArray(),
                                    false,
                                    null,
                                    false,
                                ),
                            )
                    }
                }

                is TdApi.DeleteMessages -> {
                    function.messageIds.forEach { network.revoked += "${function.chatId}/$it" }
                    TdApi.Ok()
                }

                is TdApi.EditMessageText -> {
                    FakeTelegram.message(
                        function.messageId,
                        function.chatId,
                        FakeTelegram.ME,
                        (function.inputMessageContent as TdApi.InputMessageText).text.text,
                        true,
                    )
                }

                is TdApi.ViewMessages, is TdApi.SendChatAction, is TdApi.SetMessageSenderBlockList -> {
                    TdApi.Ok()
                }

                is TdApi.SearchUserByPhoneNumber -> {
                    FakeTelegram.user(network.nextId(), "New", function.phoneNumber.trimStart('+'))
                }

                is TdApi.CreatePrivateChat -> {
                    TdApi.Chat().apply {
                        id = function.userId
                        type =
                            TdApi.ChatTypePrivate(function.userId)
                        title = "New"
                        positions = emptyArray()
                    }
                }

                is TdApi.CreateNewBasicGroupChat -> {
                    TdApi.CreatedBasicGroupChat(network.nextId(), null)
                }

                is TdApi.DownloadFile -> {
                    val f = File.createTempFile("td", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                    TdApi.File(
                        function.fileId,
                        3,
                        3,
                        TdApi.LocalFile(f.absolutePath, true, true, false, true, 0, 3, 3),
                        TdApi.RemoteFile("r", "u", false, true, 3),
                    )
                }

                is TdApi.Close -> {
                    push(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateClosed()))
                    TdApi.Ok()
                }

                else -> {
                    TdApi.Ok()
                }
            }
        return answer as T
    }

    private var linkDir: String = ""

    private fun userNamed(id: Long): TdApi.User =
        if (id == FakeTelegram.SAM) {
            FakeTelegram.user(id, "Sam Ortiz", "15555550123")
        } else {
            FakeTelegram.user(id, "User $id", "")
        }

    private fun withReactions(
        chatId: Long,
        messageId: Long,
    ): TdApi.Message {
        val chosen = network.reactions["$chatId/$messageId"].orEmpty()
        val reactions = chosen.map { TdApi.MessageReaction(TdApi.ReactionTypeEmoji(it), 1, true, null, emptyArray()) }
        return FakeTelegram.message(messageId, chatId, FakeTelegram.ME, "x", true).apply {
            interactionInfo =
                TdApi.MessageInteractionInfo(
                    0,
                    0,
                    null,
                    TdApi.MessageReactions(reactions.toTypedArray(), false, null, false),
                )
        }
    }

    private fun edited(function: TdApi.EditMessageText): TdApi.Message {
        val text = (function.inputMessageContent as TdApi.InputMessageText).text.text
        return FakeTelegram.message(function.messageId, function.chatId, FakeTelegram.ME, text, true)
    }

    private fun privateChat(userId: Long): TdApi.Chat =
        TdApi.Chat().apply {
            id = userId
            type = TdApi.ChatTypePrivate(userId)
            title = "New"
            positions = emptyArray()
        }

    private fun downloaded(fileId: Int): TdApi.File {
        val f = File.createTempFile("td", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val local = TdApi.LocalFile(f.absolutePath, true, true, false, true, 0, 3, 3)
        return TdApi.File(fileId, 3, 3, local, TdApi.RemoteFile("r", "u", false, true, 3))
    }

    private fun ready(dirPath: String): TdApi.AuthorizationState {
        linkDir = dirPath
        authorized = true
        return TdApi.AuthorizationStateReady()
    }

    override fun close() {
        push(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateClosed()))
    }
}
