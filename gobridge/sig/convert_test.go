// SPDX-License-Identifier: AGPL-3.0-or-later

package sig

import (
	"encoding/json"
	"testing"

	"github.com/google/uuid"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/protobuf/backuppb"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/protobuf/signalpb"
	"google.golang.org/protobuf/proto"
)

var (
	sam = uuid.MustParse("11111111-2222-3333-4444-555555555555")
	me  = uuid.MustParse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
)

func TestTextWithQuoteFlattens(t *testing.T) {
	dm := &signalpb.DataMessage{
		Timestamp: proto.Uint64(1759310000000),
		Body:      proto.String("See you at 7"),
		Quote: &signalpb.DataMessage_Quote{
			Id:        proto.Uint64(1759300000000),
			AuthorAci: proto.String(me.String()),
			Text:      proto.String("What time?"),
		},
	}
	out := dataMessage(sam.String(), sam, false, dm)
	if out.ID != sam.String()+":1759310000000" || out.Kind != "text" || out.Text != "See you at 7" {
		t.Fatalf("%+v", out)
	}
	if out.Quote == nil || out.Quote.ID != me.String()+":1759300000000" || out.Quote.Text != "What time?" {
		t.Fatalf("quote: %+v", out.Quote)
	}
	sender, ts, err := ParseMessageID(out.ID)
	if err != nil || sender != sam || ts != 1759310000000 {
		t.Fatalf("id round trip: %v %d %v", sender, ts, err)
	}
}

func TestVoiceNoteAndStickerKinds(t *testing.T) {
	voice := &signalpb.DataMessage{Timestamp: proto.Uint64(1), Attachments: []*signalpb.AttachmentPointer{{
		ContentType: proto.String("audio/aac"), Flags: proto.Uint32(uint32(signalpb.AttachmentPointer_VOICE_MESSAGE)),
		AttachmentIdentifier: &signalpb.AttachmentPointer_CdnKey{CdnKey: "k"}, Size: proto.Uint32(9),
	}}}
	out := dataMessage(sam.String(), sam, false, voice)
	if out.Kind != "voice" || out.Media == nil || !out.Media.Voice || out.Media.Size != 9 {
		t.Fatalf("%+v", out)
	}
	pointer, err := pointerOf(*out.Media)
	if err != nil || pointer.GetCdnKey() != "k" {
		t.Fatalf("pointer round trip: %v %v", pointer, err)
	}
	sticker := &signalpb.DataMessage{Timestamp: proto.Uint64(2), Sticker: &signalpb.DataMessage_Sticker{
		Emoji: proto.String("😀"), Data: &signalpb.AttachmentPointer{ContentType: proto.String("image/webp")},
	}}
	if got := dataMessage(sam.String(), sam, false, sticker); got.Kind != "sticker" || got.Text != "😀" {
		t.Fatalf("%+v", got)
	}
}

func TestReactionDeleteAndHousekeeping(t *testing.T) {
	reaction := &signalpb.DataMessage{Timestamp: proto.Uint64(3), Reaction: &signalpb.DataMessage_Reaction{
		Emoji: proto.String("❤️"), TargetAuthorAci: proto.String(me.String()), TargetSentTimestamp: proto.Uint64(1),
	}}
	out := dataMessage(sam.String(), sam, false, reaction)
	if out.Kind != "reaction" || out.Reaction.TargetSender != me.String() || out.Reaction.TargetTimestamp != 1 {
		t.Fatalf("%+v", out)
	}
	del := &signalpb.DataMessage{Timestamp: proto.Uint64(4), Delete: &signalpb.DataMessage_Delete{TargetSentTimestamp: proto.Uint64(1)}}
	if got := dataMessage(sam.String(), sam, false, del); got.Kind != "revoke" || got.Revoke.Timestamp != 1 {
		t.Fatalf("%+v", got)
	}
	timer := &signalpb.DataMessage{Timestamp: proto.Uint64(5), Flags: proto.Uint32(uint32(signalpb.DataMessage_EXPIRATION_TIMER_UPDATE))}
	if got := dataMessage(sam.String(), sam, false, timer); got.Kind != "skip" {
		t.Fatalf("timer change shown as %q", got.Kind)
	}
	if got := dataMessage(sam.String(), sam, false, &signalpb.DataMessage{Timestamp: proto.Uint64(6)}); got.Kind != "skip" {
		t.Fatalf("empty message shown as %q", got.Kind)
	}
}

func TestArchivedMessageKeepsReadAndStatus(t *testing.T) {
	authors := map[uint64]uuid.UUID{1: sam, 2: me}
	authorOf := func(id uint64) uuid.UUID { return authors[id] }
	incoming := &backuppb.ChatItem{
		ChatId: 7, AuthorId: 1, DateSent: 1759310000000,
		DirectionalDetails: &backuppb.ChatItem_Incoming{Incoming: &backuppb.ChatItem_IncomingMessageDetails{Read: true}},
		Item: &backuppb.ChatItem_StandardMessage{StandardMessage: &backuppb.StandardMessage{
			Text:      &backuppb.Text{Body: "hi"},
			Reactions: []*backuppb.Reaction{{Emoji: "👍", AuthorId: 2, SentTimestamp: 1759310001000}},
		}},
	}
	got := archivedMessage(sam.String(), incoming, sam, me, authorOf)
	if got == nil || got.Text != "hi" || !got.Read || got.FromMe || len(got.Reactions) != 1 || got.Reactions[0].Sender != me.String() {
		t.Fatalf("%+v", got)
	}
	outgoing := &backuppb.ChatItem{
		ChatId: 7, AuthorId: 2, DateSent: 1759310002000,
		DirectionalDetails: &backuppb.ChatItem_Outgoing{Outgoing: &backuppb.ChatItem_OutgoingMessageDetails{
			SendStatus: []*backuppb.SendStatus{{DeliveryStatus: &backuppb.SendStatus_Read_{Read: &backuppb.SendStatus_Read{}}}},
		}},
		Item: &backuppb.ChatItem_StandardMessage{StandardMessage: &backuppb.StandardMessage{
			Text:  &backuppb.Text{Body: "reply"},
			Quote: &backuppb.Quote{TargetSentTimestamp: proto.Uint64(1759310000000), AuthorId: 1, Text: &backuppb.Text{Body: "hi"}},
		}},
	}
	got = archivedMessage(sam.String(), outgoing, me, me, authorOf)
	if got == nil || !got.FromMe || got.Status != "read" || got.Quote == nil || got.Quote.ID != sam.String()+":1759310000000" {
		t.Fatalf("%+v", got)
	}
	deleted := &backuppb.ChatItem{ChatId: 7, AuthorId: 1, DateSent: 3, Item: &backuppb.ChatItem_RemoteDeletedMessage{}}
	if archivedMessage(sam.String(), deleted, sam, me, authorOf) != nil {
		t.Fatal("a remotely deleted message was shown")
	}
}

func TestMessageJSONShape(t *testing.T) {
	out := dataMessage(sam.String(), sam, false, &signalpb.DataMessage{Timestamp: proto.Uint64(1), Body: proto.String("x")})
	raw, _ := json.Marshal(out)
	var back map[string]any
	_ = json.Unmarshal(raw, &back)
	for _, key := range []string{"id", "chat", "sender", "fromMe", "timestamp", "kind", "text"} {
		if _, ok := back[key]; !ok {
			t.Fatalf("missing %q in %s", key, raw)
		}
	}
}
