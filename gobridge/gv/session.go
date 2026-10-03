// SPDX-License-Identifier: AGPL-3.0-or-later

package gv

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gvoice/pkg/libgv"
	"go.mau.fi/mautrix-gvoice/pkg/libgv/gvproto"
)

// EventSink receives every event as one JSON object. Kotlin implements it. Calls come
// from the client's own goroutines and must return quickly.
type EventSink interface {
	OnEvent(json string)
}

// Errors Kotlin tells apart by the code before the colon.
var (
	ErrLoggedOut    = errors.New("LOGGED_OUT: Google no longer accepts this sign-in")
	ErrNotConnected = errors.New("NOT_CONNECTED: not connected to Google Voice")
	ErrRejected     = errors.New("REJECTED: Google Voice refused the request")
)

const (
	requestTimeout = 60 * time.Second
	// How often the thread list is read when the realtime channel has said nothing.
	refreshEvery = 2 * time.Minute
	// A short wait after a realtime nudge, so the new item is there when asked for.
	refreshDelay = 1500 * time.Millisecond
	retryWait    = 30 * time.Second
	maxRetries   = 20
)

// Session is one signed-in Google Voice account: the cookies, the live connection, and
// every request on it.
type Session struct {
	client *libgv.Client
	sink   EventSink
	log    zerolog.Logger

	mu       sync.Mutex
	cancel   context.CancelFunc
	own      string
	names    map[string]string
	lastSeen map[string]int64 // newest message timestamp per thread
	wake     chan struct{}
}

// NewSession takes the google.com cookies as a JSON object of name to value.
func NewSession(cookiesJSON string, sink EventSink) (*Session, error) {
	var raw map[string]string
	if err := json.Unmarshal([]byte(cookiesJSON), &raw); err != nil {
		return nil, fmt.Errorf("cookies are not a JSON object: %w", err)
	}
	for _, name := range []string{"SID", "HSID", "SSID", "APISID", "SAPISID"} {
		if raw[name] == "" {
			return nil, fmt.Errorf("%w: the %s cookie is missing", ErrLoggedOut, name)
		}
	}
	s := &Session{
		client:   libgv.NewClient(raw),
		sink:     sink,
		log:      newLogger("libgv"),
		names:    make(map[string]string),
		lastSeen: make(map[string]int64),
		wake:     make(chan struct{}, 1),
	}
	s.client.EventHandler = s.handleEvent
	return s, nil
}

// CookiesJSON is the cookies as Google last refreshed them, for saving.
func (s *Session) CookiesJSON() (string, error) { return marshal(s.client.GetCookies()) }

// OwnPhone is the account's Google Voice number (+E.164), known after Connect.
func (s *Session) OwnPhone() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.own
}

func (s *Session) ctx() (context.Context, context.CancelFunc) {
	return context.WithTimeout(s.log.WithContext(context.Background()), requestTimeout)
}

func (s *Session) emit(event map[string]any) {
	raw, err := json.Marshal(event)
	if err != nil {
		s.log.Error().Err(err).Msg("Could not encode an event")
		return
	}
	s.sink.OnEvent(string(raw))
}

// Check asks Google Voice who this is; an error means the cookies do not sign in.
func (s *Session) Check() (string, error) {
	ctx, cancel := s.ctx()
	defer cancel()
	account, err := s.client.GetAccount(ctx)
	if err != nil {
		return "", wrap(err)
	}
	own := ownNumber(account)
	s.mu.Lock()
	s.own = own
	s.mu.Unlock()
	return own, nil
}

func ownNumber(resp *gvproto.RespGetAccount) string {
	first := ""
	for _, p := range resp.GetAccount().GetPhones() {
		number := p.GetAccountPhone().GetPhoneNumber().GetE164()
		if number == "" {
			continue
		}
		if p.GetAccountPhone().GetDefaultForOutboundCommunications() {
			return number
		}
		if first == "" {
			first = number
		}
	}
	return first
}

// Connect checks the sign-in, lists every thread with its newest messages ("thread"
// events, then "inboxLoaded"), announces the contact names ("contacts"), and then keeps
// watching: "message" for each new item, "thread" when a thread's state changes,
// "loggedOut" when Google drops the sign-in.
func (s *Session) Connect() error {
	if _, err := s.Check(); err != nil {
		return err
	}
	s.Disconnect()
	ctx, cancel := context.WithCancel(s.log.WithContext(context.Background()))
	s.mu.Lock()
	s.cancel = cancel
	s.mu.Unlock()
	s.emit(map[string]any{"type": "connected", "phone": s.OwnPhone()})
	go s.run(ctx)
	return nil
}

func (s *Session) run(ctx context.Context) {
	s.loadContacts(ctx)
	if err := s.loadInbox(ctx); err != nil {
		s.log.Warn().Err(err).Msg("Could not list the threads")
		if libgv.IsAuthError(err) {
			s.emit(map[string]any{"type": "loggedOut", "reason": err.Error()})
			return
		}
	}
	s.emit(map[string]any{"type": "inboxLoaded"})
	go s.realtime(ctx)
	s.refreshLoop(ctx)
}

func (s *Session) loadContacts(ctx context.Context) {
	resp, err := s.client.AutocompleteContacts(ctx, "")
	if err != nil {
		s.log.Warn().Err(err).Msg("Could not load the contact names")
		return
	}
	people := make([]Member, 0)
	s.mu.Lock()
	for _, wrapped := range resp.GetResults() {
		person := wrapped.GetPerson()
		name := ""
		for _, m := range person.GetContactMethods() {
			if v := m.GetDisplayInfo().GetName().GetValue(); v != "" && (name == "" || m.GetDisplayInfo().GetPrimary()) {
				name = v
			}
		}
		for _, m := range person.GetContactMethods() {
			e164 := m.GetPhone().GetCanonicalValue()
			if e164 == "" || name == "" {
				continue
			}
			if _, known := s.names[e164]; !known || m.GetDisplayInfo().GetPrimary() {
				s.names[e164] = name
			}
			people = append(people, Member{Phone: e164, Name: name})
		}
	}
	s.mu.Unlock()
	s.emit(map[string]any{"type": "contacts", "people": people})
}

func (s *Session) loadInbox(ctx context.Context) error {
	resp, err := s.client.ListThreads(ctx, gvproto.ThreadFolder_ALL_THREADS, "")
	if err != nil {
		return err
	}
	for _, t := range resp.GetThreads() {
		thread := convertThread(t, true)
		s.mu.Lock()
		s.lastSeen[thread.ID] = thread.LastAt
		s.mu.Unlock()
		s.emit(map[string]any{"type": "thread", "thread": thread})
	}
	return nil
}

// realtime keeps Google's push channel open; each nudge makes the list be read again.
func (s *Session) realtime(ctx context.Context) {
	retries := 0
	for ctx.Err() == nil {
		err := s.client.RunRealtimeChannel(ctx)
		switch {
		case ctx.Err() != nil:
			return
		case err == nil:
			retries = 0
		case libgv.IsAuthError(err):
			s.emit(map[string]any{"type": "loggedOut", "reason": err.Error()})
			return
		default:
			retries++
			if retries > maxRetries {
				s.emit(map[string]any{"type": "connectError", "reason": err.Error(), "fatal": true})
				return
			}
			s.log.Warn().Err(err).Int("retry", retries).Msg("Realtime channel dropped")
			select {
			case <-ctx.Done():
				return
			case <-time.After(retryWait):
			}
		}
	}
}

func (s *Session) handleEvent(ctx context.Context, raw any) {
	switch evt := raw.(type) {
	case *libgv.CookieChanged:
		if cookies, err := marshal(evt.Cookies); err == nil {
			s.emit(map[string]any{"type": "cookies", "cookies": cookies})
		}
	case *libgv.RealtimeConnected:
		s.emit(map[string]any{"type": "live"})
		s.nudge()
	case *libgv.RealtimeEvent:
		s.nudge()
	}
}

func (s *Session) nudge() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

func (s *Session) refreshLoop(ctx context.Context) {
	ticker := time.NewTicker(refreshEvery)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-s.wake:
			time.Sleep(refreshDelay)
		case <-ticker.C:
		}
		if err := s.refresh(ctx); err != nil {
			if libgv.IsAuthError(err) {
				s.emit(map[string]any{"type": "loggedOut", "reason": err.Error()})
				return
			}
			s.log.Warn().Err(err).Msg("Could not read the threads")
		}
	}
}

// refresh reads the thread list and reports what is new since last time.
func (s *Session) refresh(ctx context.Context) error {
	resp, err := s.client.ListThreads(ctx, gvproto.ThreadFolder_ALL_THREADS, "")
	if err != nil {
		return err
	}
	for _, t := range resp.GetThreads() {
		if len(t.GetMessages()) == 0 {
			continue
		}
		thread := convertThread(t, false)
		s.mu.Lock()
		before, known := s.lastSeen[thread.ID]
		s.lastSeen[thread.ID] = thread.LastAt
		s.mu.Unlock()
		if !known {
			full := convertThread(t, true)
			s.emit(map[string]any{"type": "thread", "thread": full})
			continue
		}
		s.emit(map[string]any{"type": "thread", "thread": thread})
		for i := len(t.GetMessages()) - 1; i >= 0; i-- {
			m := t.GetMessages()[i]
			if m.GetTimestamp() <= before {
				continue
			}
			s.emit(map[string]any{"type": "message", "message": convertMessage(thread.ID, m)})
		}
	}
	return nil
}

// Disconnect stops watching; the cookies stay.
func (s *Session) Disconnect() {
	s.mu.Lock()
	cancel := s.cancel
	s.cancel = nil
	s.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

// Close is Disconnect; nothing else is held.
func (s *Session) Close() { s.Disconnect() }

// Threads lists every thread (JSON array of Thread, newest messages included).
func (s *Session) Threads() (string, error) {
	ctx, cancel := s.ctx()
	defer cancel()
	resp, err := s.client.ListThreads(ctx, gvproto.ThreadFolder_ALL_THREADS, "")
	if err != nil {
		return "", wrap(err)
	}
	out := make([]Thread, 0, len(resp.GetThreads()))
	for _, t := range resp.GetThreads() {
		out = append(out, convertThread(t, true))
	}
	return marshal(out)
}

// Thread returns one thread with up to count messages, older than the page token names
// ("" for the newest), as JSON (Thread, with paginationToken for the next page).
func (s *Session) Thread(threadID string, count int, pageToken string) (string, error) {
	ctx, cancel := s.ctx()
	defer cancel()
	resp, err := s.client.GetThread(ctx, threadID, count, pageToken)
	if err != nil {
		return "", wrap(err)
	}
	return marshal(convertThread(resp.GetThread(), true))
}

// NameOf is the contact name Google has for a number, or "".
func (s *Session) NameOf(e164 string) string {
	s.mu.Lock()
	name, known := s.names[e164]
	s.mu.Unlock()
	if known {
		return name
	}
	ctx, cancel := s.ctx()
	defer cancel()
	found, err := s.client.LookupContact(ctx, e164)
	name = ""
	if err == nil {
		if match, ok := found[e164]; ok && match.GetFailureType() == gvproto.RespLookupContacts_Match_NO_FAILURE {
			for _, m := range match.GetAutocompletion().GetPerson().GetContactMethods() {
				if v := m.GetDisplayInfo().GetName().GetValue(); v != "" {
					name = v
					break
				}
			}
		}
	}
	s.mu.Lock()
	s.names[e164] = name
	s.mu.Unlock()
	return name
}

// SendText sends text to a thread ("t.<number>" starts a new one). Returns the Message as sent.
func (s *Session) SendText(threadID, text string) (string, error) {
	return s.send(threadID, &gvproto.ReqSendSMS{Text: text})
}

// SendMedia sends a picture (JPEG, PNG, GIF, WebP, BMP, TIFF) with optional text.
func (s *Session) SendMedia(threadID, path, mime, text string) (string, error) {
	kind, ok := mediaTypes[strings.ToLower(mime)]
	if !ok {
		return "", fmt.Errorf("%w: Google Voice sends pictures only, not %s", ErrRejected, mime)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	return s.send(threadID, &gvproto.ReqSendSMS{Text: text, Media: &gvproto.ReqSendSMS_Media{Type: kind, Data: data}})
}

var mediaTypes = map[string]gvproto.ReqSendSMS_Media_Type{
	"image/jpeg": gvproto.ReqSendSMS_Media_JPEG,
	"image/jpg":  gvproto.ReqSendSMS_Media_JPEG,
	"image/png":  gvproto.ReqSendSMS_Media_PNG,
	"image/gif":  gvproto.ReqSendSMS_Media_GIF,
	"image/webp": gvproto.ReqSendSMS_Media_WEBP,
	"image/bmp":  gvproto.ReqSendSMS_Media_BMP,
	"image/tiff": gvproto.ReqSendSMS_Media_TIFF,
}

func (s *Session) send(threadID string, req *gvproto.ReqSendSMS) (string, error) {
	req.ThreadID = threadID
	req.TransactionID = &gvproto.ReqSendSMS_WrappedTxnID{ID: libgv.GenerateTransactionID()}
	ctx, cancel := s.ctx()
	defer cancel()
	resp, err := s.client.SendMessage(ctx, req)
	if err != nil {
		return "", wrap(err)
	}
	thread := resp.GetThreadID()
	if thread == "" {
		thread = threadID
	}
	msg := Message{
		ID:        resp.GetThreadItemID(),
		Thread:    thread,
		FromMe:    true,
		Timestamp: resp.GetTimestampMS(),
		Kind:      "text",
		Text:      req.GetText(),
		Read:      true,
	}
	if msg.Timestamp == 0 {
		msg.Timestamp = time.Now().UnixMilli()
	}
	if req.Media != nil {
		msg.Kind = "media"
	}
	s.mu.Lock()
	if msg.Timestamp > s.lastSeen[thread] {
		s.lastSeen[thread] = msg.Timestamp
	}
	s.mu.Unlock()
	return marshal(msg)
}

// MarkRead tells Google Voice the thread is read.
func (s *Session) MarkRead(threadID string) error {
	ctx, cancel := s.ctx()
	defer cancel()
	_, err := s.client.UpdateThreadAttributes(ctx, &gvproto.ReqUpdateAttributes{
		Attributes:      &gvproto.ThreadAttributes{ThreadID: threadID, Read: true},
		OtherAttributes: &gvproto.ThreadAttributes{Read: true},
		UnknownInt:      1,
	})
	return wrap(err)
}

// Block marks the thread's people as blocked in Google Voice.
func (s *Session) Block(threadID string) error {
	ctx, cancel := s.ctx()
	defer cancel()
	_, err := s.client.UpdateThreadAttributes(ctx, &gvproto.ReqUpdateAttributes{
		Attributes:      &gvproto.ThreadAttributes{ThreadID: threadID, IsBlocked: true},
		OtherAttributes: &gvproto.ThreadAttributes{IsBlocked: true},
		UnknownInt:      1,
	})
	return wrap(err)
}

// DeleteThread removes the thread in Google Voice.
func (s *Session) DeleteThread(threadID string) error {
	ctx, cancel := s.ctx()
	defer cancel()
	_, err := s.client.DeleteThread(ctx, threadID)
	return wrap(err)
}

// Download fetches a picture or file by its media id into destPath; returns its MIME type.
func (s *Session) Download(mediaID, destPath string) (string, error) {
	ctx, cancel := context.WithTimeout(s.log.WithContext(context.Background()), 5*time.Minute)
	defer cancel()
	data, mime, err := s.client.DownloadAttachment(ctx, mediaID)
	if err != nil {
		return "", wrap(err)
	}
	temp := destPath + ".part"
	if err := os.WriteFile(temp, data, 0o600); err != nil {
		return "", err
	}
	return mime, os.Rename(temp, destPath)
}

func marshal(v any) (string, error) {
	raw, err := json.Marshal(v)
	if err != nil {
		return "", err
	}
	return string(raw), nil
}

func wrap(err error) error {
	switch {
	case err == nil:
		return nil
	case libgv.IsAuthError(err):
		return fmt.Errorf("%w: %v", ErrLoggedOut, err)
	case errors.Is(err, context.DeadlineExceeded):
		return fmt.Errorf("%w: Google Voice did not answer in time", ErrNotConnected)
	default:
		return err
	}
}
