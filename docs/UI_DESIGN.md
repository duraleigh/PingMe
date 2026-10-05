# PingMe UI Design

Status: draft v0.1
Companion to: [DESIGN.md](DESIGN.md) (architecture)
Design system: Material 3 Expressive, implemented with Jetpack Compose Material 3
(`androidx.compose.material3` 1.4 or newer, `MaterialExpressiveTheme`)

---

## 1. Design principles

1. **Expressive by default, quiet on request.** PingMe leans into Material 3 Expressive:
   springy motion, bold shapes, emphasized type, colour everywhere. Every one of those
   can be dialled down or off in Settings, and the system "remove animations" setting is
   always honoured.
2. **One inbox, many networks, one language.** Every network's chat looks and behaves
   the same way. Network identity is shown with a badge and an accent, never with a
   different layout.
3. **Honest about capabilities.** When a network cannot do something (reply, delete for
   everyone, a reaction emoji), the control is visibly disabled with a one-line reason,
   never hidden and never silently downgraded.
4. **Customisation is a feature, not a settings dump.** Personalisation lives in a
   dedicated Appearance studio with live preview, and most of it can also be applied per
   chat.
5. **Thumb-first.** Primary actions sit in the bottom third of the screen: the composer,
   the floating toolbar, the FAB menu, and the reaction bar.

---

## 2. Material 3 Expressive foundation

### 2.1 Theme

- `MaterialExpressiveTheme` with `MotionScheme.expressive()` as the default motion
  scheme. Users can switch to `MotionScheme.standard()` from the Animations setting.
- **Colour**: dynamic colour (Material You) from the wallpaper by default. Alternatives:
  a user-picked seed colour, a set of curated presets, or a fully manual palette.
  Light, dark, and pure-black (AMOLED) modes, plus contrast levels (standard, medium,
  high) mapped to the Material colour scheme variants.
- **Shape**: Material 3 Expressive's shape library (`MaterialShapes`) is used for
  avatars, the FAB, pinned-chat tiles, reaction chips, and the send button. The user
  picks a shape family (see section 4) and it flows through the whole app.
- **Typography**: emphasized type styles for headlines and chat titles. Custom fonts
  (section 4.4) replace the family while keeping the scale.

### 2.2 Components used, and where

| Component | Where |
|---|---|
| Large flexible top app bar | Inbox header, collapses to a compact bar on scroll |
| Connected button group | Inbox filters (All, Unread, per network) |
| FAB menu | Inbox: New chat, New group |
| Horizontal floating toolbar | Chat screen: contextual actions during multi-select |
| Send button | One button: tap to send; press and hold for Send later. (Owner decision at Gate G1, 2026-10-01: a split button is too easy to mis-tap, so the Expressive split button is not used here. Owner decision at Phase 3, 2026-10-01: no Send as SMS, because Google Messages gives a paired device no such choice.) |
| Loading indicator (shape-morphing) | Sync, history backfill, pairing |
| Toggle buttons | Chat settings, reaction set editor |
| Sliders (expressive) | Font size, corner radius, animation intensity |
| Bottom sheets | Message actions, attachment picker, GIF picker, emoji picker |
| Navigation bar / navigation rail | Bottom bar on phones, rail on tablets and unfolded foldables |

---

## 3. Screens

### 3.1 Inbox (home)

```
+----------------------------------------------+
|  PingMe                          [search] [me]|   large flexible top app bar
|  (All)  Unread   RCS   WhatsApp   Telegram   |   connected button group
|                                              |
|  PINNED                                      |
|  [◉ Mom] [◉ Work] [◉ Sam] [◉ Fam]  ...       |   pinned row, shaped avatars
|                                              |
|  ● Sam Ortiz                RCS   now        |
|    Are you still coming tonight?        (2)  |
|  ○ Design team              WA    12:40      |
|    Priya: new mocks are up                   |
|  ○ Dad                      SMS   Tue        |
|    Call me when you can                      |
|  ...                                         |
|                                     (+) FAB  |
|  [Chats]   [Calls*]   [Settings]             |   navigation bar (*later)
+----------------------------------------------+
```

- **Pinned row**: horizontally scrolling tiles of the user's pinned chats, drawn with the
  chosen shape family. Long-press to reorder or unpin.
  Users can choose a grid, a row, or "pinned at top of list" style. Up to 12 pins.
  In the grid, one or two pins sit centred, three to five share the width evenly, and
  more wrap at five a line (owner, Gate G2). An unread tile must shout as loudly as an
  unread row (owner, Gate G2: a dot alone was missed): a 3 dp ring in the primary colour
  around the avatar, following the tile's shape, a 20 dp dot rimmed in the background
  colour on its corner, and the name in bold primary colour. Muted chats use the
  outline colour for all three.
- **Top app bar**: has its own surface colour with rounded bottom corners, so the list
  scrolls underneath it cleanly, as Google Messages does (owner, Gate G1).
- **List items**: avatar, name, last message preview, network badge, time, unread dot
  (a chat is unread or not; no per-chat count, owner decision 2026-10-01). An unread row
  must be obvious at a glance (owner, Gate G2): a 14 dp dot in the primary colour, name
  and preview in bold at full contrast, the time in the primary colour, and a faint
  primary tint across the whole row,
  mute icon. Sender name shown in previews for groups. When the last message is yours,
  its status mark (sending, sent, delivered, read, failed; the same marks as in the chat,
  drawn the way Google Messages draws its own on every network: one hollow circle-check
  sent, two hollow circle-checks delivered, two filled circle-checks read, the ticks cut
  out in the bubble's colour, so read never looks like delivered on any bubble colour;
  owner, Gate G2 and Gate G3) shows before the preview, as Google Messages does (owner,
  Gate G1). Google Messages rows carry no network badge at all: it is the phone's own
  texting, and the RCS, SMS, or MMS distinction lives on the bubbles (section 10.1).
  Every other network keeps its badge (owner, Gate G3).
- **Selecting rows**: press and hold an avatar to start selecting; a check replaces the
  avatar and a tap on any row adds or removes it. A bar above the list says how many
  and offers mark read, mark unread, mute, archive, low priority, and delete (with one
  confirmation) for all of them at once (owner, Gate G3).
- **Bottom bar**: its items are spaced evenly and centred across the full width. It holds
  five positions: four chosen as section 10.4 describes, and a fixed fifth, **More**, which
  opens a list of every filter and space not in the bar (owner, 2026-10-03). Unread is
  said by the label's colour, not a dot (owner, 2026-10-05): a network with unread
  messages shows its name in that network's bubble colour from Appearance ("Google
  Messages" in the Google Messages colour, "WhatsApp" in WhatsApp's, and so on); All,
  Unread, a space, or Low priority with unread messages shows its name in the theme's
  primary colour; and **More** shows in the theme's primary colour when anything behind it
  is unread. The list behind More colours its entries the same way.
- **Swipe actions**: left and right swipes are user-assignable from Pin or unpin, Archive,
  Mute or unmute, Mark read or unread, Low priority, Delete. Each direction can be
  set independently, and either can be turned off.
- **Press and hold** on any row or pinned tile opens an action sheet for that chat:
  Pin or unpin, Mark read or unread, Mute or unmute, Archive, Low priority, Obscure
  messages, Delete chat. There is no per-row pin icon; pinning lives here and in the
  swipe actions, which keeps every row free of chrome.
- **Status bar**: content is drawn edge to edge and the top bar is inset by exactly the
  system status bar height, 24 dp on most phones, and nothing more.
- **Filters**: the bottom bar, section 3.1 above, and section 10.4 for spaces.
- **FAB menu**: expands into New chat and New group. (Scan QR was removed by the
  owner, 2026-09-30.)
- **Connection health chip**: when any connector is not Connected, a slim chip appears
  under the app bar: "RCS reconnecting" or "RCS needs attention, tap to fix". This is
  the single place connection state surfaces on the home screen.

### 3.2 Chat screen

```
+----------------------------------------------+
| <  ◉ Sam Ortiz  · RCS · typing...     [⋮]    |
|                                              |
|            ┌──────────────────────────┐      |
|            │ Are you still coming     │      |
|            │ tonight?                 │      |
|            └──────────────────────────┘      |
|              ❤️ 2  😂 1                       |   reaction chips
|                                              |
|  ┌─────────────────────────┐                 |
|  │ ┃ Sam: Are you still... │                 |   reply quote
|  │ Yes! Leaving at 7       │                 |
|  └─────────────────────────┘  (✓✓) Read      |
|                                              |
|  ┌────────────────────────────────────┐      |
|  │ ▶ ▁▃▅▇▅▃▁▃▅▂  0:12          1.5x  │      |   voice note
|  └────────────────────────────────────┘      |
|                                              |
| ┌ Replying to Sam ──────────────────── ✕ ┐   |   reply preview strip
| │ Are you still coming tonight?          │   |
| └────────────────────────────────────────┘   |
| [+] [GIF] [ Message...              ] [🎤/➤] |   composer
+----------------------------------------------+
```

- **Bubbles**: incoming bubbles are Material 3 tonal surfaces (surface container
  high) on every network. Outgoing bubbles take the colour of the network the message
  went out on, so a glance at your own bubble says which service carried it. See
  section 10.1 for the palette and the RCS versus SMS distinction. Consecutive messages from the same sender are
  grouped with tighter spacing and only the last bubble carries the tail.
- **Status**: sending, sent, delivered, read, failed. Failed messages show a retry
  button; no "send as SMS" (Google Messages gives paired devices no such choice). The
  marks are the same on every network (section 3.1): Google Messages' own look, so a
  WhatsApp tick reads exactly like an RCS one (owner, Gate G3).
- **Header badge**: the network badge sits beside the live status, except for Google
  Messages, which carries none (owner, Gate G3).
- **Day separators** and a "new messages" divider.
- **Header**: avatar, name, network badge, live status (typing, online, last seen where
  the network provides it). Tap opens Chat details.
- **Scroll to bottom** pill with unread count, appears when scrolled up.
- **Pinned message banner** under the header when the chat has a pinned message (local
  feature, works on every network).

### 3.3 Message actions (long-press)

Long-pressing a bubble does three things at once: the bubble lifts with a spring, the
rest of the chat dims and blurs, and two surfaces appear:

1. **Reaction bar** above the bubble: the user's quick reactions (section 5.4) plus a
   "+" that opens the full emoji picker.
2. **Action sheet** below: Reply, Voice reply, Copy, Forward, Pin, Select, Info,
   Delete, and Edit (when the network allows).

The reaction bar, the lifted bubble, and the action sheet never overlap, the way Google
Messages lays it out: when the bubble is near the top or bottom of the screen, or too
tall, the bubble moves (or is clipped to a preview) so all three fit and every reaction
can be tapped (owner, Gate G1).

A **scheduled message** (section 10.13) shows its own actions here instead: Edit,
Reschedule, Send now, and Unschedule (which deletes it), plus Copy (owner, Gate G1).

Multi-select (via Select, or long-press then tap) replaces the composer with a
horizontal floating toolbar: Copy, Forward, Delete, Share, with the count in the app
bar.

### 3.4 Chat details

Reached from the chat header. Sections:

- Media, links, and files.
- **Notifications**: mute (1h, 8h, 1 week, forever), custom sound, custom vibration.
- **Appearance**: this chat's colour, bubble style, wallpaper, and font size override.
- **Reactions**: this chat's quick-reaction set override.
- Members (groups), pinned messages, search in chat.
- Avatar source (Google Contacts photo or a network profile photo) and an editable
  display name, section 10.18.
- Pin chat, archive, block, delete chat.

### 3.5 Appearance studio (Settings > Appearance)

A live preview of a fake conversation sits at the top of the screen and updates
instantly as the user changes anything below. Sections are described in section 4.

### 3.6 Setup and pairing

Covered in DESIGN.md section 5.4. Visually: full-screen steps, one decision per
screen, a shape-morphing loading indicator while waiting for Google Messages to confirm,
and the emoji-match step shows the emoji at display size.

WhatsApp links by phone number only (owner, 2026-10-02): the number with its country
code, then the eight-character code shown at display size with the path to type it on
the phone (WhatsApp > Linked devices > Link a device > Link with phone number instead),
then Done. There is no QR step.

### 3.7 Adaptive layouts

- Phones: single pane, bottom navigation bar.
- Tablets and unfolded foldables: list-detail, inbox on the left, chat on the right,
  navigation rail.
- Predictive back everywhere.

---

## 4. Customisation

Everything below has an app-wide default and, where marked (per chat), an override in
Chat details. Themes can be exported and imported as a JSON file so people can share
them.

### 4.1 Colour

- Dynamic colour from wallpaper, seed colour, curated presets, or manual palette.
- Light, dark, AMOLED black, follow system.
- Contrast level.
- Outgoing bubble colour per network (defaults in section 10.1) and per chat.
- Per-network accent colour used in badges and the header.
- Sender name colours in groups: auto-assigned or fixed.

### 4.2 Shape

- Shape family: Round, Soft, Sharp, or Expressive (cookie, clover, sunny, and other
  Material 3 Expressive shapes for avatars and pins).
- Bubble corner radius slider, bubble tails on or off.
- Bubble style: Tonal (default), Outlined, Filled, Gradient (from the network's bubble colour to
  its gradient-end colour, the two swatches in Appearance; owner, 2026-10-04), Pill. The
  network badge is drawn in the bubble colour.

### 4.3 Layout and density

- Inbox density: Comfortable, Compact, Spacious.
- Pinned chats presentation: Row, Grid, Top of list.
- Avatar size and visibility in chat.
- Chat wallpaper: none, colour, gradient, image, blurred image (per chat).
- Timestamps: always, on tap, grouped.

### 4.4 Fonts

- Bundled, all under the SIL Open Font License, all variable fonts so emphasized
  weights work: Roboto Flex (default), Inter, Manrope, Nunito, Lexend,
  Atkinson Hyperlegible, JetBrains Mono.
- **Import your own**: pick any `.ttf` or `.otf` from the file picker. Stored in
  app-private storage. If the font is not variable, emphasized styles fall back to
  its bold face.
- Separate choices for UI text and message text.
- Font size slider (per chat), line height, and a "use emphasized headlines" toggle.

### 4.5 Motion and feedback

- Animation intensity: Off, Subtle, Full, Extra. Governs reactions, bubble entrance,
  and transitions. "Off" also selects the standard motion scheme.
- Haptics: Off, Light, Strong.
- Respects the system reduce-motion setting regardless of the in-app choice.

### 4.6 Icon and shortcuts

- App icon variants via activity aliases.
- Swipe action assignment (section 3.1).
- Home screen conversation shortcuts and Android chat bubbles per chat.

---

## 5. Messaging features

Each feature lists what PingMe does everywhere and what depends on the network.
Connectors advertise capabilities; the UI reads them and disables what is unsupported
with a short reason.

### 5.1 Pinning

- **Chats**: pin from swipe, long-press, or Chat details. Pinned chats appear in the
  pinned row on the inbox and are excluded from archive. Up to 12, reorderable.
- **Messages**: pin any message inside a chat. Shown as a banner under the header,
  tap to jump. Local-only, so it works on every network. Where a network has native
  pins (Telegram, WhatsApp), PingMe mirrors them.

### 5.2 Replying to specific messages

- Swipe right on a bubble, or Reply from the action sheet. The composer shows a reply
  strip with the quoted message and a dismiss button.
- Sent replies render with a quote block; tapping the quote scrolls to the original
  with a highlight pulse.
- Network behaviour:
  - RCS (via Google Messages), WhatsApp, Telegram, Signal: native replies.
  - SMS and MMS: no native reply. PingMe sends the message with a short quoted line
    prefix, and shows the reply strip locally. The strip says "Sent as quoted text on
    SMS" the first time.

### 5.3 Deleting individual messages

- **Delete for me**: always available, on every network. Soft-deleted with a 5-second
  Undo snackbar, then removed from the local store. In Google Messages mode the
  deletion is also sent to Google Messages so the phone's copy matches.
- **Delete for everyone**: shown only when the connector supports it, with the
  network's time limit displayed ("available for 1 hour"). Supported on WhatsApp,
  Telegram, and Signal. RCS through Google Messages depends on what Google exposes to
  paired devices; tracked as an open question.
- Multi-select delete works the same way with a single confirmation.
- Deleted-for-everyone messages from others show a placeholder "Message deleted".

### 5.4 Reactions

**Quick-reaction set.** The bar that appears on long-press shows the user's own set.
Default: ❤️ 😂 👍 😮 😢 🔥. The user edits it in Settings > Reactions: reorder,
remove, add any emoji from the full picker, up to 8. A per-chat override lives in Chat
details, so a family group can have different quick reactions than a work group.

**Any emoji.** The "+" at the end of the bar opens the full emoji picker with search,
recent, and skin-tone variants.

**Network limits.**
- RCS via Google Messages: any emoji (Google Messages supports arbitrary emoji
  reactions). Falls back to text ("Reacted ❤️ to ...") on SMS, which is what Google
  Messages itself does.
- WhatsApp, Signal: any emoji.
- Telegram: restricted set for non-Premium accounts. The connector reports the allowed
  list and unsupported emoji are shown greyed with "Not available on Telegram".

**The animation ("Reaction Burst").** This is intentionally loud.

1. *Pick.* The chosen emoji springs out of the bar at 3x scale with overshoot, and a
   strong haptic fires.
2. *Land.* It flies along an arc to the bubble's corner and shrinks into a reaction
   chip. The bubble does a squash-and-stretch wobble on impact.
3. *Celebrate.* From the chip, 16 to 24 copies of the emoji burst outward with
   confetti particles in the theme's primary and tertiary colours, drifting up and
   fading over about 800 ms. A large ghost of the emoji rises behind the bubble and
   dissolves.
4. *Extra intensity* adds a screen-edge glow in the emoji's dominant colour for
   ❤️ 🔥 🎉 👏 and any emoji the user marks as "special".

Incoming reactions play the Land and Celebrate phases so the recipient sees it too.
Removing a reaction plays a small pop. Built with Compose animations using the spring
specs from the motion scheme and a Canvas particle layer, no external animation
library. Intensity Subtle keeps only Pick and Land. Off disables all of it.

### 5.5 GIFs

- A GIF button in the composer opens a bottom-sheet picker with search, trending, and
  favourites. Provider is pluggable: Tenor by default (API key supplied at build time,
  since PingMe has no servers), with the option to disable online search entirely.
- Keyboard-inserted GIFs (Gboard and others) are accepted through the standard
  Android rich-content insertion path.
- GIFs also come from the device gallery through the attachment picker.
- Network behaviour: RCS, WhatsApp, Telegram, Signal send GIFs as animated media. SMS
  converts to MMS, and PingMe warns about the carrier size limit and offers to shrink.
- Received GIFs autoplay in chat, with a data-saver setting to play on tap.

### 5.6 Voice notes and voice replies

- The composer's right button is a microphone when the text field is empty and a send
  button when it has text.
- **Two ways to record**, as WhatsApp does (owner, Gate G1, 2026-10-01):
  - **Tap** the mic: recording starts and the composer becomes a recording bar with a
    timer, a live waveform, a delete (trash) button, Pause/Resume, and Send. While
    paused, the recording so far can be played back; Resume carries on recording.
  - **Hold** the mic: it records while held. Release to send, slide left to cancel,
    slide up to lock into the same recording bar as a tap.
- **As a reply**: pick Reply (or swipe) on a message, then hold the mic. The voice
  note carries the reply quote just like a text reply. There is also a direct "Voice
  reply" action in the long-press action sheet that opens the reply strip and starts
  recording in locked mode immediately, so it is one tap from the message to
  recording.
- Playback bubble: play and pause, scrubbable waveform, duration, speed toggle
  (1x, 1.5x, 2x). Proximity sensor switches playback to the earpiece when the phone is
  raised.
- Optional on-device transcription shown under the bubble, using Android's speech
  recogniser; off by default.
- Network behaviour: RCS via Google Messages, WhatsApp, Telegram, and Signal deliver as
  native voice messages. SMS falls back to an MMS audio attachment with the same
  size warning as GIFs. Whether RCS voice notes sent through the pairing arrive with the
  voice-message presentation, or as a plain audio file, is an open question.

### 5.7 Google Voice specifics

Google Voice chats carry a "GV" network badge and behave like SMS: quoted-text
replies, MMS-sized media, and reactions sent as text. New chat from the FAB lists
Google Voice as a sender only once the connector can start conversations; until then
the option is shown disabled with "Start this chat in the Google Voice app". Voice
calls are not in scope, so the call icon in a Google Voice chat header deep-links to
the Google Voice app.

### 5.8 Attachments

PingMe is in Android's share menu (owner, Gate G3): text, pictures, videos, sounds, and
files shared from another app open a picker of chats, with people from the networks that
can start a chat once a search is typed. Several can be picked; each gets its own
message, one after the other, the way Google Messages does. One pick opens that chat;
more go back to the inbox. A voice note, sound, or file in a chat also saves to the
phone's Downloads (a PingMe folder) from the hold menu's Save.

The "+" button opens a bottom sheet: Camera, Gallery, File, Location, Contact. Images
support multi-select with captions. Everything reports send progress on the bubble.

Pictures and videos fill their bubble's width and keep their shape (a video shows its
length in the corner), and a tap opens them full-screen inside PingMe: pictures fit the
screen, videos play with play, pause, and seek; back or the close button returns to the
chat. Files and contact cards open in the app that handles them. Never hand a picture or
video to another app (owner, Gate G2, 2026-10-02).

A sent picture or video keeps showing the file PingMe just sent; it is never thrown away
and downloaded back. Updates to a message from the network (a reaction, a status, a
history re-fetch) never lose files PingMe already saved.

The network's system notes ("switched to RCS", "X joined") are not messages and are not
drawn as bubbles.

---

## 6. Notifications

### 6.1 Per-chat sounds and vibration

Android ties sounds to notification channels, so PingMe creates one channel per chat
the moment the user customises that chat, and keeps a shared default channel for
everything else. Android does not let an app change a channel's sound after creation,
so when the user picks a new sound PingMe deletes that chat's channel and creates a
fresh one with a new ID. This is invisible to the user.

In Chat details > Notifications:

- Sound: system picker or any audio file from the device.
- Vibration pattern: several presets plus Off.
- Per-network defaults live under Settings > Notifications, so all WhatsApp chats can
  share a sound while a few individual chats override it.

### 6.2 Conversation notifications

Every chat is registered as an Android conversation with a person and a shortcut, so
Android's own per-conversation controls, priority conversations, and chat bubbles all
work. Notifications offer inline Reply and Mark read actions and are grouped per chat.

Behaviour the owner set at Gate G2 (2026-10-02):
- Tapping the notification opens that chat, landing on the newest message.
- Opening or reading the chat takes its notification down; so does Reply or Mark read
  from the notification. The chat on screen never raises one.
- Only a message PingMe has not stored before notifies. A reaction, a delivery or read
  change, or a re-sent copy of a known message never does.
- Media reads as "<sender> sent a picture" (video, GIF, sticker, voice message, file,
  location, contact), and pictures, GIFs, stickers, videos, and voice notes are fetched
  the moment they arrive, so they are there when the chat opens.
- A keyword match is named under the notification ("Keyword: urgent").

Behaviour the owner set at Gate G3 (2026-10-02):
- With PingMe open on any screen but that chat, a message makes its sound and nothing
  lands in the shade. The chat on screen stays silent.
- A chat read elsewhere (on the phone, in Google Messages) or deleted takes its
  notification down, and the group line goes with the last one.
- A picture's notification says "sent a picture" with no "downloading" note, and shows the
  picture once it is in. Pictures are fetched the moment Google Messages has finished
  fetching them itself, with no retry wait.
- An iPhone reaction that arrives as text over SMS ("Loved an image", "Laughed at “…”")
  lands as a reaction on the message it means, never as a bubble, and never notifies.
- Google Messages is one network, named "Google Messages" everywhere; RCS, SMS, and MMS are
  how a message travelled. RCS, SMS, and MMS with one number are one thread (the phone can
  keep two conversations for one person); no other network ever folds chats on its own.

### 6.3 Avoiding double notifications

In Google Messages mode both apps would notify for every text. PingMe reminds the user to
turn Google Messages' own notifications off: once as a prompt the first time the inbox
opens after pairing, and always as the first row under Settings > Notifications. The
reminder names the path (Settings > Apps > Messages > Notifications) and nothing more; PingMe
never opens or changes another app's settings (owner decision, 2026-10-02).

---

### 6.4 Instagram inbox folders

Instagram sorts direct messages into three folders: Primary, General, and Requests.
PingMe keeps that split and lets notifications differ per folder.

**Where the folder comes from.** The connector reads each thread's folder from
Instagram's inbox and pending-inbox endpoints and stores it as chat metadata. When
Instagram moves a thread (a request is accepted, or the user moves a chat between
Primary and General in the Instagram app), PingMe updates on the next sync.

**How folders show in the inbox.**
- Primary and General chats appear in the normal list and can be pinned like any
  chat. A small "General" tag sits next to the Instagram network badge so the two are
  telling apart at a glance.
- Requests never appear in the main list or the pinned grid. They live under
  Requests in the avatar menu, with a count, and each one offers Accept, Decline, and
  Block. Accepting moves the chat into Primary immediately.
- The Instagram filter in the bottom bar shows Primary and General together. A
  long-press on it offers Primary only or General only, remembered until changed.

**Hiding General entirely.** Settings > Accounts > Instagram has a "Show General in
inbox" switch, on by default. Turned off:
- General chats disappear from the main list, the pinned grid, and every filter.
- They stop counting toward every unread number: the filter badges in the bottom bar,
  the app icon badge, and the "Unread" filter.
- They stay reachable under General in the avatar menu, next to Requests, with their
  own count so nothing is lost.
- Their notifications follow the General row in section 6.4 as before, so hidden and
  silent are separate choices.

**Unread counting rule.** Every unread total in PingMe counts only chats that are
visible in the inbox, in accounts that are shown in the inbox, and not muted. Hidden folders, muted chats, and archived chats
never contribute. This is one rule applied everywhere, not a special case for
Instagram.

**Notifications per folder.** Under Settings > Notifications > Instagram there are
three independent rows, each with the full sound, vibration, and on-off controls from
section 6.1:

| Folder | Default |
|---|---|
| Primary | On, with sound |
| General | Silent, badge only |
| Requests | Off |

So the answer to "only notify me for Primary" is the default. Each folder gets its
own notification channel, and a per-chat override in Chat details still wins over the
folder default, so one General chat can be loud while the rest stay silent.

**Moving chats between folders.** Chat details has a "Move to Primary" or "Move to
General" action for Instagram chats, sent through the connector so the Instagram app
reflects it.

### 6.5 Several accounts on one network

A network can be connected more than once, and PingMe treats each connection as a
separate account with its own identity. The motivating case is Facebook: a personal
Messenger account and a Facebook Page inbox are two accounts on the Messenger
network.

**Naming and identity.** Each account gets a name the user can edit ("Messenger",
"Page: Duraleigh") and its own badge colour, so the two are distinguishable in the
list at a glance. The avatar menu lists every account with its connection state.

**Independent settings per account**, under Settings > Accounts > that account:
- Notifications: on, silent, or off, plus sound and vibration, as the network-level
  defaults in section 6.1. Per-chat overrides still win.
- Show in inbox: off hides every chat from that account from the list, the pinned
  grid, all filters, and all unread counts, exactly as the Instagram General switch
  in section 6.4. Hidden accounts stay reachable from the avatar menu.
- Everything else that exists at network level (default sound, swipe actions,
  appearance overrides) is available at account level too.

So "notify me for my personal Messenger but not the Page, and keep the Page out of
my unread counts" is two switches on the Page account: Notifications off, Show in
inbox off. The Page remains one tap away in the avatar menu with its own count.

**Filtering.** A network filter in the bottom bar covers all accounts on that network.
A long-press on it lists the accounts so one can be shown alone, the same gesture
used for Instagram folders.

**Page inbox specifics.** Page conversations are polled rather than pushed, so the
account row shows "checked 20 s ago" instead of a live connection state. Composer
shows a small notice when a reply would fall outside Meta's 24-hour window, and the
send button is disabled with that reason rather than failing after the fact.

## 7. Accessibility

- The Appearance studio shows no contrast warning. The live preview at the top is the
  check: the owner judges how a colour reads by eye (owner, 2026-10-05: "I know what I
  can see or not see using my own eyes"). Text on a picked colour is black or white,
  whichever reads better, and that is the only automatic help.
- Full TalkBack labelling, including reactions ("2 heart reactions, from Sam and Dad")
  and voice note state.
- Font size honours the system scale in addition to the in-app slider.
- Reduce-motion honoured system-wide as described in 4.5.
- Touch targets at least 48 dp, including reaction chips.

---

## 8. Capability matrix

What the UI can offer per network. Connectors report these at runtime; this table is
the expected result.

| Feature | RCS via Google Messages | SMS/MMS native | WhatsApp | Telegram | Signal | Instagram | Google Voice | Messenger | Facebook Page |
|---|---|---|---|---|---|---|---|---|---|
| Reply to message | Yes | Quoted text | Yes | Yes | Yes | Yes | Quoted text | Yes | Quoted text |
| Delete for me | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| Delete for everyone | Open question | No | Yes, time limited | Yes | Yes, time limited | Yes, unsend | No | Yes, unsend | No |
| Reactions, any emoji | Yes | Text fallback | Yes | Limited set | Yes | Yes | Text fallback | Yes | Text fallback |
| GIF | Yes | MMS, size limited | Yes | Yes | Yes | Yes | MMS, size limited | Yes | Yes |
| Voice note | Yes, presentation open question | MMS audio | Yes | Yes | Yes | Yes | MMS audio | Yes | As audio file |
| Typing indicator | Yes | No | Yes | Yes | Yes | Yes | No | Yes | No |
| Read receipts | Yes | No | Yes | Yes | Yes | Yes | No | Yes | No |
| Edit message | No | No | Yes, time limited | Yes | No | Yes, time limited | No | Yes, 15 minutes | No |
| Native pins | No | No | Yes | Yes | No | No | No | No | No |
| Inbox folders | No | No | No | Folders (Telegram) | No | Primary, General, Requests | No | Inbox, Requests | No |
| Several accounts at once | One phone number | One SIM per account | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| Start new conversation | Yes | Yes | Yes | Yes | Yes | Yes | Open question, library cannot today | Yes, by name | No, people write first |

---

## 10. Feature additions, round two

Everything in this section is in the plan. Items that carry a per-network caveat say
so in place.

### 10.1 Outgoing bubble colour by network

Each network has a signature colour. Outgoing bubbles use a light tonal tint of it in
light mode with dark text, and a deeper tone in dark mode with light text, so the
4.5:1 contrast rule holds. The greens and the blues are deliberately far apart in
lightness and hue so they stay distinguishable side by side, and every colour also
has a network badge next to it. A colour the user picks for a network is used exactly
as picked, in light and dark mode alike, with black or white text, whichever reads
better on it (owner, 2026-10-04: keeping only the hue of a pick made picks look
ignored); there is no contrast warning (owner, 2026-10-05).

| Network | Signature | Light-mode bubble | Note |
|---|---|---|---|
| Google Messages, RCS | Device dynamic primary | Primary container, full saturation | Follows the phone's theme |
| Google Messages, SMS or MMS | Same hue, desaturated | Primary container at reduced chroma, outlined edge, a small "SMS" or "MMS" tag where the status marks sit (RCS bubbles keep the marks; incoming SMS and MMS get the tag too) | Instantly reads as "this one went the old way" (owner, Gate G3) |
| WhatsApp | Bright green | Light mint tint | |
| Google Voice | Deep teal-green | Light teal tint, darker text | Clearly a different green from WhatsApp |
| Signal | Signal blue | Light periwinkle tint | |
| Telegram | Sky blue | Light sky tint | Lighter and cooler than Signal |
| Messenger | Messenger blue-violet | Light lavender-blue tint | Between Signal and Instagram |
| Instagram | Magenta-pink | Light pink tint | |

All of these are defaults. The Appearance studio can override any network's colour,
and a per-chat override wins over both.

### 10.2 Theme and colour

Already in section 4.1 and restated here because it was asked for explicitly: Light,
Dark, and Follow system, plus dynamic colour taken from the phone's wallpaper
(Material You) as the default colour source.

### 10.3 Privacy toggles: read receipts and typing

Settings > Privacy has two switches, each with a per-network override:

- **Send read receipts.** Off means PingMe never tells the network a message was
  read. Side effect the UI states plainly: the chat also stays unread in that
  network's own app, because "read" is the same signal.
- **Show when I am typing.** Off means no typing events are sent.

Neither affects what PingMe shows about other people.

### 10.4 Spaces: WhatsApp communities and Telegram topics

A **space** is a named group of chats that PingMe shows as one unit.
- A WhatsApp community becomes a space containing its announcement group and every
  linked group.
- A Telegram forum group becomes a space with one chat per topic.
- Users can also make their own spaces from any chats. Each space the user makes has
  an icon the user picks (shown in the bottom bar and the avatar menu), and a choice:
  its chats also show in All, or only inside the space (owner, Gate G1).

Spaces appear in the avatar menu and can be promoted into the bottom bar, which
becomes user-configurable: any mix of All, Unread, network filters, spaces, and Low
priority, up to four items; the fifth position is always "More", which surfaces every
space and filter not chosen (owner, 2026-10-03, so the bar can keep growing without
crowding). All is the default first item but can be removed (owner,
Gate G1); with All removed, the first item in the bar is where the inbox opens. Decided. Inside a space the inbox shows only that space's chats, with the same
pinned grid and list. Unread counts for a space follow the counting rule in section
6.4.

### 10.5 Double-tap reaction

Double-tapping a bubble sends the user's chosen quick reaction, default ❤️. It is set
in Settings > Reactions, can be overridden per chat, and plays the Reaction Burst.
Where a network does not allow that emoji, the double-tap falls back to the first
allowed emoji in the quick set and says so once.

### 10.6 One-time codes

Incoming messages are scanned on the phone for one-time codes. When one is found:
- The notification gets a "Copy code" action.
- With "Auto-copy one-time codes" on (off by default), the code is copied to the
  clipboard the moment it arrives and a small toast confirms it. Android allows
  clipboard writes from a background service, so this works while PingMe is not
  open.
- The clipboard entry is marked sensitive so Android hides it from clipboard
  previews.

### 10.7 Low priority

Any chat can be marked Low priority from its swipe action, the long-press menu, or
Chat details. Low priority chats:
- leave the main list and pinned grid and live under Low priority, reachable from
  the avatar menu or the bottom bar,
- are muted,
- never count toward any unread total,
- still match notification keywords (section 10.9) if the user allows that.

This replaces neither Archive (out of sight, unmuted, returns on new message) nor
Mute (in sight, silent). It is the third option: out of sight and silent.

### 10.8 Flippy reactions

When someone reacts to a message, the chat's row in the inbox flips over for a
moment to show the emoji large, then flips back to the preview. Under Settings >
Motion as "Flippy reactions", on by default at Full and Extra intensity, off at
Subtle and Off.

### 10.9 Notification keywords

Settings > Notifications > Keywords holds a list of words or phrases. Each entry has:
- match options: whole word, case-insensitive, and which networks or chats it
  applies to,
- its own sound and vibration, via its own notification channel,
- "Override Low priority and mute" on or off.

When an incoming message matches, the keyword rule's notification fires even if the
chat is otherwise silent, and the notification names the keyword that matched.

### 10.10 Obscured chats

Chat details > Privacy > "Obscure messages" blurs every bubble in that chat. Tapping
a bubble reveals it for a few seconds, then it blurs again. The chat's inbox preview
and its notifications are also hidden ("New message" only), and the chat is excluded
from the recent-apps screenshot when the system supports secure surfaces.

### 10.11 Clean links

Outgoing links are stripped of tracking parameters before sending, using the
open-source ClearURLs rule set kept up to date in the app. Incoming links are shown
cleaned, with the original kept and available from the link's long-press menu.
Settings > Privacy has "Clean links I send" (on) and "Clean links I receive" (on).
The rule set ships inside the app (`assets/clearurls/`, LGPL-3.0, licence beside it) and
is refreshed by a build command; the stored message always keeps the original text.

### 10.12 Link previews

Links render as a preview card with title, description, and image. Where the network
sends preview data with the message (WhatsApp, Telegram, Signal, Instagram,
Messenger), PingMe uses that and fetches nothing. Otherwise PingMe fetches the page's
metadata from the phone. Because that reveals the phone's IP address to the site,
Settings > Privacy > "Generate link previews" offers Always, Only on Wi-Fi, and
Never. Previews are cached with the message. PingMe reads at most 1 MB of the page and
2 MB of its picture, with short timeouts, and only fetches when a message carries a link
and no preview came with it.

### 10.13 Send later

Press and hold the send button to schedule. The
picker offers, in one sheet:
- quick chips: In 1 hour, This evening, Tomorrow morning, Tomorrow at this time,
  Next Monday morning,
- a date and time picker,
- a plain-text field that understands phrases like "friday 6pm" or "in 45 minutes".

Scheduled messages show in the chat as a pending bubble with a clock, editable and
cancellable until they go. PingMe has no server, so sending happens from the phone
using an exact alarm. If the phone is off or the network is disconnected at that
moment, PingMe sends as soon as it can and notifies the user that it went late (more
than five minutes after its time: "Sent late to <chat>", tap opens the chat). Android 12
and newer only allow exact alarms once the user grants "Alarms and reminders"; PingMe
asks once, after the first scheduled send, and without it falls back to a background
job that may run a few minutes late.

### 10.14 Search in chat

The search icon in the chat header opens a search bar with type chips: Text, Photos,
Videos, Links, Files, Voice notes, GIFs, plus a sender filter in groups and a date
jump. Text search uses a local full-text index over everything PingMe has synced.
Media chips show a grid. Results jump to the message in place with a highlight
pulse. Global search on the inbox uses the same index across all chats.

### 10.15 Merged chats

Chats with the same person on different networks can be merged into one thread.
- A merged chat shows every message from its underlying chats in one timeline, each
  bubble carrying only its network colour (section 10.1). The network badge appears
  beside the time and ticks when a bubble is tapped, and nowhere else on the bubble
  (owner, 2026-10-03).
- The header badge of a merged chat is tappable: a dropdown offers "All networks" and
  each member network. One network narrows the bubbles to it and sets the composer to
  it; "All" shows everything and sets the composer to the default service. A merged
  chat opens narrowed to the one network its unread messages came from; with unread
  from more than one network, or none, it opens on its default network, never on "All"
  (owner, 2026-10-03).
- The composer's text box carries a small network badge inside its left edge and a
  placeholder naming the network ("Send a WhatsApp message", "Send an Instagram DM").
  In a merged chat, tapping the badge opens the network menu to switch for the next
  messages (owner, 2026-10-03). The GIF picker lives in the + menu so the box is wide.
- Each person has a default service, set in the merged chat's details, and the chip
  starts there.
- PingMe suggests merges when people share a phone contact, a phone number, or a name
  or username that reads the same across networks. It never merges on its own; the
  user confirms, and every suggestion can be edited first: any proposed chat removed,
  any other one-to-one chat added, or the suggestion dismissed (owner, 2026-10-03).
- Any one-to-one chats on any networks can be merged by hand: Merge in the inbox's
  selection bar, or "Merge with…" in Chat details.
- Merged chats can be split again from Chat details: "Remove" on one member, labelled
  in words, or "Unmerge all" to dissolve the whole merge (owner, 2026-10-04).
- Group chats are never merged.
- Pins, low priority, mute, obscure, and notification settings apply to the merged
  chat as a whole.
- The merged chat's details let the user pick **which avatar** represents the person:
  the Google Contacts photo (default) or the profile photo from any of the merged
  networks, shown side by side as choices. Changing it changes the avatar in the
  inbox, the pinned grid, the chat header, and notifications.
- The merged chat's **name** is editable. It starts as the contact's name from Google
  Contacts and can be overridden with anything, for example "Sam (work)". The
  override applies everywhere the chat is shown and never changes the contact in
  Google Contacts.
- Merge suggestions are exactly that: PingMe proposes, the user confirms. There is no
  automatic merging, and group chats never merge. Decided.

### 10.16 Keep all media

Settings > Storage > "Save all incoming media" downloads every attachment as it
arrives, including ephemeral media wherever the network delivers it (decided, with
the per-network table below as the honest limit), to app-private storage by default or a user-chosen folder. Ephemeral media
is saved wherever the network actually delivers the bytes to a linked device. Where
it does not, PingMe cannot save what it never receives. Current state per network:

| Network | Ordinary media | View-once or disappearing media |
|---|---|---|
| RCS via Google Messages | Saved | Not a feature of RCS |
| WhatsApp | Saved | Delivery to linked devices has been switched on and off by WhatsApp; best effort |
| Signal | Saved | Not delivered to linked devices; cannot be saved |
| Telegram | Saved | Self-destruct is client-enforced; saved |
| Instagram | Saved | Delivered to the client; saved. Instagram may notify the sender the way it does for screenshots |
| Messenger | Saved | Same as Instagram |
| Google Voice | Saved | Not a feature |

### 10.17 Chat header: name, info, calls

- **Tapping the name** opens Chat details (section 3.4).
- **An info button** in Chat details opens the person's card in the phone's contacts
  app, via the contact lookup Android provides, for anyone matched to a contact.
  Unmatched people get "Add to contacts".
- **Phone icon and video icon** sit beside the overflow menu. Each places a call on
  the service the chat is currently using and, where Android allows it, starts the
  call immediately rather than opening a dial screen:

| Service | Audio | Video |
|---|---|---|
| Google Messages, SMS, merged chat on those | Dials the number immediately in the default dialer (needs the phone-call permission, asked once) | Opens Google Meet calling to that number, immediately where Meet exposes it |
| WhatsApp | Starts a WhatsApp call immediately, using the call entry WhatsApp registers in the phone's contacts | Same, video |
| Signal | Same mechanism, immediate | Same, video |
| Telegram | Same mechanism, immediate | Same, video |
| Google Voice | Opens Google Voice to that person; whether it can dial immediately is an open question | Not offered |
| Instagram | Opens the Instagram thread; Instagram exposes no call intent | Same |
| Messenger | Opens the Messenger thread; immediate calling is an open question | Same |

The immediate WhatsApp, Signal, and Telegram calls depend on the person being in the
phone's contacts with that app's contact sync on. When they are not, the icon opens
the app to that person instead, and the button's long-press explains why.

Decided behaviour for the rest: the icon always does the most direct thing the
service allows. For Google Voice, Messenger, and Meet that is "dial immediately" if
device testing finds an intent for it, otherwise "open the app to that person". For
Instagram it is "open the thread", which is all Instagram allows, and the icon
carries that as its long-press explanation.

### 10.18 Avatars from Google Contacts

Every person's avatar comes from Google Contacts first. PingMe matches people to
contacts by phone number for RCS, SMS, WhatsApp, Signal, Telegram, and Google Voice,
and by the links the user makes by hand for Instagram and Messenger. The match uses
Android's contacts provider, so it covers Google Contacts and any other synced
account, and it needs the contacts permission, asked once during setup with a plain
explanation.

Fallback order when there is no contact photo: the network's profile photo, then a
coloured tile with initials in the chosen shape. For an unmerged chat the user can
still switch that chat to the network's photo from Chat details. Contact photos
refresh when the contact changes.

## 11. Open questions

1. Does deleting a message through the Google Messages pairing delete it on the phone,
   and does Google expose "delete for everyone" for RCS to paired devices?
2. Do voice notes sent through the pairing arrive for the recipient as a voice message
   bubble or as a generic audio attachment?
3. Which GIF provider ships by default. Tenor requires a key in the build; an
   open-source-friendly alternative would be preferable if one is reliable.
4. Should the reaction particle layer be capped on low-end devices automatically
   (based on frame timing) rather than only by the user's intensity setting?
5. Whether Google Voice, Messenger, and Google Meet expose intents that start a call
   immediately, or only open the app to the person. Needs testing on a device.
6. Whether Instagram's hidden requests (the ones Meta filters out of the Requests
   tab) are reachable through the endpoints the connector uses, or only the visible
   ones.
7. Whether to ship a "theme gallery" of community-contributed JSON themes inside the
   app or only support import.
