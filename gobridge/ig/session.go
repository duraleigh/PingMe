// SPDX-License-Identifier: AGPL-3.0-or-later

package ig

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-meta/pkg/instameow"
	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
	"go.mau.fi/mautrix-meta/pkg/messagix/cookies"
	"go.mau.fi/mautrix-meta/pkg/messagix/httpclient"
	"go.mau.fi/mautrix-meta/pkg/messagix/methods"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
	"go.mau.fi/util/exhttp"
)

// EventSink receives every event as one JSON object. Kotlin implements it. Calls come
// from the client's own goroutines and must return quickly.
type EventSink interface {
	OnEvent(json string)
}

// Errors Kotlin tells apart by the code before the colon.
var (
	ErrLoggedOut    = errors.New("LOGGED_OUT: Instagram no longer accepts this sign-in")
	ErrNotConnected = errors.New("NOT_CONNECTED: not connected to Instagram")
	ErrRejected     = errors.New("REJECTED: Instagram refused the request")
)

const requestTimeout = 60 * time.Second

// Session is one signed-in Instagram account: the cookies, the live connection, and
// every request on it.
type Session struct {
	client  *instameow.Client
	cookies *cookies.Cookies
	sink    EventSink
	log     zerolog.Logger

	mu      sync.Mutex
	viewer  int64
	ids     map[string]*instameow.ThreadIGIDs
	cancel  context.CancelFunc
	threads map[string]string // thread fbid -> long id, from listings
}

// NewSession takes the instagram.com cookies as a JSON object of name to value.
func NewSession(cookiesJSON string, sink EventSink) (*Session, error) {
	var raw map[string]string
	if err := json.Unmarshal([]byte(cookiesJSON), &raw); err != nil {
		return nil, fmt.Errorf("cookies are not a JSON object: %w", err)
	}
	jar := &cookies.Cookies{Platform: types.Instagram}
	values := make(map[cookies.MetaCookieName]string, len(raw))
	for name, value := range raw {
		values[cookies.MetaCookieName(name)] = value
	}
	jar.UpdateValues(values)
	if missing := jar.GetMissingCookieNames(); len(missing) > 0 {
		return nil, fmt.Errorf("%w: sign-in cookies are incomplete: %v", ErrLoggedOut, missing)
	}
	s := &Session{
		cookies: jar,
		sink:    sink,
		log:     newLogger("instameow"),
		ids:     make(map[string]*instameow.ThreadIGIDs),
		threads: make(map[string]string),
	}
	s.client = instameow.NewClient(instameow.ClientParams{
		Cookies:      jar,
		Log:          s.log,
		Settings:     exhttp.SensibleClientSettings,
		EventHandler: s.handleEvent,
	})
	return s, nil
}

// CookiesJSON is the cookies as they stand now, to save after Instagram refreshed them.
func (s *Session) CookiesJSON() string {
	all := s.cookies.GetAll()
	out := make(map[string]string, len(all))
	for name, value := range all {
		out[string(name)] = value
	}
	data, _ := json.Marshal(out)
	return string(data)
}

// OwnID is the account's own messaging user id, once connected.
func (s *Session) OwnID() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.viewer == 0 {
		return ""
	}
	return strconv.FormatInt(s.viewer, 10)
}

// Connect loads the inbox (emitting a "thread" event per conversation) and then keeps the
// live connection up in the background until Disconnect.
func (s *Session) Connect() error {
	ctx, cancel := s.ctx()
	defer cancel()
	viewer, mailbox, err := s.client.LoadIndex(ctx)
	if err != nil {
		return wrap(err)
	}
	// The messaging id messages carry as their sender, which is not the Instagram user id.
	own := s.client.GetOwnFBID()
	if own == 0 && viewer != nil {
		own, _ = strconv.ParseInt(viewer.ID, 10, 64)
	}
	s.mu.Lock()
	s.viewer = own
	s.mu.Unlock()
	s.emit(map[string]any{"type": "connected", "id": s.OwnID(), "cookies": s.CookiesJSON()})
	if mailbox != nil {
		s.emitThreads(mailbox.ThreadsByFolder.Edges, mailbox.ThreadsByFolder.PageInfo)
	}
	bg, stop := context.WithCancel(s.log.WithContext(context.Background()))
	s.mu.Lock()
	if s.cancel != nil {
		s.cancel()
	}
	s.cancel = stop
	s.mu.Unlock()
	go s.client.Connect(bg)
	return nil
}

// Disconnect closes the live connection.
func (s *Session) Disconnect() {
	s.mu.Lock()
	if s.cancel != nil {
		s.cancel()
		s.cancel = nil
	}
	s.mu.Unlock()
	s.client.Disconnect()
}

// ListThreads returns a page of the inbox as a JSON object {threads, nextCursor} for
// folder "INBOX" (Primary and General) or "PENDING" (requests).
func (s *Session) ListThreads(folder, cursor string) (string, error) {
	ctx, cancel := s.ctx()
	defer cancel()
	req := slidetypes.MakePaginateMailboxRequest(s.viewerID(), cursor, folder, nil)
	resp, err := s.client.PaginateMailbox(ctx, req)
	if err != nil {
		return "", wrap(err)
	}
	page := struct {
		Threads    []Thread `json:"threads"`
		NextCursor string   `json:"nextCursor,omitempty"`
	}{Threads: []Thread{}}
	if resp.Mailbox != nil {
		for _, edge := range resp.Mailbox.ThreadsByFolder.Edges {
			if info := edge.Node.AsIGDirectThread; info != nil {
				page.Threads = append(page.Threads, s.remember(convertThread(info, s.viewerID())))
			}
		}
		if resp.Mailbox.ThreadsByFolder.PageInfo.HasNextPage {
			page.NextCursor = resp.Mailbox.ThreadsByFolder.PageInfo.EndCursor
		}
	}
	return marshal(page)
}

// Thread returns one conversation with its newest messages.
func (s *Session) Thread(fbid string) (string, error) {
	ctx, cancel := s.ctx()
	defer cancel()
	resp, err := s.client.GetThread(ctx, &slidetypes.GetThreadInfoRequest{ThreadFBID: fbid, InitialMessagePageCount: pageSize})
	if err != nil {
		return "", wrap(err)
	}
	info := resp.ThreadInfo.AsIGDirectThread
	if info == nil {
		return "", fmt.Errorf("%w: no such thread", ErrRejected)
	}
	return marshal(s.remember(convertThread(info, s.viewerID())))
}

// Messages returns up to count messages older than olderThan (the newest when empty),
// newest first, as a JSON array of Message.
func (s *Session) Messages(fbid, olderThan string, count int) (string, error) {
	ctx, cancel := s.ctx()
	defer cancel()
	req := &slidetypes.PaginateMessagesRequest{ThreadID: fbid, FirstN: count, InitialMessagePageCount: count}
	if olderThan != "" {
		req.OlderThanMessageID = &olderThan
	}
	resp, err := s.client.PaginateMessages(ctx, req)
	if err != nil {
		return "", wrap(err)
	}
	messages := []Message{}
	if resp.ThreadInfo.AsIGDirectThread != nil && resp.ThreadInfo.AsIGDirectThread.Messages != nil {
		for _, edge := range resp.ThreadInfo.AsIGDirectThread.Messages.Edges {
			if edge.Node != nil {
				messages = append(messages, convertMessage(edge.Node, fbid))
			}
		}
	}
	return marshal(messages)
}

// MediaURL returns a current address for the attachment fbid in thread, read from the
// thread's recent messages: Instagram's live event for a kept photo or video can come
// without one, and addresses expire (owner, 2026-10-04).
func (s *Session) MediaURL(fbid, attachmentID string) (string, error) {
	ctx, cancel := s.ctx()
	defer cancel()
	req := &slidetypes.PaginateMessagesRequest{ThreadID: fbid, FirstN: refreshPage, InitialMessagePageCount: refreshPage}
	resp, err := s.client.PaginateMessages(ctx, req)
	if err != nil {
		return "", wrap(err)
	}
	if resp.ThreadInfo.AsIGDirectThread == nil || resp.ThreadInfo.AsIGDirectThread.Messages == nil {
		return "", fmt.Errorf("%w: no messages for the thread", ErrRejected)
	}
	for _, edge := range resp.ThreadInfo.AsIGDirectThread.Messages.Edges {
		if edge.Node == nil {
			continue
		}
		for _, m := range convertMessage(edge.Node, fbid).Media {
			if m.ID == attachmentID && m.URL != "" {
				return m.URL, nil
			}
		}
	}
	return "", fmt.Errorf("%w: the attachment is not among the thread's recent messages", ErrRejected)
}

const refreshPage = 40

// SendText sends text, as a reply when replyTo names a message. Returns the Message as sent.
func (s *Session) SendText(fbid, text, replyTo string) (string, error) {
	ids, err := s.idsFor(fbid)
	if err != nil {
		return "", err
	}
	req := &slidetypes.SendTextRequest{
		IGThreadIGID:       &ids.LongID,
		OfflineThreadingID: strconv.FormatInt(methods.GenerateEpochID(), 10),
		Text:               slidetypes.SensitiveString{Value: text},
		Mentions:           []slidetypes.InputMention{},
		MentionedUserIDs:   []string{},
		Commands:           []slidetypes.InputCommand{},
	}
	if replyTo != "" {
		req.ReplyToMessageID = &replyTo
	}
	ctx, cancel := s.ctx()
	defer cancel()
	resp, err := s.client.SendMessage(ctx, req)
	if err != nil {
		return "", wrap(err)
	}
	return marshal(s.sent(fbid, resp.Message, "text", text))
}

// SendMedia uploads a file and sends it; kind is "image", "video", "gif", or "voice".
func (s *Session) SendMedia(fbid, path, mime, kind, fileName string, replyTo string) (string, error) {
	ids, err := s.idsFor(fbid)
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	threadID, _ := strconv.ParseInt(ids.ShortID, 10, 64)
	ctx, cancel := context.WithTimeout(s.log.WithContext(context.Background()), 5*time.Minute)
	defer cancel()
	up, err := s.client.GetHTTP().SendMercuryUploadRequest(ctx, threadID, &httpclient.MercuryUploadMedia{
		Filename:    fileName,
		MimeType:    mime,
		MediaData:   data,
		IsVoiceClip: kind == "voice",
	})
	if err != nil {
		return "", wrap(err)
	}
	attachment := int64(0)
	if up.Payload.RealMetadata != nil {
		attachment = up.Payload.RealMetadata.GetFbId()
	}
	if attachment == 0 {
		return "", fmt.Errorf("%w: the upload gave no attachment id", ErrRejected)
	}
	req := &slidetypes.SendMediaRequest{
		AttachmentFBID:     strconv.FormatInt(attachment, 10),
		ThreadID:           ids.ShortID,
		OfflineThreadingID: strconv.FormatInt(methods.GenerateEpochID(), 10),
	}
	if replyTo != "" {
		req.ReplyToMessageID = &replyTo
	}
	resp, err := s.client.SendMedia(ctx, req)
	if err != nil {
		return "", wrap(err)
	}
	sent := s.sent(fbid, resp.Message, kind, "")
	sent.Media = []Media{{Kind: kind, Mime: mime}}
	return marshal(sent)
}

// SendReaction puts emoji on a message, or takes yours away when remove is set.
func (s *Session) SendReaction(fbid, messageID, emoji string, remove bool) error {
	ids, err := s.idsFor(fbid)
	if err != nil {
		return err
	}
	status := slidetypes.ReactionStatusCreated
	if remove {
		status = slidetypes.ReactionStatusDeleted
	}
	ctx, cancel := s.ctx()
	defer cancel()
	_, err = s.client.SendReaction(ctx, &slidetypes.CreateReactionRequest{Input: slidetypes.ReactionInput{
		Emoji:          emoji,
		MessageID:      messageID,
		ReactionStatus: status,
		ThreadID:       ids.ShortID,
	}})
	return wrap(err)
}

// Unsend deletes one of your messages for everyone.
func (s *Session) Unsend(fbid, messageID string) error {
	ids, err := s.idsFor(fbid)
	if err != nil {
		return err
	}
	ctx, cancel := s.ctx()
	defer cancel()
	_, err = s.client.UnsendMessage(ctx, &slidetypes.UnsendMessageRequest{
		MessageID: messageID,
		SendData:  slidetypes.SendData{ThreadID: ids.LongID},
	})
	return wrap(err)
}

// Edit changes the text of one of your messages.
func (s *Session) Edit(fbid, messageID, text string) error {
	ids, err := s.idsFor(fbid)
	if err != nil {
		return err
	}
	ctx, cancel := s.ctx()
	defer cancel()
	_, err = s.client.EditMessage(ctx, &slidetypes.EditMessageRequest{
		ThreadID:           ids.ShortID,
		TargetMessageID:    messageID,
		Body:               slidetypes.SensitiveString{Value: text},
		OfflineThreadingID: strconv.FormatInt(methods.GenerateEpochID(), 10),
	})
	return wrap(err)
}

// MarkRead marks the thread read up to a message, the way the website does (two calls).
func (s *Session) MarkRead(fbid, messageID string, timestamp int64) error {
	ids, err := s.idsFor(fbid)
	if err != nil {
		return err
	}
	ctx, cancel := s.ctx()
	defer cancel()
	empty := ""
	_, err = s.client.MarkRead(ctx, &slidetypes.MarkReadRequest{
		Metadata: slidetypes.MarkReadMetadata{IGThreadIGID: ids.LongID},
		Data:     slidetypes.MarkReadData{MessageID: messageID, ItemID: &empty},
	})
	if err != nil {
		return wrap(err)
	}
	var at slidetypes.MarkReadData
	at.MessageID = messageID
	at.MessageTimestampMS.Time = time.UnixMilli(timestamp)
	_, err = s.client.MarkReadValidation(ctx, &slidetypes.MarkReadRequest{
		Metadata: slidetypes.MarkReadMetadata{IGThreadIGID: ids.LongID},
		Data:     at,
	})
	return wrap(err)
}

// SetTyping tells the thread you are typing, or that you stopped.
func (s *Session) SetTyping(fbid string, typing bool) error {
	ids, err := s.idsFor(fbid)
	if err != nil {
		return err
	}
	ctx, cancel := s.ctx()
	defer cancel()
	return wrap(s.client.SetTyping(ctx, ids.ShortID, typing))
}

// AcceptRequest moves a message request into the inbox.
func (s *Session) AcceptRequest(fbid string) error {
	ctx, cancel := s.ctx()
	defer cancel()
	_, err := s.client.AcceptMessageRequest(ctx, &slidetypes.AcceptMessageRequestRequest{
		ThreadID:           fbid,
		OfflineThreadingID: strconv.FormatInt(methods.GenerateEpochID(), 10),
	})
	return wrap(err)
}

// DeleteThread removes a conversation (declining a request, or leaving it behind).
func (s *Session) DeleteThread(fbid string) error {
	ctx, cancel := s.ctx()
	defer cancel()
	_, err := s.client.DeleteThread(ctx, &slidetypes.DeleteThreadRequest{ThreadID: fbid})
	return wrap(err)
}

// Download fetches a media address (a signed CDN link) into destPath.
func (s *Session) Download(url, destPath string) error {
	ctx, cancel := context.WithTimeout(s.log.WithContext(context.Background()), 5*time.Minute)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", downloadAgent)
	req.Header.Set("Accept", "*/*")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("%w: HTTP %d fetching media", ErrRejected, resp.StatusCode)
	}
	temp := destPath + ".part"
	file, err := os.Create(temp)
	if err != nil {
		return err
	}
	if _, err = io.Copy(file, resp.Body); err != nil {
		file.Close()
		os.Remove(temp)
		return err
	}
	if err = file.Close(); err != nil {
		os.Remove(temp)
		return err
	}
	return os.Rename(temp, destPath)
}

// SearchUsers finds people by name or username, as a JSON array of User.
func (s *Session) SearchUsers(query string) (string, error) {
	ctx, cancel := s.ctx()
	defer cancel()
	resp, err := s.client.SearchUsers(ctx, query)
	if err != nil {
		return "", wrap(err)
	}
	people := []User{}
	for _, r := range resp.Data.Results {
		if r == nil {
			continue
		}
		people = append(people, User{ID: strconv.FormatInt(r.InteropMessagingUserFBID, 10), IGID: r.PK,
			Username: r.Username, Name: r.FullName, Picture: r.ProfilePicURL})
	}
	return marshal(people)
}

// --- events ---

func (s *Session) handleEvent(ctx context.Context, raw slidetypes.ClientEvent) error {
	switch evt := raw.(type) {
	case *slidetypes.Connected:
		s.emit(map[string]any{"type": "live"})
	case *slidetypes.Disconnected:
		s.emit(map[string]any{"type": "disconnected", "error": evt.Error.Error(), "failures": evt.FailureCount})
	case *slidetypes.AuthError:
		s.emit(map[string]any{"type": "loggedOut", "error": evt.Error.Error()})
	case *slidetypes.ResnapshotRequired:
		s.emit(map[string]any{"type": "resync"})
	case *slidetypes.TypingNotification:
		s.emit(map[string]any{"type": "typing", "thread": evt.ThreadID, "sender": strconv.FormatInt(evt.SenderID, 10),
			"typing": evt.ActivityStatus != 0})
	case *slidetypes.Delta:
		s.handleDelta(evt)
	default:
		s.log.Trace().Type("event_type", raw).Msg("Unhandled instameow event")
	}
	return nil
}

func (s *Session) handleDelta(d *slidetypes.Delta) {
	thread := d.ThreadIGID
	switch evt := d.Data.(type) {
	case *slidetypes.NewMessageEvent:
		if evt.Message == nil {
			return
		}
		if thread == "" {
			thread = evt.Message.ThreadFBID
		}
		s.emit(map[string]any{"type": "message", "message": convertMessage(evt.Message, thread)})
	case *slidetypes.AdminMessageEvent:
		if evt.Message == nil {
			return
		}
		s.emit(map[string]any{"type": "message", "message": convertMessage(evt.Message, thread)})
	case *slidetypes.CreateReactionEvent:
		s.emit(map[string]any{"type": "reaction", "thread": thread, "message": evt.MessageID, "removed": false,
			"reaction": Reaction{Emoji: evt.Reaction.Reaction, Sender: strconv.FormatInt(evt.Reaction.SenderFBID, 10),
				Timestamp: evt.Reaction.ReactionTimestampMS.UnixMilli()}})
	case *slidetypes.DeleteReactionEvent:
		s.emit(map[string]any{"type": "reaction", "thread": thread, "message": evt.MessageID, "removed": true,
			"reaction": Reaction{Emoji: evt.Reaction.Reaction, Sender: strconv.FormatInt(evt.Reaction.SenderFBID, 10),
				Timestamp: evt.Reaction.ReactionTimestampMS.UnixMilli()}})
	case *slidetypes.EditMessageEvent:
		s.emit(map[string]any{"type": "edit", "thread": thread, "message": evt.MessageID, "text": evt.TextBody,
			"timestamp": evt.SlideEditHistoryEntry.TimestampMS.UnixMilli()})
	case *slidetypes.DeleteMessageEvent:
		s.emit(map[string]any{"type": "unsent", "thread": thread, "message": evt.MessageID})
	case *slidetypes.DeleteThreadEvent:
		s.emit(map[string]any{"type": "threadGone", "thread": thread})
	case *slidetypes.MarkReadEvent:
		s.emit(map[string]any{"type": "readByMe", "thread": thread, "timestamp": evt.ReadTimestampMS.UnixMilli()})
	case *slidetypes.MarkUnreadEvent:
		s.emit(map[string]any{"type": "unreadByMe", "thread": thread})
	case *slidetypes.ReadReceiptEvent:
		s.emit(map[string]any{"type": "readReceipt", "thread": thread,
			"sender":    strconv.FormatInt(evt.ReadReceipt.ParticipantFBID, 10),
			"timestamp": evt.ReadReceipt.WatermarkTimestampMS.UnixMilli()})
	case *slidetypes.UpdateThreadFolderEvent:
		s.emit(map[string]any{"type": "folder", "thread": thread, "folder": evt.Folder, "inboxFolder": evt.IGInboxFolder})
	case *slidetypes.UpdateThreadNameEvent, *slidetypes.UpdateThreadImageEvent, *slidetypes.ParticipantJoinEvent,
		*slidetypes.ParticipantLeaveEvent, *slidetypes.AdminChangeEvent, *slidetypes.PinThreadEvent,
		*slidetypes.MuteThreadEvent:
		// The thread changed: fetch it again, so the chat's name and members follow.
		go s.refreshThread(thread)
	default:
		s.log.Trace().Str("typename", d.TypeName).Msg("Unhandled delta")
	}
}

func (s *Session) refreshThread(fbid string) {
	if fbid == "" {
		return
	}
	json, err := s.Thread(fbid)
	if err != nil {
		s.log.Warn().Err(err).Str("thread", fbid).Msg("Could not fetch the changed thread")
		return
	}
	s.sink.OnEvent(`{"type":"thread","thread":` + json + `}`)
}

func (s *Session) emitThreads(edges []slidetypes.Node[slidetypes.WrappedThreadInfo], page slidetypes.PageInfo) {
	for _, edge := range edges {
		if info := edge.Node.AsIGDirectThread; info != nil {
			s.emit(map[string]any{"type": "thread", "thread": s.remember(convertThread(info, s.viewerID()))})
		}
	}
	more := ""
	if page.HasNextPage {
		more = page.EndCursor
	}
	s.emit(map[string]any{"type": "inboxLoaded", "nextCursor": more})
}

// --- helpers ---

func (s *Session) remember(t Thread) Thread {
	if t.ID != "" && t.LongID != "" {
		s.mu.Lock()
		s.threads[t.ID] = t.LongID
		s.ids[t.ID] = &instameow.ThreadIGIDs{LongID: t.LongID, ShortID: t.ID}
		s.mu.Unlock()
	}
	return t
}

// idsFor finds the two ids a thread goes by, asking Instagram when a listing has not said.
func (s *Session) idsFor(fbid string) (*instameow.ThreadIGIDs, error) {
	s.mu.Lock()
	known := s.ids[fbid]
	s.mu.Unlock()
	if known != nil && known.LongID != "" && known.ShortID != "" {
		return known, nil
	}
	n, err := strconv.ParseInt(fbid, 10, 64)
	if err != nil {
		return nil, fmt.Errorf("%w: %q is not a thread id", ErrRejected, fbid)
	}
	ctx, cancel := s.ctx()
	defer cancel()
	ids, err := s.client.FetchThreadID(ctx, n)
	if err != nil {
		return nil, wrap(err)
	}
	if ids.ShortID == "" {
		ids.ShortID = fbid
	}
	s.mu.Lock()
	s.ids[fbid] = ids
	s.mu.Unlock()
	return ids, nil
}

func (s *Session) sent(fbid string, m slidetypes.SentMessage, kind, text string) Message {
	return Message{
		ID:        m.MessageID,
		Thread:    fbid,
		Sender:    s.OwnID(),
		Timestamp: m.TimestampMS.UnixMilli(),
		Kind:      kind,
		Text:      text,
	}
}

func (s *Session) viewerID() int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.viewer
}

func (s *Session) ctx() (context.Context, context.CancelFunc) {
	return context.WithTimeout(s.log.WithContext(context.Background()), requestTimeout)
}

func (s *Session) emit(event map[string]any) {
	data, err := json.Marshal(event)
	if err != nil {
		s.log.Err(err).Msg("Could not encode an event")
		return
	}
	if s.sink != nil {
		s.sink.OnEvent(string(data))
	}
}

func wrap(err error) error {
	switch {
	case err == nil:
		return nil
	case errors.Is(err, httpclient.ErrTokenInvalidated), errors.Is(err, httpclient.ErrChallengeRequired),
		errors.Is(err, httpclient.ErrAccountSuspended):
		return fmt.Errorf("%w: %w", ErrLoggedOut, err)
	default:
		return err
	}
}

func marshal(v any) (string, error) {
	data, err := json.Marshal(v)
	if err != nil {
		return "", err
	}
	return string(data), nil
}

const (
	pageSize      = 50
	downloadAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
)
