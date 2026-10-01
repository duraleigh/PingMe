// SPDX-License-Identifier: AGPL-3.0-or-later

package gm

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"sync"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"go.mau.fi/util/exhttp"
	"go.mau.fi/util/ptr"
)

// EventSink receives every event as one JSON object. Kotlin implements it. Calls come
// from libgm's own goroutines and must return quickly.
type EventSink interface {
	OnEvent(json string)
}

// Errors Kotlin tells apart by the code before the colon.
var (
	ErrLoggedOut          = errors.New("LOGGED_OUT: Google Messages no longer accepts this pairing")
	ErrPhoneNotResponding = errors.New("PHONE_NOT_RESPONDING: the phone did not answer")
	ErrNotConnected       = errors.New("NOT_CONNECTED: the session is not connected")
	ErrRejected           = errors.New("REJECTED: the phone refused the request")
)

const requestTimeout = 60 * time.Second

// What the session remembers about a conversation, for sending.
type convInfo struct {
	isGroup    bool
	isRCS      bool
	autoSend   bool
	outgoingID string
}

// Session is one live pairing: the long-poll connection plus every request on it.
type Session struct {
	client *libgm.Client
	sink   EventSink
	log    zerolog.Logger

	mu        sync.Mutex
	convs     map[string]convInfo
	sims      map[string]*gmproto.SIMCard
	selfIDs   map[string]struct{}
	fullMedia map[string]struct{}

	ready             bool
	sessionID         string
	inactive          bool
	noDataRecently    bool
	didHackySetActive bool
	bgCancel          context.CancelFunc
}

// NewSession restores a session from the JSON that Login.Finish (or AuthJSON) gave.
func NewSession(authJSON string, sink EventSink) (*Session, error) {
	auth := &libgm.AuthData{}
	if err := json.Unmarshal([]byte(authJSON), auth); err != nil {
		return nil, fmt.Errorf("session JSON is invalid: %w", err)
	}
	if !auth.IsGoogleAccount() || !auth.HasCookies() {
		return nil, fmt.Errorf("%w: the saved session has no Google sign-in", ErrLoggedOut)
	}
	s := &Session{
		sink:      sink,
		log:       newLogger("libgm"),
		convs:     make(map[string]convInfo),
		sims:      make(map[string]*gmproto.SIMCard),
		selfIDs:   make(map[string]struct{}),
		fullMedia: make(map[string]struct{}),
	}
	s.client = libgm.NewClient(auth, nil, s.log, exhttp.SensibleClientSettings)
	s.client.SetEventHandler(s.handleEvent)
	return s, nil
}

// AuthJSON is the session as it stands now, to save after an "authUpdated" event.
func (s *Session) AuthJSON() string {
	data, err := json.Marshal(s.client.AuthData)
	if err != nil {
		return ""
	}
	return string(data)
}

// Connect opens the long-poll connection. It returns ErrLoggedOut (as a prefix) when
// Google no longer accepts the pairing, and other errors for transient failures.
func (s *Session) Connect() error {
	ctx, cancel := context.WithCancel(s.log.WithContext(context.Background()))
	s.mu.Lock()
	if s.bgCancel != nil {
		s.bgCancel()
	}
	s.bgCancel = cancel
	s.mu.Unlock()
	configCtx, configCancel := context.WithTimeout(ctx, requestTimeout)
	err := s.client.FetchConfig(configCtx)
	configCancel()
	if err != nil {
		s.log.Warn().Err(err).Msg("Failed to fetch config")
	} else if s.client.Config.GetDeviceInfo().GetEmail() == "" {
		cancel()
		return fmt.Errorf("%w: Google returned no account for these cookies", ErrLoggedOut)
	}
	if err := s.client.Connect(ctx); err != nil {
		cancel()
		if errors.Is(err, events.ErrRequestedEntityNotFound) || errors.Is(err, events.ErrInvalidCredentials) {
			return fmt.Errorf("%w: %w", ErrLoggedOut, err)
		}
		return err
	}
	return nil
}

// Disconnect closes the connection. Requests in flight fail with ErrConnectionClosed.
func (s *Session) Disconnect() {
	s.mu.Lock()
	if s.bgCancel != nil {
		s.bgCancel()
		s.bgCancel = nil
	}
	s.ready = false
	s.inactive = false
	s.mu.Unlock()
	s.client.Disconnect()
}

// SetActive makes this pairing the phone's active web session again, as Messages for
// web does when another tab took over.
func (s *Session) SetActive() error {
	ctx, cancel := s.requestContext()
	defer cancel()
	return s.client.SetActiveSession(ctx)
}

// Unpair tells the phone to forget this pairing.
func (s *Session) Unpair() error {
	ctx, cancel := s.requestContext()
	defer cancel()
	return s.client.Unpair(ctx)
}

// ListConversations returns a ConversationPage. folder is "inbox", "archive", or "spam".
func (s *Session) ListConversations(folder string, count int, cursorJSON string) (string, error) {
	cursor, err := parseCursor(cursorJSON)
	if err != nil {
		return "", err
	}
	req := &gmproto.ListConversationsRequest{Count: int64(count), Folder: gmproto.ListConversationsRequest_INBOX}
	switch folder {
	case "archive":
		req.Folder = gmproto.ListConversationsRequest_ARCHIVE
	case "spam":
		req.Folder = gmproto.ListConversationsRequest_SPAM_BLOCKED
	}
	if cursor != nil {
		req.Cursor = protoCursor(cursor)
	}
	ctx, cancel := s.requestContext()
	defer cancel()
	resp, err := s.client.ListConversations(ctx, req)
	if err != nil {
		return "", wrapRequestError(err)
	}
	page := ConversationPage{Conversations: make([]Conversation, 0, len(resp.GetConversations()))}
	for _, conv := range resp.GetConversations() {
		s.remember(conv)
		page.Conversations = append(page.Conversations, convertConversation(conv))
	}
	page.Cursor = convertCursor(resp.GetCursor())
	return marshal(page)
}

// GetConversation returns one Conversation, fresh from the phone.
func (s *Session) GetConversation(conversationID string) (string, error) {
	ctx, cancel := s.requestContext()
	defer cancel()
	conv, err := s.client.GetConversation(ctx, conversationID)
	if err != nil {
		return "", wrapRequestError(err)
	}
	if conv == nil || conv.GetConversationID() == "" {
		return "", fmt.Errorf("%w: no such conversation", ErrRejected)
	}
	s.remember(conv)
	return marshal(convertConversation(conv))
}

// FetchMessages returns a MessagePage of up to count messages, newest first, older than
// the cursor when one is given.
func (s *Session) FetchMessages(conversationID string, count int, cursorJSON string) (string, error) {
	cursor, err := parseCursor(cursorJSON)
	if err != nil {
		return "", err
	}
	ctx, cancel := s.requestContext()
	defer cancel()
	resp, err := s.client.FetchMessages(ctx, conversationID, int64(count), protoCursor(cursor))
	if err != nil {
		return "", wrapRequestError(err)
	}
	info := s.info(conversationID)
	page := MessagePage{
		Messages: make([]Message, 0, len(resp.GetMessages())),
		Cursor:   convertCursor(resp.GetCursor()),
		Total:    resp.GetTotalMessages(),
	}
	for _, msg := range resp.GetMessages() {
		page.Messages = append(page.Messages, s.convert(msg, info))
	}
	return marshal(page)
}

// SendRequest is what SendMessage takes, as JSON.
type SendRequest struct {
	ConversationID string  `json:"conversationId"`
	TmpID          string  `json:"tmpId"`
	Text           string  `json:"text,omitempty"`
	ReplyToID      string  `json:"replyToId,omitempty"`
	Media          []Media `json:"media,omitempty"`
}

// SendMessage sends text and/or uploaded media (from UploadMedia). The message itself
// arrives later as a "message" event carrying the same tmpId. Transient refusals are
// retried a few times, as the reference bridge does.
func (s *Session) SendMessage(requestJSON string) error {
	var req SendRequest
	if err := json.Unmarshal([]byte(requestJSON), &req); err != nil {
		return fmt.Errorf("send request is not valid JSON: %w", err)
	}
	if req.ConversationID == "" || req.TmpID == "" {
		return errors.New("send request needs conversationId and tmpId")
	}
	if req.Text == "" && len(req.Media) == 0 {
		return errors.New("send request has nothing to send")
	}
	info, err := s.infoOrFetch(req.ConversationID)
	if err != nil {
		return err
	}
	sim := s.sim(info.outgoingID)
	payload := &gmproto.SendMessageRequest{
		ConversationID: req.ConversationID,
		MessagePayload: &gmproto.MessagePayload{
			TmpID:          req.TmpID,
			ConversationID: req.ConversationID,
			ParticipantID:  info.outgoingID,
			TmpID2:         req.TmpID,
		},
		SIMPayload: sim.GetSIMData().GetSIMPayload(),
		TmpID:      req.TmpID,
		ForceRCS:   info.isRCS && info.autoSend,
	}
	if req.ReplyToID != "" {
		payload.Reply = &gmproto.ReplyPayload{MessageID: req.ReplyToID}
	}
	for _, m := range req.Media {
		key, err := base64.StdEncoding.DecodeString(m.Key)
		if err != nil {
			return fmt.Errorf("media key is not base64: %w", err)
		}
		payload.MessagePayload.MessageInfo = append(payload.MessagePayload.MessageInfo, &gmproto.MessageInfo{
			Data: &gmproto.MessageInfo_MediaContent{MediaContent: &gmproto.MediaContent{
				Format:        gmproto.MediaFormats(m.Format),
				MediaID:       m.MediaID,
				MediaName:     m.Name,
				Size:          m.Size,
				DecryptionKey: key,
				MimeType:      m.Mime,
			}},
		})
	}
	if req.Text != "" {
		payload.MessagePayload.MessageInfo = append(payload.MessagePayload.MessageInfo, &gmproto.MessageInfo{
			Data: &gmproto.MessageInfo_MessageContent{MessageContent: &gmproto.MessageContent{Content: req.Text}},
		})
	}
	ctx, cancel := context.WithTimeout(s.log.WithContext(context.Background()), 3*time.Minute)
	defer cancel()
	resp, err := s.client.SendMessage(ctx, payload)
	for attempt := 0; err == nil && isTransientSendFailure(resp.GetStatus()) && attempt < len(sendRetryBackoff); attempt++ {
		s.log.Warn().Stringer("status", resp.GetStatus()).Int("attempt", attempt+1).Msg("Phone refused the send; retrying")
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(sendRetryBackoff[attempt]):
		}
		resp, err = s.client.SendMessage(ctx, payload)
	}
	if err != nil {
		return wrapRequestError(err)
	}
	if resp.GetStatus() != gmproto.SendMessageResponse_SUCCESS {
		return fmt.Errorf("%w: send status %s", ErrRejected, resp.GetStatus())
	}
	return nil
}

var sendRetryBackoff = []time.Duration{3 * time.Second, 8 * time.Second, 20 * time.Second}

func isTransientSendFailure(status gmproto.SendMessageResponse_Status) bool {
	return status == gmproto.SendMessageResponse_FAILURE_2 || status == gmproto.SendMessageResponse_FAILURE_3
}

// UploadMedia encrypts and uploads a file and returns the Media to put in a SendRequest.
func (s *Session) UploadMedia(path, fileName, mime string) (string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	content, err := s.client.UploadMedia(data, fileName, mime)
	if err != nil {
		return "", wrapRequestError(err)
	}
	return marshal(convertMedia("", content))
}

// DownloadMedia fetches and decrypts one attachment into destPath.
func (s *Session) DownloadMedia(mediaID, keyBase64, destPath string) error {
	key, err := base64.StdEncoding.DecodeString(keyBase64)
	if err != nil {
		return fmt.Errorf("media key is not base64: %w", err)
	}
	reader, err := s.client.DownloadMedia(mediaID, key)
	if err != nil {
		return wrapRequestError(err)
	}
	defer reader.Close()
	temp := destPath + ".part"
	file, err := os.Create(temp)
	if err != nil {
		return err
	}
	if _, err = io.Copy(file, reader); err != nil {
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

// RequestFullSizeMedia asks the phone to upload the full file behind a thumbnail; the
// message then comes again as a "message" event with the full media ID. Asked once per part.
func (s *Session) RequestFullSizeMedia(messageID, partID string) error {
	if partID == "" {
		return nil
	}
	key := messageID + "/" + partID
	s.mu.Lock()
	_, done := s.fullMedia[key]
	s.fullMedia[key] = struct{}{}
	s.mu.Unlock()
	if done {
		return nil
	}
	ctx, cancel := s.requestContext()
	defer cancel()
	_, err := s.client.GetFullSizeImage(ctx, messageID, partID)
	return wrapRequestError(err)
}

// SendReaction adds, removes, or switches (replaces your own) an emoji on a message.
func (s *Session) SendReaction(conversationID, messageID, emoji, action string) error {
	req := &gmproto.SendReactionRequest{
		MessageID:    messageID,
		ReactionData: gmproto.MakeReactionData(emoji),
	}
	switch action {
	case "add":
		req.Action = gmproto.SendReactionRequest_ADD
	case "remove":
		req.Action = gmproto.SendReactionRequest_REMOVE
	case "switch":
		req.Action = gmproto.SendReactionRequest_SWITCH
	default:
		return fmt.Errorf("unknown reaction action %q", action)
	}
	if action != "remove" {
		req.SIMPayload = s.sim(s.info(conversationID).outgoingID).GetSIMData().GetSIMPayload()
	}
	ctx, cancel := s.requestContext()
	defer cancel()
	resp, err := s.client.SendReaction(ctx, req)
	if err != nil {
		return wrapRequestError(err)
	}
	if !resp.GetSuccess() {
		return fmt.Errorf("%w: reaction not accepted", ErrRejected)
	}
	return nil
}

// DeleteMessage deletes a message on the phone (for this user; Google Messages exposes
// no delete-for-everyone to paired devices).
func (s *Session) DeleteMessage(messageID string) error {
	ctx, cancel := s.requestContext()
	defer cancel()
	resp, err := s.client.DeleteMessage(ctx, messageID)
	if err != nil {
		return wrapRequestError(err)
	}
	if !resp.GetSuccess() {
		return fmt.Errorf("%w: delete not accepted", ErrRejected)
	}
	return nil
}

// MarkRead marks a conversation read up to a message; the phone sends the read receipt.
func (s *Session) MarkRead(conversationID, messageID string) error {
	ctx, cancel := s.requestContext()
	defer cancel()
	return wrapRequestError(s.client.MarkRead(ctx, conversationID, messageID))
}

// SetTyping tells the other side you are typing. Google Messages has no "stopped" call;
// the indicator times out on its own.
func (s *Session) SetTyping(conversationID string) error {
	ctx, cancel := s.requestContext()
	defer cancel()
	sim := s.sim(s.info(conversationID).outgoingID)
	return wrapRequestError(s.client.SetTyping(ctx, conversationID, sim.GetSIMData().GetSIMPayload()))
}

// GetOrCreateConversation finds or starts a chat with the phone numbers in numbersJSON
// (a JSON array of strings). More than one number makes a group; groupName names an RCS
// group. Returns the Conversation.
func (s *Session) GetOrCreateConversation(numbersJSON, groupName string) (string, error) {
	var numbers []string
	if err := json.Unmarshal([]byte(numbersJSON), &numbers); err != nil {
		return "", fmt.Errorf("numbers are not a JSON array: %w", err)
	}
	if len(numbers) == 0 {
		return "", errors.New("at least one number is needed")
	}
	req := &gmproto.GetOrCreateConversationRequest{Numbers: make([]*gmproto.ContactNumber, len(numbers))}
	for i, n := range numbers {
		// 2 is what the reference bridge sends for a contact's number.
		req.Numbers[i] = &gmproto.ContactNumber{MysteriousInt: 2, Number: n, Number2: n}
	}
	if len(numbers) > 1 {
		req.RCSGroupName = ptr.NonZero(groupName)
	}
	ctx, cancel := s.requestContext()
	defer cancel()
	resp, err := s.client.GetOrCreateConversation(ctx, req)
	if err == nil && resp.GetStatus() == gmproto.GetOrCreateConversationResponse_CREATE_RCS {
		if req.RCSGroupName == nil {
			req.RCSGroupName = ptr.Ptr("")
		}
		req.CreateRCSGroup = ptr.Ptr(true)
		resp, err = s.client.GetOrCreateConversation(ctx, req)
	}
	if err != nil {
		return "", wrapRequestError(err)
	}
	conv := resp.GetConversation()
	if conv.GetConversationID() == "" {
		return "", fmt.Errorf("%w: no conversation came back (status %s)", ErrRejected, resp.GetStatus())
	}
	s.remember(conv)
	return marshal(convertConversation(conv))
}

// --- libgm events ---

func (s *Session) handleEvent(rawEvt any) {
	switch evt := rawEvt.(type) {
	case *events.ListenFatalError:
		if errors.Is(evt.Error, events.ErrInvalidCredentials) || evt.Error.Error() == "http 401 while polling" {
			s.emit(map[string]any{"type": "loggedOut", "reason": "invalidCredentials", "error": evt.Error.Error()})
		} else {
			s.emit(map[string]any{"type": "fatal", "error": evt.Error.Error()})
		}
	case *events.ListenTemporaryError:
		s.emit(map[string]any{"type": "temporaryError", "error": evt.Error.Error()})
	case *events.ListenRecovered:
		s.emit(map[string]any{"type": "recovered"})
	case *events.PhoneNotResponding:
		s.emit(map[string]any{"type": "phone", "responding": false})
	case *events.PhoneRespondingAgain:
		s.emit(map[string]any{"type": "phone", "responding": true})
	case *events.HackySetActiveMayFail:
		go s.hackyResetActive()
	case *events.PingFailed:
		if errors.Is(evt.Error, events.ErrRequestedEntityNotFound) {
			s.emit(map[string]any{"type": "loggedOut", "reason": "unpaired", "error": evt.Error.Error()})
		} else if evt.ErrorCount > 1 {
			s.emit(map[string]any{"type": "temporaryError", "error": evt.Error.Error()})
		}
	case *gmproto.RevokePairData:
		s.emit(map[string]any{"type": "loggedOut", "reason": "revoked"})
	case *events.GaiaLoggedOut:
		s.emit(map[string]any{"type": "loggedOut", "reason": "signedOut"})
	case *events.AuthTokenRefreshed:
		s.emit(map[string]any{"type": "authUpdated", "auth": s.AuthJSON()})
	case *gmproto.Conversation:
		s.remember(evt)
		s.dataArrived()
		s.emit(map[string]any{"type": "conversation", "conversation": convertConversation(evt)})
	case *libgm.WrappedMessage:
		s.dataArrived()
		s.emit(map[string]any{"type": "message", "message": s.convert(evt.Message, s.info(evt.GetConversationID())), "isOld": evt.IsOld})
	case *gmproto.TypingData:
		s.emit(map[string]any{
			"type":           "typing",
			"conversationId": evt.GetConversationID(),
			"number":         evt.GetUser().GetNumber(),
			"typing":         evt.GetType() == gmproto.TypingTypes_STARTED_TYPING,
		})
	case *gmproto.UserAlertEvent:
		s.handleUserAlert(evt)
	case *gmproto.Settings:
		s.handleSettings(evt)
	case *events.AccountChange:
		s.emit(map[string]any{"type": "accountChange", "account": evt.GetAccount(), "enabled": evt.GetEnabled(), "fake": evt.IsFake})
	case *events.NoDataReceived:
		s.mu.Lock()
		s.noDataRecently = true
		s.mu.Unlock()
		s.emit(map[string]any{"type": "noData"})
	default:
		s.log.Trace().Type("event_type", rawEvt).Msg("Unhandled libgm event")
	}
}

func (s *Session) handleUserAlert(evt *gmproto.UserAlertEvent) {
	switch evt.GetAlertType() {
	case gmproto.AlertType_BROWSER_ACTIVE:
		s.mu.Lock()
		wasInactive := s.inactive || !s.ready
		newSessionID := s.client.CurrentSessionID()
		changed := s.sessionID != newSessionID
		s.sessionID = newSessionID
		s.inactive = false
		s.ready = true
		resync := changed || wasInactive || s.noDataRecently
		s.noDataRecently = false
		s.mu.Unlock()
		s.emit(map[string]any{"type": "ready", "resync": resync})
	case gmproto.AlertType_BROWSER_INACTIVE,
		gmproto.AlertType_BROWSER_INACTIVE_FROM_TIMEOUT,
		gmproto.AlertType_BROWSER_INACTIVE_FROM_INACTIVITY:
		s.mu.Lock()
		s.inactive = true
		s.mu.Unlock()
		s.emit(map[string]any{"type": "inactive", "reason": evt.GetAlertType().String()})
		// Take the session back, as the reference bridge's aggressive reconnect does.
		go s.aggressiveSetActive()
	case gmproto.AlertType_MOBILE_DATABASE_SYNC_COMPLETE:
		s.emit(map[string]any{"type": "phoneSynced"})
	case gmproto.AlertType_MOBILE_BATTERY_LOW:
		s.emit(map[string]any{"type": "battery", "low": true})
	case gmproto.AlertType_MOBILE_BATTERY_RESTORED:
		s.emit(map[string]any{"type": "battery", "low": false})
	default:
		s.log.Debug().Stringer("alert", evt.GetAlertType()).Msg("Alert")
	}
}

func (s *Session) handleSettings(settings *gmproto.Settings) {
	if settings.GetSIMCards() == nil {
		return
	}
	s.mu.Lock()
	s.sims = make(map[string]*gmproto.SIMCard, len(settings.GetSIMCards()))
	for _, sim := range settings.GetSIMCards() {
		id := sim.GetSIMParticipant().GetID()
		s.sims[id] = sim
		if id != "" {
			s.selfIDs[id] = struct{}{}
		}
	}
	s.mu.Unlock()
	s.emit(map[string]any{"type": "settings", "settings": convertSettings(settings)})
}

func (s *Session) aggressiveSetActive() {
	for _, wait := range []time.Duration{5 * time.Second, 10 * time.Second, 30 * time.Second} {
		time.Sleep(wait)
		s.mu.Lock()
		inactive := s.inactive
		s.mu.Unlock()
		if !inactive {
			return
		}
		if err := s.SetActive(); err == nil {
			return
		} else {
			s.log.Warn().Err(err).Msg("Could not take the session back")
		}
	}
}

// hackyResetActive is the reference bridge's answer to a connection that never reports
// ready: set the session active again, and if that does not help, reconnect.
func (s *Session) hackyResetActive() {
	s.mu.Lock()
	if s.didHackySetActive {
		s.mu.Unlock()
		return
	}
	s.didHackySetActive = true
	s.noDataRecently = false
	s.mu.Unlock()
	time.Sleep(7 * time.Second)
	if s.isReady() {
		return
	}
	s.log.Warn().Msg("Not ready yet; setting the session active again")
	if err := s.SetActive(); err != nil {
		s.log.Err(err).Msg("Could not set the session active")
	}
	time.Sleep(7 * time.Second)
	if s.isReady() {
		return
	}
	s.log.Warn().Msg("Still not ready; reconnecting")
	s.emit(map[string]any{"type": "reconnect"})
}

func (s *Session) isReady() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.ready
}

func (s *Session) dataArrived() {
	s.mu.Lock()
	s.noDataRecently = false
	s.mu.Unlock()
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

// --- bookkeeping ---

func (s *Session) remember(conv *gmproto.Conversation) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.convs[conv.GetConversationID()] = convInfo{
		isGroup:    conv.GetIsGroupChat(),
		isRCS:      conv.GetType() == gmproto.ConversationType_RCS,
		autoSend:   conv.GetSendMode() == gmproto.ConversationSendMode_SEND_MODE_AUTO,
		outgoingID: conv.GetDefaultOutgoingID(),
	}
	for _, p := range conv.GetParticipants() {
		if p.GetIsMe() && p.GetID().GetParticipantID() != "" {
			s.selfIDs[p.GetID().GetParticipantID()] = struct{}{}
		}
	}
}

func (s *Session) info(conversationID string) convInfo {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.convs[conversationID]
}

func (s *Session) infoOrFetch(conversationID string) (convInfo, error) {
	s.mu.Lock()
	info, ok := s.convs[conversationID]
	s.mu.Unlock()
	if ok {
		return info, nil
	}
	if _, err := s.GetConversation(conversationID); err != nil {
		return convInfo{}, err
	}
	return s.info(conversationID), nil
}

func (s *Session) sim(participantID string) *gmproto.SIMCard {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.sims[participantID]
}

// convert adds what only the session knows: whether the sender is this phone.
func (s *Session) convert(msg *gmproto.Message, info convInfo) Message {
	out := convertMessage(msg, !info.isGroup, info.isRCS)
	s.mu.Lock()
	_, self := s.selfIDs[msg.GetParticipantID()]
	s.mu.Unlock()
	switch out.Direction {
	case "outgoing":
		out.FromMe = true
	case "incoming":
		out.FromMe = false
	default:
		out.FromMe = self || msg.GetParticipantID() == "1"
	}
	return out
}

func (s *Session) requestContext() (context.Context, context.CancelFunc) {
	return context.WithTimeout(s.log.WithContext(context.Background()), requestTimeout)
}

func parseCursor(cursorJSON string) (*Cursor, error) {
	if cursorJSON == "" {
		return nil, nil
	}
	var cursor Cursor
	if err := json.Unmarshal([]byte(cursorJSON), &cursor); err != nil {
		return nil, fmt.Errorf("cursor is not valid JSON: %w", err)
	}
	return &cursor, nil
}

func wrapRequestError(err error) error {
	switch {
	case err == nil:
		return nil
	case errors.Is(err, libgm.ErrPhoneNotResponding):
		return fmt.Errorf("%w: %w", ErrPhoneNotResponding, err)
	case errors.Is(err, libgm.ErrConnectionClosed), errors.Is(err, libgm.ErrClientIsNil):
		return fmt.Errorf("%w: %w", ErrNotConnected, err)
	case errors.Is(err, events.ErrRequestedEntityNotFound), errors.Is(err, events.ErrInvalidCredentials):
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
