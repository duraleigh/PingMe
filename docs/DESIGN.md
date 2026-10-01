# PingMe Design Document

Status: draft v0.1
License decision: AGPL-3.0 (see section 9)
Platform: Android only (see section 3 for why)

---

## 1. What PingMe is

PingMe is an open-source Android app that puts all of a person's conversations in one
inbox. The first and most important network is Google Messages, which gives PingMe
RCS, SMS, and MMS. Other networks (WhatsApp, Telegram, Signal, and later Messenger and
Instagram) plug in through the same connector design.

PingMe does not run any servers. Everything happens on the phone. Your messages never
pass through infrastructure that PingMe controls.

### Goals

- One inbox for every network the user connects, with a consistent look and feel.
- RCS that works, through the Google Messages app already on the phone.
- Reconnection that is automatic wherever Google allows it, and painless where it is not.
  This is the main thing Beeper gets wrong today and the main thing PingMe must get right.
- No PingMe servers, no accounts with PingMe, no telemetry by default.
- Open source under AGPL-3.0 so the community can audit it and contribute connectors.

### Non-goals (for now)

- iOS. Android is required for the Google Messages pairing to make sense.
- Replacing Google Messages as the phone's RCS client. Not technically possible
  (see section 4).
- Discord. Discord bans user-token clients outright and enforces it. Left out until
  that changes.
- iMessage. No workable path from Android.
- Snapchat. No open-source library exists, Snap has locked accounts for third-party
  client use since 2014 and enforces it, the app uses device attestation that blocks
  paired-device or cookie approaches, and disappearing messages conflict with a
  stored unified inbox. Revisit only if a maintained library appears.
- A PingMe cloud service, web app, or desktop app.

---

## 2. Glossary

Plain-language definitions for terms used in this document.

- **RCS**: The modern replacement for SMS. Typing indicators, read receipts, high-quality
  media, group chats. On most Android phones it only works inside Google Messages.
- **Google Messages**: Google's texting app. It is the only app on a normal Android phone
  that can send and receive RCS.
- **Messages for web**: Google's feature that lets a browser show and send your texts by
  pairing with the Google Messages app on your phone. PingMe uses the same pairing
  mechanism, so Google Messages sees PingMe as a paired device.
- **Connector**: A module inside PingMe that knows how to talk to one network. There is
  one connector per network.
- **Protocol library**: Existing open-source code that already knows how to speak a
  network's private language. PingMe wraps these instead of rewriting them.
- **Default SMS app**: Android lets exactly one app be in charge of SMS. That app receives
  incoming texts and is the one that can send them normally.
- **gomobile**: A tool that packages Go code so an Android app written in Kotlin can call
  it. Several of the best protocol libraries are written in Go.
- **Foreground service**: An Android mechanism that lets an app keep running in the
  background with a visible notification. Needed to keep connections alive without a
  server doing push notifications.

---

## 3. Platform and constraints that shape everything

### 3.1 Android is mandatory

RCS on a normal phone lives inside Google Messages, and Google Messages runs only on
Android. PingMe pairs with that app on the same device. There is nothing for an iOS
version to pair with.

### 3.2 Google Messages must stay the default SMS app

Google Messages turns RCS off unless it is the phone's default SMS app. This is a hard
rule Google enforces inside the app. Consequences:

- PingMe must **not** take the default SMS role when the user wants RCS.
- SMS and MMS therefore also flow through the pairing, exactly like RCS. Google's
  companion protocol carries all three.
- PingMe needs **no SMS permissions at all** in this mode, which keeps it clear of Google
  Play's restrictive SMS permission policy.

For users who do not want RCS, PingMe offers a second mode where it *is* the default SMS
app and handles SMS and MMS natively through Android's telephony APIs. The two modes are
mutually exclusive per phone. See section 5.2.

### 3.3 No servers means persistent connections

Real messaging apps get instant notifications through a push service that their servers
feed. PingMe has no servers, so it keeps its own connections alive from the phone. That
means a foreground service and a permanent notification while connected. This is the
same trade-off Beeper's on-device mode makes. Section 6.4 covers how to keep this
battery-friendly.

---

## 4. Alternatives considered for RCS

| Approach | Verdict | Why |
|---|---|---|
| Pair with Google Messages as a companion device (what Messages for web does) | **Chosen** | Proven by Beeper since 2023. Open-source protocol library exists. Google Messages does all the RCS work. |
| Implement RCS directly, replacing Google Messages | Rejected | The RCS stack lives in Google's Carrier Services package, tied to SIM provisioning, device attestation, and Google-signed interfaces. Not reproducible, and Google actively defends it. |
| Use the hidden "Android Messages API" inside Google Messages | Rejected | Gated by a package allowlist that only Google-approved apps are on. Would require signature spoofing. |
| Read RCS notifications and reply through notification actions | Kept as a last-resort fallback idea | Works without pairing, but no history, no attachments, no new conversations. Not a real client. Might be worth a "degraded mode" later. |
| Wait for a public Android RCS API | Not available | Promised on and off since 2019. Nothing shipped as of this writing. Revisit if it ever lands. |

---

## 5. Product design

### 5.1 The unified inbox

Screen-by-screen UI design, customisation, and per-feature behaviour live in
[UI_DESIGN.md](UI_DESIGN.md).

- One list of conversations across all connected networks, sorted by latest activity.
- Each conversation shows a small network badge (RCS, SMS, WhatsApp, ...).
- Filters: all, unread, per network.
- A conversation view that looks the same regardless of network, with per-network
  features (reactions, replies, read receipts, typing) shown when the network supports
  them and hidden when it does not.
- Later: link the same person across networks so their chats sit together.

### 5.2 Texting modes

The user picks one during setup and can switch later.

**Google Messages mode (default, recommended).**
Google Messages stays the default SMS app and keeps RCS on. PingMe pairs with it and
receives RCS, SMS, and MMS through the pairing. PingMe asks for no SMS permissions.

**Native SMS mode.**
PingMe becomes the default SMS app. SMS and MMS work natively and never disconnect.
RCS is unavailable because Google Messages turns it off when it is not the default.
This is for people who do not use RCS or who value never re-pairing over RCS.

### 5.3 Connection health, the core UX problem

Beeper's biggest complaint is silent disconnects followed by a long re-login. PingMe's
answer has three layers.

1. **Never lose the pairing by accident.** Pairing keys are stored in encrypted storage
   backed by the Android Keystore. App updates, restarts, and crashes never require
   re-pairing.
2. **Reconnect automatically whenever possible.** A connection supervisor watches every
   connector. Network blips, phone sleep, and Google Messages restarts are handled with
   automatic retry and backoff. The user is never asked to do anything for these.
3. **Make forced re-pairing a two-tap affair.** When Google itself revokes the pairing
   (this happens and cannot be prevented), PingMe:
   - detects it immediately rather than after a timeout,
   - shows one persistent notification saying RCS is paused and why,
   - opens straight into the pairing flow, and launches Google Messages to the right
     screen where Android allows it,
   - keeps SMS working in the meantime if the user is in native SMS mode.

Every connector reports one of these states, and the UI shows it honestly:

- Connected
- Reconnecting (automatic, no action needed)
- Action needed (user must re-pair or re-login)
- Disabled by user

### 5.4 Setup flow for Google Messages

1. Confirm Google Messages is installed, is the default SMS app, and has RCS turned on.
   Show fix-it buttons for each if not.
2. Ask for battery optimization exemption, explaining why.
3. Start pairing with **Google account pairing**: the user signs in to Google, then
   confirms a matching emoji inside Google Messages. PingMe walks them through each step
   with screenshots. QR pairing is not offered: Google is retiring it in favour of account
   pairing, and scanning a QR shown on the same phone is impossible anyway (owner's
   decision, 2026-10-01).
4. Initial sync of conversation history.

---

## 6. Technical architecture

### 6.1 High-level shape

```
+-----------------------------------------------------------+
|  Android app (Kotlin, Jetpack Compose)                    |
|                                                           |
|  UI  <->  ViewModels  <->  Repository / unified store     |
|                               |                           |
|                        Connector manager                  |
|                               |                           |
|        +----------+-----------+-----------+-----------+   |
|        |          |           |           |           |   |
|   Google Msgs  Native SMS  WhatsApp   Telegram    Signal  |
|   connector    connector   connector  connector   conn.   |
|        |          |           |           |           |   |
+--------|----------|-----------|-----------|-----------|---+
         |          |           |           |           |
      libgm      Android      whatsmeow   TDLib     signalmeow
      (Go)      telephony      (Go)     (C++/Java)    (Go)
```

Kotlin owns the app: lifecycle, storage, notifications, UI. Protocol libraries are
called through thin wrappers. Go libraries are compiled into one Android library with
gomobile.

### 6.2 The connector interface

Every connector implements the same small contract. This is the most important design
decision in the codebase, because it is what makes adding a network cheap.

A connector must be able to:

- **Describe itself**: name, icon, which features it supports (reactions, replies,
  typing, read receipts, edits, attachments, groups).
- **Log in**: expose a login flow as a series of steps (show QR, ask for a code, ask for
  a password, wait for confirmation in another app). The UI renders whatever steps the
  connector asks for, so no connector needs its own screens.
- **Connect and disconnect**, and report its connection state.
- **Sync**: fetch conversation list and message history on demand.
- **Emit events**: new message, edit, delete, reaction, read receipt, typing, chat
  metadata change, connection state change.
- **Act**: send text, send attachment, react, mark read, send typing, create group,
  leave group.

Connectors translate between their network's own concepts and PingMe's unified model
(section 6.3). Nothing outside a connector ever sees network-specific data.

### 6.3 Unified data model

Stored locally in SQLite via Room.

- **Account**: one connected login on one network. Holds connection state and a pointer to
  encrypted credentials. A network can have several accounts at once, for example a
  personal Messenger login and a Facebook Page inbox, or two WhatsApp numbers. Each
  account has its own display name, colour, notification defaults, and "show in
  inbox" switch, and everything downstream (chats, unread counts, filters) is keyed
  by account, never just by network.
- **Chat**: a conversation. Belongs to one account. Has type (direct or group), title,
  participants, unread count, last activity.
- **Message**: belongs to one chat. Has sender, timestamp, body, attachments, reply
  target, edit history, delivery and read status.
- **Participant**: a person as seen by one network. Later, a **Contact** layer links
  participants across networks to one person.
- **Attachment**: metadata plus a reference to a file in app-private storage. Media is
  downloaded lazily.
- **Reaction**: emoji, sender, target message.

Every record keeps the network's own ID alongside PingMe's ID so syncs can reconcile.

### 6.4 Background execution and battery

- One foreground service, "PingMe is connected", hosts all live connections.
- Connectors sleep their sockets when the phone is idle and rely on the protocol's own
  keepalive rather than polling.
- A single reconnection supervisor with exponential backoff, capped, and reset on network
  change.
- Request battery optimization exemption during setup, with a clear explanation. Without
  it Android will kill the service and the user will see delayed messages.
- Sync work that can wait (history backfill, media download) goes through WorkManager,
  not the service.

### 6.5 Security and privacy

- Credentials and pairing keys are encrypted at rest with keys held in the Android
  Keystore.
- End-to-end encryption is preserved where the network provides it. For WhatsApp and
  Signal, the protocol library holds the encryption sessions on-device. For RCS, Google
  Messages on the phone does the decryption before relaying, exactly as it does for the
  web client.
- No analytics, no crash reporting, no network calls except to the networks the user
  connected. If opt-in crash reporting is ever added it is off by default.
- The message database is in app-private storage. Full database encryption is a
  later option, not a v1 requirement.

### 6.6 Notifications

Each new message raises a standard Android notification with reply and mark-read
actions, grouped per chat, using the network badge. In Google Messages mode PingMe
should offer to silence Google Messages' own notifications so the user does not get
two.

### 6.7 Tech stack

- Kotlin, Jetpack Compose, Coroutines and Flow
- Room for storage, DataStore for preferences
- Hilt for dependency injection
- WorkManager for deferred work
- Go 1.22+ with gomobile for the Go-based protocol libraries, produced as one AAR
- Gradle multi-module build
- Minimum Android 10 (API 29). This is where the modern default-app role APIs appear, and
  it comfortably covers phones that run current Google Messages.

### 6.8 Repository layout

```
PingMe/
  docs/                 design docs, connector guides
  app/                  Android application module (UI, DI wiring)
  core/
    model/              unified data model
    store/              Room database and repositories
    connector-api/      the connector interface and shared login-step types
    service/            foreground service, connection supervisor, notifications
  connectors/
    gmessages/          Google Messages connector (Kotlin side)
    sms/                native SMS/MMS connector
    whatsapp/
    telegram/
    signal/
  gobridge/             Go module wrapping libgm, whatsmeow, signalmeow for gomobile
```

---

## 7. Networks and their libraries

All libraries below are open source. Because PingMe is AGPL-3.0, none of these licenses
is a problem.

| Network | Library | Language | License | Auth style | Owner's stance |
|---|---|---|---|---|---|
| Google Messages (RCS/SMS/MMS) | libgm (from mautrix-gmessages) | Go | AGPL-3.0 | Google account pairing | Unofficial but tolerated for years |
| SMS/MMS native | Android telephony APIs | Kotlin | n/a | Default SMS role | Official |
| WhatsApp | whatsmeow | Go | MPL-2.0 | Linked device (QR) | Against terms, bans are rare but possible |
| Telegram | TDLib | C++ with Java binding | Boost | Phone number + code | Official, encouraged |
| Signal | signalmeow (from mautrix-signal) | Go | AGPL-3.0 | Linked device (QR) | Unofficial, tolerated |
| Messenger | messagix (from mautrix-meta) | Go | AGPL-3.0 | Browser cookies | Against terms, ban risk |
| Facebook Page inbox | Meta Messenger Platform (Graph API), polled from the phone | Kotlin | n/a | Page access token from a Meta developer app the user creates | Official, no ban risk; 24-hour reply window applies |
| Instagram | mautrix-instagram (Go, split out of mautrix-meta in August 2026) | Go | AGPL-3.0 | Browser cookies | Against terms, ban risk |
| Google Voice | mautrix-gvoice | Go | AGPL-3.0 | Google sign-in in an in-app browser, cookies kept on device | Unofficial, tolerated; Beeper ships it |
| Slack | Official Web API | Kotlin | n/a | OAuth | Official |

PingMe's setup screen will state the risk level for each network in plain language
before the user connects it.

---

## 8. Roadmap

### Milestone 0: foundation
- Repo, build, CI, license file, contributor guide.
- Connector interface, unified model, Room store.
- App shell with an empty inbox and a settings screen.

### Milestone 1: Google Messages, the reason PingMe exists
- Go bridge module with libgm compiled via gomobile.
- Google Messages connector: pairing (both methods), history sync, send and receive
  text and media, reactions, read receipts, typing.
- Connection supervisor and the health states in section 5.3.
- Notifications with reply.
- This milestone alone is a usable product for RCS users.

### Milestone 2: native SMS mode
- Default SMS role handling, SMS and MMS send and receive, threading.
- Mode switcher with clear explanation of the RCS trade-off.

### Milestone 3: more networks
- Telegram first, since it is officially supported and low risk.
- WhatsApp next, highest demand.
- Signal after that.
- Google Voice. Same pairing-free model as the others: the user signs in to Google in
  an in-app browser and PingMe keeps the session cookies in encrypted storage. Google
  expires those sessions on its own schedule, so this connector leans on the
  reconnection supervisor and the "Action needed" state just like RCS. Constraints as
  of the library today: text and media messages only, no calls and no voicemail, and
  new conversations must be started in the Google Voice app. Calls hand off to the
  Google Voice app with a deep link. US only, since that is where Google Voice exists.

### Milestone 4: quality of life
- Cross-network contact linking and merged chats (UI_DESIGN.md section 10.15).
- Send later, search in chat, low priority, notification keywords, obscured chats,
  clean links, link previews, keep-all-media, spaces, and the call buttons
  (UI_DESIGN.md section 10).
- Search across all networks.
- Backup and restore of the local database.
- Instagram, gated behind an explicit risk warning, with inbox folder support
  (Primary, General, Requests) and per-folder notification defaults. The bridge
  library does not expose folders today, so the PingMe connector reads them from
  the inbox and pending-inbox endpoints itself. See UI_DESIGN.md section 6.4.
- Messenger (personal account), same gating.
- Facebook Page inbox through Meta's official Messenger Platform. The user creates a
  Meta developer app, connects their own Page, and pastes the Page access token into
  PingMe. PingMe has no server, so instead of webhooks it polls the Page's
  conversations endpoint on a user-chosen interval. Constraints: replies to a person
  are only allowed within 24 hours of their last message unless a Meta message tag
  applies, and polling means seconds of delay rather than instant delivery. This is
  the only Meta connector with no account risk, so it is the recommended path for
  Pages.

---

## 9. License

PingMe is licensed under AGPL-3.0.

Reason: the Google Messages, Signal, and Meta protocol libraries are AGPL-3.0, and using
them requires the whole app to be AGPL-3.0. Since PingMe is open source by decision,
this costs nothing and lets PingMe use the best available libraries directly instead
of rewriting them.

What this means in practice:

- Anyone can use, study, modify, and redistribute PingMe.
- Anyone who distributes a modified PingMe must publish their modifications under the
  same license.
- Contributors keep their own copyright and license their contributions under AGPL-3.0.

---

## 10. Distribution

- GitHub releases (signed APKs) from day one.
- F-Droid once the build is reproducible, since F-Droid users are the natural audience.
- Google Play later. In Google Messages mode PingMe needs no SMS permissions, which
  avoids Play's hardest review. Native SMS mode requires being a default SMS handler,
  which Play allows.

---

## 11. Open questions

1. **Google sign-in inside PingMe.** Account pairing signs in to Google in an in-app web
   page. Google sometimes refuses sign-in from embedded web pages. With QR pairing dropped
   (decision log, 2026-10-01) there is no other way to pair, so this must be made to work;
   find out in P3.2 and raise it with the owner at once if Google blocks it.
2. **How aggressively Google revokes pairings.** Need data on how long account pairings
   survive so PingMe can set expectations honestly in the UI.
3. **gomobile and app size.** One Go AAR with three libraries could add 20 to 40 MB.
   Acceptable for v1, but worth measuring early.
4. **Should Go own more?** If the Kotlin-Go boundary becomes painful, the unified store
   and sync logic could move into Go, with Kotlin doing only UI. Decide after milestone 1.
5. **Instagram folders in the library.** The Go Instagram bridge does not surface
   Primary, General, or Requests. Confirm which endpoint carries the folder for each
   thread and whether Instagram's hidden requests (filtered by Meta) are reachable at
   all, then decide whether to contribute folder support upstream or keep it in the
   PingMe connector.
6. **Google Voice gaps.** The bridge library cannot start a new conversation or push
   notifications, both of which the Google Voice web client can do. Decide whether to
   implement those in the PingMe connector against the same web endpoints or
   contribute them upstream. Voicemail transcripts would be a natural addition too.
7. **Signal history and unlinking.** Signal unlinks a device after about a month of
   inactivity, and a fresh link normally starts with no history. Confirm whether the
   library exposes Signal's newer history transfer at link time, and make sure the
   supervisor surfaces an unlink as "Action needed" rather than a silent gap.
8. **Page inbox polling.** Pick a default polling interval for the Page connector that
   balances delay against battery and Meta's rate limits, and decide whether to offer
   a faster interval while the app is in the foreground.
9. **Contact linking heuristics.** Phone numbers link RCS, WhatsApp, Signal, and Telegram
   naturally. Meta accounts do not carry numbers. Defer.

---

## 12. Decisions log

| Date | Decision | Reason |
|---|---|---|
| 2026-09-30 | Open source under AGPL-3.0 | Enables direct use of AGPL protocol libraries |
| 2026-09-30 | Android only | RCS pairing requires Google Messages on the same device |
| 2026-09-30 | Google Messages stays default SMS app in RCS mode | Google Messages disables RCS otherwise |
| 2026-09-30 | No PingMe servers | Privacy, cost, and matching the on-device model |
| 2026-09-30 | Kotlin app, Go protocol libraries via gomobile | Best libraries are in Go; Android platform work belongs in Kotlin |
| 2026-09-30 | Discord and iMessage out of scope | Discord bans it; iMessage has no Android path |
| 2026-10-01 | Google Messages pairs by Google account only, no QR | Google is retiring QR pairing; a QR cannot be scanned on the same phone. Owner accepted the risk that pairing depends on Google sign-in working inside PingMe |
