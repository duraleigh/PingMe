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
- Talk to the owner in plain, simple language: short steps, no jargon. Aim for a
  fifth-grade reading level; explain any technical word the first time.
- Never generate code the owner has not approved. Plan steps are approved by the plan;
  anything new, or any change to the design, is proposed first and built only after the
  owner says yes (CLAUDE.md rule 11, and the owner's own standing preference).
- Never sit silently while something runs. Say what is running, about how long it takes,
  and do other useful work meanwhile (added 2026-10-01).
- Keep test runs short: run only the affected tests while working; run the full
  `./gradlew check` once before each commit (about 2 to 4 minutes). **The exact time
  limits, the 60-second progress checks, and what counts as frozen are CLAUDE.md
  rule 12; follow it to the letter** (added 2026-10-01, after a frozen run went
  unnoticed for about 20 minutes).
- The GIPHY API key is never committed (the repository is public). It lives in
  `local.properties` as `giphy.apiKey` (gitignored) and in the `GIPHY_API_KEY`
  repository secret for CI; the owner added that secret on 2026-10-01.
- The owner's phone is theirs to use. Test on the emulator; ask before using the phone,
  and only when the owner offers it (added 2026-10-01).

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


## Handover: from the cloud session to the owner's computer (2026-10-01)

**Why the move.** Two bugs from Gate G1 (the mic, and the reaction burst that never shows)
pass in Robolectric but fail on a real phone. The cloud machine cannot run an Android
emulator (no hardware virtualization) and cannot reach the owner's phone, so it could not
see them. The owner's computer can do both.

**The owner's computer (set up and checked 2026-10-01).** Windows 11 desktop (Intel Core
Ultra 9 285, 64 GB RAM), running Ubuntu 24.04 in WSL2 with `networkingMode=mirrored` and
`nestedVirtualization=true` in `C:\Users\ca\.wslconfig`. In Ubuntu (user `clayaiken`):
- The project is at `~/PingMe`, cloned with `gh` (signed in as duraleigh, so pushing works).
- JDK 17, Go 1.24.7 (in `/usr/local/go`), and the Android SDK in `/opt/android-sdk` from
  `scripts/setup-cloud.sh` (the name says cloud, but it is the same toolchain).
  `adb version` works.
- `local.properties` has `giphy.apiKey`.
- QEMU/KVM are installed, `clayaiken` is in the `kvm` group, and `/dev/kvm` exists.
  **No emulator exists yet**: the first job is to install the emulator package and an
  x86_64 system image with `sdkmanager`, create an AVD (match the owner's phone if they
  say which), and boot it; WSLg shows its window on the desktop.
- The owner drives Claude Code from the Claude desktop app with the WSL > Ubuntu-24.04
  environment and the `~/PingMe` folder.

**Where things stand.** Phase 2 is built and merged (P2.1 to P2.8). Gate G1 is *not*
passed: the owner's feedback (sections above) must be fixed and re-checked first. All
design changes the owner approved are now written into `docs/UI_DESIGN.md` (send button,
inbox top bar, read marks in the inbox, bottom bar centring and removable All, the hold
menu layout, scheduled message actions, voice notes, spaces' icon and "show in All").
Nothing of the fixes has been coded yet.

**What to do next, in order.** One small PR per group; run `./gradlew check` before each
commit; check each fix on the emulator with screenshots (and `adb shell screenrecord` for
animations) before calling it done.
1. Set up and boot the emulator; build the debug (demo) APK and install it with
   `adb install`; go through setup and the Demo login once.
2. Look at the reaction burst on the emulator first (double tap, Motion at Extra), with
   a screen recording. The wobble fires, so the burst's timeline runs; find why nothing
   is drawn on a real device, and fix it. Then make Subtle, Full, and Extra visibly
   different for reactions, bubble entrance, and screen transitions (UI_DESIGN.md 4.5),
   and fix Extra's edge glow.
3. Voice notes, the new tap-or-hold design (UI_DESIGN.md 5.6). Tests must press the
   button like a finger (tap, hold, slide left, slide up), not call `VoiceNotes` directly.
4. The hold menu layout (3.3), with a test that taps each emoji at its place on screen;
   then the scheduled-message actions.
5. The single Send button; the inbox top bar surface; the bottom bar centring; read marks
   in the inbox rows.
6. Bottom bar: All removable. Spaces: icon picker and "show in All".
7. Write a new Gate G1 checklist for the owner covering every item, build the demo APK in
   CI, and stop for the owner's re-check.

Also open, for the owner to decide: a demo control that sends a reaction on demand, so
flippy reactions (UI_DESIGN.md 10.8) can be tested; and, low priority after the whole
app works, an obscure blur that follows the bubble's shape.

**After Gate G1 passes:** Phase 3, starting at P3.1 (the Go bridge).

**Lessons from this phase, for writing tests here.** Robolectric's main looper needs
`shadowOf(Looper.getMainLooper()).idleFor(...)` inside waits; turn the demo's
`liveActivity` off in UI tests; click with `performSemanticsAction(OnClick)` only when a
tap's position is not what is being tested; text boxes must read typed text from Compose
state, never from a flow (see P2.8). Robolectric hid three real bugs this phase (the mic,
the hold-menu overlap, the missing burst), so anything about touch or drawing is checked
on the emulator too.

## Gate G1 fixes, session on the owner's computer (2026-10-01)

Work happens on branch `g1-fixes`, one pull request per group, merged once CI is green.

### The emulator (handover step 1)

The emulator **runs on Windows, not inside WSL**. Inside WSL it booted (nested
virtualization), but WSL offers no hardware graphics to it, and with every software
renderer (`swiftshader_indirect`, `swangle_indirect`, `guest`) screenshots and screen
recordings fail (`hasReadColorBufferDma` assertion, `Encoder failed`). The owner chose the
Windows emulator, which uses the RTX 5060.

- Windows SDK: `C:\Users\ca\AppData\Local\Android\Sdk` (emulator 37.1.11 stable, Windows
  platform-tools, and the system image copied from WSL).
- Virtual phone `pingme_api37`: Pixel 7 profile, Android 17 (API 37.0) Google Play
  x86_64 image, 4 GB RAM, 8 GB data, host GPU. Its files: `C:\Users\ca\.android\avd\`.
  Windows Hypervisor Platform was already on; nothing needed admin rights.
- Start it: `cmd.exe /c C:\Users\ca\AppData\Local\Android\pingme-emulator.bat` (from a
  Windows folder, for example `cd /mnt/c/Users/ca` first). It boots in about a minute.
- WSL's `adb` reaches it at `emulator-5554` because WSL uses mirrored networking. The adb
  server here must be started by hand (`adb nodaemon server` in the background, with
  `setsid nohup`); starting it as a daemon from the Claude shell hangs. The console needs
  `~/.emulator_console_auth_token` copied from `C:\Users\ca\`.
- `local.properties` needs `sdk.dir=/opt/android-sdk` (the Claude shell does not read
  `~/.bashrc`).
- Real finger input: `adb shell input` is too slow for a double tap (each call starts a
  Java tool) and the Play image has no root for `sendevent`. The emulator console's
  `event mouse X Y 0 1|0` is fast and exact; the builder drives taps, double taps, holds,
  and slides through it.
- Gboard shows a "Try out your stylus" tutorial over the chat; turned off with
  `settings put secure stylus_handwriting_enabled 0`.
- The WSL emulator, AVD and system image are still installed under `/opt/android-sdk`
  and `~/.android/avd`, unused.

### Reaction burst never seen on a phone (handover step 2): fixed

Cause, found with logging on the emulator: the burst canvas had **height 0**. `ChatScreen`
emitted its Scaffold and its overlays side by side, and inside the list-detail pane those
siblings are stacked like a column, so the Scaffold took the whole height and the burst
layer got none. The timeline still ran (so the reaction landed and the bubble wobbled), but
everything it drew was clipped. In Robolectric the chat sat straight in the window, which
layers siblings, so the burst showed there. Also, the burst's emoji never faded:
`drawText` ignores `alpha` unless a colour is passed.

Fix: `ChatScreen` wraps the chat and its overlays in one `Box`; the burst layer converts
screen positions into its own (it lands right when the chat pane is offset, on tablets);
emoji are drawn with a colour so they fade. `ChatLayersTest` hosts the chat in a column
and fails without the fix. Checked on the emulator with recordings at Full and Extra:
Pick, Land, Celebrate, the faint rising ghost, and Extra's red edge glow for ❤️ all show.

### Motion levels (handover step 2): built

UI_DESIGN.md 4.5 says intensity governs reactions, bubble entrance, and transitions;
before this only reactions read Subtle/Full/Extra. Now (`ScreenMotion` and
`BubbleEntrance` in `core/ui/theme/MotionLevels.kt`):

| Level | A new bubble (arriving, or one you send) | Screens and the chat pane |
|---|---|---|
| Off | just appears; lists do not animate | instant |
| Subtle | fades in | short fade |
| Full | fades in, rises 16 dp, grows from 90%, light spring | quarter-width slide and fade on the theme's expressive springs |
| Extra | pops from half size, rises 40 dp, bouncy, from the sender's corner | full-width bouncy slide that grows into place |

History and a bubble already seen never replay; your message pops once even though the
network's copy replaces the "sending" one. Tests: `MotionLevelsTest`, `EntranceTest`.
Checked on the emulator at Extra (recordings of opening a chat and of sending).

### Found while testing: a chat with no messages yet never loads its history

The inbox showed "No messages yet" on every chat after the demo login, and opening one
showed nothing. History is fetched only when the list scrolls near its top, and an empty
list has no top to reach; the background `HistoryBackfillWorker` is never scheduled by
anything. On the owner's phone messages appeared because the demo's live chatter put a
first message in.

**Fixed (owner approved both parts, 2026-10-01):**
- An empty chat asks for its history as soon as it opens (`ChatHistoryTest`, which fails
  without the fix; the test helper `DemoInbox.seed(history = false)` now starts chats empty,
  as a real login does. Every earlier UI test pre-loaded history, which hid this).
- The first sync of history (DESIGN.md 5.4 step 4): after an account's chat list arrives,
  `HistorySync` starts `HistoryBackfillWorker` for each chat with nothing stored yet
  (`ConnectorSupervisorTest.chatsWithNothingStoredFetchTheirHistory`). On the emulator,
  right after a fresh Demo login, every inbox row shows its real last message.

### Found while testing: a flaky chat test, and two small real bugs behind it

`MessageActionsScreenTest` (edit, delete) failed most runs on this faster machine when
the whole app suite ran together, on the original code too (checked at `0745f3e`). Causes:

- **Test bug:** its "wait until sent" check looked for IDs starting with `pending-`, but
  they look like `demo/pending-…`, so it often went on while the message was still
  sending. Fixed; the helper also picks the sent message by its words.
- **App bug:** as a sent message replaces its "sending" bubble, the old bubble fades out
  in the same place and still took touches, so a quick press and hold could act on a
  message that was gone. Bubbles fading out now ignore touches (`ChatUi.shown`).
- **App bug:** an action on a message held while it was still sending (edit, delete,
  react, pin) went to the "sending" copy's ID, which no longer exists, and silently did
  nothing. `MessageActions` now remembers which sent message replaced which "sending"
  one and acts on that (`MessageActionsTest.actingOnTheSendingCopyActsOnTheSentMessage`,
  which fails without the fix).

The app suite then passed 8 runs out of 8 (it failed 3 to 4 out of 4 before).

### Voice notes, tap or hold (handover step 3, UI_DESIGN.md 5.6 as approved)

- **The mic is now a plain touch area**, not an icon button: the button's own click handling
  took the touch first, so the hold-to-record code never saw it (the Gate G1 "mic does
  nothing"). Screen readers get one action, "tap to record hands-free".
- **Recording starts the moment the mic is touched.** Let go before the long-press time
  (about 0.4 s): it carries on hands-free. Keep holding: let go to send, slide left to
  cancel, slide up to lock.
- **Hands-free bar:** trash, a red dot and the timer, the live waveform, Pause, and Send.
  **Paused:** Play (and Stop) for what is recorded so far, the waveform as it was, Resume
  (the mic icon), and Send. Pausing does not count towards the length.
- **How pause works:** each stretch between pauses is its own file (Android's MediaRecorder
  cannot be played back mid-recording). Pausing joins the stretches so far into one file to
  play; sending joins them into the note. The join copies the AAC samples into one MP4 with
  `MediaExtractor` and `MediaMuxer`, without re-encoding.
- Tests press the mic like a finger (`VoiceScreenTest`: a tap records hands-free; a hold sends
  on release; slide left cancels; slide up locks, then pause, play, resume, send) and
  `VoiceNotesTest` covers pause timing. Checked on the emulator: every gesture, playback while
  paused, and a sent note made of two stretches playing past the join (0:15 of 0:17).

### The press-and-hold menu, and a scheduled message's actions (handover step 4)

- **Layout (UI_DESIGN.md 3.3):** the reaction bar, the held bubble, and the action card are now
  placed together, top to bottom, inside the screen's safe area (clear of the status and gesture
  bars). The bubble stays where it was when it can; near the top or bottom it moves just enough
  for the bar above and the card below; a bubble too tall to fit shows clipped. Before, the bar
  was pinned under the top edge and the card flipped above it, so they covered each other and
  the emoji could not be tapped. The lifted row no longer casts a shadow (it fell from the
  whole row, as a line across the screen).
- **Test:** `everyQuickReactionCanBeTappedWhereverTheBubbleIs` holds the newest message (near
  the composer) and the oldest (scrolled up under the header), checks the three parts are on
  screen and apart, and taps each of the six quick reactions at its place on screen. It fails
  against the old layout. Checked on the emulator for a bubble at the bottom and at the top.
- **Scheduled message (10.13, owner's Gate G1 item 6):** its menu is Edit, Reschedule, Send
  now, Copy, and Unschedule (which deletes it), in that order; "Change time" and "Cancel
  sending" were renamed to the design's words. Test: `SendLaterScreenTest`.

### Send button, inbox top bar, bottom bar centring, read marks (handover step 5)

- **One Send button** (UI_DESIGN.md 2.2, owner's decision): tap sends; press and hold opens Send
  later and, in Google Messages chats, Send as SMS. The split button is gone. Screen readers
  get the hold as the button's long-press action, "More ways to send". With nothing else to
  offer, holding simply sends. Tests press and hold like a finger (`SendButtonTest`,
  `SendLaterScreenTest`).
- **Inbox top bar** (3.1): its own `surfaceContainer` surface with 28 dp rounded bottom
  corners, covering the status bar area, so rows scroll cleanly under it.
- **Bottom bar centring** (3.1): each item sat at the left of its equal slot because the
  wrapper that carries the long-press menu let it shrink. The wrapper now passes the slot's
  width on (`propagateMinConstraints`). `theBottomBarsItemsAreSpreadEvenlyAcrossIt` fails
  without the fix.
- **Read marks in the inbox** (3.1): when the last message is yours, its mark (the same as in
  the chat: sending, sent, delivered, read, failed) shows before the preview; not in obscured
  chats. The last-message query now carries the status (no schema change). Tests:
  `MessageStoreTest`, `aRowWhoseLastMessageIsYoursShowsItsMark`.
- Checked on the emulator: the top bar surface, the centred bar, and Send's hold menu. The
  demo's live chatter keeps replacing your last message, so the read marks were checked in
  the test-drawn inbox (`app/build/screenshots/inbox-light.png`).

### All removable; spaces get an icon and "show in All" (handover step 6, UI_DESIGN.md 10.4)

- **All can be taken off the bar.** The bar editor lists All with the other choices; up to
  five buttons in all, and never none (the last ticked one cannot be unticked). With All off
  the inbox opens on the first button. A saved bar from before keeps All
  (`InboxBarConfig.showAll`, on by default).
- **A space you make has an icon**, picked from 18 in the space editor, shown in the bottom bar,
  the avatar menu, and Settings > Spaces. 15 new Material Symbols Rounded icons (home, work,
  family, school, sport, travel, heart, and others) were added to `core/ui`, same source and
  licence as the rest.
- **"Show these chats in All"** (on by default). Off: the space's chats leave All's list and
  All's badge, and stay inside the space. Unread and the network buttons still show them; the
  design names All only. A chat in several spaces leaves All if any of them says so.
- **Schema 4** (migration 3 to 4): `spaces.icon` and `spaces.showInAll`, with defaults so
  existing spaces keep the app icon and stay in All.
- **Found and fixed:** a space the user made never had an unread badge. The counting rule
  only summed chats that belong to a network's space (WhatsApp community, Telegram forum), not
  chats added to a space by hand. A second query applies the same rule to those
  (`UnreadRuleTest.aSpaceYouMadeCountsTheChatsYouAddedToIt`).
- Tests: `InboxStateTest` (All's list and badge, the bar's limits), `InboxScreenTest` (opens on
  the first button without All), `SettingsScreenTest` (icon and switch in the editor),
  `SpaceStoreTest`, and `MigrationTest`. Checked on the emulator: made a "Work" space with
  the briefcase icon kept out of All, took All off the bar: the inbox opened on Unread with
  Unread, Demo, Work along the bottom.

## Gate G1 re-check: the owner's checklist (2026-10-01)

Every item from the Gate G1 feedback is built and was checked on the emulator. It is your turn
on the phone. Everything runs on the pretend Demo network, so nothing real is sent.

**Install it.** Once the last of these pull requests is merged (#15, then the Send/top bar
one, then the spaces one), open GitHub > Actions > the newest green "build" run on `main`,
download the file ending in `-demo.apk` onto the phone, open it, and tap Update. It installs
over your PingMe Demo and keeps your setup.

**Then try each of these. Tell me which ones are wrong, with a screenshot if you can.**

*Reactions and motion* (Settings > Motion)
1. Set Animation to **Full**. In a chat, double-tap someone's message. A big heart springs out,
   flies to the corner of the bubble, and bursts into many small hearts that float up and
   fade. The bubble wobbles when it lands.
2. Set it to **Extra** and do it again with ❤️ or 🔥: the edges of the screen glow red or
   orange for a moment too.
3. Set it to **Subtle**: the heart flies to the bubble, with no burst. **Off**: the reaction
   just appears.
4. Compare the levels while opening a chat and going back (Off: instant; Subtle: a fade; Full:
   a short slide; Extra: a bouncy full slide) and while sending a message (Extra: your bubble
   pops up from small; Full: it rises a little; Subtle: it fades in).

*Press and hold a message*
5. Hold a message near the bottom of the chat, then one near the top (scroll up first). Each
   time: the emoji bar is above the message, the menu is below, nothing covers anything, and
   every emoji can be tapped.
6. Schedule a message (hold Send > Send later), then hold that waiting message: the menu is
   Edit, Reschedule, Send now, Copy, Unschedule. Try Unschedule: it disappears.

*Voice notes* (the mic needs permission the first time)
7. **Tap** the mic: it starts recording and stays recording. Talk, tap Pause, tap Play to hear
   what you said, tap the mic to go on, then Send.
8. **Hold** the mic, talk, let go: it sends.
9. Hold the mic and slide left: it cancels. Hold and slide up: it locks into the hands-free bar
   (then the trash deletes it).

*Send button*
10. Type something. A tap on Send just sends. Press and hold Send: a small menu with Send later
    (and Send as SMS in Google Messages chats, which come in Phase 3).

*Inbox*
11. Scroll the inbox: the top bar has its own coloured surface with rounded bottom corners, and
    the list slides under it cleanly.
12. The bottom bar's buttons are spread evenly across the full width.
13. A chat whose last message is yours shows ✓ (sent), ✓✓ (delivered) or coloured ✓✓ (read) before
    "You: …". (The demo answers quickly, so look right after you send, or at the older chats.)
14. Right after a fresh setup, every chat shows its last message, not "No messages yet".

*Bottom bar and spaces* (avatar menu > Edit bottom bar, and Settings > Spaces and the bottom bar)
15. Make a space: give it a name, pick an icon, pick some chats. Turn off "Show these chats in
    All" and save. Those chats are no longer in All, and do not count in All's number; the
    space's own button shows them and their unread count.
16. In Edit bottom bar, untick All and tick your space. The inbox now opens on the first button,
    and your space's button shows its icon.

**Noticed, not changed (tell me if you want these):**
- Opening a chat puts the cursor in the message box and opens the keyboard straight away.
  Google Messages does not. Leave it, or open chats with the keyboard closed?
- Still open from before: a demo button that sends a reaction on demand, so flippy reactions
  (UI_DESIGN.md 10.8) are easy to test; and, low priority, an obscure blur that follows the
  bubble's shape.

**Next:** your results. Anything wrong gets fixed and re-checked; when G1 passes, Phase 3 starts
(P3.1, the Go bridge).

## Owner decision for Phase 3 (2026-10-01): Google account pairing only

The owner decided that PingMe pairs with Google Messages by **Google account pairing only**;
QR pairing is not built. Reason: Google is retiring QR pairing, and a QR cannot be scanned
on the same phone. The owner accepted the risk this brings: if Google refuses sign-in inside
PingMe's in-app web page, there is no fallback way to pair, so that has to be solved, and is
the first thing to check in P3.2. DESIGN.md (5.4, section 7, open question 1, decisions log)
and BUILD_PLAN.md (P3.2, Gate G2) are updated to match. The general `ShowQr` login step stays,
since WhatsApp and Signal link by QR.

## P3.1 Go bridge (done, 2026-10-01)

The Go side of PingMe exists: `gobridge/`, a Go module (`pingme.org/gobridge`, Go 1.26)
with one package, `gm`, wrapping libgm from mautrix-gmessages, compiled by gomobile into
`gobridge/build/gobridge.aar` (never committed). Checked with `./gradlew check`, and the
bind itself was run here: the AAR is 20.6 MB for arm64, x86_64, and arm.

**What the bridge offers** (`gobridge/gm`, Java package `org.pingme.gobridge.gm`):
- `Login`: Google account pairing, step for step as libgm's reference bridge does it.
  `NewLogin(cookies)` checks the six cookies Google sign-in must give, `Start()` returns
  the emoji the user taps in Google Messages, `Finish()` waits for the tap and returns the
  session (pairing keys and cookies) as JSON, plus the phone's ID and the account's email.
- `Session`: `Connect`/`Disconnect`, `ListConversations`, `GetConversation`,
  `FetchMessages` (paged, newest first), `SendMessage` (text and uploaded media, with a
  reply), `UploadMedia`/`DownloadMedia` (encrypted both ways, as Google requires),
  `RequestFullSizeMedia`, `SendReaction` (add, remove, switch), `DeleteMessage`,
  `MarkRead`, `SetTyping`, `GetOrCreateConversation` (one number or a group), `SetActive`,
  `Unpair`, `AuthJSON` (to re-save after a token refresh).
- Everything the phone sends comes back through `EventSink.OnEvent(json)`: messages,
  conversations, typing, settings (SIMs, RCS on or off), and the connection's state:
  `ready`, `inactive` (another web client took over; the bridge takes the session back
  by itself), phone responding or not, temporary errors, `loggedOut` (Google revoked the
  pairing), `authUpdated`, `reconnect`.
- The boundary is flat for gomobile: JSON strings in and out. `gm/convert.go` holds the
  shapes; messages are flattened (text parts joined, media listed with their keys,
  reactions as emoji plus participant IDs, status groups `sent`/`delivered`/`read`/
  `failed`, `direction`, `hide` for the tombstones the reference bridge hides, and the
  transport: SMS or MMS from the message's own type, else the chat's RCS).
- Errors cross as exceptions whose message starts with a code (`LOGGED_OUT:`,
  `PHONE_NOT_RESPONDING:`, `PAIR_NO_DEVICES:`, `PAIR_WRONG_EMOJI:` ...), so the Kotlin
  side can show the right words without parsing prose.

**Kotlin side** (`connectors/gmessages/.../bridge`):
- `GmJson.kt` mirrors the JSON shapes (kotlinx.serialization; unknown fields ignored).
- `GmBridge`/`GmLogin`/`GmSession` are the bridge as interfaces; `GomobileGmBridge` is the
  real one over the gomobile classes. Tests use a fake, so no JVM test loads the native
  library.
- `GoBridge` turns conversations into `ChatSnapshot`s, messages into `MessageSnapshot`s,
  and events into `ConnectorEvent`s, for one account. It remembers each chat's
  participants (so senders and typing numbers become people) and each message's
  reactions (so a repeat is `MessageUpdated`, and a changed reaction becomes a
  `ReactionChanged` that flips the row). Deleted messages become `MessageRemoved`;
  chats the phone binned, blocked, or marked spam become `ChatRemoved`.

**Build wiring:**
- `gobridge/build.sh` vets and tests the Go code, then binds. It keeps a hash of the Go
  sources beside the AAR and skips the bind when nothing changed (the bind itself takes
  4 seconds with a warm Go cache, about 2 minutes cold). `./build.sh --force` rebuilds.
- Gradle runs it: `gobridge/` is the `:gobridge` module, whose `buildGoBridge` task runs
  the script and whose one artifact is the AAR; `:connectors:gmessages` depends on
  `project(":gobridge")`. (A first try depended on the `.aar` file directly, which
  `./gradlew check` accepted but `assembleRelease` on CI refused: the Android Gradle
  Plugin will not bundle a local `.aar` inside a library module. Local checks now run
  `assembleRelease` too before a push.) It needs Go, gomobile, and the NDK, as
  `gobridge/README.md` says; the CI workflow (P0.4) already installs those and caches
  `gobridge/build` by the hash of `gobridge/**`.
- Go unit tests cover the conversion and write `gm/testdata/session.json` (`go test
  ./gm -update`), the recorded session the Kotlin tests read: `GoBridgeTest` checks the
  Kotlin translation against exactly what Go produces, and P3.2's contract test will
  replay the same file.

**Deviations from the plan, and why:**
- The module requires libgm only. The plan lists whatsmeow and signalmeow in `go.mod` too,
  but nothing uses them until Phases 5 and 6; pulling them in now would add their size
  (tens of MB) and build time to every APK for no feature. Each joins the module in its
  own phase, with its own package next to `gm`.
- Java package `org.pingme.gobridge.gm` rather than gomobile's default `gm`, so the
  classes are clearly PingMe's in stack traces.
- Message **unread counts**: Google Messages tells a paired device only whether a chat is
  unread, not how many messages are. The chat snapshot reports 1 for an unread chat; the
  inbox's per-chat number will say "1" where Google Messages itself shows a dot.
- `Message.type` from the phone is only partly documented in libgm (1 = SMS, 2 = MMS,
  3 = undownloaded MMS; "4 = RCS?"). The bridge trusts 1 to 3 and otherwise uses the
  chat's own type. Gate G2 checks RCS and SMS bubbles against what Google Messages shows.

**App size, measured** (DESIGN.md open question 3): the release APK went from about 56 MB
to 103 MB with the bridge, because the Go library ships for three chip types (arm64,
x86_64, arm) and is stored uncompressed, as Android requires for native code. One phone
only needs one of them; if the size matters to the owner, ABI splits (one APK per chip
type) or dropping 32-bit arm would bring it back down. Owner's decision (2026-10-01):
leave the size for now; revisit at release time.

**Library versions:** mautrix-gmessages v0.2609.0 (September 2026; `pkg/libgm`, not
the `libgm/` path the plan guessed), which needs Go 1.26, so `GOTOOLCHAIN=auto` fetches
Go 1.26 next to the machine's 1.24. golang.org/x/mobile from 2026-09-08. zerolog 1.35.1
for libgm's logging, sent to logcat under `GoLog` at info level.

**Next:** P3.2, the connector: the pairing flow as login steps (OpenWebView to Google
sign-in, then WaitForConfirmation with the emoji), connect through the supervisor,
chat and message sync, send, react, read, typing, delete, media, and the "Action
needed" flow when Google revokes the pairing. Then P3.3's contract test on the fixture.

## P3.2 Google Messages connector (done, 2026-10-01)

`connectors/gmessages` is a real connector now, over the P3.1 bridge. Checked with
`./gradlew check`; the contract test (P3.3) runs against the recorded libgm session.

**Pairing** (`GmessagesLogin.kt`), Google account only (owner, 2026-10-01):
1. **Fix steps** for DESIGN.md 5.4 step 1, a new `LoginStep.Fix` any connector can use:
   "Install Google Messages" (button to its Play Store page) and "Make Google Messages
   your default SMS app" (button to Android's default-apps screen), each with Check
   again. RCS on or off is only known after pairing, from the phone's settings event;
   it is logged, and texts still work over SMS when it is off.
2. **Sign in to Google** in the in-app web page (`OpenWebView`), landing on the Messages
   for web config page so OSID is set; the six cookies libgm needs come back. The
   WebView now presents itself as Chrome (its user agent minus the WebView markers),
   keeps DOM storage, and accepts third-party cookies, because Google refuses sign-in
   from pages that announce themselves as WebViews. **If Google still refuses on the
   phone, this is the first thing Gate G2 finds out**, and there is no fallback.
3. **The emoji**: PingMe asks Google to pair, shows the emoji at display size, and the
   owner opens Google Messages on the same phone and taps the same emoji there.
4. **Done**: the session (pairing keys, cookies, tokens) is saved encrypted under
   `gmessages/<phone id>`, through the Keystore credential store (P1.4). The account's
   name is the Google account's email. Every failure the bridge tells apart has plain
   words: no phone on the account, phone not answering, wrong emoji, cancelled, timed out.

**Live session** (`GmessagesSession.kt`): connects through the bridge, lists the inbox's
chats on the phone's "ready" signal (and again when the phone asks for a resync), then
reports `Connected`; from then on every message, conversation, typing, and reaction
change arrives as `ConnectorEvent`s through `GoBridge`. Token refreshes re-save the
credentials. When Google revokes the pairing (any of libgm's four signals, or a
logged-out answer to a request) the session throws `ActionNeededException` with the
Google Messages package as the deep link, so the supervisor stops retrying and the
inbox shows "needs attention, tap to fix" (DESIGN.md 5.3). Other failures end the flow
and the supervisor backs off and reconnects.

**Doing things:**
- **Send**: media is uploaded first (encrypted, as Google requires; the bubble's
  progress follows each file), then the message is sent with the pending bubble's own
  id as tmpId. Google Messages confirms sends asynchronously by echoing the message
  back with that tmpId, so the connector waits up to 20 seconds for the echo and
  returns the real message. If the echo is slower, a stand-in copy is returned and
  swapped for the real one when it arrives.
- **Reactions**: any emoji; add, switch (when you already reacted), remove. **Read**:
  marks the chat read on the phone, which sends the RCS read receipt. **Typing**: sent
  on start (Google Messages has no "stopped" call; the indicator times out).
- **Delete for me** deletes the phone's copy too, through the pairing (UI_DESIGN.md
  5.3), so Google Messages matches. Recorded: this deletes on the phone. **Delete for
  everyone** is not offered: Google exposes none to paired devices (open question 1 in
  UI_DESIGN.md 11 is answered for now: no).
- **Media** downloads decrypt into app storage; a thumbnail is used while the phone
  uploads the full file, which then arrives as a message update.
- **New chats and groups** by phone number (an RCS group gets the given name).
- **RCS vs SMS per message** from the phone's message type (SMS, MMS) or the chat's own
  transport; the bubble shows the SMS tag on SMS fallbacks (UI_DESIGN.md 10.1).

**Capabilities** (UI_DESIGN.md 8, RCS column): native replies, delete for me, no delete
for everyone, any-emoji reactions, native GIFs and voice notes, typing, read receipts,
no edit, no pins, no folders, start conversation and create group, one account, calls
through the dialer and Meet. **Block** is reported unsupported ("Block people in Google
Messages itself"): libgm carries a block action but the reference bridge never uses
it, so it is unverified; the UI shows the reason instead of hiding the control.

**Deviations, and questions for the owner (answer at Gate G2):**
- **"Send as SMS" cannot be done through the pairing.** Google Messages lets a paired
  device say "force RCS" or leave the choice to the phone; there is no "send this one
  as SMS". The hold-Send menu still offers it (P2.4), and choosing it now fails at once
  with the reason, rather than silently sending as RCS. Options: (a) remove the menu
  item for Google Messages chats, or (b) keep it and have it switch the whole chat to
  SMS in Google Messages (not exposed to paired devices either, so (b) is not possible
  today). I suggest (a). Your call.
- **Unread counts** are "1" for an unread chat: Google Messages tells paired devices
  only whether a chat is unread. The inbox shows one blue dot either way; the number
  in a filter button counts chats, not messages, for this network.
- **One account**: a phone has one Google Messages pairing (`multiAccount = false`).

**New in the connector API**: `LoginStep.Fix` and `LoginResponse.CheckAgain` (P1.3's
login steps). The login screen draws Fix with the action button and Check again, and
a preview covers it (`LoginFixPreview`, in `PreviewsTest`).

## P3.3 Acceptance for Phase 3 (contract test done, 2026-10-01): stopped at Gate G2

`GmessagesContractTest` runs every contract test against `FakeGmBridge`, a pretend phone
that replays `gobridge/gm/testdata/session.json` (which the Go tests write from real
libgm proto messages) and answers like Google Messages: a send echoes back as a
message event with the same tmpId, a reaction comes back on the message, a delete comes
back as a deleted message, typing arrives by phone number. `GmessagesLoginTest` covers
the Fix steps, the sign-in cookies, the emoji, and the failure wording.

**Gate G2, the owner's turn.** This needs your phone and your Google account; nothing
here can be checked on the emulator, which has no Google Messages. It is the first
real network, so expect rough edges and tell me each one.

*Install it*
1. On GitHub, open Actions, the newest green "build" run on `main`, and download
   `pingme-<commit>.apk` (the real app, not the demo) onto your phone. Install it.

*Pair* (Settings > Accounts > Add account > Google Messages, or from setup)
2. If PingMe says Google Messages is not the default SMS app, use its button, fix it,
   and tap Check again.
3. Sign in to Google on the page PingMe shows. **If Google refuses ("this browser or
   app may not be secure" or similar), stop and tell me**: there is no other way to pair.
4. PingMe shows an emoji. Open Google Messages on this phone: it should ask you to
   confirm pairing with the same emoji. Tap it. PingMe says it is connected and the
   inbox fills with your chats. Tell me how long the chat list took and whether the
   names and pictures look right.

*Use it* (with a friend who has RCS, and one plain SMS contact if you can)
5. Receive an RCS message: it appears in PingMe and a notification shows.
6. Send a text; send a photo. Both show as sent, then delivered, then read (✓, ✓✓,
   coloured ✓✓) as your friend reads them.
7. React to their message with any emoji; have them react to yours: the reaction
   shows, and the row flips.
8. Have them type: "typing…" shows in the chat header.
9. Look at an RCS chat and an SMS chat side by side: RCS bubbles in the phone's colour,
   SMS bubbles with the outlined edge and the SMS tag.
10. Delete one of your messages (Delete for me): it also disappears in Google Messages.
11. Hold Send: the menu offers Send later only (Send as SMS is gone; owner, 2026-10-01).

*Reconnection*
12. Swipe PingMe away (kill it) and open it again: it reconnects by itself.
13. Turn airplane mode on for a minute and off again: the inbox shows "reconnecting",
    then connected, and nothing is lost.
14. In Google Messages, go to Device pairing and unpair this device: PingMe shows
    "needs attention, tap to fix" within a minute, and tapping it opens Google
    Messages. Pair again from Settings > Accounts > Log in again.

**Then tell me** anything in the list above that did not match.

## Owner decisions after P3.2 (2026-10-01)

Both questions in the P3.2 entry are answered:
- **"Send as SMS" is removed.** The hold-Send menu offers Send later only, everywhere; the
  string, the menu item, and the composer hook are gone. `OutgoingMessage.forceSms` stays
  in the connector API for the native SMS connector and for retries (which keep a message's
  transport), and the Google Messages connector ignores it: the phone picks RCS or SMS.
  UI_DESIGN.md 3.2, BUILD_PLAN.md P2.4, and the DESIGN.md decisions log say so.
- **Unread is a dot, not a number.** Chat rows and pinned tiles show a plain dot for any
  chat with unread messages, on every network; nobody needs the count per chat. The
  bottom bar's badges keep their numbers (they follow the counting rule in UI_DESIGN.md
  6.4, which already counts chats for Google Messages since it reports one per unread
  chat). UI_DESIGN.md 3.1 and the decisions log are updated.

**Next:** when G2 passes, Phase 4 (notifications, background, and the message features
that need the real network).

## Fix after merging Phase 3 (2026-10-01): the send's echo wait on the test clock

`main`'s build after #20 failed on `GmessagesContractTest.reactionsFollowTheCapability`,
which had passed on every pull-request run and locally. The send waits up to 20 seconds for
the phone to echo the message back; under the contract test's `runTest` that wait ran on
the test's virtual clock, which skips ahead the moment the test coroutine is idle, so on a
slow runner the wait "expired" before the echo (which comes from the bridge's own thread)
was processed, the stand-in message came back, and reacting to a stand-in is refused. The
wait now runs on a real-time dispatcher. Nothing changes on the phone, where there is no
virtual clock. Checked by running the connector's tests three times in a row and the full
check.

A second timing race, in the test double this time: `typingFollowsTheCapability` timed out
once on CI. `FakeGmBridge` attached the session, then sent "settings" and "ready"; a typing
event from the test thread could land in between, before the connector had listed the
chats, so it was dropped as "unknown chat" (which is what the connector should do with
typing for a chat it did not know). Two changes: the fake delivers its events under one
lock, so "ready" comes first as it does on the phone; and the connector no longer drops
typing for a chat it has not listed yet. It fetches that one chat from the phone, announces
it, and then shows the typing. That is better on the phone too (typing in a chat that
arrived after the list still shows) and makes the test independent of event order. The
contract test module passed ten runs in a row, then the full check.

## Gate G2, first evening on the owner's phone (2026-10-01): pairing works

The owner installed `pingme-3cab646.apk` (put on the phone over Wi-Fi debugging from this
computer) on a Motorola razr ultra 2025, Android 16, Google Messages September 2026.

**What worked, first try:**
- **Google sign-in inside PingMe was accepted** (the biggest risk): email, password, then
  Google's Messages-for-web config page. The pairing emoji came, the owner tapped it in
  Google Messages, and PingMe showed **Connected** with the real chat list, names, and RCS
  tags, within about a minute.
- Message history filled in per chat, with the right read marks on old messages.
- Incoming texts arrived live, with a notification.
- A reaction from the other side (❤️) showed under the right message.
- Sending works: each text went out exactly once and was delivered (checked in Google
  Messages).

**What was wrong:**
1. **Every sent text got a ghost copy** about 20 seconds later: the connector tags a send
   with a tmpId and expects the phone's copy back with the same tag; it never matched, so
   the stand-in stayed next to the real message (and showed a single tick forever). The
   tag was PingMe's pending id (`pending-<uuid>`); the phone appears to keep only UUIDs.
2. After the password, the owner saw Google's raw config page (a wall of JSON) and had to
   tap "I have signed in" under it.
3. Marking a chat read sometimes asked the phone about a PingMe placeholder id and waited
   a minute for an answer that never came (`PingMeChatActions: Could not send the read
   marker`).
4. The app wrote almost nothing to the log, so none of this could be read from the phone.

**Fixed (this change):**
- The tmpId is now the UUID inside the pending id (or a fresh UUID). As a second line of
  defence, the phone's copy is also matched by text and time (same chat, same text,
  within three minutes), and a sent message found in a history page retires its stand-in
  the same way an echo does.
- The sign-in step takes landing on `messages.google.com/web/config` as "done" and moves
  to the emoji by itself (`LoginStep.OpenWebView.finishedUrlPrefix`, a general option).
  The sign-in pages themselves are unchanged.
- Read marks, reactions, and deletes are only sent for ids the phone gave out (numbers).
- A message's tick never goes backwards: later events with an older status keep the
  further-along one (the reference bridge does the same with its status events).
- The connector logs each send and each message from the phone (ids, tmpId, status) at
  info level under `PingMeGmessages`, so the next test can be read from logcat.
- The fake phone in the contract test keeps a tmpId only when it is a UUID, so the test
  now catches the ghost-copy bug.

5. **The connection dropped once and reconnected** (9:53 PM): `GoBridge.remember` threw
   `NoSuchElementException` because the event loop, the history worker, and the chat-list
   sync all wrote its maps from their own threads. Every public method of `GoBridge` now
   holds one lock.
6. **Read looked like delivered.** The read mark was the two ticks in the primary colour,
   which on the owner's primary-coloured bubbles is invisible. Read is now the two ticks
   inside a filled circle (text colour, ticks cut out), in the chat and the inbox row.
   UI_DESIGN.md 3.1/3.2 and the decisions log say so. The owner's rule for this and any
   later UI fix: it ships with the current phase's fixes, no separate Gate G1.
7. The owner also noticed that contact photos are missing: expected, they come with
   Phase 7 (people), which matches `Person.phoneNumber` to the phone's contacts.
8. "What kind of tie do you need?" showed as sent while Google Messages showed it read.
   The ghost copy was hiding the real copy's marks; whether the read event itself arrives
   is checked in the morning (the log now says so, line by line).

**Not yet checked from the G2 list:** sending a photo, typing indicators, RCS vs SMS
bubbles side by side (every chat on the phone is RCS), delete for me, kill-and-restart,
airplane mode, and unpairing.

## Gate G2, morning checklist (2026-10-02)

The fixed app is `pingme-<commit>.apk` from the newest green build on `main`; I put it on
the phone over Wi-Fi debugging (turn it on and send the port). Everything stays paired;
no need to sign in again. Then:

1. Open Parker's chat: the ghost copies from last night are gone, and "what kind of tie
   do you need?" shows the read mark (two ticks in a filled circle).
2. Send a text: one bubble, a clock for a moment, then one tick, two ticks when it is
   delivered, and the filled circle when it is read. Never a second copy.
3. Receive a text: it shows, with a notification when PingMe is in the background.
4. Send a photo; have one sent to you.
5. Have someone type to you: "typing…" in the chat header.
6. React to a message; have someone react to yours.
7. Delete one of your own messages (Delete for me): gone in Google Messages too.
8. Kill PingMe and open it again: reconnects by itself. Airplane mode for a minute:
   "reconnecting", then connected.
9. Settings > Accounts > Add account > Google Messages (as if pairing again): after the
   password there is no gibberish page; it goes straight to the emoji. (Cancel there;
   no need to pair twice.)

Tell me what did not match, one line each.

## Gate G2, second session on the owner's phone (2026-10-02, 4:30 to 5:10 AM)

`pingme-424b50f.apk` went on over Wi-Fi debugging. The owner walked through the app and
every problem was written down before anything was fixed (the owner's rule: look at
everything first, then fix it all at once).

**Confirmed fixed from the night before:** no ghost copies on new sends (the phone's copy
matched the placeholder within a second every time, by its UUID tag); read marks correct
and clearly different from delivered; sends logged line by line. A photo and a GIF went
out and were delivered; a photo and a screenshot arrived; a reaction arrived.

**Found, in the order seen:**
7. **System notes drawn as bubbles.** Right after a GIF the phone sent a "switched to RCS"
   note (a tombstone), which PingMe drew as an empty bubble from "you". Tombstones are not
   messages and are no longer shown at all (`GoBridge.isShown`); empty text bubbles left
   from before are removed by the clean-up below.
8. **A sent picture or GIF went empty, then slowly filled in.** The phone's copy names the
   media but not the file; PingMe swapped its placeholder for that copy and downloaded its
   own picture back. The connector now keeps the just-sent files on the phone's copy.
9. **The one ghost from the night before** could not be matched any more. `StoreHousekeeping`
   runs at each start and removes stand-ins older than ten minutes and empty bubbles.
10. **Gallery, Camera, File, and Contact lost their pick.** The pickers' listeners lived in
    the attach sheet, which closes the moment a picker opens, so the answer landed nowhere.
    The listeners now live in the composer (`rememberAttachLaunchers` is hoisted).
11. **Reacting to a photo made it vanish; the morning's GIF vanished after a re-fetch.** Any
    update to a message replaced its attachments, dropping the "where I saved it" path.
    The store's upsert now keeps each attachment's downloaded file when the new copy has
    none (`MessageDao.upsert`, test `updatingAMessageKeepsDownloadedFiles`).
12. **Tapping a picture did nothing.** 13. **A video opened the "open with" list.** Both now
    open full-screen inside PingMe (`MediaViewer`: pictures fit, videos play with the
    platform player and its controls, close or back returns). UI_DESIGN.md 5.8 says so.
14. **Pictures and videos were slivers.** They now fill the bubble's width, keep their shape
    (from the media's size when known, else 4:3), and videos show their length.
15. **Unread rows were invisible.** A 14 dp primary dot, bold name and preview at full
    contrast, the time in the primary colour, and a faint primary tint across the row
    (UI_DESIGN.md 3.1).
16. **A sent message hid behind the keyboard.** The chat now scrolls to the bottom on your
    own send wherever you were, and when the keyboard opens while you are near the bottom.
    Also noted for the contacts phase: a chat with a bare phone number showed "80" as its
    avatar (initials taken from the number); unknown numbers get a generic person icon.

**Checklist for the next install** (same as before, plus): the GIF and photo bubbles show
their picture at once and stay; reacting to a photo keeps it; tapping a picture or video
opens it in PingMe; Gallery, Camera, and File put the pick in the message box; the unread
chat in the inbox is obvious; a sent text sits above the keyboard; no empty bubbles.

## Gate G2, third round: screen fixes before Phase 4 (2026-10-02, from 9:12 AM)

The owner finished the second session's list in the morning (items 17 to 26 below), checked
item 9 of the checklist (Add account > Google Messages went from the Google account page
straight to the emoji, no gibberish page; cancelled there, existing account untouched), and
chose how the rest goes: the plain screen fixes ship first as their own build, and the
notification and media items ride with Phase 4, all checked together at **Gate G3**. Gate
G2 is therefore passed with those items carried forward. The owner's instructions for the
work: a release on the private repository with the APK, the link emailed the moment it
exists and posted in the chat, then Phase 4 straight away with a check-in every half hour.

**Found in the second session, after item 16:**
17. **Two bubbles when a send went through.** The pending bubble and the phone's copy have
    different ids, so the list faded one out while the other faded in, overlapping.
18. **Incoming pictures and videos took far too long** to fill in; the owner wants them
    fetched on arrival and the notification to say "X sent a picture". (Phase 4.)
19. **Pinned tiles showed nothing when unread** (the dot was missed).
20. **New messages hid below the keyboard**, received ones too; a chat opened from a
    notification should land on the newest message.
21. **A reaction raised a "new message" notification.** (Phase 4.)
22. Check marks only showed on tap: the owner's own Timestamps setting; dropped.
23. **A download button in the full-screen viewer**, saving to a PingMe album in Photos.
    (Phase 4.)
24. **Pinned row layout**: three or four pins should fill the width, one or two sit centred.
25. **Notifications did nothing when tapped and never cleared** on reading the chat. (Phase 4.)
26. **The status bar icon was half the size** of Google Messages' and the debugging icon.

**Fixed in this round (17, 19, 20, 24, 26):**
- 17: `MessageActions.shownAs` gives the pending id a sent message replaced (`SentCopies`
  keeps the reverse map); `chatItems` draws the phone's copy under that key, so Compose
  updates one bubble in place; a key still owned by another message in the list is not
  used, so keys stay unique (tests `aSentMessageKeepsThePendingBubblesKey`,
  `aSentMessageIsShownUnderItsPendingId`). Everything else on the chat screen (touch
  gating, bubble bounds, wobble, reveal, jump-to) goes by the message id, never the list
  key; mixing them up made sent messages untouchable (caught by
  `MessageActionsScreenTest`). Left as is: the rarer swap from a 20-second stand-in to the
  phone's copy, which comes through the connector's events, not a send.
- 19: `PinnedTile`: a 3 dp primary ring around the avatar in the tile's shape, a 20 dp
  dot rimmed in the surface colour, the name bold in primary (UI_DESIGN.md 3.1).
- 20: `StayAtBottom` scrolls to the newest message when one arrives while you are within
  three items of the bottom (was one), and never before the list has content.
- 24: `PinnedGrid`: one or two pins centred at 84 dp, three to five share the width, more
  wrap at five (`PinnedGridTest`).
- 26: `ic_stat_message` redrawn on a 24 dp viewport filling the box; before, the bubble
  used the launcher icon's 108 canvas and filled under half of it.

Phase 4 decisions from the owner, recorded here for P4.1 and P4.3: no button that opens
Google Messages' notification settings, only a reminder under Settings > Notifications and
a one-time prompt to turn them off by hand; scheduled sends keep the background job as the
fallback when the exact-alarm permission is declined; the viewer's download goes to a
"PingMe" album in Photos.

## P4.1 Notifications, part 1 (2026-10-02, from 10:42 AM)

Started straight after the v0.1.0 release, as the owner directed; the fix list's
notification items (18, 21, 25) are folded in here.

Done:
- `NotificationRouter` rebuilt around `decide(chat, message, now, keywords, settings,
  overrides, account, visible)`: keyword rule (named on the notification; one with
  "override" beats mute and low priority) > the chat's own channel (`chat_<id>_<n>`, from
  `ChatOverrides`) > Instagram folder (`folder_<name>_<n>`) > account (`account_<id>_<n>`,
  Off drops, Silent shows without sound, the network's sound and vibration from
  Settings > Notifications) > `default`. Pure, tested in `NotificationDecisionTest`.
- **Fresh only (21):** the supervisor asks `router.isFresh(event)` before the store takes
  the message; a message already stored (a reaction or status change re-sends it from
  Google Messages) never notifies again (`NotificationRouterTest`).
- **Tap, clear, actions (25):** `MessageNotifications` posts one conversation notification
  per chat (MessagingStyle with a Person per sender, a long-lived conversation shortcut, a
  group summary, Reply with inline text, Mark read). The tap intent carries the chat id;
  `MainActivity` (now singleTop) hands it to `NotificationTaps`, and the navigation host
  opens that chat. `ChatActions.setRead` and `ChatActions.opened` (called when a chat's
  screen starts) clear the chat's notification; `ChatPresence` (set while the chat screen is
  resumed) keeps the open chat from notifying at all. `NotificationActionReceiver` handles
  Reply (sends, then marks read) and Mark read.
- **Media (18):** "sent a picture" wording per message kind; `MediaKeeper.arrived` now
  fetches pictures, GIFs, stickers, videos, and voice notes on arrival for every incoming
  message (documents still wait until shown; "Save all incoming media" fetches everything),
  and `MediaDownloadWorker` logs how long each download took under `PingMeMedia`.
- `NotificationChannels`: one channel per sound, versioned ids, older versions of the same
  channel deleted when a new one is made (UI_DESIGN.md 6.1).
- The workflow's release job now finds the APK by name pattern; the job output it relied
  on came through empty on the v0.1.0 build and the release went out without its file.

Deviation to settle with the owner: **chat bubbles metadata** (BUILD_PLAN.md P4.1) is not
attached yet. Android bubbles need an activity flagged embeddable with "always a new
document" launch mode; putting that on the one `MainActivity` would change how the app
behaves in Recents. The right shape is a small bubble-only activity; proposed for the
second notification pass, after the owner says whether bubbles matter to them.

Part 2, same session:
- **One-time codes** (UI_DESIGN.md 10.6): `OneTimeCodes.find` spots four to eight digits
  near a telling word (code, OTP, verification, sign in, ...) or Google's "G-123456"; the
  notification gets a "Copy code" button, and with "Auto-copy one-time codes" on the code
  goes to the clipboard the moment it arrives, marked sensitive, with a toast. Years and
  phone numbers are left alone (`OneTimeCodesTest`, `NotificationRouterTest`).
- **Google Messages reminder** (UI_DESIGN.md 6.3): a row at the top of Settings >
  Notifications when a Google Messages account exists, and a one-time prompt on the inbox
  (`AppSettings.gmessagesNotificationsReminderShown`). Wording only; no button that opens
  Google's settings (owner decision, 2026-10-02). The plan's "during setup" moment is
  covered by the prompt, which shows the first time the inbox opens after pairing.

Checked on the emulator (demo build): a demo reply while PingMe was in the background
posted "Morgan Diaz / Just landed" with Reply and Mark read; tapping it opened that chat at
the new message and the notification was gone. The connection notification's icon now
matches the others' size in the shade.

Known flake: `:app:lintAnalyzeDebugUnitTest` crashed twice today inside lint itself
("Unexpected failure during lint analysis of AppIconSwitcherTest.kt"), both times right
after a ktlint format run. Running that one lint task alone passes, and the full check then
passes. Not a code problem; noted so the next session does not chase it.

## P4.2 Scheduled sends on an exact alarm (2026-10-02, from 11:00 AM)

- `SendAlarm.arm` sets an exact, allow-while-idle alarm for the earliest scheduled message
  when "Alarms and reminders" is allowed (`AlarmManager.canScheduleExactAlarms`), and
  always keeps the delayed background job as the fallback, which waits for a network
  connection. With the permission declined only the job runs, which may be minutes late
  (owner, 2026-10-02: keep the fallback). `SendAlarmTest`.
- `ScheduledSendReceiver`: the alarm enqueues the sender; boot and a change of the
  permission re-arm the alarm. Permissions `SCHEDULE_EXACT_ALARM` and
  `RECEIVE_BOOT_COMPLETED` in the service manifest.
- Late sends notify (UI_DESIGN.md 10.13): a scheduled message that goes more than five
  minutes after its time raises "Sent late to <chat>" with the text; tap opens the chat
  (`NotificationRouter.sentLate`, test `aSendThatGoesWellAfterItsTimeSaysSo`).
- The composer asks once per chat, after the first scheduled send without the permission,
  with Allow (Android's own "Alarms and reminders" page for PingMe) and Not now
  (`ExactAlarmAsk`). The owner's phone has never been asked before because the old
  background job needed nothing.

Pull requests: P4.1 merged at 11:18 AM. P4.2 was first opened stacked on the P4.1 branch;
GitHub closed it when that branch was deleted on merge, so it was opened again against
main (PR 27). Lesson kept: open stacked work against main.

## P4.3 Media saving, link previews, clean links (2026-10-02, from 11:05 AM)

- **Save to Photos (item 23):** the full-screen viewer has a download button top right;
  `saveToGallery` writes the picture or video through the media store into
  `Pictures/PingMe` or `Movies/PingMe`, which Photos shows as a "PingMe" album. No storage
  permission is needed on Android 10 and newer. "Save all incoming media" already fetched
  everything on arrival and copied it to the chosen folder (`MediaKeeper`); the plan's
  "MediaStore for a public folder" is covered by the folder picker's document tree, which
  works for public folders too.
- **Link previews (UI_DESIGN.md 10.12):** `LinkPreviews.fetch` reads at most 1 MB of the
  page with short timeouts, `OpenGraph.parse` takes the Open Graph, Twitter, or plain
  title, description, and picture (picture capped at 2 MB, kept under `files/previews`),
  and `LinkPreviewWorker` stores the result on the message when the network sent none.
  "Generate link previews": Always, Only on Wi-Fi (an unmetered network), Never.
  `PreviewRequests` asks for one when a fresh incoming message, a sent message, or a
  scheduled send carries a link and no preview. Tests: `OpenGraphTest`, `LinkPreviewsTest`
  (a one-shot local web server).
- **Clean links (UI_DESIGN.md 10.11):** the ClearURLs rules (206 providers) are vendored
  under `core/service/src/main/assets/clearurls/` with their LGPL-3.0 licence and a README
  naming the source and date; `./gradlew :core:service:updateClearUrls` refreshes them.
  `CleanLinks.clean` unwraps redirections, applies raw rules, and drops the named
  parameters; `cleanText` does it for every link in a text. "Clean links I send" cleans
  the text as it is sent or scheduled (`MessageActions`); "Clean links I receive" cleans
  links as they are drawn (`linked(text, preview, clean)`), the stored message keeps the
  original, which Copy and Forward carry. Tests: `CleanLinksTest` against the vendored
  rules (utm parameters, Amazon referral tail, a Google redirect, a sign-in exception).
  Not built: a long-press item that shows the original link (UI_DESIGN.md 10.11 mentions
  it); the original is one Copy away, and the item can join the message menu later.
- Checked on the emulator: a pasted Wikipedia link went out without its `utm_` tail and
  grew a card with the page's picture, title, and site. The first two tries showed no
  card: the demo's "delivered" and "read" updates re-saved the message without the preview
  and wiped it, which Google Messages would do too. `MessageDao.upsert` now keeps a stored
  preview when the new copy has none, the same way it keeps downloaded files
  (`updatingAMessageKeepsItsFetchedLinkPreview`). The preview job also logs why it did or
  did not store one, under `PingMeLinks`. The viewer's save button was not tried on the
  emulator (the only demo picture sits a hundred messages up); it is item 10 of the Gate
  G3 checklist.

## P4.4 Acceptance and Gate G3 (2026-10-02)

Unit tests in place: router precedence and keyword matching (`NotificationDecisionTest`,
`NotificationRouterTest`), one-time codes (`OneTimeCodesTest`), ClearURLs application
(`CleanLinksTest`), the schedule parser (`WhenParserTest`, from P2), the exact alarm
(`SendAlarmTest`), late sends (`WorkersTest`).

**Gate G3 checklist, on the owner's phone** (the fixes from Gate G2 ride along):

1. Get a text while PingMe is in the background: one notification, "Name: text", with
   Reply and Mark read. Tap it: that chat opens at the new message and the notification
   is gone. Get a picture: the notification says "Name sent a picture" and the picture is
   already there when the chat opens; the log says how long it took.
2. Have someone react to a message: no new notification.
3. Open a chat and leave it open: a message arriving in it raises no notification.
4. Chat details > Notifications > Sound: pick a sound; a text in that chat uses it.
5. Settings > Notifications > Keywords: add a word; a text with it (from a muted chat too,
   with Override on) notifies and names the word.
6. Get a real one-time code: the notification offers "Copy code"; with "Auto-copy one-time
   codes" on, the code is on the clipboard the moment it arrives.
7. Settings > Notifications shows the Google Messages reminder at the top; the one-time
   prompt appeared once on the inbox.
8. Press and hold Send, schedule a message two minutes out, and swipe PingMe away
   entirely: it goes on the minute. The first time, PingMe asks for "Alarms and
   reminders"; allow it.
9. Send yourself a link with tracking (for example one with `utm_source=` in it): the
   sent bubble shows it cleaned, and a preview card appears under it with the page's title.
10. Open a picture full screen, tap the download icon top right: it is in Photos under
    "PingMe".
11. From Gate G2: one bubble through a send, unread pinned tiles with the ring, pinned
    layout at one, two, and four pins, the chat landing at the bottom, and the status bar
    icon at full size.

**Phase 4 shipped (2026-10-02, 12:20 PM):** PRs 25, 27, 28 merged; main tagged `v0.2.0`;
the release build attached `pingme-v0.2.0.apk` by itself (the fixed release step works).
Link emailed to the owner at 12:21 PM and posted in the chat. Phone off the network at
that moment; install over Wi-Fi debugging when it is back, then Gate G3 with the checklist
above. Phase 4 took 1 h 40 min from start to release, against an estimate of 7 to 8 hours.

## Gate G3 fixes (2026-10-02, afternoon)

The owner ran Gate G3 on the phone with v0.2.0 and gave 17 items. All of them are in this
round, on branch `g3-fixes`. Nothing on the phone was touched: the owner installs the
release from the emailed link and tests alone.

1. **Media the instant the phone has it.** Google Messages sends a picture twice: first
   while it is still downloading it itself (no media reference, status
   INCOMING_AUTO_DOWNLOADING), then complete. The first copy used to start a download that
   failed and retried on WorkManager's backoff (30 s, then minutes). Now `MediaKeeper`
   skips attachments with no remote reference, `MediaDownloadWorker` succeeds quietly on
   one (no retry chain), and `ConnectorSupervisor` asks the keeper again on every
   `MessageUpdated`, which is when the reference arrives. The "Downloading message..." note
   is no longer part of the message text (`GoBridge`); failure notes still are. When the
   download finishes, `NotificationRouter.pictureArrived` redraws the chat's notification
   with the picture in its line (`MessagingStyle.Message.setData`, through the app's
   FileProvider, read leave granted to the system UI), without sounding again.
2. **Notification not clearing.** Three gaps closed: a chat the phone reports as read
   (a `ChatUpdated` with no unread) takes its notification down; deleting a chat does too;
   and the group summary line is cancelled by looking at what is really in the shade, not
   at memory. Test: `aChatReadOnThePhoneTakesItsNotificationDown`.
3. **iPhone reactions as text.** `Tapbacks` reads "Loved an image", "Laughed at “…”",
   "Removed a heart from “…”" and the other five verbs, finds the message meant (the
   quoted text, or the newest picture, video, or voice message before it, within the last
   200 messages of the chat) and turns the text into a `ReactionChanged` event. Live
   messages are rewritten in `ConnectorSupervisor` before storing, so they never notify;
   history batches apply them after the messages they refer to (`EventApplier`). A
   reaction text whose target cannot be found stays a message, which should be rare.
   Tests: `TapbacksTest`.
4. **Audio plays in the bubble.** Any audio attachment (kind AUDIO or VOICE) draws as the
   voice-note player (play, waveform, length, speed); the message kind is "voice message"
   for notifications and inbox previews. Format unchanged (AAC in .m4a).
5. **Foreground: sound only.** `ChatPresence.appVisible` follows the process lifecycle
   (`PingMeApp`, `lifecycle-process`). With PingMe on screen, the router plays the channel's
   sound (`NotificationSounds`: the channel's own sound, nothing for silent channels or
   under Do not disturb) and posts nothing. The chat on screen stays silent as before.
6. **Scheduled send shown twice.** Two causes. The connector matched a late echo by text
   only within 3 minutes; now 24 hours. And a stand-in was never retired after a restart,
   because the connector forgets its sends: `EventApplier` now deletes a stand-in (same
   chat, same text, same number of files) when the network's own copy of an outgoing
   message arrives. A latent bug went with it: stand-ins carried a plain UUID as their
   remote id, so `StoreHousekeeping` never found them; they are marked `tmp/` now.
7. **Browser identity for previews.** `LinkPreviews` sends Chrome on Android's user agent
   and an Accept-Language header; the New York Times and others answer the page.
8. **Entities.** `Entities.decode` handles named, decimal, and hex references and runs up
   to three passes, so a twice-encoded `&amp;#x20;` comes out right.
9. **Final site on the card.** Redirects are followed by hand (up to five hops, across
   http and https), and the card's link is the final address, cleaned; the site line
   shows its host.
10. **Link text leaves the bubble when a card shows it**; other words stay; a bubble
    that was only the link shows the card alone. The card opens the link. No card, the link
    stays (`linked` in `Links.kt`, `LinksTest`).
11. **Google Messages is one network.** The account and bar label say "Google Messages"
    (bar dot "GM"); the row badge says RCS, SMS, or MMS by the newest message. Outgoing RCS
    bubbles keep their marks; SMS and MMS bubbles, both directions, carry a small tag in
    the marks' place (`TransportTag`), and the old tag above the bubble is gone. **One
    thread per number:** `GoBridge` folds one-to-one conversations with the same number
    (last ten digits, so with or without a country code) under the oldest conversation's
    id, which is stable while it lives; messages, typing, and reads from either land in
    that chat; sends and typing go to whichever conversation last had a message
    (`liveConversation`); read markers go to the conversation the message came from. On
    listing, the folded conversations' old rows are removed (`ChatRemoved`), so the second
    Terry Sanford row goes on the first sync after the update. History: the first page of
    every folded conversation is fetched; older pages follow the conversation the oldest
    stored message came from. If the shown conversation is deleted on the phone, the chat
    moves to the next oldest (its messages are fetched again). Groups never fold, and no
    other network folds anything on its own. **Send as SMS:** checked libgm's
    `SendMessageRequest` again: it has `forceRCS` and nothing to force SMS. Google gives a
    paired device no way to send one message as SMS, so the hold-menu item cannot be built
    through this pairing; the phone itself decides. Tests: `twoConversationsWithOnePersonAreOneChat`,
    `whenTheShownConversationGoesTheChatMovesToTheOther`.
12. **Phase 5 out.** BUILD_PLAN.md section 6 is now a roadmap note (native SMS mode,
    desktop app, chat bubbles after the Android app); Gate G4 is gone. The mode page left
    setup, `TextingMode` left the settings model (old saved settings still load: unknown
    keys are ignored), and the strings and preview went with it.
13. **Sentence capitalisation** on the composer and every text box except numbers and
    addresses: chat rename, group name, space name, account name, keyword, search boxes,
    GIF and emoji search, send-later words.
14. **Group count**: everyone else plus you, not counting a member the network lists as
    you ("You") twice.
15. **Bottom bar**: a small dot for unread, no count.
16. **Read only while on screen**: `ChatViewModel` marks read (and clears the
    notification) only while the chat is resumed; a chat left in the back stack no longer
    swallows new messages.
17. **Network change**: `ConnectionService` reports only real changes (a loss then a new
    network, or a different network), and `ConnectorSupervisor` closes a live session on
    one and opens it again at once, with no backoff (`Outcome.NetworkChanged`). Test:
    `aNetworkChangeWhileConnectedOpensTheConnectionAgainAtOnce`.

Not in this round, for later phases: a generic avatar for unknown numbers (contacts
phase); per-chat sound, keyword rules, and auto-copy still untested by the owner.

## Phase 6, network 1: WhatsApp (2026-10-02, evening)

Built straight after the Gate G3 release, as the owner asked, with no phone access.

- **Go bridge (`gobridge/wa`)**: whatsmeow `v0.0.0-20260929112325-8b41cfe6d9c4` with
  `github.com/mattn/go-sqlite3` for the device store on the phone (C SQLite, built by
  gomobile with the NDK) and `modernc.org/sqlite` standing in for `go test` on a
  development machine (`driver_android.go`, `driver_host.go`). The pure Go driver was
  the first choice everywhere and crashed the app the moment a store opened on the
  x86_64 emulator: its libc makes the raw `stat` and `lstat` system calls, which
  Android's app sandbox forbids on x86_64 (arm64 phones have no such calls, so it would
  have worked there, but not a thing to ship on a guess).
  `NewSession(dbPath, sink)` opens one store per linked number; `PairCode(phone)`
  connects anonymously and asks for the eight-character code (`PairPhone`, shown as a
  Chrome companion); events come back as JSON (`connected`, `message`, `receipt`,
  `history` per conversation, `typing`, `group`, `chatRead`, `loggedOut`,
  `temporaryBan`, ...). Requests: `SendText`, `SendMedia` (upload then the right proto per
  kind: image, video, GIF, voice note as push-to-talk, audio, document, sticker),
  `SendReaction`, `Revoke`, `Edit`, `MarkRead`, `SetTyping`, `Download` (by direct path
  and keys, so no proto is kept), `RequestHistory` (on-demand pages), `CheckNumber`,
  `CreateGroup`, `Block`, `ListGroups`, `Contacts`. `convert.go` flattens every message
  shape into one `Message` JSON; `convert_test.go` covers text with reply, media, voice,
  GIF, reaction, revoke, edit, and groups in a community. `build.sh` binds `./gm ./wa`.
- **Kotlin (`connectors/whatsapp`)**: the same shape as Google Messages. `WaBridge` and
  `GomobileWaBridge` (the gomobile classes; `ProfilePictureURL` keeps gomobile's name),
  `WaJson` (the DTOs and the sealed `WaEvent`), `WaTranslate` (ids: a chat is its
  WhatsApp id, a message is `<chat>/<id>`; chats, members, names from the address book,
  the account's own phone and hidden ids; a bounded memory of seen messages for ticks,
  reactions, and read markers; a per-chat memory of recent messages for history pages),
  `WhatsappSession` (connect, list groups, spaces from communities, history pages first
  from memory then from the phone, sends with one file per message and the text as the
  first file's caption, reactions, revokes, edits, read markers, typing, downloads;
  places and contacts are written from the message itself), `WhatsappConnector`,
  `WhatsappLogin` (number, then the code shown large with the path to type it, then
  Done; the credential ref `whatsapp/<digits>` names the store), `WhatsappModule`.
- **New connector events**, applied by `EventApplier`: `MessageRevoked` (text and files
  go, "This message was deleted" stays), `MessageEdited`, `StatusChanged` (a delivery or
  read tick without the whole message), `SpaceUpdated` (a community; the user's icon and
  "show in All" choices are kept).
- **Capabilities**: native replies, delete for me, delete for everyone within 2 days,
  any emoji, native GIFs and voice notes, typing, read receipts, edit within 15 minutes,
  start a chat and make a group, block, several numbers, calls through the WhatsApp
  entry in the phone's contacts.
- **Tests**: `WaTranslateTest` (ids and names, media and voice, reactions, revokes,
  edits, receipts only on your own messages and never backwards, history order and the
  page memory, communities as spaces), `WhatsappContractTest` against `FakeWaBridge`
  (a pretend WhatsApp that links with any number, brings a short history on connect,
  queues what arrives before the connection is up, and records sends, reactions, and
  revokes).
- **Limits, written down**: history older than what the session has seen is fetched from
  the phone only while PingMe remembers the anchor message (after a restart the history
  ends where the store ends); the first page of a chat asked for right after connecting
  waits up to 3 seconds for the history sync. View-once media arrives marked ephemeral
  and downloads like any other picture, which is the "saved when delivered" the plan
  asks for. No QR path exists, by the owner's decision. Not tried against WhatsApp
  itself: the owner links a real number at Gate G5.

**Gate G5 checklist, on the owner's phone:**

1. Settings > Accounts > Add account > WhatsApp: type your number with the country
   code; PingMe shows an eight-character code. On the phone: WhatsApp > Linked devices >
   Link a device > Link with phone number instead; type the code. PingMe says Done and
   the inbox fills with your WhatsApp chats and recent history (groups first, then
   people as the history lands).
2. Send a text, a picture, and a voice note from PingMe; they appear in WhatsApp on the
   phone and the other side. Ticks turn delivered and read.
3. Get a text, a picture, a voice note, and a reaction: the message shows in PingMe with
   a notification; the reaction lands on its message; typing shows in the header.
4. Reply to a message: the quote shows on both sides. React from PingMe. Edit one of
   your messages within 15 minutes. Delete one for everyone: both sides show it gone.
5. A community's groups show under one space in the bottom bar (add the space in
   Settings > Inbox bar).
6. Swipe PingMe away and get a message: the notification still comes (the connection
   lives in the service).

**Release v0.4.0, first try (2026-10-02, 5:47 PM):** GitHub's build of the tag failed:
`historyPagesBackwardsNewestFirst` in the WhatsApp contract test asked for the first page
before the pretend network's history had landed on the slow runner (the wait was 3 s). The
wait is now 10 s, and the fake delivers its history before `connect` returns, as a queued
stream would. The tag was moved to the fixed commit and built again.

## Phase 6, network 2: Instagram (2026-10-02, evening)

- **Library**: `github.com/mautrix/instagram` turned out to be the old Python bridge, not a
  Go module. The Go code lives in `go.mau.fi/mautrix-meta v0.2609.0`: `pkg/instameow`
  is the Instagram direct-message client (the "split" the plan expected), with
  `pkg/messagix/cookies`, `httpclient`, and `methods` beside it. It pins a fork of its
  HTTP library through a `replace` rule, which `gobridge/go.mod` now carries too
  (`github.com/imroc/req/v3` to `github.com/beeper/req/v3`); without it the build broke on
  a newer QUIC library.
- **Go bridge (`gobridge/ig`)**: `NewSession(cookiesJSON, sink)` takes the instagram.com
  cookies (sessionid, csrftoken, ds_user_id, and the rest when present); `Connect` loads
  the inbox (one `thread` event per conversation with its newest messages, then
  `inboxLoaded`) and keeps the live connection up; `ListThreads("INBOX" or "PENDING",
  cursor)` pages the inbox and the request queue; `Thread`, `Messages` (older than a
  message), `SendText`, `SendMedia` (upload through the website's upload endpoint, then
  send), `SendReaction`, `Unsend`, `Edit`, `MarkRead` (the website's two calls),
  `SetTyping`, `AcceptRequest`, `DeleteThread`, `Download` (signed CDN links), and
  `SearchUsers`. Instagram keys a thread by two ids (a short "fbid" and a long one);
  the bridge keeps both and fetches the pair when a listing has not said. Events: text,
  pictures, videos, GIFs and stickers, voice notes, view-once media (and the note when
  Instagram no longer shows one), shared posts and reels as link cards, reactions, edits,
  unsends, read markers, read receipts, folder moves, typing, sign-out.
- **Kotlin (`connectors/instagram`)**: the same shape as the other two. Folders
  (UI_DESIGN.md 6.4): a thread whose system folder is PENDING, SPAM, or HIDDEN_REQUESTS
  is a request; a folder named GENERAL is General; the rest is Primary. Unread: marked
  unread by Instagram, or a message from someone else after the account's own read
  receipt. Requests are accepted or declined through the bridge; the request queue is
  listed on every connect and sync. Capabilities: native replies, delete for me, unsend
  any time, any emoji, GIFs and voice notes, typing, read receipts, edit within 15
  minutes, folders, several accounts; calls open the thread. Not built this round, and
  shown disabled with the reason: starting a chat and making a group from PingMe,
  blocking, and moving a chat between Primary and General (the website's own interface
  has no such move; Instagram's app does it).
- **Sign-in**: the instagram.com sign-in page in the in-app browser; the user taps Done
  once on the home feed; the cookies are checked by opening a session, then saved under
  `instagram/<user id>`. Refreshed cookies are saved again whenever a connection reports
  them.
- **Tests**: `IgTranslateTest` (folders, people and unread, history order, media, shares
  as cards, reactions, unsends, read receipts, a request moving to Primary),
  `InstagramContractTest` against `FakeIgBridge`.
- **Emulator finding**: the instagram.com sign-in page loads in the in-app browser (its
  fields and buttons are all there in the view tree, at full size) but paints white on
  the emulator, while the emulator's Chrome paints it. Google's sign-in page paints fine
  in the same browser. Wide viewport, overview mode, and software rendering changed
  nothing, so it looks like the emulator's graphics and Instagram's page, not the
  browser setup; the phone's real graphics should paint it. In case it does not, the
  sign-in now falls back to pasting the cookies from any browser (a `Cookie:` header,
  a cURL command, or JSON), the way the reference bridge takes them.
- Not tried against Instagram itself: the owner signs in at Gate G6.

**Gate G6 checklist, on the owner's phone:**

1. Settings > Accounts > Add account > Instagram: sign in on the page that opens, tap
   Done on the home feed. The inbox fills with your Instagram chats; the bottom bar's
   Instagram button long-press offers Primary only, General only, or both; the Requests
   row shows the request queue.
2. Send a text and a picture; get a text, a picture, a voice note, a reaction, and a
   shared reel (it shows as a card). Typing shows in the header.
3. Reply, react, edit within 15 minutes, unsend: both sides match.
4. Accept one request and decline another.
5. Settings > Accounts > Instagram > "Show General in All" off: General chats leave All.

**Shipped (2026-10-02, evening):** v0.3.0 (the Gate G3 fixes) at 5:02 PM, v0.4.0 (WhatsApp)
at 6:46 PM after the tag was moved to the timing fix, v0.5.0 (Instagram) at 7:33 PM. Each
link was emailed to the owner the moment the release existed and posted in the chat with
its report. The owner tests all three on the phone and gives notes; Signal starts only
after that list is fixed. The owner has already said WhatsApp sends do not seem to go
out; that is looked at together over wireless debugging, log tag `PingMeWhatsapp` and the
bridge's `GoLog` lines, before anything is changed.

## Gate G5 and G6 fixes: the owner's 13 notes (2026-10-02, from 8:51 PM)

The owner tested v0.3.0, v0.4.0 and v0.5.0 on the phone and gave 13 notes. All built on
branch `phase6-fixes`; every piece verified on the emulator where the emulator can show
it, the rest in tests.

1. **Reply quote vanished after sending.** The network's copy of a reply names only the
   message it answers, and the bubble drew a quote only when the text came with it. The
   store now fills the quote from the replied-to message when it saves any reply
   (`EventApplier.withQuote`), so incoming replies show their quote too.
2. **"Add to contacts" offered your own number.** The details screen took the first
   person the network listed, which for WhatsApp was you. It now takes the first person
   who is not you (who sent your messages, or who the network names "You").
3. **No RCS/SMS/MMS badge on Google Messages** rows or in the chat header; the badge
   label for Google Messages where the network itself is named (account picker, bottom
   bar) is "GM". Every other network keeps its badge.
4. **Save a voice note**: the hold menu on a voice note, sound, or file offers Save,
   which copies it to Downloads/PingMe through the media store (no permission needed),
   fetching it first when it is not on the phone yet.
5. **PingMe in the share menu**: `MainActivity` takes SEND and SEND_MULTIPLE for text,
   pictures, videos, sounds and files; `ShareRequests` carries the share to the picker
   (`ShareRoute`), which lists chats, and people once a search is typed, and sends one
   message per pick, one after the other, as Google Messages does.
6. **Multi-select rows**: hold an avatar; a bar above the list offers mark read, mark
   unread, mute, archive, low priority, delete (confirmed once) for all picked rows
   (`BulkAction`, `RowActions.bulk`).
7. **Blurry pictures over RCS**: on our side, and incoming only. Google Messages hands
   a picture over as a thumbnail first and the full-size file a moment later; the store
   kept the thumbnail's path when the full-size reference arrived, so the full picture
   was never fetched. The store now drops the path when the network names a different
   file for the same part, and the file is named by that reference, so the full picture
   downloads beside the thumbnail. Outgoing: PingMe uploads the picked file untouched;
   any shrinking on that side is Google Messages' or the carrier's, not PingMe's.
8. **WhatsApp names**: the bridge no longer caches a missing name (the contact list
   syncs after connect, and a miss was remembered for good); when the contact list
   arrives or changes, the bridge sends a "contacts" event and every chat is sent again
   with names. Contacts also flow to the store as people (`PeopleUpdated`), so the
   new-chat search finds them.
9. **WhatsApp's two addresses per person**: every message, receipt, typing notice,
   history conversation and participant is reduced to one address, the phone-number
   form, whenever the message names it or the device store knows the pairing
   (`Session.canon`, `canonicalChat`, `canonicalSender`); history requests still use
   the phone's own key for the chat. A number typed into New chat therefore lands in the
   same thread as the hidden-address messages. Threads that were already split before
   this fix stay as they are in the store until deleted.
10. **Ticks like Google Messages** on every network: one hollow circle-check sent, two
    delivered, two filled read, the ticks cut out in the bubble's colour
    (`StatusMark`, new icons `ic_check_circle`, `ic_check_circle_outline`).
11. **Instagram sign-in page blank.** Found with Chrome's inspector attached to the live
    in-app browser (debug builds now allow it): every viewport-height unit resolved to
    0, so Instagram's page, laid out with `100vh`, collapsed to nothing. Chromium's
    `AwLayoutSizer` forces the page's layout height to zero whenever the WebView's
    layout params say wrap-content, and Compose gives every embedded view wrap-content
    params. Match-parent params fix it; the page draws and takes typing on the emulator.
    The paste fallback exists and shows after "I have signed in" when no sign-in is
    found; that is why it looked absent. The earlier note blaming the emulator's
    graphics was wrong.
12. **WhatsApp voice notes failed with 403**: the files had expired on WhatsApp's
    servers (older messages from history). The bridge now does WhatsApp's own media
    retry: it asks the phone to upload the file again and fetches the new path
    (`Session.downloadAgain`), waiting up to 45 seconds for the phone's answer. The
    message's chat and id travel with the media reference; older references get them
    from the attachment id.
13. **Housekeeping at link time** (key shares, sync notices, "peer" messages between
    your own devices) is dropped in the bridge: nothing is shown and no chat is made.
    Real messages of kinds PingMe cannot show still say so. The "You" chat the owner
    already has stays until deleted.

Also: `PeopleUpdated` connector event; the attachment upsert rule in `MessageDao`;
`SaveToGallery.saveToDownloads`.

## Phase 6, network 3: Signal (2026-10-02, late evening)

**Built on branch `signal`.** Signal through mautrix-signal's `signalmeow` (v0.2609.0) over
Signal's own `libsignal` (v0.102.2, Rust), bound by gomobile as `gobridge/sig`.

- **The native library.** `libsignal_ffi.a` must be built from Rust for each phone chip.
  This machine has no C toolchain, so a GitHub workflow (`.github/workflows/libsignal.yml`)
  builds it once per version for arm64-v8a, x86_64, armeabi-v7a, and Linux amd64 (for the
  bridge's host tests) and attaches them to the release `libsignal-v0.102.2`;
  `gobridge/build.sh` downloads them when missing (`gh release download`, so CI passes
  `GH_TOKEN`). The three Android ones are built and attached. The Linux one is not yet:
  GitHub stopped running jobs ("spending limit", see below) before it could.
- **Host builds of the bridge** need a C compiler for the Signal bindings. This machine
  has none, so: zig (`~/.local/opt/zig`) as `CC="zig cc"`, a local `go.work` (ignored)
  pointing at a copy of mautrix-signal with one extra build tag (`hostclang`) on its two
  compiler-shim files, and zlib built with zig. `GOBRIDGE_HOST_TAGS=hostclang` and
  `GOBRIDGE_SKIP_HOST_CHECKS=1` in `build.sh` exist for that; CI never sets them.
- **Linking** (`SignalLogin`): Signal links a new device only by the phone's Signal app
  scanning a QR code. The QR step's Share button now sends the code as a picture (PNG via
  the file provider) with the link as text, for another screen (owner: will have one). A
  fresh code every 45 seconds, up to six. The phone is asked to transfer its message
  history (the archive signalmeow calls a transfer); after linking the store is renamed
  from `signal/link-<time>.db` to `signal/<account id>.db`.
- **Chats and history** come from that archive (`BackupStore`): the chat list with names,
  unread, archived, pinned, mute; pages of older messages on demand (`Messages`). Signal
  keeps nothing on its servers, so without the transfer only live messages show.
- **Live**: messages (text, pictures, video, voice notes, files, stickers, contacts),
  quotes, reactions (your own remembered so taking one away can name it), edits,
  deletes for everyone, typing, delivery and read receipts (matched by timestamp), reads
  on your other devices, group changes (the group is fetched again), contacts (as people
  for the new-chat search). Sends: text, one file per message with the text as caption,
  reactions, edit, delete, read receipts, typing, new chat by phone number (CDSI lookup).
- **Ids**: chat = account id (UUID) or group identifier (44 chars); message =
  "<chat>/<sender>:<timestamp>"; person = account id.
- **Tests**: Go `sig` conversion tests (run on the host with zig and the Linux library);
  `SigTranslateTest`; `SignalContractTest` against `FakeSigBridge`.
- Not built this round, shown disabled with the reason: making groups, blocking,
  message requests (Signal itself).

**GitHub Actions stopped (2026-10-02, 10:00 PM):** every job fails at once with "recent
account payments have failed or your spending limit needs to be increased": the private
repository's free minutes for the month are used up. Until the owner raises the limit,
makes the repository public, or puts the sideload keystore on this machine, no release
can be built as an installable update. The owner was told the three options.

## Phase 6, network 2: Telegram (2026-10-02, late evening)

**Built on branch `signal`** (one branch for the rest of Phase 6). Telegram through TDLib
1.8.67, prebuilt for all four chips from Maven Central (`io.github.tdlib-android:core:0.1.1`,
published 2026-09-13, Boost licence), as the plan allows instead of an hours-long native
build. Its Java classes come in the same package; no Go bridge is involved.

- **App credentials** (api_id, api_hash from my.telegram.org, which the owner created
  tonight) are never committed: CI passes the `TELEGRAM_API_ID` and `TELEGRAM_API_HASH`
  secrets, local builds read `telegram.apiId` and `telegram.apiHash` from
  `local.properties`, and the connector module's `BuildConfig` carries them. Without
  them the sign-in says so and stops.
- **Sign-in** (`TelegramLogin`): phone number, the code Telegram sends, the two-step
  password when the account has one; TDLib's own states drive the steps. TDLib's
  database starts in `telegram/link-<time>` and moves to `telegram/<user id>`; the
  credential ref `telegram/<user id>` names it.
- **Chats**: TDLib keeps the chat list itself; on connect the main list is loaded until
  TDLib says there is no more, then read back. Group members come from the group info
  (basic groups) or the member list (supergroups, up to 200; channels have none). A
  forum group becomes a space with one chat per topic (`<chat id>#<topic id>`,
  `ChatFolder.TOPIC`, `SpaceKind.TELEGRAM_FORUM`) as UI_DESIGN.md 10.4 asks.
- **Messages**: text, photos (largest size), videos, GIFs, voice notes, audio, files,
  stickers, shared contacts (as a vCard), places (as GeoJSON), polls as text. Quotes,
  edits, reactions (whoever reacted, you when it is yours), read marks from the chat's
  outbox read mark, pending and failed sends. History pages come from TDLib, asked again
  while it fills a page.
- **Live**: new messages, send succeeded (the real id replaces the stand-in), send
  failed, edits, deletes for everyone, reaction changes (the message is fetched again),
  read marks both ways, unread counts, typing, titles, users, topics.
- **Sends**: text, one file per message with the text as its caption, reactions (removal
  names the chosen one), edit, delete for everyone, read receipts, typing, new chat by
  phone number, new basic group, block. Reactions offered: Telegram's free set
  (`TelegramConnector.FREE_REACTIONS`; Premium ones are not offered), per UI_DESIGN.md 5.4.
- **Tests**: `TelegramContractTest` against `FakeTelegramBridge`, which answers TDLib's
  requests with TDLib's own classes (no native library in tests).

## Phase 6, network 4: Google Voice (2026-10-02, late evening)

**Built on branch `signal`.** Google Voice through `libgv` from mautrix-gvoice (v0.2605.0),
bound by gomobile as `gobridge/gv`; Kotlin connector `connectors/gvoice` in the shape of
the Instagram one.

- **Sign-in**: the Google sign-in page in the in-app browser, finished by itself when
  voice.google.com shows the inbox; the google.com cookies (SID, HSID, SSID, APISID,
  SAPISID and the __Secure ones) are the credential, checked by asking Google Voice for
  the account, and saved under `gvoice/<number>`. Refreshed cookies are saved as they come.
- **Chats and history**: the thread list from Google Voice's own web API, with each
  thread's newest messages; older pages by the token Google hands out (and from the top
  of the thread after a restart). Names from the account's contacts, looked up by
  number for unknown ones. Archived and spam threads are listed as chats too.
- **Live**: Google's push channel nudges a re-read of the thread list (also every two
  minutes); new items and read-state changes flow from there. Texts, picture messages
  (any picture type; videos and files when Google carries them), calls, missed calls,
  and voicemails (as their transcript) all show.
- **Sends**: text and pictures (JPEG, PNG, GIF, WebP, BMP, TIFF; the reference bridge
  sends nothing else), a quoted first line for replies (UI_DESIGN.md 5.2), reactions as
  "Reacted … to …" texts (5.4), read marks, block, a new chat by number (Google Voice
  makes the thread on the first send: id `t.<number>`). No typing, no edits, no
  deleting for everyone: Google Voice has none.
- **Not done, and why**: Google stamps each send with a token computed by an anti-abuse
  script that the reference bridge runs in a hidden Electron browser. The reference
  bridge sends without the stamp when that browser is absent, and so does PingMe for
  now. If Google starts refusing unstamped sends, the same script can run in a hidden
  WebView on the phone; the hook for it is the `TrackingData` field of the send request.
- **Tests**: Go `gv` conversion tests; `GvoiceContractTest` against `FakeGvBridge`.

## Phase 6, network 6: Messenger (2026-10-02, late evening)

**Built on branch `signal`.** A personal Messenger account through `messagix` from
mautrix-meta (v0.2609.0, the same module Instagram uses), bound by gomobile as
`gobridge/fb`; Kotlin connector `connectors/messenger` in the shape of the Instagram one.

- **Sign-in**: the facebook.com sign-in page in the in-app browser; the cookies `xs`,
  `c_user`, and `datr` are the credential (plus `sb`, `fr`, `wd`, `presence`, `oo`, `dpr`
  when present), checked by opening a session, saved under `messenger/<user id>`. The
  same paste box as Instagram when the page does not finish. Refreshed cookies are saved.
- **Chats**: the inbox page's own table of threads (the newest conversations with their
  newest messages, members, and names), then up to four pages of older threads through
  the library's thread fetch. Meta sends the data as "Lightspeed" tables of rows, so the
  Go side folds every table it sees (the first page and every live update) into what it
  knows and reports threads, messages, reactions, edits, unsends, read marks, and typing
  from there. A one-to-one thread's key is the other person's id, and its name and
  picture are theirs; groups carry their members and admins. Message requests
  ("pending", "other", "spam") show in Requests (UI_DESIGN.md 6.4); replying from there
  accepts the request, as the website does, and Accept and Decline exist as well.
- **Messages**: text, pictures, videos, GIFs, voice clips, files, stickers, and shared
  cards (as a link card, UI_DESIGN.md 10.12); replies with the quoted text; edits;
  unsends; reactions (any emoji; a removal names the emoji it had, which Meta's row does
  not); the other side's read marks and typing. Older history by Meta's fetch-messages
  task from a message's time.
- **Sends**: text, one file per message with the text on the first, replies, reactions,
  edits (Meta allows 15 minutes), unsend, read marks, typing, a new chat by user id or
  by name search.
- **Media**: fetched from Facebook's CDN with the browser headers the reference bridge
  uses. Not verified against a live account yet: Facebook sometimes redirects a video to
  a CDN host that wants ranged requests; PingMe follows the redirect and takes 200 or 206.
- **Tests**: Go `fb` tests (tables of rows become threads, members, history, live
  messages, reactions, edits, unsends, receipts, typing, gone threads; conversion of
  shares, stickers, voice, unsent, system rows); `MessengerContractTest` against
  `FakeFbBridge`; `FbTranslateTest`.
- **Verified on the emulator**: see the Messenger line under Gate G7 below.

## Phase 6, network 7: Facebook Page (2026-10-02, late evening)

**Built on branch `signal`.** `connectors/fbpage`, Kotlin only, over Meta's official
Messenger Platform (Graph API v25.0) with a Page access token: the one Meta connector
with no account risk (DESIGN.md 5.2).

- **Connecting**: a paste box for the Page access token (from the owner's Meta developer
  app: Messenger settings > Access tokens > Generate token for the Page). The token is
  checked with `GET /me?fields=id,name`; a refused token puts the error on the box and
  asks again. Saved under `fbpage/<page id>` with the Page's id and name; the account is
  named "Page: <name>".
- **Polling**: PingMe has no server for Meta's webhooks, so the Page's conversations
  (`GET /{page}/conversations?platform=messenger`, with each one's ten newest messages)
  are read every 20 seconds while the connection service runs, and right after a send.
  Every poll ends with the "checked … ago" state (UI_DESIGN.md 6.6). A dead token
  (Graph error 190 or 200) ends the connection with "paste a new one".
- **Messages**: text, pictures, videos, animated GIFs, files, stickers, and shares (as
  link cards). Older history by Meta's cursor paging of `/{conversation}/messages`.
- **Sends**: text through the Send API as `RESPONSE`; files uploaded first to
  `/{page}/message_attachments` (the phone has no public address) and sent by attachment
  id; reactions as the emoji in a text (Meta's Page API has none); `mark_seen` when a chat
  is read. Meta's 24-hour window is checked before every send (the person's last message
  time) and the send is refused with a plain reason, as well as when Meta refuses it.
- **Deviations, for the owner**:
  - The plan names a WorkManager periodic job for the polling. The connection service
    already runs whenever any account wants a connection, so the poll runs inside the
    connector's event flow instead, with a 20-second interval while the service is up.
    A separate background job would also need a way to feed events into the store from
    outside the service, which does not exist yet. Say if the job is wanted anyway.
  - UI_DESIGN.md 6.6 asks the composer to show a notice and disable Send when the
    24-hour window has closed. The connector refuses the send with that reason today;
    the composer notice is not built (the composer has no per-chat "cannot reply"
    signal yet). Noted for the next UI pass.
  - The polling interval is fixed at 20 seconds; the "user-chosen interval" setting is
    not built yet (no account-level settings page carries it).
- **Not verified against a live Page**: the owner will get the token later. Every call
  is shaped from Meta's reference pages (conversations, message, Send API, attachment
  upload) read tonight; the pretend Page in the tests answers in those shapes.
- **Tests**: `FbPageContractTest` against `FakePageApi`; `FbPageTranslateTest` (Meta's
  times, chats, fresh-only polling, kinds of files, shares, the Page's own messages, the
  token check, the 24-hour refusal, the polled states).

## Gate G7 to G10: every Phase 6 network in one build (2026-10-02, 11:45 PM)

**Version 0.7.0** carries all of Phase 6: WhatsApp, Telegram, Signal, Google Voice,
Instagram, Messenger, and the Facebook Page inbox. Full `./gradlew check` green on the
`signal` branch before the pull request.

**Verified on the emulator tonight** (demo build, Add account):
- Messenger: the row shows with its risk note; the facebook.com sign-in page draws in
  the in-app browser with the "I have signed in" button under it.
- Facebook Page: the row shows with its 24-hour note; the token box draws; a made-up
  token goes to Meta and comes back refused ("Invalid OAuth access token - Cannot parse
  access token") on the box, with Continue ready for another try. That exercises the
  HTTP layer and Meta's error shape end to end.
- Signal, Telegram, Google Voice: sign-in screens verified earlier tonight (their entries
  above).

**Not verified, and why**: nothing past the sign-in screens, because the emulator has no
accounts on these networks. Live traffic (history, sends, media, reactions) is covered by
each connector's tests against its pretend network, and by the reference bridges' code
paths they follow.

**Owner's test checklist, phone, version 0.7.0** (one network at a time; say what you see):
1. Signal: Add account > Signal. Share the QR picture to your other screen, scan it from
   your phone's Signal (Linked devices). Expect: the account appears, chats fill from the
   phone's transfer, a new message arrives live, a reply goes out.
2. Telegram: phone number, then the code Telegram sends. Expect: chats and topics
   (forums as spaces), live messages, a reaction from the allowed set.
3. Google Voice: sign in to Google in the page. Expect: threads with names, a text sent
   and received, a picture received.
4. Messenger: sign in to Facebook in the page, then "I have signed in". Expect: chats
   with names and pictures, Requests folder if you have any, a text each way, a reaction,
   a reply with quote, a picture each way.
5. Facebook Page: paste the Page token when you have it. Expect: the Page's
   conversations, "checked … ago" on the account row, a reply to someone who wrote
   within the last day; a refusal with the reason for someone older than a day.
6. Instagram: the sign-in page draws (fixed tonight); sign in and check the inbox.

## Gate G7 fixes, round 1: the owner's notes on 0.7.0 (2026-10-03, afternoon)

**Branch `fixes-g7`.** The owner's run-through of 0.7.0 on the phone gave 21 notes. The
ones that need no phone are built here; the rest wait for a log from the phone.

Built now:
- **Share screen search box** (note 4): the box fed its text through the model's flow and
  back, so a fast keystroke landed while the box was being redrawn with an older value and
  the cursor jumped. The box now keeps its own text and only passes it out.
- **Starting a WhatsApp chat listed each person twice** (note 8): the bridge's contact list
  now folds a contact WhatsApp also files under its hidden id into the phone-number entry
  (the hidden id is looked up in whatsmeow's id map), so the hidden id never shows.
- **Instagram shared posts show their picture** (note 11): the bridge already carried the
  picture's address; the connector now fetches it (two at a time, in the background) and
  reports the message again with the picture, so the link card draws it. Also for history
  pages as they load.
- **Instagram links open inside PingMe** (note 12): a link card from an Instagram (or
  Messenger) chat whose link is on that network's site opens in a page inside PingMe,
  signed in with the account's own cookies, with Back and an "Open in browser" action.
  Everything else still goes to the phone's browser.
- **Telegram's "X joined Telegram" chats** (note 16): a private chat whose only message is
  that note is not a chat here (it appears once the person writes); such chats an earlier
  build listed are removed on the next connect; Telegram's service notes read as words
  ("Joined Telegram", "Pinned a message") instead of "not supported".
- **Remove account** (note 18): the account page gets Remove account with a confirmation;
  it disconnects, forgets the saved sign-in, and deletes the account row, which takes its
  chats, messages, and people with it (the database cascades).

- **The inbox opens at the top** (added note): coming back to the inbox landed wherever
  the list was last left; it now scrolls to the top every time the inbox is shown.
- **Chats read here stay read** (added note): a network's listing after a reconnect
  carried its own unread count (its read mark had not taken, or had not been sent), and
  PingMe copied it over the read state. Each chat now remembers when it was last read on
  this phone (`readUpTo`, schema version 5), and a listing with nothing newer than that
  cannot make it unread again. Reading a chat and sending in it both set the mark.
  The owner's cases were Google Messages and Telegram, not Instagram; both get read
  marks only while "Send read receipts" is on for them, and the Google Messages bridge
  also hands recent messages back after the app reopens, which the connector (its memory
  gone with the restart) reported as new. The applier now treats a message the store
  already has, or one no newer than the chat's read mark, as an update: no unread bump.
- **Deleted chats stay deleted** (added note): deleting a chat removed its row, and the
  network's next listing simply made it again, unread. Deleting now also leaves a
  marker with the time (`chat_tombstones`, schema version 5); a listing, a history page,
  or a message no newer than the marker is ignored, and anything newer lifts the marker
  and brings the chat back as a new one.

- **Messenger stuck on "Reconnecting"** (note 13, from the phone's log): Facebook was
  fine. A reaction arrived for a message PingMe had not stored (older than the history
  kept), the database refused the reaction row (foreign key), the exception ended the
  session, and every retry met the same reaction. Two fixes: the applier ignores a
  reaction to a message it does not have; and the supervisor skips, with a warning, any
  one event the store refuses instead of ending the connection.
- **The Go bridge's log now reaches logcat** (`gobridge/alog`, tag `GoLog`): on Android a
  process's standard error goes nowhere, so the bridge's log had been invisible on the
  phone; the Kotlin side only logs failures. Needed to diagnose the rest.

- **Signal's new-chat list** (note 14, from the phone's log): the phone's Signal sent 226
  contacts at link time, but the library keeps only those that came with an account id
  (one did) and skips the rest. Signal's own app lists "contacts on Signal" by asking the
  directory about every address-book number. PingMe now does the same: an `AddressBook`
  (the phone's contacts, read only when allowed; the new-chat screen asks once) feeds the
  numbers to the bridge's `LookupNumbers`, in batches of 100, and everyone found becomes
  a person named from the address book (`refreshPeople` on the connector, run after each
  connect and when the new-chat screen opens). The directory answers with two kinds of id;
  people who hide their account id come back with only their number id, which PingMe
  wrongly read as "not on Signal" (Nida Allam). Both count now: such a chat's id is
  "PNI:<uuid>" and sends go to that service id.
- **Messenger's first listing** ran before its socket was up ("could not list older
  chats"); the bridge now waits up to 15 seconds for the socket before listing more.
- **Notifications not clearing** (note 15): tried twice on the phone with the log open,
  reacting and not reacting; both cleared. Not reproduced; the owner will report the next
  case with the chat and the time.
- **Instagram read marks** (note 10): PingMe's own "Send read receipts" was off for
  Instagram, so Instagram was never told. Explained the two switches (PingMe's decides
  whether the network hears a read at all; the network's own decides whether the other
  person sees "Seen"); the owner set them as wanted.

- **WhatsApp's hidden ids** (notes 8 and 19, from a screenshot on the 5:37 PM build): a
  contact still showed twice, by hidden id and by number, because the library had no
  number on file for that hidden id, so the bridge's fold had nothing to fold with. The
  library fills its hidden-id map from several sources but never asks outright; the bridge
  now asks WhatsApp once per connection for the hidden id of every phone-number contact
  (the library's user lookup, batches of 50) and the library stores the answers. People
  rows an earlier build stored by hidden id are dropped when the next people list comes,
  unless a chat still lists them. A person's handle is now their number, never a raw id,
  and a contact known only by a hidden id is never listed by it. The same map is what
  files replies, reactions, and receipts that WhatsApp addresses by hidden id; whether
  that is the whole of note 20 still waits for the phone's log.

- **WhatsApp names** (note 7, from a screenshot of the new-chat list): the phone's contact
  list (names for numbers) reaches a linked device as "app state", and only when the
  client asks for it; the library does not ask on its own, and the bridge never did, so
  the names had no way to arrive. The bridge now fetches every app-state set once per
  store after connecting (the contact list first); later changes are pushed. WhatsApp's
  "0" user (its placeholder for nobody) is never a person, and the one an earlier build
  stored ("+0") is dropped with the next people list.
- **New-chat chips** (owner): the account chips read the network's name ("WhatsApp"),
  with the account's own name added only when two accounts share a network.
- **Late joiners**: WhatsApp maps a contact who joins mid-session on the next contacts
  refresh (unmapped contacts are asked about again, at most every five minutes);
  Telegram reads its contact list again when a "joined Telegram" note arrives; Signal
  looks up the address book on every connect and every opening of its new-chat screen.

- **Instagram messages named by a long number, and General chats in All** (owner, from
  the inbox): a message for a thread PingMe had not been told about made a bare
  placeholder chat, titled by the sender's user id and with no folder, which the next
  full sync replaced much later. The session now asks Instagram for the thread first,
  whenever a message comes for an unlisted thread or from an unseen sender, so the chat
  lands with its name, its people, and its folder; a General thread stays out of All.

- **A chat titled "You"** (owner): a message you sent from Instagram's own app, for a
  thread not listed yet, made a placeholder chat named after its sender, you. The
  placeholder for an outgoing message now has no title (and the thread-first fetch above
  names it properly in the normal case).
- **Share sheet** (owner): a message box under the search field. What is shared goes
  first, as its own message, and the note follows once the item is away, so the other
  side never reads the words and then waits for the picture.
- **A sent picture went blank until fetched back** (owner): the network's copy of a sent
  message names the network's file, not the phone's, and replaced the stand-in, so the
  bubble was an empty frame until the file came back down. The copy now takes over the
  stand-in's files by position; the upload's progress ring is unchanged.

**Version 0.7.1** carries every fix above. The owner chose to release without the live
WhatsApp watch (the phone stayed off Wi-Fi debugging), on the assumption that the
hidden-id mapping and the contact-list request resolve WhatsApp's inbound silence; that
assumption is the owner's and is checked on the phone after the install.

Not yet checked live (notes 2 and 20, WhatsApp): WhatsApp's inbound
silence and names (the hidden-id mapping is the lead), Instagram read marks, Messenger's
"reconnecting", Signal's contact list, notifications not clearing.

Answered, no change: note 21 (RCS pictures): the blur before 0.6.0 was PingMe's own
thumbnail; any remaining softening is Google Messages compressing outgoing RCS pictures.

### Gate G7 fixes, round 2 (2026-10-03, evening)

The owner installed 0.7.1 at 8:04 PM and found two things at once.

- **WhatsApp chats still titled by number.** The phone's log showed the contact list
  does arrive now (998 names, two seconds after connecting), and the bridge keeps it
  across restarts. The real cause was elsewhere: WhatsApp never lists one-to-one chats
  again after pairing (only groups), so a chat PingMe stored under 0.7.0, before any
  names existed, kept its number as its title for good; the people list carried the
  names, but nothing went back to the stored chats. Fixed in the store, for every
  network: when an account's people list arrives, each one-to-one chat still titled by a
  bare number or raw id takes that person's name. The round-1 note that blamed the
  missing contact-list request was only half the story.
- **Chats read under 0.7.0 came back unread, and chats deleted under 0.7.0 came back.**
  Round 1 added "read here" and "deleted here" records, but the upgrade creates both
  empty, so the first sync after the install believed Google Messages' unread marks
  again, and the deleted code chats had no deletion record to hold them back. The
  schema is now version 6, whose upgrade stamps every chat already showing as read as
  read at its last activity. Deletions made under 0.7.0 cannot be recovered after the
  fact; a chat deleted once more under 0.7.1 or later stays gone.
- The stale **"+0" chat** (WhatsApp's "nobody" placeholder, stored by an earlier build)
  is removed with the next people list and is not listed again. The **"You"** chat is
  WhatsApp's own message-yourself chat, which WhatsApp also labels "You"; it stays.

## Phase 7: people, merging, calls, spaces

Started 2026-10-03, 8:30 PM, on the owner's OK, with these owner requirements on top of the
plan: merge suggestions by phone number and by similar name or username; manual merge of
any chats across any networks (a bulk "Merge" in the inbox's multi-select, "Merge with…"
in Chat details, split per member); every suggestion editable (remove a proposed member,
add a chat that was not suggested, dismiss); a merged chat's bubbles keep only their
network colour, with the network badge beside the time and ticks when a bubble is tapped;
the merged chat's header badge opens a dropdown ("All" or one network) that filters the
bubbles and sets the composer chip; a merged chat opens on the network of its unread
messages when they are all from one network, otherwise on All; the bottom bar holds four
chosen buttons and a fixed fifth "More" button listing everything else. Phase 8 waits for
the owner's word.

### Gate G7 fixes, round 3 (2026-10-03, 9 PM)

- The round-2 renaming never reached a WhatsApp chat made from your own outgoing message
  (such a chat lists no participant), so names stayed numbers. A person's chats are now
  also found by the chat whose address is the person's and by a title that is the number.
- That fix then named almost every WhatsApp chat "Terry Sanford": the owner's own number
  is on a contact card of that name, every WhatsApp chat lists "you", and the contact
  matcher linked "you" to that card and renamed every chat you are in. Your own entry is
  now never matched to a contact and never renames anything, and on the next start every
  chat wrongly carrying that name takes the other person's own name or number back.
  The owner's words: the chats "must be named correctly", whatever the card says.

- **Eric, Tracy, and the deleted code chats back again, on the round-3 build.** Two
  causes, both read from the code: a deleted chat holds no messages, so the "fetch
  history for chats with nothing stored" step fetched its old pages and rebuilt it past
  the "deleted here" record; and "read" was stamped at the chat's reported time, which
  Google Messages can report older than the chat's newest message, so that message
  counted as unread on every sync (chats whose last message was yours were unaffected,
  which was the pattern). Now history pages for a chat deleted here are dropped, "read"
  reaches the newest stored message, and the upgrade (schema 8) restamps chats already
  showing as read. Eric and Tracy, unread at the time of the upgrade, need one more
  "mark read"; after that it holds.
- **Photos missing for many people** (owner): not yet explained; the log of a full connect
  is needed (fetch failures are logged). The photo file is now handed to the image loader
  as a file, not a bare path, removing one possible cause.

### P7.1 Contacts

- People are matched to the phone's contacts by phone number: the address book is read
  (name, numbers in international form, lookup key, photo address) when contacts are
  allowed, and every person with a number that is in it carries the contact's lookup key,
  name, and photo. The match uses the digits, and the last ten digits for a number written
  without its country, so "(555) 555-0123" and "+15555550123" meet.
- A one-to-one chat still titled by a bare number or raw id takes the contact's name; a
  network's own title is kept otherwise (WhatsApp's names already are the phone's).
- The phone's contacts are watched; a change matches everyone again after it settles.
  Allowing contacts from the new-chat screen does the same at once.
- Avatars show the contact's photo, then the network's profile photo, then the initials
  tile, in the inbox, pinned tiles, the chat header, Chat details, the new-chat list, and
  notifications (the sender's picture). Someone known only by a number gets a plain
  person mark instead of digits (owner).
- Instagram and Messenger profile pictures now reach PingMe (owner, 2026-10-03: "grab
  profile pictures from IG"): the networks give short-lived links, which the service
  fetches once into app storage (`files/avatars`) and keeps. WhatsApp, Telegram, and
  Signal profile photos are not fetched yet: each needs a request per person or a
  decrypt, and is listed for a later step.
- Deviations from the plan: numbers are normalised with Android's own PhoneNumberUtils
  (the phone's copy of libphonenumber) and the contacts provider's normalised column,
  not a bundled libphonenumber; contact photos are shown from the contacts provider's
  own address rather than copied into app storage, so a changed photo shows without a
  copy going stale. Schema version 7 adds the contact name and photo to a person.

### P7.2 Merged chats, the store and the service

- A merged chat is a chat row of its own (id `merged/<random>`, so no connector is ever
  asked about it); its members point at it. Members leave the inbox list; the merged row
  carries their summed unread count and newest activity, kept in step on every write, and
  pins, mute, archive, low priority, obscure, and notification settings live on it.
- Unread badges count the members on their own networks (so a merged chat's unread shows
  under the right network filter), under the merged chat's mute, archive, and low-priority
  settings; the merged row itself never counts twice.
- Reading the merged chat reads every member on its network (read markers go out per
  member). Deleting it deletes the members. Splitting the last pair, or deleting a member
  of a pair, dissolves the merged chat and the remaining chat returns to the inbox.
- Merging accepts chats, members (which bring their whole merged chat), and merged chats;
  the result lands in the merged chat named (or the first one among them), or a new one
  titled by the contact's name when any member has one. Groups are refused with a plain
  reason. A member whose person has no contact link takes the link the others have, so an
  Instagram or Messenger person merged with a phone contact gets that contact's name and
  photo.
- A member's new message notifies as the merged chat: its settings, its name, its screen.
- WhatsApp's own hidden-id fold (round 1) carries a membership over to the number's chat.
- Deviation from the plan: the plan's `MergeLink` (person to contact) is not what holds a
  merge together; membership is on the chat (`mergedInto`), which is what every screen and
  the unread rule need. The person-to-contact link lives on the person (P7.1).

### P7.3 Merge suggestions

- Two or more one-to-one chats on different accounts are proposed as one person when
  their people share a phone contact, a phone number (digits compared, the last ten for a
  number written without its country), or a name or username that reads the same: case,
  accents, punctuation, emoji, and tag lines after "|" or in brackets are dropped, and
  dots, underscores, and dashes read as spaces, so "sam.ortiz", "SAM_ORTIZ", and "Sam
  Ortiz 🌟" meet. Two chats on the same account never make a suggestion on their own.
- Dismissing a suggestion hides exactly that set of chats; a new chat joining the set
  brings it back. Nothing merges on its own.

### P7.4 Merging on screen

- **Suggestions**: a card at the top of the inbox ("2 people appear on more than one
  network · Review") and an avatar-menu entry open the suggestions screen. Each card
  names why (same contact, same number, same name), lists the proposed chats with their
  network badges, lets you leave any chat out, add any other one-to-one chat from any
  network (a picker with search), dismiss, or merge (owner, Phase 7).
- **Bulk Merge**: hold an avatar in the inbox, pick any chats, tap Merge. A group among
  them is refused with its name.
- **Chat details**: an ordinary one-to-one chat offers "Merge with…"; a merged chat lists
  its networks, with the one the composer starts on (tap to change), a split button per
  member, and "Add a chat…". Splitting the last pair dissolves the merged chat and the
  screen closes. The header names every network the chat spans.
- **Inbox rows** for a merged chat carry a badge per network, preview the newest message
  across the members, show typing from any member, and appear under every network
  filter they have a member on.

### P7.5 The merged chat on screen

- One timeline: a merged chat shows every member's messages in time order. Each bubble
  keeps only its own network's colour; when a bubble is tapped and its time and ticks
  appear, a small network badge sits beside them, and nowhere else (owner, Phase 7).
- The header's badge becomes a dropdown: "All networks" or one member, with the current
  choice ticked. One network narrows the bubbles to it and sets the composer to it; "All"
  shows everything and sets the composer to the chat's default account. The chip can
  still be changed by hand; the next dropdown choice moves it again.
- Opening: unread messages all from one network open the chat narrowed to that network;
  unread from two or more networks, or none, open on "All".
- Composer chips, one per member, above the composer: the filled one is where the next
  message, typing notice, and scheduled send go; a disconnected account's chip is outlined
  in the error colour and says "Not connected" to a screen reader. The attach, GIF, and
  voice offers follow the chosen member's network, as do the call buttons.
- Search in chat and "jump to date" cover every member; older history is asked of every
  member's network.
- Reading, pins, and typing come from all members. The chat's people span accounts.

### P7.6 Calls

- The chat header's phone and video buttons were built in Phase 2 (each does the most
  direct thing its service allows: the dialer, Google Meet, or the call entry WhatsApp,
  Signal, and Telegram register in the phone's contacts; otherwise the app opens). In a
  merged chat they now call on the network the composer is set to, with that member's
  number.
- Chat details of a merged chat show a phone and a video button on each member that can
  take one, so any of the person's networks can be called from one place.
- What each button actually does on the owner's phone is recorded at Gate G11 (the plan
  asks for it); it cannot be known from here.

### P7.7 Spaces and the bottom bar

- Spaces from the networks and spaces the user makes (Settings > Spaces, built at Gate G1)
  already list and filter; this step changes the bar as the owner decided on 2026-10-03:
  the bottom bar holds four chosen buttons, picked exactly as before through the same
  editor, and a fixed fifth button, **More**, that lists every filter and space not in the
  bar, each with its unread dot. Picking one opens the inbox on it and More reads as
  selected while it shows. Anyone who had five picked keeps the first four; the fifth moves
  into More. The rail on wide screens does the same.
- UI_DESIGN.md 3.1 and 10.4 and the decisions log carry the change.

### Owner notes on the first Phase 7 build (2026-10-03, 9:53 PM)

- **The merge banner** in the inbox is gone. Merging lives under Settings > Merge chats:
  "Merge chats you pick" (any one-to-one chats from any networks) and "Suggestions" (with
  the count). The avatar menu keeps its "Merge suggestions" line.
- **Six buttons in the bar** where five were agreed: the saved bar still held five picks
  from before the More button, and the four-button limit was applied only on edit, not
  on load. A saved bar now loads as four plus More.
- **The coloured network circles** in the bar were gaudy next to the line icons. Each
  network now has a plain line icon in the same style (Material Symbols that say what the
  network is for: a text bubble for Google Messages and SMS, a handset for WhatsApp, a
  paper plane for Telegram, a padlock for Signal, a voice mark for Google Voice, a camera
  for Instagram, a chat bubble for Messenger, a briefcase for a Facebook Page). No brand
  logos are bundled, as before. The same icons mark the account chips on the new-chat
  screen.
- **Group chat avatars** are made of the members' avatars, not counting you: two side by
  side, three in a triangle, and so on round to nine in a nonagon; ten or more become a
  multicoloured asterisk with one arm per member (owner, 2026-10-03).
- **Profile photo fetches** now run four at a time; a first sync started hundreds at once
  and many timed out, leaving photos missing at random.

### Owner notes on the suggestions screen (2026-10-03, 9:52 PM)

- Each suggestion card now lets you pick which member the merged chat **sends from** (a
  radio per member) and which **picture** stands for it (tap a member's picture; "Use the
  contact photo" when a contact has one), and every member and every picker row shows the
  **number** it goes by, or the username on a network without numbers, so two chats with
  one person on one network can be told apart. Chat details show the same under each
  member of a merged chat.
- Instagram chats were missing from the picker because PingMe only listed the newest
  four pages of the Instagram inbox, about eighty chats. It now lists the inbox newest
  first and stops once a page holds nothing from the last thirty days (owner: recent
  Primary chats, not the whole inbox; General chats inside the thirty days come along and
  stay out of All as before). The request queue stays one page.

### Owner note on the network filters (2026-10-03, 10:16 PM)

- A merged chat was listed under every network it had a member on, placed by its newest
  message on any network, so WhatsApp's list led with people the owner has never spoken
  to on WhatsApp. Under a network filter a merged chat now stands for its member on that
  network alone: it appears only when that member holds a message, and is placed and
  previewed by that member's newest message. The same rule for every network.

### Owner note on merged rows (2026-10-03, 10:30 PM)

- A merged chat's row carries no network badges. It is marked instead by a thin ring in
  the theme's accent colour orbiting the avatar: the avatar's own shape, a little larger,
  with a gap between ring and avatar. The ring is drawn outside the avatar's bounds, so
  every avatar stays the same size, merged or not, and rows keep their layout. Pinned
  tiles ring the same way, outside the unread ring. Single-network rows keep their badge
  and have no ring.

### Owner note on the merged composer (2026-10-03, 10:35 PM)

- The row of network chips above the composer is gone (too much room, and a scroll to
  find a network). The GIF button moved into the + menu, the text box widened into its
  place, and a small colour-coded network badge sits inside the box on the left with a
  placeholder that names the network: "Send a Google Message", "Send a WhatsApp message",
  "Send an Instagram DM", "Send a Signal", "Send a Telegram", "Send a Facebook Message",
  "Send with Google Voice". In a merged chat the badge opens the same network menu as the
  header's. Every + menu option is now tappable on its label as well as its button.
- Gone with the chips: the red-lined "not connected" mark on a member. The badge does not
  show connection state yet; the connection chip under the inbox's app bar still does.
- UI_DESIGN.md 10.15 should read "a badge inside the box" rather than "a chip left of the
  field"; updated.

### Owner note on opening a merged chat (2026-10-03, 11 PM)

- A merged chat opens, header, bubbles, and box alike, on the one network its unread
  messages came from; with no unread, or unread from several networks, on its default
  network. "All" is a choice in the header menu, never the opening state.

### Owner notes on the first full Phase 7 build (2026-10-04, 12:03 AM)

Confirmed by the owner on the phone: Eric and Tracy stay read, the deleted code chats stay
deleted, and the WhatsApp chats are named correctly.

- **Bottom bar labels** wrap to a second line ("Low priority", "Google Messages").
- **The orbiting ring is now the unread mark**, not the merged mark (owner: "I like the
  orbiting ring so much"): an unread row or pinned tile shows the ring in the accent
  colour (outline colour when muted) around its avatar, the row keeps its soft tint, and
  the dots on rows and tiles are gone. Merged chats carry no mark for now; the owner will
  choose one later. They still carry no network badges.
- **Search results** show each chat's network badge and its number or username, and the
  person's photo, so three "Quiana Parler" rows read apart.
- **Missing Instagram pictures**: found in the code. A message's sender was written to the
  store directly, bypassing the photo step, so every new Instagram message overwrote the
  person's downloaded picture with the network's web link, which the app cannot show.
  Exactly the people with recent messages lost their pictures. Senders now go through the
  same step as everyone else.
- **Chats still named after your own card** (an "Ali Aksahin" chat under a hidden id was
  still "Terry Sanford"): the repair now runs on every start, from the card that holds
  your number, and a chat whose own person PingMe cannot name falls back to its address
  rather than keep a wrong name.
- **Missing WhatsApp chats** (Gina Orr, Quiana Parler, among others, present in
  WhatsApp's own list but not PingMe's WhatsApp filter): not yet explained. The build now
  logs every inbound WhatsApp event by kind, so the next capture shows whether WhatsApp
  delivers them at all; the filter rule above also hides a merged chat whose WhatsApp
  member holds no stored message, which may be part of it.

### Owner notes of 2026-10-04, morning (five)

1. **Instagram General chats in the inbox** (Michael Robbins in All; Chris Rushton's
   General message not seen). A message for a thread PingMe knew only from earlier
   messages, never from a listing, carried no folder, so it counted as Primary. The
   thread-first fetch now also runs for a known thread whose folder PingMe never learned,
   and the folder Instagram reports is written to the phone's log. Chris Rushton's message
   most likely did arrive and sits under General (hidden from All by the switch); to be
   confirmed on the phone.
2. **The box badge switched only where the message would go.** It now moves the whole
   chat, header and bubbles included, exactly as the header's menu does.
3. **"A view-once photo or video that Instagram no longer shows" on a kept video.**
   Instagram sends a photo or video taken in the chat in one of three modes: view once,
   allow replay, or keep in chat. PingMe treated all three as ephemeral and called any one
   without an address "gone". Now only a viewed or replayed one is gone; a kept one is an
   ordinary photo or video, not ephemeral, and when the live event carries no address the
   file is fetched by its id from the thread's recent messages when wanted (the way the
   reference bridge refreshes media). View-once media that does arrive with an address is
   kept, as the design says.
4. **Quiana's merged chat opened on Instagram with no unread, not her default.** The chat
   screen's state outlives one visit (it is kept while the inbox is on the back stack), so
   the opening rule ran only the first time. It now runs every time the chat is shown.
5. **No notifications in the shade overnight.** Not swiped by me. The likely cause is my
   own doing: I started PingMe on the phone at 12:43 AM for a log capture and it stayed in
   the foreground all night (the phone's "stay awake while charging" keeps the screen on),
   and by the Gate G3 rule a message arriving while PingMe is on screen makes its sound
   and puts nothing in the shade. Not a code change; noted so the owner can decide whether
   that rule should also require the screen to be in use.

- **Pictures were not pinchable** in the full-screen viewer (owner, 2026-10-04): the viewer
  only ever showed a picture fitted to the screen. It now zooms with two fingers between
  fit and five times, pans with one finger while zoomed, and a double tap toggles between
  fit and twice the size.

- **Network colours "did nothing"** (owner, 2026-10-04, Instagram). Tried on the emulator:
  the pick is saved and both swatches change, so the mechanism works. What misled was the
  swatches themselves: the bubble swatch showed the flat base colour while the chat draws
  the gradient style from it (so it looked orange-red, not maroon), the badge swatch showed
  the full colour while rows draw the badge as a faint tint of it, and neither said which
  was which. The two circles are now a small real bubble in the current style and the real
  badge, labelled "Bubble" and "Badge". The picker sheet scrolls, so Done is reachable on
  any screen. Note: a bubble colour also sets the badge colour unless the badge has its
  own; that is by design and now visible.

- **Gradient and badge colours** (owner, 2026-10-04): the gradient bubble now runs from
  the bubble colour to the badge colour, the two swatches in Appearance, with no hidden hue
  shift (it used to turn forty degrees toward orange, which is why an Instagram bubble read
  as orange-red while its swatch was wine red). Badges are drawn in the first swatch's colour, the
  bubble colour, with readable text on it, not a faint tint; the second swatch is the
  gradient's end and the header accent.

### Owner note on Messenger (2026-10-04, 12:04 PM)

**No Messenger chats in PingMe at all**; the Messenger filter held one stray chat, "Clay
Rhodes, Messenger user", with no messages. From last night's phone log: Messenger signed
in and handed over fifteen conversations on the first page, yet none reached PingMe. The
Go bridge's own log said nothing more, and the Kotlin side logs only failures, so the
cause was found by reading: when Facebook re-sends the inbox it puts a "delete this
thread" row and a "here is this thread" row for the same conversation in one batch. The
reference bridge ignores the delete in that case ("Ignoring LSDeleteThread for thread that
has active upserts in the same sync"); PingMe's bridge did the opposite, honoured the
delete, skipped the insert, and told the app the chat was gone, so every conversation
Facebook listed that way vanished before it was ever stored. The one chat that survived
was simply one Facebook had not re-sent. Fixed in the bridge: a delete row for a thread
the same batch also upserts is not a deletion; a lone delete still is. A bridge test
reproduces the batch. The bridge now logs, per batch, how many thread rows it saw, how
many were deletions, and how many were re-insertions, and the connector logs who it
signed in as and how many chats it listed, so the next phone log settles it instead of
reasoning. Still to confirm on the phone: that the chats appear with names, and who
"Clay Rhodes" is (its members and the account's own id will be in the log).

Also seen in that log and not fixed: Messenger's thread rows no longer match the
library's table layout from column 39 on (the library warns "Failed to set" seventeen
times per thread). The columns PingMe reads (time, name, picture, key, type, folder) sit
before the break, and the newest library release and its main branch carry the same
layout, so there is nothing to update to; noted in case a later field is wanted.

**Confirmed on the phone (2026-10-04, 2:03 PM)**: the first batch after sign-in carried
29 conversations, every one as a delete-plus-insert pair; the old bridge had dropped all
29. The Messenger list now shows the same people as the Messenger app (Brett Parker, Toni
Botting, Don Aiken, Cara Orr Amos, ...), 45 chats after two pages. Two further findings,
not fixed, waiting on the owner:

1. **Every Messenger chat says "No messages yet", and one-to-one names carry an extra
   "Messenger user".** Facebook has moved personal one-to-one Messenger chats to
   end-to-end encryption carried over the WhatsApp protocol. The web inbox still lists
   each chat (which is what PingMe reads), but under a thread key that is no longer the
   other person's id (hence the phantom member), and the messages themselves never pass
   through the web inbox at all: they travel on a separate encrypted channel. The
   reference bridge registers an encryption device with Meta on first use, keeps the
   keys in a whatsmeow store on disk, runs a second whatsmeow client against Messenger's
   servers for those chats, and maps each inbox thread key to its encrypted-channel id
   through the mapping rows Facebook sends. PingMe's bridge does none of that, so it
   gets names but no messages, and could not send to those chats either. Groups that
   are not encrypted still work the old way. Building the encrypted channel is the
   reference's path, roughly 1,300 lines there (device registration, the client, the
   message conversion); PingMe already carries whatsmeow and a device store for
   WhatsApp, so the shape exists. Estimate: one long session. This is a change in the
   network since Phase 6 was built, not a Phase 7 item; the owner decides whether to do
   it now or after Gate G11. **Owner's decision (2026-10-04, 2:20 PM): after Gate G11.**
   It is the first item of work once the gate passes, before Phase 8.
2. **A chat titled with the owner's own name** ("Clay Aiken") is most likely Messenger's
   message-yourself thread (the reference bridge treats a one-to-one thread with
   yourself as "note to self"). "Clay Rhodes" from the morning no longer shows on the
   first screen; to be checked once names are right.

### Owner notes at Gate G11 (2026-10-04, 2:10 PM): colours and unmerging

1. **"Changing the per-network colours still does nothing."** Reproduced on the phone
   with the screen under my control: picking the teal quick swatch for Instagram and
   pressing Done did save, and the bubble swatch did change, but to a dark greyed teal
   rather than the teal picked. The palette kept only the hue of a pick and replaced its
   lightness and colourfulness with the theme's fixed tones (tone 30 for a dark-mode
   bubble, tone 80 for a badge), so a pick near the network's own hue, or a change of
   lightness alone, showed nothing at all. Now a pick is used exactly, in light and dark
   mode alike, for the bubble and for the badge; the text on it is black or white,
   whichever reads better. The test pick was reset afterwards. Seen while there and not
   touched: the studio's contrast warning lists every network at about 1.4 to 1, which
   cannot be right for the bubbles on screen; to look at next.
2. **Unmerging.** Chat details already had a split button per member, but as a bare
   icon beside the call buttons, and the owner did not find it. Each member now has a
   labelled "Remove", and an "Unmerge all" button beside "Add a chat…" dissolves the
   whole merge and returns to the chat list.
3. **A merged chat opened on Google Messages from the WhatsApp list though its unread
   message was on WhatsApp** (2:40 PM; from All it opened right). Not the list's doing:
   a race on the first visit to a chat since the app started. The screen marks the chat
   read as soon as it shows, and that marking fans out to every member; the opening rule
   waited for the member list flow to load before looking at the members' unread counts,
   and on a first visit the marking won, so every count was zero and the rule fell back to
   the default network. On a later visit the view model was still alive with the members
   loaded, so the rule won, which is why All "worked". The rule now asks the store for the
   members directly, and the read marking waits until the rule has decided. A test opens
   a merged chat for the first time with one unread member and checks the network chosen
   and that the members are marked read only afterwards. (Its first version reported the
   chat visible twice, itself and through the screen, so the rule ran again after the
   marking and failed on GitHub; the screen alone reports it now, three local runs green.)

### Owner note at Gate G11 (2026-10-04, 3:15 PM): colour numbers "do not stick"

The owner set every network's bubble to colourfulness 65 and lightness 30, and every
gradient end to 50 and 60, and found the numbers different on reopening. Measured with
the colour library rather than guessed: lightness holds everywhere; colourfulness 65 at
lightness 30 is more colour than a phone screen can show for the green and blue hues, so
the strongest displayable colour is kept and the slider reopens at it: WhatsApp 42,
Google Voice 30, Signal 55, Telegram 35 (Messenger and Instagram reach 65). The gradient
end holds at 50 for all but Google Voice (46). Nothing was lost in saving; the picker
was silent about the limit. Now the colourfulness slider stops at what the screen can
show for the current hue and lightness and says so ("42 of 42 the screen can show
here"), so the number set is the number kept.

### Messenger's encrypted one-to-one chats (2026-10-04, afternoon; owner: "Do it now")

Built ahead of the gate at the owner's word. Messenger moved personal one-to-one chats to
end-to-end encryption carried over the WhatsApp protocol. The bridge now does what the
reference bridge does:

- **A device registered with Meta once.** On the first connect after this build the
  bridge registers the phone as an encrypted-chat device (the web page's crypto token
  signs the request) and keeps the keys in a store at `files/messenger-keys/<account>.db`,
  opened with the same SQLite driver as WhatsApp's. If Messenger later forgets the device
  (connect failures 401, 415, 418), the bridge deletes it and registers again, twice at
  most, then reports the failure in the log.
- **A second client against Messenger's servers**, started once the web socket is up,
  through the library's own `PrepareE2EEClient`. Its messages, reactions, edits, unsends,
  read receipts, and typing become the same events the web tables give, so the Kotlin
  side is unchanged apart from the key store path and an "e2ee" state event for the log.
- **Thread identity.** The web listing shows an encrypted chat under a thread key that is
  not the other person's id; a mapping row ties that key to the chat's id on the channel
  (the other person's id for a one-to-one chat). The chat is now shown under that id, so
  the member is the person and the phantom "Messenger user" is gone. The old web-key chat
  is reported gone, which also clears the empty rows from before this build. A message
  for a chat the listing has not named yet makes the chat on the spot, and the mapping
  folds it in when it arrives.
- **Sending** on an encrypted chat goes over the channel: text and replies, pictures,
  videos, GIFs (as a video that plays as one, as Messenger wants), voice notes, files,
  stickers, reactions, edits, unsends, read marks (by sender, as the channel wants, plus
  the web read mark so the inbox agrees), and typing.
- **Receiving:** text, pictures, videos, voice notes, files, stickers, places (as a map
  link), shared links and cards, and view-once media as ordinary media. Attachments are
  fetched and decrypted on demand through the bridge by a handle in the media address.
  A contact card, a picture set, and a view-once card on Messenger's own wrapping show
  as "open it in Messenger".
- **No history.** Encrypted chats have no history on the server; only what arrives after
  connecting is seen, and the chat says it has nothing older. Not a choice: the protocol.

Bridge tests cover the mapping, a channel message under the mapped thread, a chat made
before its listing and folded in, reactions, edits, unsends, and media wrapping.

**On the phone (4:31 PM):** the registration went through on the first start ("ICDC
registration successful", device 833141165:76), the channel authenticated and connected
within ten seconds of sign-in, and the Messenger list shows plain names (Brett Parker,
Toni Botting, Don Aiken, ...) with no "Messenger user" and none of the old empty rows.
Every chat says "No messages yet". Sending and receiving on the channel wait for the
owner's test: a message in from someone, and one out from PingMe.

**Owner, 7:03 PM: "You haven't put the Messenger messages into the chats"**, with the
Messenger app showing unread messages in those same chats. Those messages were encrypted
for the devices the account had when they were sent; PingMe's device did not exist until
4:31 PM, so the server has nothing for it (the channel delivered zero waiting messages on
connect, and the web tables carried no message rows for those threads). That is what
end-to-end encryption means, and it covers the current unread ones too. One thing left
to try, added here: when an encrypted chat is opened, the bridge asks the web side once
for its history under both of its ids (web key and channel id), as the reference bridge
does, and logs how many messages came back. If Facebook still serves the messages from
before the chat went encrypted, they appear; if not, the only route left is Messenger's
"secure storage" backup (PIN), which neither the library nor the reference bridge reads.

- **The box's placeholder** ("Send a Google Message") wrapped to two lines and grew the
  box (owner, 3:38 PM). It is one line now and shrinks to fit, down to 11sp.

**Owner note (3:40 PM), queued after this:** merge suggestions should treat a phone
contact with a number as a Google Messages chat to merge with, even when no chat with
that number exists yet.

### Owner notes of 2026-10-04, evening (Instagram chats vanishing; contacts as text chats)

1. **Instagram chats vanish from the inbox** (7:03 PM: Jake, Grégory Ellis, theevandiaries;
   7:08 PM: Kameron Michaels, right after the owner reacted to a message there; Jake's
   and Grégory's last events were reactions too). The phone's log buffer for those
   minutes held no PingMe lines, and the live capture started at 7:08 PM caught nothing
   either: the removal path logs nothing yet. Read through: nothing on the reaction path
   deletes a chat, so the chat must be leaving the inbox query another way (a folder
   move to General or Requests, a merge, an archive, or a removal the network asked
   for). This build writes each of those to the log: every chat removed at a network's
   word, every folder change on a listing, every message dropped for a hidden chat, and
   every Instagram folder-move or thread-gone event. The next vanishing names its cause.
   **Then the owner searched: "Jake" was found, so the chat was stored and not deleted.**
   Found by reading with that in hand: when a network re-lists a thread, the stored
   chat took the listing's time as its own, and Instagram re-sends a thread with a stale
   time after a reaction or a folder change; the chat sank to that old place in the list,
   far below the fold, which reads as vanishing. A listing now never moves a chat
   earlier than it already is, and a reaction lifts the chat to the reaction's time, as
   Instagram's own list does ("Liked a message · 6m" sits at the top there). Test added.
2. **Contacts as Google Messages chats to merge** (3:40 PM; "fix everything", 7:05 PM).
   A phone contact with a number now counts as a Google Messages chat before any text
   has been sent to it. Suggestions: a chat, or a cluster, with no Google Messages member
   gets the contact whose card is the person's, or whose name reads the same, offered as
   its text chat; a chat on its own gets such a suggestion too. The two pickers (Settings
   > Merge chats, and Chat details) list every contact number as a Google Messages chat.
   On merge the real chat is started first (the Google Messages connector opens a
   conversation to the number), then merged. Offers are read from the address book with a
   one-minute cache. Tests cover offers by card and by name, and none where a text chat
   already exists.

### Owner note (2026-10-04, 7:29 PM): a nameless Messenger chat after a story reply

The owner replied to a story from Messenger itself; PingMe showed a chat with no name, a
plain person mark, and two bubbles carrying "You replied to Joshua's story". Two faults
in the new channel code: a chat the channel started before the web listing named it was
filed under nobody when the first message was the owner's own (only the sender was
looked at), and a story reply was shown as its card alone. Now a one-to-one chat made
from the channel always has the other person as its member (the chat's id on the channel
is their id), named from the contact rows, or fetched from the web by id and announced
again; and a story reply shows the reply's own text with "Reply to a story" as the card.
Tests cover the naming.

**On the phone (7:56 PM build):** the channel reconnected with the registered device in
six seconds; the nameless chat became "Joshua Raifman" from the web listing; his reply
"Thank you clay :)" arrived over the channel under his name (the first message received
on it); and his chat shows "Happy birthday" from April 2022, so the web listing does
still carry the messages from before a chat went encrypted, and the mapping folds them
in. The web history request itself never fired: it sat on the "older messages" path,
which only runs when scrolling up, not on the first page. Moved to the first page, so
every encrypted chat asks once when opened or backfilled. Also seen: Grégory Ellis's
Instagram chat "moves GENERAL -> PRIMARY" on the listing, so General misfiling was a
second way chats left the list; the folder-event log line will name what filed it there
the next time. The Telegram lines "Removed chat ... at the network's word" at start are
the old "joined Telegram" notice-only chats being cleared, by design.

### The 7:56 PM build froze the app (owner, 8:20 PM: "No accounts. No messages. Keeps crashing")

No crash: "PingMe isn't responding", first at 7:57 PM, one minute after that build
started, then on every touch. The contact-offer matching ran on the main thread and
compared every chat against every contact with the name normalisation done afresh for
each pair, on every change of the chat list; with hundreds of chats and a thousand
contacts the main thread never came back, so the screen showed the empty first state
("No accounts"). Put the 4:31 PM build back on the phone at 8:22 PM so it works again;
killed the pending build that carried the same code. Fix: the offers are indexed once by
contact card and by name key, so each chat's lookup is a map read, and the suggestion
flow runs off the main thread. The next build carries everything from the evening plus
this. Lesson written down: anything that scales with the address book runs off the main
thread and is indexed, never nested.

### The 8:25 PM build on the phone (2026-10-05, 4:33 AM)

Installed once wireless debugging came back; started by me, with PingMe brought to the
front and looked at: Connected, every network listed, chats and messages present, no
"not responding" in its first minutes. The freeze is gone. What could not be read: the
Messenger history answers, because the phone's log buffer is 256 KB and the Messenger
library's column warnings (seventeen per thread, hundreds per listing) fill it and make
the log client drop lines ("liblog: 44" in the log is 44 dropped). Brett Parker's and
Toni Botting's chats still show nothing after opening. Two changes for the next build:
the library's own log goes out at error level only (PingMe's lines stay at info), and the
connector logs how many messages the first page of each Messenger chat returned. The
phone's log buffer was also raised to 8 MB for the session (until reboot).

**The 4:42 AM build (5:05 AM): the history question settled.** With a readable log, the
bridge asked the web side for the history of every encrypted Messenger chat under both
of its ids, 52 requests; every one answered zero messages, none refused. The three
Messenger chats with messages are the ones whose listing carried them. So the messages
sent before PingMe's device existed cannot be fetched by any route PingMe has; only
Messenger's "secure storage" backup (PIN) holds them, and no library reads it. New
messages on those chats arrive and replies go out. Also confirmed on that start: no hang,
the channel up in seven seconds, PingMe's own lines all present in the log.

### Owner notes of 2026-10-05, 5:17 AM: Instagram reactions refused, reads not marked

Both from one cause. The Instagram connector keeps an in-memory note of every message it
has seen since the app started, and used it to find a message's thread (for a reaction,
unsend, or edit) and its time (for a read mark). After a restart that memory is empty,
so reacting to any message from before the start was refused with "Wait for the message
to finish sending", and a read mark for such a message was dropped without a word, so
Instagram kept the chat unread. Every build today restarted the app, so every older
message hit this. Now a message id carries its thread, so reactions, unsends, and edits
need nothing remembered; and a read mark for a message not seen this session fetches a
page of its thread to learn its time (falling back to now). WhatsApp and Signal share the
refusal wording but repopulate their memory from the history the network sends on
connect; left alone until seen on the phone.

**6:06 AM build on the phone:** reactions on older Instagram messages go through (owner
confirmed). Read marks still do not reach Instagram. The log of the minutes spent
reading shows no read-marker line at all, neither a failure nor a refusal, and the mark
call matches the library's shape, so most likely no mark was sent: the one thing that
stops it before sending is PingMe's own "Send read receipts" switch, which was off for
Instagram at Gate G7. Asked the owner to check Settings > Privacy. The next build logs
each read marker sent, and each held back by the switch, so the log names it.
**Owner: the switch has always been on.** Then the path is the only other one: PingMe
told the network about a read only when the chat still counted as unread in PingMe. A
chat PingMe had already read before this morning's fix, when Instagram's marks were
being dropped, was never told again, so Instagram kept it unread for good. Now opening
a chat tells its network it is read whether or not PingMe still counts it unread (a
merged chat tells each member's network); the unread case keeps its existing path.
**On the phone (6:50 AM build):** opening a chat logs "Read marker sent to INSTAGRAM for
..." with no failure. **Owner, 7:30 AM: typed messages now read in Instagram; a reaction
to one of the owner's messages does not.** The mark named the newest message and its
own time as the watermark, and a reaction that came after it sits past that time. The
watermark is now the time of reading, so whatever came before the open counts.
The hex box build (6:46 AM push) installed on the phone at about 7:25 AM.

### Owner note (2026-10-05, 6:25 AM): hex codes for any colour

Every colour picker in Appearance (seed, manual palette, network bubble and badge, chat
wallpaper, and the per-chat bubble colour, which all share the one sheet) has a "Hex
code" box above the sliders. A typed #RRGGBB (with or without the #) sets the colour
exactly and is kept exactly on Done; the sliders move to its nearest hue, colourfulness,
and lightness; moving a slider or tapping a quick swatch takes over from the typed code.
The box shows the current colour's code at all times and marks itself when the text is
not a code yet. Test: a typed code for WhatsApp's bubble is stored exactly.

### Gate G11: the owner's checklist

Built on the `phase-7` branch. Rewritten 2026-10-04, 2:30 PM, to match what is built
after the owner's rounds of notes (the first version named a banner and chips that no
longer exist). Please check, in this order:

1. **Contacts.** Allow contacts if asked. Chats with people in your address book show
   their contact name and photo: inbox rows, pinned tiles, the chat header, Chat
   details, the new-chat list, and the sender's picture on a notification. A one-to-one
   chat that was titled by a bare number takes the contact's name. Someone known only
   by a number shows a plain person mark, not digits. Your own number never names a chat.
2. **Pictures.** Instagram and Messenger chats show the person's profile picture. A
   group's avatar is built from its members' pictures: two side by side, three in a
   triangle, up to nine in a ring, ten or more as the multicoloured asterisk, you left out.
3. **Unread and merged rows.** An unread chat has the orbiting ring, on rows and on
   pinned tiles, with the row tint; no dot anywhere. A merged row shows no network
   badges. Avatars are the same size everywhere, merged or not.
4. **Settings > Merge chats.** "Merge chats you pick" opens a picker of one-to-one chats
   with each person's number or username; pick two or more on different networks and
   merge. "Suggestions" shows the count and opens the suggestions screen. On a card: the
   reason is named (same contact, same number, or similar name); leaving a chat out,
   adding any chat from the picker, and dismissing each do what they say; you can pick
   which network sends by default and which picture the merged chat uses; Merge makes
   one chat. A dismissed suggestion stays gone. Nothing about merging appears in the
   inbox uninvited.
5. **Merge from the inbox.** Hold an avatar, pick chats on different networks, tap Merge
   in the toolbar. One row appears with the newest message across all of them. Mark it
   read: every network is read. Mute, archive, pin, and delete act on the whole. In Chat
   details of an ordinary chat, "Merge with…" offers the other one-to-one chats.
6. **Network filters.** Under a network's button a merged chat appears only if it has a
   message on that network, placed by that message's time, and shows that network's
   newest message. Under All it is placed by its newest message anywhere.
7. **The merged chat.** Bubbles keep their network colour only; tap one and the network
   badge sits beside the time and ticks. The header badge opens a dropdown: All or one
   network. One network narrows the bubbles and sets the box; All brings everything
   back. The badge inside the box does the same thing as the header. The box says "Send
   a WhatsApp message", "Send an Instagram DM", and so on. Send on each network and
   confirm it arrives there. GIFs are in the + menu. Opening rule: unread messages all
   from one network open the chat on that network; otherwise it opens on the default
   network, never on All.
8. **Chat details of a merged chat.** The member chats are listed with their number or
   username; tapping one sets the default network; the split button returns that chat
   to the inbox on its own; "Add a chat…" works; the phone and video buttons on each
   member call on that network (say what each one did: dialled at once, opened the
   app, or nothing).
9. **Bottom bar.** Four chosen buttons and More, line icons, long labels wrapping to two
   lines. More lists the rest with unread dots; picking one opens the inbox on it. The
   editor allows four.
10. **Appearance.** Each network's row shows the real bubble and the real badge. The
    first swatch is the bubble and badge colour; the second is the gradient's end and
    the header accent. Changing a colour changes the chat.
11. **Pictures full screen.** Pinch to zoom, drag while zoomed, double tap to toggle.
12. **Instagram.** Only the last 30 days of Primary are listed. General folder chats
    stay out of All and under the General list. A video taken in the chat and kept
    plays as an ordinary video.

Known gaps, not hidden: Messenger one-to-one chats hold no past messages (the encrypted
channel is built; Messenger keeps that history only in its own secure backup); how a
merged row is marked is still the owner's call; the demo network cannot show a merge on
the emulator; whether Google Voice, Messenger, and Meet dial at once or open the app is
an open question until tried on the phone.

### Owner notes of 2026-10-05, 8:40 AM: photos, the box badge, the diagnostic file, the merge tap

The owner: "The Whatsapp, telegram, and signal profile photos.... that's your
responsibility. Why have you not already done it?" Done in this step, all three:

- **WhatsApp.** After the contact list arrives, the session asks WhatsApp for the profile
  picture of each one-to-one chat partner that has none yet (up to 80 a connection, one
  request at a time, the small preview size) and reports the people again with the
  picture's address every ten found. People who hide their picture are simply left as
  they were. Pictures are kept by the app's avatar saver like Instagram's.
- **Telegram.** Each user Telegram tells the session about has their small profile photo
  downloaded once (two at a time), copied beside the account's other files under
  `avatars/<user id>.jpg`, and the person reported again with the path. Telegram's own
  store keeps the original.
- **Signal.** The Go bridge writes the contact list's picture for each person beside
  the account's store under `avatars/<id>.jpg`, and when a person has only a profile
  picture it fetches and decrypts that in the background, then reports the contact
  list again. Members carry an `avatar` field the Kotlin side keeps.

"The box badge marking a disconnected account.... I don't even know what that means."
It is the rule in UI_DESIGN.md 10.15: in a merged chat the badge inside the box names
the network the message goes on, and when that account is disconnected the badge gets
a red ring and the box says "WA is not connected; the message will wait". Built now,
for merged and plain chats alike.

"The Instagram general misfiring is something you were supposed to fix yourself
already." The fault has not shown itself while the log was being read, and the phone's
log is gone by the time debugging is on. So PingMe now keeps its own small diagnostic
file on the phone: `Android/data/org.pingme.app/files/diag/pingme.log`, half a
megabyte and the one before it,
holding chat removals, folder moves, dropped messages, read markers, and Instagram's
folder and thread-gone events. Nothing leaves the phone (DESIGN.md 6.5). When the
misfiling happens again, the owner turns wireless debugging on at any later time and
the file says which event moved the chat. Added as `Diag` in the connector API module
so every connector can write to it.

"When I accept a merge suggestion by tapping 'merge' I get the error 'pick at least two
chats to merge'... go back to tap 'merge' again, it goes through fine." A contact-only
suggestion starts a Google Messages chat for the number first, and the merge ran before
the chat row was stored. The merge now waits for the row (up to five seconds) before
joining, so the first tap goes through.

Test on the phone: pictures appear on WhatsApp, Telegram, and Signal rows within a
minute of connecting (people with hidden pictures keep their initials); a merge
suggestion merges on the first tap; turning Wi-Fi off and opening a chat shows the red
ring and the "not connected" hint in the box.

### Owner notes of 2026-10-05, 8:29 and 8:41 AM: unviewed view-once photos shown as gone; no Instagram typing

"These are all ephemeral photos that are still unviewed in Instagram. PingMe has not
populated them at all... And it obviously has not made them permanent as it's supposed
to." Four view-once photos from one person read "A view-once photo or video that
Instagram no longer shows" while Instagram still had them unviewed.

What the code says: the bridge calls a view-once message gone whenever Instagram's copy
of it carries no attachment, which is also how the reference bridge (mautrix-meta
v0.2609.0) reads it. Two things were wrong on PingMe's side and are fixed here:

- **A held file was being thrown away.** When Instagram hands the same message back
  later without its attachment (the start-up listing, or the page fetched to mark the
  chat read), the service overwrote the stored message wholesale: the picture PingMe
  had already fetched, and its kind, were replaced by the "no longer shows" line. Now
  a message that already holds a view-once file keeps its file, kind, and body when a
  copy without attachments arrives.
- **Nothing recorded what Instagram actually sent.** The library's "unknown fields" map
  turned out to stay empty (its tag is not one the JSON parser honours), so the bridge
  now keeps the whole live event (up to 6,000 characters) on a file-less view-once
  message, and the connector writes it, and the message's content type, to the
  diagnostic file: "View-once without a file (live|listed) thread=… id=…: …". If
  Instagram delivers the bytes some other way for an unviewed photo, the next one will
  show it, and the bridge can be taught to fetch it. If the record shows nothing but
  the view mode, Instagram is not giving the web client the file, and the design table
  in UI_DESIGN.md 10.16 will be corrected to say so.

"Instagram is also not showing typing indicators in PingMe, even though they do show in
the Instagram app itself." The typing stream names the thread by its long id (the
24-digit one), while PingMe's chats go by the short thread id, so every typing event
landed on a chat that does not exist. The bridge now maps a known long id to its short
one before reporting typing. A typing event for a thread PingMe still does not know is
written to the diagnostic file so the cause is visible if it is something else.

Test on the phone: someone typing in an Instagram chat shows the dots in PingMe; a new
view-once photo either shows (and stays) or leaves a "View-once without a file" line in
the diagnostic file for me to read (`adb pull
/sdcard/Android/data/org.pingme.app/files/diag/pingme.log`; the 8:45 AM build wrote it
to private storage, which debugging cannot read, so this build moves it).

### Owner notes of 2026-10-05, 9:07 and 9:09 AM: "pin up to 12" with eight pinned; the contrast warning

"Attempting to pin another conversation to the top of the inbox gave me this error"
(with eight pinned tiles on screen). The pin limit counted every chat row with the pin
flag, including rows that show nowhere: a chat folded into a merged chat keeps its flag
(the merged chat takes the pin), and a chat in Requests or General is not in the grid.
Four or more such rows filled the twelve. Now only pins that show in the grid count.

"Get rid of this big ass warning. I know what I can see or not see using my own eyes."
The red "Some text will be hard to read" card in Appearance is gone, and UI_DESIGN.md
7 and 10.1 now say the preview is the check and there is no contrast warning. The
contrast arithmetic stays in the theme module (black-or-white text on a picked colour
still uses it); only the card is removed.

### Owner note of 2026-10-05, 9:14 AM: unread on the bottom bar by label colour

"Instead of placing that little dot on an icon when a network has an unread message...
make the font of the title of that network display in the color assigned to the network
in appearance settings... If there are unread messages in one of the networks/spaces
that overflow into the More section, print the word 'More' in the current color of the
phone's theme." Done: the dots are gone from the bar, the rail, and the list behind
More. A network's label takes its bubble colour from Appearance while it has unread
messages; All, Unread, a space, and Low priority take the theme's primary colour (a
space has no network colour, so it takes the theme's, the same as More); More takes the
theme's primary colour when anything behind it is unread. UI_DESIGN.md 3.1 updated.

### Builds on the phone (2026-10-05)

- 8:45 AM: profile pictures, the box badge, the diagnostic file (private storage), the
  merge first tap.
- 9:32 AM: the held view-once file, Instagram typing by short thread id, the diagnostic
  file under Android/data.
- 9:55 AM: bar labels coloured by network when unread, the pin limit counting only
  visible pins, the Appearance contrast warning removed.

Next: the owner's Gate G11 checklist (above) plus these: typing dots in an Instagram
chat; a new view-once photo (shows and stays, or leaves a "View-once without a file"
record for me to pull); pinning a ninth chat; the bar's coloured labels; the merged-row
mark is still the owner's call. Phase 8 waits for the gate.

### Owner note of 2026-10-05, 10:15 AM: chats missing from the Instagram inbox

"Why are there still messages missing from the Instagram inbox?" Five of Instagram's
top rows (Daniel Waynick, ericbellmoves, Thayne Jasperson, Kevin Wiltz, parker) were not
in PingMe's Instagram list while the rows around them were.

From the diagnostic file (first pull, 10:15 AM) and the code: at start-up the bridge
hands over the first mailbox page with each thread's newest messages; every later page
is listed through the sync as bare chats, and their messages were thrown away. So a
chat whose last messages arrived while PingMe was closed had no stored message at all.
A plain chat still shows (with "No messages yet"), but a merged chat is shown under a
network filter only when its member on that network holds a message, so the merged
ones vanished from the Instagram list. The rows that did show all had messages from
live events or the owner's own sends. Fixed: every listed page now carries its threads'
newest messages into the store.

The same pull showed six chats moving Requests to General one after another during the
inbox listing (Kris Wojciechowski, Britt Pauline, Yoshi's, Justin Hammond, Tracie, Kayla
Edie Mora), which is the General misfiling the owner has been reporting. The request
listing files them as Requests; a later listing files them as General. The diagnostic
file now records, for every thread not filed as Primary, the three folder fields
Instagram sent and where the thread came from, so the next pull names the field that
flips. Not yet fixed: the fix needs those fields.

Also seen: one view-once video listed with nothing but "RAVEN_VIDEO" as its content
type and no message id; the live form is still to be caught.

### Owner note of 2026-10-05, 10:45 AM: who placed a reaction in a group chat

"Someone else in the group chat reacted to Terry's message with a thumbs up. But I can't
see who reacted... Probably something like tapping on the reactions area to reveal all
reactions and who placed them." Done: the reaction chips under a bubble are a button
("See who reacted"); a tap opens a sheet listing every reaction with the emoji, the
person's name, and the time, newest first. The owner's own reactions read "You";
someone PingMe has no name for reads "Someone". UI_DESIGN.md 3.2 updated.

### CI, 2026-10-05, 11:03 AM: the WhatsApp contract test read an empty first page

The reactions build failed one CI run (the other passed) on the WhatsApp contract test
that pages history backwards: the first page came back empty. Right after connecting,
the session waits up to ten seconds for the history sync to land before answering a
first page, but that wait ran on the test clock, which skips delays, so the page was
read before the fake's history had been processed. The profile-picture fetch added this
morning runs beside the event loop and widened that window. The wait now runs on the
real clock, and the picture fetch runs on its own thread pool. Three local runs of the
WhatsApp tests in a row pass.
