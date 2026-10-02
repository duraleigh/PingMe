// SPDX-License-Identifier: AGPL-3.0-or-later

//go:build android

package wa

import (
	_ "github.com/mattn/go-sqlite3" // The C SQLite driver, built by gomobile with the NDK.
)

// On the phone the device store runs on C SQLite. The pure Go driver was tried first and
// crashed the app on x86_64 Android: its libc makes the raw stat system calls that the
// app sandbox forbids there (arm64 has no such calls, which is no reason to ship a guess).
func storeAddress(dbPath string) string {
	return "file:" + dbPath + "?_foreign_keys=on&_busy_timeout=10000&_journal_mode=WAL"
}
