// SPDX-License-Identifier: AGPL-3.0-or-later

package fb

import (
	"sort"
	"strconv"

	"go.mau.fi/mautrix-meta/pkg/messagix/table"
)

// The JSON shapes Kotlin reads. Timestamps are Unix milliseconds. Ids are Messenger's:
// a thread is its thread key (for a one-to-one chat, the other person's user id); a
// message is Messenger's message id ("mid.$..."); a person is their user id.

// Thread is one conversation: a person or a group.
type Thread struct {
	ID       string `json:"id"`
	Title    string `json:"title,omitempty"`
	ImageURL string `json:"imageUrl,omitempty"`
	IsGroup  bool   `json:"isGroup"`
	// Messenger's folder: "inbox", "pending" (a message request), "other" (a request
	// Messenger filed as "you may know"), "spam", or "archived".
	Folder string `json:"folder,omitempty"`
	LastAt int64  `json:"lastMessageAt"`
	// When the account itself last read the thread; 0 when unknown.
	ReadAt int64  `json:"readAt"`
	Muted  bool   `json:"muted"`
	Users  []User `json:"users"`
	// Admins, by user id, for groups.
	Admins []string `json:"admins,omitempty"`
	// The newest messages Messenger has handed over for the thread, newest first.
	Messages []Message `json:"messages"`
	// Whether Messenger has older messages than the oldest one here.
	MoreBefore bool `json:"moreBefore"`

	threadType table.ThreadType
	members    map[int64]*User
	// The web thread key (0 for a chat the encrypted channel started before the web
	// listing named it) and the chat's id on the encrypted channel (0 when not encrypted).
	fbKey int64
	jid   int64
	// Messages from the channel not yet marked read there.
	waUnread []waRef
	// Whether the web side was asked once for history under both of the chat's ids.
	askedWeb bool
}

// User is a person on Messenger.
type User struct {
	ID      string `json:"id"`
	Name    string `json:"name,omitempty"`
	Picture string `json:"picture,omitempty"`
	IsMe    bool   `json:"isMe"`

	admin bool
}

// Message is one message, flattened.
type Message struct {
	ID        string `json:"id"`
	Thread    string `json:"thread"`
	Sender    string `json:"sender"`
	Timestamp int64  `json:"timestamp"`
	// "text", "image", "video", "gif", "voice", "file", "sticker", "share", "system", or "unsupported".
	Kind      string     `json:"kind"`
	Text      string     `json:"text,omitempty"`
	Media     []Media    `json:"media,omitempty"`
	Share     *Share     `json:"share,omitempty"`
	Reactions []Reaction `json:"reactions,omitempty"`
	ReplyTo   string     `json:"replyTo,omitempty"`
	ReplyText string     `json:"replyText,omitempty"`
	Edited    bool       `json:"edited"`
	Unsent    bool       `json:"unsent"`
}

// Media is one picture, video, GIF, voice note, file, or sticker: the address to fetch it from.
type Media struct {
	Kind       string `json:"kind"` // "image", "video", "gif", "voice", "file", "sticker"
	URL        string `json:"url"`
	PreviewURL string `json:"previewUrl,omitempty"`
	Mime       string `json:"mime,omitempty"`
	FileName   string `json:"fileName,omitempty"`
	Size       int64  `json:"size,omitempty"`
	Width      int    `json:"width,omitempty"`
	Height     int    `json:"height,omitempty"`
	DurationMS int    `json:"durationMs,omitempty"`
	ID         string `json:"id,omitempty"`
}

// Share is a link, post, or other card someone sent.
type Share struct {
	Title    string `json:"title,omitempty"`
	Subtitle string `json:"subtitle,omitempty"`
	URL      string `json:"url,omitempty"`
	Preview  string `json:"previewUrl,omitempty"`
}

// Reaction is one emoji from one person.
type Reaction struct {
	Emoji     string `json:"emoji"`
	Sender    string `json:"sender"`
	Timestamp int64  `json:"timestamp"`
}

// --- conversion ---

func id(n int64) string { return strconv.FormatInt(n, 10) }

// threadFrom starts a Thread from a full listing row.
func threadFrom(row *table.LSDeleteThenInsertThread) *Thread {
	t := &Thread{
		ID:         id(row.ThreadKey),
		Title:      row.ThreadName,
		ImageURL:   row.ThreadPictureUrl,
		IsGroup:    !row.ThreadType.IsOneToOne(),
		Folder:     row.FolderName,
		LastAt:     row.LastActivityTimestampMs,
		ReadAt:     row.LastReadWatermarkTimestampMs,
		Muted:      row.MuteExpireTimeMs != 0,
		threadType: row.ThreadType,
		members:    map[int64]*User{},
	}
	return t
}

// threadFromUpdate starts a Thread from a partial listing row.
func threadFromUpdate(row *table.LSUpdateOrInsertThread) *Thread {
	return &Thread{
		ID:         id(row.ThreadKey),
		Title:      row.ThreadName,
		ImageURL:   row.ThreadPictureUrl,
		IsGroup:    !row.ThreadType.IsOneToOne(),
		Folder:     row.FolderName,
		LastAt:     row.LastActivityTimestampMs,
		ReadAt:     row.LastReadWatermarkTimestampMs,
		Muted:      row.MuteExpireTimeMs != 0,
		threadType: row.ThreadType,
		members:    map[int64]*User{},
	}
}

// merge keeps what an older copy knew that a newer row left out.
func (t *Thread) merge(old *Thread) {
	if old == nil {
		return
	}
	if t.Title == "" {
		t.Title = old.Title
	}
	if t.ImageURL == "" {
		t.ImageURL = old.ImageURL
	}
	if t.Folder == "" {
		t.Folder = old.Folder
	}
	if t.LastAt == 0 {
		t.LastAt = old.LastAt
	}
	if t.ReadAt == 0 {
		t.ReadAt = old.ReadAt
	}
	if t.threadType == 0 {
		t.threadType = old.threadType
		t.IsGroup = old.IsGroup
	}
	if t.jid == 0 {
		t.jid = old.jid
	}
	if t.fbKey == 0 {
		t.fbKey = old.fbKey
	}
	t.waUnread = append(t.waUnread, old.waUnread...)
	for key, user := range old.members {
		if _, known := t.members[key]; !known {
			t.members[key] = user
		}
	}
	t.Messages = old.Messages
	t.MoreBefore = old.MoreBefore
	t.Admins = old.Admins
}

// addMessages keeps the newest messages of a thread, newest first, without repeats.
func (t *Thread) addMessages(messages []Message) {
	byID := make(map[string]int, len(t.Messages))
	for i, m := range t.Messages {
		byID[m.ID] = i
	}
	for _, m := range messages {
		if i, known := byID[m.ID]; known {
			t.Messages[i] = m
			continue
		}
		byID[m.ID] = len(t.Messages)
		t.Messages = append(t.Messages, m)
	}
	sort.SliceStable(t.Messages, func(i, j int) bool { return t.Messages[i].Timestamp > t.Messages[j].Timestamp })
	if len(t.Messages) > keptMessages {
		t.Messages = t.Messages[:keptMessages]
	}
}

// view fills the users from the members and the people table, with the account itself first.
func (t *Thread) view(own int64, people map[int64]*User) Thread {
	out := *t
	if t.jid != 0 {
		// Shown under its id on the encrypted channel: the other person's id for a
		// one-to-one chat, which is also what the channel's messages name.
		out.ID = id(t.jid)
		out.MoreBefore = false
	}
	out.Users = []User{}
	out.Admins = []string{}
	if own != 0 {
		out.Users = append(out.Users, User{ID: id(own), IsMe: true})
	}
	keys := make([]int64, 0, len(t.members))
	for key := range t.members {
		keys = append(keys, key)
	}
	sort.Slice(keys, func(i, j int) bool { return keys[i] < keys[j] })
	for _, key := range keys {
		if key == own {
			continue
		}
		member := *t.members[key]
		if known := people[key]; known != nil {
			if member.Name == "" {
				member.Name = known.Name
			}
			if member.Picture == "" {
				member.Picture = known.Picture
			}
		}
		out.Users = append(out.Users, member)
	}
	if !t.IsGroup {
		// A one-to-one thread's shown id is the other person's id (the web key, or the id on
		// the encrypted channel), and its name and picture are theirs.
		other, _ := strconv.ParseInt(out.ID, 10, 64)
		found := false
		for i := range out.Users {
			if out.Users[i].ID == out.ID {
				found = true
				if out.Users[i].Name == "" {
					out.Users[i].Name = t.Title
				}
				if out.Users[i].Picture == "" {
					out.Users[i].Picture = t.ImageURL
				}
			}
		}
		if !found && other != 0 && other != own {
			out.Users = append(out.Users, User{ID: out.ID, Name: t.Title, Picture: t.ImageURL})
		}
	}
	for _, key := range keys {
		if t.members[key].admin {
			out.Admins = append(out.Admins, id(key))
		}
	}
	if out.Messages == nil {
		out.Messages = []Message{}
	}
	return out
}

func convertMessage(m *table.WrappedMessage) Message {
	out := Message{
		ID:        m.MessageId,
		Thread:    id(m.ThreadKey),
		Sender:    id(m.SenderId),
		Timestamp: m.TimestampMs,
		Kind:      "text",
		Text:      m.Text,
		ReplyTo:   m.ReplySourceId,
		Edited:    m.EditCount > 0,
		Unsent:    m.IsUnsent,
	}
	if m.ReplySourceId != "" {
		out.ReplyText = m.ReplyMessageText
		if out.ReplyText == "" {
			out.ReplyText = m.ReplySnippet
		}
	}
	for _, r := range m.Reactions {
		out.Reactions = append(out.Reactions, Reaction{Emoji: r.Reaction, Sender: id(r.ActorId), Timestamp: r.TimestampMs})
	}
	if m.IsUnsent {
		out.Text = ""
		return out
	}
	if m.IsAdminMessage {
		out.Kind = "system"
		return out
	}
	seen := map[string]bool{}
	for _, blob := range m.BlobAttachments {
		if blob.AttachmentFbid != "" && seen[blob.AttachmentFbid] {
			continue // Messenger sometimes lists the same file twice.
		}
		seen[blob.AttachmentFbid] = true
		out.Media = append(out.Media, blobMedia(blob))
	}
	for _, att := range m.Attachments {
		if att.AttachmentFbid != "" && seen[att.AttachmentFbid] {
			continue
		}
		seen[att.AttachmentFbid] = true
		out.Media = append(out.Media, legacyMedia(att))
	}
	for _, sticker := range m.Stickers {
		out.Media = append(out.Media, stickerMedia(sticker))
	}
	for _, xma := range m.XMAAttachments {
		share, media := xmaContent(xma)
		if media != nil {
			out.Media = append(out.Media, *media)
		} else if share != nil && out.Share == nil {
			out.Share = share
		}
	}
	switch {
	case len(out.Media) > 0:
		out.Kind = out.Media[0].Kind
	case out.Share != nil:
		out.Kind = "share"
	case out.Text == "":
		out.Kind = "unsupported"
	}
	return out
}

func mediaKind(kind table.AttachmentType, mime string) string {
	switch kind {
	case table.AttachmentTypeImage:
		return "image"
	case table.AttachmentTypeAnimatedImage:
		return "gif"
	case table.AttachmentTypeVideo:
		return "video"
	case table.AttachmentTypeAudio:
		return "voice"
	case table.AttachmentTypeSticker:
		return "sticker"
	case table.AttachmentTypeFile:
		return "file"
	}
	switch {
	case mime == "image/gif":
		return "gif"
	case len(mime) > 6 && mime[:6] == "image/":
		return "image"
	case len(mime) > 6 && mime[:6] == "video/":
		return "video"
	case len(mime) > 6 && mime[:6] == "audio/":
		return "voice"
	}
	return "file"
}

func blobMedia(b *table.LSInsertBlobAttachment) Media {
	url, mime := b.PlayableUrl, b.PlayableUrlMimeType
	if mime == "" {
		mime = b.AttachmentMimeType
	}
	width, height := 0, 0
	if url == "" {
		url, mime = b.PreviewUrl, b.PreviewUrlMimeType
		width, height = int(b.PreviewWidth), int(b.PreviewHeight)
	}
	return Media{
		Kind:       mediaKind(b.AttachmentType, mime),
		URL:        url,
		PreviewURL: b.PreviewUrl,
		Mime:       mime,
		FileName:   b.Filename,
		Size:       b.Filesize,
		Width:      width,
		Height:     height,
		DurationMS: int(b.PlayableDurationMs),
		ID:         b.AttachmentFbid,
	}
}

func legacyMedia(a *table.LSInsertAttachment) Media {
	url, mime := a.PlayableUrl, a.PlayableUrlMimeType
	if mime == "" {
		mime = a.AttachmentMimeType
	}
	width, height := 0, 0
	if url == "" {
		url, mime = a.PreviewUrl, a.PreviewUrlMimeType
		width, height = int(a.PreviewWidth), int(a.PreviewHeight)
	}
	return Media{
		Kind:       mediaKind(a.AttachmentType, mime),
		URL:        url,
		PreviewURL: a.PreviewUrl,
		Mime:       mime,
		FileName:   a.Filename,
		Size:       a.Filesize,
		Width:      width,
		Height:     height,
		DurationMS: int(a.PlayableDurationMs),
		ID:         a.AttachmentFbid,
	}
}

func stickerMedia(s *table.LSInsertStickerAttachment) Media {
	url, mime := s.PlayableUrl, s.PlayableUrlMimeType
	if url == "" {
		url, mime = s.PreviewUrl, s.PreviewUrlMimeType
	}
	if url == "" {
		url, mime = s.ImageUrl, s.ImageUrlMimeType
	}
	return Media{Kind: "sticker", URL: url, Mime: mime, Width: int(s.PreviewWidth), Height: int(s.PreviewHeight),
		ID: s.AttachmentFbid}
}

// xmaContent reads a card: a GIF or clip plays as media; anything else is a share.
func xmaContent(x *table.WrappedXMA) (*Share, *Media) {
	if x.PlayableUrl != "" && (x.ShouldAutoplayVideo || x.AttachmentType == table.AttachmentTypeAnimatedImage ||
		x.AttachmentType == table.AttachmentTypeVideo) {
		kind := "video"
		if x.ShouldAutoplayVideo || x.AttachmentType == table.AttachmentTypeAnimatedImage {
			kind = "gif"
		}
		return nil, &Media{Kind: kind, URL: x.PlayableUrl, PreviewURL: x.PreviewUrl, Mime: x.PlayableUrlMimeType,
			Width: int(x.PreviewWidth), Height: int(x.PreviewHeight), ID: x.AttachmentFbid}
	}
	share := &Share{Title: x.TitleText, Subtitle: x.SubtitleText, Preview: x.PreviewUrl}
	if share.Preview == "" {
		share.Preview = x.ImageUrl
	}
	if x.CTA != nil && x.CTA.ActionUrl != "" {
		share.URL = x.CTA.ActionUrl
	} else {
		share.URL = x.ActionUrl
	}
	if share.Title == "" && share.URL == "" && share.Preview == "" {
		if x.PreviewUrl != "" || x.ImageUrl != "" {
			url := x.PreviewUrl
			if url == "" {
				url = x.ImageUrl
			}
			return nil, &Media{Kind: "image", URL: url, Mime: x.PreviewUrlMimeType, Width: int(x.PreviewWidth),
				Height: int(x.PreviewHeight), ID: x.AttachmentFbid}
		}
		return nil, nil
	}
	return share, nil
}

const keptMessages = 50
