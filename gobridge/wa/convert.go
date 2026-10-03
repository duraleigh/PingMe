// SPDX-License-Identifier: AGPL-3.0-or-later

package wa

import (
	"encoding/base64"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// The JSON shapes Kotlin reads. Timestamps are Unix milliseconds; binary keys are base64.

// Chat is one conversation: a person or a group.
type Chat struct {
	ID       string `json:"id"`
	Name     string `json:"name,omitempty"`
	IsGroup  bool   `json:"isGroup"`
	Unread   int    `json:"unread"`
	LastAt   int64  `json:"lastMessageAt"`
	Archived bool   `json:"archived"`
	Pinned   bool   `json:"pinned"`
	// Community (WhatsApp's name for a group of groups): the parent's id on its member
	// groups, and IsCommunity on the parent itself (UI_DESIGN.md 10.4: shown as a space).
	CommunityID string `json:"communityId,omitempty"`
	IsCommunity bool   `json:"isCommunity"`
	// Members, for groups; a person's own entry for one-to-one chats.
	Participants []Participant `json:"participants"`
}

// Participant is a person in a chat. ID is the JID messages come from; Phone the number
// when known (a group may show a person by a hidden id only).
type Participant struct {
	ID      string `json:"id"`
	Phone   string `json:"phone,omitempty"`
	Name    string `json:"name,omitempty"`
	IsMe    bool   `json:"isMe"`
	IsAdmin bool   `json:"isAdmin"`
}

// Message is one message, flattened: text, or one piece of media, or a reaction, a
// revoke, or an edit of another message.
type Message struct {
	ID          string `json:"id"`
	Chat        string `json:"chat"`
	Sender      string `json:"sender"`
	SenderPhone string `json:"senderPhone,omitempty"`
	PushName    string `json:"pushName,omitempty"`
	FromMe      bool   `json:"fromMe"`
	Timestamp   int64  `json:"timestamp"`
	// "text", "image", "video", "gif", "voice", "audio", "document", "sticker",
	// "location", "contact", "reaction", "revoke", "edit", "system", or "unsupported".
	Kind       string    `json:"kind"`
	Text       string    `json:"text,omitempty"`
	Media      *Media    `json:"media,omitempty"`
	Reaction   *Reaction `json:"reaction,omitempty"`
	Revoke     *Target   `json:"revoke,omitempty"`
	Edit       *Edit     `json:"edit,omitempty"`
	ReplyTo    *Quote    `json:"replyTo,omitempty"`
	Location   *Location `json:"location,omitempty"`
	Contact    *Contact  `json:"contact,omitempty"`
	Status     string    `json:"status,omitempty"` // "pending", "sent", "delivered", "read", "played", "error"
	IsViewOnce bool      `json:"isViewOnce"`
	Ephemeral  bool      `json:"isEphemeral"`
	Edited     bool      `json:"edited"`
	System     string    `json:"system,omitempty"` // the stub type's name for system lines
	Mentions   []string  `json:"mentions,omitempty"`
}

// Media is what Download needs, plus what the bubble shows before the file is there.
type Media struct {
	// The whatsmeow media type the keys are for: "WhatsApp Image Keys" and the like.
	Type          string `json:"type"`
	Mime          string `json:"mime"`
	DirectPath    string `json:"directPath"`
	URL           string `json:"url,omitempty"`
	MediaKey      string `json:"mediaKey"`
	FileSHA256    string `json:"fileSha256"`
	FileEncSHA256 string `json:"fileEncSha256"`
	FileLength    int64  `json:"fileLength"`
	Width         int64  `json:"width,omitempty"`
	Height        int64  `json:"height,omitempty"`
	Seconds       int64  `json:"seconds,omitempty"`
	FileName      string `json:"fileName,omitempty"`
	Caption       string `json:"caption,omitempty"`
	Voice         bool   `json:"voice"` // a voice note (push to talk), not a music file
	Gif           bool   `json:"gif"`   // a video WhatsApp plays as a GIF
	Animated      bool   `json:"animated"`
	// The message the file came in, for asking the phone to upload it again once expired.
	MessageID string `json:"messageId,omitempty"`
	Chat      string `json:"chat,omitempty"`
	Sender    string `json:"sender,omitempty"`
	FromMe    bool   `json:"fromMe,omitempty"`
}

// Reaction is an emoji on another message; an empty Emoji takes it away.
type Reaction struct {
	TargetID     string `json:"targetId"`
	TargetSender string `json:"targetSender,omitempty"`
	TargetFromMe bool   `json:"targetFromMe"`
	Emoji        string `json:"emoji"`
}

// Target names another message: the one revoked (deleted for everyone).
type Target struct {
	ID     string `json:"id"`
	Sender string `json:"sender,omitempty"`
	FromMe bool   `json:"fromMe"`
}

// Edit replaces another message's text.
type Edit struct {
	TargetID string `json:"targetId"`
	Text     string `json:"text"`
}

// Quote is the message a reply answers.
type Quote struct {
	ID     string `json:"id"`
	Sender string `json:"sender,omitempty"`
	Text   string `json:"text,omitempty"`
}

type Location struct {
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
	Name      string  `json:"name,omitempty"`
}

type Contact struct {
	DisplayName string `json:"displayName"`
	VCard       string `json:"vcard"`
}

// Receipt is delivery, read, or played, for one or more messages.
type Receipt struct {
	Chat      string   `json:"chat"`
	Sender    string   `json:"sender"`
	IDs       []string `json:"ids"`
	Kind      string   `json:"kind"` // "delivered", "read", "played"
	Timestamp int64    `json:"timestamp"`
	FromMe    bool     `json:"fromMe"` // our own read on another device
}

// HistoryChat is one conversation from a history sync with its messages, newest first.
type HistoryChat struct {
	Chat     Chat      `json:"chat"`
	Messages []Message `json:"messages"`
}

// SendRequest is what SendText and SendMedia take as the reply part.
type ReplyRequest struct {
	ID     string `json:"id"`
	Sender string `json:"sender"`
	FromMe bool   `json:"fromMe"`
	Text   string `json:"text"`
}

// --- conversion ---

func millis(t time.Time) int64 {
	if t.IsZero() {
		return 0
	}
	return t.UnixMilli()
}

func b64(b []byte) string {
	if len(b) == 0 {
		return ""
	}
	return base64.StdEncoding.EncodeToString(b)
}

// convertMessage flattens a live or historical message. phoneOf resolves a hidden
// (LID) sender to a phone number when the store knows it.
func convertMessage(evt *events.Message, phoneOf func(types.JID) string, canon func(types.JID) types.JID) Message {
	info := evt.Info
	out := Message{
		ID:         info.ID,
		Chat:       canonicalChat(info, canon).String(),
		Sender:     canonicalSender(info, canon).String(),
		PushName:   info.PushName,
		FromMe:     info.IsFromMe,
		Timestamp:  millis(info.Timestamp),
		Kind:       "text",
		IsViewOnce: evt.IsViewOnce,
		Ephemeral:  evt.IsEphemeral,
		Edited:     evt.IsEdit,
	}
	if !info.SenderAlt.IsEmpty() && info.SenderAlt.Server == types.DefaultUserServer {
		out.SenderPhone = info.SenderAlt.ToNonAD().User
	} else if info.Sender.Server == types.DefaultUserServer {
		out.SenderPhone = info.Sender.ToNonAD().User
	} else if phoneOf != nil {
		out.SenderPhone = phoneOf(info.Sender)
	}
	if evt.SourceWebMsg != nil {
		out.Status = webStatus(evt.SourceWebMsg.GetStatus(), info.IsFromMe)
		if stub := evt.SourceWebMsg.GetMessageStubType(); stub != waWeb.WebMessageInfo_UNKNOWN {
			out.Kind = "system"
			out.System = stub.String()
			return out
		}
	}
	fill(&out, evt.Message)
	if out.Media != nil {
		out.Media.MessageID = info.ID
		out.Media.Chat = out.Chat
		out.Media.Sender = out.Sender
		out.Media.FromMe = info.IsFromMe
	}
	return out
}

// canonicalChat is the one id for the chat a message belongs to: a one-to-one chat under
// a hidden address becomes the phone-number form when the message itself names it, or the
// store knows it; groups and known numbers stay as they are.
func canonicalChat(info types.MessageInfo, canon func(types.JID) types.JID) types.JID {
	chat := info.Chat
	if chat.Server != types.HiddenUserServer {
		return chat
	}
	alt := info.RecipientAlt
	if !info.IsFromMe {
		alt = info.SenderAlt
	}
	if alt.Server == types.DefaultUserServer && !alt.IsEmpty() {
		return alt.ToNonAD()
	}
	if canon != nil {
		return canon(chat)
	}
	return chat
}

func canonicalSender(info types.MessageInfo, canon func(types.JID) types.JID) types.JID {
	if info.Sender.Server == types.HiddenUserServer && info.SenderAlt.Server == types.DefaultUserServer && !info.SenderAlt.IsEmpty() {
		return info.SenderAlt.ToNonAD()
	}
	if canon != nil {
		return canon(info.Sender)
	}
	return info.Sender.ToNonAD()
}

// housekeeping is a message with nothing for a person to read: votes, pins, keep-in-chat
// marks, encrypted reactions and comments, placeholders, and the key-share-only envelopes
// WhatsApp sends between devices. Shown as nothing rather than "not supported".
func housekeeping(msg *waE2E.Message) bool {
	switch {
	case msg.PollUpdateMessage != nil, msg.PinInChatMessage != nil, msg.KeepInChatMessage != nil,
		msg.EncReactionMessage != nil, msg.EncCommentMessage != nil, msg.EncEventResponseMessage != nil,
		msg.PlaceholderMessage != nil, msg.StickerSyncRmrMessage != nil, msg.StatusMentionMessage != nil:
		return true
	case msg.SenderKeyDistributionMessage != nil, msg.FastRatchetKeySenderKeyDistributionMessage != nil, msg.MessageContextInfo != nil:
		// Only the envelope: no part a person could read.
		return !readable(msg)
	}
	return false
}

// readable is whether a message carries any part a person could read or play.
func readable(msg *waE2E.Message) bool {
	return msg.Conversation != nil || msg.ExtendedTextMessage != nil || msg.ImageMessage != nil ||
		msg.VideoMessage != nil || msg.AudioMessage != nil || msg.DocumentMessage != nil ||
		msg.StickerMessage != nil || msg.LocationMessage != nil || msg.ContactMessage != nil ||
		msg.ContactsArrayMessage != nil || msg.LiveLocationMessage != nil || msg.PtvMessage != nil ||
		msg.GroupInviteMessage != nil || msg.ListMessage != nil || msg.ButtonsMessage != nil ||
		msg.TemplateMessage != nil || msg.InteractiveMessage != nil || msg.OrderMessage != nil ||
		msg.ProductMessage != nil || msg.EventMessage != nil || msg.AlbumMessage != nil ||
		msg.DocumentWithCaptionMessage != nil || msg.ViewOnceMessage != nil || msg.ViewOnceMessageV2 != nil ||
		msg.ViewOnceMessageV2Extension != nil || msg.EphemeralMessage != nil || msg.EditedMessage != nil ||
		msg.CallLogMesssage != nil || msg.CommentMessage != nil || msg.LottieStickerMessage != nil ||
		msg.PollCreationMessage != nil || msg.PollCreationMessageV2 != nil || msg.PollCreationMessageV3 != nil ||
		msg.RequestPaymentMessage != nil || msg.SendPaymentMessage != nil || msg.InvoiceMessage != nil ||
		msg.ReactionMessage != nil || msg.ProtocolMessage != nil
}

func webStatus(status waWeb.WebMessageInfo_Status, fromMe bool) string {
	if !fromMe {
		return ""
	}
	switch status {
	case waWeb.WebMessageInfo_READ:
		return "read"
	case waWeb.WebMessageInfo_PLAYED:
		return "played"
	case waWeb.WebMessageInfo_DELIVERY_ACK:
		return "delivered"
	case waWeb.WebMessageInfo_SERVER_ACK:
		return "sent"
	case waWeb.WebMessageInfo_ERROR:
		return "error"
	default:
		return "pending"
	}
}

// fill reads the one part a message carries.
func fill(out *Message, msg *waE2E.Message) {
	if msg == nil {
		out.Kind = "unsupported"
		return
	}
	var ctx *waE2E.ContextInfo
	switch {
	case msg.Conversation != nil:
		out.Text = msg.GetConversation()
	case msg.ExtendedTextMessage != nil:
		out.Text = msg.ExtendedTextMessage.GetText()
		ctx = msg.ExtendedTextMessage.GetContextInfo()
	case msg.ImageMessage != nil:
		m := msg.ImageMessage
		out.Kind = "image"
		out.Media = &Media{Type: string(whatsmeow.MediaImage), Mime: m.GetMimetype(), DirectPath: m.GetDirectPath(), URL: m.GetURL(),
			MediaKey: b64(m.GetMediaKey()), FileSHA256: b64(m.GetFileSHA256()), FileEncSHA256: b64(m.GetFileEncSHA256()),
			FileLength: int64(m.GetFileLength()), Width: int64(m.GetWidth()), Height: int64(m.GetHeight()), Caption: m.GetCaption()}
		out.Text = m.GetCaption()
		out.IsViewOnce = out.IsViewOnce || m.GetViewOnce()
		ctx = m.GetContextInfo()
	case msg.VideoMessage != nil:
		m := msg.VideoMessage
		out.Kind = "video"
		if m.GetGifPlayback() {
			out.Kind = "gif"
		}
		out.Media = &Media{Type: string(whatsmeow.MediaVideo), Mime: m.GetMimetype(), DirectPath: m.GetDirectPath(), URL: m.GetURL(),
			MediaKey: b64(m.GetMediaKey()), FileSHA256: b64(m.GetFileSHA256()), FileEncSHA256: b64(m.GetFileEncSHA256()),
			FileLength: int64(m.GetFileLength()), Width: int64(m.GetWidth()), Height: int64(m.GetHeight()), Seconds: int64(m.GetSeconds()),
			Caption: m.GetCaption(), Gif: m.GetGifPlayback()}
		out.Text = m.GetCaption()
		out.IsViewOnce = out.IsViewOnce || m.GetViewOnce()
		ctx = m.GetContextInfo()
	case msg.AudioMessage != nil:
		m := msg.AudioMessage
		out.Kind = "audio"
		if m.GetPTT() {
			out.Kind = "voice"
		}
		out.Media = &Media{Type: string(whatsmeow.MediaAudio), Mime: m.GetMimetype(), DirectPath: m.GetDirectPath(), URL: m.GetURL(),
			MediaKey: b64(m.GetMediaKey()), FileSHA256: b64(m.GetFileSHA256()), FileEncSHA256: b64(m.GetFileEncSHA256()),
			FileLength: int64(m.GetFileLength()), Seconds: int64(m.GetSeconds()), Voice: m.GetPTT()}
		out.IsViewOnce = out.IsViewOnce || m.GetViewOnce()
		ctx = m.GetContextInfo()
	case msg.DocumentMessage != nil:
		m := msg.DocumentMessage
		out.Kind = "document"
		out.Media = &Media{Type: string(whatsmeow.MediaDocument), Mime: m.GetMimetype(), DirectPath: m.GetDirectPath(), URL: m.GetURL(),
			MediaKey: b64(m.GetMediaKey()), FileSHA256: b64(m.GetFileSHA256()), FileEncSHA256: b64(m.GetFileEncSHA256()),
			FileLength: int64(m.GetFileLength()), FileName: m.GetFileName(), Caption: m.GetCaption()}
		out.Text = m.GetCaption()
		ctx = m.GetContextInfo()
	case msg.StickerMessage != nil:
		m := msg.StickerMessage
		out.Kind = "sticker"
		out.Media = &Media{Type: string(whatsmeow.MediaImage), Mime: m.GetMimetype(), DirectPath: m.GetDirectPath(), URL: m.GetURL(),
			MediaKey: b64(m.GetMediaKey()), FileSHA256: b64(m.GetFileSHA256()), FileEncSHA256: b64(m.GetFileEncSHA256()),
			FileLength: int64(m.GetFileLength()), Width: int64(m.GetWidth()), Height: int64(m.GetHeight()), Animated: m.GetIsAnimated()}
		ctx = m.GetContextInfo()
	case msg.LocationMessage != nil:
		m := msg.LocationMessage
		out.Kind = "location"
		out.Location = &Location{Latitude: m.GetDegreesLatitude(), Longitude: m.GetDegreesLongitude(), Name: m.GetName()}
		ctx = m.GetContextInfo()
	case msg.LiveLocationMessage != nil:
		m := msg.LiveLocationMessage
		out.Kind = "location"
		out.Location = &Location{Latitude: m.GetDegreesLatitude(), Longitude: m.GetDegreesLongitude()}
		ctx = m.GetContextInfo()
	case msg.ContactMessage != nil:
		m := msg.ContactMessage
		out.Kind = "contact"
		out.Contact = &Contact{DisplayName: m.GetDisplayName(), VCard: m.GetVcard()}
		ctx = m.GetContextInfo()
	case msg.ContactsArrayMessage != nil:
		m := msg.ContactsArrayMessage
		out.Kind = "contact"
		if list := m.GetContacts(); len(list) > 0 {
			out.Contact = &Contact{DisplayName: list[0].GetDisplayName(), VCard: list[0].GetVcard()}
		}
		ctx = m.GetContextInfo()
	case msg.ReactionMessage != nil:
		m := msg.ReactionMessage
		out.Kind = "reaction"
		out.Reaction = &Reaction{TargetID: m.GetKey().GetID(), TargetSender: m.GetKey().GetParticipant(),
			TargetFromMe: m.GetKey().GetFromMe(), Emoji: m.GetText()}
	case msg.ProtocolMessage != nil:
		p := msg.ProtocolMessage
		switch p.GetType() {
		case waE2E.ProtocolMessage_REVOKE:
			out.Kind = "revoke"
			out.Revoke = &Target{ID: p.GetKey().GetID(), Sender: p.GetKey().GetParticipant(), FromMe: p.GetKey().GetFromMe()}
		case waE2E.ProtocolMessage_MESSAGE_EDIT:
			out.Kind = "edit"
			edited := p.GetEditedMessage()
			text := edited.GetConversation()
			if edited.GetExtendedTextMessage() != nil {
				text = edited.GetExtendedTextMessage().GetText()
			}
			if text == "" {
				// An edited caption: image, video, or document.
				text = captionOf(edited)
			}
			out.Edit = &Edit{TargetID: p.GetKey().GetID(), Text: text}
		default:
			// Key shares, sync notices, and the like: housekeeping, not a message to show.
			out.Kind = "skip"
		}
	case msg.PollCreationMessage != nil || msg.PollCreationMessageV2 != nil || msg.PollCreationMessageV3 != nil:
		out.Kind = "text"
		out.Text = "📊 " + pollName(msg)
	case housekeeping(msg):
		out.Kind = "skip"
	default:
		out.Kind = "unsupported"
	}
	if ctx != nil {
		if ctx.GetStanzaID() != "" {
			out.ReplyTo = &Quote{ID: ctx.GetStanzaID(), Sender: ctx.GetParticipant(), Text: quoteText(ctx.GetQuotedMessage())}
		}
		out.Mentions = append(out.Mentions, ctx.GetMentionedJID()...)
	}
}

func captionOf(msg *waE2E.Message) string {
	switch {
	case msg.GetImageMessage() != nil:
		return msg.GetImageMessage().GetCaption()
	case msg.GetVideoMessage() != nil:
		return msg.GetVideoMessage().GetCaption()
	case msg.GetDocumentMessage() != nil:
		return msg.GetDocumentMessage().GetCaption()
	}
	return ""
}

func pollName(msg *waE2E.Message) string {
	switch {
	case msg.PollCreationMessageV3 != nil:
		return msg.PollCreationMessageV3.GetName()
	case msg.PollCreationMessageV2 != nil:
		return msg.PollCreationMessageV2.GetName()
	default:
		return msg.PollCreationMessage.GetName()
	}
}

// quoteText is the line a reply shows for the message it answers.
func quoteText(msg *waE2E.Message) string {
	if msg == nil {
		return ""
	}
	var q Message
	fill(&q, msg)
	if q.Text != "" {
		return q.Text
	}
	switch q.Kind {
	case "image":
		return "📷 Photo"
	case "video", "gif":
		return "🎬 Video"
	case "voice", "audio":
		return "🎤 Voice message"
	case "document":
		if q.Media != nil && q.Media.FileName != "" {
			return "📄 " + q.Media.FileName
		}
		return "📄 Document"
	case "sticker":
		return "Sticker"
	case "location":
		return "📍 Location"
	case "contact":
		return "👤 Contact"
	}
	return ""
}

// convertGroup turns group info into a chat with its members.
func convertGroup(info *types.GroupInfo, me types.JID, nameOf func(types.JID) string) Chat {
	chat := Chat{
		ID:          info.JID.String(),
		Name:        info.Name,
		IsGroup:     true,
		IsCommunity: info.IsParent,
		LastAt:      millis(info.GroupCreated),
	}
	if !info.LinkedParentJID.IsEmpty() {
		chat.CommunityID = info.LinkedParentJID.String()
	}
	for _, p := range info.Participants {
		member := Participant{ID: p.JID.ToNonAD().String(), IsAdmin: p.IsAdmin || p.IsSuperAdmin, Name: p.DisplayName}
		if !p.PhoneNumber.IsEmpty() {
			member.Phone = p.PhoneNumber.ToNonAD().User
		} else if p.JID.Server == types.DefaultUserServer {
			member.Phone = p.JID.ToNonAD().User
		}
		member.IsMe = sameUser(p.JID, me) || sameUser(p.LID, me) || sameUser(p.PhoneNumber, me)
		if member.Name == "" && nameOf != nil {
			member.Name = nameOf(p.JID)
		}
		chat.Participants = append(chat.Participants, member)
	}
	if chat.Participants == nil {
		chat.Participants = []Participant{}
	}
	return chat
}

func sameUser(a, b types.JID) bool {
	return !a.IsEmpty() && !b.IsEmpty() && a.ToNonAD().User == b.ToNonAD().User && a.Server == b.Server
}

// keyOf builds the message key other operations name a message by.
func keyOf(chat types.JID, id string, fromMe bool, sender types.JID) *waCommon.MessageKey {
	key := &waCommon.MessageKey{RemoteJID: strPtr(chat.String()), FromMe: &fromMe, ID: strPtr(id)}
	if chat.Server == types.GroupServer && !sender.IsEmpty() {
		key.Participant = strPtr(sender.ToNonAD().String())
	}
	return key
}

func strPtr(s string) *string { return &s }

func isGroup(jid types.JID) bool { return jid.Server == types.GroupServer }

func kindFromMime(mime string, voice bool) string {
	switch {
	case voice:
		return "voice"
	case strings.HasPrefix(mime, "image/"):
		return "image"
	case strings.HasPrefix(mime, "video/"):
		return "video"
	case strings.HasPrefix(mime, "audio/"):
		return "audio"
	default:
		return "document"
	}
}
