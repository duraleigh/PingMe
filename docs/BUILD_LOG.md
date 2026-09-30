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
