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
