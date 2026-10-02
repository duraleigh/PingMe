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
