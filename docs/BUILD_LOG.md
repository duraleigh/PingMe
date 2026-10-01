# PingMe build log

One entry per plan step. Newest at the bottom.

## Session start

No steps executed yet. Next: P0.1.

## P0.3 (done ahead of the build, by the owner)

The sideload signing keystore was generated and the four repository secrets exist on
GitHub with these exact names: `SIDELOAD_KEYSTORE_B64`, `SIDELOAD_KEYSTORE_PASSWORD`,
`SIDELOAD_KEY_ALIAS`, `SIDELOAD_KEY_PASSWORD`. The keystore file is not in the repo
and never should be. The builder only needs to wire the release signing config and
the CI workflow to read these; do not ask the owner to create them again. If CI
fails to decode or open the keystore, ask the owner to re-paste
`SIDELOAD_KEYSTORE_B64` from their password manager. Next: P0.1.

## P0.1 Toolchain (done, 2026-09-30)

**Setup script did not run in this session.** At session start `/opt/android-sdk`,
`/opt/android-ndk`, and `gomobile` were absent and `~/.bashrc` had no Android
lines, so the environment's setup script never executed (no error, it just was not
run). Running `scripts/setup-cloud.sh` by hand completed with no failures, so the
script is not broken. Owner: open the PingMe cloud environment (environment menu in
the session title bar > Edit) and check that the *Setup script* field contains the
current contents of `scripts/setup-cloud.sh`. New sessions pick it up from there.

Installed by hand this session, then verified:

| Tool | Version |
|---|---|
| JDK | OpenJDK 21.0.10 (preinstalled) |
| Gradle (system) | 8.14.3 (the project will use its own wrapper from P0.2) |
| Go | go1.24.7 system; `GOTOOLCHAIN=auto` fetched go1.26.8 for gomobile |
| gomobile / gobind | `golang.org/x/mobile` v0.0.0-20260908204917-8b95e45f8d3e, built with go1.26.8, `gomobile init` ran clean |
| Android cmdline-tools | 12.0 (the zip pinned in the script) |
| platform-tools | 37.0.1 |
| Platforms | android-35, android-36, android-37.2 (newest stable; 37.2 betas ignored) |
| Build-tools | 35.0.0, 36.1.0, 37.0.0 (newest) |
| NDK | 27.2.12479018 (from the script) and 27.3.13750724 (r27d, newest 27.x). `/opt/android-ndk` now points at 27.3 |
| SDK licences | accepted |

Network: every download succeeded (`dl.google.com`, `proxy.golang.org`,
`golang.org` toolchain download). No hosts refused.

**Deviations and notes**

- gomobile at `@latest` requires Go 1.26 or newer. It works here only because
  `GOTOOLCHAIN=auto` downloads a newer Go. CI (P0.4) must set up Go 1.26+ directly.
- The script still installs platform 35, build-tools 35.0.0, and NDK 27.2. It works,
  but it is behind the newest stable. Updating it to the versions P0.2 settles on
  is pending the owner's go-ahead (see "Next").

**Latest stable versions checked at session start** (plan rule 8, for P0.2):
AGP 9.4.1, Gradle 9.8.0, Kotlin 2.4.20, KSP 2.3.12, Compose BOM 2026.09.00,
material3 1.4.0 stable (1.5.0-alpha29 newest pre-release), Room 2.8.5, Hilt 2.60.1,
WorkManager 2.12.0, DataStore 1.2.1, navigation-compose 2.10.2, activity-compose
1.13.0, Media3 1.11.1, androidx.window 1.5.1, core-splashscreen 1.2.0, Coil 3.6.3,
Go 1.27.1. P0.2 must check that every Expressive component named in `UI_DESIGN.md`
(for example `FloatingActionButtonMenu`, split button, flexible top app bar) exists in
material3 1.4.0 stable. If one exists only in 1.5 alphas, record it here and ask the
owner before choosing the alpha.

**Next:** P0.2, repository scaffold (owner approved it, and the script update, on
2026-09-30).

## P0.2 Repository scaffold (done, 2026-09-30)

Gradle multi-module project per `DESIGN.md` 6.8 and the plan: `app`, `core/{model,
store,connector-api,service,ui}`, `connectors/{demo,gmessages,sms,whatsapp,telegram,
signal,gvoice,instagram,messenger,fbpage}`, and `gobridge/` (README only; the Go
module is P3.1). `LICENSE` holds the AGPL-3.0 text; every source file carries the
SPDX header. The app shows a blank Material 3 Expressive screen with "PingMe"
(`MainActivity`, using `MaterialExpressiveTheme` with `MotionScheme.expressive()`).

Checks: `./gradlew check` passes (lint and unit tests; no tests exist yet),
`assembleDebug` and `assembleRelease` build. The APK reads `org.pingme.app`,
minSdk 29, targetSdk 37, compileSdk 37, label "PingMe".

**Versions used** (`gradle/libs.versions.toml`): Gradle wrapper 9.8.0 (checksum
pinned), AGP 9.4.1, Kotlin 2.4.20, KSP 2.3.12, Hilt 2.60.1, androidx.hilt 1.4.0,
Compose BOM 2026.09.00, material3 **1.5.0-alpha29**, material3-adaptive 1.3.0,
core-ktx 1.19.1, core-splashscreen 1.2.0, activity-compose 1.13.0, lifecycle 2.11.0,
navigation-compose 2.10.2, window 1.5.1, Room 2.8.5, WorkManager 2.12.0,
DataStore 1.2.1, Coil 3.6.3, Media3 1.11.1, kotlinx-serialization 1.11.0,
kotlinx-coroutines 1.11.0, desugar_jdk_libs 2.1.5. compileSdk is API 37 minor 2
(`platforms;android-37.2`), the newest stable platform and the highest AGP 9.4 allows.

**Deviations and decisions**

- **material3 1.5.0-alpha29, not a stable release.** 1.4.0 stable (what the BOM picks)
  lacks `FloatingActionButtonMenu`, split button, button groups, floating toolbar,
  loading indicator, toggle buttons, and `MaterialShapes`, and keeps
  `MotionScheme.expressive()` internal. Checked by reading both AARs. The owner chose
  the alpha on 2026-09-30. The catalog pins it above the BOM. Each alpha bump gets its
  own log line.
- **AGP 9 built-in Kotlin.** AGP 9 compiles Kotlin itself and forbids the
  `org.jetbrains.kotlin.android` plugin, so Android modules apply only the AGP plugin
  (plus the Compose compiler plugin where needed). The root applies the Kotlin JVM
  plugin (`apply false`) so KGP 2.4.20 is on the classpath instead of AGP's bundled
  2.2.10.
- **AGP 9 DSL.** `CommonExtension` is no longer generic and exposes `defaultConfig`
  and `compileOptions` as getters only. compileSdk uses
  `compileSdk { version = release(37) { minorApiLevel = 2 } }` and targetSdk
  `targetSdk { version = release(37) }`, both read from the AGP 9.4.1 jars.
- **Shared module settings** (compileSdk, minSdk, Java 17, desugaring, the targetSdk,
  and the opt-ins) live once in the root `build.gradle.kts`, not in a `build-logic`
  convention-plugin build. That is less machinery for the same result.
- **Opt-ins** for `ExperimentalMaterial3ExpressiveApi` and `ExperimentalMaterial3Api`
  apply to every module that compiles Compose (today `app` and `core/ui`). Modules
  without material3 on the classpath would only get "unresolved opt-in marker"
  warnings from them.
- `core/model` is a plain Kotlin/JVM module (P1.1 says no Android imports).
- Module wiring: store and connector-api depend on model; service on store and
  connector-api; ui on model; every connector on connector-api; app on all of them,
  with `connectors/demo` as `debugImplementation` only (P1.5).
- Hilt, KSP, Room, and the serialization plugin are declared at the root and resolve;
  each module applies them in the step that first needs them.
- `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, and `gradlew.bat` carry no
  SPDX header. Gradle generates them and `gradlew wrapper` rewrites them.
- `LICENSE` came from `github.com/mautrix/gmessages` because `www.gnu.org` reset
  every connection through this container's proxy. Its sha256
  (`0d96a4ff…abcb0`) matches the FSF's `agpl-3.0.txt`.

**Environment notes**

- **Maven Central returned HTTP 429 (rate limit)** to this container for
  `repo.maven.apache.org` and `repo1.maven.org`. Worked around for this session only
  with `~/.gradle/init.d/central-mirror.init.gradle.kts`, which points Central at
  Google's public mirror `maven-central.storage-download.googleapis.com`. Nothing in
  the repo changed; CI resolves from Central directly. If a later session hits 429,
  recreate that init script.
- AGP 9.4 auto-installed `build-tools;36.0.0` (its default). `scripts/setup-cloud.sh`
  now installs platform android-37.2, build-tools 36.0.0 and 37.0.0, and NDK
  27.3.13750724 (owner approved). **Owner: paste the new `scripts/setup-cloud.sh`
  into the PingMe environment's Setup script field.**
- Builds print "Unable to strip libandroidx.graphics.path.so". AGP strips native libs
  with its default NDK (28.2), which is not installed. It is harmless (the lib ships
  unstripped). Revisit in P3.1 when the Go AAR adds native code.
- One Gradle 10 deprecation (`Configuration.setVisible`) is reported. It comes from
  AGP's own application plugin, not from project scripts.

**Left for later steps (not done here, by plan)**

- Release APK is unsigned until P0.3 wires the signing config to the secrets.
- Lint reports `MissingApplicationIcon` and `DataExtractionRules` (the
  `allowBackup` attribute is deprecated from Android 12). These are warnings today.
  P0.5 turns lint warnings into errors for `app`, so P0.5 must resolve them.

**Next:** P0.3, wire the release signing config to the four existing secrets.

## P0.1 follow-up: setup runs from a SessionStart hook (2026-09-30)

The owner cannot be expected to paste the setup script into the environment settings,
and the builder cannot edit those settings. So `.claude/settings.json` now registers
`.claude/hooks/session-start.sh`, which runs `scripts/setup-cloud.sh` at the start of
every cloud session (it does nothing on local machines) and exports `ANDROID_HOME`,
`ANDROID_NDK_HOME`, and `PATH` for the session. The environment's Setup script field
is no longer needed and can stay empty.

The script now skips anything already installed, adds its `~/.bashrc` lines only
once, and writes the Maven Central mirror init script from P0.2 into `~/.gradle`
(cloud sessions only; CI and local machines are unaffected).

Tested: a cloud run with everything installed takes under a second. A run with the
SDK, NDK, and gomobile removed reinstalled platform android-37.2, build-tools 36.0.0
and 37.0.0, NDK 27.3.13750724, and gomobile in 41 s. `./gradlew check assembleRelease`
passed afterwards. A local (non-cloud) run exits immediately.

**Important:** new sessions start from the default branch, `main`. The hook takes
effect only once this branch is merged into `main`.

## P0.3 Signing for sideloading (done, 2026-09-30)

The owner created the keystore and the four secrets earlier (see the P0.3 entry at
the top). `app/build.gradle.kts` now signs `release` with them when present:

- `SIDELOAD_KEYSTORE_FILE`: path to the decoded keystore. **This is not a secret.**
  CI (P0.4) decodes `SIDELOAD_KEYSTORE_B64` to a temporary file and sets this.
- `SIDELOAD_KEYSTORE_PASSWORD`, `SIDELOAD_KEY_ALIAS`, `SIDELOAD_KEY_PASSWORD`: the
  secrets, passed through as they are.

Without `SIDELOAD_KEYSTORE_FILE` (local builds) release is signed with the debug key,
as the plan says. If the file is set but any of the other three is missing or empty,
the build fails with a message naming it. It does not fall back, because an APK
signed with the debug key would not install over the owner's copy.

Tested with a throwaway key (never the real one): no variables gave the "Android
Debug" certificate; all four gave the test key's certificate; a missing password
failed with "SIDELOAD_KEYSTORE_FILE is set but SIDELOAD_KEYSTORE_PASSWORD is missing
or empty". The output is now `app/build/outputs/apk/release/app-release.apk`, the
path P0.4 uploads. `./gradlew check` passes.

**Next:** P0.4, the CI workflow (owner approved it on 2026-09-30).

## P0.4 CI (2026-09-30)

`.github/workflows/build.yml`, on every push, every pull request, and tags `v*`:

1. JDK 17 (Temurin), the exact SDK packages (`platforms;android-37.2`,
   `build-tools;36.0.0`), and Gradle with caching.
2. Go bridge: skipped until `gobridge/build.sh` exists (P3.1). After that it is
   rebuilt only when a file under `gobridge/` changes; the AAR is cached by that hash.
   That rebuild step sets up Go (from `gobridge/go.mod`, modules cached by
   `gobridge/go.sum`), NDK 27.3, and gomobile.
3. Decodes `SIDELOAD_KEYSTORE_B64` and checks that the keystore opens with
   `SIDELOAD_KEYSTORE_PASSWORD`. If not, the run fails with a message saying which
   secret to re-paste. Pushes and tags require the secrets. Pull requests from forks
   get no secrets, so they warn and use the debug key.
4. `./gradlew lint testDebugUnitTest assembleRelease`.
5. Uploads the signed APK as `pingme-<short-sha>.apk`, or `pingme-<tag>.apk` on a tag.
6. On a `v*` tag, a second job creates the GitHub release with that APK attached.

**Deviations and decisions**

- Action versions (latest releases): checkout v7, setup-java v6, setup-go v7,
  cache v6, upload-artifact v7, download-artifact v8, gradle/actions v6.
- The APK is uploaded with `archive: false` (upload-artifact v7), so the download is
  the `.apk` itself, not a zip. It opens straight from a phone.
- setup-gradle defaults to a commercial caching service; the workflow selects its
  open-source `basic` cache (GitHub Actions cache) instead.
- Go and gomobile are set up only when the bridge needs building, rather than on
  every run. Before P3.1 there is nothing for them to do.
- The plan says to run `lint testDebugUnitTest`. When P0.5 adds the `check` alias,
  CI switches to `./gradlew check`.
- Checked locally: `actionlint` 1.7.12 reports no problems. The keystore step was run
  locally against a throwaway key: a good key, a key with wrapped base64 lines, a
  wrong password, and non-base64 input all behave as intended. The workflow's first
  real run is on this push.

## P0.5 Static checks (done, 2026-09-30)

`./gradlew check` now runs, in every module: ktlint (`ktlintCheck`, including the
build scripts), detekt, Android lint, and all unit tests. CI runs
`./gradlew check assembleRelease`.

- **ktlint**: Gradle plugin 14.2.0 (supports AGP 9 built-in Kotlin), ktlint 1.8.0,
  `ktlint_official` style via `.editorconfig`. `@Composable` functions are exempt
  from the function-naming rule.
- **detekt**: **2.0.0-alpha.6**, default rule set (`buildUponDefaultConfig`) plus
  the Compose rules (`io.nlopez.compose.rules:detekt` 0.6.7). Config is in
  `config/detekt/detekt.yml`. The Compose rule block is copied from compose-rules'
  docs. Its opt-in `Material2` rule is switched on to enforce CLAUDE.md rule 10.
  detekt's own `FunctionNaming` ignores `@Composable` functions.
- **Android lint**: `warningsAsErrors = true` and `abortOnError = true` for `app`.

**Deviation: detekt alpha.** The last stable detekt, 1.23.8, embeds Kotlin 2.0.21,
which cannot reliably parse this project's Kotlin 2.4 and predates AGP 9's built-in
Kotlin. Every maintained Compose-rules release (0.5.0 onward) targets detekt 2.0
alphas. detekt 2.0.0-alpha.6 embeds Kotlin 2.4.10 and is tested against AGP 9.3
built-in Kotlin. It is build tooling only and ships nothing in the APK.

**Lint findings fixed (both became errors under warningsAsErrors):**
- `DataExtractionRules`: added `data_extraction_rules.xml` (Android 12+) and
  `backup_rules.xml` (Android 10 and 11). Both exclude everything from Android
  backup and device transfer. Keystore-wrapped credentials cannot move to another
  phone anyway (DESIGN.md 6.5), and Phase 8 builds PingMe's own encrypted backup.
- `MissingApplicationIcon`: added a **placeholder** adaptive launcher icon (white
  chat bubble with a coral "ping" dot on indigo, plus a monochrome layer for themed
  icons). The design docs do not specify an icon. The owner can supply a real one
  later.

**Proven to fire** by planting violations and removing them: ktlint (wildcard
import, spacing, indentation), detekt (`EmptyIfBlock`, `MagicNumber`), Compose rules
(`ModifierMissing` on a composable without a modifier; `Material2` on
`androidx.compose.material.Button`), and lint (the two findings above failed the build
before they were fixed).

## P0.6 Acceptance for Phase 0: stopped at Gate G0 (2026-09-30)

Builder side, all verified:
- `./gradlew check` passes on a fresh clone with the build cache off.
- CI is green on push and pull request for `cf3c293`, and produced the signed APK
  `pingme-cf3c293.apk` (run 36751138974). Download (sign in to GitHub first):
  https://github.com/duraleigh/PingMe/actions/runs/36751138974/artifacts/11115225225

**Gate G0, owner's phone test.** Not done yet; the builder cannot do it.

1. Open the download link above on the phone and download `pingme-cf3c293.apk`.
2. Open it. If the phone asks, allow your browser (or Files) to install unknown apps,
   then go back and tap Install.
3. The home screen shows an indigo icon with a white chat bubble and an orange dot,
   named "PingMe".
4. Open it: a plain screen with the word "PingMe" in the middle. In dark mode the
   screen is dark.
5. Report back: did it install, and did it open as described? Screenshots of anything
   odd help.

Update test (proves the signing key, the point of this gate): after the next CI build
exists, install it over this one. It must say "Update" or install without asking to
uninstall first. The builder will send that APK with Phase 1.

Phase 1 must not start until the owner reports G0 results (BUILD_PLAN.md rule 1).
**Next:** wait for G0 results, then P1.1.

## Working agreement with the owner (2026-09-30)

- The builder moves through plan steps without asking permission for each one. It
  still stops at every gate (G0, G1, ...) for the owner's phone test, and asks only
  when a decision is truly the owner's (for example, narrowing a feature).
- The builder merges its own pull requests into `main` once CI is green. The owner
  does not press Merge.
- Talk to the owner in plain, simple language: short steps, no jargon.

## Gate G0 result (2026-09-30)

Owner report: `pingme-cf3c293.apk` installed on the phone and opened, showing
"PingMe". **G0 passed.** Signing, sideloading, and CI are proven for a first install.

Still open: the update test (installing a newer build over this one without
uninstalling). It needs a second CI build, so it rides with the first Phase 1 APK.

**Next:** P1.1, core model.

## P1.1 Model (done, 2026-09-30)

`core/model` (plain Kotlin/JVM, kotlinx-serialization, no Android imports) holds every
type from the plan: the ID value classes, `NetworkId`, `Account`, `ConnectionState`,
`Chat`, `ChatKind`, `ChatFolder`, `Message`, `MessageKind`, `MessageStatus`,
`Transport`, `Attachment`, `Reaction`, `Quote`, `LinkPreview`, `Person`, `Space`,
`Capabilities`, `NotificationMode`, `AvatarSource`. Times are `kotlin.time.Instant`
(stable since Kotlin 2.3; no opt-in).

`SerializationTest` round-trips every type through JSON, including every sealed-class
case (5 tests, all pass).

**Deviations and decisions** (all additions or sharper types; nothing narrowed):
- Added `AttachmentId`. The plan's `Attachment` has an `id` and P1.2's `MediaSaveJob`
  refers to it, but the plan lists no ID type for it.
- Added `AttachmentKind` (image, video, audio, voice, GIF, sticker, file, contact,
  location) for `Attachment.kind`. The plan names the field without a type.
- `Capabilities` follows the capability matrix in UI_DESIGN.md section 8, which the
  plan says wins where they differ:
  - `reply` is `ReplyRule` (`NATIVE` or `QUOTED_TEXT`), not a yes/no, because SMS and
    Google Voice reply with quoted text.
  - `gif` and `voiceNote` are `MediaRule` (`NATIVE`, `MMS_SIZE_LIMITED`,
    `UNSUPPORTED`).
  - `deleteForEveryone` and `edit` are `TimeLimit?`: null means unsupported,
    `Unlimited` or `Within(duration)` otherwise.
  - `calls` is `CallRule(audio, video)`, each a `CallMethod`, matching the table in
    UI_DESIGN.md 10.17.
- `ReactionRule` cases are `AnyEmoji`, `Set(allowed)`, `TextFallback`. `Any` was
  renamed so it doesn't shadow Kotlin's `Any`.
- `Space.accountId` is nullable. It is null only for `CUSTOM` spaces, which
  UI_DESIGN.md 10.4 lets the user build "from any chats", including chats from
  several accounts.

**Next:** P1.2, the Room store.

## P1.2 Store (done, 2026-09-30)

`core/store`: Room database `pingme.db` (schema exported to `core/store/schemas/`),
DAOs returning `Flow`s, and the five repositories the UI and service talk to:
`AccountRepository`, `ChatRepository` (chats, spaces, unread totals),
`MessageRepository` (messages, search, scheduled sends, media-save jobs),
`ContactRepository` (people, merge links), `SettingsRepository` (preferences
DataStore `settings`, plus keyword rules). A Hilt `StoreModule` provides the database,
the DataStore, and a `Clock`.

Tables: accounts, chats (+ `chat_participants`), messages, attachments, reactions,
persons, spaces (+ `space_chats`), `scheduled_sends`, `keyword_rules`,
`merge_links`, `media_save_jobs`, and the FTS5 index `message_fts`. Foreign keys
cascade: deleting an account removes its chats, messages, and people; deleting a
chat removes its messages. Saving an existing row updates it in place and never
cascades.

**Unread rule**: implemented once, as `ChatRepository.unreadTotals()`. It returns
the total plus per-account, per-network, and per-space breakdowns. It counts only
chats that are not archived, not low priority, not muted, not in Requests, and not in
Instagram General while General is hidden, and only in accounts shown in the inbox.

**Search**: an FTS5 index over message body, sender name, and attachment file
names. Triggers keep it in sync when messages, attachments, or people change. Every
word the user types becomes a quoted prefix term, so typed input can never break the
query. Case and accents are ignored ("cafe" finds "Café").

Tests: 38 unit tests (Robolectric, in-memory database on the same bundled SQLite as
the app), covering every DAO through its repository, the unread rule, cascade
behaviour, and every search trigger. A deliberate break of the unread rule and of the
chat-scoped search was caught by the tests, then reverted.

**Deviations and decisions**
- **Bundled SQLite (`androidx.sqlite:sqlite-bundled` 2.7.1).** The plan asks for
  FTS5, which Android's own SQLite does not include, and Room has no FTS5 entities.
  The app ships its own SQLite through Room's bundled driver. The FTS5 table and its
  triggers are plain SQL created on open (`MessageFts`), and search uses a raw query.
  Side benefit: the same SQLite version on every phone. Cost: about 1 to 2 MB of APK
  per CPU type.
- **Host tests of the bundled SQLite.** The Android build of the library cannot load
  on the build machine. `core/store/build.gradle.kts` extracts the host build
  (`sqlite-bundled-jvm`, same version) and points the loader's two documented system
  properties at it for unit tests only.
- The index is contentless (`contentless_delete=1`): it stores only the index, not a
  second copy of every message. Messages have an internal integer `rowId` key, since
  SQLite can renumber implicit rowids.
- If the index is ever missing when the database opens, it is recreated and rebuilt
  from the messages (tested).
- A timed mute stops hiding a chat from the unread totals once it ends, but the totals
  only refresh on the next database change. A job that clears expired mutes can come
  with notifications (P4.1).
- `instagramShowGeneral` is one app-wide switch, as the plan's rule states. If the
  owner later wants it per Instagram account, the rule's query takes a list instead.
- Model additions for the store: `Attachment.fileName` (indexed as attachment names),
  and `KeywordRule`/`KeywordScope`, `ScheduledSend`, `MergeLink`, `MediaSaveJob`/
  `MediaSaveState` in `core/model`.
- detekt `TooManyFunctions`: `@Dao` interfaces are exempt and classes may have 20
  functions, since each repository is the single front door to its part of the store.
- New libraries: `sqlite-bundled` 2.7.1, Robolectric 4.17 (tests run on API 35),
  androidx.test core 1.7.0.

**Next:** P1.3, the connector API.

## P1.3 Connector API (done, 2026-09-30)

`core/connector-api`:
- **`Connector`**: the plan's interface. Operations a network cannot do throw
  `UnsupportedCapabilityException(reason)`, and the reason is shown to the user.
- **`ChatSnapshot`, `MessageSnapshot`**: what the network says. Local-only choices
  (pin, mute, low priority, obscure, name override, merge) stay out of snapshots.
- **`OutgoingMessage`, `OutgoingAttachment`**: serializable, so a scheduled send
  survives a restart. **`SendResult`**: `Sent` or `Failed(reason, retryable)`.
- **`ConnectorEvent`**: every kind the plan lists; each event names its account.
- **`LoginFlow`**: steps plus `respond(stepId, value)`, with a `loginFlow { }` script
  builder. Answers to an older step are ignored.
- **`Credentials`, `CredentialStore`**: the interface only. The encrypted
  implementation comes with P3.2's Keystore work.
- **`ConnectorRegistry`**: fed by a Hilt map multibinding keyed by `@NetworkKey`, and
  declared with `@Multibinds` so it works before any connector exists.

`core/connector-contract` (new, test-only module): `ConnectorContractTest`, which every
connector's tests extend with a `Harness` around their fake transport. It covers login
to Done, the Connected state, synced chats belonging to the account, paging history
backwards, send, incoming messages as events, typing, reactions (any, set, and text
fallback), delete for me and for everyone, starting a conversation, and disconnect.
When a capability is missing, the test asserts that the operation throws with a
reason, so every test checks something for every connector. The demo connector (P1.5)
is the first to run it.

Unit tests: `LoginFlowTest` and `ScopedIdsTest` (4 tests, all pass).

**Deviations and additions**
- **Account-scoped IDs.** Chat, message, attachment, person, and space IDs are
  `"<account id>/<network id>"` (`ScopedIds.kt`). Any ID then says which account it
  belongs to, which the plan's signatures need (for example `syncMessages(chatId, ...)`
  must know the account). Two accounts on one network never collide.
- `startConversation` takes an `accountId` as well as the handle. A network can have
  several accounts (UI_DESIGN.md 6.5), so the handle alone cannot say which one
  starts the chat.
- **Login steps beyond the plan's five.** `Choose` (Google Messages offers QR or Google
  account pairing, P3.2) and `Failed` (a login that cannot finish). `WaitForConfirmation`
  carries an optional `emoji` for the emoji-match step.
- **Extra event `MessageRemoved`.** A message deleted on the network's side, such as on
  the phone in Google Messages, must disappear here too (UI_DESIGN.md 5.3).
- **Contract test location.** It lives in its own module, not in `connector-api` test
  fixtures, because AGP supports Kotlin in test fixtures only behind an experimental
  flag.
- Spaces are only an ID on `ChatSnapshot` for now. Space metadata (community names)
  arrives with the first network that has spaces (WhatsApp, Phase 6).

**Next:** P1.4, the service skeleton.

## P1.4 Service skeleton (done, 2026-09-30)

`core/service`:
- **`ConnectionService`**: the one foreground service ("PingMe is connected", a silent
  minimum-priority notification), type `remoteMessaging`. It hosts the supervisor,
  forwards network changes, and stops itself when no account needs a connection.
  `MainActivity` starts it only when an account needs one.
- **`ConnectorSupervisor`**: one coroutine per account. It connects, syncs the chat list
  once connected, and writes every event through `EventApplier`.
  - Retries wait 1 s, 2 s, 4 s, ..., up to 5 minutes (`Backoff`).
  - A connection that worked and then dropped starts over at 1 s; a network change cuts
    any wait short and starts over.
  - `ActionNeededException`, a `State(ActionNeeded)` event, or missing credentials
    stop retries and set "Action needed" on the account.
  - Disabled accounts are never connected, and disabling disconnects.
  - Unknown failures count as transient and are logged on the phone only.
- **`EventApplier`**: turns connector events into store writes.
  - Chat snapshots keep everything the user chose (pin, mute, archive, low priority,
    obscure, name override, avatar source, merges).
  - An incoming message adds to unread, bumps activity, and brings an archived chat
    back; an outgoing one marks the chat read. History batches never count as unread.
  - A message for a chat not yet known lands under a minimal chat until its snapshot
    arrives.
- **`TypingTracker`**: who is typing where, in memory only. It clears when their message
  arrives, or after 6 s without an update.
- **`NotificationRouter` (skeleton)**: outgoing, muted (a timed mute counts until it
  ends), and low priority messages stay quiet. Obscured chats say "New message". The
  rest post a plain notification on the `default` channel once notifications are
  allowed. Full routing is P4.1.
- **`KeystoreCredentialStore`**: the `CredentialStore` implementation. AES-256-GCM with
  a key that lives only in the Android Keystore, one file per credential (named by
  hash) in no-backup storage, written atomically.
- **Workers (Hilt + WorkManager)**:
  - `HistoryBackfillWorker`: pages of 50, 4 pages a run, continuing where the store ends.
  - `MediaDownloadWorker`: saves the file path; retries up to 5 times.
  - `ScheduledSendWorker`: sends due and late messages and swaps the pending bubble for
    the sent one; retryable failures retry up to 5 times, others mark the message
    Failed.
  - `LinkPreviewWorker` and `ContactSyncWorker` are registered with empty bodies.
    Their work is P4.3 and Phase 7 in the plan, and their code says so.
- **App**: `PingMeApp` (`@HiltAndroidApp`) supplies the Hilt worker factory to
  WorkManager (the default initializer is removed from the manifest). `MainActivity` is
  a Hilt entry point.

Tests: 25 in `core/service` (backoff, notification decisions, event applier,
supervisor, credential store, workers), 40 in `core/store`, plus the earlier model and
connector-API tests. Three deliberate supervisor breaks (retrying when the user must
act, twice, and not resetting after a drop) failed the tests, then were reverted.

**Deviations and decisions**
- **Credential store now, not in P3.2.** The supervisor needs a `CredentialStore` to
  connect anything, including the demo network. P3.2's "EncryptedFile / Keystore-
  wrapped AES" is built here as Keystore-wrapped AES-GCM. Jetpack's EncryptedFile
  (`security-crypto`) is deprecated, so it is not used.
- **Network changes** come from `ConnectivityManager.registerDefaultNetworkCallback`.
  The old network-change broadcast the plan mentions is deprecated and not delivered
  to apps on current Android.
- **Scheduled sends** moved to their own `ScheduledSendRepository`, to keep
  `MessageRepository` focused. Store additions: read receipts marking outgoing messages
  Read, attachment download paths, and the oldest message of a chat.
- **Retry waits** go through a `RetryDelays` interface so tests use milliseconds. The
  app binds the real `Backoff`.
- detekt `ReturnCount` now ignores early "nothing to do" guard clauses.
- **Not in this step:** reconnecting after a phone restart (a boot receiver) is not
  in the plan's P1.4 list. Until it's added, connections resume the next time the app
  opens. It belongs with notifications (P4) and is logged here so it isn't lost.

**Next:** P1.5, the demo connector.

## P1.5 Demo connector (done, 2026-09-30)

`connectors/demo`, included by the app in debug builds only (`debugImplementation`),
added to the `ConnectorRegistry` through Hilt:
- **Cast and chats** (`DemoSeed`): 13 people and 14 chats, including Sam Ortiz, the
  Design team, Mom, Dad, and Book club.
  - Direct and group chats, unread and read.
  - A reply with a quote, reactions, pictures, a voice note, an animated GIF, a file,
    and a link preview.
  - One Instagram-style General chat and one message request, and enough chats to fill
    the pinned grid.
- **Live activity** (`DemoNetwork`): someone types, then messages, every ~25 s.
  - What you send turns Delivered, then Read (with a read receipt), and sometimes gets
    a reaction or a typed reply.
- **Runtime controls** (`DemoControls`): every capability flag, live activity on or
  off, and the timings. `FULL` and `MINIMAL` (SMS-like) presets.
- **Login** (`DemoLogin`): the QR path and the sign-in path together show every step
  kind.
  - The QR refreshes once; the emoji-match step shows 🦊.
  - Code "000000" is refused with an error; cancelling ends in Failed.
- **`DemoSimulator`** (`demoConnector.simulate`): makes someone message, type, or react
  on command, for UI tests.
- **Media** (`DemoMedia`): pictures (PNG), the GIF, and the voice note (WAV) are
  generated in code, so the demo never touches the internet (CLAUDE.md rule 8). Tests
  decode each with the JDK's own decoders.

Tests: 31, all passing.
- `ConnectorContractTest` runs twice: `DemoContractTest` with every capability, and
  `DemoMinimalContractTest` with the minimal set, so every "unsupported" path runs too.
- Plus login, media, and network tests.
- A deliberate break (typing accepted while switched off) was caught by the minimal
  contract run, then reverted.

**Decisions**
- The demo's in-app web page is `about:blank`, so no website is contacted.
- detekt: `DemoMedia` suppresses `MagicNumber` for the whole file, since a byte-level
  PNG/GIF/WAV writer's numbers are the formats' fields. The connector is split into
  `DemoConnector` (the contract), `DemoNetwork` (the pretend server), and
  `DemoSimulator`, to keep each class a readable size.
- There is no screen yet to add a demo account. The setup flow is P2.7, so a debug
  build still opens to the blank screen until Phase 2.

**Next:** P1.6, Phase 1 acceptance (the migration test harness remains).

## P1.6 Acceptance for Phase 1 (done, 2026-09-30)

Every item on the plan's list passes:
- **Unit tests for the unread rule** (`UnreadRuleTest`, 7 tests), **merge-link
  logic** (`ContactStoreTest`: confirm, look up by contact or person, remove), and
  **every DAO** (through the repositories, in `core/store`).
- **The contract test passes against the demo connector**: `DemoContractTest` and
  `DemoMinimalContractTest`, 11 checks each.
- **Schema exported** (`core/store/schemas/.../1.json`) **and a migration test harness**
  (`MigrationTest`, with the migrations list still empty):
  - Every schema version from 1 to current must have its exported schema.
  - Every version must upgrade through `PingMeDatabase.MIGRATIONS` to exactly the
    current schema.
  - A migrated database must open in Room with search working.
  - Checked by bumping the version with no migration: two of the three tests failed.

Totals: 108 unit tests, all passing (model 5, store 43, connector API 4, service 25,
demo 31). `./gradlew check`, `assembleDebug`, and `assembleRelease` pass.

**Implementation notes**
- `PingMeDatabase.VERSION` is the one place the schema version lives.
- The exported schemas become unit-test assets through AGP 9's variant API. The old
  `sourceSets` accessor fails on AGP 9's new DSL.

Phase 1 has no gate. **Next:** Phase 2, the UI against the demo connector, starting
with P2.1 (theme). Gate G1 at the end of Phase 2 is the owner's first real look at the
app.

## P2.1 Theme (done, 2026-09-30)

`core/ui/theme`:
- **`PingMeTheme(appearance)`**: wraps `MaterialExpressiveTheme(colorScheme,
  motionScheme, shapes, typography)`. It also provides what Material has no slot for,
  read through `PingMeTheme.*`: the shape family's polygons, the network palette, the
  message text style, the effective motion intensity, and emphasized headline and
  chat-title styles.
- **`Appearance`**: every theme choice, serializable to JSON for theme export and
  import (P2.2).
  - **Colour source:** Dynamic (Material You), Seed, one of 8 presets, or Manual.
  - **Mode:** Light, Dark, or Follow system; plus AMOLED black and contrast
    Standard/Medium/High.
  - **Motion:** Off, Subtle, Full, or Extra. Off uses `MotionScheme.standard()` and the
    rest `expressive()`; the system "remove animations" setting forces Off.
  - **Shapes:** the Round, Soft, Sharp, or Expressive family.
  - **Fonts:** separate UI and message fonts, text size and line height, emphasized
    headlines, and per-network colour overrides.
- **Colour** (`ColorSchemes.kt`): MaterialKolor 5.0.1 (Apache-2.0 Kotlin port of
  Material Color Utilities) generates seed, preset, and manual schemes at each contrast
  level, with AMOLED black surfaces. Dynamic colour uses Android's wallpaper scheme; on
  Android 10 and 11 it falls back to the PingMe seed.
- **Network palette** (`NetworkPalette`, UI_DESIGN.md 10.1):
  - Signature colours come from the mockup: WhatsApp, Google Voice, Signal, Telegram,
    Messenger (the Facebook Page inbox shares it), Instagram.
  - Light mode is a soft pastel with dark text; dark mode is a deep tone with light
    text.
  - Google Messages follows the theme's primary. Its SMS fallback is the same hue with
    70% of the colour drained out, outlined. Native SMS is neutral; the demo network
    uses tertiary.
  - Incoming bubbles are the same surface everywhere, and overrides replace a network's
    colour.
- **Shapes** (`PingMeShapes`): Material component shapes per family.
  - Avatars are polygons: Circle (Round), Square (Soft), a slightly rounded square
    (Sharp), Cookie9Sided (Expressive).
  - Expressive pinned tiles cycle through cookie, clover, sunny, soft burst, and flower.
  - Each family sets a default bubble corner.
- **Type** (`Typography.kt`): Material 3's scale, including the 15 Expressive
  emphasized styles, in the chosen font.
  - Variable fonts get one instance per weight on the weight axis.
  - An imported font is checked for an `fvar` table; if it has no weight axis, bold is
    synthesized. A missing imported file falls back to Roboto Flex.
- **Fonts**: Roboto Flex, Inter, Manrope, Nunito, Lexend, Atkinson Hyperlegible Next,
  and JetBrains Mono, all variable, in `core/ui/src/main/res/font/` (3.5 MB). Their OFL
  licences are in `licenses/fonts/`. Downloaded once from github.com/google/fonts; never
  fetched at runtime.
- `ThemeSample` with two `@Preview`s. The app's placeholder screen now uses
  `PingMeTheme`.

Tests (12, all passing):
- **Network palette:** every outgoing and incoming bubble meets 4.5:1 across 8 presets
  × light/dark × AMOLED × 3 contrast levels. Light tints are light and dark tones deep.
  The greens (WhatsApp vs Google Voice) and blues (Signal vs Telegram) stay apart in
  hue and lightness. The SMS fallback is less colourful and outlined. Overrides work.
- **Colour schemes:** AMOLED gives black only in dark mode. Higher contrast
  strengthens text, outline, and primary contrast. Dynamic colour works.
- **Rendering:** every bundled font is variable. A Compose test renders the sample in
  every shape family × colour source, every font at 130% size, every mode, and every
  motion level. That also proves MaterialKolor works with material3 1.5.0-alpha29 at
  runtime.
- **Motion:** Off really uses the standard motion scheme.
- **Screenshot:** a Robolectric test renders the sample to `build/screenshots/theme.png`.

**Deviations and decisions**
- **Atkinson Hyperlegible Next**, not the original Atkinson Hyperlegible: the original
  has no variable version, and UI_DESIGN.md 4.4 asks for variable fonts only. Same
  design family and licence.
- **Dynamic colour at Medium or High contrast.** Android's wallpaper scheme has no
  contrast setting, so the scheme is regenerated from the wallpaper's primary colour at
  that contrast. At Standard contrast the wallpaper scheme is used exactly.
- **Screenshots as tests.** Robolectric's native graphics render Compose to real
  images, so every Phase 2 screen can be looked at here before the owner does.
- **detekt for UI code:** `MagicNumber` ignores composables, previews, enum values, and
  named declarations (dp, sp, and colour literals). The Compose rules' allowlist names
  the five theme CompositionLocals.
- New dependencies: `com.materialkolor:material-kolor` and `material-color-utilities`
  5.0.1; Compose UI test and Robolectric for `core/ui`.

**Next:** P2.2, the Appearance studio.

## P2.2 Appearance studio (done, 2026-09-30)

A home placeholder (until the inbox in P2.3) has one button, **Appearance**, which opens
the studio. Settings > Appearance moves there in P2.6.

The studio (`app/.../appearance/`):
- **Live preview** at the top: a short conversation (incoming, RCS, SMS, WhatsApp, and
  a group message with a sender name) drawn with the real bubbles, avatars, wallpaper
  and fonts. It sits outside the scrolling list, so it stays in view while you change
  things.
- **Contrast warning** under the preview when any bubble or the wallpaper falls below
  4.5:1, naming each one and its ratio (`contrastIssues` in `core/ui/theme`).
- **Every control in UI_DESIGN.md section 4**, grouped as in the document:
  - **Colour:** source (Wallpaper, One colour, Preset, Manual), with the seed picker,
    the 8 preset swatches, or the four manual key colours (secondary, tertiary and
    neutral follow primary until set); light/dark/system; pure black; contrast; bubble
    and accent colour for each network, each with Reset; sender name colours.
  - **Shape:** family, bubble corners (follows the family until moved, with Reset),
    tails, bubble style (Tonal, Outlined, Filled, Gradient, Pill).
  - **Layout:** inbox density, pinned chats (Row, Grid, Top of list), avatar size,
    avatars in chat, wallpaper (none, colour, gradient, picture with blur), timestamps.
  - **Fonts:** UI and message font, each chip drawn in its own font, plus **Import** for
    any .ttf or .otf (copied into app storage); text size; line height; emphasized
    headlines.
  - **Motion:** intensity, with a note when the phone's "remove animations" setting
    overrides it; haptics.
  - **Icon and shortcuts:** five app icons (Default, Light, Dark, Sunset, Forest) shown
    as the real launcher icons; swipe right and swipe left actions.
  - **Theme file:** export to JSON, import from JSON, reset everything.
- The colour picker is a bottom sheet with hue, colourfulness and lightness sliders (HCT,
  so lightness stays even as the hue changes) and quick swatches.
- **Storage:** the appearance is saved as JSON in the settings store and applied to the
  whole app at once (`ThemeViewModel` in `MainActivity`). The theme file is the same
  JSON. Unknown keys are ignored, so a file from a newer version still loads; a broken
  file leaves the theme untouched and says so. An imported font or picture whose file has
  gone falls back to the default.
- **App icons:** five `activity-alias` entries in the manifest, one enabled at a time.
- **Icons:** 84 Material Symbols Rounded drawables in `core/ui/res/drawable`
  (Apache-2.0, `licenses/material-symbols-LICENSE.txt`), for this and later screens.
- Navigation uses type-safe Navigation Compose routes (`PingMeNavHost`).

Tests (14 new, all passing):
- **Theme file:** every setting survives a save and load; a broken file gives the
  default look; a file with unknown keys loads; missing fonts and pictures fall back.
- **Contrast check:** the default look passes in light and dark; a mid-tone Filled
  bubble and a white wallpaper in dark mode are flagged.
- **Studio (Compose UI tests):** choosing Dark changes the look at once; the warning
  shows for a bad combination and not for the default; Reset everything is reachable.
- **App icon:** every variant has a launcher entry pointing at the app; switching
  leaves only the chosen one on.
- **Screenshot:** `app/build/screenshots/appearance.png`.

**Deviations and decisions**
- **Per-chat overrides** (bubble colour, wallpaper, font size, conversation shortcuts,
  chat bubbles) are in Chat details, P2.5; the studio sets the app-wide defaults.
- **Choice labels shrink to fit** instead of being cut off, so "Expressive" and "One
  colour" fit on a phone.
- Tidied for detekt and the Compose rules: `MessageBubble`'s slot above the text is now
  named `header`; `BubbleStyling.kt` is `StyledBubble.kt` and `ContrastCheck.kt` is
  `ContrastIssue.kt`.
- New dependencies in `app`: Navigation Compose, Hilt ViewModel for Compose, lifecycle
  Compose, kotlinx.serialization JSON, MaterialKolor utilities (for HCT); Robolectric and
  Compose UI test for `app` tests. `app` host tests now load the bundled SQLite natives.

**Next:** P2.3, the inbox.

## P2.3 Inbox (done, 2026-09-30)

The inbox is the home screen now (`app/.../inbox/`), built to the plan's list and the
mockup in `docs/mockup/Main.dc.html`:
- **Top bar:** small wordmark, status pill, search, and the avatar menu. Inset by the
  status bar only.
  - The pill reads Connected, "RCS reconnecting" (pulsing dot), or "RCS needs
    attention". With a demo account, tapping it cycles through the three.
  - The menu: Settings, Appearance, Notifications, Accounts, Archived, Low priority,
    Requests, General, each space, and Edit bottom bar. Lists show their counts.
- **Health chip** under the bar, only while an account is reconnecting or needs
  attention. "Tap to fix" opens the app or page the account's problem points at.
- **Pinned chats:** a grid of five per row (the default), a sideways row, or at the top
  of the list, per the Appearance setting. Tiles use the shape family's pinned shapes,
  with unread badges and typing dots. Up to 12 pins; pinned chats leave the list.
- **Rows:** avatar with typing dots, name, mute icon, network badge (RCS or SMS by the
  last message), "General" tag, time, preview ("You: ...", "Priya: ..." in groups,
  "Photo" and so on, "New message" when obscured), unread badge. Density and avatar
  size follow Appearance.
- **Swipes** (`AnchoredDraggable`): each direction does what Appearance says. The
  coloured layer underneath names the action, a haptic tick marks the point of no
  return, and the row springs back. Screen readers get the same actions.
- **Press and hold** (`combinedClickable`) on a row or tile: the action sheet (Pin,
  Mark read, Mute, Archive, Low priority, Obscure, Delete). Every action works both
  ways (unpin, unarchive, ...).
- **Undo:** Archive and Low priority can be undone from the snackbar. Delete hides the
  chat at once and only deletes once the snackbar has gone.
- **Bottom bar** (`ShortNavigationBar`): All, then up to four of Unread, networks,
  spaces, and Low priority, with unread badges from the one counting rule.
  - Long-press a network to narrow it to one account (when there are several) or, for
    Instagram, Primary or General. The choice is remembered.
- **+ menu** (`FloatingActionButtonMenu`): New chat and New group.
- **Flippy reactions:** when someone else reacts, the row flips to show the emoji for
  900 ms, at Full and Extra motion.
- **Wide screens:** `NavigableListDetailPaneScaffold` puts the chat beside the list, and
  a `WideNavigationRail` replaces the bottom bar.

Also built here, because the inbox's buttons lead to them and no other step makes them:
- **Lists from the menu:** Archived, Low priority, Requests (with Accept, Decline,
  Block), General, and each space.
- **Search:** chat names and message text (full-text index) across every network.
- **New chat and New group:** pick an account, type a number or name or pick someone
  known.

Underneath:
- Store: the newest message of every chat (for previews), and a few one-time queries.
- Service: `ChatActions` (pin up to 12, reorder, read with a read marker to the
  network, mute, archive, low priority, obscure, delete, start chat, make group,
  answer requests, block) and `ReactionFeed` (other people's reactions, live).
- The bottom-bar choices are saved as JSON in the settings store.

Tests (31 new; 161 in the app and changed modules, all passing):
- **Filtering:** pins leave the list in order; Unread skips read and muted chats;
  network filters and narrowing, including Instagram folders; spaces; hidden deletes;
  badges; the default bar; time labels.
- **Chat actions:** pin limit and order; read marker sent; archive and low priority
  unpin; mute, obscure, delete; only other people's reactions flip.
- **Inbox UI against the demo network:** requests stay out; press and hold pins;
  swipe right marks read; swipe left archives, and Undo brings it back; the Unread
  button; the status pill and chip; the reaction flip; search and the menu.
- **Other screens against the demo network:** accept a request into Primary; open an
  archived chat; search finds a message; a new chat and a new group open.
- **Demo:** groups can be made and people blocked, and both switch off with the
  capability.
- **Screenshots:** `app/build/screenshots/inbox-light.png`, `inbox-dark.png`,
  `inbox-wide.png`. Previews for phone light, phone dark, and tablet.

**Deviations and decisions**
- **Connector API:** added `createGroup` and `block`, with `createGroup` and `block`
  capabilities. DESIGN.md 6.2 says connectors must create groups, and UI_DESIGN.md 6.4
  gives requests a Block button, but the P1.3 interface had neither. The demo network
  does both; the real networks come later.
- **No Scan QR.** The owner removed it from the + menu (2026-09-30); UI_DESIGN.md and
  BUILD_PLAN.md are updated to match.
- **Settings, Notifications, Accounts** in the menu are greyed out until P2.6 builds
  them.
- **Opening a chat** shows a stand-in with the chat's name until the chat screen
  (P2.4, next).
- **All** is always the first bar button, so the user picks up to four more (five in
  all, as the mockup shows).
- **Delete chat** removes it from this phone only; the network keeps its copy.
- **Network marks:** no brand logos are bundled, so a network's bar icon is its short
  name on its colour.
- The "Flippy reactions" switch itself arrives with Settings > Motion (P2.6); until
  then flips follow the motion level.
- **The phone build shows an empty inbox** until account setup (P2.7) can add the demo
  account. The tests add it directly.
- **detekt:** `TooManyFunctions` no longer counts functions a class must implement from
  an interface (every connector implements all 20 `Connector` calls).
- **Tests:** UI tests that wait on database work run the main thread's queue while
  waiting, as a phone does. Tests that open the database share one Robolectric graphics
  mode, because the SQLite library can only be loaded once per test run.
- New dependencies in `app`: `adaptive-layout` and `adaptive-navigation` 1.3.0.

**Next:** P2.4, the chat screen.

## P2.4 Chat screen (done, 2026-10-01)

Built in parts on PR #6, each part green before it was pushed.

- **Part 1:** header, message list with grouping, tails, day separators, the
  new-messages divider, scroll-to-bottom pill, pinned banner, reply strip, composer.
  Pinned messages got their own table (schema version 2, with a migration).
- **Part 2:** press and hold (reaction bar and action card), double-tap reaction, the
  emoji picker (Unicode emoji-test 18.0, licence in `licenses/`), Reaction Burst at each
  intensity, swipe right to reply, delete for me (with Undo) and for everyone, edit,
  pin, forward, copy, share, info, and multi-select. `Connector` gained `edit`.
- **Part 3a, attachments:** Camera, Gallery, File, Location, Contact; picks wait above
  the composer and go with the text as caption; upload progress on the bubble
  (`Connector.send` gained a progress callback); split send button with Send as SMS
  in Google Messages chats.
  - Location uses the phone's own location service and is sent as a GeoJSON point;
    the bubble shows the coordinates and opens the maps app. No map image is drawn,
    because that would need a map server.
- **Part 3b, voice notes:** hold to record, slide left to cancel, slide up to lock;
  playback with waveform, scrubbing, 1x / 1.5x / 2x and the earpiece when raised;
  Voice reply in the action sheet.
  - Networks that send voice notes as MMS record at 24 kbit/s instead of 64, so about
    five minutes fits under 1 MB. A note still over 1 MB asks "Send anyway" or Cancel.
- **Part 3c, GIFs:** GIF button with search, Trending, and favourites; keyboard GIFs
  (Gboard) through the text field's content receiver; MMS size warning with Shrink.

**Deviations, decided with the owner:**
- **GIPHY instead of Tenor.** Google shut the Tenor API down on 30 June 2026. The owner
  chose GIPHY and supplied a key. The provider is pluggable (`GifProvider`).
  - The key is never committed. CI reads the `GIPHY_API_KEY` secret; local builds read
    `giphy.apiKey` from `local.properties`. Without a key the picker offers favourites
    only and says search is not set up.
  - **Owner to-do:** add the `GIPHY_API_KEY` repository secret (steps below), or CI
    builds will have no GIF search.

**Deviations, my calls (owner please confirm):**
- GIPHY's optional analytics pings and user ids are not sent (CLAUDE.md rule 8). The
  picker shows "Powered by GIPHY" under online results.
- Nothing goes to GIPHY until the user types a search or taps Trending. The picker opens
  on Favourites.
- Shrink is offered only for GIPHY GIFs, which come in a smaller size. A GIF from the
  gallery or the keyboard over the limit gets "Send anyway" or Cancel; re-encoding a
  GIF on the phone is not built.
- Three settings arrive with Settings in P2.6, since there is no settings screen yet:
  turning online GIF search off, the data-saver "play GIFs on tap", and voice-note
  transcription (off by default). Until then GIFs autoplay and search is on when the
  build has a key.

**Adding the GIPHY key to GitHub (owner):** Settings > Secrets and variables > Actions >
New repository secret. Name `GIPHY_API_KEY`, value the key. Save.

**Tests:** chat tests turn the demo network's live activity off, and wait by moving the
main thread's clock on, so the demo's pretend upload finishes. A fake microphone and a
fake GIF store stand in for the hardware and the network.

- **Part 4a, search in chat:** type chips, sender filter in groups, date jump; results
  jump to the message with the highlight pulse, loading history back to it first. New
  `ChatSearchRepository` in the store.
- **Part 4b:** obscured chats (blur until tapped, blurred again after 5 seconds,
  `FLAG_SECURE`, inbox preview hidden); Send later (sheet with quick chips, date and time
  picker, and a phrase reader); tappable links and preview cards; forwarding now carries
  the files as well as the text.
  - Android 10 and 11 cannot blur, so an obscured bubble is covered with a solid colour
    there instead.
  - Send later wakes through a delayed background job (`SendAlarm`) until P4.2 adds the
    exact alarm; Android may run it a few minutes late. A message due while offline goes
    once the phone is back online. The "sent late" notice comes with notifications (P4.1).
  - The phrase reader understands English only for now.
  - Obscured chats' notifications are hidden with notifications themselves (P4.1). The
    switch to turn obscuring on lives in Chat details (P2.5).
  - Links in text are tappable and show the cleaned address when the message carries
    one. Cleaning links with the ClearURLs rules is P4.3.

**Owner decisions recorded for later steps:**
- **Link previews (for P4.3):** the owner chose that PingMe fetches previews from the
  phone when the network sends none, with the "Generate link previews" setting offering
  Always, Only on Wi-Fi, and Never. This is an allowed exception to CLAUDE.md rule 8.

**Next:** P2.5, Chat details.

## P2.5 Chat details (done, 2026-10-01)

Reached from the chat header (tap the name, or the menu's Chat details). Sections, per
UI_DESIGN.md 3.4:
- Header: photo, name with a pencil to change it here only, the network, the contact
  card (contacts provider lookup) or "Add to contacts", and Search, Mute, Pin.
- Media, Links, and Files tabs with the newest twelve; "See all" and the Search button
  go back to the chat with search in chat open on that type.
- Notifications: mute for 1 hour, 8 hours, 1 week, or until turned back on; this
  chat's sound (the system ringtone picker or an audio file, kept with a persistable
  read permission) and vibration (six patterns).
- Look: this chat's outgoing bubble colour, bubble style, wallpaper, and text size,
  over the app's appearance; "Use the app's look" resets it.
- Quick reactions: this chat's own set of up to eight, or the app's.
- Members (groups), pinned messages (tap to see one in the chat, or unpin), the photo
  source (contact or network), Obscure messages, Low priority, the Instagram folder,
  Archive, Block (where the network allows), and Delete, each final one asked first.

New: `chat_overrides` table (schema 3, migration from 2), `ChatOverridesRepository`,
`ChatLook` in core/ui, `ChatActions.muteUntil / rename / setAvatarSource / moveFolder`,
and `ChatRequests`, which carries Chat details' search and jump requests back to the
chat underneath.

**Deviations and what comes later:**
- **Merge and split** are not in Chat details yet. The plan builds merged chats in
  Phase 7 (a merged chat is a view over several chats), and there is nothing to merge
  or split before then. The merged chat's details (photo choice across networks, name,
  default service, split) come with it.
- This chat's sound and vibration are stored with a channel version now; they take
  effect when notifications are built (P4.1), which creates the per-chat channel.
- The photo choice is stored now; contact photos themselves arrive with contact
  matching (Phase 7), so until then every avatar is the initials tile.

**Fixed along the way:**
- **P1.4:** the connection supervisor could miss a network change that came just
  before its reconnect wait began, and sit out the whole backoff. Changes are now
  counted; a new test covers the gap.
- **Demo network:** with live activity off it no longer wakes every second to check;
  it waits until live activity is turned on.
- **Tests:** chat and inbox UI tests move the main thread's clock on while they wait
  (CI failed once without it). The Chat details tests run on Robolectric's default
  screen, because with a set screen size a text field in a dialog never settles there.

**Next:** P2.6, Settings.

## P2.6 Settings (done, 2026-10-01)

Settings opens from the avatar menu (Settings, or Accounts straight to that page). Every
page in the plan is there:
- **Accounts:** each account's name, badge colour, notifications (on, silent, off),
  Show in inbox, Instagram's Show General, and connection state.
- **Appearance:** the studio from P2.2.
- **Notifications:** each network's on, silent, or off, with its own sound and
  vibration; Instagram's Primary, General, and Requests rows (on, silent, off by
  default); keywords (word or phrase, whole word, capital letters, all chats or some
  accounts or some chats, Override Low priority and mute, own sound and vibration);
  and Auto-copy one-time codes (off by default). The sound and vibration rows are now
  shared with Chat details (`app/notify/SoundRows.kt`).
- **Privacy:** read receipts and typing, each with per-network exceptions (both are
  honoured now: turning them off sends nothing to the network); clean links sent and
  received; link previews Always, Only on Wi-Fi, or Never (owner's choice A); and the
  list of obscured chats, each with a way to stop.
- **Reactions:** the quick set (move, remove, add, up to eight), the double-tap
  reaction, and special emoji that also glow at Extra motion (honoured now).
- **Motion:** intensity and haptics from the studio, and Flippy reactions (follows the
  motion level until set; honoured in the inbox now).
- **Media and storage:** GIF search (off: favourites only, nothing asked of GIPHY;
  honoured now), GIF autoplay (off: a GIF stays still until tapped; honoured now),
  voice-note transcription, Save all incoming media, and the folder to save to.
- **Spaces and the bottom bar:** the networks' spaces and the user's own, made from any
  chats on any account, changed, or deleted (a deleted space leaves the bar too); and
  the bottom-bar editor from P2.3.
- **Backup:** save the chats to a file the user picks, or restore from one. A backup is
  a `VACUUM INTO` copy of the database. A restore checks the file is a PingMe database
  no newer than the app, clears media paths that do not exist on this phone, swaps it
  in, and restarts PingMe. Logins stay in the keystore and are not in a backup, so after
  a restore each network asks to log in again; the page says so.

New: `AppSettings` (core/model) in the settings store, `SettingsViewModel` with
`SettingsActions`, `SpaceActions`, and `BackupActions`, `MediaKeeper` (core/service),
`BackupStore` (core/store), `MessageRepository.setAttachmentSavedAt`.

**Deviations and what comes later:**
- **Built early:** "Save all incoming media" works now rather than only being stored.
  New media downloads as it arrives and each file is copied once to the chosen folder
  (or kept in app storage). The plan puts this in P4.3, which also names `MediaStore`
  for a public folder; P4.3 will review this against that. Ephemeral media is saved
  wherever the network delivers it, which is the demo network's normal path.
- **Log in and Log in again** on an account's page come with the login flow in P2.7,
  which renders the connector's `LoginStep`s; the account page gets the button then.
- **Voice-note transcription:** the setting is stored, but the transcription itself
  (UI_DESIGN.md 5.6, Android's on-device speech recogniser) was not built in P2.4. It is
  a gap; it will be built before Gate G1 as part of P2.8.
- Stored now, take effect later as the plan says: network, folder, and keyword sounds
  and keyword matching, and auto-copy codes (P4.1); link previews and clean links (P4.3).

**Also noted:** the plan names spaces and the bottom-bar settings again in Phase 7, and
backup again in Phase 8 ("database and settings ... encrypted"). P2.6 builds what P2.6
lists; Phase 8 adds the settings and encryption to the backup.

**Next:** P2.7, Setup flow.

## P2.7 Setup flow (done, 2026-10-01)

PingMe opens on setup until setup has run once. One decision per screen, with a step
count and a wavy progress bar:
1. **Texting mode:** Google Messages mode (marked Recommended) or Native SMS mode, each
   explained in plain words; "you can change this later".
2. **Notifications** (Android 13 and later only), **Stay connected** (the battery
   optimisation exemption; says so if it is already allowed), and **Contacts**: each says
   why, then Allow or Not now.
3. **First network:** every network this build has, each with its risk in plain words
   (DESIGN.md 7). Tapping one opens its login; "Set up later" goes to the inbox.

The **login screen** draws any connector's `LoginStep`s: choices, a QR code (ZXing,
dark on white, with "Show it on another screen" through the share sheet), text entry
(phone keyboard, code keyboard, hidden passwords and tokens, the connector's error),
the network's own sign-in page in a WebView (its cookies for the named domains go back
to the connector), the wait for confirmation (the Expressive shape-morphing indicator,
with the matching emoji at display size), and failure with Try again. When it finishes
the account is saved and starts connecting, and setup is done.

The same screen is used from **Settings > Accounts > Add account** (a network picker
with the same risk lines) and an account's **Log in again**, which keeps the account and
its chats and only replaces the login.

New: `TextingMode` and `setupDone` in `AppSettings`; the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
permission; library `com.google.zxing:core` 3.5.4 (Apache-2.0).

**Deviations and what comes later:**
- **Native SMS mode** is stored but does nothing yet: becoming the default SMS app needs
  the SMS connector (Milestone 2, Phase 5 in the plan), which brings the role request.
- The Google Messages checks (installed, default, RCS on) and the offer to silence Google
  Messages' own notifications are in P3.2 and P4.1, with that connector.
- No icon for battery ships in the icon set, so Stay connected uses the sync icon.

**Next:** P2.8, acceptance for Phase 2.

## P2.8 Acceptance for Phase 2 (done, 2026-10-01): stopped at Gate G1

Builder side, all verified with `./gradlew check`:
- **Every screen has a preview**, and a test draws each one (`PreviewsTest`, 18 previews):
  chat (light and dark), chat details, the archived list, new group, search, the
  Appearance studio, every Settings page, the setup pages, and the login steps. The
  inbox already had its own.
- **Every screen has a UI test against the demo network:** inbox, lists, search, new chat
  and new group, chat (voice, attachments, GIFs, reactions, actions, search in chat,
  send later, obscure, transcripts), chat details, Appearance, every Settings page, setup,
  and login.
- **Voice-note transcription**, the gap left in P2.4, is built: with Settings > Media and
  storage > "Write out voice notes" on, the words show under each voice note. It uses
  Android's on-device recogniser on the decoded note, needs Android 13 or newer, and keeps
  each transcript on the phone. Phones that cannot do it say so under the note.
- **A demo build to install.** Debug builds now carry the app id `org.pingme.app.demo` and
  the name "PingMe Demo", so they install beside the real app instead of over it, and CI
  signs them with the sideload key so each new one updates the last. CI uploads it next to
  the real APK as `pingme-<commit>-demo.apk`.

**Fixed along the way:**
- **Typed text could be lost** in four text boxes (new chat and new group, search, search
  in chat, GIF search). Each showed a value that came through a flow, and a redraw with an
  older value between keystrokes could wipe what was typed. The new-group UI test caught
  it as a rare timeout; a dump of the screen when it stuck showed the group name empty.
  The typed text now lives in Compose state that the box reads at once. Repeating the test
  40 times under a full check passed after the fix (before it, most runs failed once).

**Gate G1, the owner's turn.** The builder cannot do this part.

What you are checking: does every screen look and work the way `UI_DESIGN.md` says? It
all runs on the pretend "Demo" network, so nothing real is sent anywhere.

*Install it*
1. On GitHub, open Actions, then the newest green "build" run on `main`. Under
   Artifacts, download the file whose name ends in `-demo.apk` onto your phone.
2. Open it and tap Install. It shows up as **PingMe Demo**, next to your normal PingMe.

*Setup (first time you open it)*
3. You see "How should texts reach PingMe?". Google Messages mode is marked
   Recommended. Pick one and tap Next.
4. Notifications, Stay connected, Contacts: each says why it wants this. Try Allow on
   some and Not now on others.
5. "Connect your first network": tap **Demo**. Try both ways to log in:
   - "Scan a QR code": a QR code shows, changes once, then it finishes.
   - "Sign in": type any phone number. For the code, type 000000 first (it says the code
     didn't work), then any other code. A blank web page shows; tap "I have signed in".
     A fox 🦊 shows big, then it finishes.
   (To try the second way, go to Settings > Accounts > Add account > Demo.)

*Look around, comparing with UI_DESIGN.md*
6. **Inbox (3.1):** pinned chats on top, unread counts, the bottom bar, the avatar menu,
   the + menu. Swipe a chat both ways. Press and hold a chat. Try search.
7. **A chat (3.2, 5):** bubbles and grouping, press-and-hold for reactions and actions,
   double-tap to react, reply, edit, delete, select several. Hold the mic to record a
   voice note and play one back (Mom's chat has one). Attach a photo, a file, your
   location. Long-press Send for Send later. Search in the chat.
8. **Chat details (3.4):** tap the chat's name. Mute, sound, look, reactions, obscure,
   archive, block, delete.
9. **Settings:** every page. Turn on "Write out voice notes" (Media and storage) and open
   Mom's chat: the words of her voice note appear under it, or a line saying your phone
   can't.
10. **Appearance (4):** colours, shapes, fonts, wallpaper, motion, app icon.
11. If you have a tablet or a foldable, open it wide: the list and the chat sit side by
    side.

*Known and expected for now (not bugs)*
- No notifications pop up yet (that is Phase 4).
- Only the Demo network exists. Real networks start in Phase 3.
- Photos are initials, not contact pictures (Phase 7).
- Native SMS mode is saved but does nothing yet (Phase 5).
- GIF search needs the `GIPHY_API_KEY` repository secret on GitHub. Without it the GIF
  button shows favourites only.
- Link previews and clean links are settings only for now (Phase 4).

*Send back*
- Anything that looks or works differently from `UI_DESIGN.md`, with the screen's name,
  and a screenshot when you can.
- Anything confusing, slow, or broken.

**Next:** Phase 3 (P3.1, the Go bridge) once the owner's Gate G1 feedback is in.

## Gate G1 result (2026-10-01): feedback collected, fixes not started

Owner tested `pingme-7527944-demo.apk` on the phone. Overall: the UI works well and is
close to right. The owner added the `GIPHY_API_KEY` repository secret; GIF search needs
a build made after that (any new CI build).

What worked, confirmed by the owner: setup and the demo login, the inbox, chats,
Settings, Spaces, Send later, obscured chats (screenshots are blocked, as designed), and
a notification arrived from the demo network.

**Feedback, sorted.** "Change to the design" means `UI_DESIGN.md` says otherwise today,
so the owner approves the new wording before it is built (rule 2).

Fixes to what was built (the design already says or allows this):
1. **Bottom bar items sit left of centre.** Layout fix.
2. **Inbox top bar has no background**, so rows slide up behind its text when
   scrolling. Give it a surface colour with rounded bottom corners, like Google Messages.
3. **Press and hold on a message: the reactions bar and the action menu overlap**, so
   one cannot be used. Lay it out like Google Messages: reactions above the message, the
   held message itself, and the action menu below it.
4. **The mic seems to do nothing on a tap.** By design a voice note is recorded by
   holding the mic (5.6), and a quick tap should say "too short". Seeing nothing at all
   needs checking on the phone; a tap should also say "Hold to record".
5. **Motion: Full and Extra look the same as the lower levels.** Needs checking: Full
   should add the celebrate burst to reactions and Extra the edge glow for special emoji
   (5.4), plus livelier transitions elsewhere.
6. **A scheduled message's press-and-hold menu** should offer Edit, Reschedule, and
   Unschedule (delete). 10.13 already says a scheduled message stays editable and
   cancellable; Reschedule is a small addition the owner asked for.

Changes to the design (owner to approve):
7. **One Send button, not a split button.** Tap to send; press and hold for Send later
   (and Send as SMS where it applies). Today the design names the Expressive split button.
8. **"All" may be removed from the bottom bar.** Today the design keeps All always first.
9. **Spaces get an icon** chosen by the user, for the bottom bar, and a choice per space:
   its chats also show in All, or only inside the space.
10. **Read receipts in the inbox list**, as Google Messages shows them: the last message's
    sent, delivered, or read mark on the row when it is yours.

Already planned for a later phase:
11. **Reply and Mark read from a notification.** Designed in 6.2 and built in P4.1 with
    the rest of notifications; P4.1 should also add Copy code (10.6) there.

Questions back to the owner:
- **Flippy reactions** (10.8): when someone reacts to a message while you are on the
  inbox, that chat's row flips over like a card to show the reaction for a moment, then
  flips back. The demo sends reactions only now and then, so it is easy to miss; a
  demo control to send one on demand would make it testable.

Low priority, after the whole app works (owner's call):
12. **The obscure blur should follow the bubble's shape**, or blur only the text inside
    it, as Beeper does. Today it is a square-cornered mask.

**Next:** the owner approves items 7 to 10 (or changes them), and says whether items 1 to 10
are fixed now, before Phase 3, or folded into later phases. Phase 3 waits for that answer.

### Gate G1 follow-up (2026-10-01): owner's answers and what was found

Owner decisions:
- Items 1 to 10 above: all approved as written. Fix them now, before Phase 3.
- **Voice notes become WhatsApp-style (changes UI_DESIGN.md 5.6; approved):** tap the mic
  to start recording; the composer becomes a recording bar with a timer, a waveform, a
  delete (trash) button, Pause/Resume (paused, the recording so far can be played back),
  and Send. Holding the mic still records while held: release sends, slide left cancels,
  slide up locks into the same bar. The owner's reference recording of WhatsApp shows it.

Found by investigation (no code changed yet):
- **Mic does nothing (reproduced in Robolectric with a finger-like hold).** The mic's
  `FilledIconButton` has its own click handling, which takes the touch first; the
  hold-to-record `pointerInput` waits for an unconsumed down (`awaitFirstDown()`) and never
  sees it. The voice tests never pressed the button: they called `VoiceNotes` directly.
  Fix with the new design, and test with real presses (tap, hold, slide).
- **Reacting from the hold menu by touch did nothing in Robolectric:** the action menu
  is drawn over the reaction bar (item 3), so a tap lands on the menu. A tap through the
  emoji's click action saves the reaction and plays the burst. On the owner's phone the
  visible emojis do work, so the overlap is the item 3 layout bug, not the missing burst.
- **No burst on the owner's phone, double tap included.** The owner confirms the reaction
  saves, its chip appears, and the bubble wobbles. The wobble is fired by the burst layer
  when the flying emoji lands, so the burst's timeline runs on the phone but nothing is
  seen. "Remove animations" is off on the phone. In Robolectric the same code draws a
  large, visible burst. Cause not yet found: needs the real phone (screen recording, or
  adb with logs).
- **Motion levels:** UI_DESIGN.md 4.5 says intensity governs reactions, bubble entrance,
  and transitions. Only the reaction burst reads Subtle/Full/Extra today; everything else
  checks only Off or not. This narrowed the design without a log entry; it is to be built.
- **Extra's edge glow** did not show in the Robolectric frames either; to be found.

**Moving off the cloud:** the cloud machine has no hardware virtualization, so it cannot
run an Android emulator, and it cannot reach the owner's phone. The owner is setting up
Claude Code on their Windows 11 desktop (WSL2 Ubuntu, same toolchain as CI) with the phone
paired over wireless debugging (adb), so builds can be installed on the phone and checked
with screenshots, screen recordings, and logcat. The next session starts by reading
CLAUDE.md, the design docs, and this log, then fixes items 1 to 10 plus the mic and motion
work above, testing each on the phone.
