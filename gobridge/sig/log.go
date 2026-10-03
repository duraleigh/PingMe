// SPDX-License-Identifier: AGPL-3.0-or-later

package sig

import (
	"os"
	"strings"
	"sync"

	"github.com/rs/zerolog"
)

var (
	logLevel = zerolog.InfoLevel
	logLock  sync.RWMutex
)

// SetLogLevel sets the level for every logger made after the call ("debug", "info", ...).
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
	writer := zerolog.ConsoleWriter{Out: os.Stderr, NoColor: true, TimeFormat: "15:04:05"}
	return zerolog.New(writer).Level(level).With().Timestamp().Str("component", component).Logger()
}
