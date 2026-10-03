// SPDX-License-Identifier: AGPL-3.0-or-later

package gv

import (
	"fmt"
	"strings"

	"go.mau.fi/mautrix-gvoice/pkg/libgv/gvproto"
)

// Thread is a conversation as Kotlin sees it.
type Thread struct {
	ID           string    `json:"id"`
	PhoneNumbers []string  `json:"phoneNumbers"`
	Contacts     []Member  `json:"contacts"`
	Read         bool      `json:"read"`
	IsText       bool      `json:"isText"`
	Archived     bool      `json:"archived"`
	Spam         bool      `json:"spam"`
	LastAt       int64     `json:"lastAt"`
	Messages     []Message `json:"messages,omitempty"`
	// For the next older page of this thread.
	PaginationToken string `json:"paginationToken,omitempty"`
}

// Member is a person: a phone number and the name Google Voice has for it.
type Member struct {
	Phone string `json:"phone"`
	Name  string `json:"name,omitempty"`
}

// Message is one item of a thread: a text, a picture message, a call, a voicemail.
type Message struct {
	ID        string  `json:"id"`
	Thread    string  `json:"thread"`
	Sender    string  `json:"sender"` // phone number; "" for your own
	FromMe    bool    `json:"fromMe"`
	Timestamp int64   `json:"timestamp"`
	Kind      string  `json:"kind"` // "text", "media", "call", "missedCall", "voicemail", "unsupported"
	Text      string  `json:"text,omitempty"`
	Media     []Media `json:"media,omitempty"`
	Read      bool    `json:"read"`
}

// Media is a picture or file in a message, fetched by its id.
type Media struct {
	ID     string `json:"id"`
	Mime   string `json:"mime"`
	Width  int32  `json:"width,omitempty"`
	Height int32  `json:"height,omitempty"`
	// Google Voice could not take this file ("File type not supported").
	Unsupported bool `json:"unsupported,omitempty"`
}

func convertThread(t *gvproto.Thread, withMessages bool) Thread {
	out := Thread{
		ID:           t.GetID(),
		PhoneNumbers: t.GetPhoneNumbers(),
		Read:         t.GetRead(),
		IsText:       t.GetIsText(),
		Contacts:     make([]Member, 0, len(t.GetContacts())),
	}
	if out.PhoneNumbers == nil {
		out.PhoneNumbers = []string{}
	}
	for _, c := range t.GetContacts() {
		out.Contacts = append(out.Contacts, Member{Phone: c.GetPhoneNumber(), Name: c.GetName()})
	}
	for _, f := range t.GetFolders() {
		switch f {
		case gvproto.ThreadFolder_ALL_ARCHIVED_THREADS:
			out.Archived = true
		case gvproto.ThreadFolder_ALL_SPAM_THREADS:
			out.Spam = true
		}
	}
	if msgs := t.GetMessages(); len(msgs) > 0 {
		out.LastAt = msgs[0].GetTimestamp()
	}
	if withMessages {
		out.Messages = make([]Message, 0, len(t.GetMessages()))
		for _, m := range t.GetMessages() {
			out.Messages = append(out.Messages, convertMessage(t.GetID(), m))
		}
		out.PaginationToken = t.GetPaginationToken()
	}
	return out
}

// convertMessage flattens an item, the way the reference bridge reads each kind.
func convertMessage(thread string, m *gvproto.Message) Message {
	out := Message{
		ID:        m.GetID(),
		Thread:    thread,
		Timestamp: m.GetTimestamp(),
		Read:      m.GetStatus() == gvproto.Message_READ,
		Kind:      "text",
		Text:      m.GetText(),
	}
	switch m.GetType() {
	case gvproto.Message_SMS_OUT, gvproto.Message_OUTGOING_CALL, gvproto.Message_OUTGOING_CALL_CANCELLED:
		out.FromMe = true
	default:
		if from := m.GetMMS().GetSenderPhoneNumber(); from != "" {
			out.Sender = from
		} else {
			out.Sender = m.GetContact().GetPhoneNumber()
		}
	}
	if mms := m.GetMMS(); mms != nil {
		if out.Text == "" {
			out.Text = mms.GetText()
		}
		for _, a := range mms.GetAttachments() {
			media := Media{ID: a.GetID(), Mime: a.GetMimeType(), Unsupported: a.GetStatus() == gvproto.Attachment_NOT_SUPPORTED}
			for _, meta := range a.GetMetadata() {
				if meta.GetSize() == gvproto.Attachment_Metadata_ORIGINAL || meta.GetWidth() > media.Width {
					media.Width, media.Height = meta.GetWidth(), meta.GetHeight()
				}
			}
			out.Media = append(out.Media, media)
		}
		if len(out.Media) > 0 {
			out.Kind = "media"
		}
	}
	if out.Text != "" || len(out.Media) > 0 {
		return out
	}
	switch m.GetType() {
	case gvproto.Message_MISSED_CALL:
		out.Kind = "missedCall"
		out.Text = "Missed call"
	case gvproto.Message_INCOMING_CALL, gvproto.Message_OUTGOING_CALL:
		out.Kind = "call"
		out.Text = callText(m.GetDurationSeconds())
	case gvproto.Message_OUTGOING_CALL_CANCELLED:
		out.Kind = "call"
		out.Text = "Cancelled call"
	case gvproto.Message_VOICEMAIL:
		out.Kind = "voicemail"
		out.Text = "Voicemail"
		if transcript := transcriptText(m.GetTranscript()); transcript != "" {
			out.Text = "Voicemail: " + transcript
		}
	default:
		out.Kind = "unsupported"
	}
	return out
}

func callText(seconds float32) string {
	s := int(seconds + 0.5)
	if s <= 0 {
		return "Call"
	}
	if s < 60 {
		return fmt.Sprintf("Call, %d s", s)
	}
	return fmt.Sprintf("Call, %d min %d s", s/60, s%60)
}

func transcriptText(t *gvproto.Message_Transcript) string {
	var b strings.Builder
	for _, token := range t.GetTokens() {
		b.Write(token.GetText())
	}
	return strings.TrimSpace(b.String())
}

// ThreadIDFor is the thread id Google Voice uses for one phone number.
func ThreadIDFor(e164 string) string { return "t." + e164 }
