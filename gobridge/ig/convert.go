// SPDX-License-Identifier: AGPL-3.0-or-later

package ig

import (
	"encoding/json"
	"strconv"
	"strings"

	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
)

// The JSON shapes Kotlin reads. Timestamps are Unix milliseconds. Ids are Instagram's:
// a thread is its "thread fbid" (the number Instagram keys events by); a person is their
// messaging user id (the number messages carry as the sender).

// Thread is one conversation: a person or a group.
type Thread struct {
	ID       string `json:"id"`     // thread fbid
	LongID   string `json:"longId"` // ig_thread_igid, which sends and read markers need
	Title    string `json:"title,omitempty"`
	ImageURL string `json:"imageUrl,omitempty"`
	IsGroup  bool   `json:"isGroup"`
	// Instagram's inbox tabs and the request queue (UI_DESIGN.md 6.4): the raw folder
	// names the thread carries; Kotlin reads "PENDING" and "SPAM" as requests, a folder
	// naming "GENERAL" as General, the rest as Primary.
	Folder       string `json:"folder,omitempty"`
	SystemFolder string `json:"systemFolder,omitempty"`
	FolderTag    string `json:"folderTag,omitempty"`
	LastAt       int64  `json:"lastMessageAt"`
	MarkedUnread bool   `json:"markedUnread"`
	Muted        bool   `json:"muted"`
	Pinned       bool   `json:"pinned"`
	// When the account itself last read the thread, from its read receipt; 0 when unknown.
	ReadAt int64  `json:"readAt"`
	Users  []User `json:"users"`
	// Admins, by user id, for groups.
	Admins []string `json:"admins,omitempty"`
	// The newest messages the thread listing carried, newest first.
	Messages []Message `json:"messages"`
}

// User is a person on Instagram.
type User struct {
	ID       string `json:"id"` // messaging user id (interop_messaging_user_fbid)
	IGID     string `json:"igid,omitempty"`
	Username string `json:"username,omitempty"`
	Name     string `json:"name,omitempty"`
	Picture  string `json:"picture,omitempty"`
	IsMe     bool   `json:"isMe"`
}

// Message is one message, flattened.
type Message struct {
	ID        string `json:"id"`
	Thread    string `json:"thread"`
	Sender    string `json:"sender"`
	Timestamp int64  `json:"timestamp"`
	// "text", "image", "video", "gif", "voice", "sticker", "share", "system", or "unsupported".
	Kind      string     `json:"kind"`
	Text      string     `json:"text,omitempty"`
	Media     []Media    `json:"media,omitempty"`
	Share     *Share     `json:"share,omitempty"`
	Reactions []Reaction `json:"reactions,omitempty"`
	ReplyTo   string     `json:"replyTo,omitempty"`
	ReplyText string     `json:"replyText,omitempty"`
	Edited    bool       `json:"edited"`
	ViewOnce  bool       `json:"viewOnce"`
	// View-once media Instagram will not hand out again ("viewed", "replayed").
	ViewOnceGone string `json:"viewOnceGone,omitempty"`
	// What Instagram sent for a view-once message that came without a file, for the phone's
	// diagnostic file (owner, 2026-10-05: unviewed view-once photos arrived as "gone").
	Raw    string `json:"raw,omitempty"`
	Unsent bool   `json:"unsent"`
}

// Media is one picture, video, GIF, voice note, or sticker: the address to fetch it from.
type Media struct {
	Kind       string `json:"kind"` // "image", "video", "gif", "voice", "sticker"
	URL        string `json:"url"`
	PreviewURL string `json:"previewUrl,omitempty"`
	Mime       string `json:"mime,omitempty"`
	Width      int    `json:"width,omitempty"`
	Height     int    `json:"height,omitempty"`
	DurationMS int    `json:"durationMs,omitempty"`
	ID         string `json:"id,omitempty"`
}

// Share is a post, reel, story, or link someone sent.
type Share struct {
	Title    string `json:"title,omitempty"`
	Subtitle string `json:"subtitle,omitempty"`
	URL      string `json:"url,omitempty"`
	Preview  string `json:"previewUrl,omitempty"`
	Kind     string `json:"kind,omitempty"`
}

// Reaction is one emoji from one person.
type Reaction struct {
	Emoji     string `json:"emoji"`
	Sender    string `json:"sender"`
	Timestamp int64  `json:"timestamp"`
}

// --- conversion ---

func convertThread(info *slidetypes.ThreadInfo, viewer int64) Thread {
	t := Thread{
		ID:           info.ID,
		LongID:       info.ThreadID,
		Title:        info.ThreadTitle,
		ImageURL:     info.ThreadImageURL,
		IsGroup:      info.IsGroup,
		Folder:       info.Folder,
		SystemFolder: info.SystemFolder,
		FolderTag:    info.MessagingFolderTag,
		LastAt:       info.LastActivityTimestampMS.UnixMilli(),
		MarkedUnread: info.MarkedAsUnread,
		Admins:       append([]string{}, info.AdminUserIDs...),
		Users:        []User{},
		Messages:     []Message{},
	}
	if t.ID == "" && info.ThreadKey != 0 {
		t.ID = strconv.FormatInt(info.ThreadKey, 10)
	}
	if info.IsMuted != nil {
		t.Muted = *info.IsMuted
	}
	if info.IsPin != nil {
		t.Pinned = *info.IsPin
	}
	for _, r := range info.SlideReadReceipts {
		if r.ParticipantFBID == viewer {
			t.ReadAt = r.WatermarkTimestampMS.UnixMilli()
		}
	}
	for _, u := range info.Users {
		if u != nil {
			t.Users = append(t.Users, convertUser(u, viewer))
		}
	}
	if info.Viewer != nil {
		t.Users = append(t.Users, convertUser(info.Viewer, viewer))
	}
	if info.SlideMessages != nil {
		for _, edge := range info.SlideMessages.Edges {
			if edge.Node != nil {
				t.Messages = append(t.Messages, convertMessage(edge.Node, t.ID))
			}
		}
	}
	return t
}

func convertUser(u *slidetypes.User, viewer int64) User {
	return User{
		ID:       strconv.FormatInt(u.InteropMessagingUserFBID, 10),
		IGID:     u.ID,
		Username: u.Username,
		Name:     u.FullName,
		Picture:  u.ProfilePicURL,
		IsMe:     viewer != 0 && u.InteropMessagingUserFBID == viewer,
	}
}

// convertMessage flattens a message; thread is the fbid when the message does not carry one.
func convertMessage(msg *slidetypes.Message, thread string) Message {
	out := Message{
		ID:        msg.MessageID,
		Thread:    msg.ThreadFBID,
		Sender:    strconv.FormatInt(msg.SenderFBID, 10),
		Timestamp: msg.TimestampMS.UnixMilli(),
		Kind:      "text",
		Text:      msg.TextBody,
		ReplyTo:   msg.RepliedToMessageID,
		Edited:    len(msg.SlideEditHistory) > 0,
	}
	if out.Thread == "" {
		out.Thread = thread
	}
	if msg.RepliedToMessage != nil {
		out.ReplyText = quoteText(msg.RepliedToMessage)
	}
	for _, r := range msg.Reactions {
		if r == nil || r.Reaction == "" {
			continue
		}
		out.Reactions = append(out.Reactions, Reaction{
			Emoji:     r.Reaction,
			Sender:    strconv.FormatInt(r.SenderFBID, 10),
			Timestamp: r.ReactionTimestampMS.UnixMilli(),
		})
	}
	fill(&out, msg)
	return out
}

func fill(out *Message, msg *slidetypes.Message) {
	switch content := msg.Content.Content.(type) {
	case *slidetypes.MessageContentText:
		if out.Text == "" {
			out.Text = content.TextBody
		}
	case *slidetypes.MessageContentAdminText:
		out.Kind = "system"
		parts := make([]string, 0, len(content.TextFragments))
		for _, f := range content.TextFragments {
			parts = append(parts, f.Plaintext)
		}
		if text := strings.Join(parts, ""); text != "" {
			out.Text = text
		} else if out.Text == "" {
			out.Text = msg.IGDSnippet
		}
	case *slidetypes.MessageContentImage:
		out.Kind = "image"
		for _, a := range content.Attachments {
			out.Media = append(out.Media, pictureOrVideo(a, "image"))
		}
	case *slidetypes.MessageContentVideo:
		out.Kind = "video"
		for _, a := range content.Videos {
			out.Media = append(out.Media, pictureOrVideo(a, "video"))
		}
	case *slidetypes.MessageContentMultiMedia:
		out.Kind = "image"
		for _, a := range content.Attachments {
			kind := "image"
			if a.DashManifest != "" {
				kind = "video"
				out.Kind = "video"
			}
			out.Media = append(out.Media, pictureOrVideo(a, kind))
		}
	case *slidetypes.MessageContentRavenImage:
		out.Kind = "image"
		raven(out, content.Attachment, content.ViewMode, "image")
		noteGone(out, msg, content.Unrecognized)
	case *slidetypes.MessageContentRavenVideo:
		out.Kind = "video"
		raven(out, content.Attachment, content.ViewMode, "video")
		noteGone(out, msg, content.Unrecognized)
	case *slidetypes.MessageContentAudio:
		out.Kind = "voice"
		for _, a := range content.AudioAttachments {
			out.Media = append(out.Media, Media{Kind: "voice", URL: a.AttachmentCDNURL, Mime: "audio/mp4",
				DurationMS: a.PlayableDurationMS, ID: a.AttachmentFBID})
		}
	case *slidetypes.MessageContentAnimatedMedia:
		out.Kind = "gif"
		for _, a := range content.AnimatedMedia {
			kind := "gif"
			if a.IsSticker {
				kind = "sticker"
				out.Kind = "sticker"
			}
			url, mime := a.AttachmentWebpURL, "image/webp"
			if url == "" {
				url, mime = a.AttachmentMP4URL, "video/mp4"
			}
			out.Media = append(out.Media, Media{Kind: kind, URL: url, PreviewURL: a.PreviewCDNURL, Mime: mime,
				Width: a.PreviewWidth, Height: a.PreviewHeight})
		}
	case *slidetypes.MessageContentSticker:
		out.Kind = "sticker"
		out.Media = append(out.Media, Media{Kind: "sticker", URL: content.PreviewURL, Mime: "image/webp",
			Width: content.PreviewWidth, Height: content.PreviewHeight})
	case *slidetypes.MessageContentXMA:
		out.Kind = "share"
		if content.XMA != nil {
			out.Share = convertShare(content.XMA)
		}
		if out.Text == "" {
			out.Text = content.XMATextBody
		}
	case *slidetypes.MessageContentMusicSticker:
		out.Kind = "share"
		out.Share = &Share{Title: content.AudioTrack.Title, Subtitle: content.AudioTrack.DisplayArtist,
			URL: content.AttributionLink, Preview: content.PreviewURL, Kind: "music"}
	default:
		if out.Text == "" {
			out.Kind = "unsupported"
			out.Text = msg.IGDSnippet
		}
	}
}

func pictureOrVideo(a *slidetypes.Attachment, kind string) Media {
	mime := "image/jpeg"
	if kind == "video" {
		mime = "video/mp4"
	}
	return Media{Kind: kind, URL: a.AttachmentCDNURL, PreviewURL: a.PreviewCDNURL, Mime: mime,
		Width: a.PreviewWidth, Height: a.PreviewHeight, ID: a.AttachmentFBID}
}

// raven is a photo or video taken in the chat's camera. Instagram sends it in one of
// three modes: view once, allow replay, or keep in chat. The first two are ephemeral and,
// once viewed or replayed, come without an attachment: those are gone. Anything else is
// an ordinary photo or video; its address may be missing from the live event and is
// then fetched by id when the file is wanted (owner, 2026-10-04: a kept video was called
// "view-once ... no longer shows").
func raven(out *Message, a *slidetypes.Attachment, mode slidetypes.RavenViewMode, kind string) {
	out.ViewOnce = mode != slidetypes.RavenViewModeKeepInChat
	if a == nil {
		if mode.ViewType() != "" {
			out.ViewOnceGone = mode.ViewType()
		} else {
			out.ViewOnceGone = "gone"
		}
		return
	}
	media := pictureOrVideo(a, kind)
	if media.URL == "" {
		media.URL = a.AttachmentCDNFallbackURL
	}
	if media.URL == "" && kind == "image" {
		media.URL = a.PreviewCDNURL
	}
	out.Media = append(out.Media, media)
}

// noteGone keeps whatever else Instagram said about a view-once message that came without
// a file: the fields the library does not know, on the content and on the message, plus
// the message's content type. The phone's diagnostic file shows them, so the next such
// message says what Instagram actually sends for an unviewed one.
func noteGone(out *Message, msg *slidetypes.Message, content map[string]any) {
	if out.ViewOnceGone == "" {
		return
	}
	raw := map[string]any{"contentType": msg.ContentType, "typename": msg.Typename}
	if len(content) > 0 {
		raw["content"] = content
	}
	if len(msg.Unrecognized) > 0 {
		raw["message"] = msg.Unrecognized
	}
	if b, err := json.Marshal(raw); err == nil {
		out.Raw = string(b)
	}
}

func convertShare(x *slidetypes.XMAContent) *Share {
	s := &Share{Title: x.TitleText, Subtitle: x.SubtitleText, URL: x.TargetURL, Kind: x.Typename}
	if s.Title == "" {
		s.Title = x.XMATitle
	}
	if s.Title == "" {
		s.Title = x.HeaderTitleText
	}
	if s.Subtitle == "" {
		s.Subtitle = x.CaptionBodyText
	}
	if x.PreviewImage != nil {
		s.Preview = x.PreviewImage.URL
	} else if x.XMAPreviewImage != nil {
		s.Preview = x.XMAPreviewImage.URL
	}
	return s
}

func quoteText(msg *slidetypes.Message) string {
	var q Message
	q.Text = msg.TextBody
	fill(&q, msg)
	if q.Text != "" {
		return q.Text
	}
	switch q.Kind {
	case "image":
		return "Photo"
	case "video":
		return "Video"
	case "voice":
		return "Voice message"
	case "gif", "sticker":
		return "Sticker"
	case "share":
		return "Shared post"
	}
	return ""
}
