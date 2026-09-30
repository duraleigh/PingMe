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
