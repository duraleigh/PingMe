// SPDX-License-Identifier: AGPL-3.0-or-later

package ig

import (
	"encoding/json"
	"testing"

	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
)

func parseMessage(t *testing.T, raw string) *slidetypes.Message {
	t.Helper()
	var msg slidetypes.Message
	if err := json.Unmarshal([]byte(raw), &msg); err != nil {
		t.Fatalf("message JSON: %v", err)
	}
	return &msg
}

func TestTextWithReplyAndReactionsFlattens(t *testing.T) {
	msg := parseMessage(t, `{
		"message_id": "M1", "sender_fbid": "17841400000000001", "thread_fbid": "340282366841710300949128",
		"timestamp_ms": "1759310000000", "text_body": "See you at 7",
		"content": {"__typename": "SlideMessageText", "text_body": "See you at 7"},
		"replied_to_message_id": "M0",
		"replied_to_message": {"message_id": "M0", "sender_fbid": "2", "text_body": "What time?", "timestamp_ms": "1759300000000", "content": {"__typename": "SlideMessageText", "text_body": "What time?"}},
		"reactions": [{"reaction": "❤️", "sender_fbid": "2", "reaction_timestamp_ms": "1759310001000"}]
	}`)
	out := convertMessage(msg, "")
	if out.Kind != "text" || out.Text != "See you at 7" || out.Sender != "17841400000000001" || out.Thread != "340282366841710300949128" {
		t.Fatalf("unexpected message: %+v", out)
	}
	if out.ReplyTo != "M0" || out.ReplyText != "What time?" {
		t.Fatalf("reply not kept: %+v", out)
	}
	if len(out.Reactions) != 1 || out.Reactions[0].Emoji != "❤️" || out.Reactions[0].Sender != "2" {
		t.Fatalf("reactions wrong: %+v", out.Reactions)
	}
	if out.Timestamp != 1759310000000 {
		t.Fatalf("timestamp wrong: %d", out.Timestamp)
	}
}

func TestMediaAndSharesFlatten(t *testing.T) {
	picture := parseMessage(t, `{"message_id": "M2", "sender_fbid": "2", "timestamp_ms": "1", "content": {
		"__typename": "SlideMessageImageContent", "attachments": [{"attachment_fbid": "A1", "attachment_cdn_url": "https://cdn/a.jpg", "preview_width": 800, "preview_height": 600}]}}`)
	out := convertMessage(picture, "T")
	if out.Kind != "image" || len(out.Media) != 1 || out.Media[0].URL != "https://cdn/a.jpg" || out.Media[0].Width != 800 || out.Thread != "T" {
		t.Fatalf("picture wrong: %+v", out)
	}
	voice := parseMessage(t, `{"message_id": "M3", "sender_fbid": "2", "timestamp_ms": "1", "content": {
		"__typename": "SlideMessageAudiosContent", "audio_attachments": [{"attachment_fbid": "A2", "attachment_cdn_url": "https://cdn/v.m4a", "playable_duration_ms": 4200}]}}`)
	if got := convertMessage(voice, "T"); got.Kind != "voice" || got.Media[0].DurationMS != 4200 {
		t.Fatalf("voice wrong: %+v", got)
	}
	gone := parseMessage(t, `{"message_id": "M4", "sender_fbid": "2", "timestamp_ms": "1", "content": {
		"__typename": "SlideMessageRavenImageContent", "view_mode": 0, "attachment": null}}`)
	if got := convertMessage(gone, "T"); got.Kind != "image" || !got.ViewOnce || got.ViewOnceGone == "" {
		t.Fatalf("view-once wrong: %+v", got)
	}
	// A video taken in the chat and kept (view mode 2) is an ordinary video, not ephemeral,
	// even when the live event carries no address yet: the id fetches one later
	// (owner, 2026-10-04: a kept video read as "view-once ... no longer shows").
	kept := parseMessage(t, `{"message_id": "M6", "sender_fbid": "2", "timestamp_ms": "1", "content": {
		"__typename": "SlideMessageRavenVideoContent", "view_mode": 2, "attachment": {"attachment_fbid": "A9", "attachment_cdn_url": "", "preview_cdn_url": "https://cdn/p9.jpg"}}}`)
	if got := convertMessage(kept, "T"); got.Kind != "video" || got.ViewOnce || got.ViewOnceGone != "" ||
		len(got.Media) != 1 || got.Media[0].ID != "A9" || got.Media[0].URL != "" || got.Media[0].PreviewURL != "https://cdn/p9.jpg" {
		t.Fatalf("kept video wrong: %+v", got)
	}
	replayed := parseMessage(t, `{"message_id": "M7", "sender_fbid": "2", "timestamp_ms": "1", "content": {
		"__typename": "SlideMessageRavenVideoContent", "view_mode": 1, "attachment": null}}`)
	if got := convertMessage(replayed, "T"); !got.ViewOnce || got.ViewOnceGone != "replayed" {
		t.Fatalf("replayed wrong: %+v", got)
	}
	share := parseMessage(t, `{"message_id": "M5", "sender_fbid": "2", "timestamp_ms": "1", "content": {
		"__typename": "SlideMessageXMAContent", "xma_text_body": "look", "xma": {"__typename": "XMAReel", "title_text": "A reel", "target_url": "https://instagram.com/reel/1", "preview_image": {"url": "https://cdn/p.jpg"}}}}`)
	if got := convertMessage(share, "T"); got.Kind != "share" || got.Share == nil || got.Share.URL != "https://instagram.com/reel/1" || got.Share.Preview != "https://cdn/p.jpg" {
		t.Fatalf("share wrong: %+v", got)
	}
}

func TestThreadsCarryFoldersPeopleAndMessages(t *testing.T) {
	var info slidetypes.ThreadInfo
	raw := `{"id": "340", "thread_id": "340282366841710300949128", "thread_fbid": "340", "thread_key": "340", "is_group": false,
		"thread_title": "sam", "last_activity_timestamp_ms": "1759310000000", "system_folder": "PENDING", "folder": "", "marked_as_unread": true,
		"users": [{"interop_messaging_user_fbid": "2", "id": "9", "username": "sam", "full_name": "Sam Ortiz"}],
		"viewer": {"interop_messaging_user_fbid": "1", "id": "8", "username": "me"},
		"slide_read_receipts": [{"participant_fbid": "1", "watermark_timestamp_ms": "1759300000000"}],
		"slide_messages": {"edges": [{"node": {"message_id": "M1", "sender_fbid": "2", "thread_fbid": "340", "timestamp_ms": "1759310000000", "text_body": "hi", "content": {"__typename": "SlideMessageText", "text_body": "hi"}}}]}}`
	if err := json.Unmarshal([]byte(raw), &info); err != nil {
		t.Fatalf("thread JSON: %v", err)
	}
	thread := convertThread(&info, 1)
	if thread.ID != "340" || thread.LongID != "340282366841710300949128" || thread.SystemFolder != "PENDING" || !thread.MarkedUnread {
		t.Fatalf("thread wrong: %+v", thread)
	}
	if len(thread.Users) != 2 || thread.Users[0].Name != "Sam Ortiz" || !thread.Users[1].IsMe || thread.ReadAt != 1759300000000 {
		t.Fatalf("people wrong: %+v", thread.Users)
	}
	if len(thread.Messages) != 1 || thread.Messages[0].Text != "hi" {
		t.Fatalf("messages wrong: %+v", thread.Messages)
	}
}
