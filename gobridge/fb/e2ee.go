// SPDX-License-Identifier: AGPL-3.0-or-later

package fb

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	_ "image/gif"  // Sizes of pictures to send.
	_ "image/jpeg" // Sizes of pictures to send.
	_ "image/png"  // Sizes of pictures to send.
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"go.mau.fi/mautrix-meta/pkg/messagix/methods"
	"go.mau.fi/whatsmeow"
	armadillo "go.mau.fi/whatsmeow/proto"
	"go.mau.fi/whatsmeow/proto/waArmadilloApplication"
	"go.mau.fi/whatsmeow/proto/waArmadilloXMA"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waConsumerApplication"
	"go.mau.fi/whatsmeow/proto/waMediaTransport"
	"go.mau.fi/whatsmeow/proto/waMsgApplication"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	waTypes "go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	_ "golang.org/x/image/webp" // Sizes of pictures to send.
	"google.golang.org/protobuf/proto"
)

// Messenger moved personal one-to-one chats to end-to-end encryption carried over the
// WhatsApp protocol (seen 2026-10-04). The inbox still lists such a chat through the web
// tables, under a thread key that is not the other person's id, and a mapping row ties
// that key to the chat's id on the encrypted channel (the other person's id for a
// one-to-one chat). Messages for those chats travel only on that channel: a device
// registered with Meta once (its keys kept in the store at dbPath), then a whatsmeow
// client against Messenger's servers, as the reference bridge does. Those chats have no
// history on the server: only what arrives after connecting is seen.

// e2ee is the encrypted channel's state, guarded by its own lock.
type e2ee struct {
	mu       sync.Mutex
	store    *sqlstore.Container
	device   *store.Device
	client   *whatsmeow.Client
	live     bool
	resets   int
	starting bool
}

// waRef is one message on the encrypted channel, for read marks.
type waRef struct {
	id     string
	sender int64
}

const (
	e2eePrefix = "e2ee:"
	maxResets  = 2
)

func waJID(user int64) waTypes.JID {
	return waTypes.JID{User: id(user), Server: waTypes.MessengerServer}
}

// openStore opens the encryption key store; an empty path means no encrypted channel.
func (s *Session) openStore(dbPath string) error {
	if dbPath == "" {
		return nil
	}
	ctx := context.Background()
	container, err := sqlstore.New(ctx, "sqlite3", storeAddress(dbPath), waLogger("e2ee-store"))
	if err != nil {
		return fmt.Errorf("could not open the Messenger key store: %w", err)
	}
	s.e2ee.store = container
	return nil
}

func (s *Session) e2eeClient() *whatsmeow.Client {
	s.e2ee.mu.Lock()
	defer s.e2ee.mu.Unlock()
	if !s.e2ee.live {
		return nil
	}
	return s.e2ee.client
}

// startE2EE registers the device on first use and connects the encrypted channel. It runs
// once the web socket is up, as the reference bridge does, because registration needs the
// crypto token the inbox page carried.
func (s *Session) startE2EE() {
	s.e2ee.mu.Lock()
	if s.e2ee.store == nil || s.e2ee.client != nil || s.e2ee.starting {
		s.e2ee.mu.Unlock()
		return
	}
	s.e2ee.starting = true
	s.e2ee.mu.Unlock()
	defer func() {
		s.e2ee.mu.Lock()
		s.e2ee.starting = false
		s.e2ee.mu.Unlock()
	}()
	ctx, cancel := context.WithTimeout(s.log.WithContext(context.Background()), 2*requestTimeout)
	defer cancel()
	device, err := s.e2ee.store.GetFirstDevice(ctx)
	if err != nil {
		s.e2eeFailed("could not read the key store", err)
		return
	}
	s.client.SetDevice(device)
	if device.ID == nil {
		s.log.Info().Msg("Registering this phone as an encrypted-chat device with Messenger")
		if err = s.client.RegisterE2EE(ctx, s.ownID()); err != nil {
			s.e2eeFailed("Messenger refused the device registration", err)
			return
		}
		if err = device.Save(ctx); err != nil {
			s.e2eeFailed("could not save the device keys", err)
			return
		}
	}
	client, err := s.client.PrepareE2EEClient()
	if err != nil {
		s.e2eeFailed("could not prepare the encrypted client", err)
		return
	}
	client.AddEventHandler(s.handleWAEvent)
	if err = client.Connect(); err != nil {
		s.e2eeFailed("could not connect the encrypted channel", err)
		return
	}
	s.e2ee.mu.Lock()
	s.e2ee.device = device
	s.e2ee.client = client
	s.e2ee.mu.Unlock()
	s.log.Info().Stringer("device", device.ID).Msg("Encrypted channel connecting")
}

func (s *Session) e2eeFailed(what string, err error) {
	s.log.Warn().Err(err).Msg("Encrypted channel: " + what)
	s.emit(map[string]any{"type": "e2ee", "state": "failed", "error": what + ": " + err.Error()})
}

// resetE2EE throws the device away (Messenger no longer knows it) and registers again.
func (s *Session) resetE2EE(reason string) {
	s.e2ee.mu.Lock()
	client, device := s.e2ee.client, s.e2ee.device
	s.e2ee.client, s.e2ee.device, s.e2ee.live = nil, nil, false
	s.e2ee.resets++
	resets := s.e2ee.resets
	s.e2ee.mu.Unlock()
	if client != nil {
		client.Disconnect()
	}
	if device != nil {
		ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
		if err := device.Delete(ctx); err != nil {
			s.log.Warn().Err(err).Msg("Could not delete the old encrypted-chat device")
		}
		cancel()
	}
	if resets > maxResets {
		s.e2eeFailed("gave up after repeated registrations", errors.New(reason))
		return
	}
	s.log.Info().Str("reason", reason).Msg("Registering the encrypted-chat device again")
	go s.startE2EE()
}

func (s *Session) stopE2EE() {
	s.e2ee.mu.Lock()
	client := s.e2ee.client
	s.e2ee.client, s.e2ee.device, s.e2ee.live = nil, nil, false
	s.e2ee.mu.Unlock()
	if client != nil {
		client.Disconnect()
	}
}

func (s *Session) closeStore() {
	s.e2ee.mu.Lock()
	container := s.e2ee.store
	s.e2ee.store = nil
	s.e2ee.mu.Unlock()
	if container != nil {
		_ = container.Close()
	}
}

// --- events from the channel ---

func (s *Session) handleWAEvent(raw any) {
	switch evt := raw.(type) {
	case *events.Connected:
		s.e2ee.mu.Lock()
		s.e2ee.live = true
		s.e2ee.mu.Unlock()
		s.log.Info().Msg("Encrypted channel connected")
		s.emit(map[string]any{"type": "e2ee", "state": "connected"})
	case *events.Disconnected:
		s.e2ee.mu.Lock()
		s.e2ee.live = false
		s.e2ee.mu.Unlock()
		s.log.Info().Msg("Encrypted channel disconnected; it reconnects by itself")
	case *events.FBMessage:
		s.handleWAMessage(evt)
	case *events.Receipt:
		if evt.Type == waTypes.ReceiptTypeRead || evt.Type == waTypes.ReceiptTypeReadSelf {
			s.emit(map[string]any{"type": "readReceipt", "thread": s.waThreadID(evt.Chat),
				"sender": evt.Sender.User, "timestamp": evt.Timestamp.UnixMilli()})
		}
	case *events.ChatPresence:
		s.emit(map[string]any{"type": "typing", "thread": s.waThreadID(evt.Chat), "sender": evt.Sender.User,
			"typing": evt.State == waTypes.ChatPresenceComposing})
	case *events.LoggedOut:
		s.resetE2EE("Messenger logged the encrypted-chat device out")
	case *events.ConnectFailure:
		switch evt.Reason {
		case events.ConnectFailureNotFound, events.ConnectFailureClientUnknown, events.ConnectFailureLoggedOut:
			s.resetE2EE(fmt.Sprintf("connect failure %d: %s", int(evt.Reason), evt.Message))
		default:
			s.e2eeFailed("connect failure", fmt.Errorf("%d: %s", int(evt.Reason), evt.Message))
		}
	case *events.CATRefreshError:
		s.e2eeFailed("token refresh", evt.Error)
	case *events.OfflineSyncCompleted:
		s.log.Info().Int("count", evt.Count).Msg("Encrypted channel: offline messages delivered")
	default:
		s.log.Debug().Type("event_type", raw).Msg("Unhandled encrypted-channel event")
	}
}

// waThreadID is the thread id a chat on the channel is shown under: its id there (the other
// person's id for a one-to-one chat), which the mapping rows also give the web listing.
func (s *Session) waThreadID(chat waTypes.JID) string { return chat.User }

// handleWAMessage turns one message from the channel into the same events the web tables give.
func (s *Session) handleWAMessage(evt *events.FBMessage) {
	jid, err := strconv.ParseInt(evt.Info.Chat.User, 10, 64)
	if err != nil {
		s.log.Warn().Str("chat", evt.Info.Chat.String()).Msg("Encrypted message for a chat without a numeric id")
		return
	}
	sender := evt.Info.Sender.User
	if evt.Info.IsFromMe {
		sender = s.OwnID()
	}
	thread := evt.Info.Chat.User
	ts := evt.Info.Timestamp.UnixMilli()
	switch msg := evt.Message.(type) {
	case *waConsumerApplication.ConsumerApplication:
		switch payload := msg.GetPayload().GetPayload().(type) {
		case *waConsumerApplication.ConsumerApplication_Payload_Content:
			switch c := payload.Content.GetContent().(type) {
			case *waConsumerApplication.ConsumerApplication_Content_EditMessage:
				at := c.EditMessage.GetTimestampMS()
				if at == 0 {
					at = ts
				}
				s.emit(map[string]any{"type": "edit", "thread": thread, "message": c.EditMessage.GetKey().GetID(),
					"text": c.EditMessage.GetMessage().GetText(), "timestamp": at})
			case *waConsumerApplication.ConsumerApplication_Content_ReactionMessage:
				s.handleWAReaction(thread, sender, ts, c.ReactionMessage)
			default:
				m := s.waMessage(evt, payload.Content, jid, sender, ts)
				s.announce(jid, evt.Info.IsGroup, evt.Info.PushName, sender, m)
			}
		case *waConsumerApplication.ConsumerApplication_Payload_ApplicationData:
			if revoke := payload.ApplicationData.GetRevoke(); revoke != nil {
				s.emit(map[string]any{"type": "unsent", "thread": thread, "message": revoke.GetKey().GetID()})
			} else {
				s.log.Debug().Msg("Unhandled encrypted application data")
			}
		default:
			s.log.Debug().Type("payload", payload).Msg("Unhandled encrypted payload")
		}
	case *waArmadilloApplication.Armadillo:
		m := s.waArmadillo(evt, msg.GetPayload().GetContent(), jid, sender, ts)
		s.announce(jid, evt.Info.IsGroup, evt.Info.PushName, sender, m)
	default:
		if evt.Message == nil {
			return // A settings change (disappearing timer), not a message.
		}
		m := Message{ID: evt.Info.ID, Thread: thread, Sender: sender, Timestamp: ts, Kind: "unsupported"}
		s.announce(jid, evt.Info.IsGroup, evt.Info.PushName, sender, m)
	}
}

func (s *Session) handleWAReaction(thread, sender string, ts int64, r *waConsumerApplication.ConsumerApplication_ReactionMessage) {
	target := r.GetKey().GetID()
	emoji := r.GetText()
	at := r.GetSenderTimestampMS()
	if at == 0 {
		at = ts
	}
	removed := emoji == ""
	s.mu.Lock()
	if removed {
		emoji = s.emojis[target+"|"+sender]
		delete(s.emojis, target+"|"+sender)
	} else {
		s.emojis[target+"|"+sender] = emoji
	}
	s.mu.Unlock()
	s.emit(map[string]any{"type": "reaction", "thread": thread, "message": target, "removed": removed,
		"reaction": Reaction{Emoji: emoji, Sender: sender, Timestamp: at}})
}

// announce files a channel message under its thread (made on the spot for a chat the web
// listing has not shown yet) and reports it.
func (s *Session) announce(jid int64, group bool, pushName, sender string, m Message) {
	s.mu.Lock()
	t := s.threadByJID(jid)
	fresh := t == nil
	if fresh {
		t = &Thread{ID: id(jid), IsGroup: group, Folder: "inbox", jid: jid, members: map[int64]*User{}}
		if !group {
			if n, err := strconv.ParseInt(sender, 10, 64); err == nil && n != s.own {
				t.members[n] = &User{ID: sender, Name: pushName}
			}
		}
		s.threads[jid] = t
	}
	s.rememberWA(m)
	t.addMessages([]Message{m})
	if t.LastAt < m.Timestamp {
		t.LastAt = m.Timestamp
	}
	if sender != id(s.own) {
		t.waUnread = append(t.waUnread, waRef{id: m.ID, sender: parseOr0(sender)})
	}
	var view Thread
	if fresh {
		view = t.view(s.own, s.people)
	}
	s.mu.Unlock()
	if fresh {
		s.emit(map[string]any{"type": "thread", "thread": view})
	}
	s.emit(map[string]any{"type": "message", "message": m})
}

func parseOr0(text string) int64 {
	n, _ := strconv.ParseInt(text, 10, 64)
	return n
}

// rememberWA notes a channel message (caller holds the lock) with who sent it, for replies,
// reactions, and unsends, which the channel keys by sender.
func (s *Session) rememberWA(m Message) {
	s.remember(m)
	p := s.messages[m.ID]
	p.sender = parseOr0(m.Sender)
	p.fromMe = m.Sender == id(s.own)
	p.jid = parseOr0(m.Thread)
	s.messages[m.ID] = p
}

// waMessage converts ordinary content: text, a picture, a video, a voice note, a file, a
// sticker, a place, or a contact card.
func (s *Session) waMessage(evt *events.FBMessage, content *waConsumerApplication.ConsumerApplication_Content, jid int64, sender string, ts int64) Message {
	m := Message{ID: evt.Info.ID, Thread: id(jid), Sender: sender, Timestamp: ts, Kind: "text"}
	if quoted := evt.FBApplication.GetMetadata().GetQuotedMessage(); quoted != nil {
		m.ReplyTo = quoted.GetStanzaID()
	}
	switch c := content.GetContent().(type) {
	case *waConsumerApplication.ConsumerApplication_Content_MessageText:
		m.Text = c.MessageText.GetText()
	case *waConsumerApplication.ConsumerApplication_Content_ExtendedTextMessage:
		m.Text = c.ExtendedTextMessage.GetText().GetText()
	case *waConsumerApplication.ConsumerApplication_Content_LocationMessage:
		loc := c.LocationMessage.GetLocation()
		m.Kind = "share"
		m.Share = &Share{Title: loc.GetName(), Subtitle: c.LocationMessage.GetAddress(),
			URL: fmt.Sprintf("https://maps.google.com/?q=%f,%f", loc.GetDegreesLatitude(), loc.GetDegreesLongitude())}
	case *waConsumerApplication.ConsumerApplication_Content_ContactMessage,
		*waConsumerApplication.ConsumerApplication_Content_ContactsArrayMessage:
		m.Kind = "unsupported"
		m.Text = "A contact card; open it in Messenger."
	default:
		media, caption, err := s.waMedia(content)
		if err != nil {
			s.log.Warn().Err(err).Msg("Encrypted media could not be read")
			m.Kind = "unsupported"
			return m
		}
		if media == nil {
			s.log.Debug().Type("content", c).Msg("Unhandled encrypted content")
			m.Kind = "unsupported"
			return m
		}
		m.Kind = media.Kind
		m.Media = []Media{*media}
		m.Text = caption
	}
	return m
}

// waMedia reads one attachment's keys and sizes; the file itself is fetched on demand
// through Download, by the handle put in its URL.
func (s *Session) waMedia(content *waConsumerApplication.ConsumerApplication_Content) (*Media, string, error) {
	switch c := content.GetContent().(type) {
	case *waConsumerApplication.ConsumerApplication_Content_ImageMessage:
		return imageMedia(c.ImageMessage)
	case *waConsumerApplication.ConsumerApplication_Content_StickerMessage:
		tr, err := c.StickerMessage.Decode()
		if err != nil {
			return nil, "", err
		}
		media := mediaFrom(tr.GetIntegral().GetTransport(), whatsmeow.MediaImage, "sticker")
		media.Width, media.Height = int(tr.GetAncillary().GetWidth()), int(tr.GetAncillary().GetHeight())
		return media, "", nil
	case *waConsumerApplication.ConsumerApplication_Content_VideoMessage:
		return videoMedia(c.VideoMessage)
	case *waConsumerApplication.ConsumerApplication_Content_AudioMessage:
		tr, err := c.AudioMessage.Decode()
		if err != nil {
			return nil, "", err
		}
		kind := "file"
		if c.AudioMessage.GetPTT() {
			kind = "voice"
		}
		media := mediaFrom(tr.GetIntegral().GetTransport(), whatsmeow.MediaAudio, kind)
		media.DurationMS = int(tr.GetAncillary().GetSeconds()) * 1000
		return media, "", nil
	case *waConsumerApplication.ConsumerApplication_Content_DocumentMessage:
		tr, err := c.DocumentMessage.Decode()
		if err != nil {
			return nil, "", err
		}
		media := mediaFrom(tr.GetIntegral().GetTransport(), whatsmeow.MediaDocument, "file")
		media.FileName = c.DocumentMessage.GetFileName()
		return media, "", nil
	case *waConsumerApplication.ConsumerApplication_Content_ViewOnceMessage:
		// Kept as an ordinary picture or video when it arrives (UI_DESIGN.md 10.12).
		if img := c.ViewOnceMessage.GetImageMessage(); img != nil {
			return imageMedia(img)
		}
		if vid := c.ViewOnceMessage.GetVideoMessage(); vid != nil {
			return videoMedia(vid)
		}
	}
	return nil, "", nil
}

func imageMedia(img *waConsumerApplication.ConsumerApplication_ImageMessage) (*Media, string, error) {
	tr, err := img.Decode()
	if err != nil {
		return nil, "", err
	}
	media := mediaFrom(tr.GetIntegral().GetTransport(), whatsmeow.MediaImage, "image")
	media.Width, media.Height = int(tr.GetAncillary().GetWidth()), int(tr.GetAncillary().GetHeight())
	return media, img.GetCaption().GetText(), nil
}

func videoMedia(vid *waConsumerApplication.ConsumerApplication_VideoMessage) (*Media, string, error) {
	tr, err := vid.Decode()
	if err != nil {
		return nil, "", err
	}
	kind := "video"
	if tr.GetAncillary().GetGifPlayback() {
		kind = "gif"
	}
	media := mediaFrom(tr.GetIntegral().GetTransport(), whatsmeow.MediaVideo, kind)
	media.Width, media.Height = int(tr.GetAncillary().GetWidth()), int(tr.GetAncillary().GetHeight())
	media.DurationMS = int(tr.GetAncillary().GetSeconds()) * 1000
	return media, vid.GetCaption().GetText(), nil
}

// mediaHandle is what Download needs to fetch and decrypt one attachment.
type mediaHandle struct {
	Type      whatsmeow.MediaType `json:"type"`
	Integral  []byte              `json:"integral"`
	FileName  string              `json:"fileName,omitempty"`
	MediaType string              `json:"-"`
}

func mediaFrom(tr *waMediaTransport.WAMediaTransport, mediaType whatsmeow.MediaType, kind string) *Media {
	media := &Media{Kind: kind, Mime: tr.GetAncillary().GetMimetype(), Size: int64(tr.GetAncillary().GetFileLength())}
	raw, err := proto.Marshal(tr.GetIntegral())
	if err == nil {
		handle, _ := json.Marshal(mediaHandle{Type: mediaType, Integral: raw})
		media.URL = e2eePrefix + base64.RawURLEncoding.EncodeToString(handle)
		media.ID = media.URL
	}
	return media
}

// downloadE2EE fetches and decrypts an attachment named by an e2ee: handle.
func (s *Session) downloadE2EE(url, destPath string) error {
	client := s.e2eeClient()
	if client == nil {
		return ErrNotConnected
	}
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(url, e2eePrefix))
	if err != nil {
		return fmt.Errorf("%w: bad media handle", ErrRejected)
	}
	var handle mediaHandle
	if err = json.Unmarshal(raw, &handle); err != nil {
		return fmt.Errorf("%w: bad media handle", ErrRejected)
	}
	var integral waMediaTransport.WAMediaTransport_Integral
	if err = proto.Unmarshal(handle.Integral, &integral); err != nil {
		return fmt.Errorf("%w: bad media handle", ErrRejected)
	}
	ctx, cancel := context.WithTimeout(s.log.WithContext(context.Background()), 5*time.Minute)
	defer cancel()
	data, err := client.DownloadFB(ctx, &integral, handle.Type)
	if err != nil {
		return wrap(err)
	}
	temp := destPath + ".part"
	if err = os.WriteFile(temp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(temp, destPath)
}

// waArmadillo converts the other message family: shared links and cards, view-once media
// on Messenger's own wrapping, and picture galleries.
func (s *Session) waArmadillo(evt *events.FBMessage, content *waArmadilloApplication.Armadillo_Content, jid int64, sender string, ts int64) Message {
	m := Message{ID: evt.Info.ID, Thread: id(jid), Sender: sender, Timestamp: ts, Kind: "unsupported"}
	if quoted := evt.FBApplication.GetMetadata().GetQuotedMessage(); quoted != nil {
		m.ReplyTo = quoted.GetStanzaID()
	}
	switch c := content.GetContent().(type) {
	case *waArmadilloApplication.Armadillo_Content_ExtendedContentMessage:
		x := c.ExtendedContentMessage
		m.Kind = "share"
		m.Text = x.GetMessageText()
		m.Share = &Share{Title: x.GetTitleText(), Subtitle: x.GetSubtitleText(), URL: shareURL(x)}
	case *waArmadilloApplication.Armadillo_Content_RavenMessage_, *waArmadilloApplication.Armadillo_Content_RavenMessageMsgr:
		m.Text = "A view-once photo or video; open it in Messenger."
	case *waArmadilloApplication.Armadillo_Content_ImageGalleryMessage_:
		m.Text = "A set of pictures; open it in Messenger."
	default:
		s.log.Debug().Type("content", c).Msg("Unhandled encrypted card")
	}
	return m
}

func shareURL(x *waArmadilloXMA.ExtendedContentMessage) string {
	for _, cta := range x.GetCtas() {
		if u := cta.GetActionURL(); u != "" {
			return u
		}
		if u := cta.GetNativeURL(); u != "" {
			return u
		}
	}
	return ""
}

// --- sending on the channel ---

// sendE2EE sends one message on the channel and returns its id and server time.
func (s *Session) sendE2EE(t *Thread, msg armadillo.RealMessageApplicationSub, meta *waMsgApplication.MessageApplication_Metadata) (string, int64, error) {
	client := s.e2eeClient()
	if client == nil {
		return "", 0, fmt.Errorf("%w: the encrypted channel is not up", ErrNotConnected)
	}
	ctx, cancel := s.ctx()
	defer cancel()
	otid := strconv.FormatInt(methods.GenerateEpochID(), 10)
	resp, err := client.SendFBMessage(ctx, waJID(t.jid), msg, meta, whatsmeow.SendRequestExtra{ID: otid})
	if err != nil {
		return "", 0, wrap(err)
	}
	return resp.ID, resp.Timestamp.UnixMilli(), nil
}

func consumerContent(content *waConsumerApplication.ConsumerApplication_Content) *waConsumerApplication.ConsumerApplication {
	return &waConsumerApplication.ConsumerApplication{
		Payload: &waConsumerApplication.ConsumerApplication_Payload{
			Payload: &waConsumerApplication.ConsumerApplication_Payload_Content{Content: content},
		},
	}
}

func textContent(text string) *waConsumerApplication.ConsumerApplication {
	return consumerContent(&waConsumerApplication.ConsumerApplication_Content{
		Content: &waConsumerApplication.ConsumerApplication_Content_MessageText{
			MessageText: &waCommon.MessageText{Text: proto.String(text)},
		},
	})
}

// replyMeta quotes a message the channel knows; nil when there is nothing to quote.
func (s *Session) replyMeta(replyTo string) *waMsgApplication.MessageApplication_Metadata {
	if replyTo == "" {
		return nil
	}
	s.mu.Lock()
	p, known := s.messages[replyTo]
	s.mu.Unlock()
	quoted := &waMsgApplication.MessageApplication_Metadata_QuotedMessage{StanzaID: proto.String(replyTo)}
	if known && p.sender != 0 {
		quoted.Participant = proto.String(waJID(p.sender).String())
	}
	return &waMsgApplication.MessageApplication_Metadata{QuotedMessage: quoted}
}

// waKey names a message on the channel the way reactions, edits, and unsends must.
func (s *Session) waKey(t *Thread, messageID string) *waCommon.MessageKey {
	s.mu.Lock()
	p := s.messages[messageID]
	s.mu.Unlock()
	key := &waCommon.MessageKey{RemoteJID: proto.String(waJID(t.jid).String()), ID: proto.String(messageID)}
	if p.fromMe {
		key.FromMe = proto.Bool(true)
	} else if t.IsGroup && p.sender != 0 {
		key.Participant = proto.String(waJID(p.sender).String())
	}
	return key
}

func (s *Session) sendE2EEText(t *Thread, threadID, text, replyTo string) (Message, error) {
	msgID, ts, err := s.sendE2EE(t, textContent(text), s.replyMeta(replyTo))
	if err != nil {
		return Message{}, err
	}
	sent := Message{ID: msgID, Thread: threadID, Sender: s.OwnID(), Timestamp: ts, Kind: "text", Text: text, ReplyTo: replyTo}
	s.keepSent(t, sent)
	return sent, nil
}

func (s *Session) keepSent(t *Thread, sent Message) {
	s.mu.Lock()
	s.rememberWA(sent)
	t.addMessages([]Message{sent})
	if t.LastAt < sent.Timestamp {
		t.LastAt = sent.Timestamp
	}
	s.mu.Unlock()
}

// sendE2EEMedia uploads one file to the channel's media store and sends it, with text as
// its caption where the kind allows one.
func (s *Session) sendE2EEMedia(t *Thread, threadID, path, mime, kind, fileName, text, replyTo string) (Message, error) {
	client := s.e2eeClient()
	if client == nil {
		return Message{}, fmt.Errorf("%w: the encrypted channel is not up", ErrNotConnected)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return Message{}, err
	}
	mediaType := whatsmeow.MediaDocument
	switch kind {
	case "image", "sticker":
		mediaType = whatsmeow.MediaImage
	case "video", "gif":
		mediaType = whatsmeow.MediaVideo
	case "voice":
		mediaType = whatsmeow.MediaAudio
	}
	ctx, cancel := s.ctx()
	defer cancel()
	uploaded, err := client.Upload(ctx, data, mediaType)
	if err != nil {
		return Message{}, wrap(err)
	}
	width, height := 0, 0
	if mediaType == whatsmeow.MediaImage {
		if cfg, _, err := image.DecodeConfig(strings.NewReader(string(data))); err == nil {
			width, height = cfg.Width, cfg.Height
		}
	}
	tw, th := thumbSize(width, height, kind == "image")
	transport := &waMediaTransport.WAMediaTransport{
		Integral: &waMediaTransport.WAMediaTransport_Integral{
			FileSHA256:        uploaded.FileSHA256,
			MediaKey:          uploaded.MediaKey,
			FileEncSHA256:     uploaded.FileEncSHA256,
			DirectPath:        &uploaded.DirectPath,
			MediaKeyTimestamp: proto.Int64(time.Now().Unix()),
		},
		Ancillary: &waMediaTransport.WAMediaTransport_Ancillary{
			FileLength: proto.Uint64(uint64(len(data))),
			Mimetype:   proto.String(mime),
			// Messenger's phone apps refuse to show media without a thumbnail size.
			Thumbnail: &waMediaTransport.WAMediaTransport_Ancillary_Thumbnail{
				ThumbnailWidth: proto.Uint32(uint32(tw)), ThumbnailHeight: proto.Uint32(uint32(th)),
			},
			ObjectID: &uploaded.ObjectID,
		},
	}
	content, err := wrapMedia(kind, transport, text, fileName, width, height)
	if err != nil {
		return Message{}, err
	}
	msgID, ts, err := s.sendE2EE(t, consumerContent(content), s.replyMeta(replyTo))
	if err != nil {
		return Message{}, err
	}
	sent := Message{ID: msgID, Thread: threadID, Sender: s.OwnID(), Timestamp: ts, Kind: kind, Text: text, ReplyTo: replyTo,
		Media: []Media{{Kind: kind, URL: "file://" + path, Mime: mime, FileName: fileName, Size: int64(len(data)), Width: width, Height: height}}}
	s.keepSent(t, sent)
	return sent, nil
}

func thumbSize(w, h int, picture bool) (int, int) {
	if w == 0 || h == 0 {
		if picture {
			return 400, 400
		}
		return 0, 0
	}
	if w > 400 || h > 400 {
		if w > h {
			return 400, h * 400 / w
		}
		return w * 400 / h, 400
	}
	return w, h
}

func wrapMedia(kind string, tr *waMediaTransport.WAMediaTransport, text, fileName string, w, h int) (*waConsumerApplication.ConsumerApplication_Content, error) {
	caption := &waCommon.MessageText{}
	if text != "" {
		caption.Text = proto.String(text)
	}
	out := &waConsumerApplication.ConsumerApplication_Content{}
	var err error
	switch kind {
	case "image":
		msg := &waConsumerApplication.ConsumerApplication_ImageMessage{Caption: caption}
		err = msg.Set(&waMediaTransport.ImageTransport{
			Integral:  &waMediaTransport.ImageTransport_Integral{Transport: tr},
			Ancillary: &waMediaTransport.ImageTransport_Ancillary{Width: proto.Uint32(uint32(w)), Height: proto.Uint32(uint32(h))},
		})
		out.Content = &waConsumerApplication.ConsumerApplication_Content_ImageMessage{ImageMessage: msg}
	case "sticker":
		msg := &waConsumerApplication.ConsumerApplication_StickerMessage{}
		err = msg.Set(&waMediaTransport.StickerTransport{
			Integral:  &waMediaTransport.StickerTransport_Integral{Transport: tr},
			Ancillary: &waMediaTransport.StickerTransport_Ancillary{Width: proto.Uint32(uint32(w)), Height: proto.Uint32(uint32(h))},
		})
		out.Content = &waConsumerApplication.ConsumerApplication_Content_StickerMessage{StickerMessage: msg}
	case "video", "gif":
		msg := &waConsumerApplication.ConsumerApplication_VideoMessage{Caption: caption}
		anc := &waMediaTransport.VideoTransport_Ancillary{Width: proto.Uint32(uint32(w)), Height: proto.Uint32(uint32(h))}
		if kind == "gif" {
			anc.GifPlayback = proto.Bool(true)
		}
		err = msg.Set(&waMediaTransport.VideoTransport{Integral: &waMediaTransport.VideoTransport_Integral{Transport: tr}, Ancillary: anc})
		out.Content = &waConsumerApplication.ConsumerApplication_Content_VideoMessage{VideoMessage: msg}
	case "voice":
		msg := &waConsumerApplication.ConsumerApplication_AudioMessage{PTT: proto.Bool(true)}
		err = msg.Set(&waMediaTransport.AudioTransport{Integral: &waMediaTransport.AudioTransport_Integral{Transport: tr}, Ancillary: &waMediaTransport.AudioTransport_Ancillary{}})
		out.Content = &waConsumerApplication.ConsumerApplication_Content_AudioMessage{AudioMessage: msg}
	default:
		msg := &waConsumerApplication.ConsumerApplication_DocumentMessage{FileName: proto.String(fileName)}
		err = msg.Set(&waMediaTransport.DocumentTransport{Integral: &waMediaTransport.DocumentTransport_Integral{Transport: tr}, Ancillary: &waMediaTransport.DocumentTransport_Ancillary{}})
		out.Content = &waConsumerApplication.ConsumerApplication_Content_DocumentMessage{DocumentMessage: msg}
	}
	if err != nil {
		return nil, fmt.Errorf("could not wrap the media: %w", err)
	}
	return out, nil
}

func (s *Session) sendE2EEReaction(t *Thread, messageID, emoji string) error {
	_, _, err := s.sendE2EE(t, consumerContent(&waConsumerApplication.ConsumerApplication_Content{
		Content: &waConsumerApplication.ConsumerApplication_Content_ReactionMessage{
			ReactionMessage: &waConsumerApplication.ConsumerApplication_ReactionMessage{
				Key: s.waKey(t, messageID), Text: proto.String(emoji), SenderTimestampMS: proto.Int64(time.Now().UnixMilli()),
			},
		},
	}), nil)
	return err
}

func (s *Session) sendE2EEUnsend(t *Thread, messageID string) error {
	_, _, err := s.sendE2EE(t, &waConsumerApplication.ConsumerApplication{
		Payload: &waConsumerApplication.ConsumerApplication_Payload{
			Payload: &waConsumerApplication.ConsumerApplication_Payload_ApplicationData{
				ApplicationData: &waConsumerApplication.ConsumerApplication_ApplicationData{
					ApplicationContent: &waConsumerApplication.ConsumerApplication_ApplicationData_Revoke{
						Revoke: &waConsumerApplication.ConsumerApplication_RevokeMessage{Key: s.waKey(t, messageID)},
					},
				},
			},
		},
	}, nil)
	return err
}

func (s *Session) sendE2EEEdit(t *Thread, messageID, text string) error {
	_, _, err := s.sendE2EE(t, consumerContent(&waConsumerApplication.ConsumerApplication_Content{
		Content: &waConsumerApplication.ConsumerApplication_Content_EditMessage{
			EditMessage: &waConsumerApplication.ConsumerApplication_EditMessage{
				Key: s.waKey(t, messageID), Message: &waCommon.MessageText{Text: proto.String(text)},
				TimestampMS: proto.Int64(time.Now().UnixMilli()),
			},
		},
	}), nil)
	return err
}

// markReadE2EE tells the channel which of its messages were read, by sender as it wants.
func (s *Session) markReadE2EE(t *Thread, timestamp int64) {
	client := s.e2eeClient()
	if client == nil {
		return
	}
	s.mu.Lock()
	bySender := map[int64][]string{}
	for _, ref := range t.waUnread {
		bySender[ref.sender] = append(bySender[ref.sender], ref.id)
	}
	t.waUnread = nil
	s.mu.Unlock()
	at := time.UnixMilli(timestamp)
	for sender, ids := range bySender {
		ctx, cancel := s.ctx()
		if err := client.MarkRead(ctx, ids, at, waJID(t.jid), waJID(sender)); err != nil {
			s.log.Warn().Err(err).Msg("Encrypted channel: read mark refused")
		}
		cancel()
	}
}

func (s *Session) setTypingE2EE(t *Thread, typing bool) error {
	client := s.e2eeClient()
	if client == nil {
		return ErrNotConnected
	}
	state := waTypes.ChatPresencePaused
	if typing {
		state = waTypes.ChatPresenceComposing
	}
	ctx, cancel := s.ctx()
	defer cancel()
	return wrap(client.SendChatPresence(ctx, waJID(t.jid), state, waTypes.ChatPresenceMediaText))
}
