// SPDX-License-Identifier: AGPL-3.0-or-later

package wa

import (
	"encoding/json"
	"testing"
	"time"

	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

func liveMessage(id string, msg *waE2E.Message) *events.Message {
	return &events.Message{
		Info: types.MessageInfo{
			MessageSource: types.MessageSource{
				Chat:     types.NewJID("15555550123", types.DefaultUserServer),
				Sender:   types.NewJID("15555550123", types.DefaultUserServer),
				IsFromMe: false,
			},
			ID:        id,
			PushName:  "Sam",
			Timestamp: time.UnixMilli(1759310000000),
		},
		Message: msg,
	}
}

func TestTextWithReplyFlattens(t *testing.T) {
	msg := &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{
		Text: proto.String("See you at 7"),
		ContextInfo: &waE2E.ContextInfo{
			StanzaID:      proto.String("ABC"),
			Participant:   proto.String("15555550100@s.whatsapp.net"),
			QuotedMessage: &waE2E.Message{Conversation: proto.String("What time?")},
		},
	}}
	out := convertMessage(liveMessage("M1", msg), nil, nil)
	if out.Kind != "text" || out.Text != "See you at 7" || out.SenderPhone != "15555550123" || out.PushName != "Sam" {
		t.Fatalf("unexpected text message: %+v", out)
	}
	if out.ReplyTo == nil || out.ReplyTo.ID != "ABC" || out.ReplyTo.Text != "What time?" {
		t.Fatalf("reply not kept: %+v", out.ReplyTo)
	}
	if out.Timestamp != 1759310000000 {
		t.Fatalf("timestamp not in milliseconds: %d", out.Timestamp)
	}
}

func TestMediaCarriesWhatDownloadNeeds(t *testing.T) {
	msg := &waE2E.Message{ImageMessage: &waE2E.ImageMessage{
		Mimetype: proto.String("image/jpeg"), Caption: proto.String("the map"), DirectPath: proto.String("/v/t62"),
		MediaKey: []byte{1, 2, 3}, FileSHA256: []byte{4}, FileEncSHA256: []byte{5}, FileLength: proto.Uint64(34567),
		Width: proto.Uint32(1200), Height: proto.Uint32(800),
	}}
	out := convertMessage(liveMessage("M2", msg), nil, nil)
	if out.Kind != "image" || out.Text != "the map" || out.Media == nil {
		t.Fatalf("unexpected image message: %+v", out)
	}
	if out.Media.Type != "WhatsApp Image Keys" || out.Media.MediaKey != "AQID" || out.Media.FileLength != 34567 || out.Media.Width != 1200 {
		t.Fatalf("media fields wrong: %+v", out.Media)
	}
	voice := &waE2E.Message{AudioMessage: &waE2E.AudioMessage{Mimetype: proto.String("audio/ogg; codecs=opus"), PTT: proto.Bool(true), Seconds: proto.Uint32(4)}}
	if got := convertMessage(liveMessage("M3", voice), nil, nil); got.Kind != "voice" || !got.Media.Voice || got.Media.Seconds != 4 {
		t.Fatalf("voice note not recognised: %+v", got)
	}
	gif := &waE2E.Message{VideoMessage: &waE2E.VideoMessage{Mimetype: proto.String("video/mp4"), GifPlayback: proto.Bool(true)}}
	if got := convertMessage(liveMessage("M4", gif), nil, nil); got.Kind != "gif" {
		t.Fatalf("gif not recognised: %+v", got)
	}
}

func TestReactionRevokeAndEditNameTheirTarget(t *testing.T) {
	key := &waCommon.MessageKey{RemoteJID: proto.String("15555550123@s.whatsapp.net"), FromMe: proto.Bool(true), ID: proto.String("T1")}
	reaction := convertMessage(liveMessage("R1", &waE2E.Message{ReactionMessage: &waE2E.ReactionMessage{Key: key, Text: proto.String("❤️")}}), nil, nil)
	if reaction.Kind != "reaction" || reaction.Reaction.TargetID != "T1" || !reaction.Reaction.TargetFromMe || reaction.Reaction.Emoji != "❤️" {
		t.Fatalf("reaction wrong: %+v", reaction.Reaction)
	}
	revoke := convertMessage(liveMessage("R2", &waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{
		Key: key, Type: waE2E.ProtocolMessage_REVOKE.Enum()}}), nil, nil)
	if revoke.Kind != "revoke" || revoke.Revoke.ID != "T1" {
		t.Fatalf("revoke wrong: %+v", revoke)
	}
	edit := convertMessage(liveMessage("R3", &waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{
		Key: key, Type: waE2E.ProtocolMessage_MESSAGE_EDIT.Enum(), EditedMessage: &waE2E.Message{Conversation: proto.String("fixed")}}}), nil, nil)
	if edit.Kind != "edit" || edit.Edit.TargetID != "T1" || edit.Edit.Text != "fixed" {
		t.Fatalf("edit wrong: %+v", edit)
	}
}

func TestGroupsBecomeChatsWithMembersAndCommunity(t *testing.T) {
	me := types.NewJID("15555550100", types.DefaultUserServer)
	info := &types.GroupInfo{
		JID:       types.NewJID("12036304", types.GroupServer),
		GroupName: types.GroupName{Name: "Hiking crew"},
		Participants: []types.GroupParticipant{
			{JID: me, IsAdmin: true},
			{JID: types.NewJID("15555550123", types.DefaultUserServer)},
		},
	}
	info.LinkedParentJID = types.NewJID("777", types.GroupServer)
	chat := convertGroup(info, me, func(types.JID) string { return "Someone" })
	if !chat.IsGroup || chat.Name != "Hiking crew" || chat.CommunityID != "777@g.us" || len(chat.Participants) != 2 {
		t.Fatalf("group wrong: %+v", chat)
	}
	if !chat.Participants[0].IsMe || !chat.Participants[0].IsAdmin || chat.Participants[1].Phone != "15555550123" {
		t.Fatalf("members wrong: %+v", chat.Participants)
	}
	data, err := json.Marshal(chat)
	if err != nil || len(data) == 0 {
		t.Fatalf("not encodable: %v", err)
	}
}

func TestHiddenAddressBecomesPhoneNumber(t *testing.T) {
	// A message from someone's hidden address names their number too: the chat and the
	// sender both become the number, so one person stays one chat (owner, Gate G3).
	evt := liveMessage("L1", &waE2E.Message{Conversation: proto.String("hi")})
	evt.Info.Chat = types.NewJID("987654321", types.HiddenUserServer)
	evt.Info.Sender = types.NewJID("987654321", types.HiddenUserServer)
	evt.Info.SenderAlt = types.NewJID("15555550123", types.DefaultUserServer)
	out := convertMessage(evt, nil, nil)
	if out.Chat != "15555550123@s.whatsapp.net" || out.Sender != "15555550123@s.whatsapp.net" {
		t.Fatalf("chat %q sender %q", out.Chat, out.Sender)
	}
	// Without the number in the message, the store is asked; unknown stays hidden.
	evt.Info.SenderAlt = types.EmptyJID
	known := func(j types.JID) types.JID { return types.NewJID("15555550123", types.DefaultUserServer) }
	if got := convertMessage(evt, nil, known).Chat; got != "15555550123@s.whatsapp.net" {
		t.Fatalf("store lookup not used: %q", got)
	}
	if got := convertMessage(evt, nil, nil).Chat; got != "987654321@lid" {
		t.Fatalf("unknown hidden address changed: %q", got)
	}
}

func TestHousekeepingIsSkipped(t *testing.T) {
	keyShare := &waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{
		Type: waE2E.ProtocolMessage_APP_STATE_SYNC_KEY_SHARE.Enum(),
	}}
	if got := convertMessage(liveMessage("K1", keyShare), nil, nil).Kind; got != "skip" {
		t.Fatalf("key share shown as %q", got)
	}
	envelope := &waE2E.Message{SenderKeyDistributionMessage: &waE2E.SenderKeyDistributionMessage{}}
	if got := convertMessage(liveMessage("K2", envelope), nil, nil).Kind; got != "skip" {
		t.Fatalf("bare envelope shown as %q", got)
	}
	withText := &waE2E.Message{
		SenderKeyDistributionMessage: &waE2E.SenderKeyDistributionMessage{},
		Conversation:                 proto.String("real text"),
	}
	if got := convertMessage(liveMessage("K3", withText), nil, nil); got.Kind != "text" || got.Text != "real text" {
		t.Fatalf("text beside an envelope lost: %+v", got)
	}
	if got := convertMessage(liveMessage("K4", &waE2E.Message{OrderMessage: &waE2E.OrderMessage{}}), nil, nil).Kind; got != "unsupported" {
		t.Fatalf("a real but unsupported message skipped: %q", got)
	}
}

func TestMediaCarriesItsMessage(t *testing.T) {
	voice := &waE2E.Message{AudioMessage: &waE2E.AudioMessage{PTT: proto.Bool(true), DirectPath: proto.String("/v/x")}}
	out := convertMessage(liveMessage("V1", voice), nil, nil)
	if out.Media.MessageID != "V1" || out.Media.Chat != out.Chat || out.Media.Sender != out.Sender || out.Media.FromMe {
		t.Fatalf("media without its message: %+v", out.Media)
	}
}
