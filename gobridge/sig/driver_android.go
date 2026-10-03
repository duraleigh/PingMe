// SPDX-License-Identifier: AGPL-3.0-or-later

//go:build android

package sig

import (
	_ "github.com/mattn/go-sqlite3" // The C SQLite driver, built by gomobile with the NDK.
)

func storeAddress(dbPath string) string {
	return "file:" + dbPath + "?_foreign_keys=on&_busy_timeout=10000&_journal_mode=WAL&_txlock=immediate"
}
