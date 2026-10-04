// SPDX-License-Identifier: AGPL-3.0-or-later

package fb

import (
	"encoding/json"
	"testing"
	"time"

	"go.mau.fi/mautrix-meta/pkg/messagix/table"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waConsumerApplication"
	"go.mau.fi/whatsmeow/proto/waMediaTransport"
	"go.mau.fi/whatsmeow/proto/waMsgApplication"
	waTypes "go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

// Messenger lists an encrypted one-to-one chat under a thread key that is not the other
// person's id, and a mapping row ties it to the chat's id on the encrypted channel (the
// other person's id). The chat is shown under that id, so the member is the person and
// not a phantom "Messenger user" (owner, 2026-10-04).
func TestMappedThreadIsShownUnderItsChannelIDWithTheOtherPersonNamed(t *testing.T) {
	s, sink := newTestSession(t)
	s.applyTable(&table.LSTable{
		LSVerifyHybridThreadExists: []*table.LSVerifyHybridThreadExists{{ThreadKey: 900, ThreadJID: 2, ThreadType: table.ENCRYPTED_OVER_WA_ONE_TO_ONE}},
		LSUpdateOrInsertThread: []*table.LSUpdateOrInsertThread{
			{ThreadKey: 900, ThreadType: table.ONE_TO_ONE, ThreadName: "Ann Lee", ThreadPictureUrl: "https://p/2", LastActivityTimestampMs: 5},
		},
		LSDeleteThenInsertContact: []*table.LSDeleteThenInsertContact{{Id: 2, Name: "Ann Lee", ProfilePictureUrl: "https://p/2"}},
	}, true)
	got := kinds(sink.events)
	if len(got) != 2 || got[0] != "threadGone" || got[1] != "thread" {
		t.Fatalf("events: %v", got)
	}
	if sink.events[0]["thread"] != "900" {
		t.Fatalf("the web-key chat should go: %v", sink.events[0])
	}
	thread := sub(t, sink.events[1], "thread")
	if thread["id"] != "2" {
		t.Fatalf("shown under the channel id: %v", thread["id"])
	}
	users := thread["users"].([]any)
	if len(users) != 2 {
		t.Fatalf("users: %v", users)
	}
	other := users[1].(map[string]any)
	if other["id"] != "2" || other["name"] != "Ann Lee" {
		t.Fatalf("the other person: %v", other)
	}
	// Requests by either id reach the same thread.
	for _, id := range []string{"2", "900"} {
		key, err := s.keyFor(id)
		if err != nil || key != 900 {
			t.Fatalf("keyFor(%s) = %d, %v", id, key, err)
		}
	}
}

func textMessage(chat, sender int64, msgID, text string, fromMe bool) *events.FBMessage {
	return &events.FBMessage{
		Info: waTypes.MessageInfo{
			MessageSource: waTypes.MessageSource{Chat: waJID(chat), Sender: waJID(sender), IsFromMe: fromMe},
			ID:            msgID,
			Timestamp:     time.UnixMilli(1700000000000),
		},
		Message:       textContent(text),
		FBApplication: &waMsgApplication.MessageApplication{},
	}
}

// A message from the channel lands under the mapped thread, and a reply to it quotes it
// by its id and sender.
func TestChannelMessageLandsUnderTheMappedThread(t *testing.T) {
	s, sink := newTestSession(t)
	s.applyTable(&table.LSTable{
		LSVerifyHybridThreadExists: []*table.LSVerifyHybridThreadExists{{ThreadKey: 900, ThreadJID: 2, ThreadType: table.ENCRYPTED_OVER_WA_ONE_TO_ONE}},
		LSUpdateOrInsertThread:     []*table.LSUpdateOrInsertThread{{ThreadKey: 900, ThreadType: table.ONE_TO_ONE, ThreadName: "Ann Lee"}},
	}, true)
	sink.events = nil
	s.handleWAEvent(textMessage(2, 2, "3EB0ABC", "hi from the channel", false))
	if got := kinds(sink.events); len(got) != 1 || got[0] != "message" {
		t.Fatalf("events: %v", got)
	}
	msg := sub(t, sink.events[0], "message")
	if msg["thread"] != "2" || msg["sender"] != "2" || msg["text"] != "hi from the channel" || msg["kind"] != "text" {
		t.Fatalf("message: %v", msg)
	}
	listed, err := s.Thread("2")
	if err != nil {
		t.Fatal(err)
	}
	var view Thread
	if err := json.Unmarshal([]byte(listed), &view); err != nil {
		t.Fatal(err)
	}
	if len(view.Messages) != 1 || view.Messages[0].ID != "3EB0ABC" || view.MoreBefore {
		t.Fatalf("thread: %+v", view)
	}
	meta := s.replyMeta("3EB0ABC")
	if meta.GetQuotedMessage().GetStanzaID() != "3EB0ABC" || meta.GetQuotedMessage().GetParticipant() != "2@msgr" {
		t.Fatalf("reply: %v", meta)
	}
	key := s.waKey(s.threadAt(900), "3EB0ABC")
	if key.GetFromMe() || key.GetRemoteJID() != "2@msgr" {
		t.Fatalf("key: %v", key)
	}
	// Read marks on the channel name the unread messages by sender.
	th := s.threadAt(900)
	if len(th.waUnread) != 1 || th.waUnread[0].sender != 2 {
		t.Fatalf("unread: %+v", th.waUnread)
	}
}

// A message for a chat the web listing has not named yet makes the chat on the spot;
// the mapping, when it comes, folds it into the listed thread.
func TestChannelMessageBeforeTheListingMakesTheChatThenFoldsIn(t *testing.T) {
	s, sink := newTestSession(t)
	s.applyTable(&table.LSTable{}, true)
	sink.events = nil
	s.handleWAEvent(textMessage(7, 7, "M1", "first", false))
	if got := kinds(sink.events); len(got) != 2 || got[0] != "thread" || got[1] != "message" {
		t.Fatalf("events: %v", got)
	}
	if sub(t, sink.events[0], "thread")["id"] != "7" {
		t.Fatalf("thread: %v", sink.events[0])
	}
	sink.events = nil
	s.applyTable(&table.LSTable{
		LSVerifyHybridThreadExists: []*table.LSVerifyHybridThreadExists{{ThreadKey: 901, ThreadJID: 7}},
		LSUpdateOrInsertThread:     []*table.LSUpdateOrInsertThread{{ThreadKey: 901, ThreadType: table.ONE_TO_ONE, ThreadName: "Bo"}},
	}, false)
	th := s.threadAt(901)
	if th == nil || th.jid != 7 || len(th.Messages) != 1 || th.Title != "Bo" {
		t.Fatalf("folded thread: %+v", th)
	}
	if s.threadAt(7) != nil {
		t.Fatal("the stand-in chat should be gone")
	}
}

// Reactions, edits, and unsends from the channel become the same events the web gives.
func TestChannelReactionEditAndUnsend(t *testing.T) {
	s, sink := newTestSession(t)
	s.applyTable(&table.LSTable{
		LSVerifyHybridThreadExists: []*table.LSVerifyHybridThreadExists{{ThreadKey: 900, ThreadJID: 2}},
		LSUpdateOrInsertThread:     []*table.LSUpdateOrInsertThread{{ThreadKey: 900, ThreadType: table.ONE_TO_ONE}},
	}, true)
	s.handleWAEvent(textMessage(2, 1000, "MINE", "mine", true))
	sink.events = nil
	react := textMessage(2, 2, "R1", "", false)
	react.Message = consumerContent(&waConsumerApplication.ConsumerApplication_Content{
		Content: &waConsumerApplication.ConsumerApplication_Content_ReactionMessage{
			ReactionMessage: &waConsumerApplication.ConsumerApplication_ReactionMessage{
				Key: &waCommon.MessageKey{ID: proto.String("MINE"), FromMe: proto.Bool(false)}, Text: proto.String("❤️"),
			},
		},
	})
	s.handleWAEvent(react)
	edit := textMessage(2, 1000, "E1", "", true)
	edit.Message = consumerContent(&waConsumerApplication.ConsumerApplication_Content{
		Content: &waConsumerApplication.ConsumerApplication_Content_EditMessage{
			EditMessage: &waConsumerApplication.ConsumerApplication_EditMessage{
				Key: &waCommon.MessageKey{ID: proto.String("MINE")}, Message: &waCommon.MessageText{Text: proto.String("mine, edited")},
			},
		},
	})
	s.handleWAEvent(edit)
	revoke := textMessage(2, 1000, "U1", "", true)
	revoke.Message = &waConsumerApplication.ConsumerApplication{
		Payload: &waConsumerApplication.ConsumerApplication_Payload{
			Payload: &waConsumerApplication.ConsumerApplication_Payload_ApplicationData{
				ApplicationData: &waConsumerApplication.ConsumerApplication_ApplicationData{
					ApplicationContent: &waConsumerApplication.ConsumerApplication_ApplicationData_Revoke{
						Revoke: &waConsumerApplication.ConsumerApplication_RevokeMessage{Key: &waCommon.MessageKey{ID: proto.String("MINE")}},
					},
				},
			},
		},
	}
	s.handleWAEvent(revoke)
	got := kinds(sink.events)
	if len(got) != 3 || got[0] != "reaction" || got[1] != "edit" || got[2] != "unsent" {
		t.Fatalf("events: %v", got)
	}
	if r := sub(t, sink.events[0], "reaction"); r["emoji"] != "❤️" || r["sender"] != "2" || sink.events[0]["message"] != "MINE" {
		t.Fatalf("reaction: %v", sink.events[0])
	}
	if sink.events[1]["text"] != "mine, edited" || sink.events[2]["message"] != "MINE" {
		t.Fatalf("edit/unsend: %v %v", sink.events[1], sink.events[2])
	}
	// The channel names my own message as mine when I react to or take it back.
	key := s.waKey(s.threadAt(900), "MINE")
	if !key.GetFromMe() {
		t.Fatalf("key: %v", key)
	}
}

// Outgoing media is wrapped by kind, with the caption where the kind allows one.
func TestWrapMediaByKind(t *testing.T) {
	tr := &waMediaTransport.WAMediaTransport{}
	img, err := wrapMedia("image", tr, "look", "", 10, 20)
	if err != nil || img.GetImageMessage() == nil || img.GetImageMessage().GetCaption().GetText() != "look" {
		t.Fatalf("image: %v %v", img, err)
	}
	gif, err := wrapMedia("gif", tr, "", "", 10, 20)
	if err != nil || gif.GetVideoMessage() == nil {
		t.Fatalf("gif: %v %v", gif, err)
	}
	vt, err := gif.GetVideoMessage().Decode()
	if err != nil || !vt.GetAncillary().GetGifPlayback() {
		t.Fatalf("gif playback: %v %v", vt, err)
	}
	voice, err := wrapMedia("voice", tr, "", "", 0, 0)
	if err != nil || !voice.GetAudioMessage().GetPTT() {
		t.Fatalf("voice: %v %v", voice, err)
	}
	file, err := wrapMedia("file", tr, "", "notes.pdf", 0, 0)
	if err != nil || file.GetDocumentMessage().GetFileName() != "notes.pdf" {
		t.Fatalf("file: %v %v", file, err)
	}
}
