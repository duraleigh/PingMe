// SPDX-License-Identifier: AGPL-3.0-or-later

package fb

import (
	"encoding/json"
	"testing"

	"go.mau.fi/mautrix-meta/pkg/messagix/table"
)

type recorder struct{ events []map[string]any }

func (r *recorder) OnEvent(raw string) {
	var event map[string]any
	if err := json.Unmarshal([]byte(raw), &event); err != nil {
		panic(err)
	}
	r.events = append(r.events, event)
}

func newTestSession(t *testing.T) (*Session, *recorder) {
	t.Helper()
	sink := &recorder{}
	s, err := NewSession(`{"xs":"x","c_user":"1000","datr":"d"}`, sink)
	if err != nil {
		t.Fatalf("session: %v", err)
	}
	return s, sink
}

func kinds(events []map[string]any) []string {
	out := make([]string, 0, len(events))
	for _, e := range events {
		out = append(out, e["type"].(string))
	}
	return out
}

func sub(t *testing.T, event map[string]any, key string) map[string]any {
	t.Helper()
	inner, ok := event[key].(map[string]any)
	if !ok {
		t.Fatalf("no %q in %v", key, event)
	}
	return inner
}

func TestMissingCookiesAreRefused(t *testing.T) {
	if _, err := NewSession(`{"xs":"x"}`, nil); err == nil {
		t.Fatal("a sign-in without c_user and datr was accepted")
	}
}

func TestGroupListingCarriesMembersNamesAndHistory(t *testing.T) {
	s, sink := newTestSession(t)
	tbl := &table.LSTable{
		LSDeleteThenInsertThread: []*table.LSDeleteThenInsertThread{{
			ThreadKey: 555, ThreadName: "Weekend plans", ThreadType: table.GROUP_THREAD, FolderName: "inbox",
			LastActivityTimestampMs: 1759310000000, LastReadWatermarkTimestampMs: 1759300000000,
		}},
		LSAddParticipantIdToGroupThread: []*table.LSAddParticipantIdToGroupThread{
			{ThreadKey: 555, ContactId: 1000}, {ThreadKey: 555, ContactId: 2, IsAdmin: true}, {ThreadKey: 555, ContactId: 3},
		},
		LSDeleteThenInsertContact: []*table.LSDeleteThenInsertContact{{Id: 2, Name: "Ann Lee", ProfilePictureUrl: "https://p/2"}},
		LSVerifyContactRowExists:  []*table.LSVerifyContactRowExists{{ContactId: 3, Name: "Bo"}},
		LSInsertNewMessageRange:   []*table.LSInsertNewMessageRange{{ThreadKey: 555, HasMoreBefore: true}},
		LSUpsertMessage: []*table.LSUpsertMessage{
			{ThreadKey: 555, MessageId: "mid.$a", SenderId: 2, TimestampMs: 1759309000000, Text: "Earlier"},
			{ThreadKey: 555, MessageId: "mid.$b", SenderId: 3, TimestampMs: 1759310000000, Text: "Later"},
		},
	}
	s.applyTable(tbl, true)
	if got := kinds(sink.events); len(got) != 1 || got[0] != "thread" {
		t.Fatalf("events: %v", got)
	}
	thread := sub(t, sink.events[0], "thread")
	if thread["id"] != "555" || thread["title"] != "Weekend plans" || thread["isGroup"] != true || thread["folder"] != "inbox" {
		t.Fatalf("thread: %v", thread)
	}
	if thread["moreBefore"] != true {
		t.Fatalf("older history not flagged: %v", thread)
	}
	users := thread["users"].([]any)
	if len(users) != 3 {
		t.Fatalf("users: %v", users)
	}
	me := users[0].(map[string]any)
	if me["id"] != "1000" || me["isMe"] != true {
		t.Fatalf("the account itself is not first: %v", me)
	}
	ann := users[1].(map[string]any)
	if ann["id"] != "2" || ann["name"] != "Ann Lee" || ann["picture"] != "https://p/2" {
		t.Fatalf("names not joined from the contact rows: %v", ann)
	}
	if admins := thread["admins"].([]any); len(admins) != 1 || admins[0] != "2" {
		t.Fatalf("admins: %v", admins)
	}
	messages := thread["messages"].([]any)
	if len(messages) != 2 || messages[0].(map[string]any)["id"] != "mid.$b" {
		t.Fatalf("history not newest first: %v", messages)
	}
}

func TestOneToOneThreadNamesTheOtherPerson(t *testing.T) {
	s, sink := newTestSession(t)
	s.applyTable(&table.LSTable{
		LSUpdateOrInsertThread: []*table.LSUpdateOrInsertThread{{
			ThreadKey: 42, ThreadName: "Cy Dee", ThreadPictureUrl: "https://p/42", ThreadType: table.ONE_TO_ONE, FolderName: "pending",
		}},
	}, true)
	thread := sub(t, sink.events[0], "thread")
	if thread["isGroup"] != false || thread["folder"] != "pending" {
		t.Fatalf("thread: %v", thread)
	}
	users := thread["users"].([]any)
	if len(users) != 2 {
		t.Fatalf("users: %v", users)
	}
	other := users[1].(map[string]any)
	if other["id"] != "42" || other["name"] != "Cy Dee" || other["picture"] != "https://p/42" {
		t.Fatalf("the other person is not the thread's name and picture: %v", other)
	}
}

func TestLiveMessageWithPictureAndReply(t *testing.T) {
	s, sink := newTestSession(t)
	s.applyTable(&table.LSTable{
		LSUpdateOrInsertThread: []*table.LSUpdateOrInsertThread{{ThreadKey: 42, ThreadType: table.ONE_TO_ONE, FolderName: "inbox"}},
	}, true)
	sink.events = nil
	s.applyTable(&table.LSTable{
		LSInsertMessage: []*table.LSInsertMessage{{
			ThreadKey: 42, MessageId: "mid.$p", SenderId: 42, TimestampMs: 1759311000000, Text: "Look",
			ReplySourceId: "mid.$a", ReplyMessageText: "Earlier",
		}},
		LSInsertBlobAttachment: []*table.LSInsertBlobAttachment{{
			MessageId: "mid.$p", ThreadKey: 42, AttachmentFbid: "77", AttachmentType: table.AttachmentTypeImage,
			PreviewUrl: "https://cdn/p.jpg", PreviewUrlMimeType: "image/jpeg", PreviewWidth: 640, PreviewHeight: 480,
		}},
	}, false)
	if got := kinds(sink.events); len(got) != 1 || got[0] != "message" {
		t.Fatalf("events: %v", got)
	}
	message := sub(t, sink.events[0], "message")
	if message["kind"] != "image" || message["thread"] != "42" || message["sender"] != "42" || message["text"] != "Look" {
		t.Fatalf("message: %v", message)
	}
	if message["replyTo"] != "mid.$a" || message["replyText"] != "Earlier" {
		t.Fatalf("reply lost: %v", message)
	}
	media := message["media"].([]any)[0].(map[string]any)
	if media["url"] != "https://cdn/p.jpg" || media["mime"] != "image/jpeg" || media["width"] != 640.0 {
		t.Fatalf("media: %v", media)
	}
	s.mu.Lock()
	newest := s.threads[42].Messages
	s.mu.Unlock()
	if len(newest) != 1 || newest[0].ID != "mid.$p" {
		t.Fatalf("the thread did not keep the new message: %v", newest)
	}
}

func TestReactionRemovalNamesTheEmoji(t *testing.T) {
	s, sink := newTestSession(t)
	s.applyTable(&table.LSTable{
		LSUpsertReaction: []*table.LSUpsertReaction{{ThreadKey: 42, MessageId: "mid.$p", ActorId: 42, Reaction: "❤️", TimestampMs: 5}},
	}, false)
	s.applyTable(&table.LSTable{
		LSDeleteReaction: []*table.LSDeleteReaction{{ThreadKey: 42, MessageId: "mid.$p", ActorId: 42}},
	}, false)
	if got := kinds(sink.events); len(got) != 2 || got[0] != "reaction" || got[1] != "reaction" {
		t.Fatalf("events: %v", got)
	}
	removal := sink.events[1]
	reaction := sub(t, removal, "reaction")
	if removal["removed"] != true || reaction["emoji"] != "❤️" || reaction["sender"] != "42" {
		t.Fatalf("removal: %v", removal)
	}
}

func TestEditsUnsendsReceiptsAndTyping(t *testing.T) {
	s, sink := newTestSession(t)
	s.applyTable(&table.LSTable{
		LSInsertMessage: []*table.LSInsertMessage{{ThreadKey: 42, MessageId: "mid.$p", SenderId: 1000, TimestampMs: 3, Text: "Hi"}},
	}, false)
	sink.events = nil
	s.applyTable(&table.LSTable{
		LSEditMessage:             []*table.LSEditMessage{{MessageID: "mid.$p", Text: "Hi there"}},
		LSDeleteMessage:           []*table.LSDeleteMessage{{ThreadKey: 42, MessageId: "mid.$q"}},
		LSDeleteThenInsertMessage: []*table.LSDeleteThenInsertMessage{{ThreadKey: 42, MessageId: "mid.$r", IsUnsent: true}},
		LSUpdateReadReceipt:       []*table.LSUpdateReadReceipt{{ThreadKey: 42, ContactId: 42, ReadWatermarkTimestampMs: 9}},
		LSMarkThreadReadV2:        []*table.LSMarkThreadReadV2{{ThreadKey: 42, LastReadWatermarkTimestampMs: 8}},
		LSUpdateTypingIndicator:   []*table.LSUpdateTypingIndicator{{ThreadKey: 42, SenderId: 42, IsTyping: true}},
	}, false)
	got := kinds(sink.events)
	want := []string{"edit", "unsent", "unsent", "readReceipt", "readByMe", "typing"}
	if len(got) != len(want) {
		t.Fatalf("events: %v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("events: %v, wanted %v", got, want)
		}
	}
	if edit := sink.events[0]; edit["thread"] != "42" || edit["text"] != "Hi there" {
		t.Fatalf("edit did not find its thread: %v", edit)
	}
	if receipt := sink.events[3]; receipt["sender"] != "42" || receipt["timestamp"] != 9.0 {
		t.Fatalf("receipt: %v", receipt)
	}
}

func TestLeavingOrDeletingAThreadReportsItGone(t *testing.T) {
	s, sink := newTestSession(t)
	s.applyTable(&table.LSTable{
		LSUpdateOrInsertThread: []*table.LSUpdateOrInsertThread{
			{ThreadKey: 1, ThreadType: table.GROUP_THREAD}, {ThreadKey: 2, ThreadType: table.GROUP_THREAD},
		},
	}, true)
	sink.events = nil
	s.applyTable(&table.LSTable{
		LSDeleteThread:                []*table.LSDeleteThread{{ThreadKey: 1}},
		LSRemoveParticipantFromThread: []*table.LSRemoveParticipantFromThread{{ThreadKey: 2, ParticipantId: 1000}},
	}, false)
	if got := kinds(sink.events); len(got) != 2 || got[0] != "threadGone" || got[1] != "threadGone" {
		t.Fatalf("events: %v", got)
	}
	s.mu.Lock()
	left := len(s.threads)
	s.mu.Unlock()
	if left != 0 {
		t.Fatalf("%d threads still known", left)
	}
}

func TestConvertShareStickerVoiceAndUnsent(t *testing.T) {
	share := convertMessage(&table.WrappedMessage{
		LSInsertMessage: &table.LSInsertMessage{MessageId: "m1", ThreadKey: 1, SenderId: 2, TimestampMs: 1},
		XMAAttachments: []*table.WrappedXMA{{
			LSInsertXmaAttachment: &table.LSInsertXmaAttachment{TitleText: "A page", SubtitleText: "news.example", PreviewUrl: "https://cdn/x.jpg"},
			CTA:                   &table.LSInsertAttachmentCta{ActionUrl: "https://news.example/story"},
		}},
	})
	if share.Kind != "share" || share.Share == nil || share.Share.URL != "https://news.example/story" || share.Share.Title != "A page" {
		t.Fatalf("share: %+v", share)
	}
	sticker := convertMessage(&table.WrappedMessage{
		LSInsertMessage: &table.LSInsertMessage{MessageId: "m2", ThreadKey: 1, SenderId: 2, TimestampMs: 1},
		Stickers:        []*table.LSInsertStickerAttachment{{AttachmentFbid: "s1", PreviewUrl: "https://cdn/s.png", PreviewUrlMimeType: "image/png"}},
	})
	if sticker.Kind != "sticker" || len(sticker.Media) != 1 || sticker.Media[0].URL != "https://cdn/s.png" {
		t.Fatalf("sticker: %+v", sticker)
	}
	voice := convertMessage(&table.WrappedMessage{
		LSInsertMessage: &table.LSInsertMessage{MessageId: "m3", ThreadKey: 1, SenderId: 2, TimestampMs: 1},
		BlobAttachments: []*table.LSInsertBlobAttachment{{
			AttachmentFbid: "v1", AttachmentType: table.AttachmentTypeAudio, PlayableUrl: "https://cdn/v.mp4",
			PlayableUrlMimeType: "audio/mp4", PlayableDurationMs: 4200,
		}},
	})
	if voice.Kind != "voice" || voice.Media[0].DurationMS != 4200 || voice.Media[0].Mime != "audio/mp4" {
		t.Fatalf("voice: %+v", voice)
	}
	unsent := convertMessage(&table.WrappedMessage{
		LSInsertMessage: &table.LSInsertMessage{MessageId: "m4", ThreadKey: 1, SenderId: 2, TimestampMs: 1, Text: "gone", IsUnsent: true},
	})
	if !unsent.Unsent || unsent.Text != "" {
		t.Fatalf("unsent: %+v", unsent)
	}
	system := convertMessage(&table.WrappedMessage{
		LSInsertMessage: &table.LSInsertMessage{MessageId: "m5", ThreadKey: 1, SenderId: 2, TimestampMs: 1, Text: "Ann named the group", IsAdminMessage: true},
	})
	if system.Kind != "system" {
		t.Fatalf("system: %+v", system)
	}
}

func TestSessionViewsAndCookies(t *testing.T) {
	s, _ := newTestSession(t)
	if s.OwnID() != "1000" {
		t.Fatalf("own id from c_user: %q", s.OwnID())
	}
	var saved map[string]string
	if err := json.Unmarshal([]byte(s.CookiesJSON()), &saved); err != nil || saved["xs"] != "x" {
		t.Fatalf("cookies: %v %v", saved, err)
	}
	s.applyTable(&table.LSTable{
		LSUpdateOrInsertThread: []*table.LSUpdateOrInsertThread{
			{ThreadKey: 1, ThreadType: table.ONE_TO_ONE, LastActivityTimestampMs: 5},
			{ThreadKey: 2, ThreadType: table.ONE_TO_ONE, LastActivityTimestampMs: 9},
		},
	}, true)
	listed, err := s.Threads()
	if err != nil {
		t.Fatal(err)
	}
	var threads []Thread
	if err := json.Unmarshal([]byte(listed), &threads); err != nil || len(threads) != 2 || threads[0].ID != "2" {
		t.Fatalf("threads newest first: %s", listed)
	}
	if _, err := s.Thread("3"); err == nil {
		t.Fatal("an unknown thread was found")
	}
	if _, err := s.Messages("1", "mid.$x"); err == nil {
		t.Fatal("history was fetched without a connection")
	}
	if _, err := s.SendText("1", "hi", ""); err == nil {
		t.Fatal("a send went through without a connection")
	}
}

// Facebook re-sends the inbox as a delete row plus an upsert row for the same thread; that
// is not a deletion (the owner's whole Messenger inbox vanished this way, 2026-10-04).
func TestDeleteWithUpsertInSameBatchKeepsTheThread(t *testing.T) {
	s, sink := newTestSession(t)
	s.applyTable(&table.LSTable{
		LSDeletePartialThread: []*table.LSDeletePartialThread{{ThreadKey: 1}, {ThreadKey: 2}},
		LSDeleteThread:        []*table.LSDeleteThread{{ThreadKey: 3}},
		LSUpdateOrInsertThread: []*table.LSUpdateOrInsertThread{
			{ThreadKey: 1, ThreadType: table.ONE_TO_ONE, ThreadName: "Ann Lee", LastActivityTimestampMs: 5},
		},
		LSDeleteThenInsertThread: []*table.LSDeleteThenInsertThread{
			{ThreadKey: 2, ThreadType: table.GROUP_THREAD, ThreadName: "Band", LastActivityTimestampMs: 4},
		},
	}, true)
	got := kinds(sink.events)
	if len(got) != 3 || got[0] != "threadGone" || got[1] != "thread" || got[2] != "thread" {
		t.Fatalf("events: %v", got)
	}
	if sink.events[0]["thread"] != "3" {
		t.Fatalf("gone: %v", sink.events[0])
	}
	listed, err := s.Threads()
	if err != nil {
		t.Fatal(err)
	}
	var threads []Thread
	if err := json.Unmarshal([]byte(listed), &threads); err != nil {
		t.Fatal(err)
	}
	if len(threads) != 2 || threads[0].Title != "Ann Lee" || threads[1].Title != "Band" {
		t.Fatalf("threads: %s", listed)
	}
}
