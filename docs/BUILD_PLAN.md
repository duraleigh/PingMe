# PingMe Build Plan

This is the execution plan for building PingMe from the empty repository to an APK
installed on the owner's phone. It is written for a Claude Code session (any model)
acting as the builder. Read `CLAUDE.md` at the repository root first; it holds the
rules for working from this plan. Then read `docs/DESIGN.md` and `docs/UI_DESIGN.md`
in full. This plan tells you the order and the steps; those two documents tell you
what to build and how it must behave. Where this plan and those documents disagree,
those documents win and this plan has a bug; fix the plan in the same commit.

Terms: "owner" is the person the app is for. "builder" is the Claude session
executing this plan. "gate" is a point where the builder must stop, produce an APK,
and wait for the owner to test on the phone.

---

## 0. How to work from this plan

1. Work phases in order. Within a phase, work steps in order. Do not start a phase
   until the previous phase's acceptance list is fully green.
2. One step is one commit, or a few commits, on the working branch. Commit message
   starts with the step id, for example `P2.4: inbox list with swipe actions`.
3. Every step ends with the checks in section 1.6 passing. Never commit red.
4. When a library's real interface differs from what this plan describes, read the
   library's source in the Go module cache or its repository, adapt, and record the
   difference in `docs/BUILD_LOG.md` under the step id. Do not stub the feature.
5. When a step needs the phone, stop at the gate. Push, let CI build the APK, and
   write the gate's test checklist into `docs/BUILD_LOG.md` for the owner. Resume
   only when the owner reports results. Work on later steps that do not depend on
   the gate while waiting, if any exist.
6. Never silently narrow a feature. If something in the design cannot be done as
   written, say so in `docs/BUILD_LOG.md` with the reason and the closest thing that
   can be done, and ask the owner before doing the narrower thing.
7. Keep `docs/BUILD_LOG.md` current: one entry per step with what was done, what
   deviated, and what is left. It is the hand-off document between sessions.
8. Versions in this plan are minimums. At the start of each session, check the
   latest stable versions of the Android Gradle Plugin, Kotlin, Compose BOM,
   Material 3, Room, Hilt, and Go, and use them unless a known incompatibility is
   recorded in the build log.

---

## 1. Phase 0: environment, scaffold, and CI

### P0.1 Toolchain

On the build machine (the Claude Code cloud container or the owner's computer):

- JDK 17 or 21. Cloud sessions come with OpenJDK 21 preinstalled; use it.
- Android command-line tools, then via `sdkmanager`: `platform-tools`,
  `platforms;android-35` (or the newest stable), `build-tools;35.0.0` (or newest),
  `ndk;27.x` (newest LTS).
- Go 1.24 or newer. `go version`.
- gomobile: `go install golang.org/x/mobile/cmd/gomobile@latest` then
  `gomobile init`.
- Accept SDK licences: `yes | sdkmanager --licenses`.

Record the exact versions installed in `docs/BUILD_LOG.md`.

**Cloud sessions.** The owner has a cloud environment named PingMe whose setup
script is `scripts/setup-cloud.sh` (already in the repo) and whose network access is
Full. Cloud machines come with OpenJDK 21, Gradle, and Go; the script adds the
Android SDK, NDK 27, and gomobile under `/opt/android-sdk` and `/opt/android-ndk`.
At the start of P0.1, run `check-tools`, `sdkmanager --list_installed`, and
`gomobile version` to confirm the script worked. If a piece is missing, install it by
hand for this session, fix the script, commit it, and tell the owner in the build log
to paste the new version into the environment settings.

If the environment is ever set to Trusted instead of Full, these are the hosts the
build needs and a refused one must be named in the build log:
`dl.google.com`, `maven.google.com`, `repo1.maven.org`, `repo.maven.apache.org`,
`plugins.gradle.org`, `services.gradle.org`, `proxy.golang.org`, `sum.golang.org`,
`storage.googleapis.com`, `github.com`, `objects.githubusercontent.com`,
`fonts.google.com` (for the one-time font download). If a download is refused, name
the host in the build log and ask the owner to allow it or broaden the network
setting; do not work around it.

### P0.2 Repository scaffold

Create the Gradle multi-module project matching `DESIGN.md` section 6.8:

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradle.properties
app/
core/model/  core/store/  core/connector-api/  core/service/  core/ui/
connectors/demo/  connectors/gmessages/  connectors/sms/  connectors/whatsapp/
connectors/telegram/  connectors/signal/  connectors/gvoice/  connectors/instagram/
connectors/messenger/  connectors/fbpage/
gobridge/   (Go module, built by gomobile into gobridge/build/gobridge.aar)
```

`core/ui` holds shared composables (bubbles, avatars, sheets, theme). `connectors/demo`
is a fake network used for every UI test and for development without accounts; it is
built in P1.5 and shipped in debug builds only.

Version catalog entries (minimums): AGP 8.7, Kotlin 2.1, KSP matching Kotlin,
Compose BOM 2025.09 or newer, `androidx.compose.material3:material3` 1.4.0 or newer
(the Expressive APIs), Room 2.7, Hilt 2.54, WorkManager 2.10, DataStore 1.1,
Coil 3 for images, kotlinx-serialization, kotlinx-coroutines, `androidx.core:core-splashscreen`,
`androidx.activity:activity-compose`, `androidx.navigation:navigation-compose` 2.8,
`androidx.window` for foldables, Media3 for audio playback, Accompanist is not used.

App module: `applicationId = "org.pingme.app"`, `minSdk = 29`, `targetSdk` = newest
stable, `compileSdk` same. Enable `buildFeatures.compose`, core library
desugaring, and `-Xopt-in` for `ExperimentalMaterial3ExpressiveApi` and
`ExperimentalMaterial3Api` project-wide.

License: add `LICENSE` with the AGPL-3.0 text, and an SPDX header
`// SPDX-License-Identifier: AGPL-3.0-or-later` in every source file.

### P0.3 Signing for sideloading

The app is only installed on the owner's phone, but updates must install over the
previous version, which requires the same signing key every time.

- Generate one keystore once: `keytool -genkeypair -v -keystore pingme-sideload.jks
  -alias pingme -keyalg RSA -keysize 4096 -validity 36500`. Do not commit it.
- Store it base64-encoded in the GitHub repository secret `SIDELOAD_KEYSTORE_B64`,
  with `SIDELOAD_KEYSTORE_PASSWORD`, `SIDELOAD_KEY_ALIAS`, `SIDELOAD_KEY_PASSWORD`.
  The owner creates these secrets; the builder writes the instructions into
  `docs/BUILD_LOG.md` at this step and stops until they exist.
- In `app/build.gradle.kts`, a `release` signing config reads those from environment
  variables when present and falls back to the debug keystore otherwise, so local
  builds always work.

### P0.4 CI

`.github/workflows/build.yml`:

- On every push and pull request: set up JDK 17, Android SDK, Go, gomobile; cache
  Gradle and Go modules; run `./gradlew lint testDebugUnitTest`; build the Go bridge
  (P3.1) when `gobridge/` changes; build `assembleRelease`; upload
  `app/build/outputs/apk/release/app-release.apk` as an artifact named
  `pingme-<short-sha>.apk`.
- On a tag `v*`: also create a GitHub release with the APK attached. This is what the
  owner installs.

### P0.5 Static checks

- `ktlint` via the Gradle plugin, `detekt` with the default rule set plus the
  Compose rules, Android Lint with `warningsAsErrors = true` for the `app` module.
- A `check` task alias: `./gradlew ktlintCheck detekt lint testDebugUnitTest`.

### P0.6 Acceptance for Phase 0

- `./gradlew check` passes on a fresh clone.
- CI is green and produces an APK artifact that installs on the owner's phone and
  shows a blank screen with the app name. **Gate G0**: the owner installs it. This
  proves signing, sideloading, and CI before any feature exists.

---

## 2. Phase 1: core model, store, connector API, demo connector

### P1.1 Model (`core/model`)

Plain Kotlin data classes, no Android imports, all serializable:

- `NetworkId` enum: `GMESSAGES, SMS, WHATSAPP, TELEGRAM, SIGNAL, GVOICE, INSTAGRAM, MESSENGER, FBPAGE, DEMO`.
- `AccountId(value: String)`, `ChatId(value: String)`, `MessageId(value: String)`,
  `PersonId(value: String)`, `ContactId(value: String)`, `SpaceId(value: String)`.
- `Account(id, network, displayName, colorArgb, state: ConnectionState, showInInbox, notificationMode: NotificationMode, credentialRef: String)`.
- `ConnectionState` sealed: `Connected`, `Reconnecting(attempt, nextAt)`, `ActionNeeded(reason: String, deepLink: String?)`, `Disabled`, `Polled(lastCheckedAt)`.
- `Chat(id, accountId, kind: Direct|Group, title, participants, unreadCount, lastActivityAt, isPinned, pinOrder, isMuted, muteUntil, isArchived, isLowPriority, isObscured, folder: ChatFolder?, spaceId: SpaceId?, mergedInto: ChatId?, avatarSource: AvatarSource, nameOverride: String?, defaultSendAccount: AccountId?, networkRemoteId: String)`.
- `ChatFolder` enum: `PRIMARY, GENERAL, REQUESTS` (Instagram), `TOPIC` (Telegram).
- `Message(id, chatId, senderId: PersonId, sentAt, receivedAt, body: String?, kind: Text|Voice|Gif|Image|Video|File|Location|Contact|Sticker|Deleted, attachments: List<Attachment>, replyTo: MessageId?, quote: Quote?, editedAt, deletedForEveryone, status: Sending|Sent|Delivered|Read|Failed(reason)|Scheduled(at), reactions: List<Reaction>, transport: Transport, networkRemoteId, linkPreview: LinkPreview?, isOutgoing)`.
- `Transport` enum: `RCS, SMS, MMS, NETWORK` (network's own transport).
- `Attachment(id, kind, mimeType, sizeBytes, localPath: String?, remoteRef: String?, durationMs, width, height, isEphemeral, savedAt)`.
- `Reaction(emoji, senderId, at)`, `Quote(senderName, text)`, `LinkPreview(url, cleanedUrl, title, description, imagePath, fetchedAt, source: Network|Local)`.
- `Person(id, accountId, displayName, phoneNumber: String?, networkHandle, avatarPath: String?, contactId: ContactId?)`.
- `Space(id, accountId, title, kind: WhatsAppCommunity|TelegramForum|Custom, chatIds)`.
- `Capabilities(reply, deleteForMe, deleteForEveryone: TimeLimit?, reactions: ReactionRule(Any|Set|TextFallback), gif, voiceNote, typing, readReceipts, edit: TimeLimit?, nativePins, folders, startConversation, multiAccount, calls: CallRule)`.
- `NotificationMode` enum: `NORMAL, SILENT, OFF`.
- `AvatarSource` sealed: `Contacts`, `Network(accountId)`, `Initials`.

### P1.2 Store (`core/store`)

Room database `pingme.db`, schema exported to `core/store/schemas/`, with entities
mirroring P1.1 plus:

- `MessageFts` (FTS5 over `body`, `senderName`, `attachmentNames`) kept in sync by
  triggers.
- `ScheduledSend(messageId, sendAt, accountId, chatId, payloadJson, attempts)`.
- `KeywordRule(id, pattern, wholeWord, caseSensitive, scope: AccountIds|ChatIds|All, channelId, overridesSilence)`.
- `MergeLink(personId, contactId, confirmedAt)`.
- `MediaSaveJob(attachmentId, state)`.

DAOs return `Flow`s. Repositories (`ChatRepository`, `MessageRepository`,
`AccountRepository`, `ContactRepository`, `SettingsRepository`) are the only thing
the UI and the service talk to. Preferences live in DataStore (`settings.pb` with a
proto schema or preferences DataStore; choose preferences DataStore for speed).

**Unread counting rule** is implemented once, in `ChatRepository.unreadTotals()`:
only chats where `isArchived == false && isLowPriority == false && isMuted == false
&& folder != REQUESTS && (folder != GENERAL || instagramShowGeneral) && account.showInInbox`.
Every badge in the app reads from this one query.

### P1.3 Connector API (`core/connector-api`)

```kotlin
interface Connector {
  val network: NetworkId
  val capabilities: Capabilities
  fun loginFlow(): LoginFlow                 // steps: ShowQr, EnterText, OpenWebView(url, cookieDomains), WaitForConfirmation(hint), Done(credentialRef)
  suspend fun connect(account: Account, creds: Credentials): Flow<ConnectorEvent>
  suspend fun disconnect(accountId: AccountId)
  suspend fun syncChats(accountId): List<ChatSnapshot>
  suspend fun syncMessages(chatId, before: MessageId?, limit: Int): List<MessageSnapshot>
  suspend fun send(chatId, draft: OutgoingMessage): SendResult
  suspend fun react(messageId, emoji: String?, remove: Boolean)
  suspend fun markRead(chatId, upTo: MessageId)
  suspend fun setTyping(chatId, typing: Boolean)
  suspend fun delete(messageId, forEveryone: Boolean)
  suspend fun downloadAttachment(attachment): File
  suspend fun startConversation(personHandle: String): ChatId   // throws Unsupported when capabilities.startConversation == false
  suspend fun moveFolder(chatId, folder: ChatFolder)            // Instagram only
  suspend fun respondToRequest(chatId, accept: Boolean)         // Instagram only
}
sealed interface ConnectorEvent { NewMessage; MessageUpdated; ReactionChanged; ReadReceipt; Typing; ChatUpdated; ChatRemoved; State(ConnectionState); HistoryBatch }
```

Plus `ConnectorRegistry` (maps `NetworkId` to a `Connector` provider via Hilt
multibindings) and `LoginFlow` as a `Flow<LoginStep>` with a `respond(stepId, value)`
channel so the UI renders any connector's login without knowing it.

Contract tests: `ConnectorContractTest` is an abstract JUnit class every connector's
test module extends. It exercises login, sync, send, react, delete, and the event
stream against that connector's fake transport.

### P1.4 Service skeleton (`core/service`)

- `ConnectionService`: a foreground service with one persistent notification,
  hosting a `ConnectorSupervisor`.
- `ConnectorSupervisor`: per account, a coroutine that calls `connect`, consumes
  events into the store, and on failure retries with exponential backoff (1s, 2s,
  4s ... capped at 5 min), reset on network-change broadcasts. Distinguishes
  transient failures (retry) from `ActionNeeded` (stop, surface).
- `NotificationRouter`: consumes new-message events and decides channel and sound.
  Fully implemented in P4; skeleton here.
- `WorkManager` jobs: `HistoryBackfillWorker`, `MediaDownloadWorker`,
  `ScheduledSendWorker`, `LinkPreviewWorker`, `ContactSyncWorker`.

### P1.5 Demo connector (`connectors/demo`)

A fake network with scripted people ("Sam Ortiz", "Design team", "Mom", ...), that:
emits typing events, incoming messages on a timer, reactions to your messages,
supports every capability flag configurable at runtime, and has a login flow that
exercises every `LoginStep` kind. It is the basis of all UI tests and of every
screenshot. Debug builds only.

### P1.6 Acceptance for Phase 1

- Unit tests for the unread rule, merge-link logic, and every DAO.
- Contract test passes against the demo connector.
- Schema exported and a migration test harness exists (empty migrations list).

---

## 3. Phase 2: the UI, against the demo connector

Build every screen in `UI_DESIGN.md` using `MaterialExpressiveTheme` and the
Expressive components. Every screen gets a Compose preview using demo data and a
Compose UI test for its main interactions.

### P2.1 Theme (`core/ui/theme`)

- `PingMeTheme` wraps `MaterialExpressiveTheme(colorScheme, motionScheme, shapes, typography)`.
- Colour source: `Dynamic` (from `dynamicLightColorScheme`/`dynamicDarkColorScheme`),
  `Seed(argb)` (generate a tonal scheme; use the `material-color-utilities` port or
  compute with Compose's `ColorScheme` builders), `Preset(name)`, `Manual(scheme)`.
- Modes: Light, Dark, FollowSystem, plus AMOLED (dark with pure black surfaces) and
  contrast Standard/Medium/High.
- Motion: `MotionScheme.expressive()` unless intensity is Off, then `standard()`.
- Shapes: shape family Round/Soft/Sharp/Expressive maps to a `Shapes` object plus a
  `MaterialShapes` polygon for avatars and pinned tiles.
- Typography: bundled variable fonts (Roboto Flex default, Inter, Manrope, Nunito,
  Lexend, Atkinson Hyperlegible, JetBrains Mono, all under `res/font/` with OFL
  licences in `licenses/`), plus user-imported fonts loaded from app storage with
  `Font(File)`; separate UI and message families; emphasized styles use the weight
  axis when the font has one.
- Network palette per `UI_DESIGN.md` 10.1, in `NetworkColors`, with the SMS
  fallback variant computed from the accent by reducing chroma.

### P2.2 Appearance studio

Settings > Appearance with the live preview conversation at the top and every
control from `UI_DESIGN.md` section 4, including theme export/import as JSON and the
4.5:1 contrast warning.

### P2.3 Inbox

Everything in `UI_DESIGN.md` 3.1 as finalised in this conversation:

- Compact top bar: small wordmark, status pill (cycles states in the demo), search,
  avatar. Avatar opens the account menu (Settings, Appearance, Notifications,
  Accounts, Archived, Low priority, Requests, General, spaces).
- Pinned grid: five per row, wrapping, shaped avatars, unread dot, typing dots
  overlay. Pinned chats are excluded from the list.
- Rows with typing overlay, mute icon, network badge, folder tag, unread dot.
- Swipe left and right, each assignable (Pin, Archive, Mute, Mark read/unread, Low
  priority, Delete, Off) with the reveal layer and haptics. Use
  `AnchoredDraggable`.
- Press and hold on a row or tile: the action sheet (Pin, Mark read, Mute, Archive,
  Low priority, Obscure, Delete). Use `combinedClickable(onLongClick)`.
- Bottom bar: user-configurable up to five items from networks, spaces, Low
  priority; long-press a network item to narrow to one account or folder.
- FAB menu (`FloatingActionButtonMenu`): New chat, New group. (Scan QR removed by the
  owner, 2026-09-30.)
- Connection health chip only when any account is not Connected.
- Flippy reactions: row flips to show an incoming reaction for 900 ms.
- Adaptive: list-detail on wide screens via `ListDetailPaneScaffold`.

### P2.4 Chat screen

`UI_DESIGN.md` 3.2, 3.3, 5.x, 10.x:

- Header inset by the status bar only; avatar; name (tap opens Chat details);
  network badge and live status; phone, video, and overflow icons.
- Message list with bubbles coloured by transport and network, grouping, tails, day
  separators, new-messages divider, scroll-to-bottom pill, pinned message banner.
- Long-press: reaction bar (quick set + "+" full picker) and action sheet (Reply,
  Voice reply, Copy, Forward, Pin, Select, Info, Edit when allowed, Delete).
- Double-tap sends the configured reaction.
- Reaction Burst per 5.4 with intensity levels, built from `Animatable`s and a
  `Canvas` particle layer; honours `LocalReduceMotion` / system animator scale.
- Reply strip; swipe-right on a bubble to reply.
- Composer: attach, network chip (merged chats), GIF, text field, mic/send split
  button with Send later (Send as SMS was dropped: owner, 2026-10-01).
- Voice notes: hold to record, slide to cancel, slide up to lock; waveform;
  playback with speed and proximity earpiece switch (`AudioManager`).
- GIF picker sheet with pluggable provider; Tenor provider reads its key from
  `local.properties` / CI secret `TENOR_API_KEY`; keyboard GIF insertion via
  `OnReceiveContentListener`.
- Attachment sheet: Camera (`ActivityResultContracts.TakePicture`), Gallery
  (`PickMultipleVisualMedia`), File (`OpenDocument`), Location, Contact.
- Delete sheet with capability-driven Delete for everyone; Undo snackbar.
- Search in chat with type chips, FTS-backed, media grid.
- Link previews and cleaned links (10.11, 10.12) rendered in bubbles.
- Obscured mode: `Modifier.blur` on bubbles with tap-to-reveal timer, hidden
  previews, `FLAG_SECURE` on the window while an obscured chat is open.
- Send later sheet (10.13): chips, date-time pickers, natural-language field parsed
  by a small hand-written parser (`in N minutes/hours`, weekday + time, `tomorrow`,
  `tonight`, `morning/afternoon/evening`); scheduled bubbles editable/cancellable.

### P2.5 Chat details

Per 3.4 and 10.x: media/links/files tabs, notifications (mute, sound via
`RingtoneManager` picker or file, vibration), appearance overrides, reaction set
override, members, pinned messages, avatar source picker, name override, merge and
split, move folder (Instagram), info button that opens the contact card
(`ContactsContract.Contacts.CONTENT_LOOKUP_URI` with `ACTION_VIEW`), block, delete.

### P2.6 Settings

Accounts (per-account name, colour, notifications, show in inbox, connection state,
login/re-login), Notifications (per network, per folder for Instagram, keywords,
auto-copy OTP), Privacy (read receipts, typing, clean links, link previews,
obscure), Reactions (quick set, double-tap), Motion (intensity, flippy, haptics),
Storage (save all media, folder), Spaces, Backup.

### P2.7 Setup flow

Permissions with plain explanations, battery optimisation exemption request, contacts
permission for avatars, then the first connector's login flow rendered from
`LoginStep`s. (The Google Messages versus native SMS choice that opened setup was
removed at Gate G3, 2026-10-02: Google Messages is the one way texts reach PingMe.)

### P2.8 Acceptance for Phase 2

- Every screen has a preview and a UI test against the demo connector.
- The full app is usable end to end with the demo connector on a phone.
- **Gate G1**: the owner installs the demo-only build and reviews every screen
  against `UI_DESIGN.md`. Collect feedback into the build log before Phase 3.

---

## 4. Phase 3: Go bridge and the Google Messages connector

### P3.1 Go bridge (`gobridge/`)

- `go.mod` module `pingme.org/gobridge`, requiring `go.mau.fi/mautrix-gmessages/libgm`
  (module path as published; verify), `go.mau.fi/whatsmeow`, and the Signal library
  from `go.mau.fi/mautrix-signal` (`pkg/signalmeow`).
- One exported Go package per network with a small, gomobile-friendly surface:
  functions taking and returning strings (JSON) and byte slices, plus a callback
  interface `EventSink { OnEvent(json string) }`. gomobile cannot export generics,
  channels, or most complex types; keep the boundary flat.
- `gobridge/build.sh`: `gomobile bind -target android -androidapi 29 -o build/gobridge.aar ./gm ./wa ./sig`.
- Gradle: `app` depends on `files("../gobridge/build/gobridge.aar")`; CI builds it
  with caching keyed on `gobridge/go.sum`.
- Kotlin wrapper `GoBridge` translates JSON events into `ConnectorEvent`s.

### P3.2 Google Messages connector (`connectors/gmessages`)

- Login flow: Google account pairing only (owner's decision, 2026-10-01; no QR
  pairing). Open a WebView to Google sign-in with the cookie domains libgm needs, then
  show the emoji confirmation step. Follow libgm's current pairing implementation
  exactly; it changes. If Google refuses sign-in inside the WebView, stop and tell the
  owner at once: with no QR fallback there is no other way to pair.
- Persist the pairing keys with `EncryptedFile` / Keystore-wrapped AES.
- Map libgm conversations and messages into the model, including RCS vs SMS
  transport per message, reactions, replies, read receipts, typing, media.
- Send text, media, reactions, read markers, typing; delete for me (also deletes on
  the phone via libgm if supported, else local only; record which).
- Supervisor integration: on libgm "unpaired" errors, emit `ActionNeeded` with a
  deep link to `com.google.android.apps.messaging`.

### P3.3 Acceptance for Phase 3

- Contract tests against a recorded libgm session fixture.
- **Gate G2**: the owner pairs with their Google Messages. Checklist: pair with a Google
  account (sign in, match the emoji), receive an RCS message, send text, send an image, react, receive a
  reaction, see typing, see RCS vs SMS bubbles, kill the app and confirm reconnection,
  toggle airplane mode and confirm reconnection, unpair from Google Messages and
  confirm the "Action needed" flow.

---

## 5. Phase 4: notifications, background, and message features that need the real network

### P4.1 Notifications

- Channels: `default`, one per customised chat (`chat_<id>_<n>`, recreated with a new
  `n` when sound changes), one per keyword rule, one per Instagram folder, one per
  account. `NotificationRouter` chooses: keyword rule (if matches and overrides) >
  per-chat channel > folder channel > account channel > default; drops when the
  chat is muted, low priority, obscured-preview rules apply.
- Conversation notifications with `Person`, `ShortcutInfo`, `MessagingStyle`,
  inline reply and mark-read actions, bubbles metadata.
- OTP detection (`\b\d{4,8}\b` near "code", "OTP", "verification", plus Google's
  patterns) with "Copy code" action and optional auto-copy via
  `ClipboardManager.setPrimaryClip` with `ClipDescription.EXTRA_IS_SENSITIVE`.
- During setup in Google Messages mode, offer to open Google Messages' notification
  settings.

### P4.2 Scheduled sends

`AlarmManager.setExactAndAllowWhileIdle` (request `SCHEDULE_EXACT_ALARM`), a
`BroadcastReceiver` that enqueues `ScheduledSendWorker`; late sends notify.

### P4.3 Media saving, link previews, clean links

- `MediaDownloadWorker` saves attachments per Storage settings, including ephemeral
  ones where delivered; uses `MediaStore` when the owner picks a public folder.
- `LinkPreviewWorker` uses network-provided previews first, else fetches OpenGraph
  with a 1 MB cap and the Wi-Fi-only rule.
- Clean links: vendor the ClearURLs rules JSON under `assets/clearurls/` with its
  licence, update via a Gradle task, apply on send and on display.

### P4.4 Acceptance

Unit tests for router precedence, OTP regexes, ClearURLs application, schedule
parser. **Gate G3** on the owner's phone: per-chat sound, keyword rule, OTP copy,
scheduled send while the app is closed, link preview, cleaned link.

---

## 6. Phase 5: removed (native SMS mode, roadmap only)

Native SMS mode (PingMe as the phone's texting app: `RoleManager.ROLE_SMS`,
`SmsReceiver`, `MmsReceiver`, `HeadlessSmsSendService`, `SmsManager` sends, history
from `Telephony.Sms` and `Telephony.Mms`, a mode switch in Settings) was taken out of
the plan by the owner on 2026-10-02 at Gate G3. Google Messages is the one way texts
reach PingMe, so there is no mode choice in setup or Settings. The idea stays on the
roadmap for after the Android app is finished, together with a desktop app (its own
pairings, with device sync) and Android chat bubbles (pending the owner's decision).
The phase number is kept so earlier notes still read; there is no Gate G4.

---

## 7. Phase 6: further networks

Each connector follows the same shape as P3.2 and passes the contract test before its
gate. Build in this order, one gate each (G5 to G10):

1. **WhatsApp** (`whatsmeow` via the Go bridge): QR link, history sync, text/media,
   reactions, replies, delete for everyone with time limit, communities exposed as
   `Space`s, view-once media saved when delivered.
2. **Telegram** (TDLib, built with its official Android build instructions into an
   AAR under `connectors/telegram/libs/`; if a maintained prebuilt exists on Maven
   Central at build time, prefer it and record the coordinates): phone + code login,
   forum topics as `Space`s, reactions rule from `availableReactions`.
3. **Signal** (`signalmeow` via the Go bridge): QR link, note history limits,
   unlink detection as `ActionNeeded`.
4. **Google Voice** (`mautrix-gvoice` library via the Go bridge): WebView Google
   sign-in, cookie capture, poll/stream per the library, `startConversation`
   unsupported until implemented.
5. **Instagram** (the `mautrix-instagram` Go bridge's library, split from
   mautrix-meta in August 2026): cookie login, folder detection from inbox and
   pending-inbox endpoints, per-folder notification channels, Requests UI, hide
   General switch.
6. **Messenger** personal (`messagix` from mautrix-meta): cookie login.
7. **Facebook Page** (`connectors/fbpage`, Kotlin, Graph API): Page token entry,
   `GET /{page}/conversations?fields=messages{...}` polling on a WorkManager
   periodic job with a foreground-fast interval, `POST /me/messages` send, 24-hour
   window check before send, `Polled` connection state.

---

## 8. Phase 7: people, merging, calls, spaces

- `ContactSyncWorker`: match `Person.phoneNumber` to `ContactsContract` (E.164
  normalise with libphonenumber), cache contact photo to app storage, refresh on
  `ContactsContract` change.
- Merge suggestions: same E.164 across accounts creates a suggestion card; confirm
  creates `MergeLink`s and a merged `Chat` view (a virtual chat whose message flow is
  the union of member chats ordered by `sentAt`); split removes it. Avatar source
  and name override live on the merged chat.
- Merged composer network chip with disconnected state; default send account.
- Calls: `ACTION_CALL` (permission `CALL_PHONE`) for dialer; for WhatsApp, Signal,
  Telegram query `ContactsContract.Data` for the app's call MIME types
  (`vnd.android.cursor.item/vnd.com.whatsapp.voip.call`,
  `...vnd.com.whatsapp.video.call`, Signal and Telegram equivalents, discover at
  runtime by listing MIME types for the contact) and fire `ACTION_VIEW` on that row;
  Meet via its contact MIME type; fallbacks open the app's thread. Record what each
  does on the owner's phone in the build log.
- Spaces UI and bottom-bar configuration.
- **Gate G11**: merge a real person across two networks, switch services, call
  buttons for each service.

---

## 9. Phase 8: polish and release

- Accessibility pass with TalkBack; contrast checks in the Appearance studio.
- Backup and restore of the database and settings to a user-chosen file, encrypted
  with a passphrase.
- Battery review: `adb shell dumpsys batterystats` after a day; fix wakeups.
- Crash-free run for a week on the owner's phone.
- Tag `v1.0.0`; CI attaches the APK to the release.

---

## 10. Sideload instructions for the owner

1. On the phone: Settings > Apps > Special app access > Install unknown apps >
   allow for your browser or Files app.
2. Open the GitHub release (or the CI artifact), download `pingme-<version>.apk`,
   open it, tap Install. Updates install over the old version because the signing
   key is the same.
3. First launch: choose Google Messages mode, grant notifications, allow battery
   optimisation exemption, grant contacts, then pair with Google Messages.
4. Google Messages stays your default SMS app in this mode. Do not change it.

---

## 11. Risk register

| Risk | Signal | Response |
|---|---|---|
| libgm pairing changes | pairing fails on a fresh library | Read libgm's current `pair` code and its bridge's login command; adapt; log |
| gomobile export limits | bind errors on types | Keep the Go boundary to strings, bytes, callbacks |
| Google revokes pairing often | ActionNeeded frequently | Prefer account pairing; measure and tell the owner |
| Meta suspends the personal account | login loops | Two-factor on, no history spam, warn in UI |
| TDLib build time | CI over 30 min | Cache the AAR by TDLib commit |
| Call intents differ per app version | wrong screen opens | Runtime MIME discovery, fallback to app thread |
| View-once media not delivered | attachment empty | Show "not delivered to linked devices" instead of a broken tile |
