// SPDX-License-Identifier: AGPL-3.0-or-later

package fb

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-meta/pkg/messagix"
	"go.mau.fi/mautrix-meta/pkg/messagix/cookies"
	"go.mau.fi/mautrix-meta/pkg/messagix/dgw"
	"go.mau.fi/mautrix-meta/pkg/messagix/httpclient"
	"go.mau.fi/mautrix-meta/pkg/messagix/methods"
	"go.mau.fi/mautrix-meta/pkg/messagix/socket"
	"go.mau.fi/mautrix-meta/pkg/messagix/table"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
	"go.mau.fi/mautrix-meta/pkg/messagix/useragent"
	"go.mau.fi/util/exhttp"
)

// EventSink receives every event as one JSON object. Kotlin implements it. Calls come
// from the client's own goroutines and must return quickly.
type EventSink interface {
	OnEvent(json string)
}

// Errors Kotlin tells apart by the code before the colon.
var (
	ErrLoggedOut    = errors.New("LOGGED_OUT: Messenger no longer accepts this sign-in")
	ErrNotConnected = errors.New("NOT_CONNECTED: not connected to Messenger")
	ErrRejected     = errors.New("REJECTED: Messenger refused the request")
)

const (
	requestTimeout = 60 * time.Second
	syncGroup      = 1
)

// Session is one signed-in Facebook account: the cookies, the live connection, and
// every request on it.
type Session struct {
	client  *messagix.Client
	cookies *cookies.Cookies
	sink    EventSink
	log     zerolog.Logger

	mu       sync.Mutex
	own      int64
	threads  map[int64]*Thread
	people   map[int64]*User
	messages map[string]place  // message id -> where it sits, for edits and reactions
	order    []string          // message ids in the order learned, to forget the oldest
	emojis   map[string]string // "<message>|<actor>" -> emoji, so a removal can name it
	cancel   context.CancelFunc
	live     bool
	lastMin  int64
	// The encrypted channel, and the mapping between a web thread key and the chat's id
	// on the channel (see e2ee.go).
	e2ee  e2ee
	jidOf map[int64]int64
	keyOf map[int64]int64
}

type place struct {
	thread    int64
	timestamp int64
	// For messages on the encrypted channel: who sent it, and the chat's id there.
	sender int64
	fromMe bool
	jid    int64
}

// NewSession takes the facebook.com cookies as a JSON object of name to value, and the
// path of the key store for encrypted chats (empty: no encrypted channel).
func NewSession(cookiesJSON, dbPath string, sink EventSink) (*Session, error) {
	var raw map[string]string
	if err := json.Unmarshal([]byte(cookiesJSON), &raw); err != nil {
		return nil, fmt.Errorf("cookies are not a JSON object: %w", err)
	}
	jar := &cookies.Cookies{Platform: types.Facebook}
	values := make(map[cookies.MetaCookieName]string, len(raw))
	for name, value := range raw {
		values[cookies.MetaCookieName(name)] = value
	}
	jar.UpdateValues(values)
	if missing := jar.GetMissingCookieNames(); len(missing) > 0 {
		return nil, fmt.Errorf("%w: sign-in cookies are incomplete: %v", ErrLoggedOut, missing)
	}
	s := &Session{
		cookies:  jar,
		sink:     sink,
		log:      newLogger("messagix"),
		own:      jar.GetUserID(),
		threads:  map[int64]*Thread{},
		people:   map[int64]*User{},
		messages: map[string]place{},
		emojis:   map[string]string{},
		jidOf:    map[int64]int64{},
		keyOf:    map[int64]int64{},
	}
	s.client = messagix.NewClient(jar, s.log, &messagix.Config{ClientSettings: exhttp.SensibleClientSettings})
	s.client.SetEventHandler(s.handleEvent)
	if err := s.openStore(dbPath); err != nil {
		return nil, err
	}
	return s, nil
}

// keyFor is the key a thread is kept under: the web thread key, also for a chat named by
// its id on the encrypted channel.
func (s *Session) keyFor(threadID string) (int64, error) {
	n, err := parseID(threadID)
	if err != nil {
		return 0, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if key, mapped := s.keyOf[n]; mapped {
		return key, nil
	}
	return n, nil
}

// threadAt is the thread under a key, or nil.
func (s *Session) threadAt(key int64) *Thread {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.threads[key]
}

// threadByJID finds a chat by its id on the encrypted channel (caller holds the lock).
func (s *Session) threadByJID(jid int64) *Thread {
	if key, mapped := s.keyOf[jid]; mapped {
		if t := s.threads[key]; t != nil {
			return t
		}
	}
	return s.threads[jid]
}

// publicID is the id a thread is shown under (caller holds the lock): its id on the
// encrypted channel once known, else the web thread key.
func (s *Session) publicID(key int64) string {
	if t := s.threads[key]; t != nil && t.jid != 0 {
		return id(t.jid)
	}
	if jid := s.jidOf[key]; jid != 0 {
		return id(jid)
	}
	return id(key)
}

// learnMapping ties a web thread key to the chat's id on the encrypted channel (caller
// holds the lock). The chat is announced again under its channel id, and a chat the
// channel started before the web listing named it is folded in.
func (s *Session) learnMapping(key, jid int64, touched map[int64]bool) (gone []string) {
	if key == 0 || jid == 0 || s.jidOf[key] == jid {
		return nil
	}
	s.jidOf[key] = jid
	s.keyOf[jid] = key
	// The chat was shown under the web key before (this run or an earlier one): that
	// one goes, and it comes back under the channel id.
	gone = append(gone, id(key))
	t := s.threads[key]
	if t != nil {
		t.jid = jid
	}
	if orphan := s.threads[jid]; orphan != nil && orphan.fbKey == 0 && key != jid {
		if t == nil {
			orphan.fbKey = key
			s.threads[key] = orphan
		} else {
			t.addMessages(orphan.Messages)
			t.waUnread = append(t.waUnread, orphan.waUnread...)
			if t.LastAt < orphan.LastAt {
				t.LastAt = orphan.LastAt
			}
		}
		delete(s.threads, jid)
	}
	if s.threads[key] != nil {
		touched[key] = true
	}
	return gone
}

// CookiesJSON is the cookies as they stand now, to save after Messenger refreshed them.
func (s *Session) CookiesJSON() string {
	all := s.cookies.GetAll()
	out := make(map[string]string, len(all))
	for name, value := range all {
		out[string(name)] = value
	}
	data, _ := json.Marshal(out)
	return string(data)
}

// OwnID is the account's own user id.
func (s *Session) OwnID() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.own == 0 {
		return ""
	}
	return id(s.own)
}

// Connect loads the inbox page (emitting a "thread" event per conversation it carries,
// then "inboxLoaded") and keeps the live connection up in the background until Disconnect.
func (s *Session) Connect() error {
	ctx, cancel := s.ctx()
	defer cancel()
	user, initial, err := s.client.LoadMessagesPage(ctx)
	if err != nil {
		return wrap(err)
	}
	s.mu.Lock()
	if user != nil && user.GetFBID() != 0 {
		s.own = user.GetFBID()
	}
	if s.own != 0 {
		me := s.people[s.own]
		if me == nil {
			me = &User{ID: id(s.own), IsMe: true}
			s.people[s.own] = me
		}
		if user != nil && me.Name == "" {
			me.Name = user.GetName()
		}
	}
	s.mu.Unlock()
	s.log.Info().Str("own_id", s.OwnID()).Msg("Messenger page loaded")
	s.emit(map[string]any{"type": "connected", "id": s.OwnID(), "cookies": s.CookiesJSON()})
	if initial != nil {
		s.applyTable(initial, true)
	}
	s.emit(map[string]any{"type": "inboxLoaded"})
	bg, stop := context.WithCancel(s.log.WithContext(context.Background()))
	s.mu.Lock()
	if s.cancel != nil {
		s.cancel()
	}
	s.cancel = stop
	s.mu.Unlock()
	go func() {
		if err := s.client.Connect(bg); err != nil {
			s.emit(map[string]any{"type": "disconnected", "error": err.Error(), "permanent": true})
		}
	}()
	return nil
}

// Disconnect closes the live connection.
func (s *Session) Disconnect() {
	s.mu.Lock()
	if s.cancel != nil {
		s.cancel()
		s.cancel = nil
	}
	s.live = false
	s.mu.Unlock()
	s.client.Disconnect()
	s.stopE2EE()
	s.closeStore()
}

// MoreThreads asks Messenger for the next page of older conversations (each arrives as a
// "thread" event) and says whether another page may follow.
func (s *Session) MoreThreads() (bool, error) {
	// The first listing comes right after the page loads, before the socket is up.
	for waited := 0; !s.isLive() && waited < liveWait; waited += liveStep {
		time.Sleep(time.Duration(liveStep) * time.Millisecond)
	}
	if !s.isLive() {
		return false, ErrNotConnected
	}
	ctx, cancel := s.ctx()
	defer cancel()
	keys, tbl, err := s.client.FetchMoreThreads(ctx, syncGroup)
	if err != nil {
		return false, wrap(err)
	}
	if tbl == nil || keys == nil {
		return false, nil
	}
	s.applyTable(tbl, false)
	s.mu.Lock()
	same := keys.MinThreadKey == s.lastMin
	s.lastMin = keys.MinThreadKey
	s.mu.Unlock()
	return keys.HasMoreBefore && !same, nil
}

// Threads is every conversation known so far, newest first, as a JSON array of Thread.
func (s *Session) Threads() (string, error) {
	s.mu.Lock()
	out := make([]Thread, 0, len(s.threads))
	for _, t := range s.threads {
		out = append(out, t.view(s.own, s.people))
	}
	s.mu.Unlock()
	sort.SliceStable(out, func(i, j int) bool { return out[i].LastAt > out[j].LastAt })
	return marshal(out)
}

// Thread is one conversation as known so far.
func (s *Session) Thread(threadID string) (string, error) {
	key, err := s.keyFor(threadID)
	if err != nil {
		return "", err
	}
	s.mu.Lock()
	t := s.threads[key]
	s.mu.Unlock()
	if t == nil {
		return "", fmt.Errorf("%w: no such thread", ErrRejected)
	}
	return marshal(t.view(s.own, s.people))
}

// Messages returns messages older than olderThan (the newest Messenger has handed over
// when empty), newest first, as a JSON array of Message.
func (s *Session) Messages(threadID, olderThan string) (string, error) {
	key, err := s.keyFor(threadID)
	if err != nil {
		return "", err
	}
	if olderThan == "" {
		s.mu.Lock()
		t := s.threads[key]
		var known []Message
		if t != nil {
			known = append([]Message{}, t.Messages...)
		}
		s.mu.Unlock()
		if known == nil {
			known = []Message{}
		}
		return marshal(known)
	}
	// An encrypted chat has no history on the channel; the web side is asked once, under
	// both of the chat's ids, in case it still holds the messages from before the chat
	// went encrypted (the reference bridge asks the same way).
	if t := s.threadAt(key); t != nil && t.jid != 0 {
		return marshal(s.askWebOnce(t, key))
	}
	if !s.isLive() {
		return "", ErrNotConnected
	}
	s.mu.Lock()
	at, found := s.messages[olderThan]
	s.mu.Unlock()
	timestamp := at.timestamp
	if !found {
		if timestamp, err = methods.ParseMessageID(olderThan); err != nil {
			return "", fmt.Errorf("%w: %q is not a message id", ErrRejected, olderThan)
		}
	}
	ctx, cancel := s.ctx()
	defer cancel()
	resp, err := s.client.ExecuteTasks(ctx, &socket.FetchMessagesTask{
		ThreadKey:            key,
		Direction:            0,
		ReferenceTimestampMs: timestamp,
		ReferenceMessageId:   olderThan,
		SyncGroup:            syncGroup,
		Cursor:               s.client.GetCursor(syncGroup),
	})
	if err != nil {
		return "", wrap(err)
	}
	out := []Message{}
	if resp != nil {
		upsert, _ := resp.WrapMessages()
		s.mu.Lock()
		for _, batch := range upsert {
			if batch.GetThreadKey() != key {
				continue
			}
			for _, m := range batch.Messages {
				converted := convertMessage(m)
				if converted.ID == olderThan {
					continue
				}
				s.remember(converted)
				out = append(out, converted)
			}
		}
		s.mu.Unlock()
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Timestamp > out[j].Timestamp })
	return marshal(out)
}

// SendText sends text, as a reply when replyTo names a message. Returns the Message as sent.
func (s *Session) SendText(threadID, text, replyTo string) (string, error) {
	key, err := s.keyFor(threadID)
	if err != nil {
		return "", err
	}
	if t := s.threadAt(key); t != nil && t.jid != 0 {
		return marshalSent(s.sendE2EEText(t, threadID, text, replyTo))
	}
	task := s.sendTask(key, replyTo)
	task.Text = text
	sent, err := s.execute(task, threadID, "text", text)
	if err != nil {
		return "", err
	}
	return marshal(sent)
}

// SendMedia uploads a file and sends it with text (which may be empty); kind is "image",
// "video", "gif", "voice", or "file".
func (s *Session) SendMedia(threadID, path, mime, kind, fileName, text, replyTo string) (string, error) {
	key, err := s.keyFor(threadID)
	if err != nil {
		return "", err
	}
	if t := s.threadAt(key); t != nil && t.jid != 0 {
		return marshalSent(s.sendE2EEMedia(t, threadID, path, mime, kind, fileName, text, replyTo))
	}
	if !s.isLive() {
		return "", ErrNotConnected
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(s.log.WithContext(context.Background()), 5*time.Minute)
	defer cancel()
	up, err := s.client.GetHTTP().SendMercuryUploadRequest(ctx, key, &httpclient.MercuryUploadMedia{
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
	task := s.sendTask(key, replyTo)
	task.SendType = table.MEDIA
	task.AttachmentFBIds = []int64{attachment}
	task.Text = text
	sent, err := s.execute(task, threadID, kind, text)
	if err != nil {
		return "", err
	}
	sent.Media = []Media{{Kind: kind, Mime: mime, FileName: fileName, Size: int64(len(data)), ID: id(attachment)}}
	return marshal(sent)
}

func (s *Session) sendTask(key int64, replyTo string) *socket.SendMessageTask {
	task := &socket.SendMessageTask{
		ThreadId:         key,
		Otid:             methods.GenerateEpochID(),
		Source:           table.MESSENGER_INBOX_IN_THREAD,
		InitiatingSource: table.FACEBOOK_INBOX,
		SendType:         table.TEXT,
		SyncGroup:        syncGroup,
	}
	s.mu.Lock()
	if t := s.threads[key]; t != nil && t.Folder == folderPending {
		// Replying from the request queue accepts the request, as the website does.
		task.Source = table.MESSENGER_INBOX_PENDING_REQUESTS
	}
	s.mu.Unlock()
	if replyTo != "" {
		task.ReplyMetaData = &socket.ReplyMetaData{ReplyMessageId: replyTo, ReplySourceType: 1, ReplyType: 0}
	}
	return task
}

// execute sends the task with a read marker, as the website does, and reads back the id.
func (s *Session) execute(task *socket.SendMessageTask, threadID, kind, text string) (Message, error) {
	if !s.isLive() {
		return Message{}, ErrNotConnected
	}
	ctx, cancel := s.ctx()
	defer cancel()
	read := &socket.ThreadMarkReadTask{ThreadId: task.ThreadId, SyncGroup: syncGroup, LastReadWatermarkTs: time.Now().UnixMilli()}
	resp, err := s.client.ExecuteTasks(ctx, task, read)
	if err != nil {
		return Message{}, wrap(err)
	}
	otid := strconv.FormatInt(task.Otid, 10)
	messageID := ""
	if resp != nil {
		for _, replace := range resp.LSReplaceOptimsiticMessage {
			if replace.OfflineThreadingId == otid {
				messageID = replace.MessageId
			}
		}
		if messageID == "" {
			for _, failed := range resp.LSMarkOptimisticMessageFailed {
				return Message{}, fmt.Errorf("%w: %s", ErrRejected, failed.Message)
			}
			for _, failed := range resp.LSHandleFailedTask {
				return Message{}, fmt.Errorf("%w: %s", ErrRejected, failed.Message)
			}
		}
	}
	if messageID == "" {
		return Message{}, fmt.Errorf("%w: Messenger gave no id for the sent message", ErrRejected)
	}
	timestamp, err := methods.ParseMessageID(messageID)
	if err != nil {
		timestamp = time.Now().UnixMilli()
	}
	sent := Message{ID: messageID, Thread: threadID, Sender: s.OwnID(), Timestamp: timestamp, Kind: kind, Text: text}
	if task.ReplyMetaData != nil {
		sent.ReplyTo = task.ReplyMetaData.ReplyMessageId
	}
	s.mu.Lock()
	s.remember(sent)
	if t := s.threads[task.ThreadId]; t != nil {
		t.addMessages([]Message{sent})
		if t.LastAt < timestamp {
			t.LastAt = timestamp
		}
	}
	s.mu.Unlock()
	return sent, nil
}

// SendReaction puts emoji on a message, or takes yours away when remove is set.
func (s *Session) SendReaction(threadID, messageID, emoji string, remove bool) error {
	key, err := s.keyFor(threadID)
	if err != nil {
		return err
	}
	if !s.isLive() {
		return ErrNotConnected
	}
	if remove {
		emoji = ""
	}
	if t := s.threadAt(key); t != nil && t.jid != 0 {
		return s.sendE2EEReaction(t, messageID, emoji)
	}
	ctx, cancel := s.ctx()
	defer cancel()
	_, err = s.client.ExecuteTasks(ctx, &socket.SendReactionTask{
		ThreadKey:       key,
		TimestampMs:     time.Now().UnixMilli(),
		MessageID:       messageID,
		Reaction:        emoji,
		ActorID:         s.ownID(),
		SyncGroup:       syncGroup,
		SendAttribution: table.MESSENGER_INBOX_IN_THREAD,
	})
	return wrap(err)
}

// Unsend deletes one of your messages for everyone.
func (s *Session) Unsend(messageID string) error {
	if t := s.channelThreadOf(messageID); t != nil {
		return s.sendE2EEUnsend(t, messageID)
	}
	if !s.isLive() {
		return ErrNotConnected
	}
	ctx, cancel := s.ctx()
	defer cancel()
	_, err := s.client.ExecuteTasks(ctx, &socket.DeleteMessageTask{MessageId: messageID})
	return wrap(err)
}

// Edit changes the text of one of your messages.
func (s *Session) Edit(messageID, text string) error {
	if t := s.channelThreadOf(messageID); t != nil {
		return s.sendE2EEEdit(t, messageID, text)
	}
	if !s.isLive() {
		return ErrNotConnected
	}
	ctx, cancel := s.ctx()
	defer cancel()
	_, err := s.client.ExecuteTasks(ctx, &socket.EditMessageTask{MessageID: messageID, Text: text})
	return wrap(err)
}

// MarkRead marks the thread read up to a time.
func (s *Session) MarkRead(threadID string, timestamp int64) error {
	key, err := s.keyFor(threadID)
	if err != nil {
		return err
	}
	t := s.threadAt(key)
	if t != nil && t.jid != 0 {
		s.markReadE2EE(t, timestamp)
		if t.fbKey == 0 {
			return nil
		}
	}
	if !s.isLive() {
		return ErrNotConnected
	}
	ctx, cancel := s.ctx()
	defer cancel()
	_, err = s.client.ExecuteTasks(ctx, &socket.ThreadMarkReadTask{ThreadId: key, LastReadWatermarkTs: timestamp, SyncGroup: syncGroup})
	if err == nil {
		s.mu.Lock()
		if t := s.threads[key]; t != nil && t.ReadAt < timestamp {
			t.ReadAt = timestamp
		}
		s.mu.Unlock()
	}
	return wrap(err)
}

// SetTyping tells the thread you are typing, or that you stopped.
func (s *Session) SetTyping(threadID string, typing bool) error {
	key, err := s.keyFor(threadID)
	if err != nil {
		return err
	}
	if t := s.threadAt(key); t != nil && t.jid != 0 {
		return s.setTypingE2EE(t, typing)
	}
	if !s.isLive() {
		return ErrNotConnected
	}
	s.mu.Lock()
	t := s.threads[key]
	group, kind := int64(0), int64(table.ONE_TO_ONE)
	if t != nil {
		kind = int64(t.threadType)
		if t.IsGroup {
			group = 1
		}
	}
	s.mu.Unlock()
	on := int64(0)
	if typing {
		on = 1
	}
	ctx, cancel := s.ctx()
	defer cancel()
	return wrap(s.client.ExecuteStatelessTask(ctx, &socket.UpdatePresenceTask{
		ThreadKey: key, IsGroupThread: group, IsTyping: on, Attribution: 0, SyncGroup: syncGroup, ThreadType: kind,
	}))
}

// AcceptRequest moves a message request into the inbox.
func (s *Session) AcceptRequest(threadID string) error {
	key, err := s.keyFor(threadID)
	if err != nil {
		return err
	}
	if !s.isLive() {
		return ErrNotConnected
	}
	ctx, cancel := s.ctx()
	defer cancel()
	resp, err := s.client.ExecuteTasks(ctx, &socket.AcceptMessageRequestTask{ThreadKey: key, SyncGroup: syncGroup})
	if err != nil {
		return wrap(err)
	}
	s.mu.Lock()
	if t := s.threads[key]; t != nil {
		t.Folder = folderInbox
	}
	s.mu.Unlock()
	if resp != nil {
		s.applyTable(resp, false)
	}
	return nil
}

// DeleteThread removes a conversation for this account (declining a request, or leaving it behind).
func (s *Session) DeleteThread(threadID string) error {
	key, err := s.keyFor(threadID)
	if err != nil {
		return err
	}
	if !s.isLive() {
		return ErrNotConnected
	}
	ctx, cancel := s.ctx()
	defer cancel()
	_, err = s.client.ExecuteTasks(ctx, &socket.DeleteThreadTask{ThreadKey: key, RemoveType: 0, SyncGroup: syncGroup})
	if err != nil {
		return wrap(err)
	}
	s.mu.Lock()
	delete(s.threads, key)
	s.mu.Unlock()
	return nil
}

// Download fetches a media address (a signed CDN link) into destPath, with the headers
// Messenger's CDN expects from a browser.
func (s *Session) Download(url, mime, destPath string) error {
	if strings.HasPrefix(url, e2eePrefix) {
		return s.downloadE2EE(url, destPath)
	}
	ctx, cancel := context.WithTimeout(s.log.WithContext(context.Background()), 5*time.Minute)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "*/*")
	dest := "empty"
	switch {
	case len(mime) > 6 && mime[:6] == "image/":
		req.Header.Set("Accept", "image/avif,image/webp,*/*")
		dest = "image"
	case len(mime) > 6 && mime[:6] == "video/":
		dest = "video"
	case len(mime) > 6 && mime[:6] == "audio/":
		dest = "audio"
	}
	req.Header.Set("Sec-Fetch-Dest", dest)
	req.Header.Set("Sec-Fetch-Mode", "no-cors")
	req.Header.Set("Sec-Fetch-Site", "cross-site")
	req.Header.Set("User-Agent", useragent.UserAgent)
	req.Header.Set("sec-ch-ua", useragent.SecCHUserAgent)
	req.Header.Set("sec-ch-ua-platform", useragent.SecCHPlatform)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusPartialContent {
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

// SearchUsers finds people you can message by name, as a JSON array of User.
func (s *Session) SearchUsers(query string) (string, error) {
	if !s.isLive() {
		return "", ErrNotConnected
	}
	ctx, cancel := s.ctx()
	defer cancel()
	resp, err := s.client.ExecuteTasks(ctx, &socket.SearchUserTask{
		Query: query,
		SupportedTypes: []table.SearchType{
			table.SearchTypeContact, table.SearchTypeGroup, table.SearchTypePage, table.SearchTypeNonContact,
			table.SearchTypeCommunityMessagingThread,
		},
		SurfaceType: 5,
	})
	if err != nil {
		return "", wrap(err)
	}
	people := []User{}
	if resp != nil {
		for _, r := range resp.LSInsertSearchResult {
			if r.ThreadType.IsOneToOne() && r.CanViewerMessage && r.GetFBID() != 0 {
				people = append(people, User{ID: id(r.GetFBID()), Name: r.GetName(), Picture: r.GetAvatarURL()})
			}
		}
	}
	return marshal(people)
}

// StartChat opens (or finds) the one-to-one thread with a person and returns its id.
func (s *Session) StartChat(userID string) (string, error) {
	key, err := parseID(userID)
	if err != nil {
		return "", err
	}
	if !s.isLive() {
		return "", ErrNotConnected
	}
	ctx, cancel := s.ctx()
	defer cancel()
	resp, err := s.client.ExecuteTasks(ctx, &socket.CreateThreadTask{ThreadFBID: key, SyncGroup: syncGroup})
	if err != nil {
		return "", wrap(err)
	}
	if resp != nil {
		s.applyTable(resp, false)
	}
	s.mu.Lock()
	if s.threads[key] == nil {
		s.threads[key] = &Thread{ID: id(key), threadType: table.ONE_TO_ONE, Folder: folderInbox,
			members: map[int64]*User{key: {ID: id(key)}}}
	}
	s.mu.Unlock()
	return id(key), nil
}

// --- events ---

func (s *Session) handleEvent(_ context.Context, raw any) {
	switch evt := raw.(type) {
	case *table.LSTable:
		s.applyTable(evt, false)
	case *messagix.ConnectedEvent, *messagix.ReconnectedEvent:
		s.mu.Lock()
		s.live = true
		s.mu.Unlock()
		s.emit(map[string]any{"type": "live"})
		go s.startE2EE()
	case *messagix.TransientDisconnectEvent:
		s.mu.Lock()
		s.live = false
		s.mu.Unlock()
		s.emit(map[string]any{"type": "disconnected", "error": errText(evt.Err), "permanent": false})
	case *messagix.PermanentErrorEvent:
		s.mu.Lock()
		s.live = false
		s.mu.Unlock()
		if websocket.CloseStatus(evt.Err) == dgw.CloseStatusUnauthorized {
			s.emit(map[string]any{"type": "loggedOut", "error": errText(evt.Err)})
		} else {
			s.emit(map[string]any{"type": "disconnected", "error": errText(evt.Err), "permanent": true})
		}
	default:
		s.log.Trace().Type("event_type", raw).Msg("Unhandled messagix event")
	}
}

// applyTable folds one table of rows into what the session knows and reports the changes.
func (s *Session) applyTable(tbl *table.LSTable, initial bool) {
	s.mu.Lock()
	events := s.fold(tbl)
	s.mu.Unlock()
	for _, event := range events {
		s.emit(event)
	}
	if !initial {
		s.client.PostHandlePublishResponse(tbl)
	}
}

// fold is applyTable's work under the lock; it returns the events to send, in order.
func (s *Session) fold(tbl *table.LSTable) []map[string]any {
	var events []map[string]any
	touched := map[int64]bool{}
	for _, row := range tbl.LSVerifyHybridThreadExists {
		for _, old := range s.learnMapping(row.ThreadKey, row.ThreadJID, touched) {
			events = append(events, map[string]any{"type": "threadGone", "thread": old})
		}
	}
	for _, row := range tbl.LSUpdateThreadAuthorityAndMappingWithOTIDFromJID {
		for _, old := range s.learnMapping(row.ThreadKey, row.ThreadJID, touched) {
			events = append(events, map[string]any{"type": "threadGone", "thread": old})
		}
	}
	for _, c := range tbl.LSDeleteThenInsertContact {
		s.learn(c.Id, c.Name, c.ProfilePictureUrl)
	}
	for _, c := range tbl.LSVerifyContactRowExists {
		s.learn(c.ContactId, c.Name, c.ProfilePictureUrl)
	}
	// Facebook deletes and re-inserts a thread in the same batch when it re-sends the
	// inbox; a delete row for a thread the batch also upserts is that, not a deletion
	// (the reference bridge ignores it the same way). Honouring it dropped every chat.
	upserted := map[int64]bool{}
	for _, row := range tbl.LSDeleteThenInsertThread {
		upserted[row.ThreadKey] = true
	}
	for _, row := range tbl.LSUpdateOrInsertThread {
		upserted[row.ThreadKey] = true
	}
	gone := map[int64]bool{}
	reinserted := 0
	mark := func(key int64) {
		if upserted[key] {
			reinserted++
		} else {
			gone[key] = true
		}
	}
	for _, row := range tbl.LSDeleteThread {
		mark(row.ThreadKey)
	}
	for _, row := range tbl.LSDeletePartialThread {
		mark(row.ThreadKey)
	}
	for _, row := range tbl.LSDeleteMessageRequest {
		mark(row.ThreadKey)
	}
	for _, row := range tbl.LSRemoveParticipantFromThread {
		if row.ParticipantId == s.own {
			mark(row.ThreadKey)
		} else if t := s.threads[row.ThreadKey]; t != nil {
			delete(t.members, row.ParticipantId)
			touched[row.ThreadKey] = true
		}
	}
	for key := range gone {
		shown := s.publicID(key)
		delete(s.threads, key)
		events = append(events, map[string]any{"type": "threadGone", "thread": shown})
	}
	if len(upserted)+len(gone)+reinserted > 0 {
		s.log.Info().Int("threads", len(upserted)).Int("gone", len(gone)).Int("reinserted", reinserted).
			Int("known", len(s.threads)).Msg("Thread rows in this batch")
	}
	for _, row := range tbl.LSDeleteThenInsertThread {
		if gone[row.ThreadKey] {
			continue
		}
		t := threadFrom(row)
		t.fbKey, t.jid = row.ThreadKey, s.jidOf[row.ThreadKey]
		t.merge(s.threads[row.ThreadKey])
		s.threads[row.ThreadKey] = t
		touched[row.ThreadKey] = true
	}
	for _, row := range tbl.LSUpdateOrInsertThread {
		if gone[row.ThreadKey] {
			continue
		}
		t := threadFromUpdate(row)
		t.fbKey, t.jid = row.ThreadKey, s.jidOf[row.ThreadKey]
		t.merge(s.threads[row.ThreadKey])
		s.threads[row.ThreadKey] = t
		touched[row.ThreadKey] = true
	}
	for _, row := range tbl.LSAddParticipantIdToGroupThread {
		t := s.threads[row.ThreadKey]
		if t == nil || gone[row.ThreadKey] {
			continue
		}
		member := t.members[row.ContactId]
		if member == nil {
			member = &User{ID: id(row.ContactId)}
			t.members[row.ContactId] = member
		}
		member.admin = row.IsAdmin
		member.IsMe = row.ContactId == s.own
		touched[row.ThreadKey] = true
	}
	for _, row := range tbl.LSSyncUpdateThreadName {
		if t := s.threads[row.ThreadKey]; t != nil {
			t.Title = row.ThreadName
			touched[row.ThreadKey] = true
		}
	}
	for _, row := range tbl.LSSetThreadImageURL {
		if t := s.threads[row.ThreadKey]; t != nil {
			t.ImageURL = row.ImageURL
			touched[row.ThreadKey] = true
		}
	}
	upsert, insert := tbl.WrapMessages()
	for _, batch := range upsert {
		key := batch.GetThreadKey()
		t := s.threads[key]
		if t == nil || gone[key] {
			continue
		}
		converted := make([]Message, 0, len(batch.Messages))
		for _, m := range batch.Messages {
			c := convertMessage(m)
			c.Thread = s.publicID(key)
			s.remember(c)
			converted = append(converted, c)
		}
		t.addMessages(converted)
		if batch.Range != nil {
			t.MoreBefore = batch.Range.HasMoreBefore
		}
		touched[key] = true
	}
	for _, row := range tbl.LSMarkThreadReadV2 {
		if t := s.threads[row.ThreadKey]; t != nil {
			if t.ReadAt < row.LastReadWatermarkTimestampMs {
				t.ReadAt = row.LastReadWatermarkTimestampMs
			}
			touched[row.ThreadKey] = true
		}
	}
	keys := make([]int64, 0, len(touched))
	for key := range touched {
		if s.threads[key] != nil {
			keys = append(keys, key)
		}
	}
	sort.Slice(keys, func(i, j int) bool { return keys[i] < keys[j] })
	for _, key := range keys {
		events = append(events, map[string]any{"type": "thread", "thread": s.threads[key].view(s.own, s.people)})
	}
	for _, m := range insert {
		c := convertMessage(m)
		c.Thread = s.publicID(m.ThreadKey)
		s.remember(c)
		if t := s.threads[m.ThreadKey]; t != nil {
			t.addMessages([]Message{c})
			if t.LastAt < c.Timestamp {
				t.LastAt = c.Timestamp
			}
		}
		events = append(events, map[string]any{"type": "message", "message": c})
	}
	for _, row := range tbl.LSEditMessage {
		at := s.messages[row.MessageID]
		events = append(events, map[string]any{"type": "edit", "thread": s.publicID(at.thread), "message": row.MessageID,
			"text": row.Text, "timestamp": time.Now().UnixMilli()})
	}
	for _, row := range tbl.LSDeleteMessage {
		events = append(events, map[string]any{"type": "unsent", "thread": s.publicID(row.ThreadKey), "message": row.MessageId})
	}
	for _, row := range tbl.LSDeleteThenInsertMessage {
		// Messenger replaces an unsent message with this row; anything else here is noise.
		if row.IsUnsent {
			events = append(events, map[string]any{"type": "unsent", "thread": s.publicID(row.ThreadKey), "message": row.MessageId})
		}
	}
	for _, row := range tbl.LSUpsertReaction {
		s.emojis[row.MessageId+"|"+id(row.ActorId)] = row.Reaction
		events = append(events, map[string]any{"type": "reaction", "thread": s.publicID(row.ThreadKey), "message": row.MessageId,
			"removed": false, "reaction": Reaction{Emoji: row.Reaction, Sender: id(row.ActorId), Timestamp: row.TimestampMs}})
	}
	for _, row := range tbl.LSDeleteReaction {
		emoji := s.emojis[row.MessageId+"|"+id(row.ActorId)]
		delete(s.emojis, row.MessageId+"|"+id(row.ActorId))
		events = append(events, map[string]any{"type": "reaction", "thread": s.publicID(row.ThreadKey), "message": row.MessageId,
			"removed": true, "reaction": Reaction{Emoji: emoji, Sender: id(row.ActorId), Timestamp: time.Now().UnixMilli()}})
	}
	for _, row := range tbl.LSUpdateReadReceipt {
		events = append(events, map[string]any{"type": "readReceipt", "thread": s.publicID(row.ThreadKey),
			"sender": id(row.ContactId), "timestamp": row.ReadWatermarkTimestampMs})
	}
	for _, row := range tbl.LSMarkThreadReadV2 {
		events = append(events, map[string]any{"type": "readByMe", "thread": s.publicID(row.ThreadKey),
			"timestamp": row.LastReadWatermarkTimestampMs})
	}
	for _, row := range tbl.LSUpdateTypingIndicator {
		events = append(events, map[string]any{"type": "typing", "thread": s.publicID(row.ThreadKey),
			"sender": id(row.SenderId), "typing": row.IsTyping})
	}
	return events
}

// --- helpers ---

func (s *Session) learn(key int64, name, picture string) {
	if key == 0 {
		return
	}
	user := s.people[key]
	if user == nil {
		user = &User{ID: id(key), IsMe: key == s.own}
		s.people[key] = user
	}
	if name != "" {
		user.Name = name
	}
	if picture != "" {
		user.Picture = picture
	}
}

// remember notes where a message sits (caller holds the lock), forgetting the oldest.
func (s *Session) remember(m Message) {
	key, _ := strconv.ParseInt(m.Thread, 10, 64)
	if _, known := s.messages[m.ID]; !known {
		s.order = append(s.order, m.ID)
	}
	s.messages[m.ID] = place{thread: key, timestamp: m.Timestamp}
	for _, r := range m.Reactions {
		s.emojis[m.ID+"|"+r.Sender] = r.Emoji
	}
	for len(s.order) > rememberedMessages {
		oldest := s.order[0]
		s.order = s.order[1:]
		delete(s.messages, oldest)
	}
}

func (s *Session) isLive() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.live
}

func (s *Session) ownID() int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.own
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

func parseID(text string) (int64, error) {
	n, err := strconv.ParseInt(text, 10, 64)
	if err != nil || n == 0 {
		return 0, fmt.Errorf("%w: %q is not an id", ErrRejected, text)
	}
	return n, nil
}

func errText(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
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
	liveWait           = 15000 // milliseconds to wait for the live socket before listing more
	liveStep           = 250
	rememberedMessages = 6000
	folderInbox        = "inbox"
	folderPending      = "pending"
)

// channelThreadOf is the encrypted chat a message belongs to, or nil for a web message.
func (s *Session) channelThreadOf(messageID string) *Thread {
	s.mu.Lock()
	defer s.mu.Unlock()
	p, known := s.messages[messageID]
	if !known || p.jid == 0 {
		return nil
	}
	return s.threadByJID(p.jid)
}

func marshalSent(sent Message, err error) (string, error) {
	if err != nil {
		return "", err
	}
	return marshal(sent)
}

// askWebOnce fetches an encrypted chat's history from the web side under its web key and
// its channel id, once, and returns what it knows afterwards (newest first).
func (s *Session) askWebOnce(t *Thread, key int64) []Message {
	s.mu.Lock()
	asked := t.askedWeb
	t.askedWeb = true
	jid, fbKey, lastAt := t.jid, t.fbKey, t.LastAt
	s.mu.Unlock()
	if !asked && s.isLive() {
		if lastAt == 0 {
			lastAt = time.Now().UnixMilli()
		}
		for _, ask := range []int64{fbKey, jid} {
			if ask == 0 {
				continue
			}
			ctx, cancel := s.ctx()
			resp, err := s.client.ExecuteTasks(ctx, &socket.FetchMessagesTask{
				ThreadKey: ask, Direction: 0, ReferenceTimestampMs: lastAt + 1, SyncGroup: syncGroup, Cursor: s.client.GetCursor(syncGroup),
			})
			cancel()
			if err != nil {
				s.log.Warn().Err(err).Int64("asked", ask).Msg("Encrypted chat: web history refused")
				continue
			}
			count := 0
			if resp != nil {
				upsert, _ := resp.WrapMessages()
				s.mu.Lock()
				for _, batch := range upsert {
					for _, m := range batch.Messages {
						c := convertMessage(m)
						c.Thread = id(jid)
						s.remember(c)
						t.addMessages([]Message{c})
						count++
					}
				}
				s.mu.Unlock()
			}
			s.log.Info().Int64("asked", ask).Int("messages", count).Msg("Encrypted chat: web history answered")
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]Message{}, t.Messages...)
}
