// SPDX-License-Identifier: AGPL-3.0-or-later

//go:build !android

package wa

import (
	"database/sql"

	"modernc.org/sqlite"
)

// On a development machine (go test, go vet) the pure Go driver stands in under the same
// name, so nothing needs a C compiler there.
func init() {
	sql.Register("sqlite3", &sqlite.Driver{})
}

func storeAddress(dbPath string) string {
	return "file:" + dbPath + "?_pragma=foreign_keys(1)&_pragma=busy_timeout(10000)&_pragma=journal_mode(WAL)"
}
