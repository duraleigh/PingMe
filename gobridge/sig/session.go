// SPDX-License-Identifier: AGPL-3.0-or-later

package sig

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-signal/pkg/libsignalgo"
	"go.mau.fi/mautrix-signal/pkg/signalmeow"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/events"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/protobuf/backuppb"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/protobuf/signalpb"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/store"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/types"
	"go.mau.fi/util/dbutil"
	"google.golang.org/protobuf/proto"
)

// EventSink receives every event as one JSON object. Kotlin implements it. Calls come
// from the client's own goroutines and must return quickly.
type EventSink interface {
	OnEvent(json string)
}

// Errors Kotlin tells apart by the code before the colon.
var (
	ErrNotLinked    = errors.New("NOT_LINKED: this Signal account is not linked")
	ErrNotConnected = errors.New("NOT_CONNECTED: not connected to Signal")
	ErrRejected     = errors.New("REJECTED: Signal refused the request")
)

const (
	requestTimeout = 60 * time.Second
	// How long the phone gets to answer a QR code before a fresh one is shown.
	qrLife = 45 * time.Second
	// How many fresh QR codes a link attempt shows before giving up.
	qrTries = 6
	// The newest messages looked at to count a chat's unread ones from the archive.
	unreadScan = 30
)

// Session is one linked Signal account: its keys and archive in a SQLite file, the live
// connection, and every request on it.
type Session struct {
	dbPath    string
	db        *dbutil.Database
	container *store.Container
	device    *store.Device
	client    *signalmeow.Client
	sink      EventSink
	log       zerolog.Logger

	mu         sync.Mutex
	cancel     context.CancelFunc
	cancelLink context.CancelFunc
	names      map[uuid.UUID]Member
	groups     map[string]*signalmeow.Group
}

// NewSession opens (or creates) the store at dbPath. A fresh store is not linked yet:
// StartLink links it; IsLoggedIn says which.
func NewSession(dbPath string, sink EventSink) (*Session, error) {
	log := newLogger("signalmeow")
	db, err := dbutil.NewWithDialect(storeAddress(dbPath), "sqlite3")
	if err != nil {
		return nil, fmt.Errorf("could not open the Signal store: %w", err)
	}
	db.Log = dbutil.ZeroLogger(log.With().Str("component", "db").Logger())
	container := store.NewStore(db, dbutil.ZeroLogger(log.With().Str("component", "store").Logger()))
	ctx := log.WithContext(context.Background())
	if err := container.Upgrade(ctx); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("could not prepare the Signal store: %w", err)
	}
	s := &Session{
		dbPath:    dbPath,
		db:        db,
		container: container,
		sink:      sink,
		log:       log,
		names:     make(map[uuid.UUID]Member),
		groups:    make(map[string]*signalmeow.Group),
	}
	devices, err := container.GetAllDevices(ctx)
	if err == nil && len(devices) > 0 {
		s.device = devices[0]
	}
	return s, nil
}

// IsLoggedIn is whether the store holds a linked device.
func (s *Session) IsLoggedIn() bool { return s.device != nil && s.device.ACI != uuid.Nil }

// OwnID is the account's id (a UUID), or "" before linking.
func (s *Session) OwnID() string {
	if !s.IsLoggedIn() {
		return ""
	}
	return s.device.ACI.String()
}

// OwnPhone is the account's phone number in +E.164 form, or "" before linking.
func (s *Session) OwnPhone() string {
	if !s.IsLoggedIn() {
		return ""
	}
	return s.device.Number
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

// StartLink begins linking: a "linkQr" event carries the URL to show as a QR code for the
// phone's Signal app to scan (a fresh one every 45 seconds, up to six); "linkDone" says
// the account is linked, "linkError" why it was not. The phone is asked to send its
// message history as well.
func (s *Session) StartLink(deviceName string) {
	s.CancelLink()
	ctx, cancel := context.WithCancel(s.log.WithContext(context.Background()))
	s.mu.Lock()
	s.cancelLink = cancel
	s.mu.Unlock()
	go s.link(ctx, deviceName)
}

// CancelLink stops a link attempt that has not finished.
func (s *Session) CancelLink() {
	s.mu.Lock()
	cancel := s.cancelLink
	s.cancelLink = nil
	s.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func (s *Session) link(ctx context.Context, deviceName string) {
	for try := 0; try < qrTries; try++ {
		attempt, cancel := context.WithCancel(ctx)
		responses := signalmeow.PerformProvisioning(attempt, s.container, deviceName, true)
		first, ok := <-responses
		if !ok || first.Err != nil || first.State != signalmeow.StateProvisioningURLReceived {
			cancel()
			reason := "no QR code came from Signal"
			if ok && first.Err != nil {
				reason = first.Err.Error()
			}
			s.emit(map[string]any{"type": "linkError", "reason": reason})
			return
		}
		s.emit(map[string]any{"type": "linkQr", "url": first.ProvisioningURL, "try": try + 1})
		select {
		case resp, ok := <-responses:
			cancel()
			switch {
			case !ok:
				s.emit(map[string]any{"type": "linkError", "reason": "Signal closed the link before it finished"})
				return
			case resp.Err != nil:
				s.emit(map[string]any{"type": "linkError", "reason": resp.Err.Error()})
				return
			case resp.State != signalmeow.StateProvisioningDataReceived || resp.ProvisioningData == nil ||
				resp.ProvisioningData.ACI == uuid.Nil:
				s.emit(map[string]any{"type": "linkError", "reason": "Signal did not say which account was linked"})
				return
			}
			device, err := s.container.DeviceByACI(ctx, resp.ProvisioningData.ACI)
			if err != nil || device == nil {
				s.emit(map[string]any{"type": "linkError", "reason": "the linked account could not be saved"})
				return
			}
			s.mu.Lock()
			s.device = device
			s.mu.Unlock()
			s.emit(map[string]any{"type": "linkDone", "id": device.ACI.String(), "phone": device.Number})
			return
		case <-time.After(qrLife):
			cancel()
			// The phone did not scan this one: the next loop shows a fresh code.
		case <-ctx.Done():
			cancel()
			s.emit(map[string]any{"type": "linkError", "reason": "cancelled"})
			return
		}
	}
	s.emit(map[string]any{"type": "linkError", "reason": "no QR code was scanned"})
}

// Connect opens the live connection. Events follow: "connected", "disconnected",
// "loggedOut", "transfer" (the history archive's progress), "chats" (the chat list
// once the archive is in), "message", "receipt", "readSelf", "contacts", "queueEmpty".
func (s *Session) Connect() error {
	if !s.IsLoggedIn() {
		return ErrNotLinked
	}
	s.Disconnect()
	ctx, cancel := context.WithCancel(s.log.WithContext(context.Background()))
	s.mu.Lock()
	s.cancel = cancel
	s.client = signalmeow.NewClient(s.device, s.log, s.handleEvent)
	s.client.SyncContactsOnConnect = true
	client := s.client
	s.mu.Unlock()
	statuses, err := client.StartReceiveLoops(ctx)
	if err != nil {
		cancel()
		return fmt.Errorf("%w: %v", ErrNotConnected, err)
	}
	go s.watch(ctx, statuses)
	go s.afterConnect(ctx)
	return nil
}

func (s *Session) watch(ctx context.Context, statuses chan signalmeow.SignalConnectionStatus) {
	for {
		select {
		case <-ctx.Done():
			return
		case status, ok := <-statuses:
			if !ok {
				return
			}
			switch status.Event {
			case signalmeow.SignalConnectionEventConnected:
				s.emit(map[string]any{"type": "connected", "id": s.OwnID(), "phone": s.OwnPhone()})
			case signalmeow.SignalConnectionEventDisconnected:
				s.emit(map[string]any{"type": "disconnected"})
			case signalmeow.SignalConnectionEventLoggedOut:
				s.emit(map[string]any{"type": "loggedOut", "reason": errText(status.Err)})
			case signalmeow.SignalConnectionEventError, signalmeow.SignalConnectionEventFatalError:
				s.emit(map[string]any{"type": "connectError", "reason": errText(status.Err),
					"fatal": status.Event == signalmeow.SignalConnectionEventFatalError})
			}
		}
	}
}

func errText(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

// afterConnect takes the phone's history archive the first time, then lists the chats.
func (s *Session) afterConnect(ctx context.Context) {
	marker := s.dbPath + ".transferred"
	if _, err := os.Stat(marker); err != nil && s.device.EphemeralBackupKey != nil {
		s.emit(map[string]any{"type": "transfer", "state": "waiting"})
		meta, err := s.client.WaitForTransfer(ctx)
		switch {
		case err != nil:
			s.log.Warn().Err(err).Msg("The phone's history archive did not come")
			s.emit(map[string]any{"type": "transfer", "state": "failed", "reason": err.Error()})
		case meta.Error != "":
			s.log.Warn().Str("reason", meta.Error).Msg("The phone declined to send its history")
			s.emit(map[string]any{"type": "transfer", "state": "declined", "reason": meta.Error})
			_ = os.WriteFile(marker, []byte(meta.Error), 0o600)
		default:
			s.emit(map[string]any{"type": "transfer", "state": "fetching"})
			if err := s.client.FetchAndProcessTransfer(ctx, meta); err != nil {
				s.log.Warn().Err(err).Msg("The phone's history archive could not be read")
				s.emit(map[string]any{"type": "transfer", "state": "failed", "reason": err.Error()})
			} else {
				_ = os.WriteFile(marker, []byte("done"), 0o600)
				s.emit(map[string]any{"type": "transfer", "state": "done"})
			}
		}
	}
	if ctx.Err() != nil {
		return
	}
	chats, err := s.chats(ctx)
	if err != nil {
		s.log.Warn().Err(err).Msg("Could not list the chats")
		return
	}
	s.emit(map[string]any{"type": "chats", "chats": chats})
}

// Disconnect closes the live connection; the store stays.
func (s *Session) Disconnect() {
	s.mu.Lock()
	cancel := s.cancel
	client := s.client
	s.cancel = nil
	s.client = nil
	s.mu.Unlock()
	if client != nil {
		_ = client.StopReceiveLoops()
	}
	if cancel != nil {
		cancel()
	}
}

// Close disconnects and closes the store.
func (s *Session) Close() {
	s.CancelLink()
	s.Disconnect()
	_ = s.db.Close()
}

// Unlink removes this device from the account and forgets the keys.
func (s *Session) Unlink() error {
	s.mu.Lock()
	client := s.client
	s.mu.Unlock()
	ctx, cancel := s.ctx()
	defer cancel()
	if client != nil {
		_ = client.ClearKeysAndDisconnect(ctx)
	}
	s.Disconnect()
	if s.device != nil {
		_ = s.container.DeleteDevice(ctx, &s.device.DeviceData)
		s.device = nil
	}
	_ = os.Remove(s.dbPath + ".transferred")
	return nil
}

func (s *Session) live() (*signalmeow.Client, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.client == nil {
		return nil, ErrNotConnected
	}
	return s.client, nil
}

// --- Events from Signal ---

func (s *Session) handleEvent(raw events.SignalEvent) bool {
	switch evt := raw.(type) {
	case *events.ChatEvent:
		s.onChatEvent(evt)
	case *events.Receipt:
		kind := "delivered"
		switch evt.Content.GetType() {
		case signalpb.ReceiptMessage_READ:
			kind = "read"
		case signalpb.ReceiptMessage_VIEWED:
			kind = "viewed"
		}
		stamps := make([]int64, 0, len(evt.Content.GetTimestamp()))
		for _, t := range evt.Content.GetTimestamp() {
			stamps = append(stamps, int64(t))
		}
		s.emit(map[string]any{"type": "receipt", "receipt": Receipt{Sender: evt.Sender.String(), Kind: kind, Timestamps: stamps}})
	case *events.ReadSelf:
		read := make([]map[string]any, 0, len(evt.Messages))
		for _, m := range evt.Messages {
			sender, _ := parseUUID(m.GetSenderAci(), m.GetSenderAciBinary())
			read = append(read, map[string]any{"sender": sender.String(), "timestamp": int64(m.GetTimestamp())})
		}
		s.emit(map[string]any{"type": "readSelf", "messages": read})
	case *events.ContactList:
		s.mu.Lock()
		for _, c := range evt.Contacts {
			if c != nil && c.ACI != uuid.Nil {
				s.names[c.ACI] = memberOf(c, s.device.ACI)
			}
		}
		s.mu.Unlock()
		s.emit(map[string]any{"type": "contacts"})
	case *events.DecryptionError:
		s.emit(map[string]any{"type": "undecryptable", "sender": evt.Sender.String(), "timestamp": int64(evt.Timestamp)})
	case *events.QueueEmpty:
		s.emit(map[string]any{"type": "queueEmpty"})
	case *events.LoggedOut:
		s.emit(map[string]any{"type": "loggedOut", "reason": errText(evt.Error)})
	default:
		s.log.Trace().Type("event_type", raw).Msg("Unhandled Signal event")
	}
	return true
}

func (s *Session) onChatEvent(evt *events.ChatEvent) {
	chat := evt.Info.ChatID
	sender := evt.Info.Sender
	fromMe := sender == s.device.ACI
	switch content := evt.Event.(type) {
	case *signalpb.DataMessage:
		msg := dataMessage(chat, sender, fromMe, content)
		if msg.Kind == "skip" {
			if content.GetGroupV2().GetGroupChange() != nil && IsGroupID(chat) {
				s.refreshGroup(chat)
			}
			return
		}
		msg.SenderPhone = s.phoneOf(sender)
		s.emit(map[string]any{"type": "message", "message": msg})
	case *signalpb.EditMessage:
		dm := content.GetDataMessage()
		msg := Message{
			ID:        MessageID(sender, dm.GetTimestamp()),
			Chat:      chat,
			Sender:    sender.String(),
			FromMe:    fromMe,
			Timestamp: int64(dm.GetTimestamp()),
			Kind:      "edit",
			Edit:      &Edit{TargetTimestamp: int64(content.GetTargetSentTimestamp()), Text: dm.GetBody()},
		}
		s.emit(map[string]any{"type": "message", "message": msg})
	case *signalpb.TypingMessage:
		typing := content.GetAction() == signalpb.TypingMessage_STARTED
		s.emit(map[string]any{"type": "typing", "chat": chat, "sender": sender.String(), "typing": typing})
	}
}

func (s *Session) refreshGroup(chat string) {
	ctx, cancel := s.ctx()
	defer cancel()
	s.mu.Lock()
	delete(s.groups, chat)
	s.mu.Unlock()
	info, err := s.chatInfo(ctx, chat)
	if err != nil {
		s.log.Warn().Err(err).Str("chat", chat).Msg("Could not fetch the changed group")
		return
	}
	s.emit(map[string]any{"type": "chat", "chat": info})
}

// --- People and chats ---

func memberOf(r *types.Recipient, me uuid.UUID) Member {
	name := firstNonEmpty(r.ContactName, r.Nickname, r.Profile.Name)
	return Member{ID: r.ACI.String(), Phone: r.E164, Name: name, IsMe: r.ACI == me}
}

// member is what is known about a person: the contact list, the profile, the archive.
func (s *Session) member(ctx context.Context, aci uuid.UUID) Member {
	s.mu.Lock()
	known, ok := s.names[aci]
	client := s.client
	s.mu.Unlock()
	if ok && known.Name != "" {
		return known
	}
	m := Member{ID: aci.String(), IsMe: aci == s.device.ACI}
	if client != nil {
		if r, err := client.ContactByACI(ctx, aci); err == nil && r != nil {
			m = memberOf(r, s.device.ACI)
		}
	} else if r, err := s.device.RecipientStore.LoadAndUpdateRecipient(ctx, aci, uuid.Nil, nil); err == nil && r != nil {
		m = memberOf(r, s.device.ACI)
	}
	if m.Name != "" || m.Phone != "" {
		s.mu.Lock()
		s.names[aci] = m
		s.mu.Unlock()
	}
	return m
}

func (s *Session) phoneOf(aci uuid.UUID) string {
	ctx, cancel := s.ctx()
	defer cancel()
	return s.member(ctx, aci).Phone
}

// Contacts returns everyone in the phone's Signal contact list, as a JSON array of Member.
func (s *Session) Contacts() (string, error) {
	ctx, cancel := s.ctx()
	defer cancel()
	all, err := s.device.RecipientStore.LoadAllContacts(ctx)
	if err != nil {
		return "", err
	}
	people := make([]Member, 0, len(all))
	for _, r := range all {
		if r == nil || r.ACI == uuid.Nil || r.ACI == s.device.ACI {
			continue
		}
		m := memberOf(r, s.device.ACI)
		if m.Name == "" && m.Phone == "" {
			continue
		}
		people = append(people, m)
	}
	return marshal(people)
}

// Chats returns the chats the phone's history archive holds, as a JSON array of Chat.
func (s *Session) Chats() (string, error) {
	ctx, cancel := s.ctx()
	defer cancel()
	chats, err := s.chats(ctx)
	if err != nil {
		return "", err
	}
	return marshal(chats)
}

func (s *Session) chats(ctx context.Context) ([]Chat, error) {
	archived, err := s.device.BackupStore.GetBackupChats(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]Chat, 0, len(archived))
	for _, bc := range archived {
		chat, ok := s.archivedChat(ctx, bc)
		if ok {
			out = append(out, chat)
		}
	}
	return out, nil
}

func (s *Session) archivedChat(ctx context.Context, bc *store.BackupChat) (Chat, bool) {
	recipient, err := s.device.BackupStore.GetBackupRecipient(ctx, bc.GetRecipientId())
	if err != nil || recipient == nil {
		return Chat{}, false
	}
	chat := Chat{
		Archived:  bc.GetArchived(),
		Pinned:    bc.GetPinnedOrder() > 0,
		MuteUntil: int64(bc.GetMuteUntilMs()),
		LastAt:    int64(bc.LatestMessageID),
		Count:     bc.TotalMessages,
	}
	switch dest := recipient.Destination.(type) {
	case *backuppb.Recipient_Contact:
		if len(dest.Contact.GetAci()) != 16 {
			return Chat{}, false
		}
		aci := uuid.UUID(dest.Contact.GetAci())
		m := s.member(ctx, aci)
		if m.Name == "" {
			m.Name = strings.TrimSpace(dest.Contact.GetProfileGivenName() + " " + dest.Contact.GetProfileFamilyName())
		}
		if m.Phone == "" && dest.Contact.GetE164() != 0 {
			m.Phone = "+" + strconv.FormatUint(dest.Contact.GetE164(), 10)
		}
		chat.ID = aci.String()
		chat.Name = firstNonEmpty(m.Name, m.Phone)
		chat.Members = []Member{m}
	case *backuppb.Recipient_Self:
		chat.ID = s.device.ACI.String()
		chat.Name = "Note to self"
		chat.Members = []Member{{ID: chat.ID, IsMe: true, Phone: s.device.Number}}
	case *backuppb.Recipient_Group:
		gid, err := s.client.StoreMasterKey(ctx, types.SerializedGroupMasterKey(dest.Group.GetMasterKey()))
		if err != nil {
			return Chat{}, false
		}
		chat.ID = gid.String()
		chat.IsGroup = true
		chat.Name = dest.Group.GetSnapshot().GetTitle().GetTitle()
		for _, m := range dest.Group.GetSnapshot().GetMembers() {
			if len(m.GetUserId()) != 16 {
				continue
			}
			member := s.member(ctx, uuid.UUID(m.GetUserId()))
			member.IsAdmin = m.GetRole() == backuppb.Group_Member_ADMINISTRATOR
			chat.Members = append(chat.Members, member)
		}
	default:
		return Chat{}, false
	}
	if bc.GetMarkedUnread() {
		chat.Unread = 1
	}
	items, err := s.device.BackupStore.GetBackupChatItems(ctx, bc.GetId(), time.Time{}, false, unreadScan)
	if err == nil {
		for _, item := range items {
			inc, ok := item.DirectionalDetails.(*backuppb.ChatItem_Incoming)
			if !ok {
				break
			}
			if inc.Incoming.GetRead() {
				break
			}
			chat.Unread++
		}
	}
	return chat, true
}

// ChatInfo returns one chat as JSON: a group from Signal itself, a person from what is known.
func (s *Session) ChatInfo(chat string) (string, error) {
	ctx, cancel := s.ctx()
	defer cancel()
	info, err := s.chatInfo(ctx, chat)
	if err != nil {
		return "", err
	}
	return marshal(info)
}

func (s *Session) chatInfo(ctx context.Context, chat string) (Chat, error) {
	if !IsGroupID(chat) {
		aci, err := uuid.Parse(chat)
		if err != nil {
			return Chat{}, fmt.Errorf("not a chat id: %q", chat)
		}
		m := s.member(ctx, aci)
		return Chat{ID: chat, Name: firstNonEmpty(m.Name, m.Phone, "Signal user"), Members: []Member{m}}, nil
	}
	group, err := s.group(ctx, chat)
	if err != nil {
		return Chat{}, err
	}
	out := Chat{ID: chat, IsGroup: true, Name: group.Title}
	for _, gm := range group.Members {
		member := s.member(ctx, gm.ACI)
		member.IsAdmin = gm.Role == signalmeow.GroupMember_ADMINISTRATOR
		out.Members = append(out.Members, member)
	}
	return out, nil
}

func (s *Session) group(ctx context.Context, chat string) (*signalmeow.Group, error) {
	s.mu.Lock()
	cached := s.groups[chat]
	s.mu.Unlock()
	if cached != nil {
		return cached, nil
	}
	client, err := s.live()
	if err != nil {
		return nil, err
	}
	group, _, err := client.RetrieveGroupByID(ctx, types.GroupIdentifier(chat), 0)
	if err != nil {
		return nil, wrap(err)
	}
	s.mu.Lock()
	s.groups[chat] = group
	s.mu.Unlock()
	return group, nil
}

// Messages returns up to count archived messages of chat older than beforeTimestamp (0
// for the newest), newest first, as a JSON array of Message.
func (s *Session) Messages(chat string, beforeTimestamp int64, count int) (string, error) {
	ctx, cancel := s.ctx()
	defer cancel()
	var bc *store.BackupChat
	var err error
	if IsGroupID(chat) {
		bc, err = s.device.BackupStore.GetBackupChatByGroupID(ctx, types.GroupIdentifier(chat))
	} else {
		aci, perr := uuid.Parse(chat)
		if perr != nil {
			return "", fmt.Errorf("not a chat id: %q", chat)
		}
		bc, err = s.device.BackupStore.GetBackupChatByUserID(ctx, libsignalgo.NewACIServiceID(aci))
	}
	if err != nil {
		return "", err
	}
	if bc == nil {
		return "[]", nil
	}
	var anchor time.Time
	if beforeTimestamp > 0 {
		anchor = time.UnixMilli(beforeTimestamp)
	}
	items, err := s.device.BackupStore.GetBackupChatItems(ctx, bc.GetId(), anchor, false, count)
	if err != nil {
		return "", err
	}
	authors := make(map[uint64]uuid.UUID)
	authorOf := func(id uint64) uuid.UUID {
		if aci, ok := authors[id]; ok {
			return aci
		}
		aci := uuid.Nil
		if r, err := s.device.BackupStore.GetBackupRecipient(ctx, id); err == nil && r != nil {
			switch dest := r.Destination.(type) {
			case *backuppb.Recipient_Self:
				aci = s.device.ACI
			case *backuppb.Recipient_Contact:
				if len(dest.Contact.GetAci()) == 16 {
					aci = uuid.UUID(dest.Contact.GetAci())
				}
			}
		}
		authors[id] = aci
		return aci
	}
	out := make([]Message, 0, len(items))
	for _, item := range items {
		author := authorOf(item.GetAuthorId())
		if author == uuid.Nil {
			continue
		}
		msg := archivedMessage(chat, item, author, s.device.ACI, authorOf)
		if msg == nil {
			continue
		}
		msg.SenderPhone = s.member(ctx, author).Phone
		out = append(out, *msg)
	}
	return marshal(out)
}

// --- Sending ---

func (s *Session) send(ctx context.Context, chat string, content *signalpb.Content) error {
	client, err := s.live()
	if err != nil {
		return err
	}
	if IsGroupID(chat) {
		result, err := client.SendGroupMessage(ctx, types.GroupIdentifier(chat), content)
		if err != nil {
			return wrap(err)
		}
		if len(result.SuccessfullySentTo) == 0 && len(result.FailedToSendTo) > 0 {
			return fmt.Errorf("%w: the message reached no one in the group: %v", ErrRejected, result.FailedToSendTo[0].Error)
		}
		return nil
	}
	aci, err := uuid.Parse(chat)
	if err != nil {
		return fmt.Errorf("not a chat id: %q", chat)
	}
	result := client.SendMessage(ctx, libsignalgo.NewACIServiceID(aci), content)
	if !result.WasSuccessful {
		return wrap(result.Error)
	}
	return nil
}

func (s *Session) newDataMessage(text string, quoteJSON string) (*signalpb.DataMessage, error) {
	dm := &signalpb.DataMessage{Timestamp: proto.Uint64(uint64(time.Now().UnixMilli()))}
	if text != "" {
		dm.Body = proto.String(text)
	}
	if quoteJSON != "" {
		var q Quote
		if err := json.Unmarshal([]byte(quoteJSON), &q); err != nil {
			return nil, fmt.Errorf("quote is not valid JSON: %w", err)
		}
		author, ts, err := ParseMessageID(q.ID)
		if err != nil {
			return nil, err
		}
		dm.Quote = &signalpb.DataMessage_Quote{
			Id:        proto.Uint64(ts),
			AuthorAci: proto.String(author.String()),
			Text:      proto.String(q.Text),
			Type:      signalpb.DataMessage_Quote_NORMAL.Enum(),
		}
	}
	return dm, nil
}

// SendText sends text, as a reply when quoteJSON (a Quote) is not empty. Returns the
// Message as sent.
func (s *Session) SendText(chat, text, quoteJSON string) (string, error) {
	dm, err := s.newDataMessage(text, quoteJSON)
	if err != nil {
		return "", err
	}
	ctx, cancel := s.ctx()
	defer cancel()
	if err := s.send(ctx, chat, signalmeow.WrapDataMessage(dm)); err != nil {
		return "", err
	}
	return marshal(s.sentMessage(chat, dm))
}

// SendMedia sends the file at path (mime, name, caption; voice marks a voice note), as a
// reply when quoteJSON is not empty. Returns the Message as sent.
func (s *Session) SendMedia(chat, path, mime, name, caption string, voice bool, quoteJSON string) (string, error) {
	client, err := s.live()
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	dm, err := s.newDataMessage(caption, quoteJSON)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(s.log.WithContext(context.Background()), 5*time.Minute)
	defer cancel()
	pointer, err := client.UploadAttachment(ctx, data)
	if err != nil {
		return "", wrap(err)
	}
	pointer.ContentType = proto.String(mime)
	if name != "" {
		pointer.FileName = proto.String(name)
	}
	if voice {
		pointer.Flags = proto.Uint32(uint32(signalpb.AttachmentPointer_VOICE_MESSAGE))
	}
	dm.Attachments = []*signalpb.AttachmentPointer{pointer}
	if err := s.send(ctx, chat, signalmeow.WrapDataMessage(dm)); err != nil {
		return "", err
	}
	return marshal(s.sentMessage(chat, dm))
}

func (s *Session) sentMessage(chat string, dm *signalpb.DataMessage) Message {
	msg := dataMessage(chat, s.device.ACI, true, dm)
	msg.Status = "sent"
	return msg
}

// SendReaction puts emoji on the message targetID ("<sender>:<timestamp>"), or takes
// it away when remove.
func (s *Session) SendReaction(chat, targetID, emoji string, remove bool) error {
	author, ts, err := ParseMessageID(targetID)
	if err != nil {
		return err
	}
	dm := &signalpb.DataMessage{
		Timestamp:               proto.Uint64(uint64(time.Now().UnixMilli())),
		RequiredProtocolVersion: proto.Uint32(uint32(signalpb.DataMessage_REACTIONS)),
		Reaction: &signalpb.DataMessage_Reaction{
			Emoji:               proto.String(emoji),
			Remove:              proto.Bool(remove),
			TargetAuthorAci:     proto.String(author.String()),
			TargetSentTimestamp: proto.Uint64(ts),
		},
	}
	ctx, cancel := s.ctx()
	defer cancel()
	return s.send(ctx, chat, signalmeow.WrapDataMessage(dm))
}

// Revoke deletes your own message targetID for everyone.
func (s *Session) Revoke(chat, targetID string) error {
	_, ts, err := ParseMessageID(targetID)
	if err != nil {
		return err
	}
	dm := &signalpb.DataMessage{
		Timestamp: proto.Uint64(uint64(time.Now().UnixMilli())),
		Delete:    &signalpb.DataMessage_Delete{TargetSentTimestamp: proto.Uint64(ts)},
	}
	ctx, cancel := s.ctx()
	defer cancel()
	return s.send(ctx, chat, signalmeow.WrapDataMessage(dm))
}

// Edit replaces the text of your own message targetID. Returns the edit's Message.
func (s *Session) Edit(chat, targetID, text string) (string, error) {
	_, ts, err := ParseMessageID(targetID)
	if err != nil {
		return "", err
	}
	dm := &signalpb.DataMessage{Timestamp: proto.Uint64(uint64(time.Now().UnixMilli())), Body: proto.String(text)}
	em := &signalpb.EditMessage{TargetSentTimestamp: proto.Uint64(ts), DataMessage: dm}
	ctx, cancel := s.ctx()
	defer cancel()
	if err := s.send(ctx, chat, signalmeow.WrapEditMessage(em)); err != nil {
		return "", err
	}
	msg := s.sentMessage(chat, dm)
	msg.Kind = "edit"
	msg.Edit = &Edit{TargetTimestamp: int64(ts), Text: text}
	return marshal(msg)
}

// MarkRead sends read receipts for the messages (JSON array of "<sender>:<timestamp>")
// to their senders, and tells your other devices.
func (s *Session) MarkRead(chat, idsJSON string) error {
	var ids []string
	if err := json.Unmarshal([]byte(idsJSON), &ids); err != nil {
		return fmt.Errorf("ids are not valid JSON: %w", err)
	}
	bySender := make(map[uuid.UUID][]uint64)
	for _, id := range ids {
		sender, ts, err := ParseMessageID(id)
		if err != nil || sender == s.device.ACI {
			continue
		}
		bySender[sender] = append(bySender[sender], ts)
	}
	client, err := s.live()
	if err != nil {
		return err
	}
	ctx, cancel := s.ctx()
	defer cancel()
	for sender, stamps := range bySender {
		result := client.SendMessage(ctx, libsignalgo.NewACIServiceID(sender), signalmeow.ReadReceptMessageForTimestamps(stamps))
		if !result.WasSuccessful {
			return wrap(result.Error)
		}
	}
	return nil
}

// SetTyping tells the chat whether you are typing.
func (s *Session) SetTyping(chat string, typing bool) error {
	ctx, cancel := s.ctx()
	defer cancel()
	return s.send(ctx, chat, signalmeow.TypingMessage(typing))
}

// Download fetches and decrypts a file (mediaJSON is its Media) into destPath.
func (s *Session) Download(mediaJSON, destPath string) error {
	var media Media
	if err := json.Unmarshal([]byte(mediaJSON), &media); err != nil {
		return fmt.Errorf("media is not valid JSON: %w", err)
	}
	pointer, err := pointerOf(media)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(s.log.WithContext(context.Background()), 5*time.Minute)
	defer cancel()
	temp := destPath + ".part"
	file, err := os.OpenFile(temp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if _, err := signalmeow.DownloadAttachmentWithPointer(ctx, pointer, nil, file); err != nil {
		_ = file.Close()
		_ = os.Remove(temp)
		return wrap(err)
	}
	if err := file.Close(); err != nil {
		return err
	}
	return os.Rename(temp, destPath)
}

// CheckNumber asks Signal whether a phone number (+E.164) has an account; returns its
// id, or "" when it has none.
func (s *Session) CheckNumber(phone string) (string, error) {
	client, err := s.live()
	if err != nil {
		return "", err
	}
	digits := strings.TrimPrefix(phone, "+")
	e164, err := strconv.ParseUint(digits, 10, 64)
	if err != nil {
		return "", fmt.Errorf("not a phone number: %q", phone)
	}
	ctx, cancel := s.ctx()
	defer cancel()
	found, err := client.LookupPhone(ctx, e164)
	if err != nil {
		return "", wrap(err)
	}
	entry, ok := found[e164]
	if !ok || entry.ACI == uuid.Nil {
		return "", nil
	}
	return entry.ACI.String(), nil
}

func marshal(v any) (string, error) {
	raw, err := json.Marshal(v)
	if err != nil {
		return "", err
	}
	return string(raw), nil
}

func wrap(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return fmt.Errorf("%w: Signal did not answer in time", ErrNotConnected)
	}
	return err
}
