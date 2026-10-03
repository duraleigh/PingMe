// SPDX-License-Identifier: AGPL-3.0-or-later

package sig

import (
	"encoding/base64"
	"fmt"
	"strings"

	"github.com/google/uuid"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/protobuf/backuppb"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/protobuf/signalpb"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"
)

// Chat is a one-to-one chat or a group as Kotlin sees it.
type Chat struct {
	ID       string   `json:"id"`
	IsGroup  bool     `json:"isGroup"`
	Name     string   `json:"name"`
	Members  []Member `json:"members"`
	Unread   int      `json:"unread"`
	Archived bool     `json:"archived"`
	Pinned   bool     `json:"pinned"`
	// Mute end as Unix milliseconds; a huge value means for good.
	MuteUntil int64 `json:"muteUntil,omitempty"`
	LastAt    int64 `json:"lastAt"`
	// How many messages the transferred history holds for this chat.
	Count int `json:"count"`
}

// Member is a person: the other side of a chat, a group member, or a contact.
type Member struct {
	ID      string `json:"id"` // account id (UUID)
	Phone   string `json:"phone,omitempty"`
	Name    string `json:"name,omitempty"`
	IsMe    bool   `json:"isMe,omitempty"`
	IsAdmin bool   `json:"isAdmin,omitempty"`
}

// Message is one live or archived message, or something about one (a reaction, an
// edit, a delete, typing).
type Message struct {
	ID          string `json:"id"` // "<sender id>:<timestamp>"
	Chat        string `json:"chat"`
	Sender      string `json:"sender"`
	SenderPhone string `json:"senderPhone,omitempty"`
	FromMe      bool   `json:"fromMe"`
	Timestamp   int64  `json:"timestamp"`
	// "text", "image", "video", "audio", "voice", "document", "sticker", "contact",
	// "reaction", "revoke", "edit", "typing", "unsupported", or "skip".
	Kind      string     `json:"kind"`
	Text      string     `json:"text,omitempty"`
	Media     *Media     `json:"media,omitempty"`
	Quote     *Quote     `json:"quote,omitempty"`
	Reaction  *Reaction  `json:"reaction,omitempty"`
	Revoke    *Target    `json:"revoke,omitempty"`
	Edit      *Edit      `json:"edit,omitempty"`
	Typing    *bool      `json:"typing,omitempty"`
	Status    string     `json:"status,omitempty"` // "sent", "delivered", "read" for your own
	Reactions []Reaction `json:"reactions,omitempty"`
	ExpiresIn int64      `json:"expiresIn,omitempty"` // disappearing message length, ms
	Read      bool       `json:"read,omitempty"`      // an archived incoming message you had read
}

// Media is a file on Signal's servers: the pointer Kotlin hands back to download it.
type Media struct {
	Pointer  string `json:"pointer"` // the AttachmentPointer as JSON
	Mime     string `json:"mime"`
	FileName string `json:"fileName,omitempty"`
	Size     int64  `json:"size"`
	Width    int64  `json:"width,omitempty"`
	Height   int64  `json:"height,omitempty"`
	Voice    bool   `json:"voice"`
	Gif      bool   `json:"gif"`
	Caption  string `json:"caption,omitempty"`
}

// Quote is the message a reply answers.
type Quote struct {
	ID     string `json:"id"` // "<author id>:<timestamp>"
	Sender string `json:"sender"`
	Text   string `json:"text"`
}

// Reaction is an emoji on a message; Remove takes it away.
type Reaction struct {
	TargetSender    string `json:"targetSender,omitempty"`
	TargetTimestamp int64  `json:"targetTimestamp,omitempty"`
	Sender          string `json:"sender,omitempty"`
	Emoji           string `json:"emoji"`
	Remove          bool   `json:"remove,omitempty"`
	Timestamp       int64  `json:"timestamp,omitempty"`
}

// Target names a message by its timestamp (its sender is the event's sender).
type Target struct {
	Timestamp int64 `json:"timestamp"`
}

// Edit replaces the text of an earlier message of the same sender.
type Edit struct {
	TargetTimestamp int64  `json:"targetTimestamp"`
	Text            string `json:"text"`
}

// Receipt is Signal's delivery or read receipt for your own messages, by timestamp.
type Receipt struct {
	Sender     string  `json:"sender"`
	Kind       string  `json:"kind"` // "delivered", "read", "viewed"
	Timestamps []int64 `json:"timestamps"`
}

// MessageID is how a message is named: its sender and its timestamp.
func MessageID(sender uuid.UUID, timestamp uint64) string {
	return fmt.Sprintf("%s:%d", sender, timestamp)
}

// ParseMessageID splits "<sender>:<timestamp>".
func ParseMessageID(id string) (uuid.UUID, uint64, error) {
	i := strings.LastIndexByte(id, ':')
	if i < 0 {
		return uuid.Nil, 0, fmt.Errorf("not a message id: %q", id)
	}
	sender, err := uuid.Parse(id[:i])
	if err != nil {
		return uuid.Nil, 0, fmt.Errorf("not a message id: %q: %w", id, err)
	}
	var ts uint64
	if _, err := fmt.Sscanf(id[i+1:], "%d", &ts); err != nil {
		return uuid.Nil, 0, fmt.Errorf("not a message id: %q", id)
	}
	return sender, ts, nil
}

// IsGroupID tells a group identifier (44 characters of base64) from an account id.
func IsGroupID(chat string) bool { return len(chat) == 44 }

// dataMessage flattens a DataMessage: text, one file, a quote, a reaction, a delete.
func dataMessage(chat string, sender uuid.UUID, fromMe bool, dm *signalpb.DataMessage) Message {
	out := Message{
		ID:        MessageID(sender, dm.GetTimestamp()),
		Chat:      chat,
		Sender:    sender.String(),
		FromMe:    fromMe,
		Timestamp: int64(dm.GetTimestamp()),
		Kind:      "text",
		Text:      dm.GetBody(),
		ExpiresIn: int64(dm.GetExpireTimer()) * 1000,
	}
	switch {
	case dm.Reaction != nil:
		r := dm.Reaction
		author, _ := parseUUID(r.GetTargetAuthorAci(), r.GetTargetAuthorAciBinary())
		out.Kind = "reaction"
		out.Reaction = &Reaction{
			TargetSender:    author.String(),
			TargetTimestamp: int64(r.GetTargetSentTimestamp()),
			Emoji:           r.GetEmoji(),
			Remove:          r.GetRemove(),
		}
		return out
	case dm.Delete != nil:
		out.Kind = "revoke"
		out.Revoke = &Target{Timestamp: int64(dm.Delete.GetTargetSentTimestamp())}
		return out
	case dm.GetFlags()&uint32(signalpb.DataMessage_EXPIRATION_TIMER_UPDATE) != 0,
		dm.GetGroupV2().GetGroupChange() != nil && dm.Body == nil && len(dm.Attachments) == 0,
		dm.GetFlags()&uint32(signalpb.DataMessage_PROFILE_KEY_UPDATE) != 0 && dm.Body == nil:
		// Timer changes, profile key updates, group changes: nothing a person reads.
		out.Kind = "skip"
		return out
	}
	if dm.Quote != nil {
		author, _ := parseUUID(dm.Quote.GetAuthorAci(), dm.Quote.GetAuthorAciBinary())
		out.Quote = &Quote{ID: MessageID(author, dm.Quote.GetId()), Sender: author.String(), Text: dm.Quote.GetText()}
	}
	switch {
	case dm.Sticker != nil:
		out.Kind = "sticker"
		if dm.Sticker.Data != nil {
			out.Media = mediaOf(dm.Sticker.Data)
			out.Media.Mime = firstNonEmpty(out.Media.Mime, "image/webp")
		}
		out.Text = dm.Sticker.GetEmoji()
	case len(dm.Attachments) > 0:
		// One file per message in PingMe; Signal's extra files follow as their own messages.
		out.Media = mediaOf(dm.Attachments[0])
		out.Kind = mediaKind(out.Media)
		if out.Media.Caption != "" && out.Text == "" {
			out.Text = out.Media.Caption
		}
	case len(dm.Contact) > 0:
		out.Kind = "contact"
		out.Text = contactName(dm.Contact[0])
	case dm.PollCreate != nil:
		out.Kind = "text"
		out.Text = "📊 " + dm.PollCreate.GetQuestion()
	case dm.Payment != nil, dm.GiftBadge != nil, dm.PollVote != nil, dm.PollTerminate != nil,
		dm.GroupCallUpdate != nil, dm.StoryContext != nil, dm.PinMessage != nil, dm.UnpinMessage != nil:
		out.Kind = "unsupported"
	case dm.Body == nil && len(dm.Preview) == 0:
		out.Kind = "skip"
	}
	return out
}

func mediaOf(a *signalpb.AttachmentPointer) *Media {
	raw, _ := protojson.Marshal(a)
	return &Media{
		Pointer:  string(raw),
		Mime:     a.GetContentType(),
		FileName: a.GetFileName(),
		Size:     int64(a.GetSize()),
		Width:    int64(a.GetWidth()),
		Height:   int64(a.GetHeight()),
		Voice:    a.GetFlags()&uint32(signalpb.AttachmentPointer_VOICE_MESSAGE) != 0,
		Gif:      a.GetFlags()&uint32(signalpb.AttachmentPointer_GIF) != 0,
		Caption:  a.GetCaption(),
	}
}

// pointerOf reads a Media's pointer back, for downloads.
func pointerOf(m Media) (*signalpb.AttachmentPointer, error) {
	var a signalpb.AttachmentPointer
	if err := protojson.Unmarshal([]byte(m.Pointer), &a); err != nil {
		return nil, fmt.Errorf("media pointer is not valid: %w", err)
	}
	return &a, nil
}

func mediaKind(m *Media) string {
	switch {
	case m.Voice:
		return "voice"
	case strings.HasPrefix(m.Mime, "image/"):
		return "image"
	case strings.HasPrefix(m.Mime, "video/"):
		return "video"
	case strings.HasPrefix(m.Mime, "audio/"):
		return "audio"
	default:
		return "document"
	}
}

func contactName(c *signalpb.DataMessage_Contact) string {
	n := c.GetName()
	if n == nil {
		return ""
	}
	return firstNonEmpty(n.GetNickname(), strings.TrimSpace(n.GetGivenName()+" "+n.GetFamilyName()))
}

func parseUUID(text string, raw []byte) (uuid.UUID, error) {
	if len(raw) == 16 {
		return uuid.UUID(raw), nil
	}
	return uuid.Parse(text)
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

// archivedMessage flattens one item of the transferred history. ownID is this account.
func archivedMessage(chat string, item *backuppb.ChatItem, author uuid.UUID, ownID uuid.UUID, authorOf func(uint64) uuid.UUID) *Message {
	dm, reactions, quotedAuthor := archiveToDataMessage(item)
	if dm == nil {
		return nil
	}
	dm.Timestamp = proto.Uint64(item.GetDateSent())
	if dm.Quote != nil {
		dm.Quote.AuthorAci = proto.String(authorOf(quotedAuthor).String())
	}
	out := dataMessage(chat, author, author == ownID, dm)
	if out.Kind == "skip" {
		return nil
	}
	out.ExpiresIn = int64(item.GetExpiresInMs())
	switch d := item.DirectionalDetails.(type) {
	case *backuppb.ChatItem_Incoming:
		out.Read = d.Incoming.GetRead()
	case *backuppb.ChatItem_Outgoing:
		out.Status = archivedStatus(d.Outgoing)
	}
	for _, r := range reactions {
		out.Reactions = append(out.Reactions, Reaction{
			Sender:    authorOf(r.GetAuthorId()).String(),
			Emoji:     r.GetEmoji(),
			Timestamp: int64(r.GetSentTimestamp()),
		})
	}
	return &out
}

// archivedStatus is the furthest any recipient got: read beats delivered beats sent.
func archivedStatus(o *backuppb.ChatItem_OutgoingMessageDetails) string {
	best := "sent"
	for _, s := range o.GetSendStatus() {
		switch s.GetDeliveryStatus().(type) {
		case *backuppb.SendStatus_Read_, *backuppb.SendStatus_Viewed_:
			return "read"
		case *backuppb.SendStatus_Delivered_:
			best = "delivered"
		}
	}
	return best
}

// archiveToDataMessage is the reference bridge's reading of an archived item as a live
// DataMessage (mautrix-signal msgconv.BackupToDataMessage), without its Matrix parts.
func archiveToDataMessage(ci *backuppb.ChatItem) (*signalpb.DataMessage, []*backuppb.Reaction, uint64) {
	var dm signalpb.DataMessage
	var reactions []*backuppb.Reaction
	var quotedAuthor uint64
	switch ti := ci.Item.(type) {
	case *backuppb.ChatItem_StandardMessage:
		reactions = ti.StandardMessage.Reactions
		if text := ti.StandardMessage.Text; text != nil {
			dm.Body = proto.String(text.GetBody())
		}
		for _, att := range ti.StandardMessage.Attachments {
			dm.Attachments = append(dm.Attachments, archiveAttachment(att.Pointer, att.GetFlag()))
		}
		if q := ti.StandardMessage.Quote; q != nil {
			dm.Quote = &signalpb.DataMessage_Quote{
				Id:   proto.Uint64(q.GetTargetSentTimestamp()),
				Text: proto.String(q.GetText().GetBody()),
			}
			quotedAuthor = q.GetAuthorId()
		}
	case *backuppb.ChatItem_ContactMessage:
		reactions = ti.ContactMessage.Reactions
		c := ti.ContactMessage.GetContact()
		dm.Contact = []*signalpb.DataMessage_Contact{{Name: &signalpb.DataMessage_Contact_Name{
			GivenName:  proto.String(c.GetName().GetGivenName()),
			FamilyName: proto.String(c.GetName().GetFamilyName()),
			Nickname:   proto.String(c.GetName().GetNickname()),
		}}}
	case *backuppb.ChatItem_StickerMessage:
		reactions = ti.StickerMessage.Reactions
		dm.Sticker = &signalpb.DataMessage_Sticker{
			Emoji: ti.StickerMessage.Sticker.Emoji,
			Data:  archiveAttachment(ti.StickerMessage.Sticker.Data, 0),
		}
	case *backuppb.ChatItem_Poll:
		dm.PollCreate = &signalpb.DataMessage_PollCreate{Question: proto.String(ti.Poll.GetQuestion())}
	case *backuppb.ChatItem_ViewOnceMessage:
		reactions = ti.ViewOnceMessage.Reactions
		if ti.ViewOnceMessage.Attachment == nil {
			return nil, reactions, 0
		}
		dm.IsViewOnce = proto.Bool(true)
		dm.Attachments = []*signalpb.AttachmentPointer{archiveAttachment(ti.ViewOnceMessage.Attachment.Pointer, ti.ViewOnceMessage.Attachment.GetFlag())}
	default:
		// Remote deletes, updates, payments, gift badges: nothing to show.
		return nil, nil, 0
	}
	return &dm, reactions, quotedAuthor
}

func archiveAttachment(fp *backuppb.FilePointer, flag backuppb.MessageAttachment_Flag) *signalpb.AttachmentPointer {
	a := &signalpb.AttachmentPointer{
		ContentType:    fp.ContentType,
		IncrementalMac: fp.IncrementalMac,
		FileName:       fp.FileName,
		Width:          fp.Width,
		Height:         fp.Height,
		BlurHash:       fp.BlurHash,
	}
	switch flag {
	case backuppb.MessageAttachment_VOICE_MESSAGE:
		a.Flags = proto.Uint32(uint32(signalpb.AttachmentPointer_VOICE_MESSAGE))
	case backuppb.MessageAttachment_GIF:
		a.Flags = proto.Uint32(uint32(signalpb.AttachmentPointer_GIF))
	case backuppb.MessageAttachment_BORDERLESS:
		a.Flags = proto.Uint32(uint32(signalpb.AttachmentPointer_BORDERLESS))
	}
	if li := fp.LocatorInfo; li != nil {
		if li.TransitCdnKey != nil {
			a.AttachmentIdentifier = &signalpb.AttachmentPointer_CdnKey{CdnKey: li.GetTransitCdnKey()}
			a.Size = proto.Uint32(li.GetSize())
			a.Digest = li.GetEncryptedDigest()
			a.CdnNumber = li.TransitCdnNumber
		}
		a.Key = li.Key
	}
	return a
}

// groupIDString is a group identifier as the 44-character text Signal uses.
func groupIDString(raw []byte) string { return base64.StdEncoding.EncodeToString(raw) }
