// SPDX-License-Identifier: AGPL-3.0-or-later

package gm

import (
	"strings"
	"sync"

	"github.com/rs/zerolog"

	"pingme.org/gobridge/alog"
)

// libgm logs through zerolog. gomobile sends stderr to logcat under the "GoLog" tag, so
// one console writer there is enough; nothing leaves the phone (DESIGN.md 6.5).
var (
	logLevel = zerolog.InfoLevel
	logLock  sync.RWMutex
)

// SetLogLevel takes "trace", "debug", "info", "warn", or "error". Debug builds use
// "debug"; release builds keep "info".
func SetLogLevel(level string) {
	parsed, err := zerolog.ParseLevel(strings.ToLower(level))
	if err != nil {
		return
	}
	logLock.Lock()
	logLevel = parsed
	logLock.Unlock()
}

func newLogger(component string) zerolog.Logger {
	logLock.RLock()
	level := logLevel
	logLock.RUnlock()
	writer := zerolog.ConsoleWriter{Out: alog.Writer(), NoColor: true, TimeFormat: "15:04:05"}
	return zerolog.New(writer).Level(level).With().Timestamp().Str("component", component).Logger()
}
