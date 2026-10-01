// SPDX-License-Identifier: AGPL-3.0-or-later

package gm

import (
	"encoding/json"
	"flag"
	"os"
	"path/filepath"
	"testing"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"go.mau.fi/util/ptr"
)

// -update rewrites testdata/session.json, the recorded session the Kotlin contract
// tests replay (BUILD_PLAN.md P3.3). Run `go test ./gm -update` after changing the DTOs.
var update = flag.Bool("update", false, "rewrite the golden session fixture")

func TestConvertConversationKeepsWhatKotlinNeeds(t *testing.T) {
	conv := convertConversation(sampleConversation())
	if conv.ID != "12" || conv.Type != "RCS" || conv.Status != "ACTIVE" || !conv.OutgoingIsRCS {
		t.Fatalf("unexpected conversation: %+v", conv)
	}
	if len(conv.Participants) != 2 || !conv.Participants[0].IsMe || conv.Participants[1].Number != "+15555550123" {
		t.Fatalf("participants wrong: %+v", conv.Participants)
	}
	if conv.Preview == nil || conv.Preview.Text != "See you at 7" || conv.Preview.FromMe {
		t.Fatalf("preview wrong: %+v", conv.Preview)
	}
}

func TestConvertMessageJoinsTextAndListsMedia(t *testing.T) {
	msg := convertMessage(sampleIncoming(), true, true)
	if msg.Text != "See you at 7\nBring the map" {
		t.Fatalf("text = %q", msg.Text)
	}
	if msg.Direction != "incoming" || msg.Transport != "RCS" || msg.Hide {
		t.Fatalf("direction/transport wrong: %+v", msg)
	}
	if len(msg.Media) != 1 || msg.Media[0].Mime != "image/jpeg" || msg.Media[0].Key == "" || msg.Media[0].Pending {
		t.Fatalf("media wrong: %+v", msg.Media)
	}
	if len(msg.Reactions) != 1 || msg.Reactions[0].Emoji != "❤️" || msg.Reactions[0].ParticipantIDs[0] != "3" {
		t.Fatalf("reactions wrong: %+v", msg.Reactions)
	}
	if msg.Sender == nil || msg.Sender.FullName != "Sam Ortiz" {
		t.Fatalf("sender wrong: %+v", msg.Sender)
	}
}

func TestOutgoingStatusesMapToSentDeliveredRead(t *testing.T) {
	cases := map[gmproto.MessageStatusType][3]bool{
		gmproto.MessageStatusType_OUTGOING_SENDING:   {false, false, false},
		gmproto.MessageStatusType_OUTGOING_COMPLETE:  {true, false, false},
		gmproto.MessageStatusType_OUTGOING_DELIVERED: {true, true, false},
		gmproto.MessageStatusType_OUTGOING_DISPLAYED: {true, true, true},
	}
	for status, want := range cases {
		m := sampleOutgoing(status)
		got := convertMessage(m, true, true)
		if got.Sent != want[0] || got.Delivered != want[1] || got.Read != want[2] || got.Failed {
			t.Errorf("%s: sent=%v delivered=%v read=%v failed=%v", status, got.Sent, got.Delivered, got.Read, got.Failed)
		}
	}
	failed := convertMessage(sampleOutgoing(gmproto.MessageStatusType_OUTGOING_FAILED_GENERIC), true, true)
	if !failed.Failed || failed.FailReason == "" {
		t.Fatalf("failed status not reported: %+v", failed)
	}
}

func TestSmsAndMmsTransportComeFromTheMessageType(t *testing.T) {
	m := sampleOutgoing(gmproto.MessageStatusType_OUTGOING_COMPLETE)
	m.Type = 1
	if got := convertMessage(m, true, true).Transport; got != "SMS" {
		t.Fatalf("type 1 should be SMS, got %s", got)
	}
	m.Type = 2
	if got := convertMessage(m, true, true).Transport; got != "MMS" {
		t.Fatalf("type 2 should be MMS, got %s", got)
	}
	m.Type = 4
	if got := convertMessage(m, true, false).Transport; got != "SMS" {
		t.Fatalf("an unlabelled part in an SMS chat should be SMS, got %s", got)
	}
}

func TestProtocolSwitchTombstonesHideOnlyInDirectChats(t *testing.T) {
	m := sampleOutgoing(gmproto.MessageStatusType_TOMBSTONE_PROTOCOL_SWITCH_TO_RCS)
	if !convertMessage(m, true, true).Hide {
		t.Fatal("should hide in a direct chat")
	}
	if convertMessage(m, false, true).Hide {
		t.Fatal("should show in a group")
	}
	if convertMessage(m, false, true).Direction != "tombstone" {
		t.Fatal("tombstone direction")
	}
}

func TestDeletedAndPendingDownloads(t *testing.T) {
	deleted := convertMessage(sampleOutgoing(gmproto.MessageStatusType_MESSAGE_DELETED), true, true)
	if deleted.Direction != "deleted" {
		t.Fatalf("deleted direction = %s", deleted.Direction)
	}
	pending := sampleIncoming()
	pending.MessageStatus.Status = gmproto.MessageStatusType_INCOMING_AUTO_DOWNLOADING
	got := convertMessage(pending, true, true)
	if got.PendingDownload == "" {
		t.Fatal("pending download text missing")
	}
}

func TestSessionAddsFromMeFromItsSimIds(t *testing.T) {
	s := &Session{convs: map[string]convInfo{}, sims: map[string]*gmproto.SIMCard{}, selfIDs: map[string]struct{}{}}
	s.remember(sampleConversation())
	tombstone := sampleOutgoing(gmproto.MessageStatusType_TOMBSTONE_PARTICIPANT_JOINED)
	tombstone.ParticipantID = "2" // the IsMe participant of the sample conversation
	if !s.convert(tombstone, s.info("12")).FromMe {
		t.Fatal("a tombstone from our own participant should be fromMe")
	}
	in := s.convert(sampleIncoming(), s.info("12"))
	if in.FromMe {
		t.Fatal("incoming statuses are never fromMe")
	}
	out := s.convert(sampleOutgoing(gmproto.MessageStatusType_OUTGOING_COMPLETE), s.info("12"))
	if !out.FromMe {
		t.Fatal("outgoing statuses are always fromMe")
	}
}

func TestSettingsListSims(t *testing.T) {
	got := convertSettings(sampleSettings())
	if !got.RCSEnabled || len(got.SIMs) != 1 || got.SIMs[0].ParticipantID != "2" || got.SIMs[0].SIMNumber != 1 {
		t.Fatalf("settings wrong: %+v", got)
	}
	if got.IsDefaultSMSApp == nil || !*got.IsDefaultSMSApp {
		t.Fatalf("default SMS app flag lost: %+v", got)
	}
}

// Fixture is the recorded session the Kotlin side replays: the shapes Go produces,
// written as Go produces them.
type Fixture struct {
	Conversations ConversationPage      `json:"conversations"`
	Messages      map[string]MessagePage `json:"messages"`
	Settings      Settings               `json:"settings"`
	Events        []json.RawMessage      `json:"events"`
}

func TestGoldenSessionFixture(t *testing.T) {
	s := &Session{convs: map[string]convInfo{}, sims: map[string]*gmproto.SIMCard{}, selfIDs: map[string]struct{}{}}
	s.sink = nil
	s.handleSettings(sampleSettings())
	conv := sampleConversation()
	group := sampleGroup()
	s.remember(conv)
	s.remember(group)
	fixture := Fixture{
		Conversations: ConversationPage{Conversations: []Conversation{convertConversation(conv), convertConversation(group)}},
		Messages: map[string]MessagePage{
			"12": {
				Messages: []Message{
					s.convert(sampleIncoming(), s.info("12")),
					s.convert(sampleOutgoing(gmproto.MessageStatusType_OUTGOING_DISPLAYED), s.info("12")),
					s.convert(sampleOlder(), s.info("12")),
				},
				Cursor: &Cursor{LastItemID: "1001", LastItemTimestamp: 1_759_300_000_000},
				Total:  3,
			},
			"34": {Messages: []Message{s.convert(sampleGroupMessage(), s.info("34"))}, Total: 1},
		},
		Settings: convertSettings(sampleSettings()),
	}
	events := []map[string]any{
		{"type": "settings", "settings": fixture.Settings},
		{"type": "ready", "resync": false},
		{"type": "message", "message": s.convert(sampleIncoming(), s.info("12")), "isOld": false},
		{"type": "typing", "conversationId": "12", "number": "+15555550123", "typing": true},
		{"type": "conversation", "conversation": convertConversation(conv)},
	}
	for _, e := range events {
		data, err := json.Marshal(e)
		if err != nil {
			t.Fatal(err)
		}
		fixture.Events = append(fixture.Events, data)
	}
	data, err := json.MarshalIndent(fixture, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	data = append(data, '\n')
	path := filepath.Join("testdata", "session.json")
	if *update {
		if err := os.MkdirAll("testdata", 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, data, 0o644); err != nil {
			t.Fatal(err)
		}
		return
	}
	want, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read golden fixture (run with -update to create it): %v", err)
	}
	if string(want) != string(data) {
		t.Fatalf("testdata/session.json is stale; run `go test ./gm -update`")
	}
}

// --- samples: a direct RCS chat with Sam, and an SMS group ---

func sampleConversation() *gmproto.Conversation {
	return &gmproto.Conversation{
		ConversationID:       "12",
		Name:                 "Sam Ortiz",
		LastMessageTimestamp: 1_759_310_000_000_000,
		Unread:               true,
		DefaultOutgoingID:    "2",
		Status:               gmproto.ConversationStatus_ACTIVE,
		LatestMessageID:      "1003",
		SendMode:             gmproto.ConversationSendMode_SEND_MODE_AUTO,
		Type:                 gmproto.ConversationType_RCS,
		OtherParticipants:    []string{"3"},
		LatestMessage: &gmproto.LatestMessage{
			DisplayContent: "See you at 7",
			FromMe:         0,
			DisplayName:    "Sam Ortiz",
		},
		Participants: []*gmproto.Participant{
			{
				ID:        &gmproto.SmallInfo{Type: gmproto.IdentifierType_PHONE, Number: "+15555550100", ParticipantID: "2"},
				IsMe:      true,
				IsVisible: true,
			},
			{
				ID:              &gmproto.SmallInfo{Type: gmproto.IdentifierType_PHONE, Number: "+15555550123", ParticipantID: "3"},
				FirstName:       "Sam",
				FullName:        "Sam Ortiz",
				FormattedNumber: "(555) 555-0123",
				IsVisible:       true,
				ContactID:       "77",
				AvatarHexColor:  "#1e88e5",
			},
		},
	}
}

func sampleGroup() *gmproto.Conversation {
	return &gmproto.Conversation{
		ConversationID:       "34",
		Name:                 "Hiking crew",
		IsGroupChat:          true,
		LastMessageTimestamp: 1_759_200_000_000_000,
		DefaultOutgoingID:    "2",
		Status:               gmproto.ConversationStatus_ACTIVE,
		LatestMessageID:      "2001",
		SendMode:             gmproto.ConversationSendMode_SEND_MODE_XMS,
		Type:                 gmproto.ConversationType_SMS,
		OtherParticipants:    []string{"3", "5"},
		Participants: []*gmproto.Participant{
			{ID: &gmproto.SmallInfo{Number: "+15555550100", ParticipantID: "2"}, IsMe: true, IsVisible: true},
			{ID: &gmproto.SmallInfo{Number: "+15555550123", ParticipantID: "3"}, FullName: "Sam Ortiz", IsVisible: true},
			{ID: &gmproto.SmallInfo{Number: "+15555550144", ParticipantID: "5"}, FullName: "Dad", IsVisible: true},
		},
	}
}

func sampleIncoming() *gmproto.Message {
	return &gmproto.Message{
		MessageID:      "1003",
		MessageStatus:  &gmproto.MessageStatus{Status: gmproto.MessageStatusType_INCOMING_COMPLETE},
		Timestamp:      1_759_310_000_000_000,
		ConversationID: "12",
		ParticipantID:  "3",
		Type:           4,
		MessageInfo: []*gmproto.MessageInfo{
			{ActionMessageID: ptr.Ptr("p1"), Data: &gmproto.MessageInfo_MessageContent{MessageContent: &gmproto.MessageContent{Content: "See you at 7"}}},
			{ActionMessageID: ptr.Ptr("p2"), Data: &gmproto.MessageInfo_MediaContent{MediaContent: &gmproto.MediaContent{
				Format:        gmproto.MediaFormats_IMAGE_JPEG,
				MediaID:       "media-abc",
				MediaName:     "map.jpg",
				Size:          34567,
				Dimensions:    &gmproto.Dimensions{Width: 1200, Height: 800},
				DecryptionKey: []byte("0123456789abcdef0123456789abcdef"),
			}}},
			{ActionMessageID: ptr.Ptr("p3"), Data: &gmproto.MessageInfo_MessageContent{MessageContent: &gmproto.MessageContent{Content: "Bring the map"}}},
		},
		Reactions: []*gmproto.ReactionEntry{
			{Data: &gmproto.ReactionData{Type: gmproto.EmojiType_RED_HEART, Unicode: "❤️"}, ParticipantIDs: []string{"3"}},
		},
		SenderParticipant: &gmproto.Participant{
			ID:        &gmproto.SmallInfo{Number: "+15555550123", ParticipantID: "3"},
			FirstName: "Sam",
			FullName:  "Sam Ortiz",
			IsVisible: true,
		},
	}
}

func sampleOutgoing(status gmproto.MessageStatusType) *gmproto.Message {
	return &gmproto.Message{
		MessageID:      "1002",
		MessageStatus:  &gmproto.MessageStatus{Status: status},
		Timestamp:      1_759_305_000_000_000,
		ConversationID: "12",
		ParticipantID:  "2",
		Type:           4,
		TmpID:          "tmp-1002",
		MessageInfo: []*gmproto.MessageInfo{
			{Data: &gmproto.MessageInfo_MessageContent{MessageContent: &gmproto.MessageContent{Content: "Leaving now"}}},
		},
		ReplyMessage: &gmproto.ReplyMessage{MessageID: "1001"},
	}
}

func sampleOlder() *gmproto.Message {
	return &gmproto.Message{
		MessageID:      "1001",
		MessageStatus:  &gmproto.MessageStatus{Status: gmproto.MessageStatusType_INCOMING_COMPLETE},
		Timestamp:      1_759_300_000_000_000,
		ConversationID: "12",
		ParticipantID:  "3",
		Type:           1,
		MessageInfo: []*gmproto.MessageInfo{
			{Data: &gmproto.MessageInfo_MessageContent{MessageContent: &gmproto.MessageContent{Content: "Are we still on?"}}},
		},
	}
}

func sampleGroupMessage() *gmproto.Message {
	return &gmproto.Message{
		MessageID:      "2001",
		MessageStatus:  &gmproto.MessageStatus{Status: gmproto.MessageStatusType_INCOMING_COMPLETE},
		Timestamp:      1_759_200_000_000_000,
		ConversationID: "34",
		ParticipantID:  "5",
		Type:           2,
		MessageInfo: []*gmproto.MessageInfo{
			{Data: &gmproto.MessageInfo_MessageContent{MessageContent: &gmproto.MessageContent{Content: "Who brings snacks?"}}},
		},
	}
}

func sampleSettings() *gmproto.Settings {
	return &gmproto.Settings{
		SIMCards: []*gmproto.SIMCard{{
			RCSChats: &gmproto.RCSChats{Enabled: true},
			SIMData: &gmproto.SIMData{
				SIMPayload:           &gmproto.SIMPayload{Two: 2, SIMNumber: 1},
				CarrierName:          "Example Mobile",
				ColorHex:             "#00897b",
				FormattedPhoneNumber: "(555) 555-0100",
			},
			SIMParticipant: &gmproto.SIMParticipant{ID: "2"},
		}},
		RCSSettings: &gmproto.RCSSettings{
			IsEnabled:            true,
			SendReadReceipts:     true,
			ShowTypingIndicators: true,
			IsDefaultSMSApp:      ptr.Ptr(true),
		},
	}
}
