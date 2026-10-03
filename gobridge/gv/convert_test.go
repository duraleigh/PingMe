// SPDX-License-Identifier: AGPL-3.0-or-later

package gv

import (
	"testing"

	"go.mau.fi/mautrix-gvoice/pkg/libgv/gvproto"
)

func TestThreadAndMessagesFlatten(t *testing.T) {
	thread := &gvproto.Thread{
		ID:           "t.+15555550123",
		Read:         false,
		IsText:       true,
		PhoneNumbers: []string{"+15555550123"},
		Contacts:     []*gvproto.Contact{{Name: "Sam", PhoneNumber: "+15555550123"}},
		Folders:      []gvproto.ThreadFolder{gvproto.ThreadFolder_ALL_ARCHIVED_THREADS},
		Messages: []*gvproto.Message{
			{ID: "m3", Timestamp: 3000, Type: gvproto.Message_SMS_OUT, Text: "On my way", Status: gvproto.Message_READ},
			{ID: "m2", Timestamp: 2000, Type: gvproto.Message_SMS_IN, Contact: &gvproto.Contact{PhoneNumber: "+15555550123"},
				MMS: &gvproto.MMSMessage{Attachments: []*gvproto.Attachment{{ID: "a1", MimeType: "image/jpeg",
					Metadata: []*gvproto.Attachment_Metadata{{Size: gvproto.Attachment_Metadata_ORIGINAL, Width: 640, Height: 480}}}}}},
			{ID: "m1", Timestamp: 1000, Type: gvproto.Message_MISSED_CALL, Contact: &gvproto.Contact{PhoneNumber: "+15555550123"}},
		},
	}
	out := convertThread(thread, true)
	if !out.Archived || out.LastAt != 3000 || len(out.Messages) != 3 || out.Contacts[0].Name != "Sam" {
		t.Fatalf("%+v", out)
	}
	if m := out.Messages[0]; !m.FromMe || m.Kind != "text" || !m.Read {
		t.Fatalf("outgoing: %+v", m)
	}
	if m := out.Messages[1]; m.FromMe || m.Kind != "media" || m.Sender != "+15555550123" || m.Media[0].Width != 640 {
		t.Fatalf("picture: %+v", m)
	}
	if m := out.Messages[2]; m.Kind != "missedCall" || m.Text != "Missed call" {
		t.Fatalf("call: %+v", m)
	}
}

func TestVoicemailCarriesItsTranscript(t *testing.T) {
	m := &gvproto.Message{ID: "v1", Timestamp: 1, Type: gvproto.Message_VOICEMAIL, Transcript: &gvproto.Message_Transcript{
		Tokens: []*gvproto.Message_TranscriptToken{{Text: []byte("Call me ")}, {Text: []byte("back")}},
	}}
	out := convertMessage("t.+1", m)
	if out.Kind != "voicemail" || out.Text != "Voicemail: Call me back" {
		t.Fatalf("%+v", out)
	}
	if ThreadIDFor("+15555550123") != "t.+15555550123" {
		t.Fatal("thread id")
	}
}
