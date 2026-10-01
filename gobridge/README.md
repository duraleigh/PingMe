# gobridge

The Go side of PingMe (BUILD_PLAN.md P3.1): protocol libraries written in Go, compiled
with gomobile into one Android library, `build/gobridge.aar`, which is never committed.

- `gm/`: Google Messages, over libgm from
  [mautrix-gmessages](https://github.com/mautrix/gmessages) (`pkg/libgm`). The surface is
  flat for gomobile: JSON strings in and out, one `EventSink.OnEvent(json)` callback for
  everything the phone sends. `gm/convert.go` documents the JSON shapes; the Kotlin
  mirror is `connectors/gmessages/.../bridge/GmJson.kt`.
- WhatsApp (whatsmeow) and Signal (signalmeow) packages join this module in their own
  phases (P5, P6).

## Building

`./build.sh` vets and tests the Go code, then runs `gomobile bind` for arm64, x86_64,
and arm. It keeps a hash of the sources beside the AAR and skips the bind when nothing
changed; `./build.sh --force` rebuilds anyway. Gradle runs it automatically: this
directory is the `:gobridge` module, whose `buildGoBridge` task runs the script and whose
one artifact is the AAR (`connectors/gmessages` depends on `project(":gobridge")`, since
the Android Gradle Plugin refuses a local `.aar` file inside a library module). So
`./gradlew check` needs:

- Go (the version in `go.mod`; `GOTOOLCHAIN=auto` fetches it),
- gomobile: `go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init`,
- an Android NDK, found under `$ANDROID_HOME/ndk` or at `$ANDROID_NDK_HOME`.

The first bind takes a couple of minutes; later ones are skipped unless Go code changed.
The Java package is `org.pingme.gobridge.gm`.

## Tests

`go test ./...` covers the proto-to-JSON conversion and writes nothing. `go test ./gm
-update` rewrites `gm/testdata/session.json`, the recorded session the Kotlin contract
tests replay (P3.3); run it after changing the JSON shapes, and check the diff.

## Logging

libgm logs to stderr, which gomobile forwards to logcat under the tag `GoLog`. The level
is `info` unless the app calls `Gm.setLogLevel("debug")`. Nothing leaves the phone.
