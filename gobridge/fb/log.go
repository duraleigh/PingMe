// SPDX-License-Identifier: AGPL-3.0-or-later

package fb

import (
	"strings"
	"sync"

	"github.com/rs/zerolog"
	waLog "go.mau.fi/whatsmeow/util/log"

	"pingme.org/gobridge/alog"
)

// messagix logs through zerolog; the console writer lands in logcat under
// "GoLog" (alog). Nothing leaves the phone (DESIGN.md 6.5).
var (
	logLevel = zerolog.InfoLevel
	logLock  sync.RWMutex
)

// SetLogLevel takes "trace", "debug", "info", "warn", or "error".
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

func waLogger(component string) waLog.Logger {
	return waLog.Zerolog(newLogger(component))
}
