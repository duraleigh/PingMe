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
| FAB menu | Inbox: New chat, New group, Scan QR |
| Horizontal floating toolbar | Chat screen: contextual actions during multi-select |
| Split button | Send button with a dropdown for Schedule and Send as SMS |
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
  chosen shape family. Unread count sits as a badge. Long-press to reorder or unpin.
  Users can choose a grid, a row, or "pinned at top of list" style. Up to 12 pins.
- **List items**: avatar, name, last message preview, network badge, time, unread badge,
  mute icon. Sender name shown in previews for groups.
- **Swipe actions**: left and right swipes are user-assignable from Pin, Archive, Mute,
  Mark read, Delete.
- **Filters**: connected button group. The set of networks shown is whatever is
  connected.
- **FAB menu**: expands into New chat, New group, and Scan QR (for pairing flows).
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
|  └─────────────────────────┘  ✓✓ Read        |
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

- **Bubbles**: Material 3 tonal surfaces by default. Outgoing uses primary container,
  incoming uses surface container high. Consecutive messages from the same sender are
  grouped with tighter spacing and only the last bubble carries the tail.
- **Status**: sending, sent, delivered, read, failed. Failed messages show a retry
  button and a "send as SMS" option where applicable.
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
- Pin chat, archive, block, delete chat.

### 3.5 Appearance studio (Settings > Appearance)

A live preview of a fake conversation sits at the top of the screen and updates
instantly as the user changes anything below. Sections are described in section 4.

### 3.6 Setup and pairing

Covered in DESIGN.md section 5.4. Visually: full-screen steps, one decision per
screen, a shape-morphing loading indicator while waiting for Google Messages to confirm,
and the emoji-match step shows the emoji at display size.

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
- Outgoing and incoming bubble colours (per chat).
- Per-network accent colour used in badges and the header.
- Sender name colours in groups: auto-assigned or fixed.

### 4.2 Shape

- Shape family: Round, Soft, Sharp, or Expressive (cookie, clover, sunny, and other
  Material 3 Expressive shapes for avatars and pins).
- Bubble corner radius slider, bubble tails on or off.
- Bubble style: Tonal (default), Outlined, Filled, Gradient, Pill.

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
- **Hold to record**, slide left to cancel, slide up to lock hands-free. A waveform
  and timer show while recording. Release to send, or in locked mode tap send or
  delete.
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

### 5.7 Attachments

The "+" button opens a bottom sheet: Camera, Gallery, File, Location, Contact. Images
support multi-select with captions. Everything reports send progress on the bubble.

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

### 6.3 Avoiding double notifications

In Google Messages mode, PingMe offers during setup to open Google Messages'
notification settings so the user can silence them, since both apps will otherwise
notify.

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
visible in the inbox and not muted. Hidden folders, muted chats, and archived chats
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

## 7. Accessibility

- Every custom bubble style and colour choice is checked against a 4.5:1 contrast
  minimum in the Appearance studio, with a warning when a combination fails.
- Full TalkBack labelling, including reactions ("2 heart reactions, from Sam and Dad")
  and voice note state.
- Font size honours the system scale in addition to the in-app slider.
- Reduce-motion honoured system-wide as described in 4.5.
- Touch targets at least 48 dp, including reaction chips.

---

## 8. Capability matrix

What the UI can offer per network. Connectors report these at runtime; this table is
the expected result.

| Feature | RCS via Google Messages | SMS/MMS native | WhatsApp | Telegram | Signal | Instagram |
|---|---|---|---|---|---|---|
| Reply to message | Yes | Quoted text | Yes | Yes | Yes | Yes |
| Delete for me | Yes | Yes | Yes | Yes | Yes | Yes |
| Delete for everyone | Open question | No | Yes, time limited | Yes | Yes, time limited | Yes, unsend |
| Reactions, any emoji | Yes | Text fallback | Yes | Limited set | Yes | Yes |
| GIF | Yes | MMS, size limited | Yes | Yes | Yes | Yes |
| Voice note | Yes, presentation open question | MMS audio | Yes | Yes | Yes | Yes |
| Typing indicator | Yes | No | Yes | Yes | Yes | Yes |
| Read receipts | Yes | No | Yes | Yes | Yes | Yes |
| Edit message | No | No | Yes, time limited | Yes | No | Yes, time limited |
| Native pins | No | No | Yes | Yes | No | No |
| Inbox folders | No | No | No | Folders (Telegram) | No | Primary, General, Requests |

---

## 9. Open questions

1. Does deleting a message through the Google Messages pairing delete it on the phone,
   and does Google expose "delete for everyone" for RCS to paired devices?
2. Do voice notes sent through the pairing arrive for the recipient as a voice message
   bubble or as a generic audio attachment?
3. Which GIF provider ships by default. Tenor requires a key in the build; an
   open-source-friendly alternative would be preferable if one is reliable.
4. Should the reaction particle layer be capped on low-end devices automatically
   (based on frame timing) rather than only by the user's intensity setting?
5. Whether Instagram's hidden requests (the ones Meta filters out of the Requests
   tab) are reachable through the endpoints the connector uses, or only the visible
   ones.
6. Whether to ship a "theme gallery" of community-contributed JSON themes inside the
   app or only support import.
