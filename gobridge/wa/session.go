// SPDX-License-Identifier: AGPL-3.0-or-later

package wa

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"sync"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"

)

// EventSink receives every event as one JSON object. Kotlin implements it. Calls come
// from whatsmeow's own goroutines and must return quickly.
type EventSink interface {
	OnEvent(json string)
}

// Errors Kotlin tells apart by the code before the colon.
var (
	ErrNotLoggedIn  = errors.New("NOT_LOGGED_IN: this phone number is not linked")
	ErrNotConnected = errors.New("NOT_CONNECTED: not connected to WhatsApp")
	ErrRejected     = errors.New("REJECTED: WhatsApp refused the request")
)

const requestTimeout = 60 * time.Second

// Session is one linked WhatsApp account: its device keys in a SQLite file, the live
// connection, and every request on it.
type Session struct {
	container *sqlstore.Container
	device    *store.Device
	client    *whatsmeow.Client
	sink      EventSink
	log       zerolog.Logger

	mu    sync.Mutex
	names map[string]string
}

// NewSession opens (or creates) the device store at dbPath. A fresh store is not linked
// yet: PairCode links it; IsLoggedIn says which.
func NewSession(dbPath string, sink EventSink) (*Session, error) {
	ctx := context.Background()
	container, err := sqlstore.New(ctx, "sqlite3", storeAddress(dbPath), waLogger("store"))
	if err != nil {
		return nil, fmt.Errorf("could not open the WhatsApp store: %w", err)
	}
	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		_ = container.Close()
		return nil, fmt.Errorf("could not read the WhatsApp store: %w", err)
	}
	s := &Session{
		container: container,
		device:    device,
		sink:      sink,
		log:       newLogger("whatsmeow"),
		names:     make(map[string]string),
	}
	s.client = whatsmeow.NewClient(device, waLogger("client"))
	s.client.EnableAutoReconnect = true
	s.client.AutomaticMessageRerequestFromPhone = true
	s.client.AddEventHandler(s.handleEvent)
	return s, nil
}

// IsLoggedIn is whether the store holds a linked device.
func (s *Session) IsLoggedIn() bool {
	return s.client.Store.ID != nil
}

// OwnID is the account's own user id (phone number form), or "" before linking.
func (s *Session) OwnID() string {
	if s.client.Store.ID == nil {
		return ""
	}
	return s.client.Store.ID.ToNonAD().String()
}

// OwnPhone is the account's phone number digits, or "" before linking.
func (s *Session) OwnPhone() string {
	if s.client.Store.ID == nil {
		return ""
	}
	return s.client.Store.ID.User
}

// OwnLID is the account's hidden id, which group messages may come from.
func (s *Session) OwnLID() string {
	if s.client.Store.LID.IsEmpty() {
		return ""
	}
	return s.client.Store.LID.ToNonAD().String()
}

// PushName is the name WhatsApp shows for this account.
func (s *Session) PushName() string {
	return s.client.Store.PushName
}

// Connect opens the connection. Before linking it connects anonymously so PairCode can run.
func (s *Session) Connect() error {
	if s.client.IsConnected() {
		return nil
	}
	return s.client.Connect()
}

// Disconnect closes the connection and stops the automatic reconnects.
func (s *Session) Disconnect() {
	s.client.Disconnect()
}

// Close disconnects and closes the store. The session is unusable afterwards.
func (s *Session) Close() {
	s.client.Disconnect()
	_ = s.container.Close()
}

// Logout unlinks this device from the account on WhatsApp's side and clears the store.
func (s *Session) Logout() error {
	ctx, cancel := s.ctx()
	defer cancel()
	return s.client.Logout(ctx)
}

// PairCode asks WhatsApp for the eight-character code the user types into WhatsApp on
// the phone (Linked devices > Link a device > Link with phone number instead). The
// number is international, digits only. Linking finishes with a "pairSuccess" event,
// after which the connection comes up as the linked device by itself.
func (s *Session) PairCode(phone string) (string, error) {
	if s.IsLoggedIn() {
		return "", fmt.Errorf("%w: already linked", ErrRejected)
	}
	if err := s.Connect(); err != nil {
		return "", err
	}
	ctx, cancel := s.ctx()
	defer cancel()
	code, err := s.client.PairPhone(ctx, phone, true, whatsmeow.PairClientChrome, "Chrome (Android)")
	if err != nil {
		return "", err
	}
	return code, nil
}

// ListGroups returns every joined group, communities included, as a JSON array of Chat.
func (s *Session) ListGroups() (string, error) {
	ctx, cancel := s.ctx()
	defer cancel()
	groups, err := s.client.GetJoinedGroups(ctx)
	if err != nil {
		return "", wrap(err)
	}
	chats := make([]Chat, 0, len(groups))
	for _, g := range groups {
		chats = append(chats, convertGroup(g, s.me(), s.nameOf))
	}
	return marshal(chats)
}

// GroupInfo returns one group as a Chat.
func (s *Session) GroupInfo(jid string) (string, error) {
	group, err := parseJID(jid)
	if err != nil {
		return "", err
	}
	ctx, cancel := s.ctx()
	defer cancel()
	info, err := s.client.GetGroupInfo(ctx, group)
	if err != nil {
		return "", wrap(err)
	}
	return marshal(convertGroup(info, s.me(), s.nameOf))
}

// Contacts returns everyone the phone's WhatsApp knows, as a JSON array of Participant.
func (s *Session) Contacts() (string, error) {
	ctx, cancel := s.ctx()
	defer cancel()
	all, err := s.client.Store.Contacts.GetAllContacts(ctx)
	if err != nil {
		return "", err
	}
	people := make([]Participant, 0, len(all))
	for jid, info := range all {
		name := contactName(info)
		if name == "" {
			continue
		}
		p := Participant{ID: jid.ToNonAD().String(), Name: name}
		if jid.Server == types.DefaultUserServer {
			p.Phone = jid.User
		}
		people = append(people, p)
	}
	return marshal(people)
}

// ContactName is the name the phone's WhatsApp has for a user, or "".
func (s *Session) ContactName(jid string) string {
	user, err := parseJID(jid)
	if err != nil {
		return ""
	}
	return s.nameOf(user)
}

// PhoneOf resolves a hidden (LID) user id to a phone number, or "".
func (s *Session) PhoneOf(jid string) string {
	user, err := parseJID(jid)
	if err != nil {
		return ""
	}
	return s.phoneOf(user)
}

// SendText sends text, as a reply when replyJSON (a ReplyRequest) is not empty. Returns
// the Message as sent.
func (s *Session) SendText(chat, text, replyJSON string) (string, error) {
	to, err := parseJID(chat)
	if err != nil {
		return "", err
	}
	reply, err := parseReply(replyJSON)
	if err != nil {
		return "", err
	}
	msg := &waE2E.Message{}
	if reply == nil {
		msg.Conversation = proto.String(text)
	} else {
		msg.ExtendedTextMessage = &waE2E.ExtendedTextMessage{Text: proto.String(text), ContextInfo: s.contextFor(to, reply)}
	}
	return s.send(to, msg, Message{Kind: "text", Text: text, ReplyTo: quoteOf(reply)})
}

// SendMedia uploads a file and sends it. kind is "image", "video", "gif", "voice",
// "audio", "document", or "sticker"; voice notes go as push-to-talk audio.
func (s *Session) SendMedia(chat, path, mime, kind, fileName, caption string, seconds, width, height int64, replyJSON string) (string, error) {
	to, err := parseJID(chat)
	if err != nil {
		return "", err
	}
	reply, err := parseReply(replyJSON)
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	mediaType := whatsmeow.MediaDocument
	switch kind {
	case "image", "sticker":
		mediaType = whatsmeow.MediaImage
	case "video", "gif":
		mediaType = whatsmeow.MediaVideo
	case "voice", "audio":
		mediaType = whatsmeow.MediaAudio
	}
	ctx, cancel := context.WithTimeout(s.log.WithContext(context.Background()), 5*time.Minute)
	defer cancel()
	up, err := s.client.Upload(ctx, data, mediaType)
	if err != nil {
		return "", wrap(err)
	}
	info := s.contextFor(to, reply)
	msg := &waE2E.Message{}
	media := &Media{Type: string(mediaType), Mime: mime, DirectPath: up.DirectPath, URL: up.URL, MediaKey: b64(up.MediaKey),
		FileSHA256: b64(up.FileSHA256), FileEncSHA256: b64(up.FileEncSHA256), FileLength: int64(up.FileLength),
		Width: width, Height: height, Seconds: seconds, FileName: fileName, Caption: caption}
	switch kind {
	case "image":
		msg.ImageMessage = &waE2E.ImageMessage{URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, FileSHA256: up.FileSHA256,
			FileEncSHA256: up.FileEncSHA256, FileLength: &up.FileLength, Mimetype: &mime, Caption: optional(caption),
			Width: u32(width), Height: u32(height), ContextInfo: info}
	case "sticker":
		msg.StickerMessage = &waE2E.StickerMessage{URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, FileSHA256: up.FileSHA256,
			FileEncSHA256: up.FileEncSHA256, FileLength: &up.FileLength, Mimetype: &mime, Width: u32(width), Height: u32(height), ContextInfo: info}
	case "video", "gif":
		gif := kind == "gif"
		media.Gif = gif
		msg.VideoMessage = &waE2E.VideoMessage{URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, FileSHA256: up.FileSHA256,
			FileEncSHA256: up.FileEncSHA256, FileLength: &up.FileLength, Mimetype: &mime, Caption: optional(caption),
			Seconds: u32(seconds), Width: u32(width), Height: u32(height), GifPlayback: &gif, ContextInfo: info}
	case "voice", "audio":
		ptt := kind == "voice"
		media.Voice = ptt
		msg.AudioMessage = &waE2E.AudioMessage{URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, FileSHA256: up.FileSHA256,
			FileEncSHA256: up.FileEncSHA256, FileLength: &up.FileLength, Mimetype: &mime, Seconds: u32(seconds), PTT: &ptt, ContextInfo: info}
	default:
		kind = "document"
		msg.DocumentMessage = &waE2E.DocumentMessage{URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, FileSHA256: up.FileSHA256,
			FileEncSHA256: up.FileEncSHA256, FileLength: &up.FileLength, Mimetype: &mime, FileName: optional(fileName),
			Title: optional(fileName), Caption: optional(caption), ContextInfo: info}
	}
	return s.send(to, msg, Message{Kind: kind, Text: caption, Media: media, ReplyTo: quoteOf(reply)})
}

// SendReaction puts emoji on a message, or takes yours away when emoji is "".
func (s *Session) SendReaction(chat, targetID, targetSender string, targetFromMe bool, emoji string) error {
	to, err := parseJID(chat)
	if err != nil {
		return err
	}
	sender := s.targetSender(targetSender, targetFromMe)
	_, err = s.sendRaw(to, s.client.BuildReaction(to, sender, targetID, emoji))
	return err
}

// Revoke deletes one of your messages for everyone.
func (s *Session) Revoke(chat, targetID, targetSender string, targetFromMe bool) error {
	to, err := parseJID(chat)
	if err != nil {
		return err
	}
	sender := s.targetSender(targetSender, targetFromMe)
	_, err = s.sendRaw(to, s.client.BuildRevoke(to, sender, targetID))
	return err
}

// Edit changes the text of one of your messages.
func (s *Session) Edit(chat, targetID, text string) error {
	to, err := parseJID(chat)
	if err != nil {
		return err
	}
	_, err = s.sendRaw(to, s.client.BuildEdit(to, targetID, &waE2E.Message{Conversation: proto.String(text)}))
	return err
}

// MarkRead sends read receipts for the messages in idsJSON (a JSON array), which
// sender sent in chat.
func (s *Session) MarkRead(chat, sender, idsJSON string, timestamp int64) error {
	to, err := parseJID(chat)
	if err != nil {
		return err
	}
	from := to
	if sender != "" {
		if from, err = parseJID(sender); err != nil {
			return err
		}
	}
	var ids []string
	if err := json.Unmarshal([]byte(idsJSON), &ids); err != nil {
		return fmt.Errorf("ids are not a JSON array: %w", err)
	}
	if len(ids) == 0 {
		return nil
	}
	at := time.Now()
	if timestamp > 0 {
		at = time.UnixMilli(timestamp)
	}
	ctx, cancel := s.ctx()
	defer cancel()
	return wrap(s.client.MarkRead(ctx, ids, at, to, from))
}

// SetTyping tells the chat you are typing, or that you stopped.
func (s *Session) SetTyping(chat string, typing bool) error {
	to, err := parseJID(chat)
	if err != nil {
		return err
	}
	state := types.ChatPresencePaused
	if typing {
		state = types.ChatPresenceComposing
	}
	ctx, cancel := s.ctx()
	defer cancel()
	return wrap(s.client.SendChatPresence(ctx, to, state, types.ChatPresenceMediaText))
}

// Download fetches and decrypts a message's media (mediaJSON is its Media) into destPath.
func (s *Session) Download(mediaJSON, destPath string) error {
	var media Media
	if err := json.Unmarshal([]byte(mediaJSON), &media); err != nil {
		return fmt.Errorf("media is not valid JSON: %w", err)
	}
	key, _ := base64.StdEncoding.DecodeString(media.MediaKey)
	sha, _ := base64.StdEncoding.DecodeString(media.FileSHA256)
	encSha, _ := base64.StdEncoding.DecodeString(media.FileEncSHA256)
	ctx, cancel := context.WithTimeout(s.log.WithContext(context.Background()), 5*time.Minute)
	defer cancel()
	data, err := s.client.DownloadMediaWithPath(ctx, media.DirectPath, encSha, sha, key, whatsmeow.MediaType(media.Type), "", false)
	if err != nil {
		return wrap(err)
	}
	temp := destPath + ".part"
	if err := os.WriteFile(temp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(temp, destPath)
}

// RequestHistory asks the phone for up to count messages older than the given one in
// chat. They arrive as a "history" event of sync type ON_DEMAND.
func (s *Session) RequestHistory(chat, lastID string, lastTimestamp int64, lastFromMe bool, count int) error {
	to, err := parseJID(chat)
	if err != nil {
		return err
	}
	if s.client.Store.ID == nil {
		return ErrNotLoggedIn
	}
	last := &types.MessageInfo{
		MessageSource: types.MessageSource{Chat: to, IsFromMe: lastFromMe, IsGroup: isGroup(to)},
		ID:            lastID,
		Timestamp:     time.UnixMilli(lastTimestamp),
	}
	ctx, cancel := s.ctx()
	defer cancel()
	msg := s.client.BuildHistorySyncRequest(last, count)
	_, err = s.client.SendMessage(ctx, s.client.Store.ID.ToNonAD(), msg, whatsmeow.SendRequestExtra{Peer: true})
	return wrap(err)
}

// CheckNumber returns the user id for an international phone number on WhatsApp, or "".
func (s *Session) CheckNumber(phone string) (string, error) {
	ctx, cancel := s.ctx()
	defer cancel()
	found, err := s.client.IsOnWhatsApp(ctx, []string{phone})
	if err != nil {
		return "", wrap(err)
	}
	for _, f := range found {
		if f.IsIn {
			return f.JID.ToNonAD().String(), nil
		}
	}
	return "", nil
}

// CreateGroup makes a group with the user ids in participantsJSON (a JSON array).
func (s *Session) CreateGroup(name, participantsJSON string) (string, error) {
	var ids []string
	if err := json.Unmarshal([]byte(participantsJSON), &ids); err != nil {
		return "", fmt.Errorf("participants are not a JSON array: %w", err)
	}
	req := whatsmeow.ReqCreateGroup{Name: name}
	for _, id := range ids {
		jid, err := parseJID(id)
		if err != nil {
			return "", err
		}
		req.Participants = append(req.Participants, jid)
	}
	ctx, cancel := s.ctx()
	defer cancel()
	info, err := s.client.CreateGroup(ctx, req)
	if err != nil {
		return "", wrap(err)
	}
	return marshal(convertGroup(info, s.me(), s.nameOf))
}

// Block blocks a user on WhatsApp.
func (s *Session) Block(jid string) error {
	user, err := parseJID(jid)
	if err != nil {
		return err
	}
	ctx, cancel := s.ctx()
	defer cancel()
	_, err = s.client.UpdateBlocklist(ctx, user, events.BlocklistChangeActionBlock)
	return wrap(err)
}

// ProfilePictureURL is a link to a user's or group's picture, or "" when there is none.
func (s *Session) ProfilePictureURL(jid string) string {
	who, err := parseJID(jid)
	if err != nil {
		return ""
	}
	ctx, cancel := s.ctx()
	defer cancel()
	info, err := s.client.GetProfilePictureInfo(ctx, who, &whatsmeow.GetProfilePictureParams{Preview: true})
	if err != nil || info == nil {
		return ""
	}
	return info.URL
}

// --- events ---

func (s *Session) handleEvent(raw any) {
	switch evt := raw.(type) {
	case *events.Connected:
		s.onConnected()
	case *events.Disconnected:
		s.emit(map[string]any{"type": "disconnected"})
	case *events.PairSuccess:
		s.emit(map[string]any{"type": "pairSuccess", "id": evt.ID.ToNonAD().String(), "phone": evt.ID.User})
	case *events.PairError:
		s.emit(map[string]any{"type": "pairError", "error": evt.Error.Error()})
	case *events.LoggedOut:
		s.emit(map[string]any{"type": "loggedOut", "reason": evt.Reason.String()})
	case *events.StreamReplaced:
		s.emit(map[string]any{"type": "streamReplaced"})
	case *events.TemporaryBan:
		s.emit(map[string]any{"type": "temporaryBan", "reason": evt.Code.String(), "expireSeconds": int64(evt.Expire.Seconds())})
	case *events.ClientOutdated:
		s.emit(map[string]any{"type": "clientOutdated"})
	case *events.KeepAliveTimeout:
		s.emit(map[string]any{"type": "keepAliveTimeout", "errors": evt.ErrorCount})
	case *events.ConnectFailure:
		s.emit(map[string]any{"type": "connectFailure", "reason": evt.Reason.String(), "message": evt.Message})
	case *events.OfflineSyncCompleted:
		s.emit(map[string]any{"type": "offlineSyncDone", "count": evt.Count})
	case *events.Message:
		s.emit(map[string]any{"type": "message", "message": convertMessage(evt, s.phoneOf)})
	case *events.UndecryptableMessage:
		s.emit(map[string]any{"type": "undecryptable", "id": evt.Info.ID, "chat": evt.Info.Chat.String(),
			"sender": evt.Info.Sender.ToNonAD().String(), "timestamp": millis(evt.Info.Timestamp), "fromMe": evt.Info.IsFromMe})
	case *events.Receipt:
		s.emit(map[string]any{"type": "receipt", "receipt": convertReceipt(evt)})
	case *events.ChatPresence:
		s.emit(map[string]any{"type": "typing", "chat": evt.Chat.String(), "sender": evt.Sender.ToNonAD().String(),
			"typing": evt.State == types.ChatPresenceComposing})
	case *events.HistorySync:
		s.onHistory(evt)
	case *events.JoinedGroup:
		s.emit(map[string]any{"type": "group", "chat": convertGroup(&evt.GroupInfo, s.me(), s.nameOf)})
	case *events.GroupInfo:
		s.refreshGroup(evt.JID)
	case *events.MarkChatAsRead:
		s.emit(map[string]any{"type": "chatRead", "chat": evt.JID.String(), "read": evt.Action.GetRead()})
	case *events.PushName:
		s.emit(map[string]any{"type": "pushName", "id": evt.JID.ToNonAD().String(), "name": evt.NewPushName})
	default:
		s.log.Trace().Type("event_type", raw).Msg("Unhandled whatsmeow event")
	}
}

func (s *Session) onConnected() {
	ctx, cancel := s.ctx()
	defer cancel()
	// Presence must be sent once, or chat presence (typing) and read receipts stay queued.
	if s.client.Store.PushName != "" {
		if err := s.client.SendPresence(ctx, types.PresenceAvailable); err != nil {
			s.log.Warn().Err(err).Msg("Could not send presence")
		}
	}
	s.emit(map[string]any{"type": "connected", "id": s.OwnID(), "phone": s.OwnPhone(), "lid": s.OwnLID(), "pushName": s.PushName()})
}

func (s *Session) refreshGroup(jid types.JID) {
	ctx, cancel := s.ctx()
	defer cancel()
	info, err := s.client.GetGroupInfo(ctx, jid)
	if err != nil {
		s.log.Warn().Err(err).Str("group", jid.String()).Msg("Could not fetch the changed group")
		return
	}
	s.emit(map[string]any{"type": "group", "chat": convertGroup(info, s.me(), s.nameOf)})
}

// onHistory emits one "history" event per conversation in the sync, with its messages.
func (s *Session) onHistory(evt *events.HistorySync) {
	data := evt.Data
	syncType := data.GetSyncType().String()
	for _, conv := range data.GetConversations() {
		chatJID, err := types.ParseJID(conv.GetID())
		if err != nil {
			continue
		}
		chat := Chat{
			ID:       chatJID.String(),
			Name:     conv.GetName(),
			IsGroup:  isGroup(chatJID),
			Unread:   int(conv.GetUnreadCount()),
			LastAt:   int64(conv.GetConversationTimestamp()) * 1000,
			Archived: conv.GetArchived(),
			Pinned:   conv.GetPinned() > 0,
		}
		if conv.GetLastMsgTimestamp() > 0 {
			chat.LastAt = int64(conv.GetLastMsgTimestamp()) * 1000
		}
		if conv.GetIsParentGroup() {
			chat.IsCommunity = true
		}
		if parent := conv.GetParentGroupID(); parent != "" {
			chat.CommunityID = parent
		}
		for _, p := range conv.GetParticipant() {
			member, err := types.ParseJID(p.GetUserJID())
			if err != nil {
				continue
			}
			entry := Participant{ID: member.ToNonAD().String(), IsAdmin: p.GetRank() != 0, Name: s.nameOf(member), IsMe: sameUser(member, s.me())}
			if member.Server == types.DefaultUserServer {
				entry.Phone = member.User
			} else {
				entry.Phone = s.phoneOf(member)
			}
			chat.Participants = append(chat.Participants, entry)
		}
		if chat.Participants == nil {
			chat.Participants = []Participant{}
		}
		messages := make([]Message, 0, len(conv.GetMessages()))
		for _, hm := range conv.GetMessages() {
			parsed, err := s.client.ParseWebMessage(chatJID, hm.GetMessage())
			if err != nil {
				continue
			}
			messages = append(messages, convertMessage(parsed, s.phoneOf))
		}
		s.emit(map[string]any{"type": "history", "syncType": syncType, "progress": data.GetProgress(), "chat": chat, "messages": messages})
	}
	s.emit(map[string]any{"type": "historyChunk", "syncType": syncType, "progress": data.GetProgress(), "chats": len(data.GetConversations())})
}

func convertReceipt(evt *events.Receipt) Receipt {
	kind := "delivered"
	switch evt.Type {
	case types.ReceiptTypeRead, types.ReceiptTypeReadSelf:
		kind = "read"
	case types.ReceiptTypePlayed, types.ReceiptTypePlayedSelf:
		kind = "played"
	}
	return Receipt{
		Chat:      evt.Chat.String(),
		Sender:    evt.Sender.ToNonAD().String(),
		IDs:       append([]string{}, evt.MessageIDs...),
		Kind:      kind,
		Timestamp: millis(evt.Timestamp),
		FromMe:    evt.IsFromMe,
	}
}

// --- helpers ---

func (s *Session) send(to types.JID, msg *waE2E.Message, shape Message) (string, error) {
	resp, err := s.sendRaw(to, msg)
	if err != nil {
		return "", err
	}
	shape.ID = resp.ID
	shape.Chat = to.String()
	shape.Sender = s.OwnID()
	shape.SenderPhone = s.OwnPhone()
	shape.FromMe = true
	shape.Timestamp = millis(resp.Timestamp)
	shape.Status = "sent"
	return marshal(shape)
}

func (s *Session) sendRaw(to types.JID, msg *waE2E.Message) (whatsmeow.SendResponse, error) {
	if s.client.Store.ID == nil {
		return whatsmeow.SendResponse{}, ErrNotLoggedIn
	}
	ctx, cancel := context.WithTimeout(s.log.WithContext(context.Background()), 2*time.Minute)
	defer cancel()
	resp, err := s.client.SendMessage(ctx, to, msg)
	return resp, wrap(err)
}

func (s *Session) contextFor(chat types.JID, reply *ReplyRequest) *waE2E.ContextInfo {
	if reply == nil {
		return nil
	}
	sender := s.targetSender(reply.Sender, reply.FromMe)
	info := &waE2E.ContextInfo{
		StanzaID:      proto.String(reply.ID),
		Participant:   proto.String(sender.ToNonAD().String()),
		QuotedMessage: &waE2E.Message{Conversation: proto.String(reply.Text)},
	}
	if isGroup(chat) {
		info.RemoteJID = proto.String(chat.String())
	}
	return info
}

func (s *Session) targetSender(sender string, fromMe bool) types.JID {
	if fromMe || sender == "" {
		return s.me()
	}
	jid, err := parseJID(sender)
	if err != nil {
		return s.me()
	}
	return jid
}

func (s *Session) me() types.JID {
	if s.client.Store.ID == nil {
		return types.EmptyJID
	}
	return s.client.Store.ID.ToNonAD()
}

func (s *Session) nameOf(jid types.JID) string {
	key := jid.ToNonAD().String()
	s.mu.Lock()
	cached, ok := s.names[key]
	s.mu.Unlock()
	if ok {
		return cached
	}
	ctx, cancel := s.ctx()
	defer cancel()
	info, err := s.client.Store.Contacts.GetContact(ctx, jid.ToNonAD())
	name := ""
	if err == nil {
		name = contactName(info)
	}
	s.mu.Lock()
	s.names[key] = name
	s.mu.Unlock()
	return name
}

func (s *Session) phoneOf(jid types.JID) string {
	if jid.Server == types.DefaultUserServer {
		return jid.User
	}
	if jid.Server != types.HiddenUserServer {
		return ""
	}
	ctx, cancel := s.ctx()
	defer cancel()
	pn, err := s.client.Store.LIDs.GetPNForLID(ctx, jid.ToNonAD())
	if err != nil || pn.IsEmpty() {
		return ""
	}
	return pn.User
}

func contactName(info types.ContactInfo) string {
	switch {
	case info.FullName != "":
		return info.FullName
	case info.FirstName != "":
		return info.FirstName
	case info.BusinessName != "":
		return info.BusinessName
	default:
		return info.PushName
	}
}

func quoteOf(reply *ReplyRequest) *Quote {
	if reply == nil {
		return nil
	}
	return &Quote{ID: reply.ID, Sender: reply.Sender, Text: reply.Text}
}

func parseReply(replyJSON string) (*ReplyRequest, error) {
	if replyJSON == "" {
		return nil, nil
	}
	var reply ReplyRequest
	if err := json.Unmarshal([]byte(replyJSON), &reply); err != nil {
		return nil, fmt.Errorf("reply is not valid JSON: %w", err)
	}
	if reply.ID == "" {
		return nil, nil
	}
	return &reply, nil
}

func parseJID(id string) (types.JID, error) {
	jid, err := types.ParseJID(id)
	if err != nil {
		return types.EmptyJID, fmt.Errorf("%w: %q is not a WhatsApp id", ErrRejected, id)
	}
	return jid, nil
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
	case errors.Is(err, whatsmeow.ErrNotLoggedIn):
		return fmt.Errorf("%w: %w", ErrNotLoggedIn, err)
	case errors.Is(err, whatsmeow.ErrNotConnected), errors.Is(err, whatsmeow.ErrClientIsNil):
		return fmt.Errorf("%w: %w", ErrNotConnected, err)
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

func optional(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func u32(v int64) *uint32 {
	if v <= 0 {
		return nil
	}
	n := uint32(v)
	return &n
}

// Unused-import guard for waCommon, which keyOf in convert.go uses.
var _ = waCommon.MessageKey{}
