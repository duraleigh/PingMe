// SPDX-License-Identifier: AGPL-3.0-or-later

package gm

import (
	"encoding/base64"
	"strings"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// The JSON shapes Kotlin reads. Field names are the JSON names; timestamps are Unix
// microseconds, as Google Messages reports them, and binary keys are base64.

// Conversation is one chat as the phone sees it.
type Conversation struct {
	ID         string `json:"id"`
	Name       string `json:"name,omitempty"`
	IsGroup    bool   `json:"isGroup"`
	Type       string `json:"type"`   // "RCS", "SMS", or "UNKNOWN"
	Status     string `json:"status"` // ACTIVE, ARCHIVED, DELETED, KEEP_ARCHIVED, SPAM_FOLDER, BLOCKED_FOLDER, ...
	SendMode   string `json:"sendMode"`
	Unread     bool   `json:"unread"`
	ReadOnly   bool   `json:"readOnly"`
	Pinned     bool   `json:"pinned"`
	LastAt     int64  `json:"lastMessageAt"`
	LatestID   string `json:"latestMessageId,omitempty"`
	OutgoingID string `json:"outgoingId,omitempty"` // the SIM participant messages go out as
	AvatarURL  string `json:"groupAvatarUrl,omitempty"`
	// What the inbox shows as the last line, when the phone sent one.
	Preview       *Preview      `json:"preview,omitempty"`
	Participants  []Participant `json:"participants"`
	OtherIDs      []string      `json:"otherParticipantIds"`
	OutgoingIsRCS bool          `json:"outgoingIsRcs"` // type RCS and auto send mode: new messages go as RCS
}

// Preview is a conversation's last line.
type Preview struct {
	Text   string `json:"text"`
	FromMe bool   `json:"fromMe"`
	Name   string `json:"displayName,omitempty"`
}

// Participant is a person in a conversation, including the user's own SIM entry (IsMe).
type Participant struct {
	ID              string `json:"id"`
	Number          string `json:"number,omitempty"`
	FormattedNumber string `json:"formattedNumber,omitempty"`
	FirstName       string `json:"firstName,omitempty"`
	FullName        string `json:"fullName,omitempty"`
	IsMe            bool   `json:"isMe"`
	IsVisible       bool   `json:"isVisible"`
	ContactID       string `json:"contactId,omitempty"`
	AvatarHexColor  string `json:"avatarHexColor,omitempty"`
}

// Message is one message, with its text joined from the text parts and its media listed.
type Message struct {
	ID             string `json:"id"`
	ConversationID string `json:"conversationId"`
	ParticipantID  string `json:"participantId,omitempty"`
	Timestamp      int64  `json:"timestamp"`
	Status         int32  `json:"status"`
	StatusName     string `json:"statusName"`
	StatusText     string `json:"statusText,omitempty"`
	// "outgoing", "incoming", "tombstone", "deleted", or "unknown" (from the status range).
	Direction string `json:"direction"`
	// Sent from this phone: the status range says so, or the sender is one of its SIMs.
	FromMe bool `json:"fromMe"`
	// Status groups the Kotlin side shows without knowing the codes.
	Sent      bool `json:"sent"`      // the phone sent it (outgoing complete, delivered, or read)
	Delivered bool `json:"delivered"` // the other side received it
	Read      bool `json:"read"`      // the other side read it
	Failed    bool `json:"failed"`    // the phone gave up sending it
	// Hidden tombstones such as "switched to RCS" lines; true means do not show.
	Hide bool `json:"hide"`
	// Message.type from the phone: 1 SMS, 2 MMS, 3 undownloaded MMS; anything else is
	// taken as the conversation's own transport (RCS).
	Type            int64        `json:"type"`
	Transport       string       `json:"transport"` // "SMS", "MMS", or "RCS"
	TmpID           string       `json:"tmpId,omitempty"`
	Subject         string       `json:"subject,omitempty"`
	Text            string       `json:"text,omitempty"`
	Media           []Media      `json:"media"`
	Reactions       []Reaction   `json:"reactions"`
	ReplyToID       string       `json:"replyToId,omitempty"`
	PendingDownload string       `json:"pendingDownload,omitempty"` // "Downloading message..." and the like
	FailReason      string       `json:"failReason,omitempty"`
	Sender          *Participant `json:"sender,omitempty"`
}

// Media is one attachment part. Pending means the phone has not uploaded it yet.
type Media struct {
	PartID           string `json:"partId"`
	MediaID          string `json:"mediaId,omitempty"`
	ThumbnailMediaID string `json:"thumbnailMediaId,omitempty"`
	Name             string `json:"name,omitempty"`
	Mime             string `json:"mime"`
	Format           int32  `json:"format"`
	Size             int64  `json:"size"`
	Width            int64  `json:"width,omitempty"`
	Height           int64  `json:"height,omitempty"`
	Key              string `json:"key,omitempty"`
	ThumbnailKey     string `json:"thumbnailKey,omitempty"`
	Pending          bool   `json:"pending"`
}

// Reaction is one emoji and everyone who put it on the message.
type Reaction struct {
	Emoji          string   `json:"emoji"`
	ParticipantIDs []string `json:"participantIds"`
}

// Cursor pages through a list; it is passed back as it came.
type Cursor struct {
	LastItemID        string `json:"lastItemId"`
	LastItemTimestamp int64  `json:"lastItemTimestamp"`
}

// ConversationPage is what ListConversations returns.
type ConversationPage struct {
	Conversations []Conversation `json:"conversations"`
	Cursor        *Cursor        `json:"cursor,omitempty"`
}

// MessagePage is what FetchMessages returns, newest first.
type MessagePage struct {
	Messages []Message `json:"messages"`
	Cursor   *Cursor   `json:"cursor,omitempty"`
	Total    int64     `json:"total"`
}

// SIM is one SIM card the phone can send from.
type SIM struct {
	ParticipantID string `json:"participantId"`
	SIMNumber     int32  `json:"simNumber"`
	CarrierName   string `json:"carrierName,omitempty"`
	ColorHex      string `json:"colorHex,omitempty"`
	PhoneNumber   string `json:"phoneNumber,omitempty"`
	RCSEnabled    bool   `json:"rcsEnabled"`
}

// Settings is what the phone reports about itself.
type Settings struct {
	RCSEnabled       bool  `json:"rcsEnabled"`
	ReadReceipts     bool  `json:"readReceipts"`
	TypingIndicators bool  `json:"typingIndicators"`
	IsDefaultSMSApp  *bool `json:"isDefaultSmsApp,omitempty"`
	SIMs             []SIM `json:"sims"`
}

func convertConversation(conv *gmproto.Conversation) Conversation {
	out := Conversation{
		ID:           conv.GetConversationID(),
		Name:         conv.GetName(),
		IsGroup:      conv.GetIsGroupChat(),
		Type:         conversationType(conv.GetType()),
		Status:       conv.GetStatus().String(),
		SendMode:     conv.GetSendMode().String(),
		Unread:       conv.GetUnread(),
		ReadOnly:     conv.GetReadOnly(),
		Pinned:       conv.GetPinned(),
		LastAt:       conv.GetLastMessageTimestamp(),
		LatestID:     conv.GetLatestMessageID(),
		OutgoingID:   conv.GetDefaultOutgoingID(),
		AvatarURL:    conv.GetGroupAvatarURL(),
		Participants: make([]Participant, 0, len(conv.GetParticipants())),
		OtherIDs:     append([]string{}, conv.GetOtherParticipants()...),
		OutgoingIsRCS: conv.GetType() == gmproto.ConversationType_RCS &&
			conv.GetSendMode() == gmproto.ConversationSendMode_SEND_MODE_AUTO,
	}
	if lm := conv.GetLatestMessage(); lm != nil {
		out.Preview = &Preview{
			Text:   lm.GetDisplayContent(),
			FromMe: lm.GetFromMe() != 0,
			Name:   lm.GetDisplayName(),
		}
	}
	for _, p := range conv.GetParticipants() {
		out.Participants = append(out.Participants, convertParticipant(p))
	}
	return out
}

func conversationType(t gmproto.ConversationType) string {
	switch t {
	case gmproto.ConversationType_RCS:
		return "RCS"
	case gmproto.ConversationType_SMS:
		return "SMS"
	default:
		return "UNKNOWN"
	}
}

func convertParticipant(p *gmproto.Participant) Participant {
	return Participant{
		ID:              p.GetID().GetParticipantID(),
		Number:          p.GetID().GetNumber(),
		FormattedNumber: p.GetFormattedNumber(),
		FirstName:       p.GetFirstName(),
		FullName:        p.GetFullName(),
		IsMe:            p.GetIsMe(),
		IsVisible:       p.GetIsVisible(),
		ContactID:       p.GetContactID(),
		AvatarHexColor:  p.GetAvatarHexColor(),
	}
}

// convertMessage flattens a message. isDM says whether its conversation is one-to-one,
// which decides which protocol-switch tombstones are hidden (as the reference bridge
// does); convIsRCS gives the transport for parts the phone does not label.
func convertMessage(msg *gmproto.Message, isDM, convIsRCS bool) Message {
	status := msg.GetMessageStatus().GetStatus()
	out := Message{
		ID:              msg.GetMessageID(),
		ConversationID:  msg.GetConversationID(),
		ParticipantID:   msg.GetParticipantID(),
		Timestamp:       msg.GetTimestamp(),
		Status:          int32(status),
		StatusName:      status.String(),
		StatusText:      msg.GetMessageStatus().GetStatusText(),
		Direction:       direction(status),
		Sent:            isSentStatus(status),
		Delivered:       status == gmproto.MessageStatusType_OUTGOING_DELIVERED || status == gmproto.MessageStatusType_OUTGOING_DISPLAYED,
		Read:            status == gmproto.MessageStatusType_OUTGOING_DISPLAYED,
		FailReason:      failMessage(status),
		Hide:            shouldHide(status, isDM),
		Type:            msg.GetType(),
		Transport:       transport(msg.GetType(), convIsRCS),
		TmpID:           msg.GetTmpID(),
		Subject:         msg.GetSubject(),
		Media:           []Media{},
		Reactions:       []Reaction{},
		ReplyToID:       msg.GetReplyMessage().GetMessageID(),
		PendingDownload: downloadPendingStatusMessage(status),
	}
	out.Failed = out.FailReason != ""
	var text []string
	for _, part := range msg.GetMessageInfo() {
		switch data := part.GetData().(type) {
		case *gmproto.MessageInfo_MessageContent:
			text = append(text, data.MessageContent.GetContent())
		case *gmproto.MessageInfo_MediaContent:
			out.Media = append(out.Media, convertMedia(part.GetActionMessageID(), data.MediaContent))
		}
	}
	out.Text = strings.Join(text, "\n")
	for _, r := range msg.GetReactions() {
		emoji := reactionEmoji(r.GetData())
		if emoji == "" {
			continue
		}
		out.Reactions = append(out.Reactions, Reaction{
			Emoji:          emoji,
			ParticipantIDs: append([]string{}, r.GetParticipantIDs()...),
		})
	}
	if sp := msg.GetSenderParticipant(); sp != nil && sp.GetID().GetParticipantID() != "" {
		p := convertParticipant(sp)
		out.Sender = &p
	}
	return out
}

func convertMedia(partID string, mc *gmproto.MediaContent) Media {
	m := Media{
		PartID:           partID,
		MediaID:          mc.GetMediaID(),
		ThumbnailMediaID: mc.GetThumbnailMediaID(),
		Name:             mc.GetMediaName(),
		Mime:             mimeFor(mc),
		Format:           int32(mc.GetFormat()),
		Size:             mc.GetSize(),
		Width:            mc.GetDimensions().GetWidth(),
		Height:           mc.GetDimensions().GetHeight(),
		Pending:          mc.GetMediaID() == "" && mc.GetThumbnailMediaID() == "",
	}
	if k := mc.GetDecryptionKey(); len(k) > 0 {
		m.Key = base64.StdEncoding.EncodeToString(k)
	}
	if k := mc.GetThumbnailDecryptionKey(); len(k) > 0 {
		m.ThumbnailKey = base64.StdEncoding.EncodeToString(k)
	}
	return m
}

// mimeFor prefers the format table libgm keeps, then the phone's own MIME field, then
// the broad class of the format, the same order the reference bridge uses.
func mimeFor(mc *gmproto.MediaContent) string {
	if mt, ok := libgm.FormatToMediaType[mc.GetFormat()]; ok && mt.Format != "" {
		return mt.Format
	}
	if mc.GetMimeType() != "" {
		return mc.GetMimeType()
	}
	switch mc.GetFormat() {
	case gmproto.MediaFormats_IMAGE_UNSPECIFIED:
		return "image/*"
	case gmproto.MediaFormats_VIDEO_UNSPECIFIED:
		return "video/*"
	case gmproto.MediaFormats_AUDIO_UNSPECIFIED:
		return "audio/*"
	default:
		return "application/octet-stream"
	}
}

func reactionEmoji(data *gmproto.ReactionData) string {
	switch data.GetType() {
	case gmproto.EmojiType_EMOTIFY:
		// A phone-made sticker reaction; there is no emoji to show.
		return ""
	case gmproto.EmojiType_CUSTOM:
		return data.GetUnicode()
	default:
		return data.GetType().Unicode()
	}
}

// Statuses 1 to 99 are outgoing, 100 to 199 incoming, 200 to 299 tombstones, 300 deleted.
func direction(status gmproto.MessageStatusType) string {
	switch {
	case status == gmproto.MessageStatusType_MESSAGE_DELETED:
		return "deleted"
	case status >= 1 && status < 100:
		return "outgoing"
	case status >= 100 && status < 200:
		return "incoming"
	case status >= 200 && status < 300:
		return "tombstone"
	default:
		return "unknown"
	}
}

func transport(msgType int64, convIsRCS bool) string {
	switch msgType {
	case 1:
		return "SMS"
	case 2, 3:
		return "MMS"
	default:
		if convIsRCS {
			return "RCS"
		}
		return "SMS"
	}
}

func isSentStatus(status gmproto.MessageStatusType) bool {
	switch status {
	case gmproto.MessageStatusType_OUTGOING_COMPLETE,
		gmproto.MessageStatusType_OUTGOING_DELIVERED,
		gmproto.MessageStatusType_OUTGOING_DISPLAYED:
		return true
	default:
		return false
	}
}

// failMessage is the reference bridge's wording for each failed outgoing status.
func failMessage(status gmproto.MessageStatusType) string {
	switch status {
	case gmproto.MessageStatusType_OUTGOING_FAILED_GENERIC:
		return "Sending failed"
	case gmproto.MessageStatusType_OUTGOING_FAILED_EMERGENCY_NUMBER:
		return "Sending to emergency numbers is not allowed"
	case gmproto.MessageStatusType_OUTGOING_CANCELED:
		return "Sending was cancelled"
	case gmproto.MessageStatusType_OUTGOING_FAILED_TOO_LARGE:
		return "The message is too large"
	case gmproto.MessageStatusType_OUTGOING_FAILED_RECIPIENT_LOST_RCS:
		return "The recipient lost RCS"
	case gmproto.MessageStatusType_OUTGOING_FAILED_NO_RETRY_NO_FALLBACK:
		return "Sending failed, and there is no fallback"
	case gmproto.MessageStatusType_OUTGOING_FAILED_RECIPIENT_DID_NOT_DECRYPT,
		gmproto.MessageStatusType_OUTGOING_FAILED_RECIPIENT_DID_NOT_DECRYPT_NO_MORE_RETRY:
		return "The recipient could not decrypt the message"
	case gmproto.MessageStatusType_OUTGOING_FAILED_RECIPIENT_LOST_ENCRYPTION:
		return "The recipient lost encryption"
	case gmproto.MessageStatusType_OUTGOING_FAILED_RECIPIENT_NEGATIVE_DELIVERY:
		return "The recipient's phone refused the message"
	case gmproto.MessageStatusType_MESSAGE_STATUS_OUTGOING_FAILED_EMERGENCY_PROTOCOL_DETERMINATION_MESSAGE:
		return "Sending failed (emergency protocol)"
	case gmproto.MessageStatusType_OUTGOING_RESTRICTED:
		return "Sending is restricted"
	case gmproto.MessageStatusType_OUTGOING_FAILED_TO_ENCRYPT:
		return "The message could not be encrypted"
	default:
		return ""
	}
}

// shouldHide marks tombstones the reference bridge does not show: protocol switches in
// one-to-one chats, and group-creation and theme lines everywhere.
func shouldHide(status gmproto.MessageStatusType, isDM bool) bool {
	switch status {
	case gmproto.MessageStatusType_TOMBSTONE_PROTOCOL_SWITCH_TO_TEXT,
		gmproto.MessageStatusType_TOMBSTONE_PROTOCOL_SWITCH_TO_RCS,
		gmproto.MessageStatusType_TOMBSTONE_PROTOCOL_SWITCH_TO_ENCRYPTED_RCS,
		gmproto.MessageStatusType_TOMBSTONE_PROTOCOL_SWITCH_TO_ENCRYPTED_RCS_INFO,
		gmproto.MessageStatusType_TOMBSTONE_ONE_ON_ONE_SMS_CREATED,
		gmproto.MessageStatusType_TOMBSTONE_ONE_ON_ONE_RCS_CREATED,
		gmproto.MessageStatusType_TOMBSTONE_ENCRYPTED_ONE_ON_ONE_RCS_CREATED,
		gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_TEXT_TO_E2EE,
		gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_TEXT,
		gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_RCS_TO_E2EE,
		gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_RCS:
		return isDM
	case gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_ENCRYPTED_GROUP_CREATED,
		gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_GROUP_PROTOCOL_SWITCH_E2EE_TO_RCS,
		gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_GROUP_PROTOCOL_SWITCH_RCS_TO_E2EE,
		gmproto.MessageStatusType_TOMBSTONE_RCS_GROUP_CREATED,
		gmproto.MessageStatusType_TOMBSTONE_MMS_GROUP_CREATED,
		gmproto.MessageStatusType_TOMBSTONE_SMS_BROADCAST_CREATED,
		gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_PARTICIPANT_THEME_CHANGE,
		gmproto.MessageStatusType_TOMBSTONE_SHOW_LINK_PREVIEWS,
		gmproto.MessageStatusType_MESSAGE_STATUS_TOMBSTONE_ACTIVE_SELF_IDENTITY_CHANGED:
		return true
	default:
		return false
	}
}

func downloadPendingStatusMessage(status gmproto.MessageStatusType) string {
	switch status {
	case gmproto.MessageStatusType_INCOMING_YET_TO_MANUAL_DOWNLOAD:
		return "Attachment not downloaded (auto-download is off; download it in Google Messages)"
	case gmproto.MessageStatusType_INCOMING_MANUAL_DOWNLOADING,
		gmproto.MessageStatusType_INCOMING_AUTO_DOWNLOADING,
		gmproto.MessageStatusType_INCOMING_RETRYING_MANUAL_DOWNLOAD,
		gmproto.MessageStatusType_INCOMING_RETRYING_AUTO_DOWNLOAD:
		return "Downloading message..."
	case gmproto.MessageStatusType_INCOMING_DOWNLOAD_FAILED:
		return "Message download failed"
	case gmproto.MessageStatusType_INCOMING_DOWNLOAD_FAILED_TOO_LARGE:
		return "Message download failed (too large)"
	case gmproto.MessageStatusType_INCOMING_DOWNLOAD_FAILED_SIM_HAS_NO_DATA:
		return "Message download failed (no mobile data)"
	case gmproto.MessageStatusType_INCOMING_DOWNLOAD_CANCELED:
		return "Message download cancelled"
	default:
		return ""
	}
}

func convertCursor(c *gmproto.Cursor) *Cursor {
	if c == nil {
		return nil
	}
	return &Cursor{LastItemID: c.GetLastItemID(), LastItemTimestamp: c.GetLastItemTimestamp()}
}

func protoCursor(c *Cursor) *gmproto.Cursor {
	if c == nil {
		return nil
	}
	return &gmproto.Cursor{LastItemID: c.LastItemID, LastItemTimestamp: c.LastItemTimestamp}
}

func convertSettings(s *gmproto.Settings) Settings {
	rcs := s.GetRCSSettings()
	out := Settings{
		RCSEnabled:       rcs.GetIsEnabled(),
		ReadReceipts:     rcs.GetSendReadReceipts(),
		TypingIndicators: rcs.GetShowTypingIndicators(),
		SIMs:             make([]SIM, 0, len(s.GetSIMCards())),
	}
	if rcs != nil && rcs.IsDefaultSMSApp != nil {
		v := rcs.GetIsDefaultSMSApp()
		out.IsDefaultSMSApp = &v
	}
	for _, sim := range s.GetSIMCards() {
		out.SIMs = append(out.SIMs, SIM{
			ParticipantID: sim.GetSIMParticipant().GetID(),
			SIMNumber:     sim.GetSIMData().GetSIMPayload().GetSIMNumber(),
			CarrierName:   sim.GetSIMData().GetCarrierName(),
			ColorHex:      sim.GetSIMData().GetColorHex(),
			PhoneNumber:   sim.GetSIMData().GetFormattedPhoneNumber(),
			RCSEnabled:    sim.GetRCSChats().GetEnabled(),
		})
	}
	return out
}
