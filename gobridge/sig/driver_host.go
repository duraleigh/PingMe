// SPDX-License-Identifier: AGPL-3.0-or-later

//go:build !android

package sig

import (
	"database/sql"

	"modernc.org/sqlite"
)

func init() {
	// The WhatsApp package registers the same name when both are built together.
	defer func() { _ = recover() }()
	sql.Register("sqlite3", &sqlite.Driver{})
}

func storeAddress(dbPath string) string {
	return "file:" + dbPath + "?_pragma=foreign_keys(1)&_pragma=busy_timeout(10000)&_pragma=journal_mode(WAL)"
}
